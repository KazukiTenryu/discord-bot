package bot;

import java.util.EnumSet;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.audio.AudioModuleConfig;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.requests.restaction.CommandListUpdateAction;
import net.dv8tion.jda.api.utils.cache.CacheFlag;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.NonNull;

import bot.automod.AutoModMessageListener;
import bot.automod.ChannelHistory;
import bot.config.Config;
import bot.config.ConfigLoader;
import bot.database.Database;
import bot.listeners.MessageReceivedListener;
import bot.metrics.MetricService;
import bot.slash.SlashCommandRepository;
import bot.slash.music.PlayerManager;
import bot.slash.playlist.PlaylistService;
import bot.slash.playlist.SpotifyService;
import bot.stats.StatsService;
import bot.web.WebServer;
import moe.kyokobot.libdave.NativeDaveFactory;
import moe.kyokobot.libdave.jda.LDJDADaveSessionFactory;

public class Main {
    private static MetricService metricService;

    public static void main(String[] args) {
        System.out.println("Starting bot...");

        try {
            Config config = ConfigLoader.loadConfig();
            System.setProperty("infoLogsChannelWebHookURL", config.infoLogsChannelWebHookURL());
            System.setProperty("errorLogsChannelWebHookURL", config.errorLogsChannelWebHookURL());

            // DB_FILE env var (set by the Docker image to a writable volume path) overrides the
            // configured dbFile so the SQLite file and its WAL/-shm sidecars live on a persistent
            // volume in Docker; local runs fall back to config.dbFile().
            String dbFile = System.getenv("DB_FILE");
            if (dbFile == null || dbFile.isBlank()) {
                dbFile = config.dbFile();
            }
            Database database = new Database("jdbc:sqlite:" + dbFile);
            metricService = new MetricService(database);

            // Listening analytics: logged by the player (track-start) and read by the web Server page.
            StatsService statsService = new StatsService(database);

            // Configure YouTube OAuth (if set) before any /play can reach the player; the player logs
            // plays through statsService.
            PlayerManager.init(config.youtubeOauthRefreshToken(), statsService);

            PlaylistService playlistService = new PlaylistService(database);
            // Enrich any pre-metadata tracks (artist/album NULL) in the background so the network
            // lookups never block startup; it throttles itself and exits when there's nothing to do.
            Thread metadataBackfill = new Thread(playlistService::backfillMetadata, "metadata-backfill");
            metadataBackfill.setDaemon(true);
            metadataBackfill.start();
            SlashCommandRepository slashCommandRepository =
                    new SlashCommandRepository(config, database, playlistService);

            JDA jda = JDABuilder.createLight(config.botToken(), EnumSet.allOf(GatewayIntent.class))
                    // VOICE_STATE caching lets the music commands see which channel a member is in;
                    // createLight() disables all cache flags by default.
                    .enableCache(CacheFlag.VOICE_STATE)
                    // Discord requires the DAVE protocol for voice; plug in the native libdave
                    // implementation so audio connections aren't rejected. JDA's built-in factory is
                    // only a non-functional passthrough.
                    .setAudioModuleConfig(new AudioModuleConfig()
                            .withDaveSessionFactory(new LDJDADaveSessionFactory(new NativeDaveFactory())))
                    .addEventListeners(new GlobalEventListener(config, database, slashCommandRepository))
                    .addEventListeners(new MessageReceivedListener(config))
                    .addEventListeners(new AutoModMessageListener(config, new ChannelHistory()))
                    .addEventListeners(new ListenerAdapter() {
                        private static final Logger LOGGER = LogManager.getLogger("Main#ReadyListener");

                        @Override
                        public void onReady(@NonNull ReadyEvent event) {
                            LOGGER.info(
                                    "Bot is ready! Logged in as {}",
                                    event.getJDA().getSelfUser().getName());
                        }
                    })
                    .build();

            CommandListUpdateAction commands = jda.updateCommands();
            slashCommandRepository.getCommands().forEach(slashCommand -> commands.addCommands(slashCommand.getData()));
            commands.queue();

            // Serve the mobile playlist web player. Audio is streamed by the bot itself, reusing the
            // music stack, so the server needs the same PlayerManager singleton initialised above.
            // Spotify import is optional; it's only wired up when credentials are configured.
            SpotifyService spotifyService = config.spotifyConfigured()
                    ? new SpotifyService(
                            config.spotifyClientId(), config.spotifyClientSecret(), config.spotifyRedirectUri())
                    : null;
            new WebServer(playlistService, spotifyService, statsService, config).start();
        } catch (Exception e) {
            System.err.println("Failed to start application: " + e.getMessage());
        }
    }

    public static MetricService getMetrics() {
        if (metricService != null) {
            return metricService;
        }
        throw new NullPointerException("Metrics class has not been initalised");
    }
}
