package bot.maya;

import java.util.concurrent.atomic.AtomicLong;

import net.dv8tion.jda.api.audio.AudioReceiveHandler;
import net.dv8tion.jda.api.audio.CombinedAudio;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Captures what the humans in the voice channel say and streams it to the voice backend.
 *
 * <p>JDA calls {@link #handleCombinedAudio(CombinedAudio)} every 20 ms with all speakers mixed into
 * one stream (silence when nobody is talking). We down-convert each frame to 16 kHz mono and forward
 * it. Silent frames are forwarded as zeros too, so the backend sees a continuous stream for its
 * turn-taking.
 *
 * <p>Note: JDA only delivers a speaker's audio if that member is cached — the bot must be built with
 * a member-cache policy that includes voice members (see Main).
 *
 * <p>All speakers share one conversation — Discord gives us a single mixed stream, not per-user.
 */
public class MayaAudioReceiveHandler implements AudioReceiveHandler {
    private static final Logger LOGGER = LogManager.getLogger(MayaAudioReceiveHandler.class);
    // 20 ms of 16 kHz mono 16-bit PCM = 16000 * 0.02 * 2 bytes.
    private static final int SILENCE_FRAME_BYTES = 640;
    private static final byte[] SILENCE = new byte[SILENCE_FRAME_BYTES];
    // Log an uplink summary every ~5s (250 * 20ms) so we can see whether Discord is delivering the
    // users' audio, without spamming the logs.
    private static final long LOG_EVERY_FRAMES = 250;

    private final VoiceBackendClient client;
    private final AtomicLong frames = new AtomicLong();
    private final AtomicLong framesWithVoice = new AtomicLong();

    public MayaAudioReceiveHandler(VoiceBackendClient client) {
        this.client = client;
    }

    @Override
    public boolean canReceiveCombined() {
        return true;
    }

    @Override
    public boolean canReceiveUser() {
        return false;
    }

    @Override
    public void handleCombinedAudio(CombinedAudio combinedAudio) {
        if (!client.isConnected()) {
            return;
        }
        byte[] discordPcm = combinedAudio.getAudioData(1.0);

        long total = frames.incrementAndGet();
        if (discordPcm.length > 0) {
            framesWithVoice.incrementAndGet();
        }
        if (total % LOG_EVERY_FRAMES == 0) {
            long voiced = framesWithVoice.getAndSet(0);
            LOGGER.info(
                    "Uplink: {} frames from Discord in last ~5s, {} with speech ({} speakers this frame)",
                    LOG_EVERY_FRAMES,
                    voiced,
                    combinedAudio.getUsers().size());
        }

        if (discordPcm.length == 0) {
            client.sendPcm(SILENCE);
            return;
        }
        client.sendPcm(AudioResampler.discordToSesame(discordPcm, client.clientSampleRate()));
    }
}
