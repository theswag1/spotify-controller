package com.noah.spotifycontroller.fabric;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.noah.spotifycontroller.SpotifyControllerClient;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/**
 * Fabric client entrypoint that wires Fabric lifecycle, keybind, HUD, and
 * client command callbacks into {@link SpotifyControllerClient}.
 */
public class SpotifyControllerFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        SpotifyControllerClient.initCore();

        HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath(SpotifyControllerClient.MOD_ID, "now_playing_hud"),
                SpotifyControllerClient.getHudOverlay()::extractRenderState
        );

        // HUD hover controls on any in-world screen that frees the cursor (chat, inventory...).
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, delta) ->
                    SpotifyControllerClient.getHudOverlay().renderHoverControls(graphics, mouseX, mouseY));
            ScreenMouseEvents.allowMouseClick(screen).register((s, event) ->
                    event.button() != InputConstants.MOUSE_BUTTON_LEFT
                            || !SpotifyControllerClient.handleHudControlClick(event.x(), event.y(), s.width, s.height));
        });

        SpotifyControllerClient.registerKeyMappings(
                SpotifyControllerClient.createKeyCategory(),
                KeyMappingHelper::registerKeyMapping
        );

        ClientTickEvents.END_CLIENT_TICK.register(SpotifyControllerClient::onClientTick);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> SpotifyControllerClient.onClientStopping());
        registerClientCommands();
    }

    private static void registerClientCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                ClientCommands.literal("spotify")
                        .executes(context -> SpotifyControllerClient.requestOpenControllerScreen())
                        .then(ClientCommands.literal("player")
                                .executes(context -> SpotifyControllerClient.requestOpenControllerScreen()))
                        .then(ClientCommands.literal("config")
                                .executes(context -> SpotifyControllerClient.requestOpenConfigScreen()))
                        .then(ClientCommands.literal("setup")
                                .executes(context -> SpotifyControllerClient.requestOpenSetupScreen()))
                        .then(ClientCommands.literal("clientid")
                                .then(ClientCommands.argument("id", StringArgumentType.greedyString())
                                        .executes(context -> SpotifyControllerClient.executeSetClientIdCommand(
                                                StringArgumentType.getString(context, "id"),
                                                context.getSource()::sendFeedback,
                                                context.getSource()::sendError
                                        ))))
                        .then(ClientCommands.literal("login")
                                .executes(context -> SpotifyControllerClient.executeLoginCommand(
                                        context.getSource().getClient()
                                )))
                        .then(ClientCommands.literal("status")
                                .executes(context -> SpotifyControllerClient.executeStatusCommand(
                                        context.getSource()::sendFeedback
                                )))
                        .then(ClientCommands.literal("play")
                                .executes(context -> SpotifyControllerClient.executePlayCommand(
                                        context.getSource().getClient()
                                )))
                        .then(ClientCommands.literal("pause")
                                .executes(context -> SpotifyControllerClient.executePauseCommand(
                                        context.getSource().getClient()
                                )))
                        .then(ClientCommands.literal("next")
                                .executes(context -> SpotifyControllerClient.executeNextCommand(
                                        context.getSource().getClient()
                                )))
                        .then(ClientCommands.literal("prev")
                                .executes(context -> SpotifyControllerClient.executePrevCommand(
                                        context.getSource().getClient()
                                )))
                        .then(ClientCommands.literal("volume")
                                .then(ClientCommands.argument("percent", IntegerArgumentType.integer(0, 100))
                                        .executes(context -> {
                                            int percent = IntegerArgumentType.getInteger(context, "percent");
                                            Minecraft client = context.getSource().getClient();
                                            return SpotifyControllerClient.executeVolumeCommand(client, percent);
                                        })))
                        .then(ClientCommands.literal("shuffle")
                                .executes(context -> SpotifyControllerClient.executeShuffleCommand(
                                        context.getSource().getClient()
                                )))
                        .then(ClientCommands.literal("repeat")
                                .executes(context -> SpotifyControllerClient.executeRepeatCommand(
                                        context.getSource().getClient()
                                )))
                        .then(ClientCommands.literal("share")
                                .executes(context -> SpotifyControllerClient.executeShareCommand(
                                        context.getSource().getClient(),
                                        context.getSource()::sendError
                                )))
                        .then(ClientCommands.literal("hud")
                                .executes(context -> SpotifyControllerClient.executeHudCommand(
                                        context.getSource()::sendFeedback
                                )))
        ));
    }
}
