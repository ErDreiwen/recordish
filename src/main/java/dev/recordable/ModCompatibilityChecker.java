package dev.recordable;

import net.minecraftforge.fml.common.Loader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Forge 1.8.9 adaptation of the official known-conflict scanner.
 */
public final class ModCompatibilityChecker {
    private static final Map<String, String> KNOWN_CONFLICTS;
    private static List<String> detectedConflicts =
        Collections.emptyList();

    static {
        Map<String, String> conflicts =
            new LinkedHashMap<String, String>();
        conflicts.put(
            "flashback",
            "Flashback also redirects Minecraft's OpenAL sound engine. "
                + "Only one recorder can own loopback audio, so recordings "
                + "may be silent.");
        conflicts.put(
            "replaymod",
            "ReplayMod intercepts client packets and rendering and may "
                + "interfere with screen or audio capture.");
        conflicts.put(
            "replay-mod",
            "Replay Mod may conflict with Record-able's capture hooks.");
        conflicts.put(
            "bettershields",
            "BetterShields has a known shield-rendering crash that can stop "
                + "the game and corrupt an active recording.");
        KNOWN_CONFLICTS = Collections.unmodifiableMap(conflicts);
    }

    private ModCompatibilityChecker() {
    }

    public static synchronized List<String> checkAndLog() {
        List<String> conflicts = new ArrayList<String>();
        for (Map.Entry<String, String> entry
                : KNOWN_CONFLICTS.entrySet()) {
            try {
                if (Loader.isModLoaded(entry.getKey())) {
                    String message = "Mod conflict detected: '"
                        + entry.getKey() + "' - " + entry.getValue();
                    RecordableMod.LOGGER.warn("[Record-able] {}", message);
                    conflicts.add(message);
                }
            } catch (Throwable throwable) {
                RecordableMod.LOGGER.debug(
                    "Could not inspect loaded mod '{}'.",
                    entry.getKey(),
                    throwable);
            }
        }
        detectedConflicts = Collections.unmodifiableList(conflicts);
        if (conflicts.isEmpty()) {
            RecordableMod.LOGGER.info(
                "[Record-able] No known mod conflicts detected.");
        } else {
            RecordableMod.LOGGER.warn(
                "[Record-able] {} known conflicting mod(s) detected.",
                Integer.valueOf(conflicts.size()));
        }
        return detectedConflicts;
    }

    public static boolean hasConflicts() {
        return !detectedConflicts.isEmpty();
    }

    public static List<String> getDetectedConflicts() {
        return detectedConflicts;
    }

    public static void warnPlayerIfConflicts() {
        if (!hasConflicts()
                || !RecordableConfig.get().notifyWarnings) {
            return;
        }
        boolean bridge = RecordableConfig.get().replayCompatBridge;
        List<String> hard = new ArrayList<String>();
        boolean replayCoexisting = false;
        for (String conflict : detectedConflicts) {
            if (bridge && isReplayConflict(conflict)) {
                replayCoexisting = true;
            } else {
                hard.add(conflict);
            }
        }
        if (replayCoexisting) {
            RecordableMessages.send(
                ChatCategory.WARNINGS,
                "\u00A7eRecord-able compatibility bridge is active for "
                    + ReplayCompatBridge.getPresentReplayModName() + ".");
        }
        if (hard.isEmpty()) {
            return;
        }
        StringBuilder summary = new StringBuilder(
            "\u00A7cConflicting mod detected: ");
        for (int index = 0; index < hard.size(); index++) {
            if (index > 0) {
                summary.append("; ");
            }
            summary.append(hard.get(index));
        }
        RecordableMessages.send(
            ChatCategory.WARNINGS,
            summary.toString());
    }

    private static boolean isReplayConflict(String conflict) {
        String normalized = conflict == null
            ? ""
            : conflict.toLowerCase(Locale.ROOT);
        return normalized.contains("'flashback'")
            || normalized.contains("'replaymod'")
            || normalized.contains("'replay-mod'");
    }
}
