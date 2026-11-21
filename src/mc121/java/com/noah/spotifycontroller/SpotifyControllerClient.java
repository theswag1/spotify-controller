package com.noah.spotifycontroller;

import com.mojang.blaze3d.platform.InputConstants;
import com.noah.spotifycontroller.api.SpotifyApiClient;
import com.noah.spotifycontroller.api.SpotifyPoller;
import com.noah.spotifycontroller.api.TrackState;
import com.noah.spotifycontroller.auth.SpotifyAuthService;
import com.noah.spotifycontroller.config.SpotifyConfig;
import com.noah.spotifycontroller.gui.SpotifyConfigScreen;
import com.noah.spotifycontroller.gui.SpotifyControllerScreen;
import com.noah.spotifycontroller.gui.SpotifySetupGuideScreen;
import com.noah.spotifycontroller.hud.AlbumArtManager;
import com.noah.spotifycontroller.hud.SpotifyHudOverlay;
import com.noah.spotifycontroller.util.SpotifyI18n;
import com.noah.spotifycontroller.util.UpdateChecker;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * Shared loader-agnostic client core for Minecraft 1.21.1 builds of Spotify Controller.
 */
public class SpotifyControllerClient {
    public static final String MOD_ID = "spotify_controller";
    public static final String KEY_CATEGORY = "key.category.spotify_controller.main";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static SpotifyConfig config;
    private static SpotifyAuthService authService;
    private static SpotifyApiClient apiClient;
    private static SpotifyPoller poller;
    private static AlbumArtManager albumArtManager;
    private static SpotifyHudOverlay hudOverlay;
    private static UpdateChecker updateChecker;
    private static boolean updateNoticeSent = false;

    private static KeyMapping openControllerKey;
    private static KeyMapping openConfigKey;
    private static KeyMapping playPauseKey;
    private static KeyMapping prevTrackKey;
    private static KeyMapping nextTrackKey;
    private static KeyMapping toggleHudKey;

    private static volatile boolean pendingOpenControllerScreen = false;
    private static volatile boolean pendingOpenConfigScreen = false;
    private static volatile boolean pendingOpenSetupScreen = false;
    private static boolean wasInWorld = false;

    /**
     * Initializes config, HTTP/Spotify API clients, background poller, and HUD renderer.
     */
    public static void initCore() {
        config = SpotifyConfig.get();
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        authService = new SpotifyAuthService(config, httpClient, SpotifyControllerClient::openBrowserUri);
        apiClient = new SpotifyApiClient(authService, httpClient);
        poller = new SpotifyPoller(config, apiClient);
        poller.start();

        albumArtManager = new AlbumArtManager(httpClient);
        hudOverlay = new SpotifyHudOverlay(config, poller, albumArtManager);

        updateChecker = new UpdateChecker(UpdateChecker.loadCurrentVersion());
        if (config.checkForUpdates) {
            updateChecker.start(httpClient);
        }
        LOGGER.info("Spotify Controller initialized");
    }

    /**
     * Instantiates and registers all Spotify Controller keybinds using the loader-supplied registrar.
     */
    public static void registerKeyMappings(UnaryOperator<KeyMapping> registrar) {
        InputConstants.Type keyboardType = getKeyboardInputType();
        openConfigKey = registrar.apply(new KeyMapping(
                "key.spotify_controller.open_config",
                keyboardType,
                InputConstants.KEY_O,
                KEY_CATEGORY
        ));
        openControllerKey = registrar.apply(new KeyMapping(
                "key.spotify_controller.open_controller",
                keyboardType,
                InputConstants.KEY_Z,
                KEY_CATEGORY
        ));
        toggleHudKey = registrar.apply(new KeyMapping(
                "key.spotify_controller.toggle_hud",
                keyboardType,
                InputConstants.KEY_BACKSLASH,
                KEY_CATEGORY
        ));
        playPauseKey = registrar.apply(new KeyMapping(
                "key.spotify_controller.play_pause",
                keyboardType,
                InputConstants.KEY_K,
                KEY_CATEGORY
        ));
        nextTrackKey = registrar.apply(new KeyMapping(
                "key.spotify_controller.next_track",
                keyboardType,
                InputConstants.KEY_RBRACKET,
                KEY_CATEGORY
        ));
        prevTrackKey = registrar.apply(new KeyMapping(
                "key.spotify_controller.prev_track",
                keyboardType,
                InputConstants.KEY_LBRACKET,
                KEY_CATEGORY
        ));
    }

