package com.noah.spotifycontroller.auth;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.noah.spotifycontroller.config.SpotifyConfig;
import com.noah.spotifycontroller.util.SpotifyI18n;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Handles Spotify OAuth 2.0 Authorization Code with PKCE authentication,
 * local loopback callback handling, and automatic access token refreshes.
 */
public class SpotifyAuthService {
    private static final Logger LOGGER = LoggerFactory.getLogger("spotify_controller");
    private static final String DEFAULT_AUTHORIZE_ENDPOINT = "https://accounts.spotify.com/authorize";
    private static final String DEFAULT_TOKEN_ENDPOINT = "https://accounts.spotify.com/api/token";
    private static final String SCOPES = "user-read-playback-state user-read-currently-playing user-modify-playback-state";
    private static final String CODE_VERIFIER_CHARSET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int CONNECTED_AUTO_CLOSE_SECONDS = 5;

    private record TokenError(String code, String description) {
    }

    private final SpotifyConfig config;
    private final HttpClient httpClient;
    private final Consumer<URI> browserOpener;
    private final String authorizeEndpoint;
    private final String tokenEndpoint;
    private final ScheduledExecutorService cleanupExecutor;

    private final Object serverLock = new Object();
    private HttpServer activeCallbackServer;
    private HttpServer activeIpv6CallbackServer;
    private ExecutorService activeCallbackExecutor;
    private CompletableFuture<Boolean> activeAuthFuture;

    private final Object refreshLock = new Object();
    private CompletableFuture<Boolean> inFlightRefresh;

    public SpotifyAuthService(SpotifyConfig config, HttpClient httpClient) {
        this(config, httpClient, SpotifyAuthService::openInDefaultBrowser);
    }

    public SpotifyAuthService(SpotifyConfig config, HttpClient httpClient, Consumer<URI> browserOpener) {
        this(config, httpClient, browserOpener, DEFAULT_AUTHORIZE_ENDPOINT, DEFAULT_TOKEN_ENDPOINT);
    }

    public SpotifyAuthService(
            SpotifyConfig config,
            HttpClient httpClient,
            Consumer<URI> browserOpener,
            String authorizeEndpoint,
            String tokenEndpoint
    ) {
        this.config = config;
        this.httpClient = httpClient;
        this.browserOpener = browserOpener != null ? browserOpener : SpotifyAuthService::openInDefaultBrowser;
        this.authorizeEndpoint = (authorizeEndpoint != null && !authorizeEndpoint.isBlank())
                ? authorizeEndpoint
                : DEFAULT_AUTHORIZE_ENDPOINT;
        this.tokenEndpoint = (tokenEndpoint != null && !tokenEndpoint.isBlank())
                ? tokenEndpoint
                : DEFAULT_TOKEN_ENDPOINT;
        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "spotify-auth-cleanup");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Generates a cryptographically random 64-character PKCE code verifier.
     */
    public static String generateCodeVerifier() {
        StringBuilder sb = new StringBuilder(64);
        for (int i = 0; i < 64; i++) {
            int idx = SECURE_RANDOM.nextInt(CODE_VERIFIER_CHARSET.length());
            sb.append(CODE_VERIFIER_CHARSET.charAt(idx));
        }
        return sb.toString();
    }

