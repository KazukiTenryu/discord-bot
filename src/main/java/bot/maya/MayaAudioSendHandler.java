package bot.maya;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;

import net.dv8tion.jda.api.audio.AudioSendHandler;

/**
 * Streams the AI's speech into the Discord voice channel.
 *
 * <p>Maya's audio (mono little-endian PCM at the server's sample rate) is converted to Discord's
 * 48 kHz stereo big-endian PCM by {@link #offer(byte[], int)} and buffered here. JDA pulls it 20 ms
 * at a time via {@link #provide20MsAudio()}; {@link #isOpus()} returns {@code false} so JDA encodes
 * the PCM to Opus itself. When Maya is silent the buffer drains and {@link #canProvide()} returns
 * {@code false}, so nothing is sent.
 */
public class MayaAudioSendHandler implements AudioSendHandler {
    // 20 ms of 48 kHz, 16-bit, stereo PCM = 48000 * 0.02 * 2 channels * 2 bytes.
    private static final int FRAME_BYTES = 3840;
    // Cap the backlog so a burst from the server can't grow memory without bound (~5 s of audio).
    private static final int MAX_BUFFERED_BYTES = FRAME_BYTES * 250;

    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private final byte[] frame = new byte[FRAME_BYTES];

    /** Converts one chunk of Maya's PCM to Discord format and queues it for playback. */
    public void offer(byte[] sesamePcm, int sourceRate) {
        byte[] discordPcm = AudioResampler.sesameToDiscord(sesamePcm, sourceRate);
        synchronized (buffer) {
            if (buffer.size() + discordPcm.length > MAX_BUFFERED_BYTES) {
                return; // drop rather than lag further and further behind real time
            }
            buffer.write(discordPcm, 0, discordPcm.length);
        }
    }

    /** Discards any buffered audio (used when a call ends). */
    public void clear() {
        synchronized (buffer) {
            buffer.reset();
        }
    }

    @Override
    public boolean canProvide() {
        synchronized (buffer) {
            return buffer.size() >= FRAME_BYTES;
        }
    }

    @Override
    public ByteBuffer provide20MsAudio() {
        synchronized (buffer) {
            byte[] all = buffer.toByteArray();
            if (all.length < FRAME_BYTES) {
                return null;
            }
            System.arraycopy(all, 0, frame, 0, FRAME_BYTES);
            // Rewrite the buffer without the frame we just consumed.
            buffer.reset();
            buffer.write(all, FRAME_BYTES, all.length - FRAME_BYTES);
        }
        return ByteBuffer.wrap(Arrays.copyOf(frame, FRAME_BYTES));
    }

    @Override
    public boolean isOpus() {
        return false;
    }
}
