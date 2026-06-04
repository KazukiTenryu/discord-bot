package bot.slash.music;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;

import bot.stats.StatsService;

/**
 * Per-guild track queue. Listens to the {@link AudioPlayer} and, when a track finishes naturally,
 * starts the next queued track.
 */
public class TrackScheduler extends AudioEventAdapter {
    private final AudioPlayer player;
    private final BlockingQueue<AudioTrack> queue;

    public TrackScheduler(AudioPlayer player) {
        this.player = player;
        this.queue = new LinkedBlockingQueue<>();
    }

    /** Plays the track immediately if nothing is playing, otherwise appends it to the queue. */
    public void queue(AudioTrack track) {
        // startTrack(track, true) is a no-op (returns false) when a track is already playing.
        if (!player.startTrack(track, true)) {
            queue.offer(track);
        }
    }

    /** Skips to the next track in the queue (or stops if the queue is empty). */
    public void nextTrack() {
        player.startTrack(queue.poll(), false);
    }

    /** Removes every queued track without affecting what is currently playing. */
    public void clearQueue() {
        queue.clear();
    }

    /** A snapshot of the tracks waiting to be played, in order. */
    public List<AudioTrack> getQueue() {
        return new ArrayList<>(queue);
    }

    @Override
    public void onTrackStart(AudioPlayer player, AudioTrack track) {
        // A track is actually playing now (via /play or /play-playlist) — log it for the server stats.
        StatsService stats = PlayerManager.getInstance().getStatsService();
        if (stats == null) {
            return;
        }
        PlayerManager.Requester requester = track.getUserData() instanceof PlayerManager.Requester r ? r : null;
        stats.recordPlay(
                requester == null ? null : requester.userId(),
                requester == null ? null : requester.userName(),
                track.getInfo(),
                "voice");
    }

    @Override
    public void onTrackEnd(AudioPlayer player, AudioTrack track, AudioTrackEndReason endReason) {
        // mayStartNext is false for things like REPLACED/STOPPED so we don't double-advance.
        if (endReason.mayStartNext) {
            nextTrack();
        }
    }
}
