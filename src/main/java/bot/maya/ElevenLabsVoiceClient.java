package bot.maya;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Realtime voice client for the ElevenLabs Agents Platform.
 *
 * <p>Flow (see docs at elevenlabs.io/docs/agents-platform/api-reference/agents-platform/websocket):
 *
 * <ul>
 *   <li>Fetch a short-lived signed WebSocket URL from the REST API using the {@code xi-api-key}.
 *   <li>Open the socket and send {@code conversation_initiation_client_data}.
 *   <li>Server replies with {@code conversation_initiation_metadata} (audio formats) → conversation
 *       is live.
 *   <li>We stream {@code {"user_audio_chunk": "<base64 pcm>"}}; the server streams {@code audio}
 *       events ({@code audio_event.audio_base_64}). We answer {@code ping} with {@code pong}.
 * </ul>
 *
 * <p>Audio is 16-bit mono PCM; the agent is configured for {@code pcm_16000} in and out, but the
 * rates are read from the initiation metadata so a differently-configured agent still works. All
 * sends are serialised through one thread (a {@link WebSocket} allows only one in-flight send).
 */
public class ElevenLabsVoiceClient implements VoiceBackendClient {
    private static final Logger LOGGER = LogManager.getLogger(ElevenLabsVoiceClient.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final String SIGNED_URL_ENDPOINT =
            "https://api.elevenlabs.io/v1/convai/conversation/get-signed-url";
    private static final int DEFAULT_SAMPLE_RATE = 16000;
    private static final int INCOMING_QUEUE_CAPACITY = 1000;

    private final String apiKey;
    private final String agentId;

    private final BlockingQueue<byte[]> incomingAudio = new LinkedBlockingQueue<>(INCOMING_QUEUE_CAPACITY);
    private final BlockingQueue<String> outgoing = new LinkedBlockingQueue<>();

    private volatile WebSocket webSocket;
    private volatile boolean running;
    private volatile int serverSampleRate = DEFAULT_SAMPLE_RATE;
    private volatile int clientSampleRate = DEFAULT_SAMPLE_RATE;
    private volatile CompletableFuture<Void> connectedFuture;
    private volatile Runnable interruptionListener;
    private Thread senderThread;

    public ElevenLabsVoiceClient(String apiKey, String agentId) {
        this.apiKey = apiKey;
        this.agentId = agentId;
    }

    @Override
    public boolean connect(Duration timeout) {
        connectedFuture = new CompletableFuture<>();
        running = true;
        startSenderThread();

        try {
            String signedUrl = fetchSignedUrl();
            WebSocket.Builder builder =
                    HttpClient.newHttpClient().newWebSocketBuilder().connectTimeout(Duration.ofSeconds(15));
            builder.buildAsync(URI.create(signedUrl), new ElevenLabsListener()).join();
        } catch (RuntimeException e) {
            LOGGER.error("Failed to open ElevenLabs WebSocket", e);
            shutdown();
            return false;
        }

        try {
            connectedFuture.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (Exception e) {
            LOGGER.error("Timed out waiting for ElevenLabs conversation to start", e);
            return false;
        }
    }

    @Override
    public boolean isConnected() {
        return running && webSocket != null;
    }

    @Override
    public int serverSampleRate() {
        return serverSampleRate;
    }

    @Override
    public int clientSampleRate() {
        return clientSampleRate;
    }

    @Override
    public void sendPcm(byte[] pcm16) {
        if (!isConnected() || pcm16 == null || pcm16.length == 0) {
            return;
        }
        ObjectNode message = OBJECT_MAPPER.createObjectNode();
        message.put("user_audio_chunk", Base64.getEncoder().encodeToString(pcm16));
        outgoing.offer(message.toString());
    }

    @Override
    public byte[] pollAudio(long timeoutMillis) {
        try {
            return incomingAudio.poll(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    @Override
    public void disconnect() {
        running = false;
        WebSocket socket = webSocket;
        if (socket != null) {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
        }
        if (senderThread != null) {
            senderThread.interrupt();
        }
    }

    @Override
    public void setInterruptionListener(Runnable listener) {
        this.interruptionListener = listener;
    }

    // --- Internals -------------------------------------------------------------------------------

    private String fetchSignedUrl() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(SIGNED_URL_ENDPOINT + "?agent_id="
                            + URLEncoder.encode(agentId, StandardCharsets.UTF_8)))
                    .header("xi-api-key", apiKey)
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();
            HttpResponse<String> response =
                    HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException(
                        "get-signed-url failed (status " + response.statusCode() + "): " + response.body());
            }
            String signedUrl = OBJECT_MAPPER.readTree(response.body()).path("signed_url").asText();
            if (signedUrl.isEmpty()) {
                throw new IllegalStateException("get-signed-url returned no signed_url: " + response.body());
            }
            return signedUrl;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Failed to fetch ElevenLabs signed URL", e);
        }
    }

    private void shutdown() {
        running = false;
        if (senderThread != null) {
            senderThread.interrupt();
        }
    }

    private void startSenderThread() {
        senderThread = new Thread(
                () -> {
                    while (running) {
                        String message;
                        try {
                            message = outgoing.poll(200, TimeUnit.MILLISECONDS);
                        } catch (InterruptedException e) {
                            break;
                        }
                        if (message == null) {
                            continue;
                        }
                        WebSocket socket = webSocket;
                        if (socket == null) {
                            continue;
                        }
                        try {
                            socket.sendText(message, true).get();
                        } catch (Exception e) {
                            if (running) {
                                LOGGER.warn("Failed to send ElevenLabs message", e);
                            }
                        }
                    }
                },
                "elevenlabs-sender");
        senderThread.setDaemon(true);
        senderThread.start();
    }

    private final class ElevenLabsListener implements WebSocket.Listener {
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket ws) {
            ElevenLabsVoiceClient.this.webSocket = ws;
            ws.request(1);
            // Kick off the conversation; overrides could go here but the agent's own config is used.
            ObjectNode init = OBJECT_MAPPER.createObjectNode();
            init.put("type", "conversation_initiation_client_data");
            outgoing.offer(init.toString());
            LOGGER.debug("ElevenLabs WebSocket opened");
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            buffer.append(data);
            ws.request(1);
            if (last) {
                String message = buffer.toString();
                buffer.setLength(0);
                try {
                    handleMessage(message);
                } catch (RuntimeException e) {
                    LOGGER.error("Error handling ElevenLabs message", e);
                }
            }
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            LOGGER.error("ElevenLabs WebSocket error", error);
            CompletableFuture<Void> f = connectedFuture;
            if (f != null && !f.isDone()) {
                f.completeExceptionally(error);
            }
            running = false;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            LOGGER.info("ElevenLabs WebSocket closed: {} {}", statusCode, reason);
            running = false;
            return null;
        }
    }

