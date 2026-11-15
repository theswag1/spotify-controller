package com.noah.spotifycontroller.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Persists Spotify Controller settings, OAuth PKCE tokens, and HUD preferences
 * in {@code config/spotify_controller.json}.
 */
public class SpotifyConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("spotify_controller");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int DEFAULT_CALLBACK_PORT = 8888;
    private static final int DEFAULT_POLL_INTERVAL_SECONDS = 3;
    public static final int DEFAULT_HUD_X = 10;
    public static final int DEFAULT_HUD_Y = 10;

    private static volatile SpotifyConfig instance;

    private transient Path configPath;

    public String clientId = "";
    public int callbackPort = DEFAULT_CALLBACK_PORT;
    public String accessToken = "";
    public String refreshToken = "";
    public long tokenExpiresAtEpochSeconds = 0L;
    public boolean hudEnabled = true;
    public int hudX = DEFAULT_HUD_X;
    public int hudY = DEFAULT_HUD_Y;
    public int pollIntervalSeconds = DEFAULT_POLL_INTERVAL_SECONDS;

    public SpotifyConfig() {
    }

    private SpotifyConfig(Path configPath) {
        this.configPath = configPath;
    }

    /**
     * Returns the singleton config instance backed by Fabric's config directory.
     */
    public static SpotifyConfig get() {
        SpotifyConfig current = instance;
        if (current == null) {
            synchronized (SpotifyConfig.class) {
                current = instance;
                if (current == null) {
                    Path path = FabricLoader.getInstance().getConfigDir().resolve("spotify_controller.json");
                    current = loadFrom(path);
                    instance = current;
                }
            }
        }
        return current;
    }

    /**
     * Loads or initializes a config instance at a specific path (useful for testing).
     */
    public static SpotifyConfig loadFrom(Path path) {
        SpotifyConfig config = new SpotifyConfig(path);
        config.load();
        return config;
    }

    public synchronized void load() {
        if (configPath == null) {
            return;
        }
        if (Files.notExists(configPath)) {
            save();
            return;
        }

        try {
            String raw = Files.readString(configPath, StandardCharsets.UTF_8);
            SpotifyConfig loaded = GSON.fromJson(raw, SpotifyConfig.class);
            if (loaded != null) {
                this.clientId = sanitizeString(loaded.clientId).trim();
                this.callbackPort = isValidPort(loaded.callbackPort) ? loaded.callbackPort : DEFAULT_CALLBACK_PORT;
                this.accessToken = sanitizeString(loaded.accessToken).trim();
                this.refreshToken = sanitizeString(loaded.refreshToken).trim();
                this.tokenExpiresAtEpochSeconds = Math.max(0L, loaded.tokenExpiresAtEpochSeconds);
                this.hudEnabled = loaded.hudEnabled;
                this.hudX = loaded.hudX;
                this.hudY = loaded.hudY;
                this.pollIntervalSeconds = loaded.pollIntervalSeconds >= 1
                        ? loaded.pollIntervalSeconds
                        : DEFAULT_POLL_INTERVAL_SECONDS;
            }
        } catch (IOException | JsonSyntaxException e) {
            LOGGER.warn("Failed to read Spotify Controller config at {}, keeping current defaults", configPath, e);
        }
    }

    public synchronized void save() {
        if (configPath == null) {
            return;
        }
        try {
            Path parent = configPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            this.clientId = sanitizeString(this.clientId).trim();
            this.callbackPort = isValidPort(this.callbackPort) ? this.callbackPort : DEFAULT_CALLBACK_PORT;
            this.pollIntervalSeconds = Math.max(1, this.pollIntervalSeconds);
            Files.writeString(configPath, GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.error("Failed to save Spotify Controller config to {}", configPath, e);
        }
    }

    public synchronized void setClientId(String clientId) {
        this.clientId = sanitizeString(clientId).trim();
        save();
    }

    public synchronized void setHudPosition(int x, int y) {
        this.hudX = x;
        this.hudY = y;
        save();
    }

    public synchronized void resetHudPosition() {
        this.hudX = DEFAULT_HUD_X;
        this.hudY = DEFAULT_HUD_Y;
        save();
    }

    public synchronized void updateTokens(String accessToken, String refreshToken, long expiresInSeconds) {
        this.accessToken = sanitizeString(accessToken).trim();
        if (refreshToken != null && !refreshToken.isBlank()) {
            this.refreshToken = refreshToken.trim();
        }
        long clampedExpires = Math.max(60L, expiresInSeconds);
        this.tokenExpiresAtEpochSeconds = Instant.now().getEpochSecond() + clampedExpires;
        save();
    }

    public synchronized void clearTokens() {
        this.accessToken = "";
        this.refreshToken = "";
        this.tokenExpiresAtEpochSeconds = 0L;
        save();
    }

    public synchronized boolean hasClientId() {
        return clientId != null && !clientId.isBlank();
    }

    public synchronized boolean hasValidAccessToken() {
        return accessToken != null
                && !accessToken.isBlank()
                && Instant.now().getEpochSecond() < tokenExpiresAtEpochSeconds - 30;
    }

    public synchronized boolean hasRefreshToken() {
        return refreshToken != null && !refreshToken.isBlank();
    }

    public synchronized String getRedirectUri() {
        int port = isValidPort(callbackPort) ? callbackPort : DEFAULT_CALLBACK_PORT;
        return "http://127.0.0.1:" + port + "/callback";
    }

    private static String sanitizeString(String value) {
        return value == null ? "" : value;
    }

    private static boolean isValidPort(int port) {
        return port >= 1024 && port <= 65535;
    }
}
