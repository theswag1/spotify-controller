package com.noah.spotifycontroller.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.util.Objects;

/**
 * Runtime compatibility bridge for Minecraft client GUI APIs that moved between 26.1.x and 26.2+.
 * <p>
 * Minecraft 26.2 moved screen/HUD state off {@link Minecraft}/{@code Options} onto {@code Minecraft.gui}
 * ({@code Gui#setScreen}, {@code Gui#screen()}, and {@code Gui.hud#isHidden()}). Resolving these unobfuscated
 * handles once at class load lets a single compiled jar run across 26.1.x, 26.2, and 26.3 on both Fabric and NeoForge.
 */
public final class MinecraftGuiCompat {
    private static final MethodHandle SET_SCREEN = resolveSetScreen();
    private static final MethodHandle GET_SCREEN = resolveGetScreen();
    private static final MethodHandle IS_HUD_HIDDEN = resolveIsHudHidden();

    private MinecraftGuiCompat() {
    }

    public static void setScreen(Minecraft client, Screen screen) {
        Objects.requireNonNull(client, "client");
        try {
            SET_SCREEN.invokeExact(client, screen);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to invoke Minecraft setScreen compatibility handle", t);
        }
    }

    public static Screen getScreen(Minecraft client) {
        Objects.requireNonNull(client, "client");
        try {
            return (Screen) GET_SCREEN.invokeExact(client);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to invoke Minecraft getScreen compatibility handle", t);
        }
    }

    public static boolean isHudHidden(Minecraft client) {
        Objects.requireNonNull(client, "client");
        try {
            return (boolean) IS_HUD_HIDDEN.invokeExact(client);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to invoke Minecraft isHudHidden compatibility handle", t);
        }
    }

    private static MethodHandle resolveSetScreen() {
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        try {
            Field guiField = Minecraft.class.getField("gui");
            MethodHandle guiGetter = lookup.unreflectGetter(guiField);
            MethodHandle guiSetScreen = lookup.unreflect(guiField.getType().getMethod("setScreen", Screen.class));
            return MethodHandles.filterArguments(guiSetScreen, 0, guiGetter)
                    .asType(MethodType.methodType(void.class, Minecraft.class, Screen.class));
        } catch (ReflectiveOperationException ignored) {
            // Minecraft 26.1.x exposes setScreen(Screen) directly on Minecraft instead of Gui.
        }

        try {
            return lookup.unreflect(Minecraft.class.getMethod("setScreen", Screen.class))
                    .asType(MethodType.methodType(void.class, Minecraft.class, Screen.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static MethodHandle resolveGetScreen() {
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        try {
            Field guiField = Minecraft.class.getField("gui");
            MethodHandle guiGetter = lookup.unreflectGetter(guiField);
            MethodHandle guiScreen = lookup.unreflect(guiField.getType().getMethod("screen"));
            return MethodHandles.filterArguments(guiScreen, 0, guiGetter)
                    .asType(MethodType.methodType(Screen.class, Minecraft.class));
        } catch (ReflectiveOperationException ignored) {
            // Minecraft 26.1.x exposes Minecraft.screen directly instead of Gui#screen().
        }

        try {
            return lookup.unreflectGetter(Minecraft.class.getField("screen"))
                    .asType(MethodType.methodType(Screen.class, Minecraft.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static MethodHandle resolveIsHudHidden() {
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        try {
            Field guiField = Minecraft.class.getField("gui");
            MethodHandle guiGetter = lookup.unreflectGetter(guiField);
            Field hudField = guiField.getType().getField("hud");
            MethodHandle hudGetter = lookup.unreflectGetter(hudField);
            MethodHandle hudIsHidden = lookup.unreflect(hudField.getType().getMethod("isHidden"));
            MethodHandle guiIsHidden = MethodHandles.filterArguments(hudIsHidden, 0, hudGetter);
            return MethodHandles.filterArguments(guiIsHidden, 0, guiGetter)
                    .asType(MethodType.methodType(boolean.class, Minecraft.class));
        } catch (ReflectiveOperationException ignored) {
            // Minecraft 26.1.x stores HUD visibility in Minecraft.options.hideGui instead of Gui.hud.isHidden().
        }

        try {
            Field optionsField = Minecraft.class.getField("options");
            MethodHandle optionsGetter = lookup.unreflectGetter(optionsField);
            Field hideGuiField = optionsField.getType().getField("hideGui");
            MethodHandle hideGuiGetter = lookup.unreflectGetter(hideGuiField);
            return MethodHandles.filterArguments(hideGuiGetter, 0, optionsGetter)
                    .asType(MethodType.methodType(boolean.class, Minecraft.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
