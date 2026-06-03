package bot.slash.playlist;

import static bot.database.jooq.Tables.PLAYLIST_TOKENS;
import static bot.database.jooq.Tables.PLAYLIST_TRACKS;
import static org.jooq.impl.DSL.count;
import static org.jooq.impl.DSL.max;

import java.security.SecureRandom;
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
 * Persistence for the per-user playlists. Each user owns a single playlist made up of all their
 * {@code playlist_tracks} rows; there is no separate playlist entity. Shared by the slash commands
 * and the web API (see {@code bot.web}).
 */
public class PlaylistService {
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
     * Appends {@code info} to {@code userId}'s playlist, refreshing the stored display name, and
     * returns the stored row (including any resolved artist/album metadata). The metadata lookup is
     * best-effort and runs before the DB write so it never holds a connection during network I/O.
     */
    public StoredTrack addTrack(String userId, String userName, AudioTrackInfo info) {
        // duration_ms is an INTEGER column (ms comfortably fits an int); streams have no real length.
        int durationMs = info.isStream ? 0 : (int) Math.min(info.length, Integer.MAX_VALUE);
        TrackMetadata md = metadataService.lookup(info.title, info.author).orElse(null);
        String artist = md != null ? md.artist() : null;
        String album = md != null ? md.album() : null;
        String trackName = md != null ? md.title() : null;
        int id = database.writeAndProvide(ctx -> ctx.insertInto(PLAYLIST_TRACKS)
                .set(PLAYLIST_TRACKS.USER_ID, userId)
                .set(PLAYLIST_TRACKS.USER_NAME, userName)
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

    /** A user's tracks in insertion order (oldest first). */
    public List<StoredTrack> getTracks(String userId) {
        return database.read(ctx -> ctx.selectFrom(PLAYLIST_TRACKS)
                .where(PLAYLIST_TRACKS.USER_ID.eq(userId))
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
