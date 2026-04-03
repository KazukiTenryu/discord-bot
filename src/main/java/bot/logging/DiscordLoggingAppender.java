package bot.logging;

import java.io.Serializable;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.*;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.config.plugins.*;
import org.apache.logging.log4j.core.layout.PatternLayout;

@Plugin(
        name = "DiscordLoggingAppender",
        category = Core.CATEGORY_NAME,
        elementType = Appender.ELEMENT_TYPE,
        printObject = true)
@SuppressWarnings("unused")
public class DiscordLoggingAppender extends AbstractAppender {
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm:ss");
    private static final String WEBHOOK_REQUEST = """
            {
                "embeds": [{
                    "title": "INFO",
                    "description": "%s",
                    "footer": {
                        "text": "%s"
                    }
                 }]
            }
            """;

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
        if (event.getLevel() == Level.INFO) {
            sendLogToDiscord(infoWebhookUrl, event.getMessage().getFormattedMessage());
        } else if (event.getLevel() == Level.ERROR) {
            sendLogToDiscord(errorWebhookUrl, event.getMessage().getFormattedMessage());
        }
    }

    private static void sendLogToDiscord(String webhookURL, String message) {
        Thread.ofVirtual().start(() -> {
            try {
                HttpResponse<String> response = HTTP_CLIENT.send(
                        createRequest(
                                webhookURL,
                                WEBHOOK_REQUEST.formatted(
                                        message, LocalDateTime.now().format(FORMATTER))),
                        HttpResponse.BodyHandlers.ofString());

                int statusCode = response.statusCode();

                if (statusCode != 204) {
                    LOGGER.error("Failed to log event to discord, received status {}", statusCode);
                }
            } catch (Exception e) {
                LOGGER.error("Failed to send HTTP request to info-webhook", e);
            }
        });
    }

    private static HttpRequest createRequest(String url, String body) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }
}
