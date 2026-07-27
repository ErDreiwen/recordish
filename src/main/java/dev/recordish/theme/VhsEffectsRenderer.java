package dev.recordish.theme;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;

import java.util.Random;

/**
 * Texture-free VHS and film effects used by the modern Recordish screens.
 *
 * <p>The implementation deliberately uses only 1.8.9 rectangle and font
 * primitives, retaining the modern timing, density, colors, and geometry.</p>
 */
public final class VhsEffectsRenderer {
    private static final Random RNG = new Random();
    private static long lastGlitchTick;
    private static int glitchY;
    private static int glitchHeight;
    private static boolean glitchActive;

    private VhsEffectsRenderer() {
    }

    public static void renderOverPanel(
            int left, int top, int right, int bottom) {
        if (right <= left || bottom <= top) {
            return;
        }
        ThemeEngine engine = ThemeEngine.get();
        ThemeColors colors = engine.colors();
        if (engine.scanlineEnabled()) {
            renderScanlines(
                    left, top, right, bottom, colors.scanlineColor);
        }
        if (engine.grainEnabled()) {
            renderFilmGrain(
                    left, top, right, bottom, colors.grainColor);
        }
        if (engine.glitchEnabled()) {
            renderGlitchBars(
                    left, top, right, bottom, colors.glitchColor);
        }
        if (engine.vignetteEnabled()) {
            renderVignette(
                    left, top, right, bottom, colors.vignetteColor);
        }
    }

    /** Horizontal scanlines every two pixels. */
    public static void renderScanlines(
            int left,
            int top,
            int right,
            int bottom,
            int color) {
        if ((color & 0xFF000000) == 0) {
            return;
        }
        for (int y = top; y < bottom; y += 2) {
            Gui.drawRect(left, y, right, y + 1, color);
        }
    }

    /** Random one-pixel film-grain samples at the modern density. */
    public static void renderFilmGrain(
            int left,
            int top,
            int right,
            int bottom,
            int color) {
        if ((color & 0xFF000000) == 0) {
            return;
        }
        int areaWidth = right - left;
        int areaHeight = bottom - top;
        if (areaWidth <= 0 || areaHeight <= 0) {
            return;
        }
        int count = Math.max(20, areaWidth * areaHeight / 400);
        int baseAlpha = color >>> 24 & 255;
        for (int index = 0; index < count; index++) {
            int x = left + RNG.nextInt(areaWidth);
            int y = top + RNG.nextInt(areaHeight);
            int halfAlpha = Math.max(1, baseAlpha / 2);
            int alpha = Math.max(
                    1,
                    baseAlpha / 2
                            + RNG.nextInt(halfAlpha));
            int grainColor =
                    alpha << 24 | color & 0x00FFFFFF;
            Gui.drawRect(x, y, x + 1, y + 1, grainColor);
        }
    }

    /** Brief random horizontal tracking glitches every three to eight seconds. */
    public static void renderGlitchBars(
            int left,
            int top,
            int right,
            int bottom,
            int color) {
        if ((color & 0xFF000000) == 0 || bottom <= top) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!glitchActive
                && now - lastGlitchTick
                        > 3000L + RNG.nextInt(5000)) {
            glitchActive = true;
            lastGlitchTick = now;
            glitchY = top
                    + RNG.nextInt(Math.max(1, bottom - top - 10));
            glitchHeight = 2 + RNG.nextInt(6);
        }
        if (!glitchActive) {
            return;
        }
        if (now - lastGlitchTick
                > 100L + RNG.nextInt(200)) {
            glitchActive = false;
            return;
        }

