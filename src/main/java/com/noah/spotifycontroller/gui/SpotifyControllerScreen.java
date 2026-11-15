package com.noah.spotifycontroller.gui;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import com.noah.spotifycontroller.api.SpotifyApiClient;
import com.noah.spotifycontroller.api.SpotifyPoller;
import com.noah.spotifycontroller.api.TrackState;
import com.noah.spotifycontroller.auth.SpotifyAuthService;
import com.noah.spotifycontroller.config.SpotifyConfig;
import com.noah.spotifycontroller.hud.AlbumArtManager;
import com.noah.spotifycontroller.hud.SpotifyHudOverlay;
import com.noah.spotifycontroller.util.MinecraftGuiCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.concurrent.CompletableFuture;

/**
 * Full in-game Spotify playback controller screen with album art, seekable
 * progress bar, transport controls, volume bar, and config navigation.
 */
public class SpotifyControllerScreen extends Screen {
    private static final int PANEL_WIDTH = 364;
    private static final int PANEL_HEIGHT = 228;
    private static final int CONTENT_WIDTH = PANEL_WIDTH - 24;
    private static final int TEXT_AND_SEEK_WIDTH = 252;
    private static final int VOLUME_BAR_WIDTH = 178;
    private static final int ALBUM_ART_SIZE = 64;
    private static final long OPTIMISTIC_STATE_MS = 2500L;

    private final Screen parent;
    private final SpotifyConfig config;
    private final SpotifyAuthService authService;
    private final SpotifyApiClient apiClient;
    private final SpotifyPoller poller;
    private final AlbumArtManager albumArtManager;
    private final SpotifyHudOverlay hudOverlay;

    private Button shuffleButton;
    private Button playPauseButton;
    private Button repeatButton;
    private Button hudToggleButton;

    private String statusOverride;
    private boolean draggingVolume = false;
    private int pendingVolume = -1;

    private boolean optimisticPlaying;
    private long optimisticPlayUntilMs = 0L;
    private boolean optimisticShuffle;
    private long optimisticShuffleUntilMs = 0L;
    private String optimisticRepeat = "off";
    private long optimisticRepeatUntilMs = 0L;
    private int optimisticVolume = -1;
    private long optimisticVolumeUntilMs = 0L;

    public SpotifyControllerScreen(
            Screen parent,
            SpotifyConfig config,
            SpotifyAuthService authService,
            SpotifyApiClient apiClient,
            SpotifyPoller poller,
            AlbumArtManager albumArtManager,
            SpotifyHudOverlay hudOverlay
    ) {
        super(Component.translatable("screen.spotify_controller.controller.title"));
        this.parent = parent;
        this.config = config;
        this.authService = authService;
        this.apiClient = apiClient;
        this.poller = poller;
        this.albumArtManager = albumArtManager;
        this.hudOverlay = hudOverlay;
    }

