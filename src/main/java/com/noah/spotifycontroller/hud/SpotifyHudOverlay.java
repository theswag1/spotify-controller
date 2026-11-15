package com.noah.spotifycontroller.hud;

import com.noah.spotifycontroller.api.SpotifyPoller;
import com.noah.spotifycontroller.api.TrackState;
import com.noah.spotifycontroller.config.SpotifyConfig;
import com.noah.spotifycontroller.gui.SpotifyConfigScreen;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Renders the compact "Now Playing" Spotify card on the in-game HUD.
 */
public class SpotifyHudOverlay implements HudElement {
    public static final int CARD_WIDTH = 190;
    public static final int CARD_HEIGHT = 46;
    private static final int ART_SIZE = 36;
    private static final long MARQUEE_PAUSE_MS = 1500L;
    private static final double MARQUEE_PIXELS_PER_SECOND = 24.0;

    private final SpotifyConfig config;
    private final SpotifyPoller poller;
    private final AlbumArtManager albumArtManager;

    public SpotifyHudOverlay(
            SpotifyConfig config,
            SpotifyPoller poller,
            AlbumArtManager albumArtManager
    ) {
        this.config = config;
        this.poller = poller;
        this.albumArtManager = albumArtManager;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor guiGraphics, DeltaTracker deltaTracker) {
        Minecraft client = Minecraft.getInstance();
        if (!config.hudEnabled
                || client.gui.hud.isHidden()
                || client.gui.screen() instanceof SpotifyConfigScreen) {
            return;
        }

        TrackState track = poller.getCurrentTrack();
        if (track == null) {
            return;
        }

        int x = clampX(config.hudX, guiGraphics.guiWidth());
        int y = clampY(config.hudY, guiGraphics.guiHeight());
        renderCard(guiGraphics, client.font, track, x, y);
    }

    /**
     * Renders the Spotify "Now Playing" card at the given screen coordinates.
     */
    public void renderCard(GuiGraphicsExtractor guiGraphics, Font font, TrackState track, int x, int y) {
        int accentColor = track.isPlaying() ? 0xFF1DB954 : 0xFFFFB74D;

        // Card background, border, and playback-state accent strip
        guiGraphics.fill(x, y, x + CARD_WIDTH, y + CARD_HEIGHT, 0xCC121212);
        guiGraphics.outline(x, y, CARD_WIDTH, CARD_HEIGHT, 0xFF282828);
        guiGraphics.fill(x, y, x + 2, y + CARD_HEIGHT, accentColor);

        // Album art thumbnail
        int artX = x + 6;
        int artY = y + 5;
        Identifier artTexture = albumArtManager.getOrLoadTexture(track.albumArtUrl());
        if (artTexture != null) {
            guiGraphics.blit(artTexture, artX, artY, artX + ART_SIZE, artY + ART_SIZE, 0.0f, 1.0f, 0.0f, 1.0f);
        } else {
            guiGraphics.fill(artX, artY, artX + ART_SIZE, artY + ART_SIZE, 0xFF222222);
            guiGraphics.centeredText(font, Component.literal("♪"), artX + ART_SIZE / 2, y + 19, 0xFF1DB954);
        }
        guiGraphics.outline(artX - 1, artY - 1, ART_SIZE + 2, ART_SIZE + 2, 0xFF333333);

        // Track title & artist with marquee scrolling when wider than the card
        int textX = x + 48;
        int maxTextWidth = CARD_WIDTH - 54;
        long nowMillis = System.currentTimeMillis();
        drawScrollingText(guiGraphics, font, track.trackName(), textX, y + 6, maxTextWidth, 0xFFFFFFFF, nowMillis);
        drawScrollingText(guiGraphics, font, track.artistName(), textX, y + 18, maxTextWidth, 0xFFB3B3B3, nowMillis);

        // Interpolated progress timestamps & bar
        long progressMs = track.getInterpolatedProgressMs();
        long durationMs = Math.max(1L, track.durationMs());

        String elapsedText = TrackState.formatDuration(progressMs);
        String totalText = (track.isPlaying() ? "" : "⏸ ") + TrackState.formatDuration(track.durationMs());
        guiGraphics.text(font, elapsedText, textX, y + 30, 0xFF9E9E9E);
        guiGraphics.text(font, totalText, textX + maxTextWidth - font.width(totalText), y + 30, 0xFF9E9E9E);

        int barY = y + 40;
        guiGraphics.fill(textX, barY, textX + maxTextWidth, barY + 2, 0xFF333333);
        int filledWidth = (int) Math.min(maxTextWidth, Math.round((double) progressMs / durationMs * maxTextWidth));
        if (filledWidth > 0) {
            guiGraphics.fill(textX, barY, textX + filledWidth, barY + 2, accentColor);
        }
    }

    /**
     * Clamps the HUD X coordinate inside the visible screen bounds.
     */
    public static int clampX(int x, int screenWidth) {
        return Math.max(2, Math.min(x, Math.max(2, screenWidth - CARD_WIDTH - 2)));
    }

    /**
     * Clamps the HUD Y coordinate inside the visible screen bounds.
     */
    public static int clampY(int y, int screenHeight) {
        return Math.max(2, Math.min(y, Math.max(2, screenHeight - CARD_HEIGHT - 2)));
    }

    /**
     * Returns whether the pointer coordinates fall within the HUD card rectangle.
     */
    public static boolean isPointInsideCard(double mouseX, double mouseY, int cardX, int cardY) {
        return mouseX >= cardX
                && mouseX < cardX + CARD_WIDTH
                && mouseY >= cardY
                && mouseY < cardY + CARD_HEIGHT;
    }

    /**
     * Computes a ping-pong horizontal scroll offset in pixels when text exceeds {@code maxWidth}.
     */
    public static int computeScrollOffset(int textWidth, int maxWidth, long nowMillis) {
        int overflow = textWidth - maxWidth;
        if (overflow <= 0) {
            return 0;
        }

        long scrollMs = Math.max(500L, Math.round((overflow / MARQUEE_PIXELS_PER_SECOND) * 1000.0));
        long cycleMs = 2L * MARQUEE_PAUSE_MS + 2L * scrollMs;
        long phase = Math.floorMod(nowMillis, cycleMs);

        if (phase < MARQUEE_PAUSE_MS) {
            return 0;
        }
        if (phase < MARQUEE_PAUSE_MS + scrollMs) {
            double progress = (double) (phase - MARQUEE_PAUSE_MS) / scrollMs;
            return (int) Math.round(progress * overflow);
        }
        if (phase < 2L * MARQUEE_PAUSE_MS + scrollMs) {
            return overflow;
        }
        double returnProgress = (double) (phase - (2L * MARQUEE_PAUSE_MS + scrollMs)) / scrollMs;
        return (int) Math.round((1.0 - returnProgress) * overflow);
    }

    private static void drawScrollingText(
            GuiGraphicsExtractor guiGraphics,
            Font font,
            String text,
            int x,
            int y,
            int maxWidth,
            int color,
            long nowMillis
    ) {
        int textWidth = font.width(text);
        if (textWidth <= maxWidth) {
            guiGraphics.text(font, text, x, y, color);
            return;
        }

        int offset = computeScrollOffset(textWidth, maxWidth, nowMillis);
        guiGraphics.enableScissor(x, y, x + maxWidth, y + font.lineHeight);
        guiGraphics.text(font, text, x - offset, y, color);
        guiGraphics.disableScissor();
    }
}
