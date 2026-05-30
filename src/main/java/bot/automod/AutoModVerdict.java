package bot.automod;

import bot.automod.ModerationService.ModerationResult;
import bot.automod.RuleModerator.RuleJudgment;

public record AutoModVerdict(
        int severity,
        Source source,
        String reason,
        int safetySeverity,
        String safetyCategory,
        int ruleSeverity,
        String ruleBroken) {

    public enum Source {
        SAFETY,
        RULE,
        NONE
    }

    public static AutoModVerdict merge(ModerationResult moderation, RuleJudgment rule) {
        int safetySeverity = moderation != null ? moderation.severity() : 0;
        String safetyCategory = moderation != null ? moderation.topCategory() : "none";

        int ruleSeverity = rule != null ? rule.severity() : 0;
        String ruleBroken = rule != null ? rule.ruleBroken() : "none";

        Source source;
        String reason;
        int severity;

        if (safetySeverity == 0 && ruleSeverity == 0) {
            source = Source.NONE;
            reason = "no violation";
            severity = 0;
        } else if (safetySeverity >= ruleSeverity) {
            source = Source.SAFETY;
            reason = "flagged as " + safetyCategory;
            severity = safetySeverity;
        } else {
            source = Source.RULE;
            reason = rule.reason() == null || rule.reason().isBlank() ? ruleBroken : rule.reason();
            severity = ruleSeverity;
        }

        return new AutoModVerdict(severity, source, reason, safetySeverity, safetyCategory, ruleSeverity, ruleBroken);
    }
}
