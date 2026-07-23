package dev.recordable.filter;

/**
 * Available live-preview video filter presets.
 *
 * <p>This is a minimal enum used by the on-screen live-preview overlay
 * (see {@code FilterPreviewRenderer}). It intentionally carries no pixel-baking
 * implementation; the preview is a real-time visual approximation drawn with
 * draw-context primitives.</p>
 */
public enum FilterType {
    NONE("None"),
    VHS("VHS"),
    LCD_MOIRE("LCD Moire"),
    CRT("CRT");

    public final String displayName;

    FilterType(String displayName) {
        this.displayName = displayName;
    }

    /** Cycle to the next filter type. */
    public FilterType next() {
        FilterType[] values = values();
        return values[(this.ordinal() + 1) % values.length];
    }

    /** Cycle to the previous filter type. */
    public FilterType previous() {
        FilterType[] values = values();
        return values[(this.ordinal() - 1 + values.length) % values.length];
    }

    /** Resolve a filter type from its enum name, returning NONE when unknown. */
    public static FilterType fromName(String name) {
        if (name == null) return NONE;
        for (FilterType type : values()) {
            if (type.name().equalsIgnoreCase(name)) return type;
        }
        return NONE;
    }
}
