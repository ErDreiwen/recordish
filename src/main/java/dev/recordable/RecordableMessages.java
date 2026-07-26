package dev.recordable;

import net.minecraft.util.EnumChatFormatting;

public final class RecordableMessages {
    private RecordableMessages() {
    }

    public static void send(ChatCategory category, String text) {
        if (!isEnabled(category)) return;
        ToastQueue.push(text);
    }

    public static void error(String text) {
        send(ChatCategory.WARNINGS, EnumChatFormatting.RED + text);
    }

    private static boolean isEnabled(ChatCategory category) {
        RecordableConfig config = RecordableConfig.get();
        switch (category) {
            case RECORDING:
                return config.notifyRecording;
            case CLIPS:
                return config.notifyClips;
            case REPLAY_BUFFER:
                return config.notifyReplayBuffer;
            case AUTO_RECORD:
                return config.notifyAutoRecord;
            case BOOKMARKS:
                return config.notifyBookmarks;
            case WARNINGS:
                return config.notifyWarnings;
            default:
                return true;
        }
    }
}
