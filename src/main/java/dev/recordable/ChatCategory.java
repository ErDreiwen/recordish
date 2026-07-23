package dev.recordable;

/**
 * Categories of in-game chat messages produced by the mod. Each category maps to a
 * user-facing "Chat Notifications" toggle in the settings screen so players can silence
 * specific kinds of messages while keeping others. All categories default to ON.
 */
public enum ChatCategory {
    /** Messages about the main recording lifecycle (start/stop/save/errors). */
    RECORDING,
    /** Messages about auto-clips and kill montages. */
    CLIPS,
    /** Messages about the replay buffer. */
    REPLAY_BUFFER,
    /** Messages about automatic recording (auto-record on join, etc.). */
    AUTO_RECORD,
    /** Messages about bookmarks/chapter markers. */
    BOOKMARKS,
    /** Warnings (disk space, mod conflicts, etc.). */
    WARNINGS,
    /** Uncategorized/general messages. Always shown. */
    GENERAL
}
