package com.noah.spotifycontroller;

import com.noah.spotifycontroller.api.SpotifyApiClient;
import com.noah.spotifycontroller.api.SpotifyPoller;
import com.noah.spotifycontroller.api.TrackState;
import com.noah.spotifycontroller.auth.SpotifyAuthService;
import com.noah.spotifycontroller.config.SpotifyConfig;
import com.noah.spotifycontroller.gui.SpotifySetupGuideScreen;
import com.noah.spotifycontroller.hud.AlbumArtManager;
import com.noah.spotifycontroller.hud.SpotifyHudOverlay;
import com.noah.spotifycontroller.util.SpotifyI18n;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void savesAndReloadsConfigWithTrimmedClientIdAndTokens(@TempDir Path tempDir) throws IOException {
        Path configPath = tempDir.resolve("spotify_controller.json");

        SpotifyConfig created = SpotifyConfig.loadFrom(configPath);
        assertFalse(created.hasClientId());
        assertEquals("http://127.0.0.1:8888/callback", created.getRedirectUri());

        created.setClientId("  abc123clientid  ");
        created.updateTokens("access-token-xyz", "refresh-token-123", 3600);

        // Main config JSON should not contain raw OAuth tokens
        String mainConfigJson = Files.readString(configPath, StandardCharsets.UTF_8);
        assertFalse(mainConfigJson.contains("access-token-xyz"));
        assertFalse(mainConfigJson.contains("refresh-token-123"));
        assertTrue(Files.exists(created.getTokenPath()));

        SpotifyConfig reloaded = SpotifyConfig.loadFrom(configPath);
        assertEquals("abc123clientid", reloaded.clientId);
        assertTrue(reloaded.hasClientId());
        assertTrue(reloaded.hasValidAccessToken());
        assertTrue(reloaded.hasRefreshToken());
        assertEquals("access-token-xyz", reloaded.accessToken);
        assertEquals("refresh-token-123", reloaded.refreshToken);

        // Changing to a different Client ID must invalidate tokens from the old Client ID
        reloaded.setClientId("different-client-id");
        assertFalse(reloaded.hasValidAccessToken());
        assertFalse(reloaded.hasRefreshToken());

        SpotifyConfig cleared = SpotifyConfig.loadFrom(configPath);
        assertFalse(cleared.hasValidAccessToken());
        assertFalse(cleared.hasRefreshToken());
        assertEquals("different-client-id", cleared.clientId);
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

    @Test
    void computesControllerRepeatVolumeAndSeekHelpers() {
        assertEquals("context", SpotifyApiClient.nextRepeatState("off"));
        assertEquals("track", SpotifyApiClient.nextRepeatState("context"));
        assertEquals("off", SpotifyApiClient.nextRepeatState("track"));
        assertEquals("context", SpotifyApiClient.nextRepeatState(null));

        assertEquals("OFF", SpotifyApiClient.formatRepeatLabel("off"));
        assertEquals("ALL", SpotifyApiClient.formatRepeatLabel("context"));
        assertEquals("ONE", SpotifyApiClient.formatRepeatLabel("track"));

        assertEquals(0, SpotifyApiClient.clampVolume(-15));
        assertEquals(65, SpotifyApiClient.clampVolume(65));
        assertEquals(100, SpotifyApiClient.clampVolume(150));

        assertEquals(0, SpotifyApiClient.computeVolumePercent(50, 100, 200));
        assertEquals(50, SpotifyApiClient.computeVolumePercent(200, 100, 200));
        assertEquals(100, SpotifyApiClient.computeVolumePercent(350, 100, 200));

        assertEquals(0L, SpotifyApiClient.computeSeekPositionMs(80, 100, 200, 200_000L));
        assertEquals(100_000L, SpotifyApiClient.computeSeekPositionMs(200, 100, 200, 200_000L));
        assertEquals(200_000L, SpotifyApiClient.computeSeekPositionMs(320, 100, 200, 200_000L));
    }

    @Test
    void parsesAndPersistsAccentColorAndScaledHudBounds(@TempDir Path tempDir) {
        assertEquals(0xFFFF4081, SpotifyConfig.parseHexColor("ff4081", SpotifyConfig.DEFAULT_ACCENT_COLOR_ARGB));
        assertEquals(0xFF00E5FF, SpotifyConfig.parseHexColor("#00e5ff", SpotifyConfig.DEFAULT_ACCENT_COLOR_ARGB));
        assertEquals(SpotifyConfig.DEFAULT_ACCENT_COLOR_ARGB, SpotifyConfig.parseHexColor("not-a-hex", SpotifyConfig.DEFAULT_ACCENT_COLOR_ARGB));
        assertEquals("#00E5FF", SpotifyConfig.normalizeHexColor(" 00e5ff "));
        assertEquals(SpotifyConfig.DEFAULT_ACCENT_COLOR_HEX, SpotifyConfig.normalizeHexColor("#12"));

        assertEquals(0.5f, SpotifyConfig.clampHudScale(0.1f), 0.001f);
        assertEquals(1.25f, SpotifyConfig.clampHudScale(1.24f), 0.001f);
        assertEquals(2.0f, SpotifyConfig.clampHudScale(5.0f), 0.001f);

        assertEquals(285, SpotifyHudOverlay.getScaledWidth(1.5f));
        assertEquals(69, SpotifyHudOverlay.getScaledHeight(1.5f));
        assertEquals(800 - 285 - 2, SpotifyHudOverlay.clampX(750, 800, 1.5f));
        assertEquals(600 - 69 - 2, SpotifyHudOverlay.clampY(580, 600, 1.5f));
        assertTrue(SpotifyHudOverlay.isPointInsideCard(280, 75, 10, 10, 1.5f));
        assertFalse(SpotifyHudOverlay.isPointInsideCard(280, 75, 10, 10, 1.0f));

        Path configPath = tempDir.resolve("hud_appearance.json");
        SpotifyConfig config = SpotifyConfig.loadFrom(configPath);
        assertEquals(SpotifyConfig.DEFAULT_HUD_SCALE, config.hudScale, 0.001f);
        assertEquals(SpotifyConfig.DEFAULT_ACCENT_COLOR_HEX, config.accentColorHex);

        config.setHudScale(1.5f);
        config.setAccentColorHex("bb86fc");
        SpotifyConfig reloaded = SpotifyConfig.loadFrom(configPath);
        assertEquals(1.5f, reloaded.hudScale, 0.001f);
        assertEquals("#BB86FC", reloaded.accentColorHex);
        assertEquals(0xFFBB86FC, reloaded.getAccentColorArgb());

        reloaded.resetHudPosition();
        assertEquals(SpotifyConfig.DEFAULT_HUD_SCALE, reloaded.hudScale, 0.001f);
        assertEquals("#BB86FC", reloaded.accentColorHex);
    }

    @Test
    void clampsSetupWizardStepBounds() {
        assertEquals(1, SpotifySetupGuideScreen.clampStep(0));
        assertEquals(1, SpotifySetupGuideScreen.clampStep(-5));
        assertEquals(1, SpotifySetupGuideScreen.clampStep(1));
        assertEquals(2, SpotifySetupGuideScreen.clampStep(2));
        assertEquals(3, SpotifySetupGuideScreen.clampStep(3));
        assertEquals(3, SpotifySetupGuideScreen.clampStep(4));
        assertEquals(3, SpotifySetupGuideScreen.clampStep(99));
    }

    @Test
    void parsesPodcastEpisodePayload() {
        String episodePayload = """
                {
                  "progress_ms": 120000,
                  "is_playing": true,
                  "currently_playing_type": "episode",
                  "item": {
                    "id": "ep-99",
                    "name": "Deep Dive into Redstone",
                    "duration_ms": 1800000,
                    "images": [
                      { "url": "https://i.scdn.co/image/ep300", "width": 300, "height": 300 }
                    ],
                    "show": {
                      "name": "Block Talk",
                      "publisher": "Minecraft Audio Network"
                    }
                  }
                }
                """;

        Optional<TrackState> parsed = TrackState.fromPlayerJson(episodePayload);
        assertTrue(parsed.isPresent());
        TrackState episode = parsed.get();
        assertEquals("ep-99", episode.trackId());
        assertEquals("Deep Dive into Redstone", episode.trackName());
        assertEquals("Minecraft Audio Network", episode.artistName());
        assertEquals("https://i.scdn.co/image/ep300", episode.albumArtUrl());
        assertTrue(episode.isPlaying());
    }

    @Test
    void callbackServerAcceptsExplicitIpv4Connections(@TempDir Path tempDir) throws Exception {
        int freePort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByAddress(new byte[]{127, 0, 0, 1}))) {
            freePort = socket.getLocalPort();
        }

        Path configPath = tempDir.resolve("auth_callback_test.json");
        SpotifyConfig config = SpotifyConfig.loadFrom(configPath);
        config.callbackPort = freePort;
        config.setClientId("test-client-id");

        HttpClient client = HttpClient.newHttpClient();
        SpotifyAuthService authService = new SpotifyAuthService(config, client, uri -> {});
        try {
            CompletableFuture<Boolean> authFuture = authService.startAuthFlow(msg -> {});
            assertFalse(authFuture.isDone(), "Auth flow should be waiting for browser callback");

            HttpRequest request = HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + freePort + "/callback?error=access_denied"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(400, response.statusCode());
            assertTrue(response.body().contains("access_denied"));
            assertFalse(authFuture.get(5, TimeUnit.SECONDS));
        } finally {
            authService.shutdown();
        }
    }

    @Test
    void throttlesRepeatedAlbumArtDownloadsAfterFailure() throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 0), 0);
        server.createContext("/broken.png", exchange -> {
            requestCount.incrementAndGet();
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();

        try {
            AtomicLong fakeNowMs = new AtomicLong(10_000L);
            AlbumArtManager manager = new AlbumArtManager(
                    HttpClient.newHttpClient(),
                    AlbumArtManager.DEFAULT_RETRY_COOLDOWN_MS,
                    fakeNowMs::get
            );
            String brokenUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/broken.png";

            assertNull(manager.getOrLoadTexture(brokenUrl));

            long deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!manager.isInFailureCooldown(brokenUrl) && System.nanoTime() < deadlineNs) {
                Thread.sleep(5);
            }
            assertTrue(manager.isInFailureCooldown(brokenUrl), "Failed URL should enter retry cooldown");

            for (int frame = 0; frame < 60; frame++) {
                assertNull(manager.getOrLoadTexture(brokenUrl));
            }
            assertEquals(1, requestCount.get(), "Failed URL should not be re-requested every frame during cooldown");

            fakeNowMs.addAndGet(AlbumArtManager.DEFAULT_RETRY_COOLDOWN_MS);
            assertFalse(manager.isInFailureCooldown(brokenUrl));
            assertNull(manager.getOrLoadTexture(brokenUrl));

            long retryDeadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!manager.isInFailureCooldown(brokenUrl) && System.nanoTime() < retryDeadlineNs) {
                Thread.sleep(5);
            }
            assertEquals(2, requestCount.get(), "Failed URL should retry once after cooldown expires");

            String invalidSchemeUrl = "ftp://invalid.local/art.png";
            assertNull(manager.getOrLoadTexture(invalidSchemeUrl));
            assertTrue(manager.isInFailureCooldown(invalidSchemeUrl));

            manager.clear();
            assertFalse(manager.isInFailureCooldown(brokenUrl));
            assertFalse(manager.isInFailureCooldown(invalidSchemeUrl));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void computesAdaptiveAndDynamicPollIntervals(@TempDir Path tempDir) {
        TrackState playingTrack = new TrackState(
                "track-1", "Sweden", "C418", "Minecraft - Volume Alpha", "",
                10_000L, 200_000L, true, 80, false, "off", System.currentTimeMillis()
        );
        TrackState pausedTrack = new TrackState(
                "track-1", "Sweden", "C418", "Minecraft - Volume Alpha", "",
                10_000L, 200_000L, false, 80, false, "off", System.currentTimeMillis()
        );

        assertEquals(3, SpotifyPoller.computeEffectivePollIntervalSeconds(3, playingTrack, true, true, false));
        assertEquals(SpotifyPoller.IDLE_PAUSED_POLL_SECONDS,
                SpotifyPoller.computeEffectivePollIntervalSeconds(3, pausedTrack, true, true, false));
        assertEquals(SpotifyPoller.IDLE_NO_PLAYBACK_POLL_SECONDS,
                SpotifyPoller.computeEffectivePollIntervalSeconds(3, null, true, true, false));
        assertEquals(SpotifyPoller.IDLE_MENU_POLL_SECONDS,
                SpotifyPoller.computeEffectivePollIntervalSeconds(3, playingTrack, false, true, false));
        assertEquals(3,
                SpotifyPoller.computeEffectivePollIntervalSeconds(3, playingTrack, false, true, true));
        assertEquals(SpotifyPoller.IDLE_UNFOCUSED_POLL_SECONDS,
                SpotifyPoller.computeEffectivePollIntervalSeconds(3, playingTrack, true, false, true));
        assertEquals(15,
                SpotifyPoller.computeEffectivePollIntervalSeconds(15, pausedTrack, false, false, false));

        Path configPath = tempDir.resolve("dynamic_poll.json");
        SpotifyConfig config = SpotifyConfig.loadFrom(configPath);
        HttpClient httpClient = HttpClient.newHttpClient();
        SpotifyAuthService authService = new SpotifyAuthService(config, httpClient, uri -> {});
        SpotifyPoller poller = new SpotifyPoller(config, new SpotifyApiClient(authService, httpClient));
        try {
            assertEquals(SpotifyPoller.IDLE_NO_PLAYBACK_POLL_SECONDS, poller.getEffectivePollIntervalSeconds());

            config.setPollIntervalSeconds(12);
            assertEquals(12, poller.getEffectivePollIntervalSeconds());

            config.setPollIntervalSeconds(15);
            assertEquals(15, poller.getEffectivePollIntervalSeconds());
        } finally {
            poller.stop();
            authService.shutdown();
        }
    }

    @Test
    void loadsCompleteEnglishTranslationsFromResourceFile() {
        assertTrue(SpotifyI18n.getFallbackTranslations().size() >= 90);
        assertEquals("Set volume to 75%.", SpotifyI18n.translate("status.spotify_controller.volume_set", 75));
        assertEquals(
                "[Spotify] Not set up yet! Press [O] or run /spotify setup for the first-time setup guide.",
                SpotifyI18n.translate("chat.spotify_controller.setup_prompt", "O")
        );
        assertEquals("HUD Widget: ON", SpotifyI18n.translate("screen.spotify_controller.config.hud_on"));
    }
}
