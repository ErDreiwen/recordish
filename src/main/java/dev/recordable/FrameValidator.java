package dev.recordable;

/**
 * Allocation-free diagnostics for packed, top-down RGB24 frames.
 */
public final class FrameValidator {
    public static final int BLACK_PIXEL_THRESHOLD = 8;
    public static final double BLACK_FRAME_RATIO = 0.995D;

    private static final int SAMPLES_PER_AXIS = 32;

    private FrameValidator() {
    }

    public static ValidationResult validate(CapturedFrame frame) {
        if (frame == null || frame.getPixels() == null) {
            return new ValidationResult(false, false, -1.0D, 0.0D, "No frame data");
        }
        byte[] pixels = frame.getPixels();
        long expected = frame.getWidth() * (long) frame.getHeight() * 3L;
        if (expected > Integer.MAX_VALUE || pixels.length != (int) expected) {
            return new ValidationResult(false, false, -1.0D, 0.0D, "Unexpected frame size");
        }

        SampleStats stats = sample(pixels, frame.getWidth(), frame.getHeight());
        if (stats.sampleCount == 0) {
            return new ValidationResult(false, false, -1.0D, 0.0D, "No sampled pixels");
        }

        boolean black = stats.blackRatio >= BLACK_FRAME_RATIO;
        if (black) {
            return new ValidationResult(
                false,
                true,
                stats.averageBrightness,
                stats.blackRatio,
                "Captured frame is at least 99.5% black");
        }

        if (stats.maximumLuminance - stats.minimumLuminance <= 1
                && (stats.averageBrightness < 3.0D || stats.averageBrightness > 252.0D)) {
            return new ValidationResult(
                false,
                false,
                stats.averageBrightness,
                stats.blackRatio,
                "Captured frame is uniform");
        }

        return new ValidationResult(
            true,
            false,
            stats.averageBrightness,
            stats.blackRatio,
            "OK");
    }

    /**
     * Returns whether at least 99.5% of a sparse pixel grid is near-black.
     */
    public static boolean isBlackFrame(byte[] rgb, int width, int height) {
        SampleStats stats = sample(rgb, width, height);
        return stats.sampleCount > 0 && stats.blackRatio >= BLACK_FRAME_RATIO;
    }

    /**
     * Returns sparse-grid Rec. 601 luma in the range 0..255, or {@code -1}
     * for an invalid buffer.
     */
    public static double averageBrightness(byte[] rgb, int width, int height) {
        return sample(rgb, width, height).averageBrightness;
    }

    /**
     * Returns the fraction of sampled pixels considered black, or {@code 0}
     * for an invalid buffer.
     */
    public static double blackPixelRatio(byte[] rgb, int width, int height) {
        return sample(rgb, width, height).blackRatio;
    }

    private static SampleStats sample(byte[] rgb, int width, int height) {
        if (rgb == null || width <= 0 || height <= 0) {
            return SampleStats.EMPTY;
        }
        long required = width * (long) height * 3L;
        if (required > Integer.MAX_VALUE || rgb.length < (int) required) {
            return SampleStats.EMPTY;
        }

        int stepX = Math.max(1, width / SAMPLES_PER_AXIS);
        int stepY = Math.max(1, height / SAMPLES_PER_AXIS);
        int minimum = 255;
        int maximum = 0;
        long sum = 0L;
        int count = 0;
        int black = 0;

        for (int y = 0; y < height; y += stepY) {
            int rowBase = y * width * 3;
            for (int x = 0; x < width; x += stepX) {
                int index = rowBase + x * 3;
                int r = rgb[index] & 255;
                int g = rgb[index + 1] & 255;
                int b = rgb[index + 2] & 255;
                int luminance = (r * 77 + g * 150 + b * 29) >> 8;

                minimum = Math.min(minimum, luminance);
                maximum = Math.max(maximum, luminance);
                sum += luminance;
                count++;
                if (r <= BLACK_PIXEL_THRESHOLD
                        && g <= BLACK_PIXEL_THRESHOLD
                        && b <= BLACK_PIXEL_THRESHOLD) {
                    black++;
                }
            }
        }

        if (count == 0) {
            return SampleStats.EMPTY;
        }
        double average = sum / (double) count;
        return new SampleStats(
            count,
            minimum,
            maximum,
            average,
            black / (double) count);
    }

    public static final class ValidationResult {
        private final boolean valid;
        private final boolean black;
        private final double averageBrightness;
        private final double blackPixelRatio;
        private final String message;

        ValidationResult(boolean valid, String message) {
            this(valid, false, -1.0D, 0.0D, message);
        }

        ValidationResult(
                boolean valid,
                boolean black,
                double averageBrightness,
                double blackPixelRatio,
                String message) {
            this.valid = valid;
            this.black = black;
            this.averageBrightness = averageBrightness;
            this.blackPixelRatio = blackPixelRatio;
            this.message = message;
        }

        public boolean isValid() {
            return valid;
        }

        public boolean isBlack() {
            return black;
        }

        public double getAverageBrightness() {
            return averageBrightness;
        }

        public double getBlackPixelRatio() {
            return blackPixelRatio;
        }

        public String getMessage() {
            return message;
        }
    }

    private static final class SampleStats {
        static final SampleStats EMPTY = new SampleStats(0, 0, 0, -1.0D, 0.0D);

        final int sampleCount;
        final int minimumLuminance;
        final int maximumLuminance;
        final double averageBrightness;
        final double blackRatio;

        SampleStats(
                int sampleCount,
                int minimumLuminance,
                int maximumLuminance,
                double averageBrightness,
                double blackRatio) {
            this.sampleCount = sampleCount;
            this.minimumLuminance = minimumLuminance;
            this.maximumLuminance = maximumLuminance;
            this.averageBrightness = averageBrightness;
            this.blackRatio = blackRatio;
        }
    }
}
