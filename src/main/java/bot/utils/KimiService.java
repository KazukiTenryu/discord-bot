package bot.utils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Service for interacting with Kimi (Moonshot AI) API.
 * Provides a generic interface for AI text generation.
 */
public class KimiService {
    private static final Logger LOGGER = LogManager.getLogger(KimiService.class);
    private static final HttpClient HTTP_CLIENT =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final String KIMI_API_URL = "https://api.moonshot.cn/v1/chat/completions";

    private final String apiKey;
    private final String defaultModel;
    private final double defaultTemperature;
    private final int defaultMaxTokens;

    /**
     * Creates a new KimiService with default settings.
     *
     * @param apiKey Your Kimi API key
     */
    public KimiService(String apiKey) {
        this(apiKey, "moonshot-v1-8k", 0.7, 500);
    }

    /**
     * Creates a new KimiService with custom default settings.
     *
     * @param apiKey Your Kimi API key
     * @param defaultModel Default model to use (e.g., "moonshot-v1-8k", "moonshot-v1-32k", "moonshot-v1-128k")
     * @param defaultTemperature Default creativity level (0.0 - 1.0)
     * @param defaultMaxTokens Default maximum tokens in response
     */
    public KimiService(String apiKey, String defaultModel, double defaultTemperature, int defaultMaxTokens) {
        this.apiKey = apiKey;
        this.defaultModel = defaultModel;
        this.defaultTemperature = defaultTemperature;
        this.defaultMaxTokens = defaultMaxTokens;
    }

    /**
     * Check if the API key is configured.
     */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * Send a simple single-turn chat completion request.
     *
     * @param prompt The user message/prompt
     * @return Optional containing the AI response, or empty if the request failed
     */
    public Optional<String> complete(String prompt) {
        return complete(prompt, defaultModel, defaultTemperature, defaultMaxTokens);
    }

    /**
     * Send a chat completion request with custom settings.
     *
     * @param prompt The user message/prompt
     * @param model The model to use
     * @param temperature Creativity level (0.0 - 1.0)
     * @param maxTokens Maximum tokens in response
     * @return Optional containing the AI response, or empty if the request failed
     */
    public Optional<String> complete(String prompt, String model, double temperature, int maxTokens) {
        List<Message> messages = List.of(new Message("user", prompt));
        return chat(messages, model, temperature, maxTokens);
    }

    /**
     * Send a chat completion with conversation history.
     *
     * @param messages List of messages (system, user, assistant)
     * @return Optional containing the AI response, or empty if the request failed
     */
    public Optional<String> chat(List<Message> messages) {
        return chat(messages, defaultModel, defaultTemperature, defaultMaxTokens);
    }

    /**
     * Send a chat completion with conversation history and custom settings.
     *
     * @param messages List of messages (system, user, assistant)
     * @param model The model to use
     * @param temperature Creativity level (0.0 - 1.0)
     * @param maxTokens Maximum tokens in response
     * @return Optional containing the AI response, or empty if the request failed
     */
    public Optional<String> chat(List<Message> messages, String model, double temperature, int maxTokens) {
        if (!isConfigured()) {
            LOGGER.warn("Kimi API key not configured, cannot make request");
            return Optional.empty();
        }

        try {
            String requestBody = buildRequestBody(messages, model, temperature, maxTokens);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(KIMI_API_URL))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                LOGGER.error("Kimi API returned status {}: {}", response.statusCode(), response.body());
                return Optional.empty();
            }

