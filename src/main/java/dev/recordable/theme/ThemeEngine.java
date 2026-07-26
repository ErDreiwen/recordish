package dev.recordable.theme;

import dev.recordable.RecordableConfig;
import dev.recordable.RecordableMod;

/**
 * Central source of the active Record-able UI palette and visual effects.
 *
 * <p>This is the Java 8 / Forge 1.8.9 counterpart of the modern theme engine.
 * Screens can safely query it every frame; configuration reloads replace the
 * small immutable state values atomically.</p>
 */
public final class ThemeEngine {
    private static final ThemeEngine INSTANCE = new ThemeEngine();

    private volatile ThemePreset activePreset = ThemePreset.VHS;
    private volatile ThemeColors colors = ThemeColors.vhs();
    private volatile boolean scanlineEnabled = true;
    private volatile boolean grainEnabled = true;
    private volatile boolean glitchEnabled = true;
    private volatile boolean vignetteEnabled = true;
    private volatile boolean animationsEnabled = true;

    private ThemeEngine() {
    }

    public static ThemeEngine get() {
        return INSTANCE;
    }

    /** Reloads the complete theme state from the shared client config. */
    public void loadFromConfig() {
        try {
            RecordableConfig config = RecordableConfig.get();
            if (config == null) {
                return;
            }
            ThemePreset preset = config.uiTheme;
            if (preset == null) {
                preset = ThemePreset.VHS;
            }
            applyPreset(preset);
            scanlineEnabled = config.uiScanlines;
            grainEnabled = config.uiFilmGrain;
            glitchEnabled = config.uiGlitchEffects;
            vignetteEnabled = config.uiVignette;
            animationsEnabled = config.uiAnimations;
        } catch (Exception exception) {
            RecordableMod.LOGGER.debug(
                    "Failed to load theme from config", exception);
        }
    }

    public void applyPreset(ThemePreset preset) {
        ThemePreset safePreset =
                preset == null ? ThemePreset.VHS : preset;
        activePreset = safePreset;
        colors = ThemeColors.forPreset(safePreset);
    }

    public ThemePreset preset() {
        return activePreset;
    }

    public ThemeColors colors() {
        return colors;
    }

    public boolean scanlineEnabled() {
        return scanlineEnabled
                && (colors.scanlineColor & 0xFF000000) != 0;
    }

    public boolean grainEnabled() {
        return grainEnabled
                && (colors.grainColor & 0xFF000000) != 0;
    }

    public boolean glitchEnabled() {
        return glitchEnabled
                && (colors.glitchColor & 0xFF000000) != 0;
    }

    public boolean vignetteEnabled() {
        return vignetteEnabled
                && (colors.vignetteColor & 0xFF000000) != 0;
    }

    public boolean animationsEnabled() {
        return animationsEnabled;
    }

    /** Linear interpolation between two ARGB colors. */
    public static int lerpColor(int first, int second, float progress) {
        if (progress <= 0.0F) {
            return first;
        }
        if (progress >= 1.0F) {
            return second;
        }
        int firstAlpha = first >>> 24 & 255;
        int firstRed = first >>> 16 & 255;
        int firstGreen = first >>> 8 & 255;
        int firstBlue = first & 255;
        int secondAlpha = second >>> 24 & 255;
        int secondRed = second >>> 16 & 255;
        int secondGreen = second >>> 8 & 255;
        int secondBlue = second & 255;
        return (int) (firstAlpha
                + (secondAlpha - firstAlpha) * progress) << 24
                | (int) (firstRed
                        + (secondRed - firstRed) * progress) << 16
                | (int) (firstGreen
                        + (secondGreen - firstGreen) * progress) << 8
                | (int) (firstBlue
                        + (secondBlue - firstBlue) * progress);
    }

    /** Sine pulse in the inclusive 0..1 range. */
    public static float pulse(long tickMillis, int periodMillis) {
        int safePeriod = Math.max(1, periodMillis);
        float phase = (tickMillis % safePeriod) / (float) safePeriod;
        return (float) (0.5D
                + 0.5D * Math.sin(phase * Math.PI * 2.0D));
    }
}