    /**
     * Handles world-join setup prompts, deferred command screens, and keybind presses every client tick.
     */
    public static void onClientTick(Minecraft client) {
        checkSetupPromptOnWorldJoin(client);
        checkUpdateNotice(client);

        if (pendingOpenControllerScreen) {
            pendingOpenControllerScreen = false;
            openControllerScreen(client);
            return;
        }
        if (pendingOpenConfigScreen) {
            pendingOpenConfigScreen = false;
            openConfigScreen(client);
            return;
        }
        if (pendingOpenSetupScreen) {
            pendingOpenSetupScreen = false;
            openSetupGuideScreen(client);
            return;
        }

        if (openControllerKey != null) {
            while (openControllerKey.consumeClick()) {
                openControllerScreen(client);
            }
        }
        if (openConfigKey != null) {
            while (openConfigKey.consumeClick()) {
                openConfigScreen(client);
            }
        }
        if (playPauseKey != null) {
            while (playPauseKey.consumeClick()) {
                // Shares the HUD's optimistic state so repeated presses alternate instead of re-sending "pause".
                runKeybindPlaybackAction(client, hudOverlay.togglePlayingOptimistically() ? apiClient.play() : apiClient.pause());
            }
        }
        if (prevTrackKey != null) {
            while (prevTrackKey.consumeClick()) {
                runKeybindPlaybackAction(client, apiClient.previousTrack());
            }
        }
        if (nextTrackKey != null) {
            while (nextTrackKey.consumeClick()) {
                runKeybindPlaybackAction(client, apiClient.nextTrack());
            }
        }
        if (toggleHudKey != null) {
            while (toggleHudKey.consumeClick()) {
                config.hudEnabled = !config.hudEnabled;
                config.save();
                sendOverlayFeedback(
                        client,
                        Component.translatable(config.hudEnabled
                                ? "screen.spotify_controller.config.hud_on"
                                : "screen.spotify_controller.config.hud_off"),
                        config.hudEnabled ? ChatFormatting.GREEN : ChatFormatting.YELLOW
                );
            }
        }
    }

    /**
     * Releases album art textures, poller threads, and OAuth server resources on client shutdown.
     */
    public static void onClientStopping() {
        if (albumArtManager != null) {
            albumArtManager.clear();
        }
        if (poller != null) {
            poller.stop();
        }
        if (authService != null) {
            authService.shutdown();
        }
    }

    /**
     * Builds the configuration screen (or first-time setup wizard when unconfigured) for mod menus.
     */
    public static Screen createConfigScreen(Screen parent) {
        if (!config.hasClientId()) {
            return new SpotifySetupGuideScreen(parent, config, authService, poller, hudOverlay);
        }
        return new SpotifyConfigScreen(parent, config, authService, poller, hudOverlay);
    }

