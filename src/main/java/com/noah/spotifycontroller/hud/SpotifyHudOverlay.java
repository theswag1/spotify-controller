package com.noah.spotifycontroller.hud;

import com.noah.spotifycontroller.api.SpotifyPoller;
import com.noah.spotifycontroller.api.TrackState;
import com.noah.spotifycontroller.config.SpotifyConfig;
import com.noah.spotifycontroller.gui.SpotifyConfigScreen;
import com.noah.spotifycontroller.util.MinecraftGuiCompat;
import com.noah.spotifycontroller.util.SpotifyI18n;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Renders the compact "Now Playing" Spotify card on the in-game HUD.
 */
public class SpotifyHudOverlay {
    public static final int CARD_WIDTH = 190;
    public static final int CARD_HEIGHT = 46;
    public static final int COMPACT_WIDTH = 190;
    public static final int COMPACT_HEIGHT = 10;
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

    /**
     * Renders the Spotify HUD overlay during HUD extraction when enabled.
     */
    public void extractRenderState(GuiGraphicsExtractor guiGraphics, DeltaTracker deltaTracker) {
        Minecraft client = Minecraft.getInstance();
        if (!config.hudEnabled
                || MinecraftGuiCompat.isHudHidden(client)
                || MinecraftGuiCompat.getScreen(client) instanceof SpotifyConfigScreen) {
            return;
        }

        TrackState track = poller.getCurrentTrack();
        if (track == null) {
            return;
        }

        int x = clampX(config.hudX, guiGraphics.guiWidth(), config.hudScale, config.hudCompact);
        int y = clampY(config.hudY, guiGraphics.guiHeight(), config.hudScale, config.hudCompact);
        renderCard(guiGraphics, client.font, track, x, y, config.hudScale);
    }

    /**
     * Renders the Spotify "Now Playing" widget (full card, or one text line in compact mode)
     * at the given screen coordinates and scale factor.
     */
    public void renderCard(GuiGraphicsExtractor guiGraphics, Font font, TrackState track, int x, int y, float scale) {
        float clampedScale = SpotifyConfig.clampHudScale(scale);
        int accentColor = config.getAccentColorArgb();

        guiGraphics.pose().pushMatrix();
        guiGraphics.pose().translate((float) x, (float) y);
        guiGraphics.pose().scale(clampedScale, clampedScale);

        if (config.hudCompact) {
            String line = SpotifyI18n.translate("hud.spotify_controller.compact_line", track.trackName(), track.artistName());
            drawScrollingText(guiGraphics, font, line, 0, 1, COMPACT_WIDTH, accentColor, System.currentTimeMillis());
            guiGraphics.pose().popMatrix();
            return;
        }

        // Card background, border, and end accent strip
        guiGraphics.fill(0, 0, CARD_WIDTH, CARD_HEIGHT, 0xCC121212);
        guiGraphics.outline(0, 0, CARD_WIDTH, CARD_HEIGHT, 0xFF282828);
        guiGraphics.fill(0, 0, 2, CARD_HEIGHT, accentColor);

        // Album art thumbnail
        int artX = 6;
        int artY = 5;
        Identifier artTexture = albumArtManager.getOrLoadTexture(track.albumArtUrl());
        if (artTexture != null) {
            guiGraphics.blit(artTexture, artX, artY, artX + ART_SIZE, artY + ART_SIZE, 0.0f, 1.0f, 0.0f, 1.0f);
        } else {
            guiGraphics.fill(artX, artY, artX + ART_SIZE, artY + ART_SIZE, 0xFF222222);
            guiGraphics.centeredText(font, Component.translatable("hud.spotify_controller.music_note"), artX + ART_SIZE / 2, 19, accentColor);
        }
        guiGraphics.outline(artX - 1, artY - 1, ART_SIZE + 2, ART_SIZE + 2, 0xFF333333);

        // Track title & artist with marquee scrolling when wider than the card
        int textX = 48;
        int maxTextWidth = CARD_WIDTH - 54;
        long nowMillis = System.currentTimeMillis();
        drawScrollingText(guiGraphics, font, track.trackName(), textX, 6, maxTextWidth, 0xFFFFFFFF, nowMillis);
        drawScrollingText(guiGraphics, font, track.artistName(), textX, 18, maxTextWidth, 0xFFB3B3B3, nowMillis);

        // Interpolated progress timestamps & bar
        long progressMs = track.getInterpolatedProgressMs();
        long durationMs = Math.max(1L, track.durationMs());

        String elapsedText = TrackState.formatDuration(progressMs);
        String totalText = (track.isPlaying() ? "" : "⏸ ") + TrackState.formatDuration(track.durationMs());
        guiGraphics.text(font, elapsedText, textX, 30, 0xFF9E9E9E);
        guiGraphics.text(font, totalText, textX + maxTextWidth - font.width(totalText), 30, 0xFF9E9E9E);

        int barY = 40;
        guiGraphics.fill(textX, barY, textX + maxTextWidth, barY + 2, 0xFF333333);
        int filledWidth = (int) Math.min(maxTextWidth, Math.round((double) progressMs / durationMs * maxTextWidth));
        if (filledWidth > 0) {
            guiGraphics.fill(textX, barY, textX + filledWidth, barY + 2, accentColor);
        }

        guiGraphics.pose().popMatrix();
    }

    public static int getScaledWidth(float scale, boolean compact) {
        return Math.round((compact ? COMPACT_WIDTH : CARD_WIDTH) * SpotifyConfig.clampHudScale(scale));
    }

    public static int getScaledHeight(float scale, boolean compact) {
        return Math.round((compact ? COMPACT_HEIGHT : CARD_HEIGHT) * SpotifyConfig.clampHudScale(scale));
    }

    /**
     * Clamps the scaled HUD X coordinate inside the visible screen bounds.
     */
    public static int clampX(int x, int screenWidth, float scale, boolean compact) {
        int width = getScaledWidth(scale, compact);
        return Math.max(2, Math.min(x, Math.max(2, screenWidth - width - 2)));
    }

    /**
     * Clamps the scaled HUD Y coordinate inside the visible screen bounds.
     */
    public static int clampY(int y, int screenHeight, float scale, boolean compact) {
        int height = getScaledHeight(scale, compact);
        return Math.max(2, Math.min(y, Math.max(2, screenHeight - height - 2)));
    }

    /**
     * Returns whether the pointer coordinates fall within the scaled HUD widget rectangle.
     */
    public static boolean isPointInsideCard(double mouseX, double mouseY, int cardX, int cardY, float scale, boolean compact) {
        int width = getScaledWidth(scale, compact);
        int height = getScaledHeight(scale, compact);
        return mouseX >= cardX
                && mouseX < cardX + width
                && mouseY >= cardY
                && mouseY < cardY + height;
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
