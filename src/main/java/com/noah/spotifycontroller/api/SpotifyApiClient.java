package com.noah.spotifycontroller.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.noah.spotifycontroller.auth.SpotifyAuthService;
import com.noah.spotifycontroller.util.SpotifyI18n;
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
    private static final String PLAYBACK_STATE_URL = BASE_URL + "?additional_types=track,episode";

    public record ApiResult(boolean success, String message) {
    }

    public enum PlaybackPollStatus {
        ACTIVE,
        NO_PLAYBACK,
        UNAUTHENTICATED,
        RATE_LIMITED,
        ERROR
    }

    public record PlaybackPollResult(PlaybackPollStatus status, Optional<TrackState> track, String message) {
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
        return pollPlaybackState().thenApply(PlaybackPollResult::track);
    }

    public CompletableFuture<PlaybackPollResult> pollPlaybackState() {
        if (isRateLimited()) {
            return CompletableFuture.completedFuture(new PlaybackPollResult(
                    PlaybackPollStatus.RATE_LIMITED,
                    Optional.empty(),
                    SpotifyI18n.translate("status.spotify_controller.rate_limited")
            ));
        }
        return sendAuthorizedRequest("GET", PLAYBACK_STATE_URL, null, true)
                .thenApply(response -> {
                    if (response == null) {
                        return new PlaybackPollResult(
                                PlaybackPollStatus.UNAUTHENTICATED,
                                Optional.empty(),
                                SpotifyI18n.translate("status.spotify_controller.not_connected_hint")
                        );
                    }
                    int status = response.statusCode();
                    if (status == 204) {
                        return new PlaybackPollResult(
                                PlaybackPollStatus.NO_PLAYBACK,
                                Optional.empty(),
                                SpotifyI18n.translate("status.spotify_controller.no_playback")
                        );
                    }
                    if (status >= 200 && status < 300) {
                        Optional<TrackState> parsed = TrackState.fromPlayerJson(response.body());
                        if (parsed.isPresent()) {
                            TrackState track = parsed.get();
                            String msg = SpotifyI18n.translate(
                                    track.isPlaying() ? "status.spotify_controller.playing" : "status.spotify_controller.paused",
                                    track.trackName(),
                                    track.artistName()
                            );
                            return new PlaybackPollResult(PlaybackPollStatus.ACTIVE, parsed, msg);
                        }
                        return new PlaybackPollResult(
                                PlaybackPollStatus.NO_PLAYBACK,
                                Optional.empty(),
                                SpotifyI18n.translate("status.spotify_controller.no_playback")
                        );
                    }
                    if (status == 401) {
                        return new PlaybackPollResult(
                                PlaybackPollStatus.UNAUTHENTICATED,
                                Optional.empty(),
                                SpotifyI18n.translate("status.spotify_controller.session_expired")
                        );
                    }
                    if (status == 429) {
                        return new PlaybackPollResult(
                                PlaybackPollStatus.RATE_LIMITED,
                                Optional.empty(),
                                SpotifyI18n.translate("status.spotify_controller.rate_limited")
                        );
                    }
                    LOGGER.debug("GET /me/player returned HTTP {}: {}", status, response.body());
                    return new PlaybackPollResult(
                            PlaybackPollStatus.ERROR,
                            Optional.empty(),
                            formatErrorMessage(status, response)
                    );
                });
    }

    public CompletableFuture<ApiResult> play() {
        return sendControlRequest("PUT", BASE_URL + "/play", "{}", SpotifyI18n.translate("status.spotify_controller.resumed"));
    }

    public CompletableFuture<ApiResult> pause() {
        return sendControlRequest("PUT", BASE_URL + "/pause", "", SpotifyI18n.translate("status.spotify_controller.paused_action"));
    }

    public CompletableFuture<ApiResult> togglePlayPause(TrackState current) {
        if (current != null && current.isPlaying()) {
            return pause();
        }
        return play();
    }

    public CompletableFuture<ApiResult> nextTrack() {
        return sendControlRequest("POST", BASE_URL + "/next", "", SpotifyI18n.translate("status.spotify_controller.next_track"));
    }

    public CompletableFuture<ApiResult> previousTrack() {
        return sendControlRequest("POST", BASE_URL + "/previous", "", SpotifyI18n.translate("status.spotify_controller.prev_track"));
    }

    public CompletableFuture<ApiResult> setVolume(int volumePercent) {
        int clamped = clampVolume(volumePercent);
        return sendControlRequest(
                "PUT",
                BASE_URL + "/volume?volume_percent=" + clamped,
                "",
                SpotifyI18n.translate("status.spotify_controller.volume_set", clamped)
        );
    }

    public CompletableFuture<ApiResult> setShuffle(boolean enabled) {
        return sendControlRequest(
                "PUT",
                BASE_URL + "/shuffle?state=" + enabled,
                "",
                SpotifyI18n.translate(enabled ? "status.spotify_controller.shuffle_enabled" : "status.spotify_controller.shuffle_disabled")
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
                SpotifyI18n.translate("status.spotify_controller.repeat_set", SpotifyI18n.translate(getRepeatTranslationKey(normalized)))
        );
    }

    public CompletableFuture<ApiResult> seekTo(long positionMs) {
        long clamped = Math.max(0L, positionMs);
        return sendControlRequest(
                "PUT",
                BASE_URL + "/seek?position_ms=" + clamped,
                "",
                SpotifyI18n.translate("status.spotify_controller.seeked", TrackState.formatDuration(clamped))
        );
    }

    public static String getRepeatTranslationKey(String repeatState) {
        if (repeatState == null) {
            return "screen.spotify_controller.controller.repeat.off";
        }
        return switch (repeatState.toLowerCase()) {
            case "context" -> "screen.spotify_controller.controller.repeat.all";
            case "track" -> "screen.spotify_controller.controller.repeat.one";
            default -> "screen.spotify_controller.controller.repeat.off";
        };
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
                    new ApiResult(false, SpotifyI18n.translate("status.spotify_controller.rate_limit_Wait", waitSec))
            );
        }

        return sendAuthorizedRequest(method, url, jsonBody, true)
                .thenApply(response -> {
                    if (response == null) {
                        return new ApiResult(false, SpotifyI18n.translate("status.spotify_controller.not_connected_action"));
                    }
                    int status = response.statusCode();
                    if (status >= 200 && status < 300) {
                        return new ApiResult(true, successMessage);
                    }
                    return new ApiResult(false, formatErrorMessage(status, response));
                })
                .exceptionally(ex -> {
                    LOGGER.warn("Spotify API request {} {} failed", method, url, ex);
                    return new ApiResult(false, SpotifyI18n.translate("status.spotify_controller.request_failed", ex.getMessage()));
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
            if (jsonBody != null && !jsonBody.isEmpty()) {
                builder.header("Content-Type", "application/json");
                builder.POST(HttpRequest.BodyPublishers.ofString(jsonBody));
            } else {
                builder.POST(HttpRequest.BodyPublishers.noBody());
            }
        } else if ("PUT".equals(method)) {
            if (jsonBody != null && !jsonBody.isEmpty()) {
                builder.header("Content-Type", "application/json");
                builder.PUT(HttpRequest.BodyPublishers.ofString(jsonBody));
            } else {
                builder.PUT(HttpRequest.BodyPublishers.noBody());
            }
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
            case 401 -> SpotifyI18n.translate("status.spotify_controller.error_401");
            case 403 -> {
                if ("PREMIUM_REQUIRED".equalsIgnoreCase(apiReason)
                        || apiMessage.toLowerCase().contains("premium")) {
                    yield SpotifyI18n.translate("status.spotify_controller.error_403_premium");
                }
                yield apiMessage.isEmpty()
                        ? SpotifyI18n.translate("status.spotify_controller.error_403_denied")
                        : SpotifyI18n.translate("status.spotify_controller.error_403_detail", apiMessage);
            }
            case 404 -> SpotifyI18n.translate("status.spotify_controller.error_404_no_device");
            case 429 -> SpotifyI18n.translate("status.spotify_controller.error_429", parseRetryAfterSeconds(response));
            default -> apiMessage.isEmpty()
                    ? SpotifyI18n.translate("status.spotify_controller.error_http", status)
                    : SpotifyI18n.translate("status.spotify_controller.error_http_detail", status, apiMessage);
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
