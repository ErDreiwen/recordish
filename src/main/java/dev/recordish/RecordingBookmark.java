package dev.recordish;

/**
 * A chapter marker placed relative to the effective recording timeline.
 */
public final class RecordingBookmark {
    private final long timestampMs;
    private final String description;

    public RecordingBookmark(long timestampMs, String description) {
        this.timestampMs = Math.max(0L, timestampMs);
        this.description = description == null ? "Bookmark" : description;
    }

    public long getTimestampMs() {
        return timestampMs;
    }

    public String getDescription() {
        return description;
    }

    public String toFileLine() {
        long totalSeconds = timestampMs / 1000L;
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        return String.format("[%02d:%02d:%02d] %s", hours, minutes, seconds, description);
    }
}