    @Override
    protected void init() {
        int left = panelLeft();
        int top = panelTop();

        shuffleButton = addRenderableWidget(
                Button.builder(Component.literal("Shuffle: OFF"), button -> toggleShuffle())
                        .bounds(left + 12, top + 122, 76, 20)
                        .build()
        );

        addRenderableWidget(
                Button.builder(Component.literal("<< Prev"), button -> executePlaybackAction(apiClient.previousTrack()))
                        .bounds(left + 93, top + 122, 56, 20)
                        .build()
        );

        playPauseButton = addRenderableWidget(
                Button.builder(Component.literal("Play"), button -> togglePlayPause())
                        .bounds(left + 154, top + 122, 56, 20)
                        .build()
        );

        addRenderableWidget(
                Button.builder(Component.literal("Next >>"), button -> executePlaybackAction(apiClient.nextTrack()))
                        .bounds(left + 215, top + 122, 56, 20)
                        .build()
        );

        repeatButton = addRenderableWidget(
                Button.builder(Component.literal("Repeat: OFF"), button -> cycleRepeatMode())
                        .bounds(left + 276, top + 122, 76, 20)
                        .build()
        );

        addRenderableWidget(
                Button.builder(Component.literal("Vol -10%"), button -> adjustVolume(-10))
                        .bounds(left + 12, top + 148, 76, 20)
                        .build()
        );

        addRenderableWidget(
                Button.builder(Component.literal("Vol +10%"), button -> adjustVolume(10))
                        .bounds(left + 276, top + 148, 76, 20)
                        .build()
        );

        hudToggleButton = addRenderableWidget(
                Button.builder(getHudToggleLabel(), button -> {
                    config.hudEnabled = !config.hudEnabled;
                    config.save();
                    button.setMessage(getHudToggleLabel());
                    this.statusOverride = "In-game HUD widget " + (config.hudEnabled ? "enabled." : "disabled.");
                }).bounds(left + 12, top + 174, 110, 20).build()
        );

        addRenderableWidget(
                Button.builder(
                        Component.translatable("screen.spotify_controller.controller.open_config"),
                        button -> {
                            Minecraft client = this.minecraft != null ? this.minecraft : Minecraft.getInstance();
                            MinecraftGuiCompat.setScreen(client, new SpotifyConfigScreen(this, config, authService, poller, hudOverlay));
                        }
                ).bounds(left + 127, top + 174, 120, 20).build()
        );

        addRenderableWidget(
                Button.builder(Component.translatable("screen.spotify_controller.config.done"), button -> onClose())
                        .bounds(left + 252, top + 174, 100, 20)
                        .build()
        );

        refreshDynamicLabels(poller.getCurrentTrack());
        poller.refreshNow();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
        TrackState track = poller.getCurrentTrack();
        refreshDynamicLabels(track);

        int left = panelLeft();
        int top = panelTop();

        // Main panel frame behind widgets
        guiGraphics.fill(left, top, left + PANEL_WIDTH, top + PANEL_HEIGHT, 0xCC121212);
        guiGraphics.outline(left, top, PANEL_WIDTH, PANEL_HEIGHT, 0xFF2E2E2E);

        super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);

        guiGraphics.centeredText(this.font, this.title, this.width / 2, top + 9, 0xFFFFFFFF);

        renderNowPlayingCard(guiGraphics, mouseX, mouseY, left, top, track);
        renderVolumeBar(guiGraphics, mouseX, mouseY, left, top, track);
        renderStatusFooter(guiGraphics, left, top);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            int left = panelLeft();
            int top = panelTop();

            int volBarX = volumeBarX(left);
            int volBarY = volumeBarY(top);
            if (isPointInRect(event.x(), event.y(), volBarX, volBarY, VOLUME_BAR_WIDTH, 20)) {
                this.draggingVolume = true;
                this.pendingVolume = SpotifyApiClient.computeVolumePercent(event.x(), volBarX, VOLUME_BAR_WIDTH);
                return true;
            }