            return parseResponse(response.body());

        } catch (IOException | InterruptedException e) {
            LOGGER.error("Failed to call Kimi API", e);
            return Optional.empty();
        }
    }

    /**
     * Stream a chat completion response (for real-time responses).
     * Note: This returns the full response after streaming completes.
     *
     * @param messages List of messages
     * @param model The model to use
     * @param temperature Creativity level
     * @param maxTokens Maximum tokens
     * @return Optional containing the full streamed response
     */
    public Optional<String> streamChat(List<Message> messages, String model, double temperature, int maxTokens) {
        if (!isConfigured()) {
            LOGGER.warn("Kimi API key not configured, cannot make request");
            return Optional.empty();
        }

        try {
            ObjectNode requestBody = OBJECT_MAPPER.createObjectNode();
            requestBody.put("model", model);
            requestBody.put("temperature", temperature);
            requestBody.put("max_tokens", maxTokens);
            requestBody.put("stream", true);

            ArrayNode messagesArray = OBJECT_MAPPER.createArrayNode();
            for (Message msg : messages) {
                ObjectNode msgNode = OBJECT_MAPPER.createObjectNode();
                msgNode.put("role", msg.role());
                msgNode.put("content", msg.content());
                messagesArray.add(msgNode);
            }
            requestBody.set("messages", messagesArray);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(KIMI_API_URL))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                LOGGER.error("Kimi API returned status {}: {}", response.statusCode(), response.body());
                return Optional.empty();
            }

            return parseStreamResponse(response.body());

        } catch (IOException | InterruptedException e) {
            LOGGER.error("Failed to call Kimi API", e);
            return Optional.empty();
        }
    }

    private String buildRequestBody(List<Message> messages, String model, double temperature, int maxTokens) {
        ObjectNode requestBody = OBJECT_MAPPER.createObjectNode();
        requestBody.put("model", model);
        requestBody.put("temperature", temperature);
        requestBody.put("max_tokens", maxTokens);

        ArrayNode messagesArray = OBJECT_MAPPER.createArrayNode();
        for (Message msg : messages) {
            ObjectNode msgNode = OBJECT_MAPPER.createObjectNode();
            msgNode.put("role", msg.role());
            msgNode.put("content", msg.content());
            messagesArray.add(msgNode);
        }
        requestBody.set("messages", messagesArray);

        return requestBody.toString();
    }

    private Optional<String> parseResponse(String responseBody) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(responseBody);
            JsonNode choices = root.path("choices");

            if (!choices.isArray() || choices.isEmpty()) {
                LOGGER.error("No choices in Kimi API response");
                return Optional.empty();
            }

            String content =
                    choices.get(0).path("message").path("content").asText().trim();

            if (content.isEmpty()) {
                return Optional.empty();
            }

            return Optional.of(content);

        } catch (Exception e) {
            LOGGER.error("Failed to parse Kimi API response", e);
            return Optional.empty();
        }
    }

    private Optional<String> parseStreamResponse(String responseBody) {
        // For streaming responses, we need to concatenate all the delta content
        StringBuilder fullContent = new StringBuilder();

        for (String line : responseBody.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.equals("data: [DONE]")) {
                continue;
            }

            if (line.startsWith("data: ")) {
                try {
                    String jsonData = line.substring(6);
                    JsonNode node = OBJECT_MAPPER.readTree(jsonData);
                    JsonNode choices = node.path("choices");

                    if (choices.isArray() && !choices.isEmpty()) {
                        String deltaContent =
                                choices.get(0).path("delta").path("content").asText();

                        if (!deltaContent.isEmpty()) {
                            fullContent.append(deltaContent);
                        }
                    }
                } catch (Exception e) {
                    LOGGER.debug("Failed to parse streaming line: {}", line);
                }
            }
        }

        String result = fullContent.toString().trim();
        return result.isEmpty() ? Optional.empty() : Optional.of(result);
    }

    /**
     * Builder for constructing messages.
     */
    public static List<Message> buildMessages(String systemPrompt, String userPrompt) {
        List<Message> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(new Message("system", systemPrompt));
        }
        messages.add(new Message("user", userPrompt));
        return messages;
    }

    /**
     * Represents a single message in the conversation.
     */
    public record Message(String role, String content) {
        /**
         * Creates a system message (sets behavior/context).
         */
        public static Message system(String content) {
            return new Message("system", content);
        }

        /**
         * Creates a user message (the prompt).
         */
        public static Message user(String content) {
            return new Message("user", content);
        }

        /**
         * Creates an assistant message (previous AI response).
         */
        public static Message assistant(String content) {
            return new Message("assistant", content);
        }
    }
}
