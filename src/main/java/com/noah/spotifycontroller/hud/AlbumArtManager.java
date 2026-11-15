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

/**
 * Downloads Spotify album cover images asynchronously and registers them as a
 * Minecraft {@link DynamicTexture} on the render thread.
 */
public class AlbumArtManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("spotify_controller");
    public static final Identifier ALBUM_ART_ID = Identifier.fromNamespaceAndPath("spotify_controller", "album_art");

    private final HttpClient httpClient;

    private volatile String desiredUrl = "";
    private volatile String loadedUrl = "";
    private volatile String inFlightUrl = "";
    private volatile boolean textureRegistered = false;

    public AlbumArtManager(HttpClient httpClient) {
        this.httpClient = httpClient;
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

        if (!albumArtUrl.equals(inFlightUrl)) {
            startDownload(albumArtUrl);
        }

        return textureRegistered ? ALBUM_ART_ID : null;
    }

    public void clear() {
        this.desiredUrl = "";
        this.loadedUrl = "";
        this.inFlightUrl = "";
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
                clearInFlightIfMatches(url);
                return;
            }
            request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
        } catch (IllegalArgumentException e) {
            LOGGER.debug("Invalid album art URL: {}", url, e);
            clearInFlightIfMatches(url);
            return;
        }

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                .thenAccept(response -> {
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        LOGGER.debug("Album art download returned HTTP {} for {}", response.statusCode(), url);
                        clearInFlightIfMatches(url);
                        return;
                    }

                    NativeImage nativeImage;
                    try {
                        BufferedImage decoded = decodeImageBytes(response.body());
                        if (decoded == null) {
                            LOGGER.debug("Unsupported or empty album art image from {}", url);
                            clearInFlightIfMatches(url);
                            return;
                        }
                        nativeImage = toNativeImage(decoded);
                    } catch (IOException e) {
                        LOGGER.debug("Failed to decode album art from {}", url, e);
                        clearInFlightIfMatches(url);
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
                        this.textureRegistered = true;
                        clearInFlightIfMatches(url);
                    });
                })
                .exceptionally(ex -> {
                    LOGGER.debug("Album art download failed for {}", url, ex);
                    clearInFlightIfMatches(url);
                    return null;
                });
    }

    private void clearInFlightIfMatches(String url) {
        if (url.equals(this.inFlightUrl)) {
            this.inFlightUrl = "";
        }
    }
}
