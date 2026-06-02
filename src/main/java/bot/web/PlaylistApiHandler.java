package bot.web;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import tools.jackson.databind.ObjectMapper;

import bot.slash.music.PlayerManager;
import bot.slash.playlist.PlaylistService;
import bot.slash.playlist.PlaylistService.StoredTrack;

/**
 * Read-only JSON + audio API for the playlist web player:
 *
 * <ul>
 *   <li>{@code GET /api/users} — every user with a playlist (id, name, track count)
 *   <li>{@code GET /api/users/{userId}/tracks} — that user's tracks in order
 *   <li>{@code GET /api/tracks/{id}/audio} — the track decoded to Ogg/Opus, streamed to the browser
 * </ul>
 *
 * The audio endpoint reuses {@link PlayerManager#downloadOgg} (the same pipeline as {@code /song}).
 * Decoded bytes are cached so replays are instant, and HTTP Range requests are honoured so the
 * {@code <audio>} element can seek.
 */
public class PlaylistApiHandler implements HttpHandler {
    private static final Logger LOGGER = LogManager.getLogger(PlaylistApiHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    // Decoding a track is slow and the bytes are large (≤9 MB); cap the cache to a handful of recent
    // tracks (≈ a few hundred MB worst case) using access-order LRU eviction.
    private static final int AUDIO_CACHE_MAX = 12;
    private static final long DECODE_TIMEOUT_SECONDS = 90;

    private final PlaylistService playlistService;
    private final Map<Integer, byte[]> audioCache = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, byte[]> eldest) {
            return size() > AUDIO_CACHE_MAX;
        }
    });

    public PlaylistApiHandler(PlaylistService playlistService) {
        this.playlistService = playlistService;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendText(exchange, 405, "Method Not Allowed");
                return;
            }

            String path = exchange.getRequestURI().getPath();
            if (path.equals("/api/users")) {
                sendJson(exchange, playlistService.listOwners());
                return;
            }

            // /api/users/{userId}/tracks
            if (path.startsWith("/api/users/") && path.endsWith("/tracks")) {
                String userId = path.substring("/api/users/".length(), path.length() - "/tracks".length());
                sendJson(exchange, playlistService.getTracks(userId));
                return;
            }

            // /api/tracks/{id}/audio
            if (path.startsWith("/api/tracks/") && path.endsWith("/audio")) {
                String idPart = path.substring("/api/tracks/".length(), path.length() - "/audio".length());
                handleAudio(exchange, idPart);
                return;
            }

            sendText(exchange, 404, "Not Found");
        } catch (Exception e) {
            LOGGER.error("Error handling {}", exchange.getRequestURI(), e);
            safeError(exchange);
        }
    }

    private void handleAudio(HttpExchange exchange, String idPart) throws IOException {
        int id;
        try {
            id = Integer.parseInt(idPart);
        } catch (NumberFormatException e) {
            sendText(exchange, 400, "Bad track id");
            return;
        }

        StoredTrack track = playlistService.getTrack(id);
        if (track == null) {
            sendText(exchange, 404, "Track not found");
            return;
        }

        byte[] audio;
        try {
            audio = audioFor(track);
        } catch (Exception e) {
            LOGGER.warn("Couldn't decode track {} ({})", id, track.uri(), e);
            // 502: the track exists but the upstream source couldn't be decoded (e.g. broken YouTube).
            sendText(exchange, 502, "Couldn't stream that track.");
            return;
        }

        sendAudio(exchange, audio);
    }

    /** Returns the cached Ogg bytes for a track, decoding (and caching) on first request. */
    private byte[] audioFor(StoredTrack track) throws Exception {
        byte[] cached = audioCache.get(track.id());
        if (cached != null) {
            return cached;
        }
        byte[] ogg = PlayerManager.getInstance()
                .downloadOgg(track.uri())
                .get(DECODE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .ogg();
        audioCache.put(track.id(), ogg);
        return ogg;
    }

    // ---- response helpers -------------------------------------------------------------------

    private void sendAudio(HttpExchange exchange, byte[] audio) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "audio/ogg");
        exchange.getResponseHeaders().set("Accept-Ranges", "bytes");

        String range = exchange.getRequestHeaders().getFirst("Range");
        if (range == null || !range.startsWith("bytes=")) {
            exchange.sendResponseHeaders(200, audio.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(audio);
            }
            return;
        }

        // Partial content: parse "bytes=start-end" (either bound may be omitted).
        long start = 0;
        long end = audio.length - 1;
        try {
            String[] bounds = range.substring("bytes=".length()).split("-", 2);
            if (!bounds[0].isEmpty()) {
                start = Long.parseLong(bounds[0]);
            }
            if (bounds.length > 1 && !bounds[1].isEmpty()) {
                end = Long.parseLong(bounds[1]);
            }
        } catch (NumberFormatException e) {
            start = 0;
            end = audio.length - 1;
        }

        if (start < 0 || start >= audio.length || end < start) {
            exchange.getResponseHeaders().set("Content-Range", "bytes */" + audio.length);
            exchange.sendResponseHeaders(416, -1);
            exchange.close();
            return;
        }
        end = Math.min(end, audio.length - 1);
        int length = (int) (end - start + 1);

        exchange.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + end + "/" + audio.length);
        exchange.sendResponseHeaders(206, length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(audio, (int) start, length);
        }
    }

    private void sendJson(HttpExchange exchange, Object body) throws IOException {
        byte[] bytes = MAPPER.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private void sendText(HttpExchange exchange, int status, String message) throws IOException {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private void safeError(HttpExchange exchange) {
        try {
            sendText(exchange, 500, "Internal Server Error");
        } catch (IOException ignored) {
            exchange.close();
        }
    }
}
