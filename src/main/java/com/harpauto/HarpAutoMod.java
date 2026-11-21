package com.harpauto;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class HarpAutoMod implements ClientModInitializer {
    private static volatile boolean isEnabled = true;

    // Core Settings
    private static int minDelayMs = 24;
    private static int maxDelayMs = 62;
    private static String clickMode = "middle"; // middle, drop, quick
    private static boolean predictiveMode = false;
    private static int predictLeadMs = 155;

    // 90%+ Human Imperfection Settings
    private static double missChancePct = 4.5;
    private static final int SAFE_START_NOTES = 6;
    private static final int MIN_NOTES_BETWEEN_MISS = 5;

    // Anti-Detection: Simulated Mouse Travel & Global Gap
    private static int msPerSlotMin = 11;
    private static int msPerSlotMax = 22;
    private static int globalMinGapMs = 50;
    private static final long SLOT_COOLDOWN_MS = 190;

    // Tracking arrays (7 lanes)
    private static final boolean[] targetState = new boolean[7];
    private static final boolean[] upperState = new boolean[7];
    private static final long[] lastClickTime = new long[7];
    private static final long[] pendingClicks = new long[7];

    // Session state
    private static int lastClickedLane = 3;
    private static long lastGlobalClick = 0;
    private static int songDriftMs = 0;
    private static String currentSong = "None";
    private static boolean wasInHarp = false;
    private static boolean pKeyWasDown = false;

    // Stats
    private static int songNotesSeen = 0;
    private static int songHits = 0;
    private static int songIntentionalMisses = 0;
    private static int notesSinceLastMiss = 99;
    private static long totalDelaySum = 0;

    private static final Random random = new Random();

    // Cached reflection handles for Minecraft 26.2 compatibility
    private static Field screenField = null;
    private static Method clickMethod = null;
    private static Class<?> clickEnumClass = null;

    @Override
    public void onInitializeClient() {
        // P key toggle without external keybinding API dependency
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client == null || getCurrentScreen(client) != null) {
                pKeyWasDown = false;
                return;
            }
            long window = GLFW.glfwGetCurrentContext();
            if (window != 0L) {
                boolean isDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_P) == GLFW.GLFW_PRESS;
                if (isDown && !pKeyWasDown) {
                    isEnabled = !isEnabled;
                    sendMsg("§b[HarpAuto] Status: " + (isEnabled ? "§aON" : "§cOFF"));
                }
                pKeyWasDown = isDown;
            }
        });

        registerCommands();

        // 8ms background scanner
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "HarpAuto-Scanner");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleAtFixedRate(this::scanLoop, 1000, 8, TimeUnit.MILLISECONDS);
    }

    private static Screen getCurrentScreen(Minecraft client) {
        try {
            if (screenField == null) {
                for (Field f : Minecraft.class.getDeclaredFields()) {
                    if (Screen.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        screenField = f;
                        break;
                    }
                }
            }
            if (screenField != null) {
                return (Screen) screenField.get(client);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private void scanLoop() {
        if (!isEnabled) return;
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null || client.gameMode == null) return;

        Screen currentScreen = getCurrentScreen(client);
        if (!(currentScreen instanceof AbstractContainerScreen<?> containerScreen)) {
            if (wasInHarp) wasInHarp = false;
            return;
        }

        String title = containerScreen.getTitle().getString();
        if (!title.startsWith("Harp -")) {
            if (wasInHarp) wasInHarp = false;
            return;
        }

        if (!wasInHarp) {
            resetSongStates();
            wasInHarp = true;
        }

        currentSong = title.replace("Harp - ", "");
        var menu = containerScreen.getMenu();
        if (menu == null || menu.slots.size() < 44) return;

        long now = System.currentTimeMillis();

        for (int i = 0; i < 7; i++) {
            int targetSlot = 37 + i;
            int upperSlot = 28 + i;

            if (predictiveMode) {
                ItemStack upperItem = menu.getSlot(upperSlot).getItem();
                String upperPath = BuiltInRegistries.ITEM.getKey(upperItem.getItem()).getPath();
                boolean isWool = upperPath.endsWith("_wool");

                if (isWool && !upperState[i] && pendingClicks[i] == 0 && (now - lastClickTime[i] > SLOT_COOLDOWN_MS)) {
                    upperState[i] = true;
                    songNotesSeen++;

                    if (shouldIntentionallyMiss()) {
                        songIntentionalMisses++;
                        notesSinceLastMiss = 0;
                        lastClickTime[i] = now;
                        continue;
                    }

                    notesSinceLastMiss++;
                    int delay = getRealisticDelay(i);
                    totalDelaySum += delay;
                    pendingClicks[i] = now + predictLeadMs + delay;
                } else if (!isWool) {
                    upperState[i] = false;
                }

                if (pendingClicks[i] > 0 && now >= pendingClicks[i]) {
                    pendingClicks[i] = 0;
                    lastClickTime[i] = now;
                    triggerClick(client, menu.containerId, targetSlot, i);
                }
            } else {
                ItemStack item = menu.getSlot(targetSlot).getItem();
                boolean isNoteReady = item.is(Items.QUARTZ_BLOCK);

                if (isNoteReady && !targetState[i] && (now - lastClickTime[i] > SLOT_COOLDOWN_MS)) {
                    targetState[i] = true;
                    songNotesSeen++;

                    if (shouldIntentionallyMiss()) {
                        songIntentionalMisses++;
                        notesSinceLastMiss = 0;
                        lastClickTime[i] = now;
                        continue;
                    }

                    notesSinceLastMiss++;
                    int delay = getRealisticDelay(i);
                    totalDelaySum += delay;

                    if (delay > 0) {
                        pendingClicks[i] = now + delay;
                    } else {
                        lastClickTime[i] = now;
                        triggerClick(client, menu.containerId, targetSlot, i);
                    }
                } else if (!isNoteReady) {
                    targetState[i] = false;
                }

                if (pendingClicks[i] > 0 && now >= pendingClicks[i]) {
                    pendingClicks[i] = 0;
                    lastClickTime[i] = now;
                    triggerClick(client, menu.containerId, targetSlot, i);
                }
            }
        }
    }

    private void triggerClick(Minecraft client, int containerId, int slot, int laneIndex) {
        long now = System.currentTimeMillis();
        if (now - lastGlobalClick < globalMinGapMs) return;

        lastGlobalClick = now;
        lastClickedLane = laneIndex;
        songHits++;

        client.execute(() -> {
            if (!isEnabled || client.player == null || client.gameMode == null || getCurrentScreen(client) == null) return;
            invokeInventoryClick(client, containerId, slot);
        });
    }

    private static void invokeInventoryClick(Minecraft client, int containerId, int slot) {
        try {
            if (clickMethod == null) {
                for (Method m : client.gameMode.getClass().getMethods()) {
                    Class<?>[] params = m.getParameterTypes();
                    if (params.length == 5
                            && params[0] == int.class
                            && params[1] == int.class
                            && params[2] == int.class
                            && params[3].isEnum()) {
                        clickMethod = m;
                        clickEnumClass = params[3];
                        break;
                    }
                }
            }
            if (clickMethod == null || clickEnumClass == null) return;

            int button = clickMode.equals("middle") ? 2 : 0;
            String targetEnumName = clickMode.equals("drop") ? "THROW"
                    : clickMode.equals("quick") ? "QUICK_MOVE" : "CLONE";

            Object enumVal = null;
            Object[] constants = clickEnumClass.getEnumConstants();
            if (constants != null) {
                for (Object c : constants) {
                    if (c.toString().equalsIgnoreCase(targetEnumName)) {
                        enumVal = c;
                        break;
                    }
                }
                if (enumVal == null && constants.length > 0) {
                    enumVal = constants[0];
                }
            }

            if (enumVal != null) {
                clickMethod.invoke(client.gameMode, containerId, slot, button, enumVal, client.player);
            }
        } catch (Throwable ignored) {}
    }

    private static int randBell(int min, int max) {
        if (max <= min) return min;
        double u = (random.nextDouble() + random.nextDouble() + random.nextDouble()) / 3.0;
        return (int) Math.floor(min + u * (max - min));
    }

    private static int getRealisticDelay(int laneIndex) {
        int fatigueMs = Math.min(10, songNotesSeen / 25);
        int baseReaction = randBell(minDelayMs, maxDelayMs) + songDriftMs + fatigueMs;
        int distance = Math.abs(laneIndex - lastClickedLane);
        int travelDelay = distance > 0 ? distance * randBell(msPerSlotMin, msPerSlotMax) : 0;
        return Math.max(0, baseReaction + travelDelay);
    }

    private static boolean shouldIntentionallyMiss() {
        if (missChancePct <= 0) return false;
        if (songNotesSeen < SAFE_START_NOTES) return false;
        if (notesSinceLastMiss < MIN_NOTES_BETWEEN_MISS) return false;
        return (random.nextDouble() * 100.0) < missChancePct;
    }

    private static void resetSongStates() {
        Arrays.fill(targetState, false);
        Arrays.fill(upperState, false);
        Arrays.fill(pendingClicks, 0L);
        lastClickedLane = 3;
        songDriftMs = random.nextInt(14) - 5;
        songNotesSeen = 0;
        songHits = 0;
        songIntentionalMisses = 0;
        notesSinceLastMiss = 99;
        totalDelaySum = 0;
    }

    private static void sendMsg(String msg) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null) return;
        client.execute(() -> {
            Component comp = Component.literal(msg);
            try {
                Method m = client.player.getClass().getMethod("displayClientMessage", Component.class, boolean.class);
                m.invoke(client.player, comp, false);
                return;
            } catch (Throwable ignored) {}
            try {
                Method m = client.player.getClass().getMethod("sendSystemMessage", Component.class);
                m.invoke(client.player, comp);
            } catch (Throwable ignored) {}
        });
    }

    private static <S> LiteralArgumentBuilder<S> lit(String name) {
        return LiteralArgumentBuilder.literal(name);
    }

    private static <S, T> RequiredArgumentBuilder<S, T> arg(String name, ArgumentType<T> type) {
        return RequiredArgumentBuilder.argument(name, type);
    }

    private void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(lit("harp")
                .then(lit("toggle").executes(ctx -> {
                    isEnabled = !isEnabled;
                    sendMsg("§b[HarpAuto] Status: " + (isEnabled ? "§aON" : "§cOFF"));
                    return 1;
                }))
                .then(lit("status").executes(ctx -> {
                    sendMsg("§b[HarpAuto] " + (isEnabled ? "§aON" : "§cOFF")
                            + " §8| §bMode: §f" + clickMode
                            + " §8| §bMiss: §e" + missChancePct + "%"
                            + " §8| §bDelay: §f" + minDelayMs + "-" + maxDelayMs + "ms"
                            + " §8| §bPredict: " + (predictiveMode ? "§aON (" + predictLeadMs + "ms)" : "§7OFF"));
                    return 1;
                }))
                .then(lit("stats").executes(ctx -> {
                    long avg = songHits > 0 ? Math.round((double) totalDelaySum / songHits) : 0;
                    String acc = songNotesSeen > 0 ? String.format("%.1f", (songHits * 100.0) / songNotesSeen) : "0.0";
                    sendMsg("§b[HarpAuto] Song: §f" + currentSong
                            + " §8| §bHits: §a" + songHits + "/" + songNotesSeen + " (§e" + acc + "%§a)"
                            + " §8| §bMisses: §6" + songIntentionalMisses
                            + " §8| §bAvg: §f" + avg + "ms");
                    return 1;
                }))
                .then(lit("miss")
                    .then(arg("percent", IntegerArgumentType.integer(0, 10))
                        .executes(ctx -> {
                            missChancePct = IntegerArgumentType.getInteger(ctx, "percent");
                            sendMsg("§a[HarpAuto] Controlled Miss Chance: §e" + missChancePct + "%");
                            return 1;
                        })))
                .then(lit("mode")
                    .then(lit("middle").executes(ctx -> { clickMode = "middle"; sendMsg("§a[HarpAuto] Mode: §fmiddle"); return 1; }))
                    .then(lit("drop").executes(ctx -> { clickMode = "drop"; sendMsg("§a[HarpAuto] Mode: §fdrop (Q)"); return 1; }))
                    .then(lit("quick").executes(ctx -> { clickMode = "quick"; sendMsg("§a[HarpAuto] Mode: §fquick"); return 1; })))
                .then(lit("preset")
                    .then(lit("safe90").executes(ctx -> {
                        minDelayMs = 24; maxDelayMs = 62; msPerSlotMin = 11; msPerSlotMax = 22;
                        globalMinGapMs = 50; missChancePct = 4.5; clickMode = "middle"; predictiveMode = false;
                        sendMsg("§a[HarpAuto] Preset: §fSAFE90");
                        return 1;
                    }))
                    .then(lit("fast90").executes(ctx -> {
                        minDelayMs = 10; maxDelayMs = 28; msPerSlotMin = 6; msPerSlotMax = 12;
                        globalMinGapMs = 35; missChancePct = 4.0; clickMode = "drop"; predictiveMode = false;
                        sendMsg("§a[HarpAuto] Preset: §fFAST90");
                        return 1;
                    }))
                    .then(lit("highping").executes(ctx -> {
                        minDelayMs = 5; maxDelayMs = 22; msPerSlotMin = 5; msPerSlotMax = 10;
                        globalMinGapMs = 35; missChancePct = 4.0; clickMode = "drop"; predictiveMode = true; predictLeadMs = 155;
                        sendMsg("§a[HarpAuto] Preset: §fHIGHPING");
                        return 1;
                    })))
            );
        });
    }
          }
            
