package dev.recordable;

import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.Loader;

import java.nio.file.Path;

/**
 * Defensive coexistence bridge for optional replay/timeline mods.
 *
 * <p>Replay Mod has no stable cross-version playback API for this legacy
 * target, so playback detection intentionally uses only Forge's loaded-mod
 * registry and class-name inspection of the active camera, player, and
 * integrated server. Every check fails closed and can never stop a recording
 * that the bridge did not start.</p>
 */
public final class ReplayCompatBridge {
    private static final String[] REPLAY_MOD_IDS = {
        "flashback", "replaymod", "replay-mod"
    };
    private static final String[] REPLAY_CLASS_HINTS = {
        "flashback", "replaymod"
    };

    private static volatile Boolean replayModPresent;
    private static volatile boolean playbackActiveLast;
    private static volatile boolean recordingStartedByBridge;
    private static volatile Path bridgeRecordingOutput;

    private ReplayCompatBridge() {
    }

    public static boolean isReplayModPresent() {
        Boolean cached = replayModPresent;
        if (cached != null) {
            return cached.booleanValue();
        }
        boolean present = false;
        try {
            for (String id : REPLAY_MOD_IDS) {
                if (Loader.isModLoaded(id)) {
                    present = true;
                    break;
                }
            }
        } catch (Throwable ignored) {
            present = false;
        }
        replayModPresent = Boolean.valueOf(present);
        return present;
    }

    public static String getPresentReplayModId() {
        try {
            for (String id : REPLAY_MOD_IDS) {
                if (Loader.isModLoaded(id)) {
                    return id;
                }
            }
        } catch (Throwable ignored) {
            // Optional integration must never disrupt client startup.
        }
        return null;
    }

    public static String getPresentReplayModName() {
        String id = getPresentReplayModId();
        if ("flashback".equals(id)) {
            return "Flashback";
        }
        if ("replaymod".equals(id) || "replay-mod".equals(id)) {
            return "Replay Mod";
        }
        return id == null ? "another replay mod" : id;
    }

    public static boolean shouldYieldAudioDevice() {
        try {
            RecordableConfig config = RecordableConfig.get();
            return config != null
                    && config.replayCompatBridge
                    && config.replayYieldAudioDevice
                    && isReplayModPresent();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isReplayClass(Object object) {
        if (object == null) {
            return false;
        }
        try {
            Class<?> type = object.getClass();
            while (type != null && type != Object.class) {
                String name = type.getName().toLowerCase(java.util.Locale.ROOT);
                for (String hint : REPLAY_CLASS_HINTS) {
                    if (name.contains(hint)) {
                        return true;
                    }
                }
                type = type.getSuperclass();
            }
        } catch (Throwable ignored) {
            // Fail closed for unknown optional-mod object types.
        }
        return false;
    }

    public static boolean detectReplayPlayback(Minecraft minecraft) {
        if (minecraft == null || !isReplayModPresent()) {
            return false;
        }
        try {
            return isReplayClass(minecraft.getRenderViewEntity())
                    || isReplayClass(minecraft.thePlayer)
                    || isReplayClass(minecraft.getIntegratedServer());
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void onClientTick(Minecraft minecraft) {
        boolean playbackActive = detectReplayPlayback(minecraft);
        try {
            RecordableConfig config = RecordableConfig.get();
            if (config == null
                    || !config.replayCompatBridge
                    || !config.replayAutoRecordPlayback) {
                playbackActiveLast = playbackActive;
                clearRecordingOwnership();
                return;
            }

            RecordingManager manager = RecordingManager.getInstance();
            if (recordingStartedByBridge
                    && !ownsCurrentRecording(manager)) {
                clearRecordingOwnership();
            }
            if (playbackActive && !playbackActiveLast) {
                if (!manager.isActiveOrStopping()) {
                    manager.startRecording();
                    recordingStartedByBridge = manager.isRecording();
                    bridgeRecordingOutput = recordingStartedByBridge
                            ? manager.getCurrentOutputFile()
                            : null;
                    if (recordingStartedByBridge) {
                        RecordableMessages.send(
                                ChatCategory.RECORDING,
                                "\u00a7aAuto-recording "
                                        + getPresentReplayModName()
                                        + " playback.");
                    }
                }
            } else if (!playbackActive && playbackActiveLast) {
                if (ownsCurrentRecording(manager)) {
                    manager.stopRecording(RecordingManager.StopReason.AUTO);
                }
                clearRecordingOwnership();
            }
            playbackActiveLast = playbackActive;
        } catch (Throwable throwable) {
            RecordableMod.LOGGER.debug(
                    "Replay compatibility bridge tick failed.",
                    throwable);
        }
    }

    public static void resetPlaybackState() {
        playbackActiveLast = false;
        clearRecordingOwnership();
    }

    private static boolean ownsCurrentRecording(
            RecordingManager manager) {
        if (!recordingStartedByBridge
                || bridgeRecordingOutput == null
                || manager == null
                || (!manager.isRecording() && !manager.isPaused())) {
            return false;
        }
        Path currentOutput = manager.getCurrentOutputFile();
        return bridgeRecordingOutput.equals(currentOutput);
    }

    private static void clearRecordingOwnership() {
        recordingStartedByBridge = false;
        bridgeRecordingOutput = null;
    }
}
