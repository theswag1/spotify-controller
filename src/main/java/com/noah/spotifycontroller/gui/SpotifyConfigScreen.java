package com.noah.spotifycontroller.gui;

import com.noah.spotifycontroller.api.SpotifyPoller;
import com.noah.spotifycontroller.api.TrackState;
import com.noah.spotifycontroller.auth.SpotifyAuthService;
import com.noah.spotifycontroller.config.SpotifyConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/**
 * In-game configuration screen for entering the Spotify Developer Client ID,
 * copying the loopback redirect URI, and authenticating via OAuth PKCE.
 */
public class SpotifyConfigScreen extends Screen {
    private static final int CONTENT_WIDTH = 320;
    private static final int HALF_BUTTON_WIDTH = 155;
    private static final int BUTTON_GAP = 10;

    private final Screen parent;
    private final SpotifyConfig config;
    private final SpotifyAuthService authService;
    private final SpotifyPoller poller;

    private EditBox clientIdBox;
    private Button disconnectButton;
    private String statusOverride;
    private boolean authInProgress = false;

    public SpotifyConfigScreen(
            Screen parent,
            SpotifyConfig config,
            SpotifyAuthService authService,
            SpotifyPoller poller
    ) {
        super(Component.translatable("screen.spotify_controller.config.title"));
        this.parent = parent;
        this.config = config;
        this.authService = authService;
        this.poller = poller;
    }

    @Override
    protected void init() {
        int left = (this.width - CONTENT_WIDTH) / 2;
        int top = Math.max(36, (this.height - 216) / 2);

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

        disconnectButton = addRenderableWidget(
                Button.builder(
                        Component.translatable("screen.spotify_controller.config.disconnect"),
                        button -> disconnect()
                ).bounds(left, top + 98, HALF_BUTTON_WIDTH, 20).build()
        );

        addRenderableWidget(
                Button.builder(
                        Component.translatable("screen.spotify_controller.config.done"),
                        button -> onClose()
                ).bounds(left + HALF_BUTTON_WIDTH + BUTTON_GAP, top + 98, HALF_BUTTON_WIDTH, 20).build()
        );

        updateButtonStates();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);

        int left = (this.width - CONTENT_WIDTH) / 2;
        int top = Math.max(36, (this.height - 216) / 2);

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
                Component.literal("Redirect URI: " + config.getRedirectUri()),
                left,
                top + 52,
                0xFFA0A0A0
        );

        int cardTop = top + 130;
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
    }

    @Override
    public void onClose() {
        persistEditedClientIdIfChanged();
        Minecraft client = this.minecraft != null ? this.minecraft : Minecraft.getInstance();
        client.gui.setScreen(this.parent);
    }

    private void copyRedirectUri() {
        Minecraft client = this.minecraft != null ? this.minecraft : Minecraft.getInstance();
        client.keyboardHandler.setClipboard(config.getRedirectUri());
        this.statusOverride = "Copied Redirect URI to clipboard!";
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

    private void updateButtonStates() {
        if (disconnectButton != null) {
            disconnectButton.active = !authInProgress && (config.hasValidAccessToken() || config.hasRefreshToken());
        }
    }
}
