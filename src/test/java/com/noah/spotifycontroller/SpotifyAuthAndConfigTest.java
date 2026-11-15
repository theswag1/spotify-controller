package com.noah.spotifycontroller;

import com.noah.spotifycontroller.api.TrackState;
import com.noah.spotifycontroller.auth.SpotifyAuthService;
import com.noah.spotifycontroller.config.SpotifyConfig;
import com.noah.spotifycontroller.hud.AlbumArtManager;
import com.noah.spotifycontroller.hud.SpotifyHudOverlay;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpotifyAuthAndConfigTest {

    @Test
    void computesRfc7636PkceCodeChallengeVector() {
        // RFC 7636 Appendix B test vector
        String verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
        String expectedChallenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";

        assertEquals(expectedChallenge, SpotifyAuthService.computeCodeChallenge(verifier));
    }

    @Test
    void generatesValidPkceCodeVerifier() {
        String verifier = SpotifyAuthService.generateCodeVerifier();
        assertEquals(64, verifier.length());
        assertTrue(verifier.matches("^[A-Za-z0-9\\-._~]+$"));
    }

    @Test
    void savesAndReloadsConfigWithTrimmedClientIdAndTokens(@TempDir Path tempDir) {
        Path configPath = tempDir.resolve("spotify_controller.json");

        SpotifyConfig created = SpotifyConfig.loadFrom(configPath);
        assertFalse(created.hasClientId());
        assertEquals("http://127.0.0.1:8888/callback", created.getRedirectUri());

        created.setClientId("  abc123clientid  ");
        created.updateTokens("access-token-xyz", "refresh-token-123", 3600);

        SpotifyConfig reloaded = SpotifyConfig.loadFrom(configPath);
        assertEquals("abc123clientid", reloaded.clientId);
        assertTrue(reloaded.hasClientId());
        assertTrue(reloaded.hasValidAccessToken());
        assertTrue(reloaded.hasRefreshToken());
        assertEquals("access-token-xyz", reloaded.accessToken);
        assertEquals("refresh-token-123", reloaded.refreshToken);

        reloaded.clearTokens();
        SpotifyConfig cleared = SpotifyConfig.loadFrom(configPath);
        assertFalse(cleared.hasValidAccessToken());
        assertFalse(cleared.hasRefreshToken());
        assertEquals("abc123clientid", cleared.clientId);
    }

    @Test
    void parsesSpotifyPlayerPayloadAndFormatsDuration() {
        String payload = """
                {
                  "device": {
                    "id": "device-1",
                    "is_active": true,
                    "volume_percent": 75
                  },
                  "shuffle_state": true,
                  "repeat_state": "context",
                  "progress_ms": 65000,
                  "is_playing": false,
                  "item": {
                    "id": "track-42",
                    "name": "Sweden",
                    "duration_ms": 215000,
                    "artists": [
                      { "name": "C418" }
                    ],
                    "album": {
                      "name": "Minecraft - Volume Alpha",
                      "images": [
                        { "url": "https://i.scdn.co/image/640", "width": 640, "height": 640 },
                        { "url": "https://i.scdn.co/image/300", "width": 300, "height": 300 },
                        { "url": "https://i.scdn.co/image/64", "width": 64, "height": 64 }
                      ]
                    }
                  }
                }
                """;

        Optional<TrackState> parsed = TrackState.fromPlayerJson(payload);
        assertTrue(parsed.isPresent());
        TrackState track = parsed.get();
        assertEquals("track-42", track.trackId());
        assertEquals("Sweden", track.trackName());
        assertEquals("C418", track.artistName());
        assertEquals("Minecraft - Volume Alpha", track.albumName());
        assertEquals("https://i.scdn.co/image/300", track.albumArtUrl());
        assertEquals(65000L, track.progressMs());
        assertEquals(215000L, track.durationMs());
        assertFalse(track.isPlaying());
        assertEquals(75, track.volumePercent());
        assertTrue(track.shuffleState());
        assertEquals("context", track.repeatState());
        assertEquals("1:05", TrackState.formatDuration(track.getInterpolatedProgressMs()));
        assertEquals("3:35", TrackState.formatDuration(track.durationMs()));
    }

    @Test
    void decodesPngAndJpegAlbumArtBytes() throws IOException {
        BufferedImage sample = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                sample.setRGB(x, y, new Color(29, 185, 84).getRGB());
            }
        }

        for (String format : new String[]{"png", "jpg"}) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            assertTrue(ImageIO.write(sample, format, out));

            BufferedImage decoded = AlbumArtManager.decodeImageBytes(out.toByteArray());
            assertNotNull(decoded, "Failed to decode " + format + " image bytes");
            assertEquals(8, decoded.getWidth());
            assertEquals(8, decoded.getHeight());
        }
    }

    @Test
    void computesMarqueeScrollOffsetsCorrectly() {
        assertEquals(0, SpotifyHudOverlay.computeScrollOffset(100, 136, 5000L));
        assertEquals(0, SpotifyHudOverlay.computeScrollOffset(180, 136, 0L));

        int midOffset = SpotifyHudOverlay.computeScrollOffset(184, 136, 2500L);
        assertEquals(24, midOffset);
        assertEquals(48, SpotifyHudOverlay.computeScrollOffset(184, 136, 4000L));
    }

    @Test
    void clampsAndPersistsHudPositionAndHitTestsCardBounds(@TempDir Path tempDir) {
        assertEquals(2, SpotifyHudOverlay.clampX(-50, 800));
        assertEquals(800 - SpotifyHudOverlay.CARD_WIDTH - 2, SpotifyHudOverlay.clampX(2000, 800));
        assertEquals(2, SpotifyHudOverlay.clampY(-10, 600));
        assertEquals(600 - SpotifyHudOverlay.CARD_HEIGHT - 2, SpotifyHudOverlay.clampY(2000, 600));

        assertTrue(SpotifyHudOverlay.isPointInsideCard(10, 20, 10, 20));
        assertTrue(SpotifyHudOverlay.isPointInsideCard(10 + SpotifyHudOverlay.CARD_WIDTH - 1, 20 + SpotifyHudOverlay.CARD_HEIGHT - 1, 10, 20));
        assertFalse(SpotifyHudOverlay.isPointInsideCard(9, 20, 10, 20));
        assertFalse(SpotifyHudOverlay.isPointInsideCard(10 + SpotifyHudOverlay.CARD_WIDTH, 20, 10, 20));
        assertFalse(SpotifyHudOverlay.isPointInsideCard(10, 20 + SpotifyHudOverlay.CARD_HEIGHT, 10, 20));

        Path configPath = tempDir.resolve("hud_position.json");
        SpotifyConfig config = SpotifyConfig.loadFrom(configPath);
        assertEquals(SpotifyConfig.DEFAULT_HUD_X, config.hudX);
        assertEquals(SpotifyConfig.DEFAULT_HUD_Y, config.hudY);

        config.setHudPosition(240, 135);
        SpotifyConfig reloaded = SpotifyConfig.loadFrom(configPath);
        assertEquals(240, reloaded.hudX);
        assertEquals(135, reloaded.hudY);

        reloaded.resetHudPosition();
        SpotifyConfig resetReloaded = SpotifyConfig.loadFrom(configPath);
        assertEquals(SpotifyConfig.DEFAULT_HUD_X, resetReloaded.hudX);
        assertEquals(SpotifyConfig.DEFAULT_HUD_Y, resetReloaded.hudY);
    }
}
