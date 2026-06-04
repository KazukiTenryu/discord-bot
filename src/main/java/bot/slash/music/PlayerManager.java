package bot.slash.music;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.interactions.InteractionHook;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame;

import bot.stats.StatsService;
import dev.lavalink.youtube.YoutubeAudioSourceManager;
import dev.lavalink.youtube.clients.AndroidVrWithThumbnail;
import dev.lavalink.youtube.clients.MusicWithThumbnail;
import dev.lavalink.youtube.clients.Tv;
import dev.lavalink.youtube.clients.WebWithThumbnail;

/**
 * Process-wide owner of the LavaPlayer {@link AudioPlayerManager} and the per-guild
 * {@link GuildMusicManager}s. A single instance is shared across every music command.
 */
public class PlayerManager {
    private static final Logger LOGGER = LogManager.getLogger(PlayerManager.class);

    // Set this as the config value to run the one-time device-login flow and print a refresh token.
    private static final String OAUTH_INIT_SENTINEL = "INITIALIZE";

    // DISCORD_OPUS is 48kHz stereo; these drive the Ogg container written by /song downloads.
    private static final int DISCORD_OPUS_CHANNELS = 2;
    private static final long MAX_DURATION_MS = 12 * 60 * 1000L;
    // Kept under Discord's 10 MiB default attachment limit for non-boosted servers.
    private static final int MAX_FILE_BYTES = 9_000_000;

    private static PlayerManager instance;

    /** Who asked for a track, attached as its {@code userData} so the scheduler can log the play. */
    public record Requester(String userId, String userName) {}

