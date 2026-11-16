package com.noah.spotifycontroller.gui;

import com.noah.spotifycontroller.SpotifyControllerClient;
import com.noah.spotifycontroller.api.SpotifyPoller;
import com.noah.spotifycontroller.auth.SpotifyAuthService;
import com.noah.spotifycontroller.config.SpotifyConfig;
import com.noah.spotifycontroller.hud.SpotifyHudOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Interactive 3-step first-time setup wizard for Minecraft 1.21.1.
 */
public class SpotifySetupGuideScreen extends Screen {
    public static final String DASHBOARD_URL = "https://developer.spotify.com/dashboard";
    public static final int TOTAL_STEPS = 3;

    private static final int PANEL_WIDTH = 364;
    private static final int PANEL_HEIGHT = 236;
    private static final int INNER_WIDTH = PANEL_WIDTH - 28;
    private static final int STEP_BUTTON_WIDTH = 108;
    private static final int STEP_BUTTON_GAP = 6;

    private final Screen parent;
    private final SpotifyConfig config;
    private final SpotifyAuthService authService;
    private final SpotifyPoller poller;
    private final SpotifyHudOverlay hudOverlay;

    private enum StatusKind {
        NEUTRAL,
        SUCCESS,
        ERROR
    }

    private int currentStep = 1;
    private EditBox clientIdBox;
    private Button saveAndConnectButton;
    private String draftClientId;
    private Component statusMessage;
    private StatusKind statusKind = StatusKind.NEUTRAL;
    private boolean authInProgress = false;
    private CompletableFuture<Boolean> currentAuthFuture;

    public SpotifySetupGuideScreen(
            Screen parent,
            SpotifyConfig config,
            SpotifyAuthService authService,
            SpotifyPoller poller
    ) {
        this(parent, config, authService, poller, SpotifyControllerClient.getHudOverlay());
    }

    public SpotifySetupGuideScreen(
            Screen parent,
            SpotifyConfig config,
            SpotifyAuthService authService,
            SpotifyPoller poller,
            SpotifyHudOverlay hudOverlay
    ) {
        super(Component.translatable("screen.spotify_controller.setup.title"));
        this.parent = parent;
        this.config = config;
        this.authService = authService;
        this.poller = poller;
        this.hudOverlay = hudOverlay;
        this.draftClientId = config.clientId != null ? config.clientId : "";
    }

    /**
     * Clamps a 1-based setup wizard step index into the valid range {@code 1..TOTAL_STEPS}.
     */
    public static int clampStep(int step) {
        return Math.max(1, Math.min(TOTAL_STEPS, step));
    }

