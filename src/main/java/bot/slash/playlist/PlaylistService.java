package bot.slash.playlist;

import static bot.database.jooq.Tables.PLAYLIST_TRACKS;
import static org.jooq.impl.DSL.count;
import static org.jooq.impl.DSL.max;

import java.util.List;

import org.jooq.Field;

import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;

import bot.database.Database;

/**
 * Persistence for the per-user playlists. Each user owns a single playlist made up of all their
 * {@code playlist_tracks} rows; there is no separate playlist entity. Shared by the slash commands
 * and the web API (see {@code bot.web}).
 */
public class PlaylistService {
    /** A stored track, as needed by both the {@code /playlist} embeds and the web API. */
    public record StoredTrack(
            int id, String title, String author, String uri, int durationMs, String thumbnailUrl) {}

    /** A user who owns at least one track, with their latest known display name and track count. */
    public record PlaylistOwner(String userId, String userName, int trackCount) {}

    private final Database database;

    public PlaylistService(Database database) {
        this.database = database;
    }

    /** Appends {@code info} to {@code userId}'s playlist, refreshing the stored display name. */
    public void addTrack(String userId, String userName, AudioTrackInfo info) {
        // duration_ms is an INTEGER column (ms comfortably fits an int); streams have no real length.
        int durationMs = info.isStream ? 0 : (int) Math.min(info.length, Integer.MAX_VALUE);
        database.write(ctx -> ctx.insertInto(PLAYLIST_TRACKS)
                .set(PLAYLIST_TRACKS.USER_ID, userId)
                .set(PLAYLIST_TRACKS.USER_NAME, userName)
                .set(PLAYLIST_TRACKS.TITLE, info.title)
                .set(PLAYLIST_TRACKS.AUTHOR, info.author)
                .set(PLAYLIST_TRACKS.URI, info.uri)
                .set(PLAYLIST_TRACKS.DURATION_MS, durationMs)
                .set(PLAYLIST_TRACKS.THUMBNAIL_URL, info.artworkUrl)
                .execute());
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
                record.get(PLAYLIST_TRACKS.THUMBNAIL_URL));
    }
}
