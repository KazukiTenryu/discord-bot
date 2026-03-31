package bot.utils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import bot.config.Config;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public class KlippyService {
    private static final Logger LOGGER = LogManager.getLogger(KlippyService.class);
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final Config config;

    public KlippyService(Config config) {
        this.config = config;
    }

    public Optional<String> fetchGif(String query) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(klippyUrl(config.klippyApiKey(), query)))
                .GET()
                .build();

        try {
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            String body = response.body();
            JsonNode root = OBJECT_MAPPER.readTree(body);

            String gifUrl = root.path("data")
                    .path("data")
                    .get(0)
                    .path("file")
                    .path("hd")
                    .path("gif")
                    .path("url")
                    .asString();

            return Optional.of(gifUrl);
        } catch (IOException | InterruptedException e) {
            LOGGER.error("Failed to make request to Klippy", e);
        }

        return Optional.empty();
    }

    private String klippyUrl(String apiKey, String query) {
        return "https://api.klipy.com/api/v1/%s/gifs/search?q=%s&customer_id=discord".formatted(apiKey, query);
    }
}
