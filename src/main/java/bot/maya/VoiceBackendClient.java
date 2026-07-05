package bot.maya;

import java.time.Duration;

/**
 * A realtime voice-AI backend the Discord bridge can talk to: connect, stream microphone PCM up,
 * and pull the AI's speech back down. Keeps the audio bridge ({@link MayaSession} and the
 * send/receive handlers) independent of which service is behind it.
 *
 * <p>All PCM is signed 16-bit little-endian mono. We send at {@link #clientSampleRate()} and receive
 * at {@link #serverSampleRate()}.
 */
public interface VoiceBackendClient {
    /** Connects and blocks until the conversation is live or the timeout elapses. */
    boolean connect(Duration timeout);

    /** Whether the conversation is currently established. */
    boolean isConnected();

    /** Sample rate (Hz) of the audio the backend sends us. */
    int serverSampleRate();

    /** Sample rate (Hz) the backend expects from us. */
    int clientSampleRate();

    /** Sends one chunk of microphone PCM to the backend. */
    void sendPcm(byte[] pcm16);

    /** Returns the next chunk of the AI's speech, or {@code null} if none arrives within the timeout. */
    byte[] pollAudio(long timeoutMillis);

    /** Ends the conversation and closes the connection. */
    void disconnect();

    /**
     * Registers a callback invoked when the backend signals the AI was interrupted, so buffered
     * playback can be flushed. No-op by default.
     */
    default void setInterruptionListener(Runnable listener) {}
}
