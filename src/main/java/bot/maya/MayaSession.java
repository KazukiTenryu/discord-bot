package bot.maya;

import java.time.Duration;

import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.managers.AudioManager;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * One live voice conversation bound to a guild's voice channel: it owns a {@link VoiceBackendClient},
 * wires the receive/send handlers to the guild's {@link AudioManager}, and runs a background thread
 * that pulls the AI's audio and feeds it to the send handler.
 */
public class MayaSession {
    private static final Logger LOGGER = LogManager.getLogger(MayaSession.class);

    private final AudioManager audioManager;
    private final VoiceBackendClient client;
    private final MayaAudioSendHandler sendHandler = new MayaAudioSendHandler();
    private final MayaAudioReceiveHandler receiveHandler;

    private Thread downlinkThread;
    private volatile boolean running;

    public MayaSession(VoiceBackendClient client, AudioChannel channel) {
        this.audioManager = channel.getGuild().getAudioManager();
        this.client = client;
        this.receiveHandler = new MayaAudioReceiveHandler(client);
        // When the AI is interrupted, drop whatever we've buffered so we stop talking over the user.
        this.client.setInterruptionListener(sendHandler::clear);
    }

    /**
     * Connects to the backend and, on success, joins the voice channel and starts streaming both
     * ways.
     *
     * @return {@code true} if the conversation connected and audio is now flowing
     */
    public boolean start(AudioChannel channel) {
        if (!client.connect(Duration.ofSeconds(15))) {
            return false;
        }

        running = true;
        audioManager.setSendingHandler(sendHandler);
        audioManager.setReceivingHandler(receiveHandler);
        audioManager.openAudioConnection(channel);
        startDownlink();
        LOGGER.info("Voice session started in guild {}", channel.getGuild().getId());
        return true;
    }

    /** Ends the conversation, stops streaming, and leaves the voice channel. */
    public void stop() {
        running = false;
        if (downlinkThread != null) {
            downlinkThread.interrupt();
        }
        try {
            client.disconnect();
        } catch (RuntimeException e) {
            LOGGER.warn("Error disconnecting voice backend", e);
        }
        audioManager.setReceivingHandler(null);
        audioManager.setSendingHandler(null);
        audioManager.closeAudioConnection();
        sendHandler.clear();
        LOGGER.info("Voice session stopped");
    }

    private void startDownlink() {
        downlinkThread = new Thread(
                () -> {
                    while (running) {
                        byte[] audio = client.pollAudio(500);
                        if (audio != null) {
                            sendHandler.offer(audio, client.serverSampleRate());
                        }
                        if (!client.isConnected()) {
                            break;
                        }
                    }
                },
                "maya-downlink");
        downlinkThread.setDaemon(true);
        downlinkThread.start();
    }
}
