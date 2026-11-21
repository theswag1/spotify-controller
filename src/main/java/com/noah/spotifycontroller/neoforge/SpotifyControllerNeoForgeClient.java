package com.noah.spotifycontroller.neoforge;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.noah.spotifycontroller.SpotifyControllerClient;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.GameShuttingDownEvent;

/**
 * NeoForge client entrypoint that wires NeoForge mod-bus and game-bus events
 * into {@link SpotifyControllerClient}.
 */
@Mod(value = SpotifyControllerClient.MOD_ID, dist = Dist.CLIENT)
public class SpotifyControllerNeoForgeClient {
    public SpotifyControllerNeoForgeClient(IEventBus modEventBus, ModContainer modContainer) {
        SpotifyControllerClient.initCore();

        modEventBus.addListener(RegisterKeyMappingsEvent.class, event -> {
            KeyMapping.Category category = SpotifyControllerClient.createUnregisteredKeyCategory();
            event.registerCategory(category);
            SpotifyControllerClient.registerKeyMappings(category, key -> {
                event.register(key);
                return key;
            });
        });

        modEventBus.addListener(RegisterGuiLayersEvent.class, event -> event.registerAboveAll(
                Identifier.fromNamespaceAndPath(SpotifyControllerClient.MOD_ID, "now_playing_hud"),
                SpotifyControllerClient.getHudOverlay()::extractRenderState
        ));

        NeoForge.EVENT_BUS.addListener(
                ClientTickEvent.Post.class,
                event -> SpotifyControllerClient.onClientTick(Minecraft.getInstance())
        );
        // HUD hover controls on any in-world screen that frees the cursor (chat, inventory...).
        NeoForge.EVENT_BUS.addListener(ScreenEvent.Render.Post.class, event ->
                SpotifyControllerClient.getHudOverlay().renderHoverControls(
                        event.getGuiGraphics(), event.getMouseX(), event.getMouseY()));
        NeoForge.EVENT_BUS.addListener(ScreenEvent.MouseButtonPressed.Pre.class, event -> {
            Screen screen = event.getScreen();
            if (event.getButton() == 0 && SpotifyControllerClient.handleHudControlClick(
                    event.getMouseX(), event.getMouseY(), screen.width, screen.height)) {
                event.setCanceled(true);
            }
        });
        NeoForge.EVENT_BUS.addListener(
                GameShuttingDownEvent.class,
                event -> SpotifyControllerClient.onClientStopping()
        );
        NeoForge.EVENT_BUS.addListener(
                RegisterClientCommandsEvent.class,
                event -> registerCommands(event.getDispatcher())
        );

        modContainer.registerExtensionPoint(
                IConfigScreenFactory.class,
                (container, parent) -> SpotifyControllerClient.createConfigScreen(parent)
        );
    }

    private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("encore")
                        .executes(context -> SpotifyControllerClient.requestOpenControllerScreen())
                        .then(Commands.literal("player")
                                .executes(context -> SpotifyControllerClient.requestOpenControllerScreen()))
                        .then(Commands.literal("config")
                                .executes(context -> SpotifyControllerClient.requestOpenConfigScreen()))
                        .then(Commands.literal("setup")
                                .executes(context -> SpotifyControllerClient.requestOpenSetupScreen()))
                        .then(Commands.literal("clientid")
                                .then(Commands.argument("id", StringArgumentType.greedyString())
                                        .executes(context -> SpotifyControllerClient.executeSetClientIdCommand(
                                                StringArgumentType.getString(context, "id"),
                                                msg -> context.getSource().sendSuccess(() -> msg, false),
                                                context.getSource()::sendFailure
                                        ))))
                        .then(Commands.literal("login")
                                .executes(context -> SpotifyControllerClient.executeLoginCommand(
                                        Minecraft.getInstance()
                                )))
                        .then(Commands.literal("status")
                                .executes(context -> SpotifyControllerClient.executeStatusCommand(
                                        msg -> context.getSource().sendSuccess(() -> msg, false)
                                )))
                        .then(Commands.literal("play")
                                .executes(context -> SpotifyControllerClient.executePlayCommand(
                                        Minecraft.getInstance()
                                )))
                        .then(Commands.literal("pause")
                                .executes(context -> SpotifyControllerClient.executePauseCommand(
                                        Minecraft.getInstance()
                                )))
                        .then(Commands.literal("next")
                                .executes(context -> SpotifyControllerClient.executeNextCommand(
                                        Minecraft.getInstance()
                                )))
                        .then(Commands.literal("prev")
                                .executes(context -> SpotifyControllerClient.executePrevCommand(
                                        Minecraft.getInstance()
                                )))
                        .then(Commands.literal("volume")
                                .then(Commands.argument("percent", IntegerArgumentType.integer(0, 100))
                                        .executes(context -> {
                                            int percent = IntegerArgumentType.getInteger(context, "percent");
                                            return SpotifyControllerClient.executeVolumeCommand(Minecraft.getInstance(), percent);
                                        })))
                        .then(Commands.literal("shuffle")
                                .executes(context -> SpotifyControllerClient.executeShuffleCommand(
                                        Minecraft.getInstance()
                                )))
                        .then(Commands.literal("repeat")
                                .executes(context -> SpotifyControllerClient.executeRepeatCommand(
                                        Minecraft.getInstance()
                                )))
                        .then(Commands.literal("share")
                                .executes(context -> SpotifyControllerClient.executeShareCommand(
                                        Minecraft.getInstance(),
                                        context.getSource()::sendFailure
                                )))
                        .then(Commands.literal("hud")
                                .executes(context -> SpotifyControllerClient.executeHudCommand(
                                        msg -> context.getSource().sendSuccess(() -> msg, false)
                                )))
        );
    }
}
