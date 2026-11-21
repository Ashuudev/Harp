package com.harpauto;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

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

    // Stats
    private static int songNotesSeen = 0;
    private static int songHits = 0;
    private static int songIntentionalMisses = 0;
    private static int notesSinceLastMiss = 99;
    private static long totalDelaySum = 0;

    private static final Random random = new Random();
    private static KeyMapping toggleKey;

    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.harpauto.toggle",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_P,
                "category.harpauto"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggleKey.consumeClick()) {
                isEnabled = !isEnabled;
                sendMsg("§b[HarpAuto] Status: " + (isEnabled ? "§aON" : "§cOFF"));
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

    private void scanLoop() {
        if (!isEnabled) return;
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null || client.gameMode == null) return;

        if (!(client.screen instanceof AbstractContainerScreen<?> containerScreen)) {
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
            if (!isEnabled || client.player == null || client.gameMode == null || client.screen == null) return;
            if (clickMode.equals("drop")) {
                client.gameMode.handleInventoryMouseClick(containerId, slot, 0, ClickType.THROW, client.player);
            } else if (clickMode.equals("quick")) {
                client.gameMode.handleInventoryMouseClick(containerId, slot, 0, ClickType.QUICK_MOVE, client.player);
            } else {
                client.gameMode.handleInventoryMouseClick(containerId, slot, 2, ClickType.CLONE, client.player);
            }
        });
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
        if (client != null && client.player != null) {
            client.execute(() -> client.player.displayClientMessage(Component.literal(msg), false));
        }
    }

    private void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("harp")
                .then(ClientCommandManager.literal("toggle").executes(ctx -> {
                    isEnabled = !isEnabled;
                    sendMsg("§b[HarpAuto] Status: " + (isEnabled ? "§aON" : "§cOFF"));
                    return 1;
                }))
                .then(ClientCommandManager.literal("status").executes(ctx -> {
                    sendMsg("§b[HarpAuto] " + (isEnabled ? "§aON" : "§cOFF")
                            + " §8| §bMode: §f" + clickMode
                            + " §8| §bMiss: §e" + missChancePct + "%"
                            + " §8| §bDelay: §f" + minDelayMs + "-" + maxDelayMs + "ms"
                            + " §8| §bPredict: " + (predictiveMode ? "§aON (" + predictLeadMs + "ms)" : "§7OFF"));
                    return 1;
                }))
                .then(ClientCommandManager.literal("stats").executes(ctx -> {
                    long avg = songHits > 0 ? Math.round((double) totalDelaySum / songHits) : 0;
                    String acc = songNotesSeen > 0 ? String.format("%.1f", (songHits * 100.0) / songNotesSeen) : "0.0";
                    sendMsg("§b[HarpAuto] Song: §f" + currentSong
                            + " §8| §bHits: §a" + songHits + "/" + songNotesSeen + " (§e" + acc + "%§a)"
                            + " §8| §bMisses: §6" + songIntentionalMisses
                            + " §8| §bAvg: §f" + avg + "ms");
                    return 1;
                }))
                .then(ClientCommandManager.literal("miss")
                    .then(ClientCommandManager.argument("percent", IntegerArgumentType.integer(0, 10))
                        .executes(ctx -> {
                            missChancePct = IntegerArgumentType.getInteger(ctx, "percent");
                            sendMsg("§a[HarpAuto] Controlled Miss Chance: §e" + missChancePct + "%");
                            return 1;
                        })))
                .then(ClientCommandManager.literal("mode")
                    .then(ClientCommandManager.literal("middle").executes(ctx -> { clickMode = "middle"; sendMsg("§a[HarpAuto] Mode: §fmiddle"); return 1; }))
                    .then(ClientCommandManager.literal("drop").executes(ctx -> { clickMode = "drop"; sendMsg("§a[HarpAuto] Mode: §fdrop (Q)"); return 1; }))
                    .then(ClientCommandManager.literal("quick").executes(ctx -> { clickMode = "quick"; sendMsg("§a[HarpAuto] Mode: §fquick"); return 1; })))
                .then(ClientCommandManager.literal("preset")
                    .then(ClientCommandManager.literal("safe90").executes(ctx -> {
                        minDelayMs = 24; maxDelayMs = 62; msPerSlotMin = 11; msPerSlotMax = 22;
                        globalMinGapMs = 50; missChancePct = 4.5; clickMode = "middle"; predictiveMode = false;
                        sendMsg("§a[HarpAuto] Preset: §fSAFE90");
                        return 1;
                    }))
                    .then(ClientCommandManager.literal("fast90").executes(ctx -> {
                        minDelayMs = 10; maxDelayMs = 28; msPerSlotMin = 6; msPerSlotMax = 12;
                        globalMinGapMs = 35; missChancePct = 4.0; clickMode = "drop"; predictiveMode = false;
                        sendMsg("§a[HarpAuto] Preset: §fFAST90");
                        return 1;
                    }))
                    .then(ClientCommandManager.literal("highping").executes(ctx -> {
                        minDelayMs = 5; maxDelayMs = 22; msPerSlotMin = 5; msPerSlotMax = 10;
                        globalMinGapMs = 35; missChancePct = 4.0; clickMode = "drop"; predictiveMode = true; predictLeadMs = 155;
                        sendMsg("§a[HarpAuto] Preset: §fHIGHPING");
                        return 1;
                    })))
            );
        });
    }
  }
