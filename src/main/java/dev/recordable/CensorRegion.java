package dev.recordable;

/**
 * V1-0.08 Streamer Mode: a single censor region.
 *
 * <p>A censor region hides a rectangular part of every recorded frame so streamers
 * can keep sensitive information (coordinates, chat, donation goals, webcam area,
 * server IPs, etc.) out of the saved video. Coordinates are stored as fractions of
 * the frame (0.0 - 1.0) so they remain correct at any recording resolution.</p>
 *
 * <p>Origin is the top-left corner of the frame (the recorded RGB buffer is already
 * stored top-down by {@code ScreenCapture}).</p>
 *
 * <p><b>V1-0.08 censor upgrade:</b> regions are now fully solid, stretchable overlays.
 * Every region is rendered 100% opaque (no transparency, no pixelation, no blur) so
 * there is no way for a viewer to recover the hidden content frame-by-frame. Each
 * region can use a single custom colour or a two-colour gradient, and can paint a
 * custom text label directly onto the censor block.</p>
 *
 * <p>This is a plain mutable data holder so Gson can (de)serialize it inside the
 * config file without custom adapters.</p>
 */
public final class CensorRegion {

    /** How a censor region paints the pixels underneath it. Always 100% opaque. */
    public enum Style {
        /** Single flat custom colour, fully opaque. */
        SOLID,
        /** Two-colour gradient between {@code color} and {@code colorEnd}, fully opaque. */
        GRADIENT
    }

    /** Direction the gradient sweeps when {@link Style#GRADIENT} is used. */
    public enum GradientDirection {
        HORIZONTAL,
        VERTICAL,
        DIAGONAL
    }

    /** Fraction of frame width for the left edge (0.0 - 1.0). */
    public double x = 0.0;
    /** Fraction of frame height for the top edge (0.0 - 1.0). */
    public double y = 0.0;
    /** Fraction of frame width for the region width (0.0 - 1.0). */
    public double width = 0.25;
    /** Fraction of frame height for the region height (0.0 - 1.0). */
    public double height = 0.10;
    /** Censor style applied to this region. */
    public Style style = Style.SOLID;

    /** Primary fill colour as 0xRRGGBB (alpha ignored - always fully opaque). */
    public int color = 0x000000;
    /** Secondary colour for {@link Style#GRADIENT} as 0xRRGGBB. */
    public int colorEnd = 0x444444;
    /** Direction the gradient sweeps. */
    public GradientDirection gradientDirection = GradientDirection.HORIZONTAL;

    /** User-facing label shown in the editor and live preview. */
    public String label = "Censor";
    /** Whether the label text is painted onto the censor block in the recording. */
    public boolean showLabel = false;
    /** Colour of the painted label text as 0xRRGGBB. */
    public int textColor = 0xFFFFFF;

    /** Whether this region is currently active. */
    public boolean enabled = true;

    public CensorRegion() {
        // Required by Gson.
    }

    public CensorRegion(double x, double y, double width, double height, Style style, String label) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.style = style;
        this.label = label;
        this.enabled = true;
    }

    /** Returns a defensive copy of this region. */
    public CensorRegion copy() {
        CensorRegion c = new CensorRegion(x, y, width, height, style, label);
        c.color = color;
        c.colorEnd = colorEnd;
        c.gradientDirection = gradientDirection;
        c.showLabel = showLabel;
        c.textColor = textColor;
        c.enabled = enabled;
        return c;
    }

    /** Clamps all fractional fields into the valid [0,1] range and repairs nulls. */
    public void sanitize() {
        x = clamp01(x);
        y = clamp01(y);
        width = clamp01(width);
        height = clamp01(height);
        // Keep the region inside the frame.
        if (x + width > 1.0) width = 1.0 - x;
        if (y + height > 1.0) height = 1.0 - y;
        if (width <= 0.0) width = 0.02;
        if (height <= 0.0) height = 0.02;
        // Repair nulls (e.g. configs written by older builds with removed styles).
        if (style == null) style = Style.SOLID;
        if (gradientDirection == null) gradientDirection = GradientDirection.HORIZONTAL;
        if (label == null || label.trim().isEmpty()) label = "Censor";
        // Strip any stray alpha bits so colours are always treated as opaque RGB.
        color &= 0xFFFFFF;
        colorEnd &= 0xFFFFFF;
        textColor &= 0xFFFFFF;
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v) || v < 0.0) return 0.0;
        return Math.min(v, 1.0);
    }
}