    /**
     * Computes the RFC 7636 S256 PKCE code challenge from a code verifier.
     */
    public static String computeCodeChallenge(String codeVerifier) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Starts an interactive browser OAuth PKCE flow and opens a local loopback callback server.
     */
    public CompletableFuture<Boolean> startAuthFlow(Consumer<String> statusCallback) {
        Consumer<String> notify = statusCallback != null ? statusCallback : msg -> {};
        if (!config.hasClientId()) {
            notify.accept(SpotifyI18n.translate("status.spotify_controller.auth.missing_client_id"));
            return CompletableFuture.completedFuture(false);
        }

        stopCallbackServer();

        String clientId;
        int port;
        String redirectUri;
        synchronized (config) {
            clientId = config.clientId;
            int configuredPort = config.callbackPort;
            port = (configuredPort <= 0 || configuredPort > 65535) ? 8888 : configuredPort;
            redirectUri = config.getRedirectUri();
        }

        String codeVerifier = generateCodeVerifier();
        String codeChallenge = computeCodeChallenge(codeVerifier);
        String state = generateRandomState();

        CompletableFuture<Boolean> resultFuture = new CompletableFuture<>();
        AtomicBoolean handled = new AtomicBoolean(false);

        HttpServer ipv4Server;
        try {
            // Bind explicitly to IPv4 127.0.0.1 because dual-stack flags (e.g. NeoForge's
            // preferIPv6Addresses=true) cause InetAddress.getLoopbackAddress() to return ::1,
            // leaving http://127.0.0.1:<port>/callback unreachable from the browser.
            ipv4Server = HttpServer.create(new InetSocketAddress(loopbackIpv4Address(), port), 0);
        } catch (IOException e) {
            String message = SpotifyI18n.translate("status.spotify_controller.auth.server_start_failed", port, e.getMessage());
            LOGGER.error("Failed to start local callback server on port {}: {}", port, e.getMessage(), e);
            notify.accept(message);
            return CompletableFuture.completedFuture(false);
        }

        String spotifyAuthorizeUrl = buildAuthorizeUrl(authorizeEndpoint, clientId, redirectUri, codeChallenge, state);

        HttpHandler authorizeHandler = exchange -> {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendHtmlResponse(exchange, 405, "Method Not Allowed", "Invalid HTTP method.", true, 0);
                return;
            }

            // Probe the Client ID before redirecting the browser so an invalid ID renders a styled
            // error page locally instead of Spotify's blank "client_id: invalid" page.
            String invalidClientDescription = probeInvalidClientDescription(clientId, redirectUri);
            if (invalidClientDescription != null) {
                handled.compareAndSet(false, true);
                sendHtmlResponse(
                        exchange,
                        400,
                        "Invalid Spotify Client ID",
                        "Spotify rejected your Client ID (" + invalidClientDescription
                                + "). Double-check the Client ID in your Spotify Developer Dashboard (Settings -> Client ID) and try again in Minecraft.",
                        true,
                        0
                );
                scheduleServerStop(ipv4Server);
                notify.accept(SpotifyI18n.translate("status.spotify_controller.auth.invalid_client_id"));
                resultFuture.complete(false);
                return;
            }

            exchange.getResponseHeaders().set("Location", spotifyAuthorizeUrl);
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        };

        HttpHandler callbackHandler = exchange -> {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendHtmlResponse(exchange, 405, "Method Not Allowed", "Invalid HTTP method.", true, 0);
                return;
            }
            if (!handled.compareAndSet(false, true)) {
                sendHtmlResponse(exchange, 200, "Spotify Controller",
                        "Authentication already processed. You may close this tab.", false, 0);
                return;
            }

            Map<String, String> params = parseQueryParams(exchange.getRequestURI().getRawQuery());
            String error = params.get("error");
            if (error != null && !error.isBlank()) {
                String msg = SpotifyI18n.translate("status.spotify_controller.auth.failed", error);
                sendHtmlResponse(exchange, 400, "Spotify Connection Failed",
                        "Authorization was cancelled or denied (" + error + "). You can return to Minecraft and try again.",
                        true, 0);
                scheduleServerStop(ipv4Server);
                notify.accept(msg);
                resultFuture.complete(false);
                return;
            }

            String returnedState = params.get("state");
            String code = params.get("code");
            if (returnedState == null || !returnedState.equals(state)) {
                String msg = SpotifyI18n.translate("status.spotify_controller.auth.state_mismatch");
                sendHtmlResponse(exchange, 400, "Spotify Connection Failed",
                        "Invalid OAuth state parameter. Please try connecting again from Minecraft.",
                        true, 0);
                scheduleServerStop(ipv4Server);
                notify.accept(msg);
                resultFuture.complete(false);
                return;
            }

