package com.noah.spotifycontroller.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

/**
 * Persists Spotify Controller settings in {@code config/spotify_controller.json}
 * and OAuth PKCE tokens in {@code config/spotify_controller_tokens.json}.
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
    private transient Path tokenPath;

    public volatile String clientId = "";
    public volatile int callbackPort = DEFAULT_CALLBACK_PORT;
    public transient volatile String accessToken = "";
    public transient volatile String refreshToken = "";
    public transient volatile long tokenExpiresAtEpochSeconds = 0L;
    public volatile boolean hudEnabled = true;
    public volatile boolean hudCompact = false;
    public volatile boolean checkForUpdates = true;
    public volatile int hudX = DEFAULT_HUD_X;
    public volatile int hudY = DEFAULT_HUD_Y;
    public volatile float hudScale = DEFAULT_HUD_SCALE;
    public volatile String accentColorHex = DEFAULT_ACCENT_COLOR_HEX;
    public volatile int pollIntervalSeconds = DEFAULT_POLL_INTERVAL_SECONDS;

    private static final class StoredTokens {
        String accessToken = "";
        String refreshToken = "";
        long tokenExpiresAtEpochSeconds = 0L;
    }

    public SpotifyConfig() {
    }

    private SpotifyConfig(Path configPath) {
        this.configPath = configPath;
        this.tokenPath = resolveTokenPath(configPath);
    }

    /**
     * Returns the singleton config instance backed by the active mod loader's config directory.
     */
    public static SpotifyConfig get() {
        SpotifyConfig current = instance;
        if (current == null) {
            synchronized (SpotifyConfig.class) {
                current = instance;
                if (current == null) {
                    Path path = resolveDefaultConfigDir().resolve("spotify_controller.json");
                    current = loadFrom(path);
                    instance = current;
                }
            }
        }
        return current;
    }

    private static Path resolveDefaultConfigDir() {
        try {
            Class<?> fabricLoaderClass = Class.forName("net.fabricmc.loader.api.FabricLoader");
            Object loader = fabricLoaderClass.getMethod("getInstance").invoke(null);
            return (Path) fabricLoaderClass.getMethod("getConfigDir").invoke(loader);
        } catch (ReflectiveOperationException ignored) {
            // Not running on Fabric
        }
        try {
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object configDirEnum = Enum.valueOf(
                    (Class<? extends Enum>) Class.forName("net.neoforged.fml.loading.FMLPaths"),
                    "CONFIGDIR"
            );
            return (Path) configDirEnum.getClass().getMethod("get").invoke(configDirEnum);
        } catch (ReflectiveOperationException | IllegalArgumentException ignored) {
            // Not running on NeoForge
        }
        return Path.of("config");
    }

    /**
     * Loads or initializes a config instance at a specific path (useful for testing).
     */
    public static SpotifyConfig loadFrom(Path path) {
        SpotifyConfig config = new SpotifyConfig(path);
        config.load();
        return config;
    }

    /**
     * Resolves the companion OAuth token storage file path for a given config file path.
     */
    public static Path resolveTokenPath(Path configPath) {
        if (configPath == null) {
            return null;
        }
        String fileName = configPath.getFileName().toString();
        String tokenFileName = fileName.endsWith(".json")
                ? fileName.substring(0, fileName.length() - 5) + "_tokens.json"
                : fileName + "_tokens.json";
        return configPath.resolveSibling(tokenFileName);
    }

    public Path getTokenPath() {
        return tokenPath;
    }

    public synchronized void load() {
        if (configPath == null) {
            return;
        }
        if (Files.notExists(configPath)) {
            loadTokensFile();
            save();
            return;
        }

        boolean migratedLegacyTokens = false;
        try {
            String raw = Files.readString(configPath, StandardCharsets.UTF_8);
            SpotifyConfig loaded = GSON.fromJson(raw, SpotifyConfig.class);
            if (loaded != null) {
                this.clientId = sanitizeString(loaded.clientId).trim();
                this.callbackPort = isValidPort(loaded.callbackPort) ? loaded.callbackPort : DEFAULT_CALLBACK_PORT;
                this.hudEnabled = loaded.hudEnabled;
                this.hudCompact = loaded.hudCompact;
                this.checkForUpdates = loaded.checkForUpdates;
                this.hudX = loaded.hudX;
                this.hudY = loaded.hudY;
                this.hudScale = loaded.hudScale > 0.0f
                        ? clampHudScale(loaded.hudScale)
                        : DEFAULT_HUD_SCALE;
                this.accentColorHex = normalizeHexColor(loaded.accentColorHex);
                this.pollIntervalSeconds = loaded.pollIntervalSeconds >= 1
                        ? loaded.pollIntervalSeconds
                        : DEFAULT_POLL_INTERVAL_SECONDS;
            }

            // Migrate legacy tokens if they were stored inside the main config JSON
            JsonObject rawObj = JsonParser.parseString(raw).isJsonObject()
                    ? JsonParser.parseString(raw).getAsJsonObject()
                    : null;
            if (rawObj != null && (rawObj.has("accessToken") || rawObj.has("refreshToken"))) {
                this.accessToken = getJsonString(rawObj, "accessToken");
                this.refreshToken = getJsonString(rawObj, "refreshToken");
                this.tokenExpiresAtEpochSeconds = Math.max(0L, getJsonLong(rawObj, "tokenExpiresAtEpochSeconds"));
                migratedLegacyTokens = true;
            }
        } catch (IOException | JsonSyntaxException | IllegalStateException e) {
            LOGGER.warn("Failed to read Spotify Controller config at {}, keeping current defaults", configPath, e);
        }

        loadTokensFile();
        if (migratedLegacyTokens) {
            save();
        }
    }

    private void loadTokensFile() {
        if (tokenPath == null || Files.notExists(tokenPath)) {
            return;
        }
        try {
            String rawTokens = Files.readString(tokenPath, StandardCharsets.UTF_8);
            StoredTokens tokens = GSON.fromJson(rawTokens, StoredTokens.class);
            if (tokens != null) {
                this.accessToken = sanitizeString(tokens.accessToken).trim();
                this.refreshToken = sanitizeString(tokens.refreshToken).trim();
                this.tokenExpiresAtEpochSeconds = Math.max(0L, tokens.tokenExpiresAtEpochSeconds);
            }
        } catch (IOException | JsonSyntaxException e) {
            LOGGER.warn("Failed to read Spotify token file at {}", tokenPath, e);
        }
    }

    public synchronized void save() {
        if (configPath == null) {
            return;
        }
        try {
            this.clientId = sanitizeString(this.clientId).trim();
            this.callbackPort = isValidPort(this.callbackPort) ? this.callbackPort : DEFAULT_CALLBACK_PORT;
            this.hudScale = clampHudScale(this.hudScale);
            this.accentColorHex = normalizeHexColor(this.accentColorHex);
            this.pollIntervalSeconds = Math.max(1, this.pollIntervalSeconds);
            writeFileAtomically(configPath, GSON.toJson(this), false);

            if (tokenPath != null) {
                StoredTokens tokens = new StoredTokens();
                tokens.accessToken = sanitizeString(this.accessToken).trim();
                tokens.refreshToken = sanitizeString(this.refreshToken).trim();
                tokens.tokenExpiresAtEpochSeconds = Math.max(0L, this.tokenExpiresAtEpochSeconds);
                writeFileAtomically(tokenPath, GSON.toJson(tokens), true);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to save Spotify Controller config to {}", configPath, e);
        }
    }

    private static void writeFileAtomically(Path target, String content, boolean restrictPermissions) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tempFile = target.resolveSibling(target.getFileName().toString() + ".tmp");
        Files.writeString(tempFile, content, StandardCharsets.UTF_8);
        if (restrictPermissions) {
            try {
                Set<PosixFilePermission> perms = EnumSet.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE
                );
                Files.setPosixFilePermissions(tempFile, perms);
            } catch (UnsupportedOperationException | IOException ignored) {
                // Non-POSIX filesystems (e.g. Windows NTFS) do not support POSIX permissions
            }
        }
        try {
            Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public synchronized void setClientId(String clientId) {
        String normalized = sanitizeString(clientId).trim();
        String previous = sanitizeString(this.clientId).trim();
        this.clientId = normalized;
        if (!previous.isEmpty() && !previous.equals(normalized)) {
            this.accessToken = "";
            this.refreshToken = "";
            this.tokenExpiresAtEpochSeconds = 0L;
        }
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

    public synchronized void setPollIntervalSeconds(int seconds) {
        this.pollIntervalSeconds = Math.max(1, seconds);
        save();
    }

    public int getAccentColorArgb() {
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

    public boolean hasClientId() {
        String id = this.clientId;
        return id != null && !id.isBlank();
    }

    public boolean hasValidAccessToken() {
        String token = this.accessToken;
        return token != null
                && !token.isBlank()
                && Instant.now().getEpochSecond() < this.tokenExpiresAtEpochSeconds - 30;
    }

    public boolean hasRefreshToken() {
        String token = this.refreshToken;
        return token != null && !token.isBlank();
    }

    public String getRedirectUri() {
        int port = this.callbackPort;
        int validPort = isValidPort(port) ? port : DEFAULT_CALLBACK_PORT;
        return "http://127.0.0.1:" + validPort + "/callback";
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

    private static String getJsonString(JsonObject obj, String key) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsString().trim();
        }
        return "";
    }

    private static long getJsonLong(JsonObject obj, String key) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            try {
                return obj.get(key).getAsLong();
            } catch (NumberFormatException | UnsupportedOperationException ignored) {
            }
        }
        return 0L;
    }

    private static boolean isValidPort(int port) {
        return port >= 1024 && port <= 65535;
    }
}
