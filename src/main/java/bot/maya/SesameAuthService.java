package bot.maya;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Firebase authentication for Sesame's voice API.
 *
 * <p>Sesame's web client authenticates anonymously against Google Firebase and passes the resulting
 * ID token to the voice WebSocket. This mirrors the flow of the unofficial reference client
 * (github.com/ijub/sesame_ai): an anonymous {@code signUp}, refreshed via the secure-token endpoint.
 * No secret of ours is required — the Firebase web API key below is the public one shipped in
 * Sesame's own web app.
 *
 * <p><b>Note:</b> this talks to an undocumented, reverse-engineered API. It may break or be blocked
 * by Sesame at any time and likely falls outside their terms of service.
 */
public class SesameAuthService {
    private static final Logger LOGGER = LogManager.getLogger(SesameAuthService.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // Public Firebase web API key embedded in Sesame's web app (not a secret of ours).
    private static final String DEFAULT_API_KEY = "AIzaSyDtC7Uwb5pGAsdmrH2T4Gqdk5Mga07jYPM";
    private static final String SIGNUP_URL = "https://identitytoolkit.googleapis.com/v1/accounts:signUp";
    private static final String SIGNIN_URL = "https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword";
    private static final String TOKEN_URL = "https://securetoken.googleapis.com/v1/token";
    private static final String FIREBASE_GMPID = "1:1072000975600:web:75b0bf3a9bb8d92e767835";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36";
    // Refresh this many seconds before the ID token's stated expiry to avoid mid-call auth failures.
    private static final long REFRESH_SKEW_SECONDS = 300;

    private final HttpClient httpClient =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final String apiKey;
    private final String email;
    private final String password;

    private TokenSet current;

    /** Anonymous auth (note: Sesame currently rejects anonymous users on the voice endpoint). */
    public SesameAuthService() {
        this(DEFAULT_API_KEY, null, null);
    }

    /** Signs in with a real Sesame account (email/password), which the voice endpoint requires. */
    public SesameAuthService(String email, String password) {
        this(DEFAULT_API_KEY, email, password);
    }

    public SesameAuthService(String apiKey, String email, String password) {
        this.apiKey = (apiKey == null || apiKey.isBlank()) ? DEFAULT_API_KEY : apiKey;
        this.email = (email == null || email.isBlank()) ? null : email;
        this.password = (password == null || password.isBlank()) ? null : password;
    }

    /** Whether real account credentials are configured (vs. the anonymous fallback). */
    public boolean hasCredentials() {
        return email != null && password != null;
    }

    /**
     * Returns a currently-valid Firebase ID token: signing in with the configured credentials (or
     * creating an anonymous account when none are set) on first use, and refreshing — or
     * re-authenticating — when it is close to expiry.
     */
    public synchronized String validIdToken() {
        if (current == null) {
            current = authenticate();
        } else if (current.isExpiringSoon()) {
            try {
                current = refresh(current.refreshToken());
                LOGGER.info("Refreshed Sesame ID token");
            } catch (RuntimeException e) {
                LOGGER.warn("Sesame token refresh failed; re-authenticating", e);
                current = authenticate();
            }
        }
        return current.idToken();
    }

    private TokenSet authenticate() {
        return hasCredentials() ? signInWithPassword(email, password) : createAnonymousAccount();
    }

    /** Exchanges an email/password for Firebase tokens via the sign-in endpoint. */
    public synchronized TokenSet signInWithPassword(String email, String password) {
        ObjectNode body = OBJECT_MAPPER.createObjectNode();
        body.put("email", email);
        body.put("password", password);
        body.put("returnSecureToken", true);
        JsonNode json = post(SIGNIN_URL, "application/json", body.toString());
        LOGGER.info("Signed in to Sesame as {}", json.path("email").asText(email));
        return new TokenSet(
                json.path("idToken").asText(),
                json.path("refreshToken").asText(),
                parseLong(json.path("expiresIn").asText(), 3600),
                System.currentTimeMillis());
    }

    /** Creates a fresh anonymous Firebase account and returns its tokens. */
    public synchronized TokenSet createAnonymousAccount() {
        ObjectNode body = OBJECT_MAPPER.createObjectNode();
        body.put("returnSecureToken", true);
        JsonNode json = post(SIGNUP_URL, "application/json", body.toString());
        TokenSet set = new TokenSet(
                json.path("idToken").asText(),
                json.path("refreshToken").asText(),
                parseLong(json.path("expiresIn").asText(), 3600),
                System.currentTimeMillis());
        LOGGER.info("Created anonymous Sesame account (uid {})", json.path("localId").asText());
        return set;
    }

    /** Exchanges a refresh token for a new ID token via Firebase's secure-token endpoint. */
    public synchronized TokenSet refresh(String refreshToken) {
        String form = "grant_type=refresh_token&refresh_token="
                + URLEncoder.encode(refreshToken, StandardCharsets.UTF_8);
        JsonNode json = post(TOKEN_URL, "application/x-www-form-urlencoded", form);
        return new TokenSet(
                json.path("id_token").asText(),
                json.path("refresh_token").asText(),
                parseLong(json.path("expires_in").asText(), 3600),
                System.currentTimeMillis());
    }

    private JsonNode post(String url, String contentType, String body) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url + "?key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8)))
                    .header("content-type", contentType)
                    .header("accept", "*/*")
                    .header("user-agent", USER_AGENT)
                    .header("x-firebase-client", firebaseClientHeader())
                    .header("x-client-version", "Chrome/JsCore/11.3.1/FirebaseCore-web")
                    .header("x-firebase-gmpid", FIREBASE_GMPID)
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode json = OBJECT_MAPPER.readTree(response.body());
            if (response.statusCode() != 200 || json.has("error")) {
                throw new IllegalStateException("Sesame auth request to " + url + " failed (status "
                        + response.statusCode() + "): " + response.body());
            }
            return json;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Sesame auth request to " + url + " failed", e);
        }
    }

    /** Builds the base64 {@code x-firebase-client} heartbeat header Firebase's SDK sends. */
    private static String firebaseClientHeader() {
        String today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        String json = "{\"version\":2,\"heartbeats\":[{\"agent\":\"fire-core/0.11.1 fire-core-esm2017/0.11.1"
                + " fire-js/ fire-js-all-app/11.3.1 fire-auth/1.9.0 fire-auth-esm2017/1.9.0\",\"dates\":[\""
                + today + "\"]}]}";
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static long parseLong(String s, long fallback) {
        try {
            return Long.parseLong(s.trim());
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /** A Firebase token pair plus the wall-clock time it was obtained, used to detect expiry. */
    public record TokenSet(String idToken, String refreshToken, long expiresInSeconds, long obtainedAtMillis) {
        boolean isExpiringSoon() {
            long ageSeconds = (System.currentTimeMillis() - obtainedAtMillis) / 1000;
            return ageSeconds >= expiresInSeconds - REFRESH_SKEW_SECONDS;
        }
    }
}
