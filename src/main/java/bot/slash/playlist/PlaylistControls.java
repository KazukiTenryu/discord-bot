package bot.slash.playlist;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.managers.AudioManager;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;

import bot.slash.music.GuildMusicManager;
import bot.slash.music.PlayerManager;

/**
 * Transport actions behind the {@code /play-playlist} embed buttons. Reuses the same player/scheduler
 * operations as the {@code /pause}, {@code /skip}, {@code /stop} commands so behaviour stays in sync.
 * Routed here from {@link bot.GlobalEventListener#onButtonInteraction}.
 */
public final class PlaylistControls {
    public static final String TOGGLE_ID = "playlist:toggle";
    public static final String SKIP_ID = "playlist:skip";
    public static final String STOP_ID = "playlist:stop";

    private PlaylistControls() {}

    /** Pauses if playing, resumes if paused. */
    public static String toggle(Guild guild) {
        AudioPlayer player = PlayerManager.getInstance().getMusicManager(guild).getPlayer();
        if (player.getPlayingTrack() == null) {
            return "🔇 Nothing is playing.";
        }
        boolean nowPaused = !player.isPaused();
        player.setPaused(nowPaused);
        return nowPaused ? "⏸️ Paused." : "▶️ Resumed.";
    }

    /** Advances to the next queued track. */
    public static String skip(Guild guild) {
        GuildMusicManager musicManager = PlayerManager.getInstance().getMusicManager(guild);
        AudioTrack current = musicManager.getPlayer().getPlayingTrack();
        if (current == null) {
            return "🔇 Nothing is playing.";
        }
        musicManager.getScheduler().nextTrack();
        return "⏭️ Skipped **" + current.getInfo().title + "**";
    }

    /** Clears the queue, stops playback, and leaves the voice channel. */
    public static String stop(Guild guild) {
        GuildMusicManager musicManager = PlayerManager.getInstance().getMusicManager(guild);
        musicManager.getScheduler().clearQueue();
        musicManager.getPlayer().stopTrack();

        AudioManager audioManager = guild.getAudioManager();
        audioManager.closeAudioConnection();
        return "⏹️ Stopped and left the channel.";
    }
}
