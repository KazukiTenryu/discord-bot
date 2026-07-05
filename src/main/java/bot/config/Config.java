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
        @JsonProperty("webBaseUrl") String webBaseUrl,
        // Spotify OAuth app credentials, used by the web player's "Connect Spotify" import. When the
        // client id/secret are unset/blank, the import endpoints are disabled and the UI hides the
        // button. spotifyRedirectUri must exactly match a Redirect URI registered on the Spotify app
        // (typically webBaseUrl + "/api/spotify/callback").
        @JsonProperty("spotifyClientId") String spotifyClientId,
        @JsonProperty("spotifyClientSecret") String spotifyClientSecret,
        @JsonProperty("spotifyRedirectUri") String spotifyRedirectUri,
        // ElevenLabs Agents voice conversation ("Maya"). eleven_labs_agent_id points at an agent
        // created in the ElevenLabs dashboard/API (it defines the voice + persona). When either the
        // key or agent id is blank the /maya command is not registered. maya_character is just the
        // display name (defaults to "Maya"); maya_auto_join (off by default) makes the bot follow
        // humans into voice channels automatically — note conversations are billed per minute.
        @JsonProperty("eleven_labs_api_key") String elevenLabsApiKey,
        @JsonProperty("eleven_labs_agent_id") String elevenLabsAgentId,
        @JsonProperty("maya_character") String mayaCharacter,
        @JsonProperty("maya_auto_join") Boolean mayaAutoJoin) {

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

    /** Whether Spotify import is configured (client id, secret and redirect URI all present). */
    public boolean spotifyConfigured() {
        return notBlank(spotifyClientId) && notBlank(spotifyClientSecret) && notBlank(spotifyRedirectUri);
    }

    /** Whether the ElevenLabs voice conversation is configured (API key and agent id present). */
    public boolean mayaConfigured() {
        return notBlank(elevenLabsApiKey) && notBlank(elevenLabsAgentId);
    }

    /** The configured voice-assistant display name, or "Maya" when unset. */
    public String mayaCharacterOrDefault() {
        return notBlank(mayaCharacter) ? mayaCharacter.strip() : "Maya";
    }

    /** Whether the bot should auto-join voice channels for Maya (defaults to false). */
    public boolean mayaAutoJoinEnabled() {
        return Boolean.TRUE.equals(mayaAutoJoin);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