            if (code == null || code.isBlank()) {
                String msg = SpotifyI18n.translate("status.spotify_controller.auth.missing_code");
                sendHtmlResponse(exchange, 400, "Spotify Connection Failed",
                        "Missing authorization code in callback. Please try again.",
                        true, 0);
                scheduleServerStop(ipv4Server);
                notify.accept(msg);
                resultFuture.complete(false);
                return;
            }

            notify.accept(SpotifyI18n.translate("status.spotify_controller.auth.exchanging_code"));
            try {
                boolean exchanged = Boolean.TRUE.equals(
                        exchangeCodeForTokens(clientId, code, redirectUri, codeVerifier).get(16, TimeUnit.SECONDS)
                );
                if (exchanged) {
                    sendHtmlResponse(
                            exchange,
                            200,
                            "Spotify Connected",
                            "Authorization received! You can return to Minecraft now.",
                            false,
                            CONNECTED_AUTO_CLOSE_SECONDS
                    );
                    scheduleServerStop(ipv4Server);
                    notify.accept(SpotifyI18n.translate("status.spotify_controller.auth.connected"));
                    resultFuture.complete(true);
                } else {
                    sendHtmlResponse(
                            exchange,
                            400,
                            "Spotify Connection Failed",
                            "Failed to exchange the authorization code for Spotify tokens. Check your Client ID and Redirect URL in the Spotify Developer Dashboard and try again.",
                            true,
                            0
                    );
                    scheduleServerStop(ipv4Server);
                    notify.accept(SpotifyI18n.translate("status.spotify_controller.auth.exchange_failed"));
                    resultFuture.complete(false);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                sendHtmlResponse(exchange, 500, "Spotify Connection Failed",
                        "Token exchange was interrupted. Please return to Minecraft and try again.",
                        true, 0);
                scheduleServerStop(ipv4Server);
                notify.accept(SpotifyI18n.translate("status.spotify_controller.auth.exchange_error", e.getMessage()));
                resultFuture.complete(false);
            } catch (ExecutionException | TimeoutException e) {
                Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
                LOGGER.error("Token exchange failed", cause);
                sendHtmlResponse(exchange, 500, "Spotify Connection Failed",
                        "Token exchange failed (" + cause.getMessage() + "). Please try again in Minecraft.",
                        true, 0);
                scheduleServerStop(ipv4Server);
                notify.accept(SpotifyI18n.translate("status.spotify_controller.auth.exchange_error", cause.getMessage()));
                resultFuture.complete(false);
            }
        };

