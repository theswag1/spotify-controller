package com.noah.spotifycontroller.api;

import com.noah.spotifycontroller.config.SpotifyConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Periodically polls the Spotify Web API in the background to keep the current
 * playback state fresh without blocking Minecraft's client tick or render threads.
 */
public class SpotifyPoller {
    private static final Logger LOGGER = LoggerFactory.getLogger("spotify_controller");

    private final SpotifyConfig config;
    private final SpotifyApiClient apiClient;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean pollInFlight = new AtomicBoolean(false);
    private volatile boolean started = false;

    private volatile TrackState currentTrack;
    private volatile String lastStatusMessage = "Not connected";

    public SpotifyPoller(SpotifyConfig config, SpotifyApiClient apiClient) {
        this.config = config;
        this.apiClient = apiClient;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "spotify-controller-poller");
            t.setDaemon(true);
            return t;
        });
    }

    public synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        int interval = Math.max(1, config.pollIntervalSeconds);
        scheduler.scheduleAtFixedRate(this::pollOnce, 1, interval, TimeUnit.SECONDS);
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

    public void setStatusMessage(String message) {
        if (message != null && !message.isBlank()) {
            this.lastStatusMessage = message;
        }
    }

    private void pollOnce() {
        if (!config.hasClientId()) {
            currentTrack = null;
            lastStatusMessage = "Client ID not set";
            return;
        }
        if (!config.hasValidAccessToken() && !config.hasRefreshToken()) {
            currentTrack = null;
            lastStatusMessage = "Not connected — click Connect Spotify or run /spotify login";
            return;
        }
        if (apiClient.isRateLimited()) {
            lastStatusMessage = "Rate limited by Spotify; pausing polls briefly";
            return;
        }
        if (!pollInFlight.compareAndSet(false, true)) {
            return;
        }

        apiClient.pollPlaybackState().whenComplete((result, throwable) -> {
            try {
                if (throwable != null) {
                    LOGGER.debug("Spotify background poll error", throwable);
                    lastStatusMessage = "Error polling Spotify: " + throwable.getMessage();
                    return;
                }
                if (result == null) {
                    return;
                }
                switch (result.status()) {
                    case ACTIVE -> {
                        currentTrack = result.track().orElse(null);
                        lastStatusMessage = result.message();
                    }
                    case NO_PLAYBACK, UNAUTHENTICATED -> {
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
