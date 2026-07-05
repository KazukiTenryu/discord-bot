package bot.maya;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * WebSocket client for Sesame's real-time voice API (characters "Maya" / "Miles"), ported to Java
 * from the unofficial reference client (github.com/ijub/sesame_ai).
 *
 * <p>Protocol summary:
 *
 * <ul>
 *   <li>Connect to {@code wss://sesameai.app/agent-service-0/v1/connect} with the Firebase ID token,
 *       client name, user context and character as query parameters.
 *   <li>Server sends {@code initialize} (with a session id) → we reply with {@code
 *       client_location_state} and {@code call_connect}.
 *   <li>Server sends {@code call_connect_response} (call id + its output sample rate) → the call is
 *       live.
 *   <li>Both sides then stream {@code audio} messages carrying base64 16-bit mono PCM. We send at 16
 *       kHz; the server sends at the rate it advertised (typically 24 kHz).
 * </ul>
 *
 * <p>All outgoing frames are serialised through a single sender thread because {@link WebSocket}
 * permits only one in-flight send at a time. Received audio is buffered in a bounded queue that
 * drops the oldest chunk when full, matching the reference client's back-pressure behaviour.
 *
 * <p><b>Note:</b> this is an undocumented, reverse-engineered API; it may break or be blocked at any
 * time and likely falls outside Sesame's terms of service.
 */
public class SesameVoiceClient implements VoiceBackendClient {
    private static final Logger LOGGER = LogManager.getLogger(SesameVoiceClient.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final String BASE_WS_URL = "wss://sesameai.app/agent-service-0/v1/connect";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36";
    private static final int CLIENT_SAMPLE_RATE = 16000;
    private static final int DEFAULT_SERVER_SAMPLE_RATE = 24000;
    private static final int INCOMING_QUEUE_CAPACITY = 1000;

    // Sesame's WS endpoint is served with a certificate the JDK does not validate cleanly, so the
    // reference client disables TLS verification for it (ssl.CERT_NONE). We mirror that, scoped to
    // this dedicated client only — the Firebase auth calls in SesameAuthService still use full
    // verification. If Sesame ever fixes their chain this can be flipped to false.
    private static final boolean INSECURE_TLS = true;

    private final SesameAuthService auth;
    private final String character;
    private final String clientName;

    private final BlockingQueue<byte[]> incomingAudio = new LinkedBlockingQueue<>(INCOMING_QUEUE_CAPACITY);
    private final BlockingQueue<String> outgoing = new LinkedBlockingQueue<>();
    private final AtomicBoolean firstAudioReceived = new AtomicBoolean(false);

    private volatile WebSocket webSocket;
    private volatile String sessionId;
    private volatile String callId;
    private volatile int serverSampleRate = DEFAULT_SERVER_SAMPLE_RATE;
    private volatile boolean running;
    private volatile CompletableFuture<Void> connectedFuture;
    private Thread senderThread;

    // Guarded by this instance's monitor via the synchronized send(); mirrors the reference client's
    // "ping before a message whose type differs from the last sent" keep-alive.
    private String lastSentType;

    public SesameVoiceClient(SesameAuthService auth, String character) {
        this(auth, character, "RP-Web");
    }

    public SesameVoiceClient(SesameAuthService auth, String character, String clientName) {
        this.auth = auth;
        this.character = (character == null || character.isBlank()) ? "Maya" : character;
        this.clientName = clientName;
    }

    /**
     * Opens the connection and blocks until the call is live (a {@code call_connect_response} is
     * received) or the timeout elapses.
     *
     * @return {@code true} if the call became live within the timeout
     */
    public boolean connect(Duration timeout) {
        String idToken = auth.validIdToken();
        connectedFuture = new CompletableFuture<>();
        running = true;
        startSenderThread();

        try {
            HttpClient client = buildHttpClient();
            WebSocket.Builder builder = client.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .header("Origin", "https://www.sesame.com")
                    .header("User-Agent", USER_AGENT);
            builder.buildAsync(URI.create(buildUrl(idToken)), new SesameListener())
                    .join();
        } catch (RuntimeException e) {
            LOGGER.error("Failed to open Sesame WebSocket", e);
            shutdown();
            return false;
        }

        try {
            connectedFuture.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (Exception e) {
            LOGGER.error("Timed out waiting for Sesame call to connect", e);
            return false;
        }
    }

    /** Whether the call is fully established (both session and call ids are known). */
    public boolean isConnected() {
        return running && sessionId != null && callId != null;
    }

    /** The sample rate (Hz) of the audio the server sends us; valid once connected. */
    public int serverSampleRate() {
        return serverSampleRate;
    }

    /** The sample rate (Hz) the server expects from us. */
    public int clientSampleRate() {
        return CLIENT_SAMPLE_RATE;
    }

    /**
     * Sends one chunk of microphone audio to the AI.
     *
     * @param pcm16 raw 16-bit little-endian mono PCM at {@link #clientSampleRate()}
     */
    public void sendPcm(byte[] pcm16) {
        if (!isConnected() || pcm16 == null || pcm16.length == 0) {
            return;
        }
        sendAudioBase64(Base64.getEncoder().encodeToString(pcm16));
    }

    /**
     * Retrieves the next chunk of the AI's speech, or {@code null} if none arrives within the
     * timeout. The bytes are raw 16-bit little-endian mono PCM at {@link #serverSampleRate()}.
     */
    public byte[] pollAudio(long timeoutMillis) {
        try {
            return incomingAudio.poll(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** Sends a graceful call-disconnect and closes the socket. */
    public void disconnect() {
        try {
            if (sessionId != null && callId != null) {
                enqueue(callDisconnectMessage());
                // Give the sender thread a moment to flush the disconnect before we tear down.
                Thread.sleep(200);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        WebSocket socket = webSocket;
        if (socket != null) {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
        }
        shutdown();
    }

    // --- Internals -------------------------------------------------------------------------------

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
                            // Block until this send completes; WebSocket allows only one at a time.
                            socket.sendText(message, true).get();
                        } catch (Exception e) {
                            if (running) {
                                LOGGER.warn("Failed to send Sesame message", e);
                            }
                        }
                    }
                },
                "sesame-sender");
        senderThread.setDaemon(true);
        senderThread.start();
    }

    private final class SesameListener implements WebSocket.Listener {
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket ws) {
            SesameVoiceClient.this.webSocket = ws;
            ws.request(1);
            LOGGER.debug("Sesame WebSocket opened");
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
                    LOGGER.error("Error handling Sesame message", e);
                }
            }
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            LOGGER.error("Sesame WebSocket error", error);
            CompletableFuture<Void> f = connectedFuture;
            if (f != null && !f.isDone()) {
                f.completeExceptionally(error);
            }
            running = false;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            LOGGER.info("Sesame WebSocket closed: {} {}", statusCode, reason);
            running = false;
            return null;
        }
    }

    private void handleMessage(String message) {
        JsonNode data = OBJECT_MAPPER.readTree(message);
        String type = data.path("type").asText();
        switch (type) {
            case "initialize" -> {
                sessionId = data.path("session_id").asText();
                LOGGER.debug("Sesame session id {}", sessionId);
                // These precede a live call, so they bypass the ping/keep-alive logic.
                enqueue(clientLocationStateMessage());
                enqueue(callConnectMessage());
            }
            case "call_connect_response" -> {
                sessionId = data.path("session_id").asText(sessionId);
                callId = data.path("call_id").asText();
                JsonNode content = data.path("content");
                serverSampleRate = content.path("sample_rate").asInt(serverSampleRate);
                LOGGER.info(
                        "Sesame call connected (character {}, session {}, call {}, {} Hz)",
                        character,
                        sessionId,
                        callId,
                        serverSampleRate);
                CompletableFuture<Void> f = connectedFuture;
                if (f != null) {
                    f.complete(null);
                }
            }
            case "audio" -> handleAudio(data);
            case "call_disconnect_response" -> {
                LOGGER.info("Sesame call disconnected");
                callId = null;
                running = false;
            }
            case "ping_response" -> {
                /* keep-alive ack, nothing to do */
            }
            default -> LOGGER.debug("Unhandled Sesame message type: {}", type);
        }
    }

    private void handleAudio(JsonNode data) {
        String b64 = data.path("content").path("audio_data").asText("");
        if (b64.isEmpty()) {
            return;
        }
        byte[] pcm;
        try {
            pcm = Base64.getDecoder().decode(b64);
        } catch (IllegalArgumentException e) {
            LOGGER.warn("Received malformed base64 audio from Sesame", e);
            return;
        }
        offerIncoming(pcm);

        if (firstAudioReceived.compareAndSet(false, true)) {
            // The reference client primes the stream with two chunks of silence once the AI starts
            // speaking; without them the server can stall waiting for client audio. This literal is
            // already base64 (1280 bytes of zeros).
            String primer = "A".repeat(1707) + "=";
            sendAudioBase64(primer);
            sendAudioBase64(primer);
        }
    }

    private void offerIncoming(byte[] pcm) {
        if (!incomingAudio.offer(pcm)) {
            incomingAudio.poll(); // drop oldest to make room, prioritising fresh audio
            incomingAudio.offer(pcm);
        }
    }

    private void sendAudioBase64(String base64Pcm) {
        if (sessionId == null || callId == null) {
            return;
        }
        ObjectNode content = OBJECT_MAPPER.createObjectNode();
        content.put("audio_data", base64Pcm);
        ObjectNode message = baseMessage("audio", false);
        message.set("content", content);
        send("audio", message.toString());
    }

    /**
     * Applies the reference client's keep-alive rule (send a ping whenever the outgoing message type
     * changes) and enqueues the message. Control messages skip the rule.
     */
    private synchronized void send(String type, String json) {
        boolean control = type.equals("ping") || type.equals("call_connect") || type.equals("call_disconnect");
        if (callId != null && !control) {
            if (lastSentType == null || !type.equals(lastSentType)) {
                enqueue(pingMessage());
            }
            lastSentType = type;
        }
        enqueue(json);
    }

    private void enqueue(String json) {
        outgoing.offer(json);
    }

    // --- Message builders ------------------------------------------------------------------------

    private ObjectNode baseMessage(String type, boolean withRequestId) {
        ObjectNode message = OBJECT_MAPPER.createObjectNode();
        message.put("type", type);
        message.put("session_id", sessionId);
        if (callId != null) {
            message.put("call_id", callId);
        } else {
            message.putNull("call_id");
        }
        if (withRequestId) {
            message.put("request_id", UUID.randomUUID().toString());
        }
        return message;
    }

    private String pingMessage() {
        ObjectNode message = baseMessage("ping", true);
        message.put("content", "ping");
        return message.toString();
    }

    private String clientLocationStateMessage() {
        ObjectNode content = OBJECT_MAPPER.createObjectNode();
        content.put("latitude", 0);
        content.put("longitude", 0);
        content.put("address", "");
        content.put("timezone", "America/Chicago");
        ObjectNode message = baseMessage("client_location_state", false);
        message.set("content", content);
        return message.toString();
    }

    private String callConnectMessage() {
        ObjectNode settings = OBJECT_MAPPER.createObjectNode();
        settings.put("preset", character);

        ObjectNode metadata = OBJECT_MAPPER.createObjectNode();
        metadata.put("language", "en-US");
        metadata.put("user_agent", USER_AGENT);
        metadata.put("mobile_browser", false);
        metadata.set("media_devices", mediaDevices());

        ObjectNode content = OBJECT_MAPPER.createObjectNode();
        content.put("sample_rate", CLIENT_SAMPLE_RATE);
        content.put("audio_codec", "none");
        content.put("reconnect", false);
        content.put("is_private", false);
        content.put("client_name", clientName);
        content.set("settings", settings);
        content.set("client_metadata", metadata);

        ObjectNode message = baseMessage("call_connect", true);
        message.set("content", content);
        return message.toString();
    }

    private String callDisconnectMessage() {
        ObjectNode content = OBJECT_MAPPER.createObjectNode();
        content.put("reason", "user_request");
        ObjectNode message = baseMessage("call_disconnect", true);
        message.set("content", content);
        return message.toString();
    }

    private ArrayNode mediaDevices() {
        ArrayNode devices = OBJECT_MAPPER.createArrayNode();
        devices.add(device("audioinput", "Default - Microphone"));
        devices.add(device("audiooutput", "Default - Speaker"));
        return devices;
    }

    private ObjectNode device(String kind, String label) {
        ObjectNode device = OBJECT_MAPPER.createObjectNode();
        device.put("deviceId", "default");
        device.put("kind", kind);
        device.put("label", label);
        device.put("groupId", "default");
        return device;
    }

    private String buildUrl(String idToken) {
        String userContext = "{\"timezone\":\"America/Chicago\"}";
        return BASE_WS_URL
                + "?id_token=" + enc(idToken)
                + "&client_name=" + enc(clientName)
                + "&usercontext=" + enc(userContext)
                + "&character=" + enc(character);
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static HttpClient buildHttpClient() {
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15));
        if (INSECURE_TLS) {
            try {
                SSLContext ctx = SSLContext.getInstance("TLS");
                ctx.init(null, new TrustManager[] {TRUST_ALL}, new SecureRandom());
                builder.sslContext(ctx);
            } catch (Exception e) {
                LOGGER.warn("Could not install permissive TLS context; using default verification", e);
            }
        }
        return builder.build();
    }

    // Trusts any server certificate. Used only for the Sesame WS endpoint (see INSECURE_TLS).
    private static final X509TrustManager TRUST_ALL = new X509TrustManager() {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {}

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {}

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    };
}
