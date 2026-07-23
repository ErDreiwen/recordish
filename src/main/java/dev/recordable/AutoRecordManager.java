package dev.recordable;

import net.minecraft.client.Minecraft;

/**
 * Forge-side replacement for Fabric connection callbacks and the V1-0.08
 * delayed auto-record watchdog.
 */
public final class AutoRecordManager {
    private static final int TICKS_PER_SECOND = 20;
    private static final int DISCONNECT_WATCHDOG_TICKS = 60;

    private int countdownTicks = -1;
    private int announcedSecond = -1;
    private int worldAbsentTicks;
    private boolean sawWorldDuringRecording;
    private boolean gameStartHandled;

    public void onConnected() {
        cancel();
        worldAbsentTicks = 0;
        sawWorldDuringRecording = false;
        gameStartHandled = false;

        RecordableConfig config = RecordableConfig.get();
        RecordingManager manager = RecordingManager.getInstance();
        if (!config.stopOnDisconnect && manager.isPaused()) {
            manager.resumeRecording();
        }
        if ("world_join".equals(config.autoRecordTrigger)) {
            schedule("world join");
        }
    }

    public void onDisconnected() {
        cancel();
        gameStartHandled = false;
        worldAbsentTicks = 0;
        sawWorldDuringRecording = false;

        RecordableConfig config = RecordableConfig.get();
        RecordingManager manager = RecordingManager.getInstance();
        if (manager.isRecording()) {
            if (config.stopOnDisconnect) {
                manager.stopRecording(
                        RecordingManager.StopReason.DISCONNECT);
            } else {
                manager.pauseRecording();
            }
        }
        if ("world_leave".equals(config.autoStopTrigger)
                && !config.stopOnDisconnect
                && (manager.isRecording() || manager.isPaused())) {
            manager.stopRecording(RecordingManager.StopReason.AUTO);
        }
    }

    public void onClientTick() {
        Minecraft minecraft = Minecraft.getMinecraft();
        checkDisconnectWatchdog(minecraft);

        RecordableConfig config = RecordableConfig.get();
        if (config.replayCompatBridge
                && ReplayCompatBridge.detectReplayPlayback(minecraft)) {
            cancel();
            return;
        }
        if (!gameStartHandled
                && config.autoRecord
                && "game_start".equals(config.autoRecordTrigger)
                && RecordingManager.isInGameState(minecraft)) {
            gameStartHandled = true;
            schedule("game start");
        }

        if (countdownTicks < 0) return;
        if (!RecordingManager.isInGameState(minecraft)) return;

        countdownTicks--;
        if (countdownTicks >= 0) {
            int seconds = (int) Math.ceil(
                    countdownTicks / (double) TICKS_PER_SECOND);
            if (seconds > 0 && seconds != announcedSecond) {
                announcedSecond = seconds;
                RecordableMessages.send(
                        ChatCategory.AUTO_RECORD,
                        "Auto-recording starts in " + seconds + "...");
            }
            return;
        }

        announcedSecond = -1;
        triggerNow();
    }

    public void schedule(String reason) {
        RecordableConfig config = RecordableConfig.get();
        if (!config.enabled
                || !config.autoRecord
                || "manual".equals(config.autoRecordTrigger)
                || RecordingManager.getInstance()
                        .isActiveOrStopping()) {
            return;
        }

        int seconds = Math.max(0, config.autoRecordDelay);
        countdownTicks = seconds * TICKS_PER_SECOND;
        announcedSecond = seconds;
        if (seconds > 0) {
            RecordableMessages.send(
                    ChatCategory.AUTO_RECORD,
                    "Auto-recording starts in " + seconds + "...");
        }
        RecordableMod.LOGGER.info(
                "Scheduled auto-record in {} seconds ({})",
                seconds,
                reason);
    }

    public void cancel() {
        countdownTicks = -1;
        announcedSecond = -1;
    }

    private void triggerNow() {
        RecordableConfig config = RecordableConfig.get();
        RecordingManager manager = RecordingManager.getInstance();
        if (!config.enabled
                || !config.autoRecord
                || manager.isActiveOrStopping()) {
            countdownTicks = -1;
            return;
        }
        if (!RecordingManager.isInGameState(Minecraft.getMinecraft())) {
            countdownTicks = 0;
            return;
        }
        countdownTicks = -1;
        manager.startRecording();
        if (manager.isRecording()) {
            RecordableMessages.send(
                    ChatCategory.AUTO_RECORD,
                    "Auto-recording started.");
        }
    }

    private void checkDisconnectWatchdog(Minecraft minecraft) {
        RecordingManager manager = RecordingManager.getInstance();
        if (!manager.isRecording() && !manager.isPaused()) {
            worldAbsentTicks = 0;
            sawWorldDuringRecording = false;
            return;
        }
        if (minecraft != null && minecraft.theWorld != null) {
            sawWorldDuringRecording = true;
            worldAbsentTicks = 0;
            return;
        }
        if (!sawWorldDuringRecording) return;
        worldAbsentTicks++;
        if (worldAbsentTicks < DISCONNECT_WATCHDOG_TICKS) return;

        worldAbsentTicks = 0;
        sawWorldDuringRecording = false;
        onDisconnected();
    }
}
