package bot.slash.playlist;

import static bot.database.jooq.Tables.PLAYLIST_TOKENS;
import static bot.database.jooq.Tables.PLAYLIST_TRACKS;
import static org.jooq.impl.DSL.count;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.max;
import static org.jooq.impl.DSL.name;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jooq.Field;

import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;

import bot.database.Database;
import bot.utils.MetadataService;
import bot.utils.MetadataService.TrackMetadata;

/**
 * Persistence for the per-user playlists. A user may own several named {@code playlists}; each
 * {@code playlist_tracks} row belongs to exactly one of them via {@code playlist_id} (every user has
 * one {@code is_default} playlist). Also stores web-player tokens, custom covers and Spotify OAuth
 * connections. Shared by the slash commands and the web API (see {@code bot.web}).
 *
 * <p>The {@code playlists}, {@code spotify_accounts} and {@code spotify_oauth_state} tables and the
 * {@code playlist_id} column are accessed via jOOQ plain SQL / {@link org.jooq.impl.DSL#field} so the
 * service compiles without regenerating the generated code (mirroring the cover-image methods).
 */
public class PlaylistService {
    // playlist_tracks.playlist_id referenced without generated code (see class doc).
    private static final Field<Integer> TRACK_PLAYLIST_ID = field(name("playlist_id"), Integer.class);
    // OAuth state rows older than this are treated as expired (CSRF / abandoned-flow guard).
    private static final int OAUTH_STATE_TTL_MINUTES = 10;
    /**
     * A stored track, as needed by both the {@code /playlist} embeds and the web API. {@code title}
     * is the raw source title (kept for lyrics/search/filenames); {@code author} is the source
     * (YouTube channel) name; {@code artist}, {@code album} and {@code trackName} are the resolved
     * metadata and may be {@code null} when no match was found. Display the canonical
     * {@code trackName} when present, falling back to {@code title}.
     */
    public record StoredTrack(
            int id,
            String title,
            String author,
            String uri,
            int durationMs,
            String thumbnailUrl,
            String artist,
            String album,
            String trackName) {}

    /** A user who owns at least one track, with their latest known display name and track count. */
    public record PlaylistOwner(String userId, String userName, int trackCount) {}

    /** The Discord user a web-player token belongs to. */
    public record TokenOwner(String userId, String userName) {}

    /** A user's custom playlist cover image. */
    public record PlaylistImage(String contentType, byte[] data) {}

    /** A named playlist owned by a user, with its current track count. */
    public record Playlist(
            int id,
            String userId,
            String userName,
            String name,
            String description,
            boolean isDefault,
            int trackCount) {}

    /** A user's stored Spotify OAuth connection. */
    public record SpotifyAccount(
            String userId,
            String accessToken,
            String refreshToken,
            Instant expiresAt,
            String scope,
            String spotifyUserId) {}

