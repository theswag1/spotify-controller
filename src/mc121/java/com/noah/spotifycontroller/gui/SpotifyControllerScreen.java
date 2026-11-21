package com.noah.spotifycontroller.gui;

import com.mojang.blaze3d.platform.InputConstants;
import com.noah.spotifycontroller.api.SpotifyApiClient;
import com.noah.spotifycontroller.api.SpotifyPoller;
import com.noah.spotifycontroller.api.TrackState;
import com.noah.spotifycontroller.config.SpotifyConfig;
import com.noah.spotifycontroller.hud.AlbumArtManager;
import com.noah.spotifycontroller.hud.SpotifyHudOverlay;
import com.noah.spotifycontroller.util.SpotifyI18n;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Full in-game Spotify playback controller screen for Minecraft 1.21.1.
 */
public class SpotifyControllerScreen extends Screen {
    private static final int PANEL_WIDTH = 364;
    private static final int PANEL_HEIGHT = 206;
    private static final int CONTENT_WIDTH = PANEL_WIDTH - 24;
    private static final int TEXT_AND_SEEK_WIDTH = 252;
    private static final int VOLUME_BAR_WIDTH = 178;
    private static final int ALBUM_ART_SIZE = 64;
    private static final long OPTIMISTIC_STATE_MS = 2500L;
    private static final long ERROR_DISPLAY_MS = 5000L;
    private static final int QUEUE_ICON_SIZE = 14;
    private static final int QUEUE_POPOVER_WIDTH = 220;
    private static final int QUEUE_ROW_HEIGHT = 12;
    private static final long QUEUE_REFRESH_MS = 10_000L;

    private final Screen parent;
    private final SpotifyConfig config;
    private final SpotifyApiClient apiClient;
    private final SpotifyPoller poller;
    private final AlbumArtManager albumArtManager;

    private Button shuffleButton;
    private Button playPauseButton;
    private Button repeatButton;

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

    private Component errorMessage;
    private long errorUntilMs = 0L;

    private boolean queueOpen = false;
    private boolean queueLoading = false;
    private long queueFetchedAtMs = 0L;
    private List<TrackState.QueueItem> queueItems;
    private String queueError;

    public SpotifyControllerScreen(
            Screen parent,
            SpotifyConfig config,
            SpotifyApiClient apiClient,
            SpotifyPoller poller,
            AlbumArtManager albumArtManager
    ) {
        super(Component.translatable("screen.spotify_controller.controller.title"));
        this.parent = parent;
        this.config = config;
        this.apiClient = apiClient;
        this.poller = poller;
        this.albumArtManager = albumArtManager;
    }

    @Override
    protected void init() {
        int left = panelLeft();
        int top = panelTop();

        shuffleButton = addRenderableWidget(
                Button.builder(Component.translatable("screen.spotify_controller.controller.shuffle_off"), button -> toggleShuffle())
                        .bounds(left + 12, top + 122, 76, 20)
                        .build()
        );

        addRenderableWidget(
                Button.builder(Component.translatable("screen.spotify_controller.controller.prev"), button -> executePlaybackAction(apiClient.previousTrack()))
                        .bounds(left + 93, top + 122, 56, 20)
                        .build()
        );

        playPauseButton = addRenderableWidget(
                Button.builder(Component.translatable("screen.spotify_controller.controller.play"), button -> togglePlayPause())
                        .bounds(left + 154, top + 122, 56, 20)
                        .build()
        );

        addRenderableWidget(
                Button.builder(Component.translatable("screen.spotify_controller.controller.next"), button -> executePlaybackAction(apiClient.nextTrack()))
                        .bounds(left + 215, top + 122, 56, 20)
                        .build()
        );

        repeatButton = addRenderableWidget(
                Button.builder(
                        Component.translatable(
                                "screen.spotify_controller.controller.repeat",
                                Component.translatable("screen.spotify_controller.controller.repeat.off")
                        ),
                        button -> cycleRepeatMode()
                ).bounds(left + 276, top + 122, 76, 20).build()
        );

        addRenderableWidget(
                Button.builder(Component.translatable("screen.spotify_controller.controller.vol_down"), button -> adjustVolume(-10))
                        .bounds(left + 12, top + 148, 76, 20)
                        .build()
        );

        addRenderableWidget(
                Button.builder(Component.translatable("screen.spotify_controller.controller.vol_up"), button -> adjustVolume(10))
                        .bounds(left + 276, top + 148, 76, 20)
                        .build()
        );

        addRenderableWidget(
                Button.builder(Component.translatable("screen.spotify_controller.config.done"), button -> onClose())
                        .bounds(left + 12, top + 174, CONTENT_WIDTH, 20)
                        .build()
        );

        refreshDynamicLabels(poller.getCurrentTrack());
        poller.refreshNow();
    }

    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        int left = panelLeft();
        int top = panelTop();
        guiGraphics.fill(left, top, left + PANEL_WIDTH, top + PANEL_HEIGHT, 0xCC121212);
        guiGraphics.renderOutline(left, top, PANEL_WIDTH, PANEL_HEIGHT, 0xFF2E2E2E);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        TrackState track = poller.getCurrentTrack();
        refreshDynamicLabels(track);

