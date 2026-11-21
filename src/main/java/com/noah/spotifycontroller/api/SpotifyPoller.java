package com.noah.spotifycontroller.api;

import com.noah.spotifycontroller.SpotifyControllerClient;
import com.noah.spotifycontroller.config.SpotifyConfig;
import com.noah.spotifycontroller.util.SpotifyI18n;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Periodically polls the Spotify Web API in the background to keep the current
 * playback state fresh without blocking Minecraft's client tick or render threads.
 */
public class SpotifyPoller {
    private static final Logger LOGGER = LoggerFactory.getLogger("encore_music");
    public static final int IDLE_PAUSED_POLL_SECONDS = 6;
    public static final int IDLE_NO_PLAYBACK_POLL_SECONDS = 8;
    public static final int IDLE_MENU_POLL_SECONDS = 10;
    public static final int IDLE_UNFOCUSED_POLL_SECONDS = 10;
    /** Spotify needs a moment to switch tracks; polling exactly at the end often still returns the old one. */
    private static final long END_OF_TRACK_GRACE_MS = 750L;

    private final SpotifyConfig config;
    private final SpotifyApiClient apiClient;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean pollInFlight = new AtomicBoolean(false);
    private final AtomicInteger secondsSinceLastPoll = new AtomicInteger(Integer.MAX_VALUE / 2);
    private volatile boolean started = false;
    private volatile long endOfTrackRefreshDueAtMs = 0L;

    private volatile TrackState currentTrack;
    private volatile String lastStatusMessage = SpotifyI18n.translate("status.spotify_controller.not_connected");
    private volatile SpotifyApiClient.PlaybackPollStatus lastStatus = SpotifyApiClient.PlaybackPollStatus.UNAUTHENTICATED;

