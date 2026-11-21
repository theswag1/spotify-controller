package com.noah.spotifycontroller.fabric;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.noah.spotifycontroller.SpotifyControllerClient;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;

/**
 * Fabric client entrypoint for Minecraft 1.21.1.
 */
public class SpotifyControllerFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        SpotifyControllerClient.initCore();

        HudRenderCallback.EVENT.register((guiGraphics, tickCounter) ->
                SpotifyControllerClient.getHudOverlay().render(guiGraphics, tickCounter)
        );

        // HUD hover controls on any in-world screen that frees the cursor (chat, inventory...).
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            ScreenEvents.afterRender(screen).register((s, graphics, mouseX, mouseY, delta) ->
                    SpotifyControllerClient.getHudOverlay().renderHoverControls(graphics, mouseX, mouseY));
            ScreenMouseEvents.allowMouseClick(screen).register((s, mouseX, mouseY, button) ->
                    button != 0 || !SpotifyControllerClient.handleHudControlClick(mouseX, mouseY, s.width, s.height));
        });

        SpotifyControllerClient.registerKeyMappings(KeyBindingHelper::registerKeyBinding);

        ClientTickEvents.END_CLIENT_TICK.register(SpotifyControllerClient::onClientTick);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> SpotifyControllerClient.onClientStopping());
        registerClientCommands();
    }

    private static void registerClientCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                ClientCommandManager.literal("encore")
                        .executes(context -> SpotifyControllerClient.requestOpenControllerScreen())
                        .then(ClientCommandManager.literal("player")
                                .executes(context -> SpotifyControllerClient.requestOpenControllerScreen()))
                        .then(ClientCommandManager.literal("config")
                                .executes(context -> SpotifyControllerClient.requestOpenConfigScreen()))
                        .then(ClientCommandManager.literal("setup")
                                .executes(context -> SpotifyControllerClient.requestOpenSetupScreen()))
                        .then(ClientCommandManager.literal("clientid")
                                .then(ClientCommandManager.argument("id", StringArgumentType.greedyString())
                                        .executes(context -> SpotifyControllerClient.executeSetClientIdCommand(
                                                StringArgumentType.getString(context, "id"),
                                                context.getSource()::sendFeedback,
                                                context.getSource()::sendError
                                        ))))
                        .then(ClientCommandManager.literal("login")
                                .executes(context -> SpotifyControllerClient.executeLoginCommand(
                                        context.getSource().getClient()
                                )))
                        .then(ClientCommandManager.literal("status")
                                .executes(context -> SpotifyControllerClient.executeStatusCommand(
                                        context.getSource()::sendFeedback
                                )))
                        .then(ClientCommandManager.literal("play")
                                .executes(context -> SpotifyControllerClient.executePlayCommand(
                                        context.getSource().getClient()
                                )))
                        .then(ClientCommandManager.literal("pause")
                                .executes(context -> SpotifyControllerClient.executePauseCommand(
                                        context.getSource().getClient()
                                )))
                        .then(ClientCommandManager.literal("next")
                                .executes(context -> SpotifyControllerClient.executeNextCommand(
                                        context.getSource().getClient()
                                )))
                        .then(ClientCommandManager.literal("prev")
                                .executes(context -> SpotifyControllerClient.executePrevCommand(
                                        context.getSource().getClient()
                                )))
                        .then(ClientCommandManager.literal("volume")
                                .then(ClientCommandManager.argument("percent", IntegerArgumentType.integer(0, 100))
                                        .executes(context -> {
                                            int percent = IntegerArgumentType.getInteger(context, "percent");
                                            Minecraft client = context.getSource().getClient();
                                            return SpotifyControllerClient.executeVolumeCommand(client, percent);
                                        })))
                        .then(ClientCommandManager.literal("shuffle")
                                .executes(context -> SpotifyControllerClient.executeShuffleCommand(
                                        context.getSource().getClient()
                                )))
                        .then(ClientCommandManager.literal("repeat")
                                .executes(context -> SpotifyControllerClient.executeRepeatCommand(
                                        context.getSource().getClient()
                                )))
                        .then(ClientCommandManager.literal("share")
                                .executes(context -> SpotifyControllerClient.executeShareCommand(
                                        context.getSource().getClient(),
                                        context.getSource()::sendError
                                )))
                        .then(ClientCommandManager.literal("hud")
                                .executes(context -> SpotifyControllerClient.executeHudCommand(
                                        context.getSource()::sendFeedback
                                )))
        ));
    }
}
