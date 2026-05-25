package bot.automod;

import java.util.List;

public record Verdict(Severity severity, String reason, String summary, String reply, List<String> users) {

    public static final Verdict NONE = new Verdict(Severity.NONE, "", "", "", List.of());

    public boolean requiresAction() {
        return severity != Severity.NONE;
    }

    public boolean requiresHumanModerator() {
        return severity == Severity.ALERT;
    }
}
