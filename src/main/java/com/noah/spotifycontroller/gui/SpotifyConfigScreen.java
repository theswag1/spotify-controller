package com.noah.spotifycontroller.gui;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import com.noah.spotifycontroller.api.SpotifyPoller;
import com.noah.spotifycontroller.api.TrackState;
import com.noah.spotifycontroller.auth.SpotifyAuthService;
import com.noah.spotifycontroller.config.SpotifyConfig;
import com.noah.spotifycontroller.hud.SpotifyHudOverlay;
import com.noah.spotifycontroller.util.MinecraftGuiCompat;
import com.noah.spotifycontroller.util.SpotifyI18n;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * In-game configuration screen for entering the Spotify Developer Client ID,
 * copying the loopback redirect URI, authenticating via OAuth PKCE, and
 * positioning the in-game HUD widget via drag-and-drop.
 */
public class SpotifyConfigScreen extends Screen {
    private record ColorPreset(String translationKey, String hex) {
    }

    private static final List<ColorPreset> COLOR_PRESETS = List.of(
            new ColorPreset("screen.spotify_controller.config.color.green", "#1DB954"),
            new ColorPreset("screen.spotify_controller.config.color.cyan", "#00E5FF"),
            new ColorPreset("screen.spotify_controller.config.color.blue", "#448AFF"),
            new ColorPreset("screen.spotify_controller.config.color.purple", "#BB86FC"),
            new ColorPreset("screen.spotify_controller.config.color.pink", "#FF4081"),
            new ColorPreset("screen.spotify_controller.config.color.red", "#FF5252"),
            new ColorPreset("screen.spotify_controller.config.color.orange", "#FFAB40"),
            new ColorPreset("screen.spotify_controller.config.color.white", "#FFFFFF")
    );

    private static final int CONTENT_WIDTH = 320;
    private static final int HALF_BUTTON_WIDTH = 155;
    private static final int BUTTON_GAP = 10;

    private final Screen parent;
    private final SpotifyConfig config;
    private final SpotifyAuthService authService;
    private final SpotifyPoller poller;
    private final SpotifyHudOverlay hudOverlay;

    private EditBox clientIdBox;
    private EditBox accentColorBox;
    private Button connectButton;
    private Button colorPresetButton;
    private Button disconnectButton;
    private Component statusOverride;
    private boolean authInProgress = false;
    private CompletableFuture<Boolean> currentAuthFuture;
    private boolean updatingColorBoxProgrammatically = false;
    private boolean draggingHud = false;
    private boolean dirtyConfig = false;
    private int dragOffsetX = 0;
    private int dragOffsetY = 0;

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
        int top = computeTop();

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
        clientIdBox.setHint(Component.translatable("screen.spotify_controller.config.client_id_hint"));
        addRenderableWidget(clientIdBox);
        setInitialFocus(clientIdBox);

        addRenderableWidget(
                Button.builder(
                        Component.translatable("screen.spotify_controller.config.copy_redirect"),
                        button -> copyRedirectUri()
                ).bounds(left, top + 72, HALF_BUTTON_WIDTH, 20).build()
        );

        connectButton = addRenderableWidget(
                Button.builder(
                        Component.translatable("screen.spotify_controller.config.connect"),
                        button -> saveAndConnect()
                ).bounds(left + HALF_BUTTON_WIDTH + BUTTON_GAP, top + 72, HALF_BUTTON_WIDTH, 20).build()
        );

