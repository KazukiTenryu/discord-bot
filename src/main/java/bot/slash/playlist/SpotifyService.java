package bot.slash.playlist;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Talks to the Spotify Web API for the web player's import feature. Spotify only exposes track
 * metadata (no playable audio), so the import resolves each track to a YouTube source via LavaPlayer
 * elsewhere; this class just handles the OAuth dance and reads the user's playlists / liked songs.
 *
 * <p>Mirrors {@link bot.utils.MetadataService}: a shared {@link HttpClient} + Jackson. Network/parse
 * failures and non-2xx responses surface as {@link SpotifyException} so callers can map them to an
 * error response.
 */
public class SpotifyService {
    private static final Logger LOGGER = LogManager.getLogger(SpotifyService.class);
    private static final HttpClient HTTP_CLIENT =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String AUTHORIZE_URL = "https://accounts.spotify.com/authorize";
    private static final String TOKEN_URL = "https://accounts.spotify.com/api/token";
    private static final String API_BASE = "https://api.spotify.com/v1";
    // Read the user's (private) playlists and their saved/liked tracks.
    private static final String SCOPES = "playlist-read-private user-library-read";
    private static final int PAGE_LIMIT = 50; // playlists / liked songs page size
    private static final int TRACK_PAGE_LIMIT = 100; // playlist-tracks page size (Spotify's max)
    private static final int MAX_PAGES = 40; // safety cap (≈ 2000 playlists / 4000 tracks)

    /** Synthetic id used by the web UI to mean "the user's Liked Songs" rather than a real playlist. */
    public static final String LIKED_SONGS_ID = "liked";

    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;

    public SpotifyService(String clientId, String clientSecret, String redirectUri) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.redirectUri = redirectUri;
    }

    /** Thrown when a Spotify request fails (network error, bad status, or unparseable body). */
    public static class SpotifyException extends RuntimeException {
        public SpotifyException(String message) {
            super(message);
        }

        public SpotifyException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** OAuth tokens from a code exchange or refresh. {@code refreshToken} may be null on a refresh. */
    public record Tokens(String accessToken, String refreshToken, int expiresInSeconds, String scope) {}

    /** A Spotify playlist as shown in the import picker. */
    public record SpotifyPlaylist(String id, String name, int trackCount, String imageUrl) {}

    /** A Spotify track's metadata (no audio); resolved to a YouTube source at import time. */
    public record SpotifyTrack(String name, String artist, String album, int durationMs) {}

    /** Builds the Spotify authorize URL the browser is redirected to, carrying the CSRF {@code state}. */
    public String buildAuthorizeUrl(String state) {
        return AUTHORIZE_URL + "?response_type=code"
                + "&client_id=" + enc(clientId)
                + "&scope=" + enc(SCOPES)
                + "&redirect_uri=" + enc(redirectUri)
                + "&state=" + enc(state);
    }

    /** Exchanges an authorization code for tokens. */
    public Tokens exchangeCode(String code) {
        return requestTokens("grant_type=authorization_code&code=" + enc(code) + "&redirect_uri=" + enc(redirectUri));
    }

    /** Refreshes an access token. The response may omit a new refresh token (reuse the old one). */
    public Tokens refresh(String refreshToken) {
        return requestTokens("grant_type=refresh_token&refresh_token=" + enc(refreshToken));
    }

    private Tokens requestTokens(String form) {
        String basic =
                Base64.getEncoder().encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(TOKEN_URL))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Basic " + basic)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        JsonNode node = send(request, "token request");
        String accessToken = text(node, "access_token");
        if (accessToken == null) {
            throw new SpotifyException("Spotify token response had no access_token");
        }
        return new Tokens(
                accessToken,
                text(node, "refresh_token"),
                node.path("expires_in").asInt(3600),
                text(node, "scope"));
    }

    /** The connected Spotify account's user id (stored so we can show whose account is linked). */
    public String getSpotifyUserId(String accessToken) {
        return text(get(accessToken, API_BASE + "/me"), "id");
    }

    /** The user's playlists (paginated), newest API order. */
    public List<SpotifyPlaylist> listPlaylists(String accessToken) {
        List<SpotifyPlaylist> out = new ArrayList<>();
        for (int page = 0; page < MAX_PAGES; page++) {
            JsonNode node =
                    get(accessToken, API_BASE + "/me/playlists?limit=" + PAGE_LIMIT + "&offset=" + (page * PAGE_LIMIT));
            JsonNode items = node.path("items");
            if (!items.isArray() || items.isEmpty()) {
                break;
            }
            for (JsonNode item : items) {
                if (item == null || item.isNull()) {
                    continue;
                }
                String image =
                        item.path("images").isArray() && !item.path("images").isEmpty()
                                ? text(item.path("images").get(0), "url")
                                : null;
                out.add(new SpotifyPlaylist(
                        text(item, "id"),
                        text(item, "name"),
                        item.path("tracks").path("total").asInt(0),
                        image));
            }
            if (items.size() < PAGE_LIMIT) {
                break;
            }
        }
        return out;
    }

    /** Every track in a playlist (paginated). Skips removed/local entries with no usable metadata. */
    public List<SpotifyTrack> listPlaylistTracks(String accessToken, String playlistId) {
        return collectTracks(accessToken, API_BASE + "/playlists/" + enc(playlistId) + "/tracks", TRACK_PAGE_LIMIT);
    }

    /** The user's saved/"liked" tracks (paginated). */
    public List<SpotifyTrack> listLikedTracks(String accessToken) {
        return collectTracks(accessToken, API_BASE + "/me/tracks", PAGE_LIMIT);
    }

    private List<SpotifyTrack> collectTracks(String accessToken, String baseUrl, int pageLimit) {
        List<SpotifyTrack> out = new ArrayList<>();
        for (int page = 0; page < MAX_PAGES; page++) {
            String sep = baseUrl.contains("?") ? "&" : "?";
            JsonNode node = get(accessToken, baseUrl + sep + "limit=" + pageLimit + "&offset=" + (page * pageLimit));
            JsonNode items = node.path("items");
            if (!items.isArray() || items.isEmpty()) {
                break;
            }
            for (JsonNode item : items) {
                JsonNode track = item.path("track");
                if (track.isMissingNode() || track.isNull()) {
                    continue; // removed track or podcast episode
                }
                String name = text(track, "name");
                if (name == null) {
                    continue;
                }
                out.add(new SpotifyTrack(
                        name,
                        joinArtists(track.path("artists")),
                        text(track.path("album"), "name"),
                        track.path("duration_ms").asInt(0)));
            }
            if (items.size() < pageLimit) {
                break;
            }
        }
        return out;
    }

    private static String joinArtists(JsonNode artists) {
        if (!artists.isArray() || artists.isEmpty()) {
            return null;
        }
        List<String> names = new ArrayList<>();
        for (JsonNode artist : artists) {
            String name = text(artist, "name");
            if (name != null) {
                names.add(name);
            }
        }
        return names.isEmpty() ? null : String.join(", ", names);
    }

    private JsonNode get(String accessToken, String url) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();
        return send(request, "GET " + url);
    }

    private JsonNode send(HttpRequest request, String what) {
        try {
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                LOGGER.warn("Spotify {} returned status {}: {}", what, response.statusCode(), response.body());
                throw new SpotifyException("Spotify " + what + " failed with status " + response.statusCode());
            }
            return MAPPER.readTree(response.body());
        } catch (SpotifyException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SpotifyException("Interrupted during Spotify " + what, e);
        } catch (Exception e) {
            throw new SpotifyException("Spotify " + what + " failed", e);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : blankToNull(value.asString());
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