        ExecutorService callbackExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "spotify-oauth-callback");
            t.setDaemon(true);
            return t;
        });

        ipv4Server.createContext("/authorize", authorizeHandler);
        ipv4Server.createContext("/callback", callbackHandler);
        ipv4Server.setExecutor(callbackExecutor);
        ipv4Server.start();
        LOGGER.info("Spotify OAuth callback server listening on 127.0.0.1:{}", port);

        HttpServer ipv6Server = null;
        try {
            HttpServer candidateIpv6 = HttpServer.create(new InetSocketAddress(loopbackIpv6Address(), port), 0);
            try {
                candidateIpv6.createContext("/authorize", authorizeHandler);
                candidateIpv6.createContext("/callback", callbackHandler);
                candidateIpv6.setExecutor(callbackExecutor);
                candidateIpv6.start();
                ipv6Server = candidateIpv6;
                LOGGER.info("Spotify OAuth callback server also listening on [::1]:{}", port);
            } catch (RuntimeException e) {
                candidateIpv6.stop(0);
                LOGGER.debug("Failed to initialize optional IPv6 loopback listener on [::1]:{}", port, e);
            }
        } catch (IOException e) {
            LOGGER.debug("Optional IPv6 loopback listener on [::1]:{} unavailable: {}", port, e.getMessage());
        }

        synchronized (serverLock) {
            this.activeCallbackServer = ipv4Server;
            this.activeIpv6CallbackServer = ipv6Server;
            this.activeCallbackExecutor = callbackExecutor;
            this.activeAuthFuture = resultFuture;
        }

        // Automatically tear down the temporary listener if the user never finishes in-browser auth.
        cleanupExecutor.schedule(() -> {
            if (handled.compareAndSet(false, true) && !resultFuture.isDone()) {
                stopCallbackServerIfMatches(ipv4Server);
                notify.accept(SpotifyI18n.translate("status.spotify_controller.auth.timeout"));
                resultFuture.complete(false);
            }
        }, 3, TimeUnit.MINUTES);

        String launcherUrl = "http://127.0.0.1:" + port + "/authorize";
        notify.accept(SpotifyI18n.translate("status.spotify_controller.auth.waiting_browser", redirectUri));
        try {
            browserOpener.accept(URI.create(launcherUrl));
        } catch (Exception e) {
            LOGGER.error("Could not open browser for Spotify login", e);
            stopCallbackServerIfMatches(ipv4Server);
            notify.accept(SpotifyI18n.translate("status.spotify_controller.auth.browser_error", e.getMessage()));
            resultFuture.complete(false);
        }

        return resultFuture;
    }

    /**
     * Exchanges the stored refresh token for a fresh access token.
     */
    public CompletableFuture<Boolean> refreshAccessToken() {
        if (!config.hasRefreshToken() || !config.hasClientId()) {
            return CompletableFuture.completedFuture(false);
        }

        synchronized (refreshLock) {
            if (inFlightRefresh != null && !inFlightRefresh.isDone()) {
                return inFlightRefresh;
            }

            String clientId;
            String refreshToken;
            synchronized (config) {
                clientId = config.clientId;
                refreshToken = config.refreshToken;
            }

            String formBody = buildFormBody(Map.of(
                    "grant_type", "refresh_token",
                    "refresh_token", refreshToken,
                    "client_id", clientId
            ));

            HttpRequest request = HttpRequest.newBuilder(URI.create(tokenEndpoint))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(formBody))
                    .build();

            CompletableFuture<Boolean> future = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> handleTokenResponse(response, true))
                    .exceptionally(ex -> {
                        LOGGER.warn("Spotify token refresh request failed", ex);
                        return false;
                    })
                    .whenComplete((ok, ex) -> {
                        synchronized (refreshLock) {
                            inFlightRefresh = null;
                        }
                    });

            inFlightRefresh = future;
            return future;
        }
    }

    /**
     * Returns a non-expired access token, refreshing it automatically if needed.
     * Returns {@code null} when not authenticated.
     */
    public CompletableFuture<String> getValidAccessToken() {
        synchronized (config) {
            if (config.hasValidAccessToken()) {
                return CompletableFuture.completedFuture(config.accessToken);
            }
        }
        if (!config.hasRefreshToken() || !config.hasClientId()) {
            return CompletableFuture.completedFuture(null);
        }
        return refreshAccessToken().thenApply(ok -> {
            if (!Boolean.TRUE.equals(ok)) {
                // Rejected refresh tokens are cleared in handleTokenResponse. If one is still stored,
                // the failure was transient (offline, Spotify 5xx), so report an error, not "logged out".
                if (config.hasRefreshToken()) {
                    throw new CompletionException(new IOException(
                            SpotifyI18n.translate("status.spotify_controller.refresh_unreachable")));
                }
                return null;
            }
            synchronized (config) {
                return config.accessToken;
            }
        });
    }

    public void shutdown() {
        stopCallbackServer();
        cleanupExecutor.shutdownNow();
    }

    private String probeInvalidClientDescription(String clientId, String redirectUri) {
        try {
            String formBody = buildFormBody(Map.of(
                    "client_id", clientId,
                    "grant_type", "authorization_code",
                    "code", "spotify_controller_probe",
                    "redirect_uri", redirectUri,
                    "code_verifier", "spotify_controller_probe_verifier"
            ));
            HttpRequest request = HttpRequest.newBuilder(URI.create(tokenEndpoint))
                    .timeout(Duration.ofSeconds(4))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(formBody))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 400) {
                TokenError error = parseTokenError(response.body());
                if ("invalid_client".equalsIgnoreCase(error.code())) {
                    return error.description().isBlank() ? "Invalid client" : error.description();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOGGER.debug("Pre-flight Client ID probe failed (continuing to browser auth): {}", e.getMessage());
        }
        return null;
    }

    private CompletableFuture<Boolean> exchangeCodeForTokens(
            String clientId,
            String code,
            String redirectUri,
            String codeVerifier
    ) {
        String formBody = buildFormBody(Map.of(
                "client_id", clientId,
                "grant_type", "authorization_code",
                "code", code,
                "redirect_uri", redirectUri,
                "code_verifier", codeVerifier
        ));

        HttpRequest request = HttpRequest.newBuilder(URI.create(tokenEndpoint))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formBody))
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> handleTokenResponse(response, false));
    }

    private boolean handleTokenResponse(HttpResponse<String> response, boolean isRefresh) {
        int status = response.statusCode();
        String body = response.body();
        if (status >= 200 && status < 300) {
            try {
                JsonObject json = JsonParser.parseString(body).getAsJsonObject();
                String accessToken = json.has("access_token") ? json.get("access_token").getAsString() : "";
                String refreshToken = (json.has("refresh_token") && !json.get("refresh_token").isJsonNull())
                        ? json.get("refresh_token").getAsString()
                        : null;
                long expiresIn = json.has("expires_in") ? json.get("expires_in").getAsLong() : 3600L;

                if (accessToken.isBlank()) {
                    LOGGER.warn("Spotify token endpoint returned 2xx without access_token");
                    return false;
                }
                config.updateTokens(accessToken, refreshToken, expiresIn);
                return true;
            } catch (Exception e) {
                LOGGER.error("Failed to parse Spotify token response", e);
                return false;
            }
        }

        TokenError error = parseTokenError(body);
        LOGGER.warn("Spotify token request failed (HTTP {}): {} - {}", status, error.code(), error.description());
        if (isRefresh && ("invalid_grant".equals(error.code())
                || "invalid_client".equals(error.code())
                || "unauthorized_client".equals(error.code()))) {
            LOGGER.warn("Spotify refresh token rejected ({}); clearing stored tokens.", error.code());
            config.clearTokens();
        }
        return false;
    }

    private static TokenError parseTokenError(String body) {
        String errorCode = "";
        String errorDescription = body != null ? body : "";
        try {
            if (body != null) {
                JsonElement parsed = JsonParser.parseString(body);
                if (parsed.isJsonObject()) {
                    JsonObject obj = parsed.getAsJsonObject();
                    if (obj.has("error")) {
                        errorCode = obj.get("error").getAsString();
                    }
                    if (obj.has("error_description")) {
                        errorDescription = obj.get("error_description").getAsString();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return new TokenError(errorCode, errorDescription);
    }

    private void stopCallbackServer() {
        synchronized (serverLock) {
            stopCallbackServersLocked();
            if (activeAuthFuture != null && !activeAuthFuture.isDone()) {
                activeAuthFuture.complete(false);
            }
            activeAuthFuture = null;
        }
    }

    private void stopCallbackServerIfMatches(HttpServer server) {
        synchronized (serverLock) {
            if (activeCallbackServer == server) {
                stopCallbackServersLocked();
            }
        }
    }

    private void stopCallbackServersLocked() {
        if (activeCallbackServer != null) {
            activeCallbackServer.stop(0);
            activeCallbackServer = null;
        }
        if (activeIpv6CallbackServer != null) {
            activeIpv6CallbackServer.stop(0);
            activeIpv6CallbackServer = null;
        }
        if (activeCallbackExecutor != null) {
            activeCallbackExecutor.shutdownNow();
            activeCallbackExecutor = null;
        }
    }

    private void scheduleServerStop(HttpServer server) {
        cleanupExecutor.schedule(() -> stopCallbackServerIfMatches(server), 2, TimeUnit.SECONDS);
    }

    private static InetAddress loopbackIpv4Address() {
        try {
            return InetAddress.getByAddress("127.0.0.1", new byte[]{127, 0, 0, 1});
        } catch (IOException e) {
            throw new IllegalStateException("Invalid IPv4 loopback address", e);
        }
    }

    private static InetAddress loopbackIpv6Address() {
        try {
            return InetAddress.getByAddress(
                    "localhost",
                    new byte[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1}
            );
        } catch (IOException e) {
            throw new IllegalStateException("Invalid IPv6 loopback address", e);
        }
    }

    private static String buildAuthorizeUrl(
            String authorizeEndpoint,
            String clientId,
            String redirectUri,
            String codeChallenge,
            String state
    ) {
        return authorizeEndpoint
                + "?response_type=code"
                + "&client_id=" + urlEncode(clientId)
                + "&scope=" + urlEncode(SCOPES)
                + "&redirect_uri=" + urlEncode(redirectUri)
                + "&code_challenge_method=S256"
                + "&code_challenge=" + urlEncode(codeChallenge)
                + "&state=" + urlEncode(state);
    }

    private static String buildFormBody(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (!sb.isEmpty()) {
                sb.append('&');
            }
            sb.append(urlEncode(entry.getKey())).append('=').append(urlEncode(entry.getValue()));
        }
        return sb.toString();
    }

    private static Map<String, String> parseQueryParams(String rawQuery) {
        Map<String, String> map = new HashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return map;
        }
        for (String pair : rawQuery.split("&")) {
            int idx = pair.indexOf('=');
            if (idx > 0) {
                String key = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8);
                String value = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
                map.put(key, value);
            }
        }
        return map;
    }

    private static void sendHtmlResponse(
            HttpExchange exchange,
            int statusCode,
            String title,
            String message,
            boolean isError,
            int autoCloseSeconds
    ) throws IOException {
        String accentHex = isError ? "#FF5252" : "#1DB954";
        String countdownBlock = autoCloseSeconds > 0
                ? "<p class=\"hint\" id=\"close-hint\">This tab will automatically close in <span id=\"countdown\">"
                        + autoCloseSeconds + "</span> seconds.</p>"
                : "";
        String autoCloseScript = autoCloseSeconds > 0
                ? """
                  <script>
                    (function () {
                      var remaining = %d;
                      var countdown = document.getElementById('countdown');
                      var hint = document.getElementById('close-hint');
                      var timer = setInterval(function () {
                        remaining -= 1;
                        if (countdown && remaining >= 0) {
                          countdown.textContent = String(remaining);
                        }
                        if (remaining <= 0) {
                          clearInterval(timer);
                          window.open('', '_self');
                          window.close();
                          setTimeout(function () {
                            if (hint) {
                              hint.textContent = 'You may close this tab now.';
                            }
                          }, 400);
                        }
                      }, 1000);
                    })();
                  </script>
                  """.formatted(autoCloseSeconds)
                : "";

        String html = """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <meta charset="UTF-8">
                  <title>%s</title>
                  <style>
                    body { font-family: system-ui, -apple-system, sans-serif; background: #121212; color: #fff;
                           display: flex; align-items: center; justify-content: center; height: 100vh; margin: 0; }
                    .card { background: #1e1e1e; border-radius: 12px; padding: 32px; max-width: 460px;
                            text-align: center; box-shadow: 0 8px 24px rgba(0,0,0,0.4);
                            border-top: 4px solid %s; }
                    h1 { color: %s; margin-top: 0; font-size: 1.5rem; }
                    p { color: #d4d4d4; line-height: 1.5; margin-bottom: 0; }
                    .hint { color: #9a9a9a; font-size: 0.9rem; margin-top: 12px; }
                  </style>
                </head>
                <body>
                  <div class="card">
                    <h1>%s</h1>
                    <p>%s</p>
                    %s
                  </div>
                  %s
                </body>
                </html>
                """.formatted(
                escapeHtml(title),
                accentHex,
                accentHex,
                escapeHtml(title),
                escapeHtml(message),
                countdownBlock,
                autoCloseScript
        );

        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String generateRandomState() {
        byte[] bytes = new byte[24];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static void openInDefaultBrowser(URI uri) {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            try {
                Desktop.getDesktop().browse(uri);
                return;
            } catch (IOException e) {
                throw new RuntimeException("Failed to launch browser", e);
            }
        }
        throw new IllegalStateException("System browser browsing is not supported on this platform");
    }
}