        addRenderableWidget(
                Button.builder(
                        getHudToggleLabel(),
                        button -> {
                            cycleHudMode();
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

        colorPresetButton = addRenderableWidget(
                Button.builder(
                        getColorPresetButtonLabel(),
                        button -> cycleAccentColorPreset()
                ).bounds(left, top + 124, HALF_BUTTON_WIDTH, 20).build()
        );

        String previousHex = accentColorBox != null
                ? accentColorBox.getValue()
                : SpotifyConfig.normalizeHexColor(config.accentColorHex);
        accentColorBox = new EditBox(
                this.font,
                left + HALF_BUTTON_WIDTH + BUTTON_GAP,
                top + 124,
                HALF_BUTTON_WIDTH - 24,
                20,
                Component.translatable("screen.spotify_controller.config.accent_color_box")
        );
        accentColorBox.setMaxLength(7);
        accentColorBox.setHint(Component.translatable("screen.spotify_controller.config.accent_color_hint"));
        updatingColorBoxProgrammatically = true;
        accentColorBox.setValue(previousHex);
        updatingColorBoxProgrammatically = false;
        accentColorBox.setResponder(value -> {
            if (!updatingColorBoxProgrammatically && SpotifyConfig.isValidHexColor(value)) {
                config.accentColorHex = SpotifyConfig.normalizeHexColor(value);
                this.dirtyConfig = true;
                if (colorPresetButton != null) {
                    colorPresetButton.setMessage(getColorPresetButtonLabel());
                }
            }
        });
        addRenderableWidget(accentColorBox);

        addRenderableWidget(
                Button.builder(
                        Component.translatable("screen.spotify_controller.config.reset_hud"),
                        button -> {
                            config.resetHudPosition();
                            this.draggingHud = false;
                            this.statusOverride = Component.translatable(
                                    "screen.spotify_controller.config.status.reset_hud",
                                    config.hudX,
                                    config.hudY
                            );
                        }
                ).bounds(left, top + 150, 100, 20).build()
        );

        addRenderableWidget(
                Button.builder(
                        Component.translatable("screen.spotify_controller.config.setup_guide"),
                        button -> openSetupGuide()
                ).bounds(left + 110, top + 150, 100, 20).build()
        );

        addRenderableWidget(
                Button.builder(
                        Component.translatable("screen.spotify_controller.config.done"),
                        button -> onClose()
                ).bounds(left + 220, top + 150, 100, 20).build()
        );

        updateButtonStates();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);

        int left = (this.width - CONTENT_WIDTH) / 2;
        int top = computeTop();

        guiGraphics.centeredText(this.font, this.title, this.width / 2, Math.max(4, top - 14), 0xFFFFFFFF);
        guiGraphics.text(
                this.font,
                Component.translatable("screen.spotify_controller.config.client_id"),
                left,
                top + 12,
                0xFFCCCCCC
        );
        guiGraphics.text(
                this.font,
                Component.translatable("screen.spotify_controller.config.redirect_url", config.getRedirectUri()),
                left,
                top + 52,
                0xFFA0A0A0
        );

        int swatchX = left + CONTENT_WIDTH - 20;
        int swatchY = top + 124;
        guiGraphics.fill(swatchX, swatchY, swatchX + 20, swatchY + 20, config.getAccentColorArgb());
        guiGraphics.outline(swatchX, swatchY, 20, 20, 0xFFFFFFFF);

        int cardTop = top + 176;
        int cardBottom = Math.min(this.height - 4, cardTop + 78);
        guiGraphics.fill(left, cardTop, left + CONTENT_WIDTH, cardBottom, 0xAA181818);
        guiGraphics.outline(left, cardTop, CONTENT_WIDTH, cardBottom - cardTop, 0xFF333333);

        boolean connected = config.hasValidAccessToken() || config.hasRefreshToken();
        Component connectionLabel = Component.translatable(connected
                ? "screen.spotify_controller.config.account_connected"
                : "screen.spotify_controller.config.account_disconnected");
        int connectionColor = connected ? 0xFF1DB954 : 0xFFFFB74D;
        guiGraphics.text(this.font, connectionLabel, left + 8, cardTop + 8, connectionColor);

        Component displayStatus = statusOverride != null
                ? statusOverride
                : Component.literal(poller.getLastStatusMessage());
        List<FormattedCharSequence> statusLines = this.font.split(
                Component.translatable("screen.spotify_controller.config.status_label", displayStatus),
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
            String trackSummary = SpotifyI18n.translate(
                    track.isPlaying()
                            ? "screen.spotify_controller.config.now_playing"
                            : "screen.spotify_controller.config.paused",
                    track.trackName(),
                    track.artistName()
            );
            String truncatedTrack = this.font.plainSubstrByWidth(trackSummary, CONTENT_WIDTH - 16);
            guiGraphics.text(this.font, truncatedTrack, left + 8, trackY, config.getAccentColorArgb());

            String timeText = TrackState.formatDuration(track.getInterpolatedProgressMs())
                    + " / " + TrackState.formatDuration(track.durationMs());
            guiGraphics.text(this.font, timeText, left + 8, trackY + 12, 0xFFB0B0B0);
        } else {
            guiGraphics.text(
                    this.font,
                    Component.translatable("screen.spotify_controller.config.no_track"),
                    left + 8,
                    trackY,
                    0xFF888888
            );
        }

        renderDraggableHudPreview(guiGraphics, mouseX, mouseY, track);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            float scale = SpotifyConfig.clampHudScale(config.hudScale);
            int hudX = SpotifyHudOverlay.clampX(config.hudX, this.width, scale, config.hudCompact);
            int hudY = SpotifyHudOverlay.clampY(config.hudY, this.height, scale, config.hudCompact);
            if (SpotifyHudOverlay.isPointInsideCard(event.x(), event.y(), hudX, hudY, scale, config.hudCompact)) {
                this.draggingHud = true;
                this.dragOffsetX = (int) Math.round(event.x()) - hudX;
                this.dragOffsetY = (int) Math.round(event.y()) - hudY;
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (this.draggingHud && event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            float scale = SpotifyConfig.clampHudScale(config.hudScale);
            config.hudX = SpotifyHudOverlay.clampX((int) Math.round(event.x()) - dragOffsetX, this.width, scale, config.hudCompact);
            config.hudY = SpotifyHudOverlay.clampY((int) Math.round(event.y()) - dragOffsetY, this.height, scale, config.hudCompact);
            this.dirtyConfig = true;
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (this.draggingHud && event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            this.draggingHud = false;
            this.dirtyConfig = false;
            config.save();
            this.statusOverride = Component.translatable(
                    "screen.spotify_controller.config.status.saved_hud",
                    config.hudX,
                    config.hudY
            );
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0.0) {
            float currentScale = SpotifyConfig.clampHudScale(config.hudScale);
            int hudX = SpotifyHudOverlay.clampX(config.hudX, this.width, currentScale, config.hudCompact);
            int hudY = SpotifyHudOverlay.clampY(config.hudY, this.height, currentScale, config.hudCompact);
            if (this.draggingHud || SpotifyHudOverlay.isPointInsideCard(mouseX, mouseY, hudX, hudY, currentScale, config.hudCompact)) {
                float delta = scrollY > 0.0 ? SpotifyConfig.HUD_SCALE_STEP : -SpotifyConfig.HUD_SCALE_STEP;
                float nextScale = SpotifyConfig.clampHudScale(currentScale + delta);
                config.hudScale = nextScale;
                config.hudX = SpotifyHudOverlay.clampX(config.hudX, this.width, nextScale, config.hudCompact);
                config.hudY = SpotifyHudOverlay.clampY(config.hudY, this.height, nextScale, config.hudCompact);
                this.dirtyConfig = true;
                int percent = Math.round(nextScale * 100.0f);
                this.statusOverride = Component.translatable("screen.spotify_controller.config.status.hud_size", percent);
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void onClose() {
        flushPendingChanges();
        Minecraft client = this.minecraft != null ? this.minecraft : Minecraft.getInstance();
        MinecraftGuiCompat.setScreen(client, this.parent);
    }

    public Screen getParent() {
        return this.parent;
    }

    private void openSetupGuide() {
        flushPendingChanges();
        // Clear cached box so returning from the wizard picks up any updated Client ID from config
        this.clientIdBox = null;
        Minecraft client = this.minecraft != null ? this.minecraft : Minecraft.getInstance();
        MinecraftGuiCompat.setScreen(client, new SpotifySetupGuideScreen(this, config, authService, poller, hudOverlay));
    }

    private void flushPendingChanges() {
        this.draggingHud = false;
        // Persist HUD/appearance edits without saving unconfirmed Client ID edits so tokens stay intact.
        if (this.dirtyConfig) {
            config.save();
        }
        this.dirtyConfig = false;
    }

    private int computeTop() {
        return Math.max(16, (this.height - 254) / 2);
    }

    private void renderDraggableHudPreview(
            GuiGraphicsExtractor guiGraphics,
            int mouseX,
            int mouseY,
            TrackState track
    ) {
        float scale = SpotifyConfig.clampHudScale(config.hudScale);
        int hudX = SpotifyHudOverlay.clampX(config.hudX, this.width, scale, config.hudCompact);
        int hudY = SpotifyHudOverlay.clampY(config.hudY, this.height, scale, config.hudCompact);
        int scaledWidth = SpotifyHudOverlay.getScaledWidth(scale, config.hudCompact);
        int scaledHeight = SpotifyHudOverlay.getScaledHeight(scale, config.hudCompact);

        TrackState displayTrack = track != null ? track : new TrackState(
                "preview",
                SpotifyI18n.translate("screen.spotify_controller.config.preview.title"),
                SpotifyI18n.translate("screen.spotify_controller.config.preview.artist"),
                SpotifyI18n.translate("screen.spotify_controller.config.preview.album"),
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
            hudOverlay.renderCard(guiGraphics, this.font, displayTrack, hudX, hudY, scale);
        }

        boolean hudHovered = SpotifyHudOverlay.isPointInsideCard(mouseX, mouseY, hudX, hudY, scale, config.hudCompact);
        int accentArgb = config.getAccentColorArgb();
        int borderColor = (draggingHud || hudHovered) ? accentArgb : (0xAA000000 | (accentArgb & 0x00FFFFFF));
        guiGraphics.outline(
                hudX - 1,
                hudY - 1,
                scaledWidth + 2,
                scaledHeight + 2,
                borderColor
        );

        if (draggingHud || hudHovered) {
            guiGraphics.requestCursor(CursorTypes.RESIZE_ALL);
        }

        int scalePercent = Math.round(scale * 100.0f);
        String baseCaption = draggingHud
                ? SpotifyI18n.translate("screen.spotify_controller.config.hud_badge.dragging", hudX, hudY, scalePercent)
                : SpotifyI18n.translate("screen.spotify_controller.config.hud_badge.hint", scalePercent);
        String caption = config.hudEnabled
                ? baseCaption
                : SpotifyI18n.translate("screen.spotify_controller.config.hud_badge.off_suffix", baseCaption);
        int badgeHeight = this.font.lineHeight + 4;
        int badgeWidth = this.font.width(caption) + 8;
        int badgeX = Math.max(2, Math.min(hudX, Math.max(2, this.width - badgeWidth - 2)));
        int badgeY = (hudY + scaledHeight + 3 + badgeHeight <= this.height - 2)
                ? hudY + scaledHeight + 3
                : Math.max(2, hudY - badgeHeight - 3);

        guiGraphics.fill(badgeX, badgeY, badgeX + badgeWidth, badgeY + badgeHeight, 0xCC121212);
        guiGraphics.outline(badgeX, badgeY, badgeWidth, badgeHeight, borderColor);
        guiGraphics.text(this.font, caption, badgeX + 4, badgeY + 2, draggingHud ? accentArgb : 0xFFE0E0E0);
    }

    private void cycleAccentColorPreset() {
        String currentHex = SpotifyConfig.normalizeHexColor(config.accentColorHex);
        int currentIndex = -1;
        for (int i = 0; i < COLOR_PRESETS.size(); i++) {
            if (COLOR_PRESETS.get(i).hex().equalsIgnoreCase(currentHex)) {
                currentIndex = i;
                break;
            }
        }
        ColorPreset next = COLOR_PRESETS.get((currentIndex + 1) % COLOR_PRESETS.size());
        config.setAccentColorHex(next.hex());

        updatingColorBoxProgrammatically = true;
        if (accentColorBox != null) {
            accentColorBox.setValue(next.hex());
        }
        updatingColorBoxProgrammatically = false;

        if (colorPresetButton != null) {
            colorPresetButton.setMessage(getColorPresetButtonLabel());
        }
        this.statusOverride = Component.translatable(
                "screen.spotify_controller.config.status.color_set",
                Component.translatable(next.translationKey()),
                next.hex()
        );
    }

    private Component getColorPresetButtonLabel() {
        String normalized = SpotifyConfig.normalizeHexColor(config.accentColorHex);
        for (ColorPreset preset : COLOR_PRESETS) {
            if (preset.hex().equalsIgnoreCase(normalized)) {
                return Component.translatable(
                        "screen.spotify_controller.config.hud_color_preset",
                        Component.translatable(preset.translationKey())
                );
            }
        }
        return Component.translatable("screen.spotify_controller.config.hud_color_custom");
    }

    private void copyRedirectUri() {
        Minecraft client = this.minecraft != null ? this.minecraft : Minecraft.getInstance();
        client.keyboardHandler.setClipboard(config.getRedirectUri());
        this.statusOverride = Component.translatable("screen.spotify_controller.config.status.copied_redirect");
    }

    private void saveAndConnect() {
        String enteredId = clientIdBox != null ? clientIdBox.getValue().trim() : "";
        if (enteredId.isEmpty()) {
            this.statusOverride = Component.translatable("screen.spotify_controller.config.status.missing_client_id");
            return;
        }

        config.setClientId(enteredId);
        authInProgress = true;
        updateButtonStates();
        this.statusOverride = Component.translatable("screen.spotify_controller.config.status.starting_auth");

        Minecraft client = this.minecraft != null ? this.minecraft : Minecraft.getInstance();
        CompletableFuture<Boolean> authFuture = authService.startAuthFlow(message -> client.execute(() -> {
            this.statusOverride = Component.literal(message);
            this.poller.setStatusMessage(message);
        }));
        this.currentAuthFuture = authFuture;
        authFuture.whenComplete((success, throwable) -> client.execute(() -> {
            if (this.currentAuthFuture != authFuture) {
                return;
            }
            this.authInProgress = false;
            if (Boolean.TRUE.equals(success)) {
                this.statusOverride = Component.translatable("screen.spotify_controller.config.status.connected");
                this.poller.refreshNow();
            } else if (throwable != null) {
                this.statusOverride = Component.translatable(
                        "screen.spotify_controller.config.status.connect_failed",
                        throwable.getMessage()
                );
            }
            this.updateButtonStates();
        }));
    }

    private void disconnect() {
        config.clearTokens();
        this.statusOverride = Component.translatable("screen.spotify_controller.config.status.disconnected");
        this.poller.refreshNow();
        updateButtonStates();
    }

    /**
     * Cycles the HUD widget Full -> Compact -> Off -> Full. Off keeps the chosen style for the keybind toggle.
     */
    private void cycleHudMode() {
        if (!config.hudEnabled) {
            config.hudEnabled = true;
            config.hudCompact = false;
        } else if (!config.hudCompact) {
            config.hudCompact = true;
        } else {
            config.hudEnabled = false;
        }
        // Keep the widget on screen when switching between the tall card and the one-line compact box.
        config.hudX = SpotifyHudOverlay.clampX(config.hudX, this.width, config.hudScale, config.hudCompact);
        config.hudY = SpotifyHudOverlay.clampY(config.hudY, this.height, config.hudScale, config.hudCompact);
        config.save();
    }

    private Component getHudToggleLabel() {
        if (!config.hudEnabled) {
            return Component.translatable("screen.spotify_controller.config.hud_off");
        }
        return Component.translatable(config.hudCompact
                ? "screen.spotify_controller.config.hud_compact"
                : "screen.spotify_controller.config.hud_on");
    }

    private void updateButtonStates() {
        if (connectButton != null) {
            connectButton.active = !authInProgress;
        }
        if (disconnectButton != null) {
            disconnectButton.active = !authInProgress && (config.hasValidAccessToken() || config.hasRefreshToken());
        }
    }
}
