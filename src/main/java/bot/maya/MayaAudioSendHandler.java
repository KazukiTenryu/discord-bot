package bot.maya;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;

import net.dv8tion.jda.api.audio.AudioSendHandler;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Streams the AI's speech into the Discord voice channel.
 *
 * <p>Maya's audio (mono little-endian PCM at the server's sample rate) is converted to Discord's
 * 48 kHz stereo big-endian PCM by {@link #offer(byte[], int)} and buffered here. JDA pulls it 20 ms
 * at a time via {@link #provide20MsAudio()}; {@link #isOpus()} returns {@code false} so JDA encodes
 * the PCM to Opus itself. When Maya is silent the buffer drains and {@link #canProvide()} returns
 * {@code false}, so nothing is sent.
 *
 * <p>The backend streams a whole reply faster than real time, so it lands here in a burst and drains
 * at 20 ms/frame. The buffer is a queue of chunks drained from the front (O(frame) per pull, not a
 * full-buffer copy), with a generous ceiling so long replies aren't truncated. Barge-in clears it.
 */
public class MayaAudioSendHandler implements AudioSendHandler {
    private static final Logger LOGGER = LogManager.getLogger(MayaAudioSendHandler.class);
    // 20 ms of 48 kHz, 16-bit, stereo PCM = 48000 * 0.02 * 2 channels * 2 bytes.
    private static final int FRAME_BYTES = 3840;
    // Ceiling on buffered audio (~120 s). Only a runaway reply should ever reach this; a single
    // spoken response is far shorter, and interruptions clear the buffer, so this stays generous.
    private static final int MAX_BUFFERED_BYTES = FRAME_BYTES * 50 * 120;

    private final Object lock = new Object();
    private final Deque<byte[]> chunks = new ArrayDeque<>();
    private int headOffset; // bytes already consumed from the head chunk
    private int available; // total unread bytes across all chunks
    private boolean overflowLogged;

    /** Converts one chunk of Maya's PCM to Discord format and queues it for playback. */
    public void offer(byte[] backendPcm, int sourceRate) {
        byte[] discordPcm = AudioResampler.sesameToDiscord(backendPcm, sourceRate);
        if (discordPcm.length == 0) {
            return;
        }
        synchronized (lock) {
            if (available + discordPcm.length > MAX_BUFFERED_BYTES) {
                if (!overflowLogged) {
                    LOGGER.warn("Playback buffer full (~{}s); dropping audio to avoid unbounded lag", 120);
                    overflowLogged = true;
                }
                return;
            }
            chunks.addLast(discordPcm);
            available += discordPcm.length;
        }
    }

    /** Discards any buffered audio (used on interruption and when a call ends). */
    public void clear() {
        synchronized (lock) {
            chunks.clear();
            headOffset = 0;
            available = 0;
            overflowLogged = false;
        }
    }

    @Override
    public boolean canProvide() {
        synchronized (lock) {
            return available >= FRAME_BYTES;
        }
    }

    @Override
    public ByteBuffer provide20MsAudio() {
        byte[] out = new byte[FRAME_BYTES];
        synchronized (lock) {
            if (available < FRAME_BYTES) {
                return null;
            }
            int written = 0;
            while (written < FRAME_BYTES) {
                byte[] head = chunks.peekFirst();
                int take = Math.min(head.length - headOffset, FRAME_BYTES - written);
                System.arraycopy(head, headOffset, out, written, take);
                written += take;
                headOffset += take;
                if (headOffset >= head.length) {
                    chunks.pollFirst();
                    headOffset = 0;
                }
            }
            available -= FRAME_BYTES;
            if (available < MAX_BUFFERED_BYTES / 2) {
                overflowLogged = false; // room again; allow a future overflow to log once more
            }
        }
        return ByteBuffer.wrap(out);
    }

    @Override
    public boolean isOpus() {
        return false;
    }
}
