package bot.slash.music;

/** Small formatting helpers shared by the music commands. */
public final class MusicFormat {
    private MusicFormat() {}

    /** Formats a millisecond duration as {@code H:MM:SS} (hours omitted when zero). */
    public static String duration(long millis) {
        long totalSeconds = millis / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;

        if (hours > 0) {
            return String.format("%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format("%d:%02d", minutes, seconds);
    }
}
