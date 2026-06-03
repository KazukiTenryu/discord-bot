package bot.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import bot.slash.music.PlayerManager;
import bot.slash.playlist.PlaylistService;
import bot.slash.playlist.PlaylistService.StoredTrack;
import bot.slash.playlist.PlaylistService.TokenOwner;
import bot.utils.LyricsService;
import tools.jackson.databind.ObjectMapper;

/**
 * JSON + audio API for the playlist web player. The read endpoints are public; the write endpoints
 * require a per-user token (issued by {@code /playlist link}, sent in the {@code X-Playlist-Token}
 * header) and only ever touch the token owner's own playlist.
 *
 * <ul>
 *   <li>{@code GET /api/users} — every user with a playlist (id, name, track count)
 *   <li>{@code GET /api/users/{userId}/tracks} — that user's tracks in order
 *   <li>{@code GET /api/users/{userId}/download} — the whole playlist as a {@code .zip} of {@code .ogg} files
 *   <li>{@code GET /api/tracks/{id}/audio} — the track decoded to Ogg/Opus, streamed to the browser
 *       ({@code ?download} sends it as a {@code .ogg} attachment instead)
 *   <li>{@code GET /api/lyrics?title=&artist=} — plain lyrics from lrclib.net, or 404 if none match
 *   <li>{@code GET /api/search?q=…} — search results' metadata (no audio decoded)
 *   <li>{@code GET /api/preview?uri=…} — stream a search result by URI (preview before adding)
 *   <li>{@code GET /api/me} (token) — the calling user's id and name
 *   <li>{@code POST /api/playlist/tracks} (token, body {@code {"uri": …}}) — add a track to the caller's playlist
 *   <li>{@code DELETE /api/playlist/tracks/{id}} (token) — remove one of the caller's tracks
 *   <li>{@code GET /api/users/{userId}/image} — that user's custom cover (404 → client uses the gradient)
 *   <li>{@code POST /api/playlist/image} (token, raw image body) — set the caller's custom cover
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
    // Resolving search/add metadata is network-bound but quick; cap the wait so a wedged source can't
    // hold a web-server thread forever.
    private static final long RESOLVE_TIMEOUT_SECONDS = 20;
    private static final int SEARCH_LIMIT = 12;
    private static final long MAX_BODY_BYTES = 4096;
    private static final long IMAGE_MAX_BYTES = 3L * 1024 * 1024; // 3 MB cap for custom covers

    /** A search hit's metadata, mirroring {@link StoredTrack} minus the (not-yet-stored) id. */
    public record SearchResult(String title, String author, String uri, int durationMs, String thumbnailUrl) {}

    private final PlaylistService playlistService;
    private final LyricsService lyricsService = new LyricsService();
    private final Map<Integer, byte[]> audioCache = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, byte[]> eldest) {
            return size() > AUDIO_CACHE_MAX;
        }
    });
    // Search-result previews are streamed by URI (the track isn't stored yet), cached separately.
    private final Map<String, byte[]> previewCache = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
            return size() > AUDIO_CACHE_MAX;
        }
    });

    public PlaylistApiHandler(PlaylistService playlistService) {
        this.playlistService = playlistService;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();

            if ("GET".equalsIgnoreCase(method)) {
                handleGet(exchange, path);
                return;
            }
            if ("POST".equalsIgnoreCase(method) && path.equals("/api/playlist/tracks")) {
                handleAddTrack(exchange);
                return;
            }
            if ("DELETE".equalsIgnoreCase(method) && path.startsWith("/api/playlist/tracks/")) {
                handleRemoveTrack(exchange, path.substring("/api/playlist/tracks/".length()));
                return;
            }
            if ("POST".equalsIgnoreCase(method) && path.equals("/api/playlist/image")) {
                handleSetImage(exchange);
                return;
            }
            sendText(exchange, 405, "Method Not Allowed");
        } catch (Exception e) {
            LOGGER.error("Error handling {}", exchange.getRequestURI(), e);
            safeError(exchange);
        }
    }

    private void handleGet(HttpExchange exchange, String path) throws IOException {
        if (path.equals("/api/users")) {
            sendJson(exchange, playlistService.listOwners());
            return;
        }
        if (path.equals("/api/search")) {
            handleSearch(exchange);
            return;
        }
        if (path.equals("/api/lyrics")) {
            handleLyrics(exchange);
            return;
        }
        if (path.equals("/api/preview")) {
            handlePreview(exchange);
            return;
        }
        if (path.equals("/api/me")) {
            TokenOwner owner = authenticate(exchange);
            if (owner == null) {
                sendText(exchange, 401, "Unauthorized");
                return;
            }
            sendJson(exchange, Map.of("userId", owner.userId(), "userName", owner.userName()));
            return;
        }
        // /api/users/{userId}/tracks
        if (path.startsWith("/api/users/") && path.endsWith("/tracks")) {
            String userId = path.substring("/api/users/".length(), path.length() - "/tracks".length());
            sendJson(exchange, playlistService.getTracks(userId));
            return;
        }
        // /api/users/{userId}/download — the whole playlist as a .zip of .ogg files
        if (path.startsWith("/api/users/") && path.endsWith("/download")) {
            String userId = path.substring("/api/users/".length(), path.length() - "/download".length());
            handlePlaylistDownload(exchange, userId);
            return;
        }
        // /api/users/{userId}/image — that user's custom cover (404 → client uses the gradient)
        if (path.startsWith("/api/users/") && path.endsWith("/image")) {
            String userId = path.substring("/api/users/".length(), path.length() - "/image".length());
            handleUserImage(exchange, userId);
            return;
        }
        // /api/tracks/{id}/audio
        if (path.startsWith("/api/tracks/") && path.endsWith("/audio")) {
            String idPart = path.substring("/api/tracks/".length(), path.length() - "/audio".length());
            handleAudio(exchange, idPart);
            return;
        }
        sendText(exchange, 404, "Not Found");
    }

    // ---- search & edit ----------------------------------------------------------------------

    private void handleSearch(HttpExchange exchange) throws IOException {
        String query = queryParam(exchange, "q");
        if (query == null || query.isBlank()) {
            sendJson(exchange, List.of());
            return;
        }
        String identifier = isUrl(query) ? query : "ytsearch:" + query;
        List<AudioTrackInfo> hits;
        try {
            hits = PlayerManager.getInstance()
                    .search(identifier, SEARCH_LIMIT)
                    .get(RESOLVE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOGGER.warn("Search failed for '{}'", query, e);
            sendText(exchange, 502, "Search failed.");
            return;
        }
        List<SearchResult> results =
                hits.stream().map(PlaylistApiHandler::toSearchResult).toList();
        sendJson(exchange, results);
    }

    private void handleLyrics(HttpExchange exchange) throws IOException {
        String title = queryParam(exchange, "title");
        String artist = queryParam(exchange, "artist");
        if (title == null || title.isBlank()) {
            sendText(exchange, 400, "Missing 'title'.");
            return;
        }
        // Strip "(Official Video)"-style noise that hurts the match, then pair with the artist —
        // the same query shape /lyrics uses.
        String cleaned = title.replaceAll("[(\\[].*?[)\\]]", "").trim();
        String query = (artist == null || artist.isBlank()) ? cleaned : (cleaned + " " + artist).trim();

        Optional<LyricsService.Lyrics> lyrics;
        try {
            lyrics = lyricsService.fetch(query);
        } catch (Exception e) {
            // Lyrics are a nicety — never let a lookup failure surface as an error to the player.
            LOGGER.warn("Lyrics lookup failed for '{}'", query, e);
            sendText(exchange, 404, "No lyrics.");
            return;
        }
        if (lyrics.isEmpty()) {
            sendText(exchange, 404, "No lyrics.");
            return;
        }
        LyricsService.Lyrics found = lyrics.get();
        sendJson(
                exchange,
                Map.of(
                        "trackName", found.trackName(),
                        "artistName", found.artistName(),
                        "plainLyrics", found.plainLyrics()));
    }

    /** Streams a search result by its URI so the user can preview it before adding (no DB row needed). */
    private void handlePreview(HttpExchange exchange) throws IOException {
        String uri = queryParam(exchange, "uri");
        if (uri == null || uri.isBlank()) {
            sendText(exchange, 400, "Missing 'uri'.");
            return;
        }
        byte[] audio = previewCache.get(uri);
        if (audio == null) {
            try {
                audio = PlayerManager.getInstance()
                        .downloadOgg(uri)
                        .get(DECODE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .ogg();
            } catch (Exception e) {
                LOGGER.warn("Couldn't decode preview {}", uri, e);
                sendText(exchange, 502, "Couldn't play that track.");
                return;
            }
            previewCache.put(uri, audio);
        }
        sendAudio(exchange, audio);
    }

    /** Stores the caller's custom playlist cover (raw image bytes in the body, type from Content-Type). */
    private void handleSetImage(HttpExchange exchange) throws IOException {
        TokenOwner owner = authenticate(exchange);
        if (owner == null) {
            sendText(exchange, 401, "Unauthorized");
            return;
        }
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.toLowerCase().startsWith("image/")) {
            sendText(exchange, 415, "Expected an image.");
            return;
        }
        contentType = contentType.split(";", 2)[0].trim(); // drop any ;charset

        byte[] data;
        try (InputStream in = exchange.getRequestBody()) {
            data = in.readNBytes((int) IMAGE_MAX_BYTES + 1);
        }
        if (data.length == 0) {
            sendText(exchange, 400, "Empty body.");
            return;
        }
        if (data.length > IMAGE_MAX_BYTES) {
            sendText(exchange, 413, "Image too large (max 3 MB).");
            return;
        }
        playlistService.setImage(owner.userId(), contentType, data);
        sendText(exchange, 200, "OK");
    }

    /** Serves a user's custom cover, or 404 (the web player then shows the gradient). Public read. */
    private void handleUserImage(HttpExchange exchange, String userId) throws IOException {
        PlaylistService.PlaylistImage image = playlistService.getImage(userId);
        if (image == null) {
            sendText(exchange, 404, "No image");
            return;
        }
        byte[] data = image.data();
        exchange.getResponseHeaders().set("Content-Type", image.contentType());
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, data.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(data);
        }
    }

    private void handleAddTrack(HttpExchange exchange) throws IOException {
        TokenOwner owner = authenticate(exchange);
        if (owner == null) {
            sendText(exchange, 401, "Unauthorized");
            return;
        }
        String uri = readUri(exchange);
        if (uri == null || uri.isBlank()) {
            sendText(exchange, 400, "Missing 'uri'.");
            return;
        }
        AudioTrackInfo info;
        try {
            info = PlayerManager.getInstance().resolve(uri).get(RESOLVE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOGGER.warn("Couldn't resolve '{}' for {}", uri, owner.userId(), e);
            sendText(exchange, 502, "Couldn't add that track.");
            return;
        }
        int id = playlistService.addTrack(owner.userId(), owner.userName(), info);
        int durationMs = info.isStream ? 0 : (int) Math.min(info.length, Integer.MAX_VALUE);
        sendJson(exchange, new StoredTrack(id, info.title, info.author, info.uri, durationMs, info.artworkUrl));
    }

    private void handleRemoveTrack(HttpExchange exchange, String idPart) throws IOException {
        TokenOwner owner = authenticate(exchange);
        if (owner == null) {
            sendText(exchange, 401, "Unauthorized");
            return;
        }
        int id;
        try {
            id = Integer.parseInt(idPart);
        } catch (NumberFormatException e) {
            sendText(exchange, 400, "Bad track id");
            return;
        }
        boolean removed = playlistService.removeTrackById(owner.userId(), id);
        sendText(exchange, removed ? 200 : 404, removed ? "OK" : "Not Found");
    }

    /** Resolves the {@code X-Playlist-Token} header to its owner, or {@code null} if absent/invalid. */
    private TokenOwner authenticate(HttpExchange exchange) {
        return playlistService.resolveToken(exchange.getRequestHeaders().getFirst("X-Playlist-Token"));
    }

    /** Reads the {@code uri} field from a small JSON request body. */
    private String readUri(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            byte[] body = in.readNBytes((int) MAX_BODY_BYTES);
            if (body.length == 0) {
                return null;
            }
            Map<?, ?> json = MAPPER.readValue(body, Map.class);
            Object uri = json.get("uri");
            return uri == null ? null : uri.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** Returns the named query parameter (URL-decoded), or {@code null} if absent. */
    private String queryParam(HttpExchange exchange, String name) {
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null) {
            return null;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            if (key.equals(name)) {
                String value = eq < 0 ? "" : pair.substring(eq + 1);
                return URLDecoder.decode(value, StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static SearchResult toSearchResult(AudioTrackInfo info) {
        int durationMs = info.isStream ? 0 : (int) Math.min(info.length, Integer.MAX_VALUE);
        return new SearchResult(info.title, info.author, info.uri, durationMs, info.artworkUrl);
    }

    private static boolean isUrl(String query) {
        String lower = query.toLowerCase();
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    /**
     * Streams the whole playlist as a {@code .zip} of {@code .ogg} files. Tracks are decoded and zipped
     * on the fly (chunked response). Tracks that can't be decoded (e.g. broken YouTube) are skipped so
     * the rest still download. The response code is committed up front, so failures only drop entries.
     */
    private void handlePlaylistDownload(HttpExchange exchange, String userId) throws IOException {
        List<StoredTrack> tracks = playlistService.getTracks(userId);
        if (tracks.isEmpty()) {
            sendText(exchange, 404, "Empty playlist.");
            return;
        }
        String owner = playlistService.listOwners().stream()
                .filter(o -> o.userId().equals(userId))
                .map(PlaylistService.PlaylistOwner::userName)
                .findFirst()
                .orElse("playlist");
        String zipName = safeFilename(owner) + " playlist.zip";
        String encoded = URLEncoder.encode(zipName, StandardCharsets.UTF_8).replace("+", "%20");

        exchange.getResponseHeaders().set("Content-Type", "application/zip");
        exchange.getResponseHeaders()
                .set(
                        "Content-Disposition",
                        "attachment; filename=\"" + zipName.replaceAll("[^\\x20-\\x7e]", "_") + "\"; filename*=UTF-8''"
                                + encoded);
        exchange.sendResponseHeaders(200, 0); // 0 → chunked; we close the stream when done

        int n = 1, ok = 0;
        try (ZipOutputStream zip = new ZipOutputStream(exchange.getResponseBody())) {
            for (StoredTrack track : tracks) {
                byte[] ogg;
                try {
                    ogg = audioFor(track);
                } catch (Exception e) {
                    LOGGER.warn(
                            "Skipping un-downloadable track {} ({}) in zip for {}", track.id(), track.uri(), userId);
                    n++;
                    continue;
                }
                zip.putNextEntry(new ZipEntry(String.format("%02d - %s.ogg", n, safeFilename(track.title()))));
                zip.write(ogg);
                zip.closeEntry();
                n++;
                ok++;
            }
        }
        LOGGER.info("Zipped {}/{} tracks for {}'s playlist download", ok, tracks.size(), owner);
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

        if (queryParam(exchange, "download") != null) {
            sendDownload(exchange, audio, track);
            return;
        }
        sendAudio(exchange, audio);
    }

    /** Sends the whole file as an attachment so the browser saves it as {@code <title>.ogg}. */
    private void sendDownload(HttpExchange exchange, byte[] audio, StoredTrack track) throws IOException {
        String name = safeFilename(track.title()) + ".ogg";
        // Both filename (ASCII fallback) and RFC 5987 filename* (UTF-8) so non-ASCII titles survive.
        String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        exchange.getResponseHeaders().set("Content-Type", "audio/ogg");
        exchange.getResponseHeaders()
                .set(
                        "Content-Disposition",
                        "attachment; filename=\"" + name.replaceAll("[^\\x20-\\x7e]", "_") + "\"; filename*=UTF-8''"
                                + encoded);
        exchange.sendResponseHeaders(200, audio.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(audio);
        }
    }

    /** Trims a track title down to a filesystem-friendly base filename. */
    private static String safeFilename(String title) {
        String cleaned = (title == null || title.isBlank() ? "track" : title)
                .replaceAll("[\\\\/:*?\"<>|]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return cleaned.length() > 80 ? cleaned.substring(0, 80).trim() : cleaned;
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
