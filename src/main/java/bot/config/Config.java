package bot.config;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Config(
        @JsonProperty("botToken") String botToken,
        @JsonProperty("muteRole") String muteRole,
        @JsonProperty("infoLogsChannelWebHookURL") String infoLogsChannelWebHookURL,
        @JsonProperty("errorLogsChannelWebHookURL") String errorLogsChannelWebHookURL,
        @JsonProperty("dbFile") String dbFile,
        @JsonProperty("klippyApiKey") String klippyApiKey,
        @JsonProperty("kimiApiKey") String kimiApiKey,
        @JsonProperty("openAiApiKey") String openAiApiKey,
        @JsonProperty("youtubeOauthRefreshToken") String youtubeOauthRefreshToken,
        // Server-specific AI personality. The deployed config.json for each server supplies its own
        // value; when absent/blank the code falls back to a built-in default (see
        // MessageReceivedListener), so existing deployments keep working without a config change.
        @JsonProperty("aiPersonalityPrompt") String aiPersonalityPrompt,
        // Server-specific rules shown by /rules, one entry per rule block. Falls back to a built-in
        // default (see RulesCommand) when absent/empty.
        @JsonProperty("rules") List<String> rules,
        @JsonProperty("autoModIgnoredChannels") List<String> autoModIgnoredChannels,
        // Port for the playlist web player (bot.web.WebServer). Falls back to a built-in default
        // (see Config#webPortOrDefault) when absent, so existing deployments need no config change.
        @JsonProperty("webPort") Integer webPort,
        // Public base URL the web player is reachable at (e.g. "https://playlist.sk96.uk"), used to
        // build "listen online" links in /playlist show. When unset/blank, no link is shown.
        @JsonProperty("webBaseUrl") String webBaseUrl) {

    private static final int DEFAULT_WEB_PORT = 8080;

    /** The configured web port, or the built-in default when unset. */
    public int webPortOrDefault() {
        return webPort == null ? DEFAULT_WEB_PORT : webPort;
    }

    /** The configured web base URL without any trailing slash, or {@code null} when unset/blank. */
    public String webBaseUrlOrNull() {
        if (webBaseUrl == null || webBaseUrl.isBlank()) {
            return null;
        }
        String trimmed = webBaseUrl.strip();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
