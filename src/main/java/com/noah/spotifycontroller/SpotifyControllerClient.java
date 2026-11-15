package com.noah.spotifycontroller;

import com.mojang.blaze3d.Blaze3D;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.noah.spotifycontroller.api.SpotifyApiClient;
import com.noah.spotifycontroller.api.SpotifyPoller;
import com.noah.spotifycontroller.api.TrackState;
import com.noah.spotifycontroller.auth.SpotifyAuthService;
import com.noah.spotifycontroller.config.SpotifyConfig;
import com.noah.spotifycontroller.gui.SpotifyConfigScreen;
import com.noah.spotifycontroller.hud.AlbumArtManager;
import com.noah.spotifycontroller.hud.SpotifyHudOverlay;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Client entrypoint for the Spotify Controller Fabric mod.
 */
public class SpotifyControllerClient implements ClientModInitializer {
    public static final String MOD_ID = "spotify_controller";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static SpotifyConfig config;
    private static SpotifyAuthService authService;
    private static SpotifyApiClient apiClient;
    private static SpotifyPoller poller;
    private static AlbumArtManager albumArtManager;
    private static SpotifyHudOverlay hudOverlay;

    private static KeyMapping openConfigKey;
    private static volatile boolean pendingOpenConfigScreen = false;

    @Override
    public void onInitializeClient() {
        config = SpotifyConfig.get();
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        authService = new SpotifyAuthService(config, httpClient, Blaze3D::openUri);
        apiClient = new SpotifyApiClient(authService, httpClient);
        poller = new SpotifyPoller(config, apiClient);
        poller.start();

        albumArtManager = new AlbumArtManager(httpClient);
        hudOverlay = new SpotifyHudOverlay(config, poller, albumArtManager);
        HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath(MOD_ID, "now_playing_hud"),
                hudOverlay
        );

        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath(MOD_ID, "main")
        );
        openConfigKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.spotify_controller.open_config",
                InputConstants.Type.KEYBOARD,
                InputConstants.KEY_O,
                category
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (pendingOpenConfigScreen) {
                pendingOpenConfigScreen = false;
                openConfigScreen(client);
                return;
            }
            while (openConfigKey.consumeClick()) {
                openConfigScreen(client);
            }
        });

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            if (albumArtManager != null) {
                albumArtManager.clear();
            }
            if (poller != null) {
                poller.stop();
            }
            if (authService != null) {
                authService.shutdown();
            }
        });

        registerClientCommands();
        LOGGER.info("Spotify Controller initialized for Minecraft 26.3");
    }

    public static SpotifyConfig getConfig() {
        return config;
    }

    public static SpotifyAuthService getAuthService() {
        return authService;
    }

    public static SpotifyApiClient getApiClient() {
        return apiClient;
    }

    public static SpotifyPoller getPoller() {
        return poller;
    }

    private static void openConfigScreen(Minecraft client) {
        client.gui.setScreen(new SpotifyConfigScreen(client.gui.screen(), config, authService, poller));
    }

    private static void registerClientCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                ClientCommands.literal("spotify")
                        .then(ClientCommands.literal("config")
                                .executes(context -> {
                                    pendingOpenConfigScreen = true;
                                    return 1;
                                }))
                        .then(ClientCommands.literal("clientid")
                                .then(ClientCommands.argument("id", StringArgumentType.greedyString())
                                        .executes(context -> {
                                            String id = StringArgumentType.getString(context, "id").trim();
                                            if (id.isEmpty()) {
                                                context.getSource().sendError(
                                                        Component.literal("[Spotify] Client ID cannot be empty.")
                                                );
                                                return 0;
                                            }
                                            config.setClientId(id);
                                            context.getSource().sendFeedback(
                                                    Component.literal("[Spotify] Saved Client ID. Run /spotify login to connect.")
                                                            .withStyle(ChatFormatting.GREEN)
                                            );
                                            return 1;
                                        })))
                        .then(ClientCommands.literal("login")
                                .executes(context -> {
                                    Minecraft client = context.getSource().getClient();
                                    authService.startAuthFlow(msg -> sendClientMessage(client, msg, false))
                                            .whenComplete((ok, ex) -> {
                                                if (Boolean.TRUE.equals(ok)) {
                                                    poller.refreshNow();
                                                }
                                            });
                                    return 1;
                                }))
                        .then(ClientCommands.literal("status")
                                .executes(context -> {
                                    boolean connected = config.hasValidAccessToken() || config.hasRefreshToken();
                                    TrackState track = poller.getCurrentTrack();
                                    StringBuilder summary = new StringBuilder();
                                    summary.append(connected ? "Connected" : "Not connected");
                                    if (!config.hasClientId()) {
                                        summary.append(" (Client ID not set)");
                                    }
                                    if (track != null) {
                                        summary.append(" | ")
                                                .append(track.isPlaying() ? "Playing: " : "Paused: ")
                                                .append(track.trackName())
                                                .append(" - ")
                                                .append(track.artistName())
                                                .append(" [")
                                                .append(TrackState.formatDuration(track.getInterpolatedProgressMs()))
                                                .append(" / ")
                                                .append(TrackState.formatDuration(track.durationMs()))
                                                .append("]");
                                    } else {
                                        summary.append(" | ").append(poller.getLastStatusMessage());
                                    }
                                    context.getSource().sendFeedback(
                                            Component.literal("[Spotify] " + summary)
                                                    .withStyle(connected ? ChatFormatting.GREEN : ChatFormatting.YELLOW)
                                    );
                                    return 1;
                                }))
                        .then(ClientCommands.literal("play")
                                .executes(context -> runPlaybackCommand(context.getSource().getClient(), apiClient.play())))
                        .then(ClientCommands.literal("pause")
                                .executes(context -> runPlaybackCommand(context.getSource().getClient(), apiClient.pause())))
                        .then(ClientCommands.literal("next")
                                .executes(context -> runPlaybackCommand(context.getSource().getClient(), apiClient.nextTrack())))
                        .then(ClientCommands.literal("prev")
                                .executes(context -> runPlaybackCommand(context.getSource().getClient(), apiClient.previousTrack())))
                        .then(ClientCommands.literal("hud")
                                .executes(context -> {
                                    config.hudEnabled = !config.hudEnabled;
                                    config.save();
                                    context.getSource().sendFeedback(
                                            Component.literal("[Spotify] In-game HUD widget " + (config.hudEnabled ? "enabled." : "disabled."))
                                                    .withStyle(config.hudEnabled ? ChatFormatting.GREEN : ChatFormatting.YELLOW)
                                    );
                                    return 1;
                                }))
        ));
    }

    private static int runPlaybackCommand(Minecraft client, CompletableFuture<SpotifyApiClient.ApiResult> action) {
        action.whenComplete((result, throwable) -> {
            if (throwable != null) {
                sendClientMessage(client, "Error: " + throwable.getMessage(), true);
                return;
            }
            if (result.success()) {
                poller.triggerImmediateRefresh(350L);
            }
            sendClientMessage(client, result.message(), !result.success());
        });
        return 1;
    }

    private static void sendClientMessage(Minecraft client, String message, boolean isError) {
        client.execute(() -> {
            if (client.player != null) {
                client.player.sendSystemMessage(
                        Component.literal("[Spotify] " + message)
                                .withStyle(isError ? ChatFormatting.RED : ChatFormatting.GREEN)
                );
            }
        });
    }
}
