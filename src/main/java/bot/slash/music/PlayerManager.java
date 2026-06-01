package bot.slash.music;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.interactions.InteractionHook;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;

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

    private static PlayerManager instance;

    private final AudioPlayerManager audioPlayerManager;
    private final Map<Long, GuildMusicManager> musicManagers;

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
            LOGGER.warn("YouTube OAuth not configured — playback may fail with \"Sign in to confirm "
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
    public static synchronized void init(String youtubeOauthRefreshToken) {
        if (instance == null) {
            instance = new PlayerManager(youtubeOauthRefreshToken);
        }
    }

    public static synchronized PlayerManager getInstance() {
        if (instance == null) {
            instance = new PlayerManager(null);
        }
        return instance;
    }

    public GuildMusicManager getMusicManager(Guild guild) {
        return musicManagers.computeIfAbsent(guild.getIdLong(), _ -> new GuildMusicManager(audioPlayerManager));
    }

    /**
     * Resolves {@code query} (a URL or a {@code ytsearch:} term) and queues the result, replying to
     * the user through {@code hook}. The voice connection must already be open.
     */
    public void loadAndPlay(Guild guild, String query, InteractionHook hook) {
        GuildMusicManager musicManager = getMusicManager(guild);

        audioPlayerManager.loadItemOrdered(musicManager, query, new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack track) {
                musicManager.getScheduler().queue(track);
                hook.sendMessage("🎵 Queued **" + track.getInfo().title + "**").queue();
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                // A search (ytsearch:) returns a playlist of results; queue only the top hit.
                if (playlist.isSearchResult()) {
                    AudioTrack track = playlist.getTracks().getFirst();
                    musicManager.getScheduler().queue(track);
                    hook.sendMessage("🎵 Queued **" + track.getInfo().title + "**")
                            .queue();
                    return;
                }

                for (AudioTrack track : playlist.getTracks()) {
                    musicManager.getScheduler().queue(track);
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
}