            TrackState track = poller.getCurrentTrack();
            int seekBarX = seekBarX(left);
            int seekHitTop = top + 88;
            if (track != null
                    && track.durationMs() > 0L
                    && isPointInRect(event.x(), event.y(), seekBarX, seekHitTop, TEXT_AND_SEEK_WIDTH, 14)) {
                long targetMs = SpotifyApiClient.computeSeekPositionMs(event.x(), seekBarX, TEXT_AND_SEEK_WIDTH, track.durationMs());
                executePlaybackAction(apiClient.seekTo(targetMs));
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (this.draggingVolume && event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            this.pendingVolume = SpotifyApiClient.computeVolumePercent(event.x(), volumeBarX(panelLeft()), VOLUME_BAR_WIDTH);
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (this.draggingVolume && event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            this.draggingVolume = false;
            int targetVolume = SpotifyApiClient.clampVolume(this.pendingVolume);
            this.optimisticVolume = targetVolume;
            this.optimisticVolumeUntilMs = System.currentTimeMillis() + OPTIMISTIC_STATE_MS;
            executePlaybackAction(apiClient.setVolume(targetVolume));
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public void onClose() {
        this.draggingVolume = false;
        Minecraft client = this.minecraft != null ? this.minecraft : Minecraft.getInstance();
        MinecraftGuiCompat.setScreen(client, this.parent);
    }

    private void renderNowPlayingCard(
            GuiGraphicsExtractor guiGraphics,
            int mouseX,
            int mouseY,
            int left,
            int top,
            TrackState track
    ) {
        int cardX = left + 12;
        int cardY = top + 26;
        int cardWidth = CONTENT_WIDTH;
        int cardHeight = 88;

        guiGraphics.fill(cardX, cardY, cardX + cardWidth, cardY + cardHeight, 0xCC181818);
        guiGraphics.outline(cardX, cardY, cardWidth, cardHeight, 0xFF2B2B2B);

        int accentColor = config.getAccentColorArgb();
        int artX = left + 20;
        int artY = top + 34;
        Identifier artTexture = (track != null && albumArtManager != null)
                ? albumArtManager.getOrLoadTexture(track.albumArtUrl())
                : null;
        if (artTexture != null) {
            guiGraphics.blit(artTexture, artX, artY, artX + ALBUM_ART_SIZE, artY + ALBUM_ART_SIZE, 0.0f, 1.0f, 0.0f, 1.0f);
        } else {
            guiGraphics.fill(artX, artY, artX + ALBUM_ART_SIZE, artY + ALBUM_ART_SIZE, 0xFF222222);
            guiGraphics.centeredText(this.font, Component.literal("♪"), artX + ALBUM_ART_SIZE / 2, artY + 28, accentColor);
        }
        guiGraphics.outline(artX - 1, artY - 1, ALBUM_ART_SIZE + 2, ALBUM_ART_SIZE + 2, 0xFF3A3A3A);

        int textX = left + 92;
        int maxTextWidth = TEXT_AND_SEEK_WIDTH;
        long nowMillis = System.currentTimeMillis();

        if (track != null) {
            drawScrollingText(guiGraphics, track.trackName(), textX, top + 36, maxTextWidth, 0xFFFFFFFF, nowMillis);
            drawScrollingText(guiGraphics, track.artistName(), textX, top + 50, maxTextWidth, 0xFFB3B3B3, nowMillis);
            String albumLabel = track.albumName().isBlank() ? "Spotify Playback" : track.albumName();
            drawScrollingText(guiGraphics, albumLabel, textX, top + 64, maxTextWidth, 0xFF8E8E8E, nowMillis);

            long progressMs = track.getInterpolatedProgressMs();
            long durationMs = Math.max(1L, track.durationMs());
            String elapsedText = TrackState.formatDuration(progressMs);
            String totalText = (getEffectivePlaying(track) ? "" : "⏸ ") + TrackState.formatDuration(track.durationMs());

            guiGraphics.text(this.font, elapsedText, textX, top + 80, 0xFFB0B0B0);
            guiGraphics.text(this.font, totalText, textX + maxTextWidth - this.font.width(totalText), top + 80, 0xFFB0B0B0);

            int seekX = seekBarX(left);
            int seekY = top + 92;
            int seekHeight = 6;
            boolean seekHovered = isPointInRect(mouseX, mouseY, seekX, top + 88, maxTextWidth, 14);
            if (seekHovered) {
                guiGraphics.requestCursor(CursorTypes.POINTING_HAND);
            }

            guiGraphics.fill(seekX, seekY, seekX + maxTextWidth, seekY + seekHeight, 0xFF333333);
            int fillColor = getEffectivePlaying(track)
                    ? accentColor
                    : 0xFFFFB74D;
            int filledWidth = (int) Math.min(maxTextWidth, Math.round((double) progressMs / durationMs * maxTextWidth));
            if (filledWidth > 0) {
                guiGraphics.fill(seekX, seekY, seekX + filledWidth, seekY + seekHeight, fillColor);
            }
            if (seekHovered) {
                guiGraphics.outline(seekX - 1, seekY - 1, maxTextWidth + 2, seekHeight + 2, 0xFFFFFFFF);
            }
        } else {
            guiGraphics.text(this.font, Component.literal("No active Spotify track"), textX, top + 38, 0xFFFFFFFF);
            guiGraphics.text(this.font, Component.literal("Start playback in Spotify or log in"), textX, top + 52, 0xFFA0A0A0);
            guiGraphics.text(this.font, Component.literal("0:00"), textX, top + 80, 0xFF777777);
            guiGraphics.text(this.font, Component.literal("0:00"), textX + maxTextWidth - this.font.width("0:00"), top + 80, 0xFF777777);
            guiGraphics.fill(textX, top + 92, textX + maxTextWidth, top + 98, 0xFF2B2B2B);
        }
    }

    private void renderVolumeBar(
            GuiGraphicsExtractor guiGraphics,
            int mouseX,
            int mouseY,
            int left,
            int top,
            TrackState track
    ) {
        int barX = volumeBarX(left);
        int barY = volumeBarY(top);
        int barWidth = VOLUME_BAR_WIDTH;
        int barHeight = 20;

        boolean hovered = isPointInRect(mouseX, mouseY, barX, barY, barWidth, barHeight);
        if (hovered || draggingVolume) {
            guiGraphics.requestCursor(CursorTypes.POINTING_HAND);
        }

        int volume = getEffectiveVolume(track);
        guiGraphics.fill(barX, barY, barX + barWidth, barY + barHeight, 0xFF181818);

        if (volume >= 0) {
            int filledWidth = (int) Math.round((volume / 100.0) * (barWidth - 2));
            if (filledWidth > 0) {
                int fillColor = draggingVolume ? 0xFF1ED760 : 0xFF178D41;
                guiGraphics.fill(barX + 1, barY + 1, barX + 1 + filledWidth, barY + barHeight - 1, fillColor);
            }
        }

        int borderColor = (hovered || draggingVolume) ? 0xFFFFFFFF : 0xFF3E3E3E;
        guiGraphics.outline(barX, barY, barWidth, barHeight, borderColor);

        String label = volume >= 0 ? "Volume: " + volume + "%" : "Volume: --%";
        guiGraphics.centeredText(this.font, label, barX + barWidth / 2, barY + 6, 0xFFFFFFFF);
    }

    private void renderStatusFooter(GuiGraphicsExtractor guiGraphics, int left, int top) {
        boolean connected = config.hasValidAccessToken() || config.hasRefreshToken();
        String prefix = connected ? "Spotify: " : "Not connected: ";
        int prefixColor = connected ? 0xFF1DB954 : 0xFFFFB74D;

        String detail = statusOverride != null ? statusOverride : poller.getLastStatusMessage();
        String fullText = this.font.plainSubstrByWidth(prefix + detail, CONTENT_WIDTH);

        int footerY = top + 204;
        if (fullText.startsWith(prefix)) {
            int prefixWidth = this.font.width(prefix);
            guiGraphics.text(this.font, prefix, left + 12, footerY, prefixColor);
            guiGraphics.text(this.font, fullText.substring(prefix.length()), left + 12 + prefixWidth, footerY, 0xFFD0D0D0);
        } else {
            guiGraphics.text(this.font, fullText, left + 12, footerY, 0xFFD0D0D0);
        }
    }

    private void drawScrollingText(
            GuiGraphicsExtractor guiGraphics,
            String text,
            int x,
            int y,
            int maxWidth,
            int color,
            long nowMillis
    ) {
        int textWidth = this.font.width(text);
        if (textWidth <= maxWidth) {
            guiGraphics.text(this.font, text, x, y, color);
            return;
        }
        int offset = SpotifyHudOverlay.computeScrollOffset(textWidth, maxWidth, nowMillis);
        guiGraphics.enableScissor(x, y, x + maxWidth, y + this.font.lineHeight);
        guiGraphics.text(this.font, text, x - offset, y, color);
        guiGraphics.disableScissor();
    }

    private void refreshDynamicLabels(TrackState track) {
        boolean playing = getEffectivePlaying(track);
        boolean shuffle = getEffectiveShuffle(track);
        String repeat = getEffectiveRepeat(track);

        if (playPauseButton != null) {
            playPauseButton.setMessage(Component.literal(playing ? "Pause" : "Play"));
        }
        if (shuffleButton != null) {
            shuffleButton.setMessage(Component.literal(shuffle ? "Shuffle: ON" : "Shuffle: OFF"));
        }
        if (repeatButton != null) {
            repeatButton.setMessage(Component.literal("Repeat: " + SpotifyApiClient.formatRepeatLabel(repeat)));
        }
        if (hudToggleButton != null) {
            hudToggleButton.setMessage(getHudToggleLabel());
        }
    }

    private void togglePlayPause() {
        TrackState track = poller.getCurrentTrack();
        boolean currentlyPlaying = getEffectivePlaying(track);
        this.optimisticPlaying = !currentlyPlaying;
        this.optimisticPlayUntilMs = System.currentTimeMillis() + OPTIMISTIC_STATE_MS;
        executePlaybackAction(currentlyPlaying ? apiClient.pause() : apiClient.play());
    }

    private void toggleShuffle() {
        TrackState track = poller.getCurrentTrack();
        boolean nextShuffle = !getEffectiveShuffle(track);
        this.optimisticShuffle = nextShuffle;
        this.optimisticShuffleUntilMs = System.currentTimeMillis() + OPTIMISTIC_STATE_MS;
        executePlaybackAction(apiClient.setShuffle(nextShuffle));
    }

    private void cycleRepeatMode() {
        TrackState track = poller.getCurrentTrack();
        String nextRepeat = SpotifyApiClient.nextRepeatState(getEffectiveRepeat(track));
        this.optimisticRepeat = nextRepeat;
        this.optimisticRepeatUntilMs = System.currentTimeMillis() + OPTIMISTIC_STATE_MS;
        executePlaybackAction(apiClient.setRepeatMode(nextRepeat));
    }

    private void adjustVolume(int delta) {
        int current = getEffectiveVolume(poller.getCurrentTrack());
        int base = current >= 0 ? current : 50;
        int nextVolume = SpotifyApiClient.clampVolume(base + delta);
        this.optimisticVolume = nextVolume;
        this.optimisticVolumeUntilMs = System.currentTimeMillis() + OPTIMISTIC_STATE_MS;
        executePlaybackAction(apiClient.setVolume(nextVolume));
    }

    private void executePlaybackAction(CompletableFuture<SpotifyApiClient.ApiResult> action) {
        Minecraft client = this.minecraft != null ? this.minecraft : Minecraft.getInstance();
        action.whenComplete((result, throwable) -> client.execute(() -> {
            if (throwable != null) {
                this.statusOverride = "Error: " + throwable.getMessage();
                return;
            }
            if (result != null) {
                this.statusOverride = result.message();
                if (result.success()) {
                    poller.triggerImmediateRefresh(350L);
                }
            }
        }));
    }

    private boolean getEffectivePlaying(TrackState track) {
        if (System.currentTimeMillis() < optimisticPlayUntilMs) {
            return optimisticPlaying;
        }
        return track != null && track.isPlaying();
    }

    private boolean getEffectiveShuffle(TrackState track) {
        if (System.currentTimeMillis() < optimisticShuffleUntilMs) {
            return optimisticShuffle;
        }
        return track != null && track.shuffleState();
    }

    private String getEffectiveRepeat(TrackState track) {
        if (System.currentTimeMillis() < optimisticRepeatUntilMs) {
            return optimisticRepeat;
        }
        return track != null ? track.repeatState() : "off";
    }

    private int getEffectiveVolume(TrackState track) {
        if (draggingVolume && pendingVolume >= 0) {
            return pendingVolume;
        }
        if (System.currentTimeMillis() < optimisticVolumeUntilMs && optimisticVolume >= 0) {
            return optimisticVolume;
        }
        return track != null ? track.volumePercent() : -1;
    }

    private Component getHudToggleLabel() {
        return Component.translatable(
                config.hudEnabled
                        ? "screen.spotify_controller.config.hud_on"
                        : "screen.spotify_controller.config.hud_off"
        );
    }

    private int panelLeft() {
        return (this.width - PANEL_WIDTH) / 2;
    }

    private int panelTop() {
        return Math.max(16, (this.height - PANEL_HEIGHT) / 2);
    }

    private static int seekBarX(int left) {
        return left + 92;
    }

    private static int volumeBarX(int left) {
        return left + 93;
    }

    private static int volumeBarY(int top) {
        return top + 148;
    }

    private static boolean isPointInRect(double x, double y, int rectX, int rectY, int width, int height) {
        return x >= rectX && x < rectX + width && y >= rectY && y < rectY + height;
    }
}
