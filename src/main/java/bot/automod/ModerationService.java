package bot.automod;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import bot.Main;
import bot.utils.KimiService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Service for interacting with OpenAI's moderation endpoint.
 *
 * <p>Unlike a chat model (e.g. {@link KimiService}), the moderation endpoint is purpose-built to
 * <em>ingest</em> unsafe content and classify it, rather than refusing it. This is what makes it
 * suitable for an automod use case where we deliberately feed it the worst messages in a channel.
 */
public class ModerationService {
    private static final Logger LOGGER = LogManager.getLogger(ModerationService.class);
    private static final HttpClient HTTP_CLIENT =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final String MODERATION_API_URL = "https://api.openai.com/v1/moderations";
    private static final String DEFAULT_MODEL = "omni-moderation-latest";

    private final String apiKey;
    private final String model;

    public ModerationService(String apiKey) {
        this(apiKey, DEFAULT_MODEL);
    }

    public ModerationService(String apiKey, String model) {
        this.apiKey = apiKey;
        this.model = model;
    }

    public boolean isConfigured() {
        boolean configured = apiKey != null && !apiKey.isBlank();
        if (!configured) {
            LOGGER.warn("OpenAI moderation API key not configured");
        }
        return configured;
    }

    /**
     * Classify a single piece of text.
     *
     * @param input the text to moderate
     * @return the moderation result, or empty if the request failed
     */
    public Optional<ModerationResult> moderate(String input) {
        if (!isConfigured()) {
            LOGGER.warn("OpenAI moderation API key not configured, cannot make request");
            return Optional.empty();
        }

        try {
            Main.getMetrics().count("ai_usage", Map.of("model", "openai-moderation"));

            ObjectNode requestBody = OBJECT_MAPPER.createObjectNode();
            requestBody.put("model", model);
            requestBody.put("input", input);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(MODERATION_API_URL))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                    .build();

            long start = System.currentTimeMillis();
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            LOGGER.info(
                    "OpenAI moderation call took {} ms (status {})",
                    System.currentTimeMillis() - start,
                    response.statusCode());

            if (response.statusCode() != 200) {
                Main.getMetrics()
                        .count(
                                "ai_request_fail",
                                Map.of("model", "openai-moderation", "statusCode", response.statusCode()));
                LOGGER.error("OpenAI moderation returned status {}: {}", response.statusCode(), response.body());
                return Optional.empty();
            }

            return parseResponse(response.body());

        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOGGER.error("Failed to call OpenAI moderation API", e);
            return Optional.empty();
        }
    }

    private Optional<ModerationResult> parseResponse(String responseBody) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(responseBody);
            JsonNode results = root.path("results");

            if (!results.isArray() || results.isEmpty()) {
                LOGGER.error("No results in OpenAI moderation response: {}", responseBody);
                return Optional.empty();
            }

            JsonNode result = results.get(0);
            boolean flagged = result.path("flagged").asBoolean(false);

            Map<String, Double> scores = new HashMap<>();
            String topCategory = "none";
            double maxScore = 0.0;

            JsonNode categoryScores = result.path("category_scores");
            for (Map.Entry<String, JsonNode> entry : categoryScores.properties()) {
                double score = entry.getValue().asDouble(0.0);
                scores.put(entry.getKey(), score);
                if (score > maxScore) {
                    maxScore = score;
                    topCategory = entry.getKey();
                }
            }

            // Map the highest category confidence (0.0 - 1.0) onto a 0 - 10 severity scale.
            int severity = (int) Math.round(maxScore * 10);

            return Optional.of(new ModerationResult(flagged, severity, maxScore, topCategory, scores));

        } catch (Exception e) {
            LOGGER.error("Failed to parse OpenAI moderation response", e);
            return Optional.empty();
        }
    }

    /**
     * The outcome of moderating a single message.
     *
     * @param flagged whether OpenAI's own threshold flagged the content
     * @param severity the top category score mapped onto a 0 - 10 scale
     * @param maxScore the raw highest category confidence (0.0 - 1.0)
     * @param topCategory the category with the highest confidence (e.g. "harassment", "sexual")
     * @param scores the full per-category confidence map
     */
    public record ModerationResult(
            boolean flagged, int severity, double maxScore, String topCategory, Map<String, Double> scores) {}
}
