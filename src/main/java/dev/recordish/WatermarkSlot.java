package dev.recordish;

/**
 * A single, fully-configurable watermark / branding slot (V1-0.06).
 *
 * <p>A watermark can render either a custom image (PNG with transparency, referenced by
 * an absolute file path) or a text string (with optional {username} / {date} / {time}
 * placeholders). Each slot has independent placement, opacity, scale, rotation,
 * animation and visible-time-range controls.</p>
 *
 * <p>This is a plain data holder so it serializes cleanly via Gson and can be shared
 * across all three Minecraft variants. Rendering is performed per-variant.</p>
 */
public class WatermarkSlot {

    /** Watermark content kind. */
    public enum Kind { IMAGE, TEXT }

    /** Nine-point anchor grid plus a custom absolute position. */
    public enum Position {
        TOP_LEFT, TOP_CENTER, TOP_RIGHT,
        MIDDLE_LEFT, CENTER, MIDDLE_RIGHT,
        BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT,
        CUSTOM
    }

    /** Animation style applied while the watermark is visible. */
    public enum Animation { NONE, FADE, SLIDE, PULSE }

    // ── Identity / enable ────────────────────────────────────────────────
    public boolean enabled = false;
    public String name = "Watermark";
    public Kind kind = Kind.TEXT;

    // ── Content ──────────────────────────────────────────────────────────
    /** Absolute path to a PNG image (when kind == IMAGE). */
    public String imagePath = "";
    /** Text content (when kind == TEXT). Supports {username}, {date}, {time}. */
    public String text = "Recordish";
    /** Text color as #RRGGBB or #AARRGGBB (legacy single-color field, kept for back-compat). */
    public String textColor = "#FFFFFFFF";
    /**
     * Gradient colors (1-10 hex strings, #RRGGBB or #AARRGGBB). When this list has more
     * than one entry the text is rendered with a horizontal gradient interpolated across
     * its width. When it has a single entry the text is rendered as a solid color.
     * If empty/null it falls back to {@link #textColor}.
     */
    public java.util.List<String> textColors = new java.util.ArrayList<>();
    /** Draw a subtle shadow/outline behind text for legibility. */
    public boolean textShadow = true;

    // ── Placement ────────────────────────────────────────────────────────
    public Position position = Position.BOTTOM_RIGHT;
    /** Custom X (pixels, scaled GUI coords) when position == CUSTOM. */
    public int customX = 0;
    /** Custom Y (pixels, scaled GUI coords) when position == CUSTOM. */
    public int customY = 0;
    /** Padding from the screen edges (pixels) for the 9-point grid. */
    public int padding = 8;

    // ── Appearance ───────────────────────────────────────────────────────
    /** Opacity 0-100%. */
    public int opacity = 80;
    /** Scale 10-200% (applied to image pixels or text size). */
    public int scale = 100;
    /** Rotation in degrees (-180..180). */
    public int rotation = 0;

    // ── Animation ────────────────────────────────────────────────────────
    public Animation animation = Animation.NONE;
    /** Animation speed/period in milliseconds (fade duration, pulse period, slide time). */
    public int animationDurationMs = 1000;

    // ── Visible time range (seconds into the recording) ──────────────────
    /** When true, the watermark only shows between startSeconds and endSeconds. */
    public boolean useTimeRange = false;
    public int startSeconds = 0;
    /** End second; 0 or negative means "until the end". */
    public int endSeconds = 0;

    public WatermarkSlot() {}

    public WatermarkSlot(String name, Kind kind) {
        this.name = name;
        this.kind = kind;
    }

    /** Deep copy (used for presets and editor snapshots). */
    public WatermarkSlot copy() {
        WatermarkSlot w = new WatermarkSlot();
        w.enabled = enabled;
        w.name = name;
        w.kind = kind;
        w.imagePath = imagePath;
        w.text = text;
        w.textColor = textColor;
        w.textColors = (textColors == null) ? new java.util.ArrayList<>() : new java.util.ArrayList<>(textColors);
        w.textShadow = textShadow;
        w.position = position;
        w.customX = customX;
        w.customY = customY;
        w.padding = padding;
        w.opacity = opacity;
        w.scale = scale;
        w.rotation = rotation;
        w.animation = animation;
        w.animationDurationMs = animationDurationMs;
        w.useTimeRange = useTimeRange;
        w.startSeconds = startSeconds;
        w.endSeconds = endSeconds;
        return w;
    }

    /** Clamp all numeric fields and null-guard enums/strings. */
    public void sanitize() {
        if (isBlank(name)) name = "Watermark";
        if (kind == null) kind = Kind.TEXT;
        if (position == null) position = Position.BOTTOM_RIGHT;
        if (animation == null) animation = Animation.NONE;
        if (imagePath == null) imagePath = "";
        if (text == null) text = "";
        if (isBlank(textColor)) textColor = "#FFFFFFFF";
        if (textColors == null) textColors = new java.util.ArrayList<>();
        // Migrate: seed the gradient list from the legacy single color if empty.
        if (textColors.isEmpty()) textColors.add(textColor);
        // Drop null/blank entries and normalize.
        textColors.removeIf(c -> isBlank(c));
        if (textColors.isEmpty()) textColors.add("#FFFFFFFF");
        // Clamp to 1..10 gradient stops.
        while (textColors.size() > 10) textColors.remove(textColors.size() - 1);
        // Keep the legacy single-color field in sync with the first stop.
        textColor = textColors.get(0);
        padding = clamp(padding, 0, 1000);
        opacity = clamp(opacity, 0, 100);
        scale = clamp(scale, 10, 200);
        rotation = clamp(rotation, -180, 180);
        animationDurationMs = clamp(animationDurationMs, 100, 10000);
        customX = clamp(customX, -10000, 10000);
        customY = clamp(customY, -10000, 10000);
        startSeconds = Math.max(0, startSeconds);
        if (endSeconds < 0) endSeconds = 0;
    }

    /**
     * The effective list of gradient color stops. Never returns null or empty; falls back
     * to {@link #textColor} (or white) when the list is unset. Safe to call before sanitize().
     */
    public java.util.List<String> effectiveColors() {
        if (textColors != null && !textColors.isEmpty()) {
            java.util.List<String> out = new java.util.ArrayList<>();
            for (String c : textColors) {
                if (!isBlank(c)) out.add(c);
            }
            if (!out.isEmpty()) return out;
        }
        java.util.List<String> out = new java.util.ArrayList<>();
        out.add(isBlank(textColor) ? "#FFFFFFFF" : textColor);
        return out;
    }

    /** Resolve {username}/{date}/{time} placeholders in text. */
    public String resolveText(String username) {
        String out = text == null ? "" : text;
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        out = out.replace("{username}", username == null ? "Player" : username);
        out = out.replace("{date}", now.toLocalDate().toString());
        out = out.replace("{time}", now.toLocalTime().withNano(0).toString());
        return out;
    }

    /** Whether this watermark should be visible at the given recording time (seconds). */
    public boolean visibleAt(double recordingSeconds) {
        if (!enabled) return false;
        if (!useTimeRange) return true;
        if (recordingSeconds < startSeconds) return false;
        return !(endSeconds > 0 && recordingSeconds > endSeconds);
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