    // 24 random bytes → 32-char url-safe token; ample entropy and clean in a URL.
    private static final Logger LOGGER = LogManager.getLogger(PlaylistService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder TOKEN_ENCODER = Base64.getUrlEncoder().withoutPadding();
    // Throttle between backfill lookups to stay friendly to the public iTunes API.
    private static final long BACKFILL_DELAY_MS = 1200;

    private final Database database;
    private final MetadataService metadataService = new MetadataService();

    public PlaylistService(Database database) {
        this.database = database;
    }

    /**
     * Appends {@code info} to the given playlist, refreshing the stored display name, and returns the
     * stored row (including any resolved artist/album metadata). The metadata lookup is best-effort
     * and runs before the DB write so it never holds a connection during network I/O.
     */
    public StoredTrack addTrack(String userId, String userName, int playlistId, AudioTrackInfo info) {
        TrackMetadata md = metadataService.lookup(info.title, info.author).orElse(null);
        return insertTrack(
                userId,
                userName,
                playlistId,
                info,
                md != null ? md.artist() : null,
                md != null ? md.album() : null,
                md != null ? md.title() : null);
    }

    /**
     * Like {@link #addTrack} but with the artist/album/track name supplied by the caller, skipping the
     * iTunes lookup. Used by the Spotify import, which already has canonical metadata for every track
     * (and would otherwise pay the lookup latency hundreds of times).
     */
    public StoredTrack addTrackWithMetadata(
            String userId,
            String userName,
            int playlistId,
            AudioTrackInfo info,
            String artist,
            String album,
            String trackName) {
        return insertTrack(userId, userName, playlistId, info, artist, album, trackName);
    }

    private StoredTrack insertTrack(
            String userId,
            String userName,
            int playlistId,
            AudioTrackInfo info,
            String artist,
            String album,
            String trackName) {
        // duration_ms is an INTEGER column (ms comfortably fits an int); streams have no real length.
        int durationMs = info.isStream ? 0 : (int) Math.min(info.length, Integer.MAX_VALUE);
        int id = database.writeAndProvide(ctx -> ctx.insertInto(PLAYLIST_TRACKS)
                .set(PLAYLIST_TRACKS.USER_ID, userId)
                .set(PLAYLIST_TRACKS.USER_NAME, userName)
                .set(TRACK_PLAYLIST_ID, playlistId)
                .set(PLAYLIST_TRACKS.TITLE, info.title)
                .set(PLAYLIST_TRACKS.AUTHOR, info.author)
                .set(PLAYLIST_TRACKS.URI, info.uri)
                .set(PLAYLIST_TRACKS.DURATION_MS, durationMs)
                .set(PLAYLIST_TRACKS.THUMBNAIL_URL, info.artworkUrl)
                .set(PLAYLIST_TRACKS.ARTIST, artist)
                .set(PLAYLIST_TRACKS.ALBUM, album)
                .set(PLAYLIST_TRACKS.TRACK_NAME, trackName)
                .returning(PLAYLIST_TRACKS.ID)
                .fetchOne()
                .getId());
        return new StoredTrack(
                id, info.title, info.author, info.uri, durationMs, info.artworkUrl, artist, album, trackName);
    }

    /**
     * One-time enrichment of rows added before metadata lookup existed ({@code artist IS NULL}).
     * Best-effort and throttled to stay friendly to the public iTunes API; intended to run on a
     * background daemon thread so it never blocks startup.
     */
    public void backfillMetadata() {
        List<StoredTrack> pending = database.read(ctx -> ctx.selectFrom(PLAYLIST_TRACKS)
                .where(PLAYLIST_TRACKS.ARTIST.isNull())
                .orderBy(PLAYLIST_TRACKS.ID.asc())
                .fetch(PlaylistService::toStoredTrack));
        if (pending.isEmpty()) {
            return;
        }
        LOGGER.info("Backfilling track metadata for {} row(s)…", pending.size());
        int enriched = 0;
        for (StoredTrack t : pending) {
            TrackMetadata md = metadataService.lookup(t.title(), t.author()).orElse(null);
            if (md != null && (md.artist() != null || md.album() != null || md.title() != null)) {
                database.write(ctx -> ctx.update(PLAYLIST_TRACKS)
                        .set(PLAYLIST_TRACKS.ARTIST, md.artist())
                        .set(PLAYLIST_TRACKS.ALBUM, md.album())
                        .set(PLAYLIST_TRACKS.TRACK_NAME, md.title())
                        .where(PLAYLIST_TRACKS.ID.eq(t.id()))
                        .execute());
                enriched++;
            }
            try {
                Thread.sleep(BACKFILL_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOGGER.info("Metadata backfill interrupted after {} row(s).", enriched);
                return;
            }
        }
        LOGGER.info("Metadata backfill complete: enriched {}/{} row(s).", enriched, pending.size());
    }

    /** All of a user's tracks across every playlist, in insertion order (oldest first). */
    public List<StoredTrack> getTracks(String userId) {
        return database.read(ctx -> ctx.selectFrom(PLAYLIST_TRACKS)
                .where(PLAYLIST_TRACKS.USER_ID.eq(userId))
                .orderBy(PLAYLIST_TRACKS.ID.asc())
                .fetch(PlaylistService::toStoredTrack));
    }

    /** A single playlist's tracks in insertion order (oldest first). */
    public List<StoredTrack> getTracksByPlaylist(int playlistId) {
        return database.read(ctx -> ctx.selectFrom(PLAYLIST_TRACKS)
                .where(TRACK_PLAYLIST_ID.eq(playlistId))
                .orderBy(PLAYLIST_TRACKS.ID.asc())
                .fetch(PlaylistService::toStoredTrack));
    }

    /** A single track by id (for the web audio endpoint), or {@code null} if it no longer exists. */
    public StoredTrack getTrack(int id) {
        return database.read(ctx -> ctx.selectFrom(PLAYLIST_TRACKS)
                .where(PLAYLIST_TRACKS.ID.eq(id))
                .fetchOne(PlaylistService::toStoredTrack));
    }

    /**
     * Removes the track at 1-based {@code position} from {@code userId}'s playlist (matching the
     * order shown by {@code /playlist show}). Returns the removed track's title, or {@code null} if
     * the position is out of range.
     */
    public String removeTrack(String userId, int position) {
        return database.writeAndProvide(ctx -> {
            List<Integer> ids = ctx.select(PLAYLIST_TRACKS.ID)
                    .from(PLAYLIST_TRACKS)
                    .where(PLAYLIST_TRACKS.USER_ID.eq(userId))
                    .orderBy(PLAYLIST_TRACKS.ID.asc())
                    .fetch(PLAYLIST_TRACKS.ID);
            if (position < 1 || position > ids.size()) {
                return null;
            }
            int targetId = ids.get(position - 1);
            String title = ctx.select(PLAYLIST_TRACKS.TITLE)
                    .from(PLAYLIST_TRACKS)
                    .where(PLAYLIST_TRACKS.ID.eq(targetId))
                    .fetchOne(PLAYLIST_TRACKS.TITLE);
            ctx.deleteFrom(PLAYLIST_TRACKS)
                    .where(PLAYLIST_TRACKS.ID.eq(targetId))
                    .execute();
            return title;
        });
    }

    /**
     * Removes the track with the given {@code trackId} from {@code userId}'s playlist. The user_id
     * match makes this safe for the web API: a caller can only delete their own tracks. Returns
     * {@code true} if a row was removed.
     */
    public boolean removeTrackById(String userId, int trackId) {
        return database.writeAndProvide(ctx -> ctx.deleteFrom(PLAYLIST_TRACKS)
                        .where(PLAYLIST_TRACKS.ID.eq(trackId))
                        .and(PLAYLIST_TRACKS.USER_ID.eq(userId))
                        .execute())
                > 0;
    }

    /**
     * Issues (or rotates) {@code userId}'s web-player token and returns it. There is one token per
     * user; calling this again replaces the previous one, invalidating any older link.
     */
    public String issueToken(String userId, String userName) {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        String token = TOKEN_ENCODER.encodeToString(bytes);
        database.write(ctx -> ctx.insertInto(PLAYLIST_TOKENS)
                .set(PLAYLIST_TOKENS.TOKEN, token)
                .set(PLAYLIST_TOKENS.USER_ID, userId)
                .set(PLAYLIST_TOKENS.USER_NAME, userName)
                .onConflict(PLAYLIST_TOKENS.USER_ID)
                .doUpdate()
                .set(PLAYLIST_TOKENS.TOKEN, token)
                .set(PLAYLIST_TOKENS.USER_NAME, userName)
                .execute());
        return token;
    }

    /** Resolves a web-player token to its owner, or {@code null} if the token is unknown. */
    public TokenOwner resolveToken(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        return database.read(ctx -> ctx.select(PLAYLIST_TOKENS.USER_ID, PLAYLIST_TOKENS.USER_NAME)
                .from(PLAYLIST_TOKENS)
                .where(PLAYLIST_TOKENS.TOKEN.eq(token))
                .fetchOne(record ->
                        new TokenOwner(record.get(PLAYLIST_TOKENS.USER_ID), record.get(PLAYLIST_TOKENS.USER_NAME))));
    }

    // ---- custom cover images ---------------------------------------------------------------
    // Accessed via jOOQ plain SQL so the BLOB table needs no generated code (one upsert/select/delete).

    /** Stores (or replaces) {@code userId}'s custom playlist cover. */
    public void setImage(String userId, String contentType, byte[] data) {
        database.write(ctx -> ctx.execute(
                "INSERT INTO playlist_images (user_id, content_type, data) VALUES (?, ?, ?) "
                        + "ON CONFLICT(user_id) DO UPDATE SET content_type = excluded.content_type, "
                        + "data = excluded.data, updated_at = datetime('now', 'localtime')",
                userId,
                contentType,
                data));
    }

    /** Returns {@code userId}'s custom cover, or {@code null} if they haven't set one. */
    public PlaylistImage getImage(String userId) {
        return database.read(ctx -> {
            org.jooq.Record record =
                    ctx.fetchOne("SELECT content_type, data FROM playlist_images WHERE user_id = ?", userId);
            return record == null ? null : new PlaylistImage(record.get(0, String.class), record.get(1, byte[].class));
        });
    }

    /** Empties {@code userId}'s playlist, returning the number of tracks removed. */
    public int clear(String userId) {
        return database.writeAndProvide(ctx -> ctx.deleteFrom(PLAYLIST_TRACKS)
                .where(PLAYLIST_TRACKS.USER_ID.eq(userId))
                .execute());
    }

    /** Every user with at least one track, with their latest display name and count. */
    public List<PlaylistOwner> listOwners() {
        Field<Integer> trackCount = count().as("track_count");
        // Selecting MAX(id) alongside the GROUP BY makes SQLite return the bare user_name from that
        // latest row, so renamed users show their most recent name.
        return database.read(ctx -> ctx.select(
                        PLAYLIST_TRACKS.USER_ID, PLAYLIST_TRACKS.USER_NAME, trackCount, max(PLAYLIST_TRACKS.ID))
                .from(PLAYLIST_TRACKS)
                .groupBy(PLAYLIST_TRACKS.USER_ID)
                .orderBy(PLAYLIST_TRACKS.USER_NAME.asc())
                .fetch(record -> new PlaylistOwner(
                        record.get(PLAYLIST_TRACKS.USER_ID),
                        record.get(PLAYLIST_TRACKS.USER_NAME),
                        record.get(trackCount))));
    }

    // ---- named playlists -------------------------------------------------------------------
    // The playlists table and playlist_tracks.playlist_id are accessed via plain SQL so the service
    // needs no regenerated jOOQ code (see class doc); integrity is enforced here, not by a real FK.

    /**
     * Returns {@code userId}'s default playlist, creating it the first time. Also refreshes the stored
     * display name so a renamed user shows their current name. Every per-user entry point funnels
     * through here so a user always has a target playlist.
     */
    public Playlist ensureDefaultPlaylist(String userId, String userName) {
        Playlist existing = database.read(ctx -> {
            org.jooq.Record r = ctx.fetchOne(
                    "SELECT id, name, description FROM playlists WHERE user_id = ? AND is_default = 1", userId);
            return r == null
                    ? null
                    : new Playlist(
                            r.get("id", Integer.class),
                            userId,
                            userName,
                            r.get("name", String.class),
                            r.get("description", String.class),
                            true,
                            0);
        });
        if (existing != null) {
            database.write(
                    ctx -> ctx.execute("UPDATE playlists SET user_name = ? WHERE id = ?", userName, existing.id()));
            return getPlaylist(existing.id());
        }
        int id = database.writeAndProvide(ctx -> {
            ctx.execute(
                    "INSERT INTO playlists (user_id, user_name, name, is_default) VALUES (?, ?, 'My Playlist', 1)",
                    userId,
                    userName);
            return ctx.fetchOne("SELECT last_insert_rowid()").get(0, Integer.class);
        });
        return new Playlist(id, userId, userName, "My Playlist", null, true, 0);
    }

    /**
     * Creates a new (non-default) playlist for the user. Returns the created playlist, or {@code null}
     * if they already have one with that name (enforced by the {@code UNIQUE(user_id, name)} index).
     */
    public Playlist createPlaylist(String userId, String userName, String name) {
        if (getPlaylistByName(userId, name) != null) {
            return null;
        }
        int id = database.writeAndProvide(ctx -> {
            ctx.execute(
                    "INSERT INTO playlists (user_id, user_name, name, is_default) VALUES (?, ?, ?, 0)",
                    userId,
                    userName,
                    name);
            return ctx.fetchOne("SELECT last_insert_rowid()").get(0, Integer.class);
        });
        return new Playlist(id, userId, userName, name, null, false, 0);
    }

    /** A user's playlists with track counts, default first then alphabetical. */
    public List<Playlist> listPlaylists(String userId) {
        return database.read(ctx -> ctx.fetch(
                        "SELECT p.id, p.user_id, p.user_name, p.name, p.description, p.is_default, "
                                + "COUNT(t.id) AS track_count "
                                + "FROM playlists p LEFT JOIN playlist_tracks t ON t.playlist_id = p.id "
                                + "WHERE p.user_id = ? GROUP BY p.id "
                                + "ORDER BY p.is_default DESC, p.name COLLATE NOCASE ASC",
                        userId)
                .map(PlaylistService::toPlaylist));
    }

    /** A single playlist by id (with track count), or {@code null} if it doesn't exist. */
    public Playlist getPlaylist(int playlistId) {
        return database.read(ctx -> {
            org.jooq.Record r = ctx.fetchOne(
                    "SELECT p.id, p.user_id, p.user_name, p.name, p.description, p.is_default, "
                            + "COUNT(t.id) AS track_count "
                            + "FROM playlists p LEFT JOIN playlist_tracks t ON t.playlist_id = p.id "
                            + "WHERE p.id = ? GROUP BY p.id",
                    playlistId);
            return r == null ? null : toPlaylist(r);
        });
    }

    /** A user's playlist by name (case-insensitive), or {@code null} if they have none with that name. */
    public Playlist getPlaylistByName(String userId, String name) {
        return database.read(ctx -> {
            org.jooq.Record r = ctx.fetchOne(
                    "SELECT p.id, p.user_id, p.user_name, p.name, p.description, p.is_default, "
                            + "COUNT(t.id) AS track_count "
                            + "FROM playlists p LEFT JOIN playlist_tracks t ON t.playlist_id = p.id "
                            + "WHERE p.user_id = ? AND p.name = ? COLLATE NOCASE GROUP BY p.id",
                    userId,
                    name);
            return r == null ? null : toPlaylist(r);
        });
    }

    /**
     * Renames {@code userId}'s playlist. Returns {@code false} if the playlist isn't theirs or the new
     * name collides with another of their playlists. The {@code user_id} guard makes this safe for the
     * web API.
     */
    public boolean renamePlaylist(String userId, int playlistId, String newName) {
        Playlist clash = getPlaylistByName(userId, newName);
        if (clash != null && clash.id() != playlistId) {
            return false;
        }
        return database.writeAndProvide(ctx -> ctx.execute(
                        "UPDATE playlists SET name = ?, updated_at = datetime('now', 'localtime') "
                                + "WHERE id = ? AND user_id = ?",
                        newName,
                        playlistId,
                        userId))
                > 0;
    }

    /**
     * Deletes {@code userId}'s playlist and all its tracks. Refuses the default playlist (returns
     * {@code false}). Tracks are deleted first because {@code playlist_id} has no real FK cascade.
     */
    public boolean deletePlaylist(String userId, int playlistId) {
        return database.writeAndProvide(ctx -> {
            // Only delete a non-default playlist that belongs to this user.
            int playlists = ctx.execute(
                    "DELETE FROM playlists WHERE id = ? AND user_id = ? AND is_default = 0", playlistId, userId);
            if (playlists == 0) {
                return false;
            }
            ctx.execute("DELETE FROM playlist_tracks WHERE playlist_id = ?", playlistId);
            return true;
        });
    }

    /**
     * Moves a track into {@code targetPlaylistId}. The {@code user_id} guards (on both the track and the
     * target playlist) ensure a caller can only shuffle their own tracks between their own playlists.
     * Returns {@code true} if a row moved.
     */
    public boolean moveTrack(String userId, int trackId, int targetPlaylistId) {
        return database.writeAndProvide(ctx -> {
            boolean ownsTarget =
                    ctx.fetchOne("SELECT 1 FROM playlists WHERE id = ? AND user_id = ?", targetPlaylistId, userId)
                            != null;
            if (!ownsTarget) {
                return false;
            }
            return ctx.execute(
                            "UPDATE playlist_tracks SET playlist_id = ? WHERE id = ? AND user_id = ?",
                            targetPlaylistId,
                            trackId,
                            userId)
                    > 0;
        });
    }

    /**
     * Removes the track at 1-based {@code position} within a playlist (matching the order shown by
     * {@code /playlist show}). Returns the removed track's title, or {@code null} if out of range.
     */
    public String removeTrackByPosition(int playlistId, int position) {
        return database.writeAndProvide(ctx -> {
            List<Integer> ids = ctx.select(PLAYLIST_TRACKS.ID)
                    .from(PLAYLIST_TRACKS)
                    .where(TRACK_PLAYLIST_ID.eq(playlistId))
                    .orderBy(PLAYLIST_TRACKS.ID.asc())
                    .fetch(PLAYLIST_TRACKS.ID);
            if (position < 1 || position > ids.size()) {
                return null;
            }
            int targetId = ids.get(position - 1);
            String title = ctx.select(PLAYLIST_TRACKS.TITLE)
                    .from(PLAYLIST_TRACKS)
                    .where(PLAYLIST_TRACKS.ID.eq(targetId))
                    .fetchOne(PLAYLIST_TRACKS.TITLE);
            ctx.deleteFrom(PLAYLIST_TRACKS)
                    .where(PLAYLIST_TRACKS.ID.eq(targetId))
                    .execute();
            return title;
        });
    }

    /** Empties a single playlist, returning the number of tracks removed. */
    public int clearPlaylist(int playlistId) {
        return database.writeAndProvide(ctx -> ctx.deleteFrom(PLAYLIST_TRACKS)
                .where(TRACK_PLAYLIST_ID.eq(playlistId))
                .execute());
    }

    // ---- Spotify OAuth ----------------------------------------------------------------------

    /** Stores (or replaces) {@code userId}'s Spotify connection. */
    public void saveSpotifyAccount(
            String userId,
            String accessToken,
            String refreshToken,
            Instant expiresAt,
            String scope,
            String spotifyUserId) {
        database.write(ctx -> ctx.execute(
                "INSERT INTO spotify_accounts (user_id, access_token, refresh_token, expires_at, scope, spotify_user_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT(user_id) DO UPDATE SET "
                        + "access_token = excluded.access_token, refresh_token = excluded.refresh_token, "
                        + "expires_at = excluded.expires_at, scope = excluded.scope, "
                        + "spotify_user_id = excluded.spotify_user_id, updated_at = datetime('now', 'localtime')",
                userId,
                accessToken,
                refreshToken,
                expiresAt.toString(),
                scope,
                spotifyUserId));
    }

    /** Returns {@code userId}'s Spotify connection, or {@code null} if they haven't connected one. */
    public SpotifyAccount getSpotifyAccount(String userId) {
        return database.read(ctx -> {
            org.jooq.Record r = ctx.fetchOne(
                    "SELECT access_token, refresh_token, expires_at, scope, spotify_user_id "
                            + "FROM spotify_accounts WHERE user_id = ?",
                    userId);
            return r == null
                    ? null
                    : new SpotifyAccount(
                            userId,
                            r.get("access_token", String.class),
                            r.get("refresh_token", String.class),
                            Instant.parse(r.get("expires_at", String.class)),
                            r.get("scope", String.class),
                            r.get("spotify_user_id", String.class));
        });
    }

    /** Records a one-time OAuth {@code state} bound to the user who started the flow. */
    public void putOAuthState(String state, String userId) {
        database.write(
                ctx -> ctx.execute("INSERT INTO spotify_oauth_state (state, user_id) VALUES (?, ?)", state, userId));
    }

    /**
     * Consumes an OAuth {@code state}: returns the user it was minted for and deletes the row, or
     * {@code null} if it's unknown or older than {@link #OAUTH_STATE_TTL_MINUTES} minutes.
     */
    public String consumeOAuthState(String state) {
        if (state == null || state.isBlank()) {
            return null;
        }
        return database.writeAndProvide(ctx -> {
            org.jooq.Record r = ctx.fetchOne(
                    "SELECT user_id FROM spotify_oauth_state WHERE state = ? "
                            + "AND created_at >= datetime('now', 'localtime', ?)",
                    state,
                    "-" + OAUTH_STATE_TTL_MINUTES + " minutes");
            String userId = r == null ? null : r.get("user_id", String.class);
            ctx.execute("DELETE FROM spotify_oauth_state WHERE state = ?", state);
            return userId;
        });
    }

    private static Playlist toPlaylist(org.jooq.Record r) {
        return new Playlist(
                r.get("id", Integer.class),
                r.get("user_id", String.class),
                r.get("user_name", String.class),
                r.get("name", String.class),
                r.get("description", String.class),
                r.get("is_default", Integer.class) != null && r.get("is_default", Integer.class) != 0,
                r.get("track_count", Integer.class) == null ? 0 : r.get("track_count", Integer.class));
    }

    private static StoredTrack toStoredTrack(org.jooq.Record record) {
        Integer duration = record.get(PLAYLIST_TRACKS.DURATION_MS);
        return new StoredTrack(
                record.get(PLAYLIST_TRACKS.ID),
                record.get(PLAYLIST_TRACKS.TITLE),
                record.get(PLAYLIST_TRACKS.AUTHOR),
                record.get(PLAYLIST_TRACKS.URI),
                duration == null ? 0 : duration,
                record.get(PLAYLIST_TRACKS.THUMBNAIL_URL),
                record.get(PLAYLIST_TRACKS.ARTIST),
                record.get(PLAYLIST_TRACKS.ALBUM),
                record.get(PLAYLIST_TRACKS.TRACK_NAME));
    }
}
