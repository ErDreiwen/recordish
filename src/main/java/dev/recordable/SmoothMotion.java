package dev.recordable;

/**
 * Builds the optional V1-0.08 FFmpeg interpolation filter.
 */
public final class SmoothMotion {
    public static final String MODE_BLEND = "blend";
    public static final String MODE_MOTION = "motion";

    private SmoothMotion() {
    }

    public static String buildFilter(
            RecordableConfig config,
            int outputFps) {
        if (config == null || !config.smoothMotionEnabled) {
            return null;
        }
        int fps = Math.max(1, outputFps);
        if (MODE_MOTION.equals(sanitizeMode(config.smoothMotionMode))) {
            return "minterpolate=fps=" + fps
                    + ":mi_mode=mci:mc_mode=aobmc"
                    + ":me_mode=bidir:vsbmc=1";
        }
        return "minterpolate=fps=" + fps + ":mi_mode=blend";
    }

    public static String describe(String mode) {
        return MODE_MOTION.equals(sanitizeMode(mode))
                ? "Motion (smoothest, heavier CPU)"
                : "Blend (light, balanced)";
    }

    public static String sanitizeMode(String mode) {
        return MODE_MOTION.equalsIgnoreCase(
                mode == null ? "" : mode.trim())
                ? MODE_MOTION
                : MODE_BLEND;
    }
}
