package com.noah.spotifycontroller.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.noah.spotifycontroller.auth.SpotifyAuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Asynchronous client for the Spotify Web API playback endpoints.
 */
public class SpotifyApiClient {
    private static final Logger LOGGER = LoggerFactory.getLogger("spotify_controller");
    private static final String BASE_URL = "https://api.spotify.com/v1/me/player";

    public record ApiResult(boolean success, String message) {
    }

    private final SpotifyAuthService authService;
    private final HttpClient httpClient;
    private volatile long rateLimitedUntilMillis = 0L;

    public SpotifyApiClient(SpotifyAuthService authService, HttpClient httpClient) {
        this.authService = authService;
        this.httpClient = httpClient;
    }

    public boolean isRateLimited() {
        return System.currentTimeMillis() < rateLimitedUntilMillis;
    }

    public CompletableFuture<Optional<TrackState>> getPlaybackState() {
        if (isRateLimited()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return sendAuthorizedRequest("GET", BASE_URL, null, true)
                .thenApply(response -> {
                    if (response == null) {
                        return Optional.empty();
                    }
                    int status = response.statusCode();
                    if (status == 204) {
                        return Optional.empty();
                    }
                    if (status >= 200 && status < 300) {
                        return TrackState.fromPlayerJson(response.body());
                    }
                    if (status != 429) {
                        LOGGER.debug("GET /me/player returned HTTP {}: {}", status, response.body());
                    }
                    return Optional.empty();
                });
    }

    public CompletableFuture<ApiResult> play() {
        return sendControlRequest("PUT", BASE_URL + "/play", "{}", "Resumed playback.");
    }

    public CompletableFuture<ApiResult> pause() {
        return sendControlRequest("PUT", BASE_URL + "/pause", "", "Paused playback.");
    }

    public CompletableFuture<ApiResult> togglePlayPause(TrackState current) {
        if (current != null && current.isPlaying()) {
            return pause();
        }
        return play();
    }

    public CompletableFuture<ApiResult> nextTrack() {
        return sendControlRequest("POST", BASE_URL + "/next", "", "Skipped to next track.");
    }

    public CompletableFuture<ApiResult> previousTrack() {
        return sendControlRequest("POST", BASE_URL + "/previous", "", "Returned to previous track.");
    }

    public CompletableFuture<ApiResult> setVolume(int volumePercent) {
        int clamped = clampVolume(volumePercent);
        return sendControlRequest(
                "PUT",
                BASE_URL + "/volume?volume_percent=" + clamped,
                "",
                "Set volume to " + clamped + "%."
        );
    }

    public CompletableFuture<ApiResult> setShuffle(boolean enabled) {
        return sendControlRequest(
                "PUT",
                BASE_URL + "/shuffle?state=" + enabled,
                "",
                "Shuffle " + (enabled ? "enabled." : "disabled.")
        );
    }

    public CompletableFuture<ApiResult> setRepeatMode(String repeatState) {
        String normalized = (repeatState == null || repeatState.isBlank()) ? "off" : repeatState.trim().toLowerCase();
        if (!normalized.equals("off") && !normalized.equals("context") && !normalized.equals("track")) {
            normalized = "off";
        }
        return sendControlRequest(
                "PUT",
                BASE_URL + "/repeat?state=" + normalized,
                "",
                "Repeat mode set to " + formatRepeatLabel(normalized) + "."
        );
    }

    public CompletableFuture<ApiResult> seekTo(long positionMs) {
        long clamped = Math.max(0L, positionMs);
        return sendControlRequest(
                "PUT",
                BASE_URL + "/seek?position_ms=" + clamped,
                "",
                "Seeked to " + TrackState.formatDuration(clamped) + "."
        );
    }

    public static int clampVolume(int volumePercent) {
        return Math.max(0, Math.min(100, volumePercent));
    }

    public static String nextRepeatState(String currentRepeatState) {
        if (currentRepeatState == null) {
            return "context";
        }
        return switch (currentRepeatState.toLowerCase()) {
            case "off" -> "context";
            case "context" -> "track";
            case "track" -> "off";
            default -> "context";
        };
    }

    public static String formatRepeatLabel(String repeatState) {
        if (repeatState == null) {
            return "OFF";
        }
        return switch (repeatState.toLowerCase()) {
            case "context" -> "ALL";
            case "track" -> "ONE";
            default -> "OFF";
        };
    }

    public static long computeSeekPositionMs(double mouseX, int barX, int barWidth, long durationMs) {
        if (durationMs <= 0L || barWidth <= 0) {
            return 0L;
        }
        double ratio = Math.max(0.0, Math.min(1.0, (mouseX - barX) / (double) barWidth));
        return Math.round(ratio * durationMs);
    }

    public static int computeVolumePercent(double mouseX, int barX, int barWidth) {
        if (barWidth <= 0) {
            return 0;
        }
        double ratio = Math.max(0.0, Math.min(1.0, (mouseX - barX) / (double) barWidth));
        return clampVolume((int) Math.round(ratio * 100.0));
    }

    private CompletableFuture<ApiResult> sendControlRequest(
            String method,
            String url,
            String jsonBody,
            String successMessage
    ) {
        if (isRateLimited()) {
            long waitSec = Math.max(1L, (rateLimitedUntilMillis - System.currentTimeMillis()) / 1000L);
            return CompletableFuture.completedFuture(
                    new ApiResult(false, "Spotify rate limit active. Retry in " + waitSec + "s.")
            );
        }

        return sendAuthorizedRequest(method, url, jsonBody, true)
                .thenApply(response -> {
                    if (response == null) {
                        return new ApiResult(false, "Not connected to Spotify. Connect in the Config screen or via /spotify login.");
                    }
                    int status = response.statusCode();
                    if (status >= 200 && status < 300) {
                        return new ApiResult(true, successMessage);
                    }
                    return new ApiResult(false, formatErrorMessage(status, response));
                })
                .exceptionally(ex -> {
                    LOGGER.warn("Spotify API request {} {} failed", method, url, ex);
                    return new ApiResult(false, "Spotify API request failed: " + ex.getMessage());
                });
    }

    private CompletableFuture<HttpResponse<String>> sendAuthorizedRequest(
            String method,
            String url,
            String jsonBody,
            boolean allowRetryOn401
    ) {
        return authService.getValidAccessToken().thenCompose(token -> {
            if (token == null || token.isBlank()) {
                return CompletableFuture.completedFuture(null);
            }

            HttpRequest request = buildRequest(method, url, jsonBody, token);
            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenCompose(response -> {
                        if (response.statusCode() == 401 && allowRetryOn401) {
                            return authService.refreshAccessToken().thenCompose(refreshed -> {
                                if (!Boolean.TRUE.equals(refreshed)) {
                                    return CompletableFuture.completedFuture(response);
                                }
                                return sendAuthorizedRequest(method, url, jsonBody, false);
                            });
                        }
                        if (response.statusCode() == 429) {
                            long retryAfterSec = parseRetryAfterSeconds(response);
                            rateLimitedUntilMillis = System.currentTimeMillis() + retryAfterSec * 1000L;
                            LOGGER.warn("Spotify API rate-limited (429); backing off for {}s", retryAfterSec);
                        }
                        return CompletableFuture.completedFuture(response);
                    });
        });
    }

    private static HttpRequest buildRequest(String method, String url, String jsonBody, String accessToken) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + accessToken);

