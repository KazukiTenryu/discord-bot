package bot.stats;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jooq.DSLContext;
import org.jooq.Result;

import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;

import bot.database.Database;

/**
 * Listening analytics for the web player's "Server" / "Hot in this server" page. Every track that
 * actually plays — in voice via {@code /play} and {@code /play-playlist}, or as a {@code /song} post —
 * is logged to {@code play_events}; combined with the saved {@code playlist_tracks}, that drives the
 * server's own charts and stats (distinct from the global Apple Discover/Charts feeds).
 *
 * <p>Writes happen on a single daemon thread so logging a play never blocks the LavaPlayer event
 * thread, and all DB access uses jOOQ plain SQL (no generated code for {@code play_events}).
 */
public class StatsService {
    private static final Logger LOGGER = LogManager.getLogger(StatsService.class);

    private final Database database;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "play-logger");
        t.setDaemon(true);
        return t;
    });

    public StatsService(Database database) {
        this.database = database;
    }

    /** A track on a chart, with how many times it was played and saved. */
    public record HotTrack(String title, String artist, String artworkUrl, String uri, int plays, int adds) {}

    /** An artist and how many of their tracks are saved across the server. */
    public record ArtistCount(String name, int count) {}

    /** A recent play, for the activity feed. */
    public record RecentPlay(
            String title, String artist, String artworkUrl, String uri, String userName, String source) {}

    /** Server-wide totals shown as headline numbers. */
    public record Totals(int plays, int savedTracks, int listeners) {}

    /** Everything the Server page needs in one payload. */
    public record ServerStats(
            Totals totals, List<HotTrack> hot, List<ArtistCount> topArtists, List<RecentPlay> recent) {}

    /** Records a play (best-effort, off the caller's thread). No-op for streams / un-played URIs. */
    public void recordPlay(String userId, String userName, AudioTrackInfo info, String source) {
        if (info == null || info.uri == null || info.uri.isBlank()) {
            return;
        }
        writer.submit(() -> {
            try {
                database.write(ctx -> ctx.execute(
                        "INSERT INTO play_events (user_id, user_name, title, author, uri, artwork_url, source) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                        userId,
                        userName,
                        info.title,
                        info.author,
                        info.uri,
                        info.artworkUrl,
                        source));
            } catch (Exception e) {
                LOGGER.warn("Couldn't record play of '{}'", info.title, e);
            }
        });
    }

    /** A single snapshot for the Server page (totals + hot tracks + top artists + recent plays). */
    public ServerStats serverStats() {
        return database.read(ctx -> new ServerStats(totals(ctx), hot(ctx, 20), topArtists(ctx, 12), recent(ctx, 15)));
    }

    /** A user's most-played tracks (their personal top), for the signed-in section of the page. */
    public List<HotTrack> userTop(String userId, int limit) {
        if (userId == null) {
            return List.of();
        }
        return database.read(ctx -> {
            List<HotTrack> out = new ArrayList<>();
            Result<?> rows = ctx.fetch(
                    "SELECT uri, MAX(title) title, MAX(author) author, MAX(artwork_url) art, COUNT(*) c "
                            + "FROM play_events WHERE user_id = ? AND uri IS NOT NULL AND uri <> '' "
                            + "GROUP BY uri ORDER BY c DESC LIMIT ?",
                    userId,
                    limit);
            for (org.jooq.Record r : rows) {
                out.add(new HotTrack(
                        r.get("title", String.class),
                        r.get("author", String.class),
                        r.get("art", String.class),
                        r.get("uri", String.class),
                        r.get("c", Integer.class),
                        0));
            }
            return out;
        });
    }

    private Totals totals(DSLContext ctx) {
        int plays = ctx.fetchOne("SELECT COUNT(*) FROM play_events").get(0, Integer.class);
        int saved = ctx.fetchOne("SELECT COUNT(*) FROM playlist_tracks").get(0, Integer.class);
        int listeners = ctx.fetchOne("SELECT COUNT(*) FROM (SELECT user_id FROM play_events WHERE user_id IS NOT NULL "
                        + "UNION SELECT user_id FROM playlist_tracks)")
                .get(0, Integer.class);
        return new Totals(plays, saved, listeners);
    }

    // Mutable accumulator while merging play counts and save counts per track URI.
    private static final class Acc {
        String title;
        String artist;
        String artwork;
        int plays;
        int adds;
    }

    /** Hot tracks = plays (weighted x2) + saves, merged by URI so the same song counts once. */
    private List<HotTrack> hot(DSLContext ctx, int limit) {
        Map<String, Acc> byUri = new LinkedHashMap<>();
        // Plays (from /play, /play-playlist, /song). author stands in as the artist when nothing better.
        for (org.jooq.Record r :
                ctx.fetch("SELECT uri, MAX(title) title, MAX(author) author, MAX(artwork_url) art, COUNT(*) c "
                        + "FROM play_events WHERE uri IS NOT NULL AND uri <> '' GROUP BY uri")) {
            Acc a = byUri.computeIfAbsent(r.get("uri", String.class), k -> new Acc());
            a.plays += r.get("c", Integer.class);
            fill(a, r.get("title", String.class), r.get("author", String.class), r.get("art", String.class), false);
        }
        // Saves (from playlists) carry resolved artist/cover, so prefer their metadata.
        for (org.jooq.Record r : ctx.fetch(
                "SELECT uri, MAX(COALESCE(track_name, title)) title, MAX(artist) artist, MAX(thumbnail_url) art, "
                        + "COUNT(*) c FROM playlist_tracks WHERE uri IS NOT NULL AND uri <> '' GROUP BY uri")) {
            Acc a = byUri.computeIfAbsent(r.get("uri", String.class), k -> new Acc());
            a.adds += r.get("c", Integer.class);
            fill(a, r.get("title", String.class), r.get("artist", String.class), r.get("art", String.class), true);
        }
        return byUri.entrySet().stream()
                .sorted((x, y) -> score(y.getValue()) - score(x.getValue()))
                .limit(limit)
                .map(e -> new HotTrack(
                        e.getValue().title,
                        e.getValue().artist,
                        e.getValue().artwork,
                        e.getKey(),
                        e.getValue().plays,
                        e.getValue().adds))
                .toList();
    }

    private static int score(Acc a) {
        return a.plays * 2 + a.adds;
    }

    private static void fill(Acc a, String title, String artist, String artwork, boolean preferred) {
        if (title != null && !title.isBlank() && (preferred || a.title == null)) {
            a.title = title;
        }
        if (artist != null && !artist.isBlank() && (preferred || a.artist == null)) {
            a.artist = artist;
        }
        if (artwork != null && !artwork.isBlank() && (preferred || a.artwork == null)) {
            a.artwork = artwork;
        }
    }

    private List<ArtistCount> topArtists(DSLContext ctx, int limit) {
        // Resolved artists from saved playlists (cleaner than YouTube channel names).
        List<ArtistCount> out = new ArrayList<>();
        for (org.jooq.Record r : ctx.fetch(
                "SELECT artist, COUNT(*) c FROM playlist_tracks WHERE artist IS NOT NULL AND artist <> '' "
                        + "GROUP BY artist ORDER BY c DESC LIMIT ?",
                limit)) {
            out.add(new ArtistCount(r.get("artist", String.class), r.get("c", Integer.class)));
        }
        return out;
    }

    private List<RecentPlay> recent(DSLContext ctx, int limit) {
        List<RecentPlay> out = new ArrayList<>();
        for (org.jooq.Record r : ctx.fetch(
                "SELECT title, author, uri, artwork_url, user_name, source FROM play_events ORDER BY id DESC LIMIT ?",
                limit)) {
            out.add(new RecentPlay(
                    r.get("title", String.class),
                    r.get("author", String.class),
                    r.get("artwork_url", String.class),
                    r.get("uri", String.class),
                    r.get("user_name", String.class),
                    r.get("source", String.class)));
        }
        return out;
    }
}
