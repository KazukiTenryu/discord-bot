package bot.utils;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Resolves canonical track metadata (artist, album, cover art) from Apple's free, key-less
 * <a href="https://itunes.apple.com">iTunes Search API</a>. YouTube only gives us a channel name in
 * {@code author}; this searches for the cleaned-up title and returns the best match so the player
 * can show the real artist and album. All failures degrade to {@link Optional#empty()} — metadata
 * is a best-effort nicety, never a hard dependency.
 */
public class MetadataService {
    private static final Logger LOGGER = LogManager.getLogger(MetadataService.class);
    private static final HttpClient HTTP_CLIENT =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final String SEARCH_URL = "https://itunes.apple.com/search?media=music&entity=song&limit=1&term=%s";

    /** Canonical metadata for a track; any field may be {@code null} if the source didn't supply it. */
    public record TrackMetadata(String artist, String album, String title, String artworkUrl) {}

    /**
     * Looks up metadata for a track by its (often messy) title, falling back to {@code author} as a
     * search hint when the title is empty. Returns {@link Optional#empty()} on no match or any error.
     */
    public Optional<TrackMetadata> lookup(String title, String author) {
        String term = buildTerm(title, author);
        if (term.isBlank()) {
            return Optional.empty();
        }
        String encoded = URLEncoder.encode(term, StandardCharsets.UTF_8).replace("+", "%20");
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(SEARCH_URL.formatted(encoded)))
                .timeout(Duration.ofSeconds(8))
                .GET()
                .build();
        try {
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                LOGGER.warn("iTunes lookup for '{}' returned status {}", term, response.statusCode());
                return Optional.empty();
            }
            JsonNode results = OBJECT_MAPPER.readTree(response.body()).path("results");
            if (!results.isArray() || results.isEmpty()) {
                return Optional.empty();
            }
            JsonNode hit = results.get(0);
            String artist = blankToNull(hit.path("artistName").asString());
            String album = blankToNull(hit.path("collectionName").asString());
            String trackName = blankToNull(hit.path("trackName").asString());
            String artwork = upscale(blankToNull(hit.path("artworkUrl100").asString()));
            if (artist == null && album == null) {
                return Optional.empty(); // nothing useful came back
            }
            return Optional.of(new TrackMetadata(artist, album, trackName, artwork));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.error("Interrupted during iTunes lookup for '{}'", term, e);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("iTunes lookup failed for '{}'", term, e);
        }
        return Optional.empty();
    }

    /** Strips "(Official Video)"-style noise so the search matches the actual song. */
    private static String buildTerm(String title, String author) {
        if (title == null || title.isBlank()) {
            return author == null ? "" : author.trim();
        }
        String cleaned = title.replaceAll("[(\\[\\{].*?[)\\]\\}]", " ") // (Official Video), [HD], {…}
                .replaceAll("(?i)\\b(official\\s*)?(music\\s*)?(video|audio|lyrics?|visualizer|mv|hd|4k|hq)\\b", " ")
                .replaceAll("(?i)\\b(feat|ft)\\.?\\b", " ")
                .replaceAll("[|\\-–—]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return cleaned.isBlank() ? title.trim() : cleaned;
    }

    private static String upscale(String artworkUrl100) {
        return artworkUrl100 == null ? null : artworkUrl100.replace("100x100", "600x600");
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
