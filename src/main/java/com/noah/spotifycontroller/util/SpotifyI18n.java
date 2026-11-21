package com.noah.spotifycontroller.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.locale.Language;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.IllegalFormatException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Resolves localized mod strings using Minecraft's active {@link Language},
 * falling back to the bundled {@code en_us.json} resource for headless contexts or missing keys.
 */
public final class SpotifyI18n {
    private static final Logger LOGGER = LoggerFactory.getLogger("encore_music");
    private static final String EN_US_RESOURCE_PATH = "/assets/encore_music/lang/en_us.json";
    private static final Map<String, String> FALLBACK_EN_US = loadFallbackTranslations();

    private SpotifyI18n() {
    }

    /**
     * Translates {@code key} against the active language (falling back to {@code en_us.json})
     * and formats positional placeholders when {@code args} are provided.
     */
    public static String translate(String key, Object... args) {
        if (key == null) {
            return "";
        }
        String pattern = resolvePattern(key);
        if (args == null || args.length == 0) {
            return pattern;
        }
        try {
            return String.format(Locale.ROOT, pattern, args);
        } catch (IllegalFormatException e) {
            return pattern;
        }
    }

    /**
     * Returns an unmodifiable view of all translations loaded from {@code en_us.json}.
     */
    public static Map<String, String> getFallbackTranslations() {
        return FALLBACK_EN_US;
    }

    private static String resolvePattern(String key) {
        try {
            Language language = Language.getInstance();
            if (language != null && language.has(key)) {
                return language.getOrDefault(key);
            }
        } catch (LinkageError | RuntimeException ignored) {
            // Fall back to bundled en_us.json if Language is unavailable in headless environments
        }
        return FALLBACK_EN_US.getOrDefault(key, key);
    }

    private static Map<String, String> loadFallbackTranslations() {
        try (InputStream stream = SpotifyI18n.class.getResourceAsStream(EN_US_RESOURCE_PATH)) {
            if (stream == null) {
                LOGGER.warn("Could not find bundled translation resource at {}", EN_US_RESOURCE_PATH);
                return Collections.emptyMap();
            }
            try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                JsonElement root = JsonParser.parseReader(reader);
                if (!root.isJsonObject()) {
                    return Collections.emptyMap();
                }
                JsonObject jsonObject = root.getAsJsonObject();
                Map<String, String> entries = new LinkedHashMap<>();
                for (Map.Entry<String, JsonElement> entry : jsonObject.entrySet()) {
                    if (entry.getValue() != null && entry.getValue().isJsonPrimitive()) {
                        entries.put(entry.getKey(), entry.getValue().getAsString());
                    }
                }
                return Collections.unmodifiableMap(entries);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Failed to load fallback translations from {}", EN_US_RESOURCE_PATH, e);
            return Collections.emptyMap();
        }
    }
}
