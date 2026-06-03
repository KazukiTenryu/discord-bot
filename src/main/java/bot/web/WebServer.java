package bot.web;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.sun.net.httpserver.HttpServer;

import bot.config.Config;
import bot.slash.playlist.PlaylistService;
import bot.slash.playlist.SpotifyService;

/**
 * Lightweight HTTP server for the playlist web player, built on the JDK's {@code com.sun.net.httpserver}
 * (no extra dependency). Serves a static mobile-friendly page at {@code /} and a small JSON/audio API
 * under {@code /api/}. Intended to sit behind nginx; it binds all interfaces. Browsing is public;
 * playlist edits require a per-user token (see {@link PlaylistApiHandler}).
 */
public class WebServer {
    private static final Logger LOGGER = LogManager.getLogger(WebServer.class);

    private final PlaylistService playlistService;
    private final SpotifyService spotifyService;
    private final Config config;
    private final int port;

    public WebServer(PlaylistService playlistService, SpotifyService spotifyService, Config config) {
        this.playlistService = playlistService;
        this.spotifyService = spotifyService;
        this.config = config;
        this.port = config.webPortOrDefault();
    }

    public void start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
            // Daemon threads so the web server never blocks JVM shutdown. The audio endpoint decodes
            // tracks (blocking), so allow a handful of concurrent requests.
            server.setExecutor(Executors.newFixedThreadPool(8, runnable -> {
                Thread thread = new Thread(runnable, "web-server");
                thread.setDaemon(true);
                return thread;
            }));

            server.createContext("/api/", new PlaylistApiHandler(playlistService, spotifyService, config));
            server.createContext("/", new StaticHandler());

            server.start();
            LOGGER.info("Playlist web player listening on http://0.0.0.0:{}", port);
        } catch (IOException e) {
            LOGGER.error("Failed to start web server on port {}", port, e);
        }
    }
}
