package dev.recordish.theme;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;

/**
 * Animated two-reel loading panel from the modern theme system.
 */
public final class FilmReelLoadingWidget {
    private final int x;
    private final int y;
    private final int width;
    private final int height;
    private String statusText = "Loading...";
    private float progress = -1.0F;

    public FilmReelLoadingWidget(
            int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    public void setStatusText(String text) {
        statusText = text;
    }

    public void setProgress(float progress) {
        this.progress = progress;
    }

    public void draw(Minecraft minecraft) {
        draw(minecraft.fontRendererObj);
    }

    public void draw(FontRenderer font) {
        ThemeColors colors = ThemeEngine.get().colors();
        Gui.drawRect(
                x,
                y,
                x + width,
                y + height,
                colors.sectionBackground);

        int reelRadius = Math.min(height / 3, 12);
        int leftReelX = x + width / 3;
        int rightReelX = x + 2 * width / 3;
        int reelY = y + height / 2 - 4;
        long tick = System.currentTimeMillis();
        drawSpinningReel(
                leftReelX, reelY, reelRadius, tick, colors);
        drawSpinningReel(
                rightReelX,
                reelY,
                reelRadius,
                tick + 500L,
                colors);

        int stripY = reelY - 1;
        Gui.drawRect(
                leftReelX + reelRadius,
                stripY,
                rightReelX - reelRadius,
                stripY + 3,
                colors.accent & 0x80FFFFFF);
        int dotOffset = (int) ((tick / 80L) % 6L);
        for (int stripX = leftReelX + reelRadius + 2;
                stripX < rightReelX - reelRadius - 2;
                stripX += 6) {
            Gui.drawRect(
                    stripX + dotOffset,
                    stripY,
                    stripX + dotOffset + 2,
                    stripY + 1,
                    colors.textMuted);
        }

        int barY = y + height - 6;
        if (progress >= 0.0F) {
            VhsEffectsRenderer.renderTapeLoadingBar(
                    x + 4,
                    barY,
                    x + width - 4,
                    progress);
        } else {
            int shimmerWidth = width / 4;
            int shimmerPosition =
                    (int) ((tick / 8L)
                            % (width + shimmerWidth))
                            - shimmerWidth;
            int shimmerLeft = Math.max(
                    x + 4, x + shimmerPosition);
            int shimmerRight = Math.min(
                    x + width - 4,
                    x + shimmerPosition + shimmerWidth);
            Gui.drawRect(
                    x + 4,
                    barY,
                    x + width - 4,
                    barY + 3,
                    colors.panelBorder);
            if (shimmerRight > shimmerLeft) {
                Gui.drawRect(
                        shimmerLeft,
                        barY,
                        shimmerRight,
                        barY + 3,
                        colors.accent);
            }
        }

        if (statusText != null) {
            int textX = x + (width
                    - font.getStringWidth(statusText)) / 2;
            font.drawString(
                    statusText,
                    textX,
                    y + 3,
                    colors.textSecondary);
        }
    }

    private static void drawSpinningReel(
            int centerX,
            int centerY,
            int radius,
            long tick,
            ThemeColors colors) {
        Gui.drawRect(
                centerX - radius,
                centerY - radius,
                centerX + radius,
                centerY + radius,
                colors.panelBorder);
        Gui.drawRect(
                centerX - radius + 1,
                centerY - radius + 1,
                centerX + radius - 1,
                centerY + radius - 1,
                colors.sectionBackground);
        double angle = (tick % 1500L)
                / 1500.0D * Math.PI * 2.0D;
        for (int index = 0; index < 3; index++) {
            double spokeAngle =
                    angle + index * Math.PI * 2.0D / 3.0D;
            int offsetX =
                    (int) (Math.cos(spokeAngle) * (radius - 2));
            int offsetY =
                    (int) (Math.sin(spokeAngle) * (radius - 2));
            Gui.drawRect(
                    centerX - 1,
                    centerY - 1,
                    centerX + offsetX,
                    centerY + offsetY,
                    colors.accent);
        }
        Gui.drawRect(
                centerX - 2,
                centerY - 2,
                centerX + 2,
                centerY + 2,
                colors.accent);
    }
}