    /**
     * Opens a URI in the system browser via Minecraft's OS helper with AWT Desktop fallback.
     */
    public static void openBrowserUri(URI uri) {
        try {
            Util.getPlatform().openUri(uri);
            return;
        } catch (RuntimeException ignored) {
            // Fall back to AWT Desktop if Minecraft OS helper fails
        }
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            try {
                Desktop.getDesktop().browse(uri);
                return;
            } catch (IOException e) {
                LOGGER.error("Failed to open browser URI {}", uri, e);
                throw new RuntimeException("Failed to open browser: " + e.getMessage(), e);
            }
        }
        throw new IllegalStateException("System browser browsing is not supported on this platform");
    }

    private static InputConstants.Type getKeyboardInputType() {
        for (InputConstants.Type type : InputConstants.Type.values()) {
            if (type.name().equals("KEYBOARD") || type.name().equals("KEYSYM")) {
                return type;
            }
        }
        return InputConstants.Type.values()[0];
    }

    public static SpotifyConfig getConfig() {
        return config;
    }

    public static SpotifyPoller getPoller() {
        return poller;
    }

    public static SpotifyHudOverlay getHudOverlay() {
        return hudOverlay;
    }

    /**
     * Returns whether any Spotify Controller screen is currently open on the client.
     */
    public static boolean isSpotifyUiOpen(Minecraft client) {
        if (client == null) {
            return false;
        }
        Screen screen = client.screen;
        return screen instanceof SpotifyControllerScreen
                || screen instanceof SpotifyConfigScreen
                || screen instanceof SpotifySetupGuideScreen;
    }

    public static int requestOpenControllerScreen() {
        pendingOpenControllerScreen = true;
        return 1;
    }

    public static int requestOpenConfigScreen() {
        pendingOpenConfigScreen = true;
        return 1;
    }

    public static int requestOpenSetupScreen() {
        pendingOpenSetupScreen = true;
        return 1;
    }

    public static int executeSetClientIdCommand(
            String rawId,
            Consumer<Component> sendFeedback,
            Consumer<Component> sendError
    ) {
        String id = rawId == null ? "" : rawId.trim();
        if (id.isEmpty()) {
            sendError.accept(Component.translatable("chat.spotify_controller.client_id_empty"));
            return 0;
        }
        config.setClientId(id);
        sendFeedback.accept(
                Component.translatable("chat.spotify_controller.client_id_saved")
                        .withStyle(ChatFormatting.GREEN)
        );
        return 1;
    }

    public static int executeLoginCommand(Minecraft client) {
        authService.startAuthFlow(msg -> sendClientMessage(client, msg, false))
                .whenComplete((ok, ex) -> {
                    if (Boolean.TRUE.equals(ok)) {
                        poller.refreshNow();
                    }
                });
        return 1;
    }

    public static int executeStatusCommand(Consumer<Component> sendFeedback) {
        boolean connected = config.hasValidAccessToken() || config.hasRefreshToken();
        TrackState track = poller.getCurrentTrack();
        StringBuilder summary = new StringBuilder();
        summary.append(SpotifyI18n.translate(connected
                ? "chat.spotify_controller.status.connected"
                : "chat.spotify_controller.status.not_connected"));
        if (!config.hasClientId()) {
            summary.append(SpotifyI18n.translate("chat.spotify_controller.status.client_id_unset"));
        }
        if (track != null) {
            summary.append(" | ")
                    .append(SpotifyI18n.translate(
                            track.isPlaying() ? "chat.spotify_controller.status.playing" : "chat.spotify_controller.status.paused",
                            track.trackName(),
                            track.artistName(),
                            TrackState.formatDuration(track.getInterpolatedProgressMs()),
                            TrackState.formatDuration(track.durationMs())
                    ));
        } else {
            summary.append(" | ").append(poller.getLastStatusMessage());
        }
        sendFeedback.accept(
                Component.translatable("chat.spotify_controller.prefix", summary.toString())
                        .withStyle(connected ? ChatFormatting.GREEN : ChatFormatting.YELLOW)
        );
        return 1;
    }

    public static int executePlayCommand(Minecraft client) {
        return runPlaybackCommand(client, apiClient.play());
    }

    public static int executePauseCommand(Minecraft client) {
        return runPlaybackCommand(client, apiClient.pause());
    }

    public static int executeNextCommand(Minecraft client) {
        return runPlaybackCommand(client, apiClient.nextTrack());
    }

    public static int executePrevCommand(Minecraft client) {
        return runPlaybackCommand(client, apiClient.previousTrack());
    }

    public static int executeVolumeCommand(Minecraft client, int percent) {
        return runPlaybackCommand(client, apiClient.setVolume(percent));
    }

    public static int executeShuffleCommand(Minecraft client) {
        TrackState track = poller.getCurrentTrack();
        boolean nextShuffle = track == null || !track.shuffleState();
        return runPlaybackCommand(client, apiClient.setShuffle(nextShuffle));
    }

    public static int executeRepeatCommand(Minecraft client) {
        TrackState track = poller.getCurrentTrack();
        String nextRepeat = SpotifyApiClient.nextRepeatState(track != null ? track.repeatState() : "off");
        return runPlaybackCommand(client, apiClient.setRepeatMode(nextRepeat));
    }

    /**
     * Runs the HUD hover control under the mouse (prev / play-pause / next) and returns whether the click was used.
     */
    public static boolean handleHudControlClick(double mouseX, double mouseY, int screenWidth, int screenHeight) {
        int control = hudOverlay == null ? -1 : hudOverlay.controlAt(mouseX, mouseY, screenWidth, screenHeight);
        if (control < 0) {
            return false;
        }
        Minecraft client = Minecraft.getInstance();
        runKeybindPlaybackAction(client, switch (control) {
            case 0 -> apiClient.previousTrack();
            case 1 -> hudOverlay.togglePlayingOptimistically() ? apiClient.play() : apiClient.pause();
            default -> apiClient.nextTrack();
        });
        return true;
    }

    /**
     * Posts "♪ Now playing: Song — Artist <link>" to server chat. Player chat is plain text, so the link is only
     * clickable where the server turns URLs into links (NeoForge does; vanilla and Fabric servers don't).
     */
    public static int executeShareCommand(Minecraft client, Consumer<Component> sendError) {
        TrackState track = poller.getCurrentTrack();
        if (track == null || client.player == null) {
            sendError.accept(Component.translatable("chat.spotify_controller.share_no_track"));
            return 0;
        }
        String message = TrackState.fitChatMessage(
                SpotifyI18n.translate("chat.spotify_controller.share_message", track.trackName(), track.artistName()),
                track.spotifyUrl()
        );
        // Defer so the chat packet is sent after the current command finishes executing.
        client.execute(() -> {
            if (client.player != null) {
                client.player.connection.sendChat(message);
            }
        });
        return 1;
    }

    public static int executeHudCommand(Consumer<Component> sendFeedback) {
        config.hudEnabled = !config.hudEnabled;
        config.save();
        sendFeedback.accept(
                Component.translatable(config.hudEnabled
                                ? "chat.spotify_controller.hud_enabled"
                                : "chat.spotify_controller.hud_disabled")
                        .withStyle(config.hudEnabled ? ChatFormatting.GREEN : ChatFormatting.YELLOW)
        );
        return 1;
    }

    private static void checkSetupPromptOnWorldJoin(Minecraft client) {
        boolean inWorld = client.player != null && client.level != null;
        if (inWorld && !wasInWorld && !config.hasClientId()) {
            String keyName = openConfigKey != null ? openConfigKey.getTranslatedKeyMessage().getString() : "O";
            client.player.displayClientMessage(
                    Component.translatable("chat.spotify_controller.setup_prompt", keyName)
                            .withStyle(ChatFormatting.YELLOW),
                    false
            );
        }
        wasInWorld = inWorld;
    }

    /**
     * Sends one chat notice per session, once in a world, if the background check found a newer release.
     */
    private static void checkUpdateNotice(Minecraft client) {
        if (updateNoticeSent || updateChecker == null || client.player == null || client.level == null) {
            return;
        }
        UpdateChecker.Release release = updateChecker.getNewerRelease();
        if (release == null) {
            return;
        }
        updateNoticeSent = true;
        Component download = Component.translatable("chat.spotify_controller.update_download")
                .withStyle(style -> style
                        .withColor(ChatFormatting.AQUA)
                        .withUnderlined(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, release.url())));
        client.player.displayClientMessage(
                Component.translatable(
                        "chat.spotify_controller.prefix",
                        Component.translatable(
                                "chat.spotify_controller.update_available",
                                release.version(),
                                "v" + updateChecker.getCurrentVersion()
                        ).append(download)
                ).withStyle(ChatFormatting.YELLOW),
                false
        );
    }

    private static void openControllerScreen(Minecraft client) {
        if (!config.hasClientId()) {
            openSetupGuideScreen(client);
            return;
        }
        client.setScreen(new SpotifyControllerScreen(
                client.screen,
                config,
                apiClient,
                poller,
                albumArtManager
        ));
    }

    private static void openConfigScreen(Minecraft client) {
        client.setScreen(createConfigScreen(client.screen));
    }

    private static void openSetupGuideScreen(Minecraft client) {
        client.setScreen(new SpotifySetupGuideScreen(
                client.screen,
                config,
                authService,
                poller,
                hudOverlay
        ));
    }

    private static int runPlaybackCommand(Minecraft client, CompletableFuture<SpotifyApiClient.ApiResult> action) {
        action.whenComplete((result, throwable) -> {
            if (throwable != null) {
                sendClientMessage(client, SpotifyI18n.translate("chat.spotify_controller.error", throwable.getMessage()), true);
                return;
            }
            if (result.success()) {
                poller.triggerImmediateRefresh(350L);
            }
            sendClientMessage(client, result.message(), !result.success());
        });
        return 1;
    }

    private static void runKeybindPlaybackAction(Minecraft client, CompletableFuture<SpotifyApiClient.ApiResult> action) {
        action.whenComplete((result, throwable) -> {
            if (throwable != null) {
                sendOverlayFeedback(client, SpotifyI18n.translate("chat.spotify_controller.error", throwable.getMessage()), ChatFormatting.RED);
                return;
            }
            if (result.success()) {
                poller.triggerImmediateRefresh(350L);
            }
            sendOverlayFeedback(
                    client,
                    result.message(),
                    result.success() ? ChatFormatting.GREEN : ChatFormatting.RED
            );
        });
    }

    private static void sendClientMessage(Minecraft client, String message, boolean isError) {
        client.execute(() -> {
            if (client.player != null) {
                client.player.displayClientMessage(
                        Component.translatable("chat.spotify_controller.prefix", message)
                                .withStyle(isError ? ChatFormatting.RED : ChatFormatting.GREEN),
                        false
                );
            }
        });
    }

    private static void sendOverlayFeedback(Minecraft client, String message, ChatFormatting color) {
        sendOverlayFeedback(client, Component.literal(message), color);
    }

    private static void sendOverlayFeedback(Minecraft client, Component message, ChatFormatting color) {
        client.execute(() -> {
            if (client.player != null) {
                client.player.displayClientMessage(
                        Component.translatable("chat.spotify_controller.prefix", message).withStyle(color),
                        true
                );
            }
        });
    }
}
