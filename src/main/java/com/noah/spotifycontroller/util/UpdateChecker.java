package com.noah.spotifycontroller.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * Checks GitHub once per game session for a release newer than the running mod version.
 */
public final class UpdateChecker {
    private static final Logger LOGGER = LoggerFactory.getLogger("encore_music");
    public static final String RELEASES_URL = "https://github.com/theswag1/encore-music/releases";
    private static final String LATEST_RELEASE_API = "https://api.github.com/repos/theswag1/encore-music/releases/latest";
    private static final String VERSION_RESOURCE = "/assets/encore_music/version.txt";

    public record Release(String version, String url) {
    }

    private final String currentVersion;
    private volatile Release newerRelease;

    public UpdateChecker(String currentVersion) {
        this.currentVersion = currentVersion == null ? "" : currentVersion.trim();
    }

    /**
     * Reads the build-time mod version (expanded into {@code version.txt} by processResources), or "" if unknown.
     */
    public static String loadCurrentVersion() {
        try (InputStream stream = UpdateChecker.class.getResourceAsStream(VERSION_RESOURCE)) {
            if (stream == null) {
                return "";
            }
            String version = new String(stream.readAllBytes(), StandardCharsets.UTF_8).trim();
            if (version.contains("${")) {
                return "";
            }
            // The project version carries a loader/MC suffix ("0.7.0-fabric-mc26.3"); keep only the release number.
            int suffix = version.indexOf('-');
            return suffix > 0 ? version.substring(0, suffix) : version;
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * Fetches the latest GitHub release in the background. Failures are logged and otherwise ignored.
     */
    public void start(HttpClient httpClient) {
        if (currentVersion.isEmpty()) {
            return;
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(LATEST_RELEASE_API))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "encore-music-mod")
                .GET()
                .build();
        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (response.statusCode() != 200) {
                        LOGGER.debug("Update check returned HTTP {}", response.statusCode());
                        return;
                    }
                    parseLatestRelease(response.body())
                            .filter(release -> compareVersions(release.version(), currentVersion) > 0)
                            .ifPresent(release -> {
                                newerRelease = release;
                                LOGGER.info("Encore Music update available: {} (running {})", release.version(), currentVersion);
                            });
                })
                .exceptionally(ex -> {
                    LOGGER.debug("Update check failed", ex);
                    return null;
                });
    }

    public String getCurrentVersion() {
        return currentVersion;
    }

    /**
     * Returns the newer release found by {@link #start}, or {@code null} when up to date or unknown.
     */
    public Release getNewerRelease() {
        return newerRelease;
    }

    /**
     * Parses a GitHub {@code releases/latest} response. The link is only trusted when it points
     * at this repository's releases; otherwise the generic releases page is used.
     */
    public static Optional<Release> parseLatestRelease(String json) {
        try {
            JsonElement root = JsonParser.parseString(json == null ? "" : json);
            if (!root.isJsonObject()) {
                return Optional.empty();
            }
            JsonObject obj = root.getAsJsonObject();
            if (!obj.has("tag_name") || obj.get("tag_name").isJsonNull()) {
                return Optional.empty();
            }
            String tag = obj.get("tag_name").getAsString().trim();
            String url = obj.has("html_url") && !obj.get("html_url").isJsonNull()
                    ? obj.get("html_url").getAsString()
                    : "";
            if (!url.startsWith(RELEASES_URL + "/")) {
                url = RELEASES_URL;
            }
            return tag.isEmpty() ? Optional.empty() : Optional.of(new Release(tag, url));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * Compares dotted numeric versions ("v0.7", "0.7.0"). Positive when {@code a} is newer than {@code b}.
     * Returns 0 when either side cannot be parsed, so a malformed tag never triggers a notice.
     */
    public static int compareVersions(String a, String b) {
        int[] left = parseVersion(a);
        int[] right = parseVersion(b);
        if (left == null || right == null) {
            return 0;
        }
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            int l = i < left.length ? left[i] : 0;
            int r = i < right.length ? right[i] : 0;
            if (l != r) {
                return Integer.compare(l, r);
            }
        }
        return 0;
    }

    private static int[] parseVersion(String version) {
        if (version == null) {
            return null;
        }
        String cleaned = version.trim();
        if (cleaned.startsWith("v") || cleaned.startsWith("V")) {
            cleaned = cleaned.substring(1);
        }
        // Ignore pre-release / build suffixes such as "-beta" or "+build".
        int suffix = cleaned.indexOf('-');
        if (suffix < 0) {
            suffix = cleaned.indexOf('+');
        }
        if (suffix >= 0) {
            cleaned = cleaned.substring(0, suffix);
        }
        if (cleaned.isEmpty()) {
            return null;
        }
        String[] parts = cleaned.split("\\.");
        int[] numbers = new int[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) {
                numbers[i] = Integer.parseInt(parts[i]);
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return numbers;
    }
}
