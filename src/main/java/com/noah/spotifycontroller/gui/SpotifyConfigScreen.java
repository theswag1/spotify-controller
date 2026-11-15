package com.noah.spotifycontroller.gui;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import com.noah.spotifycontroller.SpotifyControllerClient;
import com.noah.spotifycontroller.api.SpotifyPoller;
import com.noah.spotifycontroller.api.TrackState;
import com.noah.spotifycontroller.auth.SpotifyAuthService;
import com.noah.spotifycontroller.config.SpotifyConfig;
import com.noah.spotifycontroller.hud.SpotifyHudOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/**
 * In-game configuration screen for entering the Spotify Developer Client ID,
 * copying the loopback redirect URI, authenticating via OAuth PKCE, and
 * positioning the in-game HUD widget via drag-and-drop.
 */
public class SpotifyConfigScreen extends Screen {
    private static final int CONTENT_WIDTH = 320;
    private static final int HALF_BUTTON_WIDTH = 155;
    private static final int BUTTON_GAP = 10;

    private final Screen parent;
    private final SpotifyConfig config;
    private final SpotifyAuthService authService;
    private final SpotifyPoller poller;
    private final SpotifyHudOverlay hudOverlay;

    private EditBox clientIdBox;
    private Button disconnectButton;
    private String statusOverride;
    private boolean authInProgress = false;
    private boolean draggingHud = false;
    private int dragOffsetX = 0;
    private int dragOffsetY = 0;

    public SpotifyConfigScreen(
            Screen parent,
            SpotifyConfig config,
            SpotifyAuthService authService,
            SpotifyPoller poller
    ) {
        this(parent, config, authService, poller, SpotifyControllerClient.getHudOverlay());
    }

    public SpotifyConfigScreen(
            Screen parent,
            SpotifyConfig config,
            SpotifyAuthService authService,
            SpotifyPoller poller,
            SpotifyHudOverlay hudOverlay
    ) {
        super(Component.translatable("screen.spotify_controller.config.title"));
        this.parent = parent;
        this.config = config;
        this.authService = authService;
        this.poller = poller;
        this.hudOverlay = hudOverlay;
    }

    @Override
    protected void init() {
        int left = (this.width - CONTENT_WIDTH) / 2;
        int top = Math.max(30, (this.height - 238) / 2);

        String previousInput = clientIdBox != null
                ? clientIdBox.getValue()
                : (config.clientId != null ? config.clientId : "");

        clientIdBox = new EditBox(
                this.font,
                left,
                top + 24,
                CONTENT_WIDTH,
                20,
                Component.translatable("screen.spotify_controller.config.client_id")
        );
        clientIdBox.setMaxLength(128);
        clientIdBox.setValue(previousInput);
        clientIdBox.setHint(Component.literal("Paste your Spotify Client ID here..."));
        addRenderableWidget(clientIdBox);
        setInitialFocus(clientIdBox);

        addRenderableWidget(
                Button.builder(
                        Component.translatable("screen.spotify_controller.config.copy_redirect"),
                        button -> copyRedirectUri()
                ).bounds(left, top + 72, HALF_BUTTON_WIDTH, 20).build()
        );

        addRenderableWidget(
                Button.builder(
                        Component.translatable("screen.spotify_controller.config.connect"),
                        button -> saveAndConnect()
                ).bounds(left + HALF_BUTTON_WIDTH + BUTTON_GAP, top + 72, HALF_BUTTON_WIDTH, 20).build()
        );

        addRenderableWidget(
                Button.builder(
                        getHudToggleLabel(),
                        button -> {
                            config.hudEnabled = !config.hudEnabled;
                            config.save();
                            button.setMessage(getHudToggleLabel());
                        }
                ).bounds(left, top + 98, HALF_BUTTON_WIDTH, 20).build()
        );

        disconnectButton = addRenderableWidget(
                Button.builder(
                        Component.translatable("screen.spotify_controller.config.disconnect"),
                        button -> disconnect()
                ).bounds(left + HALF_BUTTON_WIDTH + BUTTON_GAP, top + 98, HALF_BUTTON_WIDTH, 20).build()
        );

        addRenderableWidget(
                Button.builder(
                        Component.translatable("screen.spotify_controller.config.reset_hud"),
                        button -> {
                            config.resetHudPosition();
                            this.draggingHud = false;
                            this.statusOverride = "Reset HUD position to (" + config.hudX + ", " + config.hudY + ").";
                        }
                ).bounds(left, top + 124, HALF_BUTTON_WIDTH, 20).build()
        );

        addRenderableWidget(
                Button.builder(
                        Component.translatable("screen.spotify_controller.config.done"),
                        button -> onClose()
                ).bounds(left + HALF_BUTTON_WIDTH + BUTTON_GAP, top + 124, HALF_BUTTON_WIDTH, 20).build()
        );

        updateButtonStates();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);