    private void handleMessage(String message) {
        JsonNode data = OBJECT_MAPPER.readTree(message);
        String type = data.path("type").asText();
        switch (type) {
            case "conversation_initiation_metadata" -> {
                JsonNode meta = data.path("conversation_initiation_metadata_event");
                serverSampleRate = parseRate(meta.path("agent_output_audio_format").asText(), serverSampleRate);
                clientSampleRate = parseRate(meta.path("user_input_audio_format").asText(), clientSampleRate);
                LOGGER.info(
                        "ElevenLabs conversation started (in {} Hz, out {} Hz, id {})",
                        clientSampleRate,
                        serverSampleRate,
                        meta.path("conversation_id").asText());
                CompletableFuture<Void> f = connectedFuture;
                if (f != null) {
                    f.complete(null);
                }
            }
            case "audio" -> {
                String b64 = data.path("audio_event").path("audio_base_64").asText("");
                if (!b64.isEmpty()) {
                    try {
                        offerIncoming(Base64.getDecoder().decode(b64));
                    } catch (IllegalArgumentException e) {
                        LOGGER.warn("Malformed base64 audio from ElevenLabs", e);
                    }
                }
            }
            case "ping" -> {
                int eventId = data.path("ping_event").path("event_id").asInt();
                ObjectNode pong = OBJECT_MAPPER.createObjectNode();
                pong.put("type", "pong");
                pong.put("event_id", eventId);
                outgoing.offer(pong.toString());
            }
            case "interruption" -> {
                // The AI was cut off: drop everything queued so we stop talking over the user.
                incomingAudio.clear();
                Runnable listener = interruptionListener;
                if (listener != null) {
                    listener.run();
                }
            }
            case "user_transcript" ->
                LOGGER.info(
                        "Heard user: \"{}\"",
                        data.path("user_transcription_event")
                                .path("user_transcript")
                                .asText());
            case "agent_response" ->
                LOGGER.info(
                        "Maya: \"{}\"",
                        data.path("agent_response_event").path("agent_response").asText());
            case "vad_score", "agent_response_correction", "internal_tentative_agent_response" -> {
                /* not needed for playback */
            }
            default -> LOGGER.debug("Unhandled ElevenLabs message type: {}", type);
        }
    }

    private void offerIncoming(byte[] pcm) {
        if (!incomingAudio.offer(pcm)) {
            incomingAudio.poll();
            incomingAudio.offer(pcm);
        }
    }

    /** Parses an ElevenLabs audio format string like {@code "pcm_16000"} into its sample rate. */
    static int parseRate(String format, int fallback) {
        if (format == null) {
            return fallback;
        }
        int underscore = format.lastIndexOf('_');
        if (underscore < 0) {
            return fallback;
        }
        try {
            return Integer.parseInt(format.substring(underscore + 1));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