    private final AudioPlayerManager audioPlayerManager;
    private final Map<Long, GuildMusicManager> musicManagers;
    // Set via init(); used by the scheduler to log plays. May be null (stats disabled / not wired).
    private StatsService statsService;
    // Off-thread decoders for /song; daemon so they never hold up shutdown.
    private final ExecutorService downloadExecutor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "song-download");
        thread.setDaemon(true);
        return thread;
    });

    private PlayerManager(String youtubeOauthRefreshToken) {
        this.audioPlayerManager = new DefaultAudioPlayerManager();
        this.musicManagers = new ConcurrentHashMap<>();

        // LavaPlayer 2.x ships no built-in YouTube manager; register the standalone source first.
        //
        // Client ordering is driven by two YouTube failure modes:
        //   1. Anonymous bot-blocking ("Sign in to confirm you're not a bot").
        //   2. Signature-cipher breakage: clients whose Client#requirePlayerScript() is true (Tv,
        //      Web, MWeb, ...) resolve stream URLs through LocalSignatureCipherManager, which parses
        //      the YouTube player script. When YouTube ships a new player script the parser can't
        //      read, every such client fails with "must find sig function" until youtube-source is
        //      updated — there is no newer release to bump to when that happens.
        //
        // AndroidVr (requirePlayerScript() == false) leads because it sidesteps BOTH: it neither
        // needs OAuth/login nor touches the player-script parser, so it keeps playing through a
        // broken-script window. Tv follows as the OAuth-bearing fallback (Client#supportsOAuth is
        // true only for it in this version, so it MUST precede the other authed-but-ciphered clients
        // for the configured token to be applied at all). Music/Web are best-effort after that.
        YoutubeAudioSourceManager youtube = new YoutubeAudioSourceManager(
                true, new AndroidVrWithThumbnail(), new Tv(), new MusicWithThumbnail(), new WebWithThumbnail());
        configureOauth(youtube, youtubeOauthRefreshToken);

        audioPlayerManager.registerSourceManager(youtube);
        AudioSourceManagers.registerRemoteSources(audioPlayerManager);
        AudioSourceManagers.registerLocalSource(audioPlayerManager);

        // Output Discord-ready Opus so the send handler can forward frames without re-encoding.
        audioPlayerManager.getConfiguration().setOutputFormat(StandardAudioDataFormats.DISCORD_OPUS);
    }

    private void configureOauth(YoutubeAudioSourceManager youtube, String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            LOGGER.warn(
                    "YouTube OAuth not configured — playback may fail with \"Sign in to confirm "
                            + "you're not a bot\". Set youtubeOauthRefreshToken to \"{}\" once to obtain a token.",
                    OAUTH_INIT_SENTINEL);
            return;
        }

        if (refreshToken.equalsIgnoreCase(OAUTH_INIT_SENTINEL)) {
            // skipInitialization=false starts the device-code flow: youtube-source logs a
            // google.com/device URL and code, then prints the refresh token to paste into config.
            LOGGER.info("Starting YouTube OAuth device-login flow — follow the URL logged below.");
            youtube.useOauth2(null, false);
            return;
        }

        // Reuse a previously obtained refresh token; skip the interactive flow.
        youtube.useOauth2(refreshToken, true);
        LOGGER.info("YouTube OAuth enabled using configured refresh token.");
    }

    /** Initialises the singleton with YouTube OAuth configured from {@code refreshToken}. */
    public static synchronized void init(String youtubeOauthRefreshToken, StatsService statsService) {
        if (instance == null) {
            instance = new PlayerManager(youtubeOauthRefreshToken);
        }
        instance.statsService = statsService;
    }

    public static synchronized PlayerManager getInstance() {
        if (instance == null) {
            instance = new PlayerManager(null);
        }
        return instance;
    }

    /** The play-logging service, or {@code null} when stats aren't wired up. */
    public StatsService getStatsService() {
        return statsService;
    }

    public GuildMusicManager getMusicManager(Guild guild) {
        return musicManagers.computeIfAbsent(guild.getIdLong(), _ -> new GuildMusicManager(audioPlayerManager));
    }

    /** Tags the track with its requester (for play-logging) and hands it to the guild's scheduler. */
    private static void queue(GuildMusicManager musicManager, AudioTrack track, Requester requester) {
        if (requester != null) {
            track.setUserData(requester);
        }
        musicManager.getScheduler().queue(track);
    }

    /**
     * Resolves {@code query} (a URL or a {@code ytsearch:} term) and queues the result, replying to
     * the user through {@code hook}. The voice connection must already be open.
     */
    public void loadAndPlay(Guild guild, String query, InteractionHook hook, Requester requester) {
        GuildMusicManager musicManager = getMusicManager(guild);

        audioPlayerManager.loadItemOrdered(musicManager, query, new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack track) {
                queue(musicManager, track, requester);
                hook.sendMessage("🎵 Queued **" + track.getInfo().title + "**").queue();
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                // A search (ytsearch:) returns a playlist of results; queue only the top hit.
                if (playlist.isSearchResult()) {
                    AudioTrack track = playlist.getTracks().getFirst();
                    queue(musicManager, track, requester);
                    hook.sendMessage("🎵 Queued **" + track.getInfo().title + "**")
                            .queue();
                    return;
                }

                for (AudioTrack track : playlist.getTracks()) {
                    queue(musicManager, track, requester);
                }
                hook.sendMessage("🎵 Queued **" + playlist.getTracks().size() + "** tracks from **" + playlist.getName()
                                + "**")
                        .queue();
            }

            @Override
            public void noMatches() {
                hook.sendMessage("🔍 Nothing found for `" + query + "`.").queue();
            }

            @Override
            public void loadFailed(FriendlyException exception) {
                LOGGER.error("Failed to load track for query '{}'", query, exception);
                hook.sendMessage("⚠️ Couldn't load that track: " + exception.getMessage())
                        .queue();
            }
        });
    }

    /**
     * Resolves {@code query} (a URL or a {@code ytsearch:} term) to a single track's metadata without
     * decoding any audio. Used by the playlist commands to capture title/author/uri/duration/artwork
     * when a user adds a song. The returned future fails with {@link NoMatchException} when nothing
     * matches, or with the load error otherwise.
     */
    public CompletableFuture<AudioTrackInfo> resolve(String query) {
        CompletableFuture<AudioTrackInfo> future = new CompletableFuture<>();

        audioPlayerManager.loadItem(query, new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack track) {
                future.complete(track.getInfo());
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                if (playlist.getTracks().isEmpty()) {
                    future.completeExceptionally(new NoMatchException(query));
                    return;
                }
                future.complete(playlist.getTracks().getFirst().getInfo());
            }

            @Override
            public void noMatches() {
                future.completeExceptionally(new NoMatchException(query));
            }

            @Override
            public void loadFailed(FriendlyException exception) {
                future.completeExceptionally(exception);
            }
        });

        return future;
    }

    /**
     * Searches for {@code query} (a URL or a {@code ytsearch:} term) and returns up to {@code limit}
     * matches' metadata, without decoding any audio. Used by the web player's search bar. A direct URL
     * resolves to a single result; a search term yields the ranked candidates. The future completes
     * with an empty list when nothing matches, and never fails for a "no match" — only for load errors.
     */
    public CompletableFuture<List<AudioTrackInfo>> search(String query, int limit) {
        CompletableFuture<List<AudioTrackInfo>> future = new CompletableFuture<>();

        audioPlayerManager.loadItem(query, new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack track) {
                future.complete(List.of(track.getInfo()));
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                List<AudioTrackInfo> results = new ArrayList<>();
                for (AudioTrack track : playlist.getTracks()) {
                    if (results.size() >= limit) {
                        break;
                    }
                    results.add(track.getInfo());
                }
                future.complete(results);
            }

            @Override
            public void noMatches() {
                future.complete(List.of());
            }

            @Override
            public void loadFailed(FriendlyException exception) {
                future.completeExceptionally(exception);
            }
        });

        return future;
    }

    /**
     * Resolves {@code identifier} and appends it to {@code guild}'s queue without posting any chat
     * message (unlike {@link #loadAndPlay}). Used by {@code /play-playlist} to enqueue stored tracks
     * in bulk; the returned future completes with the queued track (the first, for a playlist) or
     * fails if nothing loads, so the caller can keep its own count of what made it in.
     */
    public CompletableFuture<AudioTrack> enqueue(Guild guild, String identifier, Requester requester) {
        GuildMusicManager musicManager = getMusicManager(guild);
        CompletableFuture<AudioTrack> future = new CompletableFuture<>();

        audioPlayerManager.loadItemOrdered(musicManager, identifier, new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack track) {
                queue(musicManager, track, requester);
                future.complete(track);
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                if (playlist.getTracks().isEmpty()) {
                    future.completeExceptionally(new NoMatchException(identifier));
                    return;
                }
                AudioTrack first =
                        playlist.isSearchResult() ? playlist.getTracks().getFirst() : null;
                if (first != null) {
                    queue(musicManager, first, requester);
                    future.complete(first);
                    return;
                }
                for (AudioTrack track : playlist.getTracks()) {
                    queue(musicManager, track, requester);
                }
                future.complete(playlist.getTracks().getFirst());
            }

            @Override
            public void noMatches() {
                future.completeExceptionally(new NoMatchException(identifier));
            }

            @Override
            public void loadFailed(FriendlyException exception) {
                future.completeExceptionally(exception);
            }
        });

        return future;
    }

    /** A track resolved by {@link #downloadOgg} together with its rendered Ogg/Opus bytes. */
    public record DownloadedTrack(byte[] ogg, AudioTrackInfo info) {}

    /**
     * Searches for {@code query}, decodes the top hit to an in-memory Ogg/Opus file, and completes
     * with the bytes plus the track metadata. The decode runs off-thread so callers can invoke this
     * from a JDA event thread. The returned future fails if nothing matches, the track is a live
     * stream, the load errors, or the result would exceed {@link #MAX_FILE_BYTES}.
     */
    public CompletableFuture<DownloadedTrack> downloadOgg(String query) {
        CompletableFuture<DownloadedTrack> future = new CompletableFuture<>();

        audioPlayerManager.loadItem(query, new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack track) {
                capture(track);
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                // A search (ytsearch:) returns a playlist of candidates; take the top hit.
                if (playlist.getTracks().isEmpty()) {
                    noMatches();
                    return;
                }
                capture(playlist.getTracks().getFirst());
            }

            @Override
            public void noMatches() {
                future.completeExceptionally(new NoMatchException(query));
            }

            @Override
            public void loadFailed(FriendlyException exception) {
                future.completeExceptionally(exception);
            }

            private void capture(AudioTrack track) {
                AudioTrackInfo info = track.getInfo();
                if (info.isStream) {
                    future.completeExceptionally(
                            new IllegalArgumentException("That's a live stream, which can't be downloaded."));
                    return;
                }
                if (info.length > MAX_DURATION_MS) {
                    future.completeExceptionally(new IllegalArgumentException(
                            "That track is over " + (MAX_DURATION_MS / 60_000) + " minutes long."));
                    return;
                }
                // Move the blocking decode off LavaPlayer's loader thread.
                downloadExecutor.execute(() -> {
                    try {
                        future.complete(new DownloadedTrack(renderOgg(track), info));
                    } catch (Exception e) {
                        future.completeExceptionally(e);
                    }
                });
            }
        });

        return future;
    }

    /** Plays {@code track} through a throwaway player, packing its Opus frames into an Ogg file. */
    private byte[] renderOgg(AudioTrack track) throws Exception {
        AudioPlayer player = audioPlayerManager.createPlayer();
        CountDownLatch ended = new CountDownLatch(1);
        AtomicReference<AudioTrackEndReason> endReason = new AtomicReference<>();
        AtomicReference<FriendlyException> trackError = new AtomicReference<>();

        player.addListener(new AudioEventAdapter() {
            @Override
            public void onTrackEnd(AudioPlayer player, AudioTrack track, AudioTrackEndReason reason) {
                endReason.set(reason);
                ended.countDown();
            }

            @Override
            public void onTrackException(AudioPlayer player, AudioTrack track, FriendlyException exception) {
                trackError.set(exception);
            }
        });

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        OggOpusWriter writer = new OggOpusWriter(out, DISCORD_OPUS_CHANNELS);
        writer.writeHeaders();

        try {
            player.playTrack(track);
            boolean finished = false;
            while (!finished) {
                AudioFrame frame = player.provide();
                if (frame != null) {
                    writer.writeAudioPacket(frame.getData());
                    if (out.size() > MAX_FILE_BYTES) {
                        throw new IllegalStateException("That song is too large to upload here.");
                    }
                    continue;
                }
                // No buffered frame: either the decoder is catching up, or the track has ended.
                finished = ended.await(20, TimeUnit.MILLISECONDS);
            }
        } finally {
            player.destroy();
        }

        if (trackError.get() != null) {
            throw trackError.get();
        }
        if (endReason.get() == AudioTrackEndReason.LOAD_FAILED) {
            throw new IllegalStateException("Couldn't decode that track.");
        }

        writer.finish();
        return out.toByteArray();
    }

    /** Raised when a search/lookup yields nothing, so callers can word the reply themselves. */
    public static final class NoMatchException extends RuntimeException {
        public NoMatchException(String query) {
            super(query);
        }
    }
}