    @Override
    protected void init() {
        if (clientIdBox != null) {
            draftClientId = clientIdBox.getValue();
        }
        clientIdBox = null;
        saveAndConnectButton = null;

        int left = panelLeft();
        int top = panelTop();
        int innerLeft = left + 14;
        int cardTop = top + 48;
        int cardBottom = top + 196;

        String[] stepKeys = {
                "screen.spotify_controller.setup.tab.step1",
                "screen.spotify_controller.setup.tab.step2",
                "screen.spotify_controller.setup.tab.step3"
        };
        for (int i = 0; i < TOTAL_STEPS; i++) {
            int stepNumber = i + 1;
            int tabX = innerLeft + i * (STEP_BUTTON_WIDTH + STEP_BUTTON_GAP);
            addRenderableWidget(
                    Button.builder(Component.translatable(stepKeys[i]), button -> goToStep(stepNumber))
                            .bounds(tabX, top + 26, STEP_BUTTON_WIDTH, 18)
                            .build()
            );
        }

        if (currentStep == 1) {
            addRenderableWidget(
                    Button.builder(Component.translatable("screen.spotify_controller.setup.open_dashboard"), button -> openDashboard())
                            .bounds(left + (PANEL_WIDTH - 200) / 2, cardBottom - 28, 200, 20)
                            .build()
            );
        } else if (currentStep == 2) {
            addRenderableWidget(
                    Button.builder(Component.translatable("screen.spotify_controller.config.copy_redirect"), button -> copyRedirectUrl())
                            .bounds(innerLeft + 10, cardBottom - 28, 153, 20)
                            .build()
            );
            addRenderableWidget(
                    Button.builder(Component.translatable("screen.spotify_controller.setup.open_dashboard"), button -> openDashboard())
                            .bounds(innerLeft + 173, cardBottom - 28, 153, 20)
                            .build()
            );
        } else {
            clientIdBox = new EditBox(
                    this.font,
                    innerLeft + 10,
                    cardTop + 72,
                    INNER_WIDTH - 20,
                    20,
                    Component.translatable("screen.spotify_controller.config.client_id")
            );
            clientIdBox.setMaxLength(128);
            clientIdBox.setValue(draftClientId != null ? draftClientId : "");
            clientIdBox.setHint(Component.translatable("screen.spotify_controller.config.client_id_hint"));
            clientIdBox.setResponder(value -> draftClientId = value);
            addRenderableWidget(clientIdBox);
            setInitialFocus(clientIdBox);

            saveAndConnectButton = addRenderableWidget(
                    Button.builder(Component.translatable("screen.spotify_controller.config.connect"), button -> saveAndConnect())
                            .bounds(innerLeft + 10, cardTop + 98, 180, 20)
                            .build()
            );
            saveAndConnectButton.active = !authInProgress;

            addRenderableWidget(
                    Button.builder(Component.translatable("screen.spotify_controller.setup.paste_clipboard"), button -> pasteClipboard())
                            .bounds(innerLeft + 196, cardTop + 98, 130, 20)
                            .build()
            );
        }

        int navY = top + 204;
        Button backButton = addRenderableWidget(
                Button.builder(Component.translatable("screen.spotify_controller.setup.back"), button -> goToStep(currentStep - 1))
                        .bounds(innerLeft, navY, 80, 20)
                        .build()
        );
        backButton.active = currentStep > 1;

        addRenderableWidget(
                Button.builder(Component.translatable("screen.spotify_controller.setup.open_config"), button -> openConfigScreen())
                        .bounds(innerLeft + 86, navY, 105, 20)
                        .build()
        );

        if (currentStep < TOTAL_STEPS) {
            addRenderableWidget(
                    Button.builder(Component.translatable("screen.spotify_controller.setup.next_step"), button -> goToStep(currentStep + 1))
                            .bounds(innerLeft + 197, navY, 85, 20)
                            .build()
            );
            addRenderableWidget(
                    Button.builder(Component.translatable("screen.spotify_controller.setup.close"), button -> onClose())
                            .bounds(innerLeft + 288, navY, 48, 20)
                            .build()
            );
        } else {
            addRenderableWidget(
                    Button.builder(Component.translatable("screen.spotify_controller.setup.finish"), button -> onClose())
                            .bounds(innerLeft + 197, navY, 139, 20)
                            .build()
            );
        }
    }

    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        int left = panelLeft();
        int top = panelTop();
        int innerLeft = left + 14;
        int cardTop = top + 48;
        int cardBottom = top + 196;
        int accentColor = config.getAccentColorArgb();

        guiGraphics.fill(left, top, left + PANEL_WIDTH, top + PANEL_HEIGHT, 0xE0101010);
        guiGraphics.renderOutline(left, top, PANEL_WIDTH, PANEL_HEIGHT, 0xFF333333);
        guiGraphics.fill(left, top, left + PANEL_WIDTH, top + 2, accentColor);