        super.render(guiGraphics, mouseX, mouseY, partialTick);

        int left = panelLeft();
        int top = panelTop();

        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, top + 9, 0xFFFFFFFF);

        renderNowPlayingCard(guiGraphics, mouseX, mouseY, left, top, track);
        renderVolumeBar(guiGraphics, mouseX, mouseY, left, top, track);

        // Drawn below the panel, on top of the blurred background, so failed actions are visible.
        if (errorMessage != null && System.currentTimeMillis() < errorUntilMs) {
            int y = top + PANEL_HEIGHT + 4;
            for (FormattedCharSequence line : this.font.split(errorMessage, PANEL_WIDTH)) {
                guiGraphics.drawString(this.font, line, (this.width - this.font.width(line)) / 2, y, 0xFFFF6B6B);
                y += this.font.lineHeight + 1;
            }
        }

        renderQueuePopover(guiGraphics, mouseX, mouseY, left, top);
    }

    /**
     * Queue icon at the panel's top-right; hovering it (or the open list) shows the next tracks.
     * Spotify's API has no endpoint to reorder or remove queue items, so this is read-only.
     */
    private void renderQueuePopover(GuiGraphics guiGraphics, int mouseX, int mouseY, int left, int top) {
        int iconX = queueIconX(left);
        int iconY = top + 6;
        boolean overIcon = isPointInRect(mouseX, mouseY, iconX, iconY, QUEUE_ICON_SIZE, QUEUE_ICON_SIZE);
        queueOpen = overIcon || (queueOpen && isOverQueuePopover(mouseX, mouseY, left, top));

        int accent = config.getAccentColorArgb();
        guiGraphics.fill(iconX, iconY, iconX + QUEUE_ICON_SIZE, iconY + QUEUE_ICON_SIZE, queueOpen ? 0xFF2A2A2A : 0xFF1A1A1A);
        guiGraphics.renderOutline(iconX, iconY, QUEUE_ICON_SIZE, QUEUE_ICON_SIZE, queueOpen ? accent : 0xFF3A3A3A);
        guiGraphics.drawCenteredString(this.font, "☰", iconX + QUEUE_ICON_SIZE / 2, iconY + 3, queueOpen ? accent : 0xFFFFFFFF);
        if (!queueOpen) {
            return;
        }

        long now = System.currentTimeMillis();
        if (!queueLoading && now - queueFetchedAtMs > QUEUE_REFRESH_MS) {
            queueLoading = true;
            Minecraft client = this.minecraft != null ? this.minecraft : Minecraft.getInstance();
            apiClient.getQueue().thenAccept(result -> client.execute(() -> {
                queueItems = result.items();
                queueError = result.errorMessage();
                queueLoading = false;
                queueFetchedAtMs = System.currentTimeMillis();
            }));
        }

        // Lift above the buttons, which 1.21.1 draws at z=0 in the same pass.
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(0.0f, 0.0f, 200.0f);
        int popX = left + PANEL_WIDTH - QUEUE_POPOVER_WIDTH - 6;
        int popY = iconY + QUEUE_ICON_SIZE + 2;
        guiGraphics.fill(popX, popY, popX + QUEUE_POPOVER_WIDTH, popY + queuePopoverHeight(), 0xF0121212);
        guiGraphics.renderOutline(popX, popY, QUEUE_POPOVER_WIDTH, queuePopoverHeight(), accent);
        guiGraphics.drawString(this.font, Component.translatable("screen.spotify_controller.queue.title"), popX + 6, popY + 6, accent);

        int y = popY + 20;
        int textWidth = QUEUE_POPOVER_WIDTH - 12;
        if (queueError != null) {
            guiGraphics.drawString(this.font, this.font.plainSubstrByWidth(queueError, textWidth), popX + 6, y, 0xFFFF6B6B);
        } else if (queueItems == null) {
            guiGraphics.drawString(this.font, Component.translatable("screen.spotify_controller.queue.loading"), popX + 6, y, 0xFFA0A0A0);
        } else if (queueItems.isEmpty()) {
            guiGraphics.drawString(this.font, Component.translatable("screen.spotify_controller.queue.empty"), popX + 6, y, 0xFFA0A0A0);
        } else {
            for (int i = 0; i < queueItems.size(); i++) {
                TrackState.QueueItem item = queueItems.get(i);
                String row = (i + 1) + ". " + SpotifyI18n.translate("screen.spotify_controller.queue.row", item.name(), item.artist());
                guiGraphics.drawString(this.font, this.font.plainSubstrByWidth(row, textWidth), popX + 6, y, 0xFFE0E0E0);
                y += QUEUE_ROW_HEIGHT;
            }
        }
        guiGraphics.pose().popPose();
    }

    private boolean isOverQueuePopover(double mouseX, double mouseY, int left, int top) {
        // Includes the 2px gap under the icon so moving down into the list keeps it open.
        int popX = left + PANEL_WIDTH - QUEUE_POPOVER_WIDTH - 6;
        int popY = top + 6 + QUEUE_ICON_SIZE;
        return isPointInRect(mouseX, mouseY, popX, popY, QUEUE_POPOVER_WIDTH, queuePopoverHeight() + 2);
    }

    private int queuePopoverHeight() {
        int rows = (queueItems != null && queueError == null && !queueItems.isEmpty()) ? queueItems.size() : 1;
        return 24 + rows * QUEUE_ROW_HEIGHT;
    }

    private static int queueIconX(int left) {
        return left + PANEL_WIDTH - 6 - QUEUE_ICON_SIZE;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (queueOpen && isOverQueuePopover(mouseX, mouseY, panelLeft(), panelTop())) {
            return true; // the open list covers the buttons underneath
        }
        if (button == InputConstants.MOUSE_BUTTON_LEFT) {
            int left = panelLeft();
            int top = panelTop();

            int volBarX = volumeBarX(left);
            int volBarY = volumeBarY(top);
            if (isPointInRect(mouseX, mouseY, volBarX, volBarY, VOLUME_BAR_WIDTH, 20)) {
                this.draggingVolume = true;
                this.pendingVolume = SpotifyApiClient.computeVolumePercent(mouseX, volBarX, VOLUME_BAR_WIDTH);
                return true;
            }

            TrackState track = poller.getCurrentTrack();
            int seekBarX = seekBarX(left);
            int seekHitTop = top + 88;
            if (track != null
                    && track.durationMs() > 0L
                    && isPointInRect(mouseX, mouseY, seekBarX, seekHitTop, TEXT_AND_SEEK_WIDTH, 14)) {
                long targetMs = SpotifyApiClient.computeSeekPositionMs(mouseX, seekBarX, TEXT_AND_SEEK_WIDTH, track.durationMs());
                executePlaybackAction(apiClient.seekTo(targetMs));
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (this.draggingVolume && button == InputConstants.MOUSE_BUTTON_LEFT) {
            this.pendingVolume = SpotifyApiClient.computeVolumePercent(mouseX, volumeBarX(panelLeft()), VOLUME_BAR_WIDTH);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (this.draggingVolume && button == InputConstants.MOUSE_BUTTON_LEFT) {
            this.draggingVolume = false;
            int targetVolume = SpotifyApiClient.clampVolume(this.pendingVolume);
            this.optimisticVolume = targetVolume;
            this.optimisticVolumeUntilMs = System.currentTimeMillis() + OPTIMISTIC_STATE_MS;
            executePlaybackAction(apiClient.setVolume(targetVolume));
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public void onClose() {
        this.draggingVolume = false;
        Minecraft client = this.minecraft != null ? this.minecraft : Minecraft.getInstance();
        client.setScreen(this.parent);
    }


    private void renderNowPlayingCard(
            GuiGraphics guiGraphics,
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
        guiGraphics.renderOutline(cardX, cardY, cardWidth, cardHeight, 0xFF2B2B2B);

        int accentColor = config.getAccentColorArgb();
        int artX = left + 20;
        int artY = top + 34;
        ResourceLocation artTexture = (track != null && albumArtManager != null)
                ? albumArtManager.getOrLoadTexture(track.albumArtUrl())
                : null;
        if (artTexture != null) {
            guiGraphics.blit(artTexture, artX, artY, ALBUM_ART_SIZE, ALBUM_ART_SIZE, 0.0f, 0.0f, 1, 1, 1, 1);
        } else {
            guiGraphics.fill(artX, artY, artX + ALBUM_ART_SIZE, artY + ALBUM_ART_SIZE, 0xFF222222);
            guiGraphics.drawCenteredString(this.font, Component.translatable("hud.spotify_controller.music_note"), artX + ALBUM_ART_SIZE / 2, artY + 28, accentColor);
        }
        guiGraphics.renderOutline(artX - 1, artY - 1, ALBUM_ART_SIZE + 2, ALBUM_ART_SIZE + 2, 0xFF3A3A3A);

        int textX = left + 92;
        int maxTextWidth = TEXT_AND_SEEK_WIDTH;
        long nowMillis = System.currentTimeMillis();

        if (track != null) {
            drawScrollingText(guiGraphics, track.trackName(), textX, top + 36, maxTextWidth, 0xFFFFFFFF, nowMillis);
            drawScrollingText(guiGraphics, track.artistName(), textX, top + 50, maxTextWidth, 0xFFB3B3B3, nowMillis);
            String albumLabel = track.albumName().isBlank()
                    ? SpotifyI18n.translate("screen.spotify_controller.controller.default_album")
                    : track.albumName();
            drawScrollingText(guiGraphics, albumLabel, textX, top + 64, maxTextWidth, 0xFF8E8E8E, nowMillis);

            long progressMs = track.getInterpolatedProgressMs();
            long durationMs = Math.max(1L, track.durationMs());
            String elapsedText = TrackState.formatDuration(progressMs);
            String totalText = (getEffectivePlaying(track) ? "" : "⏸ ") + TrackState.formatDuration(track.durationMs());

            guiGraphics.drawString(this.font, elapsedText, textX, top + 80, 0xFFB0B0B0);
            guiGraphics.drawString(this.font, totalText, textX + maxTextWidth - this.font.width(totalText), top + 80, 0xFFB0B0B0);

            int seekX = seekBarX(left);
            int seekY = top + 92;
            int seekHeight = 6;
            boolean seekHovered = isPointInRect(mouseX, mouseY, seekX, top + 88, maxTextWidth, 14);

            guiGraphics.fill(seekX, seekY, seekX + maxTextWidth, seekY + seekHeight, 0xFF333333);
            int fillColor = getEffectivePlaying(track)
                    ? accentColor
                    : 0xFFFFB74D;
            int filledWidth = (int) Math.min(maxTextWidth, Math.round((double) progressMs / durationMs * maxTextWidth));
            if (filledWidth > 0) {
                guiGraphics.fill(seekX, seekY, seekX + filledWidth, seekY + seekHeight, fillColor);
            }
            if (seekHovered) {
                guiGraphics.renderOutline(seekX - 1, seekY - 1, maxTextWidth + 2, seekHeight + 2, 0xFFFFFFFF);
            }
        } else {
            Component zeroTime = Component.translatable("screen.spotify_controller.controller.zero_time");
            guiGraphics.drawString(this.font, Component.translatable("screen.spotify_controller.controller.no_track_title"), textX, top + 38, 0xFFFFFFFF);
            guiGraphics.drawString(this.font, Component.translatable("screen.spotify_controller.controller.no_track_subtitle"), textX, top + 52, 0xFFA0A0A0);
            guiGraphics.drawString(this.font, zeroTime, textX, top + 80, 0xFF777777);
            guiGraphics.drawString(this.font, zeroTime, textX + maxTextWidth - this.font.width(zeroTime), top + 80, 0xFF777777);
            guiGraphics.fill(textX, top + 92, textX + maxTextWidth, top + 98, 0xFF2B2B2B);
        }
    }

    private void renderVolumeBar(
            GuiGraphics guiGraphics,
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
        guiGraphics.renderOutline(barX, barY, barWidth, barHeight, borderColor);

        Component label = volume >= 0
                ? Component.translatable("screen.spotify_controller.controller.volume_value", volume)
                : Component.translatable("screen.spotify_controller.controller.volume_unknown");
        guiGraphics.drawCenteredString(this.font, label, barX + barWidth / 2, barY + 6, 0xFFFFFFFF);
    }

    private void drawScrollingText(
            GuiGraphics guiGraphics,
            String text,
            int x,
            int y,
            int maxWidth,
            int color,
            long nowMillis
    ) {
        int textWidth = this.font.width(text);
        if (textWidth <= maxWidth) {
            guiGraphics.drawString(this.font, text, x, y, color);
            return;
        }
        int offset = SpotifyHudOverlay.computeScrollOffset(textWidth, maxWidth, nowMillis);
        guiGraphics.enableScissor(x, y, x + maxWidth, y + this.font.lineHeight);
        guiGraphics.drawString(this.font, text, x - offset, y, color);
        guiGraphics.disableScissor();
    }

    private void refreshDynamicLabels(TrackState track) {
        boolean playing = getEffectivePlaying(track);
        boolean shuffle = getEffectiveShuffle(track);
        String repeat = getEffectiveRepeat(track);

        if (playPauseButton != null) {
            playPauseButton.setMessage(Component.translatable(playing
                    ? "screen.spotify_controller.controller.pause"
                    : "screen.spotify_controller.controller.play"));
        }
        if (shuffleButton != null) {
            shuffleButton.setMessage(Component.translatable(shuffle
                    ? "screen.spotify_controller.controller.shuffle_on"
                    : "screen.spotify_controller.controller.shuffle_off"));
        }
        if (repeatButton != null) {
            repeatButton.setMessage(Component.translatable(
                    "screen.spotify_controller.controller.repeat",
                    Component.translatable(SpotifyApiClient.getRepeatTranslationKey(repeat))
            ));
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
            if (throwable == null && result != null && result.success()) {
                poller.triggerImmediateRefresh(350L);
                errorUntilMs = 0L;
                return;
            }
            // Snap optimistic button states back and say why the action failed.
            optimisticPlayUntilMs = 0L;
            optimisticShuffleUntilMs = 0L;
            optimisticRepeatUntilMs = 0L;
            optimisticVolumeUntilMs = 0L;
            String message = throwable != null
                    ? SpotifyI18n.translate("chat.spotify_controller.error", throwable.getMessage())
                    : result != null ? result.message() : "";
            errorMessage = Component.literal(message);
            errorUntilMs = System.currentTimeMillis() + ERROR_DISPLAY_MS;
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
