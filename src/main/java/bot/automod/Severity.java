package bot.automod;

import java.util.Locale;

public enum Severity {
    NONE,
    WARN,
    ALERT;

    public static Severity parse(String raw) {
        if (raw == null) return NONE;
        try {
            return Severity.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return NONE;
        }
    }
}
