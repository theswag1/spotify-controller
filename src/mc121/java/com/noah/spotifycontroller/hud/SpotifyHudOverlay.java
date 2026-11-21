package com.noah.spotifycontroller.hud;

import com.noah.spotifycontroller.SpotifyControllerClient;
import com.noah.spotifycontroller.api.SpotifyPoller;
import com.noah.spotifycontroller.api.TrackState;
import com.noah.spotifycontroller.config.SpotifyConfig;
import com.noah.spotifycontroller.gui.SpotifyConfigScreen;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import com.noah.spotifycontroller.util.SpotifyI18n;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Renders the compact "Now Playing" Spotify card on the in-game HUD (Minecraft 1.21.1).
 */
public class SpotifyHudOverlay {
    public static final int CARD_WIDTH = 190;
    public static final int CARD_HEIGHT = 46;
    public static final int COMPACT_WIDTH = 190;
    public static final int COMPACT_HEIGHT = 10;
    public static final int CONTROL_SIZE = 16;
    private static final int CONTROL_GAP = 2;
    private static final int CONTROL_STRIP_WIDTH = 3 * CONTROL_SIZE + 2 * CONTROL_GAP;
    private static final long OPTIMISTIC_PLAYING_MS = 2500L;

    private volatile boolean optimisticPlaying;
    private volatile long optimisticPlayingUntilMs = 0L;
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
     * Renders the Spotify HUD overlay when enabled and the HUD is visible.
     */
    public void render(GuiGraphics guiGraphics, DeltaTracker deltaTracker) {
        Minecraft client = Minecraft.getInstance();
        if (!config.hudEnabled
                || client.options.hideGui
                || client.screen instanceof SpotifyConfigScreen) {
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
    public void renderCard(GuiGraphics guiGraphics, Font font, TrackState track, int x, int y, float scale) {
        float clampedScale = SpotifyConfig.clampHudScale(scale);
        int accentColor = config.getAccentColorArgb();

        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate((float) x, (float) y, 0.0f);
        guiGraphics.pose().scale(clampedScale, clampedScale, 1.0f);

        if (config.hudCompact) {
            String line = SpotifyI18n.translate("hud.spotify_controller.compact_line", track.trackName(), track.artistName());
            drawScrollingText(guiGraphics, font, line, 0, 1, COMPACT_WIDTH, accentColor, System.currentTimeMillis());
            guiGraphics.pose().popPose();
            return;
        }

        // Card background, border, and end accent strip
        guiGraphics.fill(0, 0, CARD_WIDTH, CARD_HEIGHT, 0xCC121212);
        guiGraphics.renderOutline(0, 0, CARD_WIDTH, CARD_HEIGHT, 0xFF282828);
        guiGraphics.fill(0, 0, 2, CARD_HEIGHT, accentColor);

        // Album art thumbnail
        int artX = 6;
        int artY = 5;
        ResourceLocation artTexture = albumArtManager.getOrLoadTexture(track.albumArtUrl());
        if (artTexture != null) {
            guiGraphics.blit(artTexture, artX, artY, ART_SIZE, ART_SIZE, 0.0f, 0.0f, 1, 1, 1, 1);
        } else {
            guiGraphics.fill(artX, artY, artX + ART_SIZE, artY + ART_SIZE, 0xFF222222);
            guiGraphics.drawCenteredString(font, Component.translatable("hud.spotify_controller.music_note"), artX + ART_SIZE / 2, 19, accentColor);
        }
        guiGraphics.renderOutline(artX - 1, artY - 1, ART_SIZE + 2, ART_SIZE + 2, 0xFF333333);

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
        guiGraphics.drawString(font, elapsedText, textX, 30, 0xFF9E9E9E);
        guiGraphics.drawString(font, totalText, textX + maxTextWidth - font.width(totalText), 30, 0xFF9E9E9E);

        int barY = 40;
        guiGraphics.fill(textX, barY, textX + maxTextWidth, barY + 2, 0xFF333333);
        int filledWidth = (int) Math.min(maxTextWidth, Math.round((double) progressMs / durationMs * maxTextWidth));
        if (filledWidth > 0) {
            guiGraphics.fill(textX, barY, textX + filledWidth, barY + 2, accentColor);
        }

        guiGraphics.pose().popPose();
    }

    /**
     * Draws prev / play-pause / next buttons by the HUD widget while the mouse hovers it on an
     * in-world screen that frees the cursor (chat, inventory...). Called from the loaders' screen render events.
     */
    public void renderHoverControls(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        int[] bounds = hoverableBounds(guiGraphics.guiWidth(), guiGraphics.guiHeight());
        if (bounds == null) {
            return;
        }
        int stripY = controlStripY(bounds[1], bounds[3], guiGraphics.guiHeight());
        if (!isInHoverZone(mouseX, mouseY, bounds[0], bounds[1], bounds[2], bounds[3], stripY)) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        int accent = config.getAccentColorArgb();
        int hovered = hoverControlAt(mouseX, mouseY, bounds[0], stripY);
        String[] icons = {"⏮", isEffectivelyPlaying() ? "⏸" : "▶", "⏭"};
        for (int i = 0; i < icons.length; i++) {
            int cx = bounds[0] + i * (CONTROL_SIZE + CONTROL_GAP);
            guiGraphics.fill(cx, stripY, cx + CONTROL_SIZE, stripY + CONTROL_SIZE, 0xE0121212);
            guiGraphics.renderOutline(cx, stripY, CONTROL_SIZE, CONTROL_SIZE, i == hovered ? accent : 0xFF333333);
            guiGraphics.drawCenteredString(font, icons[i], cx + CONTROL_SIZE / 2, stripY + 4, i == hovered ? accent : 0xFFFFFFFF);
        }
    }

    /** Playing state including a just-clicked toggle, so the icon flips before the next poll confirms it. */
    public boolean isEffectivelyPlaying() {
        if (System.currentTimeMillis() < optimisticPlayingUntilMs) {
            return optimisticPlaying;
        }
        TrackState track = poller.getCurrentTrack();
        return track != null && track.isPlaying();
    }

    /** Records a play/pause click; returns whether it should resume (true) or pause (false). */
    public boolean togglePlayingOptimistically() {
        boolean resume = !isEffectivelyPlaying();
        optimisticPlaying = resume;
        optimisticPlayingUntilMs = System.currentTimeMillis() + OPTIMISTIC_PLAYING_MS;
        return resume;
    }

    /**
     * Returns the hover control (0 prev, 1 play/pause, 2 next) under the mouse, or -1.
     */
    public int controlAt(double mouseX, double mouseY, int screenWidth, int screenHeight) {
        int[] bounds = hoverableBounds(screenWidth, screenHeight);
        return bounds == null ? -1 : hoverControlAt(mouseX, mouseY, bounds[0], controlStripY(bounds[1], bounds[3], screenHeight));
    }

    /** On-screen widget bounds {x, y, width, height}, or null when hover controls should not show. */
    private int[] hoverableBounds(int screenWidth, int screenHeight) {
        Minecraft client = Minecraft.getInstance();
        if (!config.hudEnabled
                || client.player == null
                || poller.getCurrentTrack() == null
                || client.options.hideGui
                || SpotifyControllerClient.isSpotifyUiOpen(client)) {
            return null;
        }
        return new int[]{
                clampX(config.hudX, screenWidth, config.hudScale, config.hudCompact),
                clampY(config.hudY, screenHeight, config.hudScale, config.hudCompact),
                getScaledWidth(config.hudScale, config.hudCompact),
                getScaledHeight(config.hudScale, config.hudCompact)
        };
    }

    /** The control strip goes below the widget, or above it when the widget sits at the bottom of the screen. */
    public static int controlStripY(int y, int height, int screenHeight) {
        int below = y + height + CONTROL_GAP;
        return below + CONTROL_SIZE <= screenHeight ? below : y - CONTROL_GAP - CONTROL_SIZE;
    }

    /** The widget plus the control strip, so moving the mouse onto the buttons keeps them open. */
    public static boolean isInHoverZone(double mouseX, double mouseY, int x, int y, int width, int height, int stripY) {
        int top = Math.min(y, stripY);
        int bottom = Math.max(y + height, stripY + CONTROL_SIZE);
        return mouseX >= x && mouseX < x + Math.max(width, CONTROL_STRIP_WIDTH) && mouseY >= top && mouseY < bottom;
    }

    public static int hoverControlAt(double mouseX, double mouseY, int x, int stripY) {
        if (mouseY < stripY || mouseY >= stripY + CONTROL_SIZE) {
            return -1;
        }
        for (int i = 0; i < 3; i++) {
            int cx = x + i * (CONTROL_SIZE + CONTROL_GAP);
            if (mouseX >= cx && mouseX < cx + CONTROL_SIZE) {
                return i;
            }
        }
        return -1;
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
            GuiGraphics guiGraphics,
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
            guiGraphics.drawString(font, text, x, y, color);
            return;
        }

        int offset = computeScrollOffset(textWidth, maxWidth, nowMillis);
        // 1.21.1 enableScissor takes screen coordinates and ignores the pose, so map the clip box
        // through the HUD's translate/scale; otherwise long titles are clipped away entirely.
        Matrix4f matrix = guiGraphics.pose().last().pose();
        Vector3f min = matrix.transformPosition(x, y, 0.0f, new Vector3f());
        Vector3f max = matrix.transformPosition(x + maxWidth, y + font.lineHeight, 0.0f, new Vector3f());
        guiGraphics.enableScissor(
                (int) Math.floor(min.x), (int) Math.floor(min.y),
                (int) Math.ceil(max.x), (int) Math.ceil(max.y)
        );
        guiGraphics.drawString(font, text, x - offset, y, color);
        guiGraphics.disableScissor();
    }
}
