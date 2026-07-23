package dev.recordable;

/**
 * Available encoder backends for Record-able.
 *
 * <p>FFmpeg is the sole encoder. It provides the best quality, compression,
 * and hardware-accelerated encoding support. Requires FFmpeg installed on the system.</p>
 */
public enum EncoderType {
    /** External FFmpeg process. Best quality/speed. Required dependency. */
    FFMPEG("FFmpeg");

    private final String displayName;

    EncoderType(String displayName) {
        this.displayName = displayName;
    }

    /** Human-readable label for the settings UI. */
    public String displayName() {
        return displayName;
    }
}
