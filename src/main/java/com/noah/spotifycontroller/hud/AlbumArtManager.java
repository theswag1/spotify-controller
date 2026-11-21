package com.noah.spotifycontroller.hud;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Downloads Spotify album cover images asynchronously and registers them as a
 * Minecraft {@link DynamicTexture} on the render thread.
 */
public class AlbumArtManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("encore_music");
    public static final Identifier ALBUM_ART_ID = Identifier.fromNamespaceAndPath("encore_music", "album_art");
    public static final long DEFAULT_RETRY_COOLDOWN_MS = 30_000L;
    private static final int MAX_FAILED_CACHE_SIZE = 64;

    private final HttpClient httpClient;
    private final long retryCooldownMs;
    private final LongSupplier clockMs;
    private final ConcurrentHashMap<String, Long> failedUntilMs = new ConcurrentHashMap<>();

    private volatile String desiredUrl = "";
    private volatile String loadedUrl = "";
    private volatile String inFlightUrl = "";
    private volatile boolean textureRegistered = false;

    public AlbumArtManager(HttpClient httpClient) {
        this(httpClient, DEFAULT_RETRY_COOLDOWN_MS, System::currentTimeMillis);
    }

    public AlbumArtManager(HttpClient httpClient, long retryCooldownMs, LongSupplier clockMs) {
        this.httpClient = httpClient;
        this.retryCooldownMs = Math.max(0L, retryCooldownMs);
        this.clockMs = clockMs;
    }

    /**
     * Decodes JPEG/PNG image bytes into a {@link BufferedImage}.
     */
    public static BufferedImage decodeImageBytes(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }

    /**
     * Converts a {@link BufferedImage} into an opaque {@link NativeImage} using ARGB pixels.
     */
    public static NativeImage toNativeImage(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        NativeImage nativeImage = new NativeImage(width, height, false);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                nativeImage.setPixel(x, y, image.getRGB(x, y) | 0xFF000000);
            }
        }
        return nativeImage;
    }

    /**
     * Returns the texture {@link Identifier} to draw for {@code albumArtUrl},
     * triggering an asynchronous download when the URL changes.
     */
    public Identifier getOrLoadTexture(String albumArtUrl) {
        if (albumArtUrl == null || albumArtUrl.isBlank()) {
            return null;
        }

        this.desiredUrl = albumArtUrl;
        if (albumArtUrl.equals(loadedUrl) && textureRegistered) {
            return ALBUM_ART_ID;
        }

        if (!albumArtUrl.equals(inFlightUrl) && !isInFailureCooldown(albumArtUrl)) {
            startDownload(albumArtUrl);
        }

        // Return null so callers show the ♪ placeholder until the requested URL finishes loading.
        return null;
    }

    public boolean isInFailureCooldown(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        Long retryAtMs = failedUntilMs.get(url);
        if (retryAtMs == null) {
            return false;
        }
        if (clockMs.getAsLong() < retryAtMs) {
            return true;
        }
        failedUntilMs.remove(url, retryAtMs);
        return false;
    }

    public void clear() {
        this.desiredUrl = "";
        this.loadedUrl = "";
        this.inFlightUrl = "";
        this.failedUntilMs.clear();
        boolean wasRegistered = this.textureRegistered;
        this.textureRegistered = false;

        if (wasRegistered) {
            Minecraft client = Minecraft.getInstance();
            if (client != null) {
                if (client.isSameThread()) {
                    client.getTextureManager().release(ALBUM_ART_ID);
                } else {
                    client.execute(() -> client.getTextureManager().release(ALBUM_ART_ID));
                }
            }
        }
    }

    private void startDownload(String url) {
        this.inFlightUrl = url;
        HttpRequest request;
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            if (scheme == null || (!scheme.equalsIgnoreCase("https") && !scheme.equalsIgnoreCase("http"))) {
                LOGGER.debug("Refusing non-HTTP(S) album art URL: {}", url);
                markDownloadFailed(url);
                return;
            }
            request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
        } catch (IllegalArgumentException e) {
            LOGGER.debug("Invalid album art URL: {}", url, e);
            markDownloadFailed(url);
            return;
        }

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                .thenAccept(response -> {
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        LOGGER.debug("Album art download returned HTTP {} for {}", response.statusCode(), url);
                        markDownloadFailed(url);
                        return;
                    }

                    NativeImage nativeImage;
                    try {
                        BufferedImage decoded = decodeImageBytes(response.body());
                        if (decoded == null) {
                            LOGGER.debug("Unsupported or empty album art image from {}", url);
                            markDownloadFailed(url);
                            return;
                        }
                        nativeImage = toNativeImage(decoded);
                    } catch (IOException e) {
                        LOGGER.debug("Failed to decode album art from {}", url, e);
                        markDownloadFailed(url);
                        return;
                    }

                    Minecraft client = Minecraft.getInstance();
                    if (client == null) {
                        nativeImage.close();
                        clearInFlightIfMatches(url);
                        return;
                    }
                    client.execute(() -> {
                        if (!url.equals(this.desiredUrl)) {
                            nativeImage.close();
                            clearInFlightIfMatches(url);
                            return;
                        }

                        DynamicTexture texture = new DynamicTexture(() -> "spotify_album_art", nativeImage);
                        client.getTextureManager().register(ALBUM_ART_ID, texture);
                        this.loadedUrl = url;
                        this.failedUntilMs.remove(url);
                        this.textureRegistered = true;
                        clearInFlightIfMatches(url);
                    });
                })
                .exceptionally(ex -> {
                    LOGGER.debug("Album art download failed for {}", url, ex);
                    markDownloadFailed(url);
                    return null;
                });
    }

    private void markDownloadFailed(String url) {
        if (retryCooldownMs > 0L) {
            long now = clockMs.getAsLong();
            if (failedUntilMs.size() >= MAX_FAILED_CACHE_SIZE) {
                failedUntilMs.entrySet().removeIf(entry -> entry.getValue() <= now);
            }
            // Record the cooldown before clearing inFlightUrl so render-thread callers cannot start an immediate duplicate download.
            failedUntilMs.put(url, now + retryCooldownMs);
        }
        clearInFlightIfMatches(url);
    }

    private void clearInFlightIfMatches(String url) {
        if (url.equals(this.inFlightUrl)) {
            this.inFlightUrl = "";
        }
    }
}