        int left = (this.width - CONTENT_WIDTH) / 2;
        int top = Math.max(30, (this.height - 238) / 2);

        guiGraphics.centeredText(this.font, this.title, this.width / 2, top - 16, 0xFFFFFFFF);
        guiGraphics.text(
                this.font,
                Component.translatable("screen.spotify_controller.config.client_id"),
                left,
                top + 12,
                0xFFCCCCCC
        );
        guiGraphics.text(
                this.font,
                Component.literal("Redirect URL: " + config.getRedirectUri()),
                left,
                top + 52,
                0xFFA0A0A0
        );

        int cardTop = top + 152;
        int cardBottom = cardTop + 82;
        guiGraphics.fill(left, cardTop, left + CONTENT_WIDTH, cardBottom, 0xAA181818);
        guiGraphics.outline(left, cardTop, CONTENT_WIDTH, cardBottom - cardTop, 0xFF333333);

        boolean connected = config.hasValidAccessToken() || config.hasRefreshToken();
        String connectionLabel = connected ? "Account: Connected" : "Account: Not connected";
        int connectionColor = connected ? 0xFF1DB954 : 0xFFFFB74D;
        guiGraphics.text(this.font, Component.literal(connectionLabel), left + 8, cardTop + 8, connectionColor);

        String displayStatus = statusOverride != null ? statusOverride : poller.getLastStatusMessage();
        List<FormattedCharSequence> statusLines = this.font.split(
                Component.literal("Status: " + displayStatus),
                CONTENT_WIDTH - 16
        );
        int y = cardTop + 22;
        int maxLines = Math.min(2, statusLines.size());
        for (int i = 0; i < maxLines; i++) {
            guiGraphics.text(this.font, statusLines.get(i), left + 8, y, 0xFFE0E0E0);
            y += this.font.lineHeight + 2;
        }

        TrackState track = poller.getCurrentTrack();
        int trackY = cardTop + 50;
        if (track != null) {
            String trackSummary = (track.isPlaying() ? "Now Playing: " : "Paused: ")
                    + track.trackName() + " - " + track.artistName();
            String truncatedTrack = this.font.plainSubstrByWidth(trackSummary, CONTENT_WIDTH - 16);
            guiGraphics.text(this.font, truncatedTrack, left + 8, trackY, 0xFF1DB954);

            String timeText = TrackState.formatDuration(track.getInterpolatedProgressMs())
                    + " / " + TrackState.formatDuration(track.durationMs());
            guiGraphics.text(this.font, timeText, left + 8, trackY + 12, 0xFFB0B0B0);
        } else {
            guiGraphics.text(
                    this.font,
                    Component.literal("No active Spotify track detected."),
                    left + 8,
                    trackY,
                    0xFF888888
            );
        }