    public SpotifyPoller(SpotifyConfig config, SpotifyApiClient apiClient) {
        this.config = config;
        this.apiClient = apiClient;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "encore-music-poller");
            t.setDaemon(true);
            return t;
        });
    }

    public synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        secondsSinceLastPoll.set(Integer.MAX_VALUE / 2);
        scheduler.scheduleAtFixedRate(this::tickPoller, 1, 1, TimeUnit.SECONDS);
    }

    /**
     * Computes the effective poll interval in seconds, slowing down when paused, idle, on menus, or unfocused.
     */
    public static int computeEffectivePollIntervalSeconds(
            int configuredPollSeconds,
            TrackState currentTrack,
            boolean inWorld,
            boolean windowFocused,
            boolean spotifyUiOpen
    ) {
        int base = Math.max(1, configuredPollSeconds);
        if (!windowFocused) {
            return Math.max(base, IDLE_UNFOCUSED_POLL_SECONDS);
        }
        if (!inWorld && !spotifyUiOpen) {
            return Math.max(base, IDLE_MENU_POLL_SECONDS);
        }
        if (currentTrack == null) {
            return Math.max(base, IDLE_NO_PLAYBACK_POLL_SECONDS);
        }
        if (!currentTrack.isPlaying()) {
            return Math.max(base, IDLE_PAUSED_POLL_SECONDS);
        }
        return base;
    }

    /**
     * Returns the current effective poll interval using live {@link SpotifyConfig} and client state.
     */
    public int getEffectivePollIntervalSeconds() {
        int configured = Math.max(1, config.pollIntervalSeconds);
        boolean inWorld = true;
        boolean windowFocused = true;
        boolean spotifyUiOpen = false;
        try {
            Minecraft client = Minecraft.getInstance();
            if (client != null) {
                inWorld = client.player != null && client.level != null;
                windowFocused = client.isWindowActive();
                spotifyUiOpen = SpotifyControllerClient.isSpotifyUiOpen(client);
            }
        } catch (LinkageError | RuntimeException ignored) {
            // Headless unit test environments may not initialize Minecraft client state
        }
        return computeEffectivePollIntervalSeconds(configured, currentTrack, inWorld, windowFocused, spotifyUiOpen);
    }

    /**
     * Delay in ms for an extra poll just after the playing track should end, or -1 when the regular
     * interval poll will come first. Without it the HUD can sit on a finished song for a whole interval.
     */
    public static long computeEndOfTrackRefreshDelayMs(TrackState track, int intervalSeconds) {
        if (track == null || !track.isPlaying() || track.durationMs() <= 0) {
            return -1L;
        }
        long remainingMs = track.durationMs() - track.progressMs();
        if (remainingMs < 0 || remainingMs > intervalSeconds * 1000L) {
            return -1L;
        }
        return remainingMs + END_OF_TRACK_GRACE_MS;
    }

    private void scheduleEndOfTrackRefresh(TrackState track) {
        long delayMs = computeEndOfTrackRefreshDelayMs(track, getEffectivePollIntervalSeconds());
        long now = System.currentTimeMillis();
        // One pending end-of-track refresh at a time, so polls near the end don't stack extra requests.
        if (delayMs < 0 || now < endOfTrackRefreshDueAtMs) {
            return;
        }
        endOfTrackRefreshDueAtMs = now + delayMs;
        triggerImmediateRefresh(delayMs);
    }

    private void tickPoller() {
        int elapsed = secondsSinceLastPoll.incrementAndGet();
        if (elapsed >= getEffectivePollIntervalSeconds()) {
            pollOnce();
        }
    }

    public synchronized void stop() {
        started = false;
        scheduler.shutdownNow();
    }

    public void triggerImmediateRefresh(long delayMs) {
        if (scheduler.isShutdown()) {
            return;
        }
        scheduler.schedule(this::pollOnce, Math.max(0L, delayMs), TimeUnit.MILLISECONDS);
    }

    public void refreshNow() {
        triggerImmediateRefresh(0L);
    }

    public TrackState getCurrentTrack() {
        return currentTrack;
    }

    public String getLastStatusMessage() {
        return lastStatusMessage;
    }

    /** The state behind {@link #getLastStatusMessage()}, so callers can branch without parsing text. */
    public SpotifyApiClient.PlaybackPollStatus getLastStatus() {
        return lastStatus;
    }

    public void setStatusMessage(String message) {
        if (message != null && !message.isBlank()) {
            this.lastStatusMessage = message;
        }
    }

    private void pollOnce() {
        secondsSinceLastPoll.set(0);
        if (!config.hasClientId()) {
            currentTrack = null;
            lastStatus = SpotifyApiClient.PlaybackPollStatus.NOT_CONFIGURED;
            lastStatusMessage = SpotifyI18n.translate("status.spotify_controller.client_id_not_set");
            return;
        }
        if (!config.hasValidAccessToken() && !config.hasRefreshToken()) {
            currentTrack = null;
            lastStatus = SpotifyApiClient.PlaybackPollStatus.UNAUTHENTICATED;
            lastStatusMessage = SpotifyI18n.translate("status.spotify_controller.not_connected_hint");
            return;
        }
        if (apiClient.isRateLimited()) {
            lastStatus = SpotifyApiClient.PlaybackPollStatus.RATE_LIMITED;
            lastStatusMessage = SpotifyI18n.translate("status.spotify_controller.rate_limited");
            return;
        }
        if (!pollInFlight.compareAndSet(false, true)) {
            return;
        }

        apiClient.pollPlaybackState().whenComplete((result, throwable) -> {
            try {
                if (throwable != null) {
                    LOGGER.debug("Spotify background poll error", throwable);
                    lastStatus = SpotifyApiClient.PlaybackPollStatus.ERROR;
                    lastStatusMessage = SpotifyI18n.translate("status.spotify_controller.poll_error",
                            SpotifyApiClient.rootMessage(throwable));
                    return;
                }
                if (result == null) {
                    return;
                }
                lastStatus = result.status();
                switch (result.status()) {
                    case ACTIVE -> {
                        currentTrack = result.track().orElse(null);
                        lastStatusMessage = result.message();
                        scheduleEndOfTrackRefresh(currentTrack);
                    }
                    case NOT_CONFIGURED, NO_PLAYBACK, UNAUTHENTICATED -> {
                        currentTrack = null;
                        lastStatusMessage = result.message();
                    }
                    case RATE_LIMITED, ERROR -> {
                        // Preserve currentTrack during transient rate-limits or 5xx errors so the HUD does not flicker
                        lastStatusMessage = result.message();
                    }
                }
            } finally {
                pollInFlight.set(false);
            }
        });
    }
}
