package bot.maya;

import java.time.Duration;

import bot.config.Config;
import bot.config.ConfigLoader;

/**
 * Standalone manual check that the ElevenLabs voice link works end-to-end — signed-URL auth, the
 * WebSocket handshake, and receiving the agent's greeting audio — with no Discord involved. Reads
 * the API key and agent id from config.json.
 *
 * <pre>{@code java -cp build/libs/app.jar bot.maya.ElevenLabsConnectionCheck}</pre>
 */
public final class ElevenLabsConnectionCheck {
    private ElevenLabsConnectionCheck() {}

    public static void main(String[] args) throws Exception {
        Config config = ConfigLoader.loadConfig();
        if (!config.mayaConfigured()) {
            System.err.println("FAILED: eleven_labs_api_key / eleven_labs_agent_id not set in config.json");
            return;
        }

        System.out.println("Connecting to ElevenLabs agent " + config.elevenLabsAgentId() + "...");
        ElevenLabsVoiceClient client =
                new ElevenLabsVoiceClient(config.elevenLabsApiKey(), config.elevenLabsAgentId());

        if (!client.connect(Duration.ofSeconds(15))) {
            System.err.println("FAILED: could not establish the ElevenLabs conversation. See logs above.");
            return;
        }

        System.out.println("Connected. in=" + client.clientSampleRate() + "Hz out=" + client.serverSampleRate() + "Hz");
        System.out.println("Listening ~8s for the agent's greeting audio...");

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
                "Received %d audio chunks, %d bytes (~%.1f s of %d Hz mono PCM).%n",
                chunks, totalBytes, totalBytes / 2.0 / client.serverSampleRate(), client.serverSampleRate());
        System.out.println(totalBytes > 0 ? "SUCCESS: ElevenLabs voice link works." : "WARNING: connected but no audio.");

        client.disconnect();
        System.out.println("Disconnected.");
    }
}
