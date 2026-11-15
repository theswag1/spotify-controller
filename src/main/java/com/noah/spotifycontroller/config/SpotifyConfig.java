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
    public static final float DEFAULT_HUD_SCALE = 1.0f;
    public static final float MIN_HUD_SCALE = 0.5f;
    public static final float MAX_HUD_SCALE = 2.0f;
    public static final float HUD_SCALE_STEP = 0.05f;
    public static final String DEFAULT_ACCENT_COLOR_HEX = "#1DB954";
    public static final int DEFAULT_ACCENT_COLOR_ARGB = 0xFF1DB954;

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
    public float hudScale = DEFAULT_HUD_SCALE;
    public String accentColorHex = DEFAULT_ACCENT_COLOR_HEX;
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
                this.hudScale = loaded.hudScale >= MIN_HUD_SCALE
                        ? clampHudScale(loaded.hudScale)
                        : DEFAULT_HUD_SCALE;
                this.accentColorHex = normalizeHexColor(loaded.accentColorHex);
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
            this.hudScale = clampHudScale(this.hudScale);
            this.accentColorHex = normalizeHexColor(this.accentColorHex);
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
        this.hudScale = DEFAULT_HUD_SCALE;
        save();
    }

    public synchronized void setHudScale(float scale) {
        this.hudScale = clampHudScale(scale);
        save();
    }

    public synchronized void setAccentColorHex(String hex) {
        this.accentColorHex = normalizeHexColor(hex);
        save();
    }

    public synchronized int getAccentColorArgb() {
        return parseHexColor(this.accentColorHex, DEFAULT_ACCENT_COLOR_ARGB);
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

    public static float clampHudScale(float scale) {
        if (Float.isNaN(scale) || Float.isInfinite(scale)) {
            return DEFAULT_HUD_SCALE;
        }
        float clamped = Math.max(MIN_HUD_SCALE, Math.min(MAX_HUD_SCALE, scale));
        return Math.round(clamped * 20.0f) / 20.0f;
    }

    public static boolean isValidHexColor(String hex) {
        if (hex == null) {
            return false;
        }
        String cleaned = stripHexPrefix(hex.trim());
        return cleaned.matches("^[0-9A-Fa-f]{6}$");
    }

    public static String normalizeHexColor(String hex) {
        if (!isValidHexColor(hex)) {
            return DEFAULT_ACCENT_COLOR_HEX;
        }
        return "#" + stripHexPrefix(hex.trim()).toUpperCase();
    }

    public static int parseHexColor(String hex, int fallbackArgb) {
        if (!isValidHexColor(hex)) {
            return fallbackArgb;
        }
        String cleaned = stripHexPrefix(hex.trim());
        int rgb = Integer.parseInt(cleaned, 16);
        return 0xFF000000 | rgb;
    }

    private static String stripHexPrefix(String value) {
        if (value.startsWith("#")) {
            return value.substring(1);
        }
        if (value.startsWith("0x") || value.startsWith("0X")) {
            return value.substring(2);
        }
        return value;
    }

    private static String sanitizeString(String value) {
        return value == null ? "" : value;
    }

    private static boolean isValidPort(int port) {
        return port >= 1024 && port <= 65535;
    }
}