        guiGraphics.fill(innerLeft, cardTop, innerLeft + INNER_WIDTH, cardBottom, 0xFF181818);
        guiGraphics.renderOutline(innerLeft, cardTop, INNER_WIDTH, cardBottom - cardTop, 0xFF2E2E2E);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        int left = panelLeft();
        int top = panelTop();
        int innerLeft = left + 14;
        int cardTop = top + 48;
        int cardBottom = top + 196;
        int accentColor = config.getAccentColorArgb();

        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, top + 10, 0xFFFFFFFF);

        int activeTabX = innerLeft + (currentStep - 1) * (STEP_BUTTON_WIDTH + STEP_BUTTON_GAP);
        guiGraphics.renderOutline(activeTabX - 1, top + 25, STEP_BUTTON_WIDTH + 2, 20, accentColor);

        int textLeft = innerLeft + 10;
        int textMaxWidth = INNER_WIDTH - 20;
        if (currentStep == 1) {
            renderStepOneText(guiGraphics, textLeft, cardTop, textMaxWidth, accentColor);
        } else if (currentStep == 2) {
            renderStepTwoText(guiGraphics, textLeft, cardTop, textMaxWidth, accentColor);
        } else {
            renderStepThreeText(guiGraphics, textLeft, cardTop, textMaxWidth, accentColor);
        }

        Component footerStatus = resolveFooterStatusMessage();
        if (footerStatus != null) {
            int statusY = currentStep == 3 ? cardTop + 124 : cardBottom - 44;
            int statusColor = resolveStatusColor(footerStatus, accentColor);
            List<FormattedCharSequence> lines = this.font.split(footerStatus, textMaxWidth);
            int maxLines = Math.min(2, lines.size());
            for (int i = 0; i < maxLines; i++) {
                guiGraphics.drawString(this.font, lines.get(i), textLeft, statusY + i * (this.font.lineHeight + 1), statusColor);
            }
        }
    }

    @Override
    public void onClose() {
        persistDraftClientIdIfNonEmpty();
        clientOrDefault().setScreen(this.parent);
    }

    private void renderStepOneText(
            GuiGraphics guiGraphics,
            int textLeft,
            int cardTop,
            int maxWidth,
            int accentColor
    ) {
        guiGraphics.drawString(this.font, Component.translatable("screen.spotify_controller.setup.step1.heading"), textLeft, cardTop + 10, accentColor);

        int y = cardTop + 26;
        y = drawWrappedStepLine(guiGraphics, Component.translatable("screen.spotify_controller.setup.step1.line1"), textLeft, y, maxWidth, 0xFFE0E0E0);
        y = drawWrappedStepLine(guiGraphics, Component.translatable("screen.spotify_controller.setup.step1.line2"), textLeft, y, maxWidth, 0xFFE0E0E0);
        y = drawWrappedStepLine(guiGraphics, Component.translatable("screen.spotify_controller.setup.step1.line3"), textLeft, y, maxWidth, 0xFFE0E0E0);
        y = drawWrappedStepLine(guiGraphics, Component.translatable("screen.spotify_controller.setup.step1.line4"), textLeft, y, maxWidth, 0xFFE0E0E0);
        drawWrappedStepLine(guiGraphics, Component.translatable("screen.spotify_controller.setup.step1.note"), textLeft, y + 2, maxWidth, 0xFFFFB74D);
    }

    private void renderStepTwoText(
            GuiGraphics guiGraphics,
            int textLeft,
            int cardTop,
            int maxWidth,
            int accentColor
    ) {
        guiGraphics.drawString(this.font, Component.translatable("screen.spotify_controller.setup.step2.heading"), textLeft, cardTop + 8, accentColor);
        guiGraphics.drawString(
                this.font,
                Component.translatable("screen.spotify_controller.setup.step2.line1"),
                textLeft,
                cardTop + 22,
                0xFFE0E0E0
        );

        int boxY = cardTop + 34;
        guiGraphics.fill(textLeft, boxY, textLeft + maxWidth, boxY + 16, 0xFF101010);
        guiGraphics.renderOutline(textLeft, boxY, maxWidth, 16, 0xFF3A3A3A);
        String redirectUri = config.getRedirectUri();
        String clippedUri = this.font.plainSubstrByWidth(redirectUri, maxWidth - 10);
        guiGraphics.drawString(this.font, clippedUri, textLeft + 5, boxY + 4, 0xFF80CBC4);

        int y = cardTop + 54;
        y = drawWrappedStepLine(guiGraphics, Component.translatable("screen.spotify_controller.setup.step2.line2"), textLeft, y, maxWidth, 0xFFE0E0E0);
        drawWrappedStepLine(guiGraphics, Component.translatable("screen.spotify_controller.setup.step2.line3"), textLeft, y, maxWidth, 0xFFE0E0E0);
    }

    private void renderStepThreeText(
            GuiGraphics guiGraphics,
            int textLeft,
            int cardTop,
            int maxWidth,
            int accentColor
    ) {
        guiGraphics.drawString(this.font, Component.translatable("screen.spotify_controller.setup.step3.heading"), textLeft, cardTop + 10, accentColor);
        int y = cardTop + 26;
        y = drawWrappedStepLine(guiGraphics, Component.translatable("screen.spotify_controller.setup.step3.line1"), textLeft, y, maxWidth, 0xFFE0E0E0);
        y = drawWrappedStepLine(guiGraphics, Component.translatable("screen.spotify_controller.setup.step3.line2"), textLeft, y, maxWidth, 0xFFE0E0E0);
        drawWrappedStepLine(guiGraphics, Component.translatable("screen.spotify_controller.setup.step3.line3"), textLeft, y, maxWidth, 0xFFE0E0E0);
    }

    private int drawWrappedStepLine(
            GuiGraphics guiGraphics,
            Component text,
            int x,
            int y,
            int maxWidth,
            int color
    ) {
        List<FormattedCharSequence> lines = this.font.split(text, maxWidth);
        int stepY = this.font.lineHeight + 1;
        for (FormattedCharSequence line : lines) {
            guiGraphics.drawString(this.font, line, x, y, color);
            y += stepY;
        }
        return y + 2;
    }

    private void goToStep(int step) {
        this.currentStep = clampStep(step);
        this.rebuildWidgets();
    }

    private void openDashboard() {
        SpotifyControllerClient.openBrowserUri(URI.create(DASHBOARD_URL));
        setStatus("screen.spotify_controller.setup.status.opened_dashboard", StatusKind.SUCCESS);
    }

    private void copyRedirectUrl() {
        clientOrDefault().keyboardHandler.setClipboard(config.getRedirectUri());
        setStatus("screen.spotify_controller.setup.status.copied_redirect", StatusKind.SUCCESS);
    }

    private void pasteClipboard() {
        String clipboard = clientOrDefault().keyboardHandler.getClipboard();
        String trimmed = clipboard != null ? clipboard.trim() : "";
        if (trimmed.isEmpty()) {
            setStatus("screen.spotify_controller.setup.status.clipboard_empty", StatusKind.ERROR);
            return;
        }
        this.draftClientId = trimmed;
        if (this.clientIdBox != null) {
            this.clientIdBox.setValue(trimmed);
        }
        setStatus("screen.spotify_controller.setup.status.pasted_client_id", StatusKind.SUCCESS);
    }

    private void saveAndConnect() {
        String enteredId = getCurrentClientIdInput();
        if (enteredId.isEmpty()) {
            setStatus("screen.spotify_controller.setup.status.missing_client_id", StatusKind.ERROR);
            return;
        }

        config.setClientId(enteredId);
        authInProgress = true;
        if (saveAndConnectButton != null) {
            saveAndConnectButton.active = false;
        }
        setStatus("screen.spotify_controller.setup.status.starting_auth", StatusKind.NEUTRAL);

        Minecraft client = clientOrDefault();
        CompletableFuture<Boolean> authFuture = authService.startAuthFlow(message -> client.execute(() -> {
            this.statusMessage = Component.literal(message);
            this.statusKind = StatusKind.NEUTRAL;
            this.poller.setStatusMessage(message);
        }));
        this.currentAuthFuture = authFuture;
        authFuture.whenComplete((success, throwable) -> client.execute(() -> {
            if (this.currentAuthFuture != authFuture) {
                return;
            }
            this.authInProgress = false;
            if (this.saveAndConnectButton != null) {
                this.saveAndConnectButton.active = true;
            }
            if (Boolean.TRUE.equals(success)) {
                setStatus("screen.spotify_controller.setup.status.connected_finish", StatusKind.SUCCESS);
                this.poller.refreshNow();
            } else if (throwable != null) {
                setStatus("screen.spotify_controller.setup.status.connect_failed", StatusKind.ERROR, throwable.getMessage());
            }
        }));
    }

    private void openConfigScreen() {
        persistDraftClientIdIfNonEmpty();
        Screen configParent = (this.parent instanceof SpotifyConfigScreen existingConfig)
                ? existingConfig.getParent()
                : this.parent;
        clientOrDefault().setScreen(new SpotifyConfigScreen(
                configParent,
                config,
                authService,
                poller,
                hudOverlay
        ));
    }

    private void persistDraftClientIdIfNonEmpty() {
        String current = getCurrentClientIdInput();
        if (!current.isEmpty() && !current.equals(config.clientId)) {
            config.setClientId(current);
        }
    }

    private String getCurrentClientIdInput() {
        if (clientIdBox != null) {
            return clientIdBox.getValue().trim();
        }
        return draftClientId != null ? draftClientId.trim() : "";
    }

    private void setStatus(String key, StatusKind kind, Object... args) {
        this.statusMessage = Component.translatable(key, args);
        this.statusKind = kind;
    }

    private Component resolveFooterStatusMessage() {
        if (statusMessage != null) {
            return statusMessage;
        }
        if (currentStep == 3 && (config.hasValidAccessToken() || config.hasRefreshToken())) {
            return Component.translatable("screen.spotify_controller.setup.status.account_ready");
        }
        return null;
    }

    private int resolveStatusColor(Component message, int accentColor) {
        if (statusMessage == null) {
            return accentColor;
        }
        if (statusKind == StatusKind.ERROR) {
            return 0xFFFF8A80;
        }
        if (statusKind == StatusKind.SUCCESS) {
            return accentColor;
        }
        String lower = message.getString().toLowerCase();
        if (lower.contains("failed") || lower.contains("rejected") || lower.contains("missing") || lower.contains("timed out")) {
            return 0xFFFF8A80;
        }
        if (lower.contains("connected")) {
            return accentColor;
        }
        return 0xFFFFD54F;
    }

    private int panelLeft() {
        return Math.max(8, (this.width - PANEL_WIDTH) / 2);
    }

    private int panelTop() {
        return Math.max(8, (this.height - PANEL_HEIGHT) / 2);
    }

    private Minecraft clientOrDefault() {
        return this.minecraft != null ? this.minecraft : Minecraft.getInstance();
    }
}