        renderDraggableHudPreview(guiGraphics, mouseX, mouseY, track);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            int hudX = SpotifyHudOverlay.clampX(config.hudX, this.width);
            int hudY = SpotifyHudOverlay.clampY(config.hudY, this.height);
            if (SpotifyHudOverlay.isPointInsideCard(event.x(), event.y(), hudX, hudY)) {
                this.draggingHud = true;
                this.dragOffsetX = (int) Math.round(event.x()) - hudX;
                this.dragOffsetY = (int) Math.round(event.y()) - hudY;
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (this.draggingHud && event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            config.hudX = SpotifyHudOverlay.clampX((int) Math.round(event.x()) - dragOffsetX, this.width);
            config.hudY = SpotifyHudOverlay.clampY((int) Math.round(event.y()) - dragOffsetY, this.height);
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (this.draggingHud && event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            this.draggingHud = false;
            config.save();
            this.statusOverride = "Saved HUD position (" + config.hudX + ", " + config.hudY + ").";
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public void onClose() {
        if (this.draggingHud) {
            this.draggingHud = false;
            config.save();
        }
        persistEditedClientIdIfChanged();
        Minecraft client = this.minecraft != null ? this.minecraft : Minecraft.getInstance();
        client.gui.setScreen(this.parent);
    }

    private void renderDraggableHudPreview(
            GuiGraphicsExtractor guiGraphics,
            int mouseX,
            int mouseY,
            TrackState track
    ) {
        int hudX = SpotifyHudOverlay.clampX(config.hudX, this.width);
        int hudY = SpotifyHudOverlay.clampY(config.hudY, this.height);

        TrackState displayTrack = track != null ? track : new TrackState(
                "preview",
                "Spotify HUD Preview",
                "Drag anywhere to move",
                "Spotify Controller",
                "",
                83_000L,
                215_000L,
                true,
                100,
                false,
                "off",
                System.currentTimeMillis()
        );

        if (hudOverlay != null) {
            hudOverlay.renderCard(guiGraphics, this.font, displayTrack, hudX, hudY);
        }

        boolean hudHovered = SpotifyHudOverlay.isPointInsideCard(mouseX, mouseY, hudX, hudY);
        int borderColor = (draggingHud || hudHovered) ? 0xFF1DB954 : 0xAA1DB954;
        guiGraphics.outline(
                hudX - 1,
                hudY - 1,
                SpotifyHudOverlay.CARD_WIDTH + 2,
                SpotifyHudOverlay.CARD_HEIGHT + 2,
                borderColor
        );

        if (draggingHud || hudHovered) {
            guiGraphics.requestCursor(CursorTypes.RESIZE_ALL);
        }

        String baseCaption = draggingHud
                ? "HUD Position: " + hudX + ", " + hudY
                : "Drag to move HUD (" + hudX + ", " + hudY + ")";
        String caption = config.hudEnabled ? baseCaption : baseCaption + " [OFF]";
        int badgeHeight = this.font.lineHeight + 4;
        int badgeWidth = this.font.width(caption) + 8;
        int badgeX = Math.max(2, Math.min(hudX, Math.max(2, this.width - badgeWidth - 2)));
        int badgeY = (hudY + SpotifyHudOverlay.CARD_HEIGHT + 3 + badgeHeight <= this.height - 2)
                ? hudY + SpotifyHudOverlay.CARD_HEIGHT + 3
                : Math.max(2, hudY - badgeHeight - 3);

        guiGraphics.fill(badgeX, badgeY, badgeX + badgeWidth, badgeY + badgeHeight, 0xCC121212);
        guiGraphics.outline(badgeX, badgeY, badgeWidth, badgeHeight, borderColor);
        guiGraphics.text(this.font, caption, badgeX + 4, badgeY + 2, draggingHud ? 0xFF1DB954 : 0xFFE0E0E0);
    }

    private void copyRedirectUri() {
        Minecraft client = this.minecraft != null ? this.minecraft : Minecraft.getInstance();
        client.keyboardHandler.setClipboard(config.getRedirectUri());
        this.statusOverride = "Copied Redirect URL to clipboard!";
    }

    private void saveAndConnect() {
        String enteredId = clientIdBox != null ? clientIdBox.getValue().trim() : "";
        if (enteredId.isEmpty()) {
            this.statusOverride = "Please paste your Spotify Client ID first.";
            return;
        }

        config.setClientId(enteredId);
        authInProgress = true;
        this.statusOverride = "Starting Spotify authorization in your browser...";

        Minecraft client = this.minecraft != null ? this.minecraft : Minecraft.getInstance();
        authService.startAuthFlow(message -> client.execute(() -> {
            this.statusOverride = message;
            this.poller.setStatusMessage(message);
        })).whenComplete((success, throwable) -> client.execute(() -> {
            this.authInProgress = false;
            if (Boolean.TRUE.equals(success)) {
                this.statusOverride = "Connected to Spotify! Fetching playback state...";
                this.poller.refreshNow();
            } else if (throwable != null) {
                this.statusOverride = "Spotify connection failed: " + throwable.getMessage();
            }
            this.updateButtonStates();
        }));
    }

    private void disconnect() {
        config.clearTokens();
        this.statusOverride = "Disconnected from Spotify.";
        this.poller.refreshNow();
        updateButtonStates();
    }

    private void persistEditedClientIdIfChanged() {
        if (clientIdBox == null) {
            return;
        }
        String enteredId = clientIdBox.getValue().trim();
        if (!enteredId.equals(config.clientId)) {
            config.setClientId(enteredId);
        }
    }

    private Component getHudToggleLabel() {
        return Component.translatable(
                config.hudEnabled
                        ? "screen.spotify_controller.config.hud_on"
                        : "screen.spotify_controller.config.hud_off"
        );
    }

    private void updateButtonStates() {
        if (disconnectButton != null) {
            disconnectButton.active = !authInProgress && (config.hasValidAccessToken() || config.hasRefreshToken());
        }
    }
}
