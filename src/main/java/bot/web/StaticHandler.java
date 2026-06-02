package bot.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

/**
 * Serves the static web player from the classpath ({@code /web/}). {@code /} maps to
 * {@code index.html}; any other path is resolved as a resource under {@code /web/} so additional
 * assets can be dropped in later. Everything is read-only and path traversal is rejected.
 */
public class StaticHandler implements HttpHandler {
    private static final String BASE = "/web";

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/") || path.isEmpty()) {
            path = "/index.html";
        }
        if (path.contains("..")) {
            send(exchange, 400, "text/plain", "Bad Request".getBytes(StandardCharsets.UTF_8));
            return;
        }

        try (InputStream resource = StaticHandler.class.getResourceAsStream(BASE + path)) {
            if (resource == null) {
                send(exchange, 404, "text/plain", "Not Found".getBytes(StandardCharsets.UTF_8));
                return;
            }
            send(exchange, 200, contentType(path), resource.readAllBytes());
        }
    }

    private static String contentType(String path) {
        if (path.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (path.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (path.endsWith(".js")) {
            return "text/javascript; charset=utf-8";
        }
        if (path.endsWith(".svg")) {
            return "image/svg+xml";
        }
        if (path.endsWith(".png")) {
            return "image/png";
        }
        if (path.endsWith(".ico")) {
            return "image/x-icon";
        }
        return "application/octet-stream";
    }

    private static void send(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
