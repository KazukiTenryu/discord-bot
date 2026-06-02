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
 * Fetches song lyrics from <a href="https://lrclib.net">lrclib.net</a> — a free, key-less public
 * lyrics API. Uses the search endpoint and returns the first result that carries plain lyrics.
 */
public class LyricsService {
    private static final Logger LOGGER = LogManager.getLogger(LyricsService.class);
    private static final HttpClient HTTP_CLIENT =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final String SEARCH_URL = "https://lrclib.net/api/search?q=%s";
    // lrclib asks clients to identify themselves via User-Agent.
    private static final String USER_AGENT = "discord-bot-kazuki (https://github.com/discord-bot-kazuki)";

    /** A resolved lyrics hit: the matched track metadata plus its plain (unsynced) lyrics. */
    public record Lyrics(String trackName, String artistName, String plainLyrics) {}

    public Optional<Lyrics> fetch(String query) {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8).replace("+", "%20");
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(SEARCH_URL.formatted(encoded)))
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        try {
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                LOGGER.warn("lrclib search for '{}' returned status {}", query, response.statusCode());
                return Optional.empty();
            }

            JsonNode root = OBJECT_MAPPER.readTree(response.body());
            if (!root.isArray()) {
                return Optional.empty();
            }
            for (JsonNode node : root) {
                String plain = node.path("plainLyrics").asString();
                if (plain != null && !plain.isBlank()) {
                    return Optional.of(new Lyrics(
                            node.path("trackName").asString(),
                            node.path("artistName").asString(),
                            plain));
                }
            }
            return Optional.empty();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.error("Interrupted fetching lyrics for '{}'", query, e);
        } catch (IOException e) {
            LOGGER.error("Failed to fetch lyrics for '{}'", query, e);
        }
        return Optional.empty();
    }
}
