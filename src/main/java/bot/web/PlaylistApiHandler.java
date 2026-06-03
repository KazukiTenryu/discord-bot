package bot.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import bot.config.Config;
import bot.slash.music.PlayerManager;
import bot.slash.playlist.PlaylistService;
import bot.slash.playlist.PlaylistService.Playlist;
import bot.slash.playlist.PlaylistService.SpotifyAccount;
import bot.slash.playlist.PlaylistService.StoredTrack;
import bot.slash.playlist.PlaylistService.TokenOwner;
import bot.slash.playlist.SpotifyService;
import bot.slash.playlist.SpotifyService.SpotifyPlaylist;
import bot.slash.playlist.SpotifyService.SpotifyTrack;
import bot.slash.playlist.SpotifyService.Tokens;
import bot.utils.DiscoverService;
import bot.utils.LyricsService;
import tools.jackson.databind.JsonNode;
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

    // OAuth state tokens are short (18 random bytes → 24 url-safe chars); ample for a one-time nonce.
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder STATE_ENCODER = Base64.getUrlEncoder().withoutPadding();
    // Refresh a Spotify access token if it expires within this margin (clock-skew / in-flight safety).
    private static final long TOKEN_REFRESH_MARGIN_SECONDS = 60;

    /** Live progress of a user's Spotify import, polled by the web UI. */
    private static final class ImportProgress {
        final String playlistName;
        final int total;
        final AtomicInteger processed = new AtomicInteger();
        final AtomicInteger added = new AtomicInteger();
        volatile boolean done;

        ImportProgress(String playlistName, int total) {
            this.playlistName = playlistName;
            this.total = total;
        }
    }

    private final PlaylistService playlistService;
    // Null when Spotify import isn't configured; the /api/spotify/* endpoints then report disabled.
    private final SpotifyService spotifyService;
    private final Config config;
    // At most one running import per user, keyed by Discord user id.
    private final Map<String, ImportProgress> imports = new ConcurrentHashMap<>();
    private final LyricsService lyricsService = new LyricsService();
    private final DiscoverService discoverService = new DiscoverService();
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

    public PlaylistApiHandler(PlaylistService playlistService, SpotifyService spotifyService, Config config) {
        this.playlistService = playlistService;
        this.spotifyService = spotifyService;
        this.config = config;
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
            if ("POST".equalsIgnoreCase(method)) {
                if (path.equals("/api/playlist/tracks")) {
                    handleAddTrack(exchange);
                    return;
                }
                // POST /api/playlist/tracks/{id}/move — move a track to another of the caller's playlists
                if (path.startsWith("/api/playlist/tracks/") && path.endsWith("/move")) {
                    String id = path.substring("/api/playlist/tracks/".length(), path.length() - "/move".length());
                    handleMoveTrack(exchange, id);
                    return;
                }
                if (path.equals("/api/playlist/create")) {
                    handleCreatePlaylist(exchange);
                    return;
                }
                // POST /api/playlists/{id}/rename
                if (path.startsWith("/api/playlists/") && path.endsWith("/rename")) {
                    String id = path.substring("/api/playlists/".length(), path.length() - "/rename".length());
                    handleRenamePlaylist(exchange, id);
                    return;
                }
                if (path.equals("/api/playlist/image")) {
                    handleSetImage(exchange);
                    return;
                }
                if (path.equals("/api/spotify/import")) {
                    handleSpotifyImport(exchange);
                    return;
                }
            }
            if ("DELETE".equalsIgnoreCase(method)) {
                // DELETE /api/playlists/{id} — delete a whole playlist (must be checked before tracks/)
                if (path.startsWith("/api/playlists/")) {
                    handleDeletePlaylist(exchange, path.substring("/api/playlists/".length()));
                    return;
                }
                if (path.startsWith("/api/playlist/tracks/")) {
                    handleRemoveTrack(exchange, path.substring("/api/playlist/tracks/".length()));
                    return;
                }
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
        if (path.equals("/api/discover")) {
            sendJson(exchange, discoverService.discover());
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
        if (path.startsWith("/api/spotify/")) {
            handleSpotifyGet(exchange, path);
            return;
        }
        // /api/users/{userId}/playlists — the user's named playlists with track counts
        if (path.startsWith("/api/users/") && path.endsWith("/playlists")) {
            String userId = path.substring("/api/users/".length(), path.length() - "/playlists".length());
            sendJson(exchange, playlistService.listPlaylists(userId));
            return;
        }
        // /api/playlists/{playlistId}/tracks — a single playlist's tracks in order
        if (path.startsWith("/api/playlists/") && path.endsWith("/tracks")) {
            String idPart = path.substring("/api/playlists/".length(), path.length() - "/tracks".length());
            Integer playlistId = parseIntOrNull(idPart);
            if (playlistId == null) {
                sendText(exchange, 400, "Bad playlist id");
                return;
            }
            sendJson(exchange, playlistService.getTracksByPlaylist(playlistId));
            return;
        }
        // /api/playlists/{playlistId}/download — that playlist as a .zip of .ogg files
        if (path.startsWith("/api/playlists/") && path.endsWith("/download")) {
            String idPart = path.substring("/api/playlists/".length(), path.length() - "/download".length());
            Integer playlistId = parseIntOrNull(idPart);
            if (playlistId == null) {
                sendText(exchange, 400, "Bad playlist id");
                return;
            }
            Playlist playlist = playlistService.getPlaylist(playlistId);
            String label = playlist == null ? "playlist" : playlist.userName() + " - " + playlist.name();
            handleDownload(exchange, playlistService.getTracksByPlaylist(playlistId), label);
            return;
        }
        // /api/users/{userId}/tracks — all of a user's tracks (kept for compatibility / "download all")
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
        JsonNode body = readJsonBody(exchange);
        String uri = body == null ? null : text(body, "uri");
        if (uri == null || uri.isBlank()) {
            sendText(exchange, 400, "Missing 'uri'.");
            return;
        }
        // Add to the requested playlist (if it's the caller's), else their default.
        Playlist target = resolveOwnPlaylist(owner, body == null ? null : intOrNull(body, "playlistId"));
        if (target == null) {
            sendText(exchange, 404, "No such playlist.");
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
        sendJson(exchange, playlistService.addTrack(owner.userId(), owner.userName(), target.id(), info));
    }

    /**
     * Resolves the caller's playlist by id (verifying ownership), or their default when {@code id} is
     * null. Returns {@code null} when the id is given but isn't one of the caller's playlists.
     */
    private Playlist resolveOwnPlaylist(TokenOwner owner, Integer id) {
        if (id == null) {
            return playlistService.ensureDefaultPlaylist(owner.userId(), owner.userName());
        }
        Playlist playlist = playlistService.getPlaylist(id);
        return (playlist != null && playlist.userId().equals(owner.userId())) ? playlist : null;
    }

    private void handleMoveTrack(HttpExchange exchange, String idPart) throws IOException {
        TokenOwner owner = authenticate(exchange);
        if (owner == null) {
            sendText(exchange, 401, "Unauthorized");
            return;
        }
        Integer trackId = parseIntOrNull(idPart);
        JsonNode body = readJsonBody(exchange);
        Integer targetPlaylistId = body == null ? null : intOrNull(body, "playlistId");
        if (trackId == null || targetPlaylistId == null) {
            sendText(exchange, 400, "Missing track id or 'playlistId'.");
            return;
        }
        boolean moved = playlistService.moveTrack(owner.userId(), trackId, targetPlaylistId);
        sendText(exchange, moved ? 200 : 404, moved ? "OK" : "Not Found");
    }

    private void handleCreatePlaylist(HttpExchange exchange) throws IOException {
        TokenOwner owner = authenticate(exchange);
        if (owner == null) {
            sendText(exchange, 401, "Unauthorized");
            return;
        }
        JsonNode body = readJsonBody(exchange);
        String name = body == null ? null : text(body, "name");
        if (name == null || name.isBlank() || name.length() > 80) {
            sendText(exchange, 400, "Bad playlist name.");
            return;
        }
        Playlist created = playlistService.createPlaylist(owner.userId(), owner.userName(), name.trim());
        if (created == null) {
            sendText(exchange, 409, "You already have a playlist with that name.");
            return;
        }
        sendJson(exchange, created);
    }

    private void handleRenamePlaylist(HttpExchange exchange, String idPart) throws IOException {
        TokenOwner owner = authenticate(exchange);
        if (owner == null) {
            sendText(exchange, 401, "Unauthorized");
            return;
        }
        Integer playlistId = parseIntOrNull(idPart);
        JsonNode body = readJsonBody(exchange);
        String name = body == null ? null : text(body, "name");
        if (playlistId == null || name == null || name.isBlank() || name.length() > 80) {
            sendText(exchange, 400, "Bad playlist id or name.");
            return;
        }
        boolean renamed = playlistService.renamePlaylist(owner.userId(), playlistId, name.trim());
        sendText(exchange, renamed ? 200 : 409, renamed ? "OK" : "Couldn't rename (name taken or not yours).");
    }

    private void handleDeletePlaylist(HttpExchange exchange, String idPart) throws IOException {
        TokenOwner owner = authenticate(exchange);
        if (owner == null) {
            sendText(exchange, 401, "Unauthorized");
            return;
        }
        Integer playlistId = parseIntOrNull(idPart);
        if (playlistId == null) {
            sendText(exchange, 400, "Bad playlist id");
            return;
        }
        boolean deleted = playlistService.deletePlaylist(owner.userId(), playlistId);
        sendText(exchange, deleted ? 200 : 409, deleted ? "OK" : "Can't delete (default playlist or not yours).");
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

    /** Parses a small JSON request body, or {@code null} if it's empty/oversized/unparseable. */
    private JsonNode readJsonBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            byte[] body = in.readNBytes((int) MAX_BODY_BYTES);
            if (body.length == 0) {
                return null;
            }
            return MAPPER.readTree(body);
        } catch (Exception e) {
            return null;
        }
    }

    /** A string field of a JSON object, or {@code null} if absent/null/blank. */
    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String s = value.asString();
        return (s == null || s.isBlank()) ? null : s;
    }

    /** An int field of a JSON object, or {@code null} if absent/not an integer. */
    private static Integer intOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isInt() || value.isNumber() ? value.asInt() : null;
    }

    private static Integer parseIntOrNull(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
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
        String owner = playlistService.listOwners().stream()
                .filter(o -> o.userId().equals(userId))
                .map(PlaylistService.PlaylistOwner::userName)
                .findFirst()
                .orElse("playlist");
        handleDownload(exchange, playlistService.getTracks(userId), owner);
    }

    /** Streams the given tracks as a {@code .zip} of {@code .ogg} files named after {@code label}. */
    private void handleDownload(HttpExchange exchange, List<StoredTrack> tracks, String label) throws IOException {
        if (tracks.isEmpty()) {
            sendText(exchange, 404, "Empty playlist.");
            return;
        }
        String zipName = safeFilename(label) + " playlist.zip";
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
                    LOGGER.warn("Skipping un-downloadable track {} ({}) in zip for {}", track.id(), track.uri(), label);
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
        LOGGER.info("Zipped {}/{} tracks for {} download", ok, tracks.size(), label);
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

    // ---- Spotify import ---------------------------------------------------------------------

    /** Dispatches the {@code GET /api/spotify/*} endpoints. The callback is the only one with no token. */
    private void handleSpotifyGet(HttpExchange exchange, String path) throws IOException {
        if (path.equals("/api/spotify/callback")) {
            handleSpotifyCallback(exchange);
            return;
        }
        TokenOwner owner = authenticate(exchange);
        if (owner == null) {
            sendText(exchange, 401, "Unauthorized");
            return;
        }
        switch (path) {
            case "/api/spotify/status" -> {
                boolean configured = spotifyService != null;
                boolean connected = configured && playlistService.getSpotifyAccount(owner.userId()) != null;
                sendJson(exchange, Map.of("configured", configured, "connected", connected));
            }
            case "/api/spotify/login" -> handleSpotifyLogin(exchange, owner);
            case "/api/spotify/playlists" -> handleSpotifyPlaylists(exchange, owner);
            case "/api/spotify/import/status" -> {
                ImportProgress p = imports.get(owner.userId());
                if (p == null) {
                    sendJson(exchange, Map.of("running", false));
                } else {
                    sendJson(
                            exchange,
                            Map.of(
                                    "running",
                                    !p.done,
                                    "done",
                                    p.done,
                                    "total",
                                    p.total,
                                    "processed",
                                    p.processed.get(),
                                    "added",
                                    p.added.get(),
                                    "playlist",
                                    p.playlistName));
                }
            }
            default -> sendText(exchange, 404, "Not Found");
        }
    }

    private void handleSpotifyLogin(HttpExchange exchange, TokenOwner owner) throws IOException {
        if (spotifyService == null) {
            sendText(exchange, 404, "Spotify import isn't configured.");
            return;
        }
        byte[] bytes = new byte[18];
        RANDOM.nextBytes(bytes);
        String state = STATE_ENCODER.encodeToString(bytes);
        playlistService.putOAuthState(state, owner.userId());
        sendJson(exchange, Map.of("url", spotifyService.buildAuthorizeUrl(state)));
    }

    /**
     * Spotify's redirect back to us. There's no token header on a top-level redirect, so identity comes
     * from the one-time {@code state} we minted during the token-authed login. Always 302s back to the SPA.
     */
    private void handleSpotifyCallback(HttpExchange exchange) throws IOException {
        String base = config.webBaseUrlOrNull() == null ? "" : config.webBaseUrlOrNull();
        if (spotifyService == null) {
            redirect(exchange, base + "/?spotify=disabled");
            return;
        }
        String code = queryParam(exchange, "code");
        String state = queryParam(exchange, "state");
        if (queryParam(exchange, "error") != null || code == null || state == null) {
            redirect(exchange, base + "/?spotify=denied");
            return;
        }
        String userId = playlistService.consumeOAuthState(state);
        if (userId == null) {
            redirect(exchange, base + "/?spotify=expired");
            return;
        }
        try {
            Tokens tokens = spotifyService.exchangeCode(code);
            String spotifyUserId = null;
            try {
                spotifyUserId = spotifyService.getSpotifyUserId(tokens.accessToken());
            } catch (Exception ignored) {
                // The id is only for display; don't fail the connection over it.
            }
            Instant expiresAt =
                    Instant.now().plusSeconds(Math.max(0, tokens.expiresInSeconds() - TOKEN_REFRESH_MARGIN_SECONDS));
            playlistService.saveSpotifyAccount(
                    userId, tokens.accessToken(), tokens.refreshToken(), expiresAt, tokens.scope(), spotifyUserId);
            redirect(exchange, base + "/?spotify=connected");
        } catch (Exception e) {
            LOGGER.warn("Spotify callback failed for {}", userId, e);
            redirect(exchange, base + "/?spotify=error");
        }
    }

    private void handleSpotifyPlaylists(HttpExchange exchange, TokenOwner owner) throws IOException {
        if (spotifyService == null) {
            sendText(exchange, 404, "Spotify import isn't configured.");
            return;
        }
        String token = validAccessToken(owner.userId());
        if (token == null) {
            sendText(exchange, 401, "Connect Spotify first.");
            return;
        }
        try {
            List<SpotifyPlaylist> out = new ArrayList<>();
            // A synthetic "Liked Songs" entry (trackCount -1 → the UI shows no count).
            out.add(new SpotifyPlaylist(SpotifyService.LIKED_SONGS_ID, "Liked Songs", -1, null));
            out.addAll(spotifyService.listPlaylists(token));
            sendJson(exchange, out);
        } catch (Exception e) {
            LOGGER.warn("Couldn't list Spotify playlists for {}", owner.userId(), e);
            // Surface the underlying Spotify status/body so the player can show why (it's the user's
            // own data; the message is e.g. "...returned status 403").
            sendText(exchange, 502, "Couldn't read your Spotify playlists: " + e.getMessage());
        }
    }

    private void handleSpotifyImport(HttpExchange exchange) throws IOException {
        TokenOwner owner = authenticate(exchange);
        if (owner == null) {
            sendText(exchange, 401, "Unauthorized");
            return;
        }
        if (spotifyService == null) {
            sendText(exchange, 404, "Spotify import isn't configured.");
            return;
        }
        ImportProgress running = imports.get(owner.userId());
        if (running != null && !running.done) {
            sendText(exchange, 409, "An import is already running.");
            return;
        }
        JsonNode body = readJsonBody(exchange);
        String source = body == null ? null : text(body, "source");
        if (source == null) {
            sendText(exchange, 400, "Missing 'source'.");
            return;
        }
        String token = validAccessToken(owner.userId());
        if (token == null) {
            sendText(exchange, 401, "Connect Spotify first.");
            return;
        }

        // Resolve (or create) the target local playlist.
        Playlist target;
        String newName = text(body, "newName");
        if (newName != null) {
            target = playlistService.createPlaylist(owner.userId(), owner.userName(), newName.trim());
            if (target == null) {
                target = playlistService.getPlaylistByName(owner.userId(), newName.trim());
            }
        } else {
            target = resolveOwnPlaylist(owner, intOrNull(body, "targetPlaylistId"));
        }
        if (target == null) {
            sendText(exchange, 404, "No such target playlist.");
            return;
        }

        // Reading the track list is network-bound but bounded; do it inline so we can report the count.
        List<SpotifyTrack> tracks;
        try {
            tracks = SpotifyService.LIKED_SONGS_ID.equals(source)
                    ? spotifyService.listLikedTracks(token)
                    : spotifyService.listPlaylistTracks(token, source);
        } catch (Exception e) {
            LOGGER.warn("Spotify import: couldn't read source {} for {}", source, owner.userId(), e);
            sendText(exchange, 502, "Couldn't read that Spotify playlist.");
            return;
        }

        startImport(owner, target, tracks);
        sendJson(exchange, Map.of("started", tracks.size(), "playlist", target.name(), "playlistId", target.id()));
    }

    /**
     * Runs the import on a background daemon thread (each YouTube match takes seconds; a full playlist
     * would otherwise block a web thread for minutes). Progress is published via {@link #imports} and
     * polled by {@code GET /api/spotify/import/status}.
     */
    private void startImport(TokenOwner owner, Playlist target, List<SpotifyTrack> tracks) {
        ImportProgress progress = new ImportProgress(target.name(), tracks.size());
        imports.put(owner.userId(), progress);
        Thread thread = new Thread(
                () -> {
                    try {
                        for (SpotifyTrack track : tracks) {
                            String query = "ytsearch:"
                                    + (track.artist() == null ? track.name() : track.artist() + " " + track.name());
                            try {
                                AudioTrackInfo info = PlayerManager.getInstance()
                                        .resolve(query)
                                        .get(RESOLVE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                                if (info != null) {
                                    playlistService.addTrackWithMetadata(
                                            owner.userId(),
                                            owner.userName(),
                                            target.id(),
                                            info,
                                            track.artist(),
                                            track.album(),
                                            track.name());
                                    progress.added.incrementAndGet();
                                }
                            } catch (Exception e) {
                                LOGGER.warn("Spotify import: no YouTube match for '{}'", query);
                            }
                            progress.processed.incrementAndGet();
                        }
                    } finally {
                        progress.done = true;
                        LOGGER.info(
                                "Spotify import for {} into '{}': matched {}/{}",
                                owner.userId(),
                                target.name(),
                                progress.added.get(),
                                progress.total);
                    }
                },
                "spotify-import-" + owner.userId());
        thread.setDaemon(true);
        thread.start();
    }

    /** Returns a valid (refreshed if needed) access token for the user, or {@code null} if unconnected. */
    private String validAccessToken(String userId) {
        SpotifyAccount account = playlistService.getSpotifyAccount(userId);
        if (account == null || spotifyService == null) {
            return null;
        }
        if (account.expiresAt().isAfter(Instant.now().plusSeconds(TOKEN_REFRESH_MARGIN_SECONDS))) {
            return account.accessToken();
        }
        try {
            Tokens tokens = spotifyService.refresh(account.refreshToken());
            String refresh = tokens.refreshToken() != null ? tokens.refreshToken() : account.refreshToken();
            String scope = tokens.scope() != null ? tokens.scope() : account.scope();
            Instant expiresAt =
                    Instant.now().plusSeconds(Math.max(0, tokens.expiresInSeconds() - TOKEN_REFRESH_MARGIN_SECONDS));
            playlistService.saveSpotifyAccount(
                    userId, tokens.accessToken(), refresh, expiresAt, scope, account.spotifyUserId());
            return tokens.accessToken();
        } catch (Exception e) {
            LOGGER.warn("Spotify token refresh failed for {}", userId, e);
            return null;
        }
    }

    private void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().set("Location", location.isEmpty() ? "/" : location);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
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
