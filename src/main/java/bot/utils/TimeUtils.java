package bot.utils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class TimeUtils {
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm:ss");

    private TimeUtils() {}

    public static String now() {
        return LocalDateTime.now().format(FORMATTER);
    }
}
