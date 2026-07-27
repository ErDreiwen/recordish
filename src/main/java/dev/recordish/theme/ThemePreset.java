package dev.recordish.theme;

/**
 * Available UI theme presets for the Recordish mod.
 * Each preset defines a complete visual identity including colors, effects, and layout style.
 */
public enum ThemePreset {
    CLASSIC("Classic", "Clean modern dark theme"),
    VHS("VHS Retro", "Nostalgic VHS tape aesthetic with scanlines and static"),
    CINEMA("Cinema", "Film strip and movie theater inspired"),
    NEON("Neon Synthwave", "Vibrant neon colors on dark background"),
    MINIMAL("Minimal", "Ultra-clean minimal design");

    public final String displayName;
    public final String description;

    ThemePreset(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public ThemePreset next() {
        ThemePreset[] values = values();
        return values[(this.ordinal() + 1) % values.length];
    }

    public ThemePreset prev() {
        ThemePreset[] values = values();
        return values[(this.ordinal() - 1 + values.length) % values.length];
    }
}
