package dev.recordable.screen;

import dev.recordable.RecordableConfig;
import dev.recordable.theme.ThemeColors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;

/**
 * Small texture-free themed button used by the legacy 1.8.9 screens.
 *
 * <p>Using rectangles instead of the vanilla widget atlas keeps the controls
 * crisp at every GUI scale and lets the existing Record-able theme presets
 * remain visible on the legacy client.</p>
 */
public class RecordableButton extends GuiButton {
    public RecordableButton(
            int buttonId,
            int x,
            int y,
            int width,
            int height,
            String label) {
        super(buttonId, x, y, width, height, label);
    }

    @Override
    public void drawButton(Minecraft minecraft, int mouseX, int mouseY) {
        if (!visible) {
            return;
        }

        hovered = mouseX >= xPosition
                && mouseY >= yPosition
                && mouseX < xPosition + width
                && mouseY < yPosition + height;

        ThemeColors colors = colors();
        int background = !enabled
                ? darken(colors.buttonBackground, 0.55F)
                : hovered
                        ? colors.buttonBackgroundHover
                        : colors.buttonBackground;
        int border = hovered && enabled
                ? colors.accent
                : colors.buttonBorder;
        int text = !enabled
                ? colors.textMuted
                : hovered
                        ? colors.textPrimary
                        : colors.buttonText;

        drawRect(xPosition, yPosition, xPosition + width, yPosition + height,
                border);
        drawRect(
                xPosition + 1,
                yPosition + 1,
                xPosition + width - 1,
                yPosition + height - 1,
                background);

        if (hovered && enabled) {
            drawRect(
                    xPosition + 1,
                    yPosition + 1,
                    xPosition + 3,
                    yPosition + height - 1,
                    colors.accent);
        }

        if (RecordableConfig.get().uiScanlines && height >= 16) {
            int line = yPosition + 3
                    + (int) ((System.currentTimeMillis() / 70L)
                    % Math.max(1, height - 6));
            drawRect(
                    xPosition + 3,
                    line,
                    xPosition + width - 2,
                    line + 1,
                    colors.scanlineColor);
        }

        mouseDragged(minecraft, mouseX, mouseY);
        drawCenteredString(
                minecraft.fontRendererObj,
                displayString,
                xPosition + width / 2,
                yPosition + (height - 8) / 2,
                text);
    }

    private static ThemeColors colors() {
        try {
            return ThemeColors.forPreset(RecordableConfig.get().uiTheme);
        } catch (RuntimeException ignored) {
            return ThemeColors.vhs();
        }
    }

    private static int darken(int color, float factor) {
        int alpha = color >>> 24;
        int red = (int) (((color >>> 16) & 255) * factor);
        int green = (int) (((color >>> 8) & 255) * factor);
        int blue = (int) ((color & 255) * factor);
        return alpha << 24 | red << 16 | green << 8 | blue;
    }
}
