package com.noah.spotifycontroller;

import com.noah.spotifycontroller.api.TrackState;
import com.noah.spotifycontroller.auth.SpotifyAuthService;
import com.noah.spotifycontroller.config.SpotifyConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
