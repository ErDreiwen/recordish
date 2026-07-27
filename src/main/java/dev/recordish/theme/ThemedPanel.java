package dev.recordish.theme;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;

/**
 * Shared 1.8.9 drawing helpers for modern Recordish panels and decorations.
 */
public final class ThemedPanel {
    private ThemedPanel() {
    }

    /**
     * Darkens the legacy dirt/world background to match the modern menu
     * backdrop used by Record-able 26.2 while retaining a faint sense of the
     * underlying screen when opened in-game.
     */
    public static void drawMenuBackdrop(int width, int height) {
        ThemeColors colors = ThemeEngine.get().colors();
        int tint = 0xDC000000
                | colors.sectionBackground & 0x00FFFFFF;
        Gui.drawRect(0, 0, width, height, tint);
    }

    /** Draws a themed panel with the two-pixel accent bar used in V1-0.09. */
    public static void drawPanel(
            int left, int top, int right, int bottom) {
        ThemeColors colors = ThemeEngine.get().colors();
        Gui.drawRect(
                left, top, right, bottom, colors.panelBackground);
        Gui.drawRect(
                left, top, right, top + 2, colors.accent);
        Gui.drawRect(
                left,
                bottom - 1,
                right,
                bottom,
                colors.panelBorder);
        Gui.drawRect(
                left,
                top,
                left + 1,
                bottom,
                colors.panelBorder);
        Gui.drawRect(
                right - 1,
                top,
                right,
                bottom,
                colors.panelBorder);
        VhsEffectsRenderer.renderOverPanel(
                left + 1, top + 2, right - 1, bottom - 1);
    }

    /** Draws the Cinema variant with twelve-pixel sprocket strips. */
    public static void drawFilmPanel(
            int left, int top, int right, int bottom) {
        ThemeColors colors = ThemeEngine.get().colors();
        final int stripWidth = 12;
        Gui.drawRect(
                left + stripWidth,
                top,
                right - stripWidth,
                bottom,
                colors.panelBackground);
        VhsEffectsRenderer.renderFilmStripBorders(
                left,
                top,
                right,
                bottom,
                stripWidth);
        VhsEffectsRenderer.renderOverPanel(
                left + stripWidth,
                top,
                right - stripWidth,
                bottom);
    }

    public static void drawSectionHeader(
            FontRenderer font,
            String text,
            int x,
            int y,
            int maximumWidth) {
        ThemeColors colors = ThemeEngine.get().colors();
        ThemePreset preset = ThemeEngine.get().preset();
        String safeText = text == null ? "" : text;
        font.drawStringWithShadow(
                safeText, x, y, colors.headerText);
        int textWidth = font.getStringWidth(safeText);
        int lineY = y + 10;
        Gui.drawRect(
                x,
                lineY,
                x + textWidth,
                lineY + 1,
                colors.headerUnderline);
        int extensionEnd = Math.min(
                x + maximumWidth, x + textWidth + 40);
        if (extensionEnd > x + textWidth + 4) {
            int fadeColor =
                    colors.headerUnderline & 0x40FFFFFF;
            Gui.drawRect(
                    x + textWidth + 2,
                    lineY,
                    extensionEnd,
                    lineY + 1,
                    fadeColor);
        }
        if (preset == ThemePreset.VHS
                || preset == ThemePreset.NEON) {
            font.drawStringWithShadow(
                    "\u258C", x - 8, y, colors.accent);
        } else if (preset == ThemePreset.CINEMA) {
            font.drawStringWithShadow(
                    "\u2605", x - 10, y, colors.accent);
        }
    }

    public static void drawDivider(
            int x, int y, int width) {
        Gui.drawRect(
                x,
                y,
                x + width,
                y + 1,
                ThemeEngine.get().colors().panelBorder);
    }

    public static void drawScrollbar(
            int barLeft,
            int barTop,
            int barBottom,
            int thumbTop,
            int thumbHeight) {
        ThemeColors colors = ThemeEngine.get().colors();
        int barRight = barLeft + 3;
        Gui.drawRect(
                barLeft,
                barTop,
                barRight,
                barBottom,
                colors.scrollTrack);
        Gui.drawRect(
                barLeft,
                thumbTop,
                barRight,
                thumbTop + thumbHeight,
                colors.scrollThumb);
    }

    public static void drawReelLoading(
            int centerX, int centerY, int radius) {
        ThemeColors colors = ThemeEngine.get().colors();
        long tick = System.currentTimeMillis();
        double angle = (tick % 2000L)
                / 2000.0D * Math.PI * 2.0D;
        for (int index = 0; index < 8; index++) {
            double dotAngle =
                    angle + index * Math.PI / 4.0D;
            int offsetX =
                    (int) (Math.cos(dotAngle) * radius);
            int offsetY =
                    (int) (Math.sin(dotAngle) * radius);
            int alpha = 80
                    + (int) (175
                            * ((index + (tick / 125L) % 8L) % 8L)
                            / 8.0D);
            alpha = Math.min(255, alpha);
            int dotColor = alpha << 24
                    | colors.accent & 0x00FFFFFF;
            Gui.drawRect(
                    centerX + offsetX - 1,
                    centerY + offsetY - 1,
                    centerX + offsetX + 2,
                    centerY + offsetY + 2,
                    dotColor);
        }
    }

    public static void drawVhsStatusBadge(
            FontRenderer font,
            String text,
            int x,
            int y,
            boolean blink) {
        ThemeColors colors = ThemeEngine.get().colors();
        String safeText = text == null ? "" : text;
        int background = colors.panelBackground & 0xCC000000
                | colors.panelBackground & 0x00FFFFFF;
        int textWidth = font.getStringWidth(safeText);
        Gui.drawRect(
                x - 2,
                y - 1,
                x + textWidth + 2,
                y + 10,
                background);
        int textColor = colors.textPrimary;
        if (blink) {
            float pulse = ThemeEngine.pulse(
                    System.currentTimeMillis(), 1200);
            int alpha = (int) (130.0F + 125.0F * pulse);
            textColor = alpha << 24
                    | colors.textPrimary & 0x00FFFFFF;
        }
        font.drawStringWithShadow(
                safeText, x, y, textColor);
    }

    public static void drawCategoryTab(
            FontRenderer font,
            String label,
            int x,
            int y,
            int width,
            boolean selected) {
        ThemeColors colors = ThemeEngine.get().colors();
        Gui.drawRect(
                x,
                y,
                x + width,
                y + 16,
                selected
                        ? colors.sectionHover
                        : colors.sectionBackground);
        if (selected) {
            Gui.drawRect(
                    x,
                    y,
                    x + 2,
                    y + 16,
                    colors.accent);
        }
        font.drawStringWithShadow(
                label == null ? "" : label,
                x + 6,
                y + 4,
                selected
                        ? colors.textPrimary
                        : colors.textSecondary);
    }
}
