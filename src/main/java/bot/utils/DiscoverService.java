package bot.utils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Trending-music feed for the web player's Discover page, built from Apple's free, key-less charts
 * (the same iTunes ecosystem {@link MetadataService} already uses) so it works regardless of the
 * Spotify connection. Two sources: the modern marketing-tools "most-played" JSON for the overall Top
 * shelf, and the classic iTunes RSS top-songs feed (per genre) for the genre shelves.
 *
 * <p>Tracks carry only metadata (title/artist/artwork) — the player resolves each to a playable
 * YouTube source on demand (via {@code /api/search}), exactly like the Spotify import. The whole
 * result is cached for {@link #CACHE_TTL_MS} since charts move slowly, so the page is instant after
 * the first hit and we stay friendly to Apple's endpoints. Every fetch is best-effort: a shelf that
 * fails is simply omitted rather than failing the page.
 */
public class DiscoverService {
    private static final Logger LOGGER = LogManager.getLogger(DiscoverService.class);
    private static final HttpClient HTTP_CLIENT =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String DEFAULT_COUNTRY = "us";
    private static final int SHELF_SIZE = 20;
    private static final long CACHE_TTL_MS = 3L * 60 * 60 * 1000; // charts move slowly; refresh every 3h

    /** A trending track — metadata only; resolved to a YouTube source at play/add time. */
    public record DiscoverTrack(String title, String artist, String artworkUrl) {}

    /** A horizontal row on the Discover page. {@code accent} is a hue (0–360) for the card tint. */
    public record DiscoverShelf(String title, String key, int accent, boolean ranked, List<DiscoverTrack> tracks) {}

    // Apple genre ids → a display title and an accent hue, defining the genre shelves (in order).
    private record GenreShelf(int genreId, String title, int accent) {}

    // The full set of Apple music genres (id, shelf title, accent hue), in display order.
    private static final List<GenreShelf> GENRES = List.of(
            new GenreShelf(14, "Pop", 330),
            new GenreShelf(18, "Hip-Hop / Rap", 38),
            new GenreShelf(17, "Dance", 200),
            new GenreShelf(15, "R&B / Soul", 276),
            new GenreShelf(21, "Rock", 8),
            new GenreShelf(20, "Alternative", 168),
            new GenreShelf(7, "Electronic", 250),
            new GenreShelf(6, "Country", 28),
            new GenreShelf(12, "Latin", 348),
            new GenreShelf(51, "K-Pop", 312),
            new GenreShelf(1153, "Heavy Metal", 220),
            new GenreShelf(24, "Reggae", 130),
            new GenreShelf(11, "Jazz", 45),
            new GenreShelf(2, "Blues", 215),
            new GenreShelf(5, "Classical", 50),
            new GenreShelf(16, "Soundtracks", 285),
            new GenreShelf(19, "World", 95),
            new GenreShelf(13, "New Age", 180),
            new GenreShelf(22, "Christian & Gospel", 60));

    private record Cached(long at, List<DiscoverShelf> shelves) {}

    // One cache entry per storefront (country), so the Discover and Charts pages share the US data.
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    /** Discover shelves for the default (US) storefront. */
    public List<DiscoverShelf> discover() {
        return discover(DEFAULT_COUNTRY);
    }

    /**
     * Discover shelves for a storefront (e.g. {@code "us"}, {@code "gb"}, {@code "jp"}), rebuilt from
     * Apple at most once per {@link #CACHE_TTL_MS} per country. Never throws. The ~20 feeds are fetched
     * in parallel so a cold refresh takes about as long as the single slowest feed, not their sum.
     */
    public List<DiscoverShelf> discover(String country) {
        String cc = normalizeCountry(country);
        long now = System.currentTimeMillis();
        Cached hit = cache.get(cc);
        if (hit != null && (now - hit.at()) < CACHE_TTL_MS) {
            return hit.shelves();
        }
        return rebuild(cc, now);
    }

    private synchronized List<DiscoverShelf> rebuild(String cc, long now) {
        // Re-check under the lock so concurrent first-hits for the same country don't all fetch.
        Cached hit = cache.get(cc);
        if (hit != null && (now - hit.at()) < CACHE_TTL_MS) {
            return hit.shelves();
        }
        ExecutorService pool = Executors.newFixedThreadPool(8, r -> {
            Thread t = new Thread(r, "discover-fetch");
            t.setDaemon(true);
            return t;
        });
        try {
            List<CompletableFuture<DiscoverShelf>> futures = new ArrayList<>();
            // Index 0: the overall "Trending now" Top shelf (ranked); then one per genre, in order.
            futures.add(CompletableFuture.supplyAsync(
                    () -> shelf("Trending now", "top", 142, true, fetchTopSongs(cc)), pool));
            for (GenreShelf genre : GENRES) {
                futures.add(CompletableFuture.supplyAsync(
                        () -> shelf(
                                genre.title(),
                                "g" + genre.genreId(),
                                genre.accent(),
                                false,
                                fetchGenre(cc, genre.genreId())),
                        pool));
            }
            List<DiscoverShelf> shelves = new ArrayList<>();
            for (CompletableFuture<DiscoverShelf> future : futures) {
                try {
                    DiscoverShelf s = future.get(20, TimeUnit.SECONDS);
                    if (s != null) {
                        shelves.add(s);
                    }
                } catch (Exception e) {
                    LOGGER.warn("A discover shelf failed to load for {}", cc, e);
                }
            }
            if (!shelves.isEmpty()) {
                cache.put(cc, new Cached(now, shelves));
            }
            // If everything failed but we have a stale entry, serve that rather than nothing.
            return shelves.isEmpty() && hit != null ? hit.shelves() : shelves;
        } finally {
            pool.shutdownNow();
        }
    }

    /** Sanitises a storefront code to a safe 2-letter lowercase value, defaulting to {@code "us"}. */
    private static String normalizeCountry(String country) {
        if (country == null) {
            return DEFAULT_COUNTRY;
        }
        String cc = country.trim().toLowerCase();
        return cc.matches("[a-z]{2}") ? cc : DEFAULT_COUNTRY;
    }

    private static DiscoverShelf shelf(
            String title, String key, int accent, boolean ranked, List<DiscoverTrack> tracks) {
        return tracks.isEmpty() ? null : new DiscoverShelf(title, key, accent, ranked, tracks);
    }

    /** Overall most-played, via the marketing-tools JSON feed ({@code feed.results[]}). */
    private List<DiscoverTrack> fetchTopSongs(String country) {
        String url = "https://rss.marketingtools.apple.com/api/v2/" + country + "/music/most-played/" + SHELF_SIZE
                + "/songs.json";
        List<DiscoverTrack> out = new ArrayList<>();
        JsonNode root = get(url);
        if (root == null) {
            return out;
        }
        for (JsonNode r : root.path("feed").path("results")) {
            DiscoverTrack t = track(text(r, "name"), text(r, "artistName"), text(r, "artworkUrl100"));
            if (t != null) {
                out.add(t);
            }
        }
        return out;
    }

    /** Per-genre top songs, via the classic iTunes RSS feed ({@code feed.entry[]}). */
    private List<DiscoverTrack> fetchGenre(String country, int genreId) {
        String url = "https://itunes.apple.com/" + country + "/rss/topsongs/limit=" + SHELF_SIZE + "/genre=" + genreId
                + "/json";
        List<DiscoverTrack> out = new ArrayList<>();
        JsonNode root = get(url);
        if (root == null) {
            return out;
        }
        for (JsonNode e : root.path("feed").path("entry")) {
            JsonNode images = e.path("im:image");
            String art = images.isArray() && !images.isEmpty() ? text(images.get(images.size() - 1), "label") : null;
            DiscoverTrack t = track(
                    e.path("im:name").path("label").asString(null),
                    e.path("im:artist").path("label").asString(null),
                    art);
            if (t != null) {
                out.add(t);
            }
        }
        return out;
    }

    private static DiscoverTrack track(String title, String artist, String artworkUrl) {
        if (title == null || title.isBlank() || artist == null || artist.isBlank()) {
            return null;
        }
        return new DiscoverTrack(title.trim(), artist.trim(), upscale(artworkUrl));
    }

    /** Bumps Apple's small thumbnail (…/100x100bb.jpg or …/170x170bb.png) up to a crisp 480px. */
    private static String upscale(String artworkUrl) {
        return artworkUrl == null ? null : artworkUrl.replaceAll("/\\d+x\\d+bb", "/480x480bb");
    }

    private JsonNode get(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                LOGGER.warn("Discover feed {} returned status {}", url, response.statusCode());
                return null;
            }
            return MAPPER.readTree(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Interrupted fetching discover feed {}", url);
        } catch (Exception e) {
            LOGGER.warn("Couldn't fetch discover feed {}", url, e);
        }
        return null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asString(null);
    }
}