        if ("GET".equals(method)) {
            builder.GET();
        } else if ("POST".equals(method)) {
            builder.header("Content-Type", "application/json");
            builder.POST(HttpRequest.BodyPublishers.ofString(jsonBody != null ? jsonBody : ""));
        } else if ("PUT".equals(method)) {
            builder.header("Content-Type", "application/json");
            builder.PUT(HttpRequest.BodyPublishers.ofString(jsonBody != null ? jsonBody : ""));
        } else {
            throw new IllegalArgumentException("Unsupported HTTP method: " + method);
        }
        return builder.build();
    }

    private static long parseRetryAfterSeconds(HttpResponse<String> response) {
        Optional<String> header = response.headers().firstValue("Retry-After");
        if (header.isPresent()) {
            try {
                return Math.max(1L, Long.parseLong(header.get().trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        return 5L;
    }

    private static String formatErrorMessage(int status, HttpResponse<String> response) {
        String apiMessage = extractSpotifyErrorMessage(response.body());
        String apiReason = extractSpotifyErrorReason(response.body());

        return switch (status) {
            case 401 -> "Spotify session expired. Please reconnect via /spotify login.";
            case 403 -> {
                if ("PREMIUM_REQUIRED".equalsIgnoreCase(apiReason)
                        || apiMessage.toLowerCase().contains("premium")) {
                    yield "Spotify Premium is required for playback control.";
                }
                yield apiMessage.isEmpty()
                        ? "Spotify denied permission for this action."
                        : "Spotify error (403): " + apiMessage;
            }
            case 404 -> "No active Spotify device found — start playing on Spotify first.";
            case 429 -> "Spotify rate limit reached. Please wait " + parseRetryAfterSeconds(response) + "s.";
            default -> apiMessage.isEmpty()
                    ? "Spotify API error (HTTP " + status + ")."
                    : "Spotify API error (" + status + "): " + apiMessage;
        };
    }

    private static String extractSpotifyErrorMessage(String body) {
        JsonObject error = extractErrorObject(body);
        if (error != null && error.has("message") && !error.get("message").isJsonNull()) {
            return error.get("message").getAsString();
        }
        return "";
    }

    private static String extractSpotifyErrorReason(String body) {
        JsonObject error = extractErrorObject(body);
        if (error != null && error.has("reason") && !error.get("reason").isJsonNull()) {
            return error.get("reason").getAsString();
        }
        return "";
    }

    private static JsonObject extractErrorObject(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonElement root = JsonParser.parseString(body);
            if (root.isJsonObject() && root.getAsJsonObject().has("error")) {
                JsonElement err = root.getAsJsonObject().get("error");
                if (err.isJsonObject()) {
                    return err.getAsJsonObject();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
