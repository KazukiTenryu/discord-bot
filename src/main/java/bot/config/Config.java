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
        @JsonProperty("autoModIgnoredChannels") List<String> autoModIgnoredChannels) {}
