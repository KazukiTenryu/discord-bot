package bot.logging;

import java.io.Serializable;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.*;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.config.plugins.*;
import org.apache.logging.log4j.core.layout.PatternLayout;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Plugin(
        name = "DiscordLoggingAppender",
        category = Core.CATEGORY_NAME,
        elementType = Appender.ELEMENT_TYPE,
        printObject = true)
@SuppressWarnings("unused")
public class DiscordLoggingAppender extends AbstractAppender {
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm:ss");

    // Discord rejects embeds whose description exceeds 4096 characters with a 400.
    private static final int MAX_DESCRIPTION_LENGTH = 4096;
    // Discord webhooks are rate limited (~5 requests / 2s). On a 429 we honour Retry-After and retry.
    private static final int MAX_ATTEMPTS = 5;
    private static final long DEFAULT_BACKOFF_MS = 1_000L;
    private static final long MAX_BACKOFF_MS = 60_000L;

    // Serialise every webhook POST through one worker so bursts of startup logs are paced rather
    // than fired concurrently (which tripped Discord's rate limit). The queue is bounded and drops
    // the oldest pending log under sustained back-pressure — logs are best-effort, not durable.
    private static final ExecutorService SENDER = new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(256),
            runnable -> {
                Thread thread = new Thread(runnable, "discord-log-webhook");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.DiscardOldestPolicy());

    private final String infoWebhookUrl;
    private final String errorWebhookUrl;

    protected DiscordLoggingAppender(
            String name,
            Filter filter,
            Layout<? extends Serializable> layout,
            boolean ignoreExceptions,
            Property[] properties,
            String infoWebhookUrl,
            String errorWebhookUrl) {
        super(name, filter, layout, ignoreExceptions, properties);
        this.infoWebhookUrl = infoWebhookUrl;
        this.errorWebhookUrl = errorWebhookUrl;
    }

    @PluginFactory
    public static DiscordLoggingAppender createAppender(
            @PluginAttribute("name") String name,
            @PluginElement("Layout") Layout<? extends Serializable> layout,
            @PluginElement("Filter") Filter filter,
            @PluginAttribute(value = "ignoreExceptions", defaultBoolean = true) boolean ignoreExceptions,
            @PluginAttribute("infoLogsChannelWebHookURL") String infoWebhookUrl,
            @PluginAttribute("errorLogsChannelWebHookURL") String errorWebhookUrl) {
        if (name == null) {
            LOGGER.error("No name provided for DiscordLoggingAppender");
            return null;
        }
        if (layout == null) {
            layout = PatternLayout.createDefaultLayout();
        }
        if (infoWebhookUrl == null && errorWebhookUrl == null) {
            LOGGER.error("No webhook URLs provided for DiscordLoggingAppender");
            return null;
        }

        return new DiscordLoggingAppender(
                name, filter, layout, ignoreExceptions, Property.EMPTY_ARRAY, infoWebhookUrl, errorWebhookUrl);
    }

    @Override
    public void append(LogEvent event) {
        String webhookUrl;
        if (event.getLevel() == Level.INFO) {
            webhookUrl = infoWebhookUrl;
        } else if (event.getLevel() == Level.ERROR) {
            webhookUrl = errorWebhookUrl;
        } else {
            return;
        }
        if (webhookUrl == null || webhookUrl.isBlank()) {
            return;
        }

        // Build the payload on the logging thread so it snapshots the message/timestamp now; the
        // worker thread only performs the HTTP I/O.
        String payload =
                buildPayload(event.getLevel().name(), event.getMessage().getFormattedMessage());
        SENDER.execute(() -> deliver(webhookUrl, payload));
    }

    private static String buildPayload(String title, String message) {
        if (message != null && message.length() > MAX_DESCRIPTION_LENGTH) {
            message = message.substring(0, MAX_DESCRIPTION_LENGTH - 1) + "…";
        }
        // Jackson escapes quotes, backslashes, newlines and control characters — the raw string
        // template this replaced produced invalid JSON (HTTP 400) whenever a log contained them.
        ObjectNode root = OBJECT_MAPPER.createObjectNode();
        ArrayNode embeds = root.putArray("embeds");
        ObjectNode embed = embeds.addObject();
        embed.put("title", title);
        embed.put("description", message);
        embed.putObject("footer").put("text", LocalDateTime.now().format(FORMATTER));
        return OBJECT_MAPPER.writeValueAsString(root);
    }

    private static void deliver(String webhookURL, String payload) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpResponse<String> response =
                        HTTP_CLIENT.send(createRequest(webhookURL, payload), HttpResponse.BodyHandlers.ofString());
                int statusCode = response.statusCode();

                if (statusCode == 429) {
                    Thread.sleep(retryAfterMillis(response));
                    continue;
                }
                if (statusCode / 100 != 2) {
                    LOGGER.error("Failed to log event to discord, received status {}", statusCode);
                }
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                LOGGER.error("Failed to send HTTP request to discord webhook", e);
                return;
            }
        }
        LOGGER.error("Gave up sending log to discord after {} attempts (rate limited)", MAX_ATTEMPTS);
    }

    private static long retryAfterMillis(HttpResponse<?> response) {
        long backoff = response.headers()
                .firstValue("Retry-After")
                .map(value -> {
                    try {
                        // Discord sends Retry-After in seconds, possibly fractional (e.g. "0.75").
                        return (long) (Double.parseDouble(value.trim()) * 1000);
                    } catch (NumberFormatException e) {
                        return DEFAULT_BACKOFF_MS;
                    }
                })
                .orElse(DEFAULT_BACKOFF_MS);
        return Math.max(DEFAULT_BACKOFF_MS, Math.min(backoff, MAX_BACKOFF_MS));
    }

    private static HttpRequest createRequest(String url, String body) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }
}
