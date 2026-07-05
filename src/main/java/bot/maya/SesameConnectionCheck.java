package bot.maya;

import java.time.Duration;

/**
 * Standalone manual check that we can authenticate with Sesame, open the voice WebSocket, and
 * receive audio from the character — no Discord involved.
 *
 * <p>Run from a built jar:
 *
 * <pre>{@code java -cp build/libs/app.jar bot.maya.SesameConnectionCheck [character] [email] [password]}</pre>
 *
 * Character defaults to "Maya". If email and password are given it signs in with that real account;
 * otherwise it falls back to anonymous auth, which Sesame's voice endpoint currently rejects (403).
 */
public final class SesameConnectionCheck {
    private SesameConnectionCheck() {}

    public static void main(String[] args) {
        String character = args.length > 0 ? args[0] : "Maya";
        String email = args.length > 1 ? args[1] : null;
        String password = args.length > 2 ? args[2] : null;
        System.out.println("Authenticating with Sesame" + (email != null ? " as " + email : " (anonymous)") + "...");

        SesameAuthService auth =
                email != null ? new SesameAuthService(email, password) : new SesameAuthService();
        SesameVoiceClient client = new SesameVoiceClient(auth, character);

        System.out.println("Connecting to " + character + "...");
        if (!client.connect(Duration.ofSeconds(15))) {
            System.err.println("FAILED: could not establish the Sesame call. Check the logs above.");
            return;
        }

        System.out.println("Connected. Server sample rate: " + client.serverSampleRate() + " Hz");
        System.out.println("Listening for ~8s of audio from " + character + "...");

        long deadline = System.currentTimeMillis() + 8000;
        long totalBytes = 0;
        int chunks = 0;
        while (System.currentTimeMillis() < deadline) {
            byte[] audio = client.pollAudio(1000);
            if (audio != null) {
                totalBytes += audio.length;
                chunks++;
            }
        }

        System.out.printf(
                "Received %d audio chunks, %d bytes (%.1f s of %d Hz mono PCM).%n",
                chunks, totalBytes, totalBytes / 2.0 / client.serverSampleRate(), client.serverSampleRate());
        System.out.println(totalBytes > 0 ? "SUCCESS: Sesame link works." : "WARNING: connected but no audio received.");

        client.disconnect();
        System.out.println("Disconnected.");
    }
}