        int shift = -3 + RNG.nextInt(7);
        Gui.drawRect(
                left + shift,
                glitchY,
                right + shift,
                Math.min(bottom, glitchY + glitchHeight),
                color);
        int secondY = glitchY + 8 + RNG.nextInt(20);
        if (secondY < bottom) {
            Gui.drawRect(
                    left - shift,
                    secondY,
                    right - shift,
                    Math.min(bottom, secondY + 2),
                    color & 0x80FFFFFF);
        }
    }

    /** Four-layer dark-corner vignette approximation. */
    public static void renderVignette(
            int left,
            int top,
            int right,
            int bottom,
            int color) {
        if ((color & 0xFF000000) == 0) {
            return;
        }
        int areaWidth = right - left;
        int areaHeight = bottom - top;
        if (areaWidth <= 0 || areaHeight <= 0) {
            return;
        }
        int borderWidth = Math.max(6, areaWidth / 12);
        int borderHeight = Math.max(6, areaHeight / 12);
        int alpha = color >>> 24 & 255;
        for (int layer = 0; layer < 4; layer++) {
            int layerAlpha = alpha * (4 - layer) / 6;
            int layerColor = layerAlpha << 24;
            int horizontalInset = layer * (borderWidth / 4);
            int verticalInset = layer * (borderHeight / 4);
            Gui.drawRect(
                    left + horizontalInset,
                    top + verticalInset,
                    right - horizontalInset,
                    top + borderHeight - verticalInset,
                    layerColor);
            Gui.drawRect(
                    left + horizontalInset,
                    bottom - borderHeight + verticalInset,
                    right - horizontalInset,
                    bottom - verticalInset,
                    layerColor);
            Gui.drawRect(
                    left + horizontalInset,
                    top + borderHeight - verticalInset,
                    left + borderWidth - horizontalInset,
                    bottom - borderHeight + verticalInset,
                    layerColor);
            Gui.drawRect(
                    right - borderWidth + horizontalInset,
                    top + borderHeight - verticalInset,
                    right - horizontalInset,
                    bottom - borderHeight + verticalInset,
                    layerColor);
        }
    }

    public static void renderTrackingNoise(
            int left, int top, int right, int lineCount) {
        ThemeColors colors = ThemeEngine.get().colors();
        int alpha = Math.max(
                10, (colors.scanlineColor >>> 24 & 255) / 2);
        for (int index = 0; index < lineCount; index++) {
            int y = top + index * 2;
            int xOffset = RNG.nextInt(5) - 2;
            Gui.drawRect(
                    left + xOffset,
                    y,
                    right + xOffset,
                    y + 1,
                    alpha << 24 | 0x00FFFFFF);
        }
    }

    public static void renderSprocketHoles(
            int x, int top, int bottom, int color) {
        final int spacing = 18;
        final int holeWidth = 6;
        final int holeHeight = 4;
        for (int y = top + 6; y < bottom - 6; y += spacing) {
            Gui.drawRect(
                    x, y, x + holeWidth, y + holeHeight, color);
            Gui.drawRect(
                    x + 1,
                    y + 1,
                    x + holeWidth - 1,
                    y + holeHeight - 1,
                    0xFF000000);
        }
    }

    public static void renderFilmStripBorders(
            int left,
            int top,
            int right,
            int bottom,
            int borderWidth) {
        ThemeColors colors = ThemeEngine.get().colors();
        Gui.drawRect(
                left,
                top,
                left + borderWidth,
                bottom,
                colors.panelBorder);
        renderSprocketHoles(
                left + 2, top, bottom, colors.accent);
        Gui.drawRect(
                right - borderWidth,
                top,
                right,
                bottom,
                colors.panelBorder);
        renderSprocketHoles(
                right - borderWidth + 2,
                top,
                bottom,
                colors.accent);
    }

    public static void renderTapeLoadingBar(
            int left, int y, int right, float progress) {
        ThemeColors colors = ThemeEngine.get().colors();
        final int height = 3;
        Gui.drawRect(
                left,
                y,
                right,
                y + height,
                colors.panelBorder);
        float safeProgress =
                Math.max(0.0F, Math.min(1.0F, progress));
        int filled =
                (int) ((right - left) * safeProgress);
        Gui.drawRect(
                left,
                y,
                left + filled,
                y + height,
                colors.accent);
        if (safeProgress < 1.0F) {
            int shimmerX = left + filled;
            Gui.drawRect(
                    shimmerX,
                    y,
                    Math.min(shimmerX + 4, right),
                    y + height,
                    colors.accentHover);
        }
    }

    public static void renderVcrPlayBadge(
            FontRenderer font, int x, int y) {
        ThemeColors colors = ThemeEngine.get().colors();
        float pulse = ThemeEngine.pulse(
                System.currentTimeMillis(), 2000);
        int alpha = (int) (180.0F + 75.0F * pulse);
        int textColor = alpha << 24
                | colors.textPrimary & 0x00FFFFFF;
        font.drawStringWithShadow(
                "\u25B6 PLAY", x, y, textColor);
    }

    public static void renderRecDot(
            int x, int y, int radius) {
        ThemeColors colors = ThemeEngine.get().colors();
        float pulse = ThemeEngine.pulse(
                System.currentTimeMillis(), 1000);
        int alpha = (int) (100.0F + 155.0F * pulse);
        int dotColor =
                alpha << 24 | colors.accent & 0x00FFFFFF;
        Gui.drawRect(
                x - radius,
                y - radius,
                x + radius,
                y + radius,
                dotColor);
    }
}
