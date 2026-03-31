package bot.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Config(
        @JsonProperty("botToken") String botToken,
        @JsonProperty("muteRole") String muteRole,
        @JsonProperty("infoLogsChannelWebHookURL") String infoLogsChannelWebHookURL,
        @JsonProperty("errorLogsChannelWebHookURL") String errorLogsChannelWebHookURL,
        @JsonProperty("dbFile") String dbFile,
        @JsonProperty("klippyApiKey") String klippyApiKey) {}
