package com.noah.spotifycontroller.gui;

import com.mojang.blaze3d.Blaze3D;
import com.noah.spotifycontroller.SpotifyControllerClient;
import com.noah.spotifycontroller.api.SpotifyPoller;
import com.noah.spotifycontroller.auth.SpotifyAuthService;
import com.noah.spotifycontroller.config.SpotifyConfig;
import com.noah.spotifycontroller.hud.SpotifyHudOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.net.URI;
import java.util.List;

/**
 * Interactive 3-step in-game wizard guiding players through creating a Spotify Developer app,
 * registering the loopback Redirect URL, and connecting their Spotify account.
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

    private int currentStep = 1;
    private EditBox clientIdBox;
    private Button saveAndConnectButton;
    private String draftClientId;
    private String statusMessage = "";
    private boolean authInProgress = false;

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

        // Top step selector tabs
        String[] stepLabels = {"1. Create App", "2. Redirect URL", "3. Connect"};
        for (int i = 0; i < TOTAL_STEPS; i++) {
            int stepNumber = i + 1;
            int tabX = innerLeft + i * (STEP_BUTTON_WIDTH + STEP_BUTTON_GAP);
            addRenderableWidget(
                    Button.builder(Component.literal(stepLabels[i]), button -> goToStep(stepNumber))
                            .bounds(tabX, top + 26, STEP_BUTTON_WIDTH, 18)
                            .build()
            );
        }

        // Step-specific action widgets
        if (currentStep == 1) {
            addRenderableWidget(
                    Button.builder(Component.literal("Open Spotify Dashboard"), button -> openDashboard())
                            .bounds(left + (PANEL_WIDTH - 200) / 2, cardBottom - 28, 200, 20)
                            .build()
            );
        } else if (currentStep == 2) {
            addRenderableWidget(
                    Button.builder(Component.literal("Copy Redirect URL"), button -> copyRedirectUrl())
                            .bounds(innerLeft + 10, cardBottom - 28, 153, 20)
                            .build()
            );
            addRenderableWidget(
                    Button.builder(Component.literal("Open Spotify Dashboard"), button -> openDashboard())
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
            clientIdBox.setHint(Component.literal("Paste your Spotify Client ID here..."));
            clientIdBox.setResponder(value -> draftClientId = value);
            addRenderableWidget(clientIdBox);
            setInitialFocus(clientIdBox);

            saveAndConnectButton = addRenderableWidget(
                    Button.builder(Component.literal("Save & Connect Spotify"), button -> saveAndConnect())
                            .bounds(innerLeft + 10, cardTop + 98, 180, 20)
                            .build()
            );
            saveAndConnectButton.active = !authInProgress;

            addRenderableWidget(
                    Button.builder(Component.literal("Paste Clipboard"), button -> pasteClipboard())
                            .bounds(innerLeft + 196, cardTop + 98, 130, 20)
                            .build()
            );
        }

        // Bottom wizard navigation bar
        int navY = top + 204;
        Button backButton = addRenderableWidget(
                Button.builder(Component.literal("< Back"), button -> goToStep(currentStep - 1))
                        .bounds(innerLeft, navY, 80, 20)
                        .build()
        );
        backButton.active = currentStep > 1;

        addRenderableWidget(
                Button.builder(Component.literal("Open Config"), button -> openConfigScreen())
                        .bounds(innerLeft + 86, navY, 105, 20)
                        .build()
        );

        if (currentStep < TOTAL_STEPS) {
            addRenderableWidget(
                    Button.builder(Component.literal("Next Step >"), button -> goToStep(currentStep + 1))
                            .bounds(innerLeft + 197, navY, 85, 20)
                            .build()
            );
            addRenderableWidget(
                    Button.builder(Component.literal("Close"), button -> onClose())
                            .bounds(innerLeft + 288, navY, 48, 20)
                            .build()
            );
        } else {
            addRenderableWidget(
                    Button.builder(Component.literal("Finish"), button -> onClose())
                            .bounds(innerLeft + 197, navY, 139, 20)
                            .build()
            );
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
        int left = panelLeft();
        int top = panelTop();
        int innerLeft = left + 14;
        int cardTop = top + 48;
        int cardBottom = top + 196;
        int accentColor = config.getAccentColorArgb();

        // Render outer panel before widgets so buttons and inputs sit cleanly on top
        guiGraphics.fill(left, top, left + PANEL_WIDTH, top + PANEL_HEIGHT, 0xE0101010);
        guiGraphics.outline(left, top, PANEL_WIDTH, PANEL_HEIGHT, 0xFF333333);
        guiGraphics.fill(left, top, left + PANEL_WIDTH, top + 2, accentColor);

        // Instruction card surface
        guiGraphics.fill(innerLeft, cardTop, innerLeft + INNER_WIDTH, cardBottom, 0xFF181818);
        guiGraphics.outline(innerLeft, cardTop, INNER_WIDTH, cardBottom - cardTop, 0xFF2E2E2E);

        super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);

        guiGraphics.centeredText(this.font, this.title, this.width / 2, top + 10, 0xFFFFFFFF);

        // Highlight active step tab button
        int activeTabX = innerLeft + (currentStep - 1) * (STEP_BUTTON_WIDTH + STEP_BUTTON_GAP);
        guiGraphics.outline(activeTabX - 1, top + 25, STEP_BUTTON_WIDTH + 2, 20, accentColor);

        int textLeft = innerLeft + 10;
        int textMaxWidth = INNER_WIDTH - 20;
        if (currentStep == 1) {
            renderStepOneText(guiGraphics, textLeft, cardTop, textMaxWidth, accentColor);
        } else if (currentStep == 2) {
            renderStepTwoText(guiGraphics, textLeft, cardTop, textMaxWidth, accentColor);
        } else {
            renderStepThreeText(guiGraphics, textLeft, cardTop, textMaxWidth, accentColor);
        }

        String footerStatus = resolveFooterStatusMessage();
        if (!footerStatus.isEmpty()) {
            int statusY = currentStep == 3 ? cardTop + 124 : cardBottom - 44;
            int statusColor = resolveStatusColor(footerStatus, accentColor);
            List<FormattedCharSequence> lines = this.font.split(Component.literal(footerStatus), textMaxWidth);
            int maxLines = Math.min(2, lines.size());
            for (int i = 0; i < maxLines; i++) {
                guiGraphics.text(this.font, lines.get(i), textLeft, statusY + i * (this.font.lineHeight + 1), statusColor);
            }
        }
    }

    @Override
    public void onClose() {
        persistDraftClientIdIfNonEmpty();
        clientOrDefault().gui.setScreen(this.parent);
    }

    private void renderStepOneText(
            GuiGraphicsExtractor guiGraphics,
            int textLeft,
            int cardTop,
            int maxWidth,
            int accentColor
    ) {
        guiGraphics.text(this.font, "Step 1 of 3: Create a Spotify Developer App", textLeft, cardTop + 10, accentColor);

        int y = cardTop + 26;
        y = drawWrappedStepLine(guiGraphics, "1. Click \"Open Spotify Dashboard\" below and log in.", textLeft, y, maxWidth, 0xFFE0E0E0);
        y = drawWrappedStepLine(guiGraphics, "2. Click \"Create app\" in the top-right corner.", textLeft, y, maxWidth, 0xFFE0E0E0);
        y = drawWrappedStepLine(guiGraphics, "3. Enter any App name & description (e.g. \"MC Spotify\").", textLeft, y, maxWidth, 0xFFE0E0E0);
        y = drawWrappedStepLine(
                guiGraphics,
                "4. Under \"Which API/SDKs are you planning to use?\", check \"Web API\".",
                textLeft,
                y,
                maxWidth,
                0xFFE0E0E0
        );
        drawWrappedStepLine(
                guiGraphics,
                "Note: Spotify Premium is required for playback control.",
                textLeft,
                y + 2,
                maxWidth,
                0xFFFFB74D
        );
    }

    private void renderStepTwoText(
            GuiGraphicsExtractor guiGraphics,
            int textLeft,
            int cardTop,
            int maxWidth,
            int accentColor
    ) {
        guiGraphics.text(this.font, "Step 2 of 3: Add the Redirect URL", textLeft, cardTop + 8, accentColor);
        guiGraphics.text(
                this.font,
                "1. Click \"Copy Redirect URL\" below to copy this exact address:",
                textLeft,
                cardTop + 22,
                0xFFE0E0E0
        );

        int boxY = cardTop + 34;
        guiGraphics.fill(textLeft, boxY, textLeft + maxWidth, boxY + 16, 0xFF101010);
        guiGraphics.outline(textLeft, boxY, maxWidth, 16, 0xFF3A3A3A);
        String redirectUri = config.getRedirectUri();
        String clippedUri = this.font.plainSubstrByWidth(redirectUri, maxWidth - 10);
        guiGraphics.text(this.font, clippedUri, textLeft + 5, boxY + 4, 0xFF80CBC4);

        int y = cardTop + 54;
        y = drawWrappedStepLine(
                guiGraphics,
                "2. Paste it into the \"Redirect URIs\" field in the dashboard and click \"Add\".",
                textLeft,
                y,
                maxWidth,
                0xFFE0E0E0
        );
        drawWrappedStepLine(
                guiGraphics,
                "3. Check the Developer Terms of Service and click \"Save\" at the bottom.",
                textLeft,
                y,
                maxWidth,
                0xFFE0E0E0
        );
    }

    private void renderStepThreeText(
            GuiGraphicsExtractor guiGraphics,
            int textLeft,
            int cardTop,
            int maxWidth,
            int accentColor
    ) {
        guiGraphics.text(this.font, "Step 3 of 3: Paste Your Client ID & Connect", textLeft, cardTop + 10, accentColor);
        int y = cardTop + 26;
        y = drawWrappedStepLine(
                guiGraphics,
                "1. In your Spotify app dashboard, click the \"Settings\" button.",
                textLeft,
                y,
                maxWidth,
                0xFFE0E0E0
        );
        y = drawWrappedStepLine(
                guiGraphics,
                "2. Copy the \"Client ID\" (you do not need the Client Secret).",
                textLeft,
                y,
                maxWidth,
                0xFFE0E0E0
        );
        drawWrappedStepLine(
                guiGraphics,
                "3. Paste it below and click \"Save & Connect Spotify\":",
                textLeft,
                y,
                maxWidth,
                0xFFE0E0E0
        );
    }

    private int drawWrappedStepLine(
            GuiGraphicsExtractor guiGraphics,
            String text,
            int x,
            int y,
            int maxWidth,
            int color
    ) {
        List<FormattedCharSequence> lines = this.font.split(Component.literal(text), maxWidth);
        int stepY = this.font.lineHeight + 1;
        for (FormattedCharSequence line : lines) {
            guiGraphics.text(this.font, line, x, y, color);
            y += stepY;
        }
        return y + 2;
    }

    private void goToStep(int step) {
        this.currentStep = clampStep(step);
        this.rebuildWidgets();
    }

    private void openDashboard() {
        Blaze3D.openUri(URI.create(DASHBOARD_URL));
        this.statusMessage = "Opened Spotify Developer Dashboard in your browser.";
    }

    private void copyRedirectUrl() {
        clientOrDefault().keyboardHandler.setClipboard(config.getRedirectUri());
        this.statusMessage = "Copied Redirect URL to clipboard!";
    }

    private void pasteClipboard() {
        String clipboard = clientOrDefault().keyboardHandler.getClipboard();
        String trimmed = clipboard != null ? clipboard.trim() : "";
        if (trimmed.isEmpty()) {
            this.statusMessage = "Clipboard is empty.";
            return;
        }
        this.draftClientId = trimmed;
        if (this.clientIdBox != null) {
            this.clientIdBox.setValue(trimmed);
        }
        this.statusMessage = "Pasted Client ID from clipboard.";
    }

    private void saveAndConnect() {
        String enteredId = getCurrentClientIdInput();
        if (enteredId.isEmpty()) {
            this.statusMessage = "Please paste your Spotify Client ID first.";
            return;
        }

        config.setClientId(enteredId);
        authInProgress = true;
        if (saveAndConnectButton != null) {
            saveAndConnectButton.active = false;
        }
        this.statusMessage = "Starting Spotify authorization in your browser...";

        Minecraft client = clientOrDefault();
        authService.startAuthFlow(message -> client.execute(() -> {
            this.statusMessage = message;
            this.poller.setStatusMessage(message);
        })).whenComplete((success, throwable) -> client.execute(() -> {
            this.authInProgress = false;
            if (this.saveAndConnectButton != null) {
                this.saveAndConnectButton.active = true;
            }
            if (Boolean.TRUE.equals(success)) {
                this.statusMessage = "Connected to Spotify! Click Finish to start listening.";
                this.poller.refreshNow();
            } else if (throwable != null) {
                this.statusMessage = "Spotify connection failed: " + throwable.getMessage();
            }
        }));
    }

    private void openConfigScreen() {
        persistDraftClientIdIfNonEmpty();
        Screen configParent = (this.parent instanceof SpotifyConfigScreen existingConfig)
                ? existingConfig.getParent()
                : this.parent;
        clientOrDefault().gui.setScreen(new SpotifyConfigScreen(
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

    private String resolveFooterStatusMessage() {
        if (statusMessage != null && !statusMessage.isEmpty()) {
            return statusMessage;
        }
        if (currentStep == 3 && (config.hasValidAccessToken() || config.hasRefreshToken())) {
            return "Account: Connected! You can click Finish below.";
        }
        return "";
    }

    private int resolveStatusColor(String message, int accentColor) {
        String lower = message.toLowerCase();
        if (lower.contains("failed") || lower.contains("please paste") || lower.contains("empty")) {
            return 0xFFFF8A80;
        }
        if (lower.contains("connected") || lower.contains("copied") || lower.contains("pasted") || lower.contains("opened")) {
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
