package dev.recordish.theme;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;

/**
 * Texture-free themed button for the Forge 1.8.9 UI.
 */
public class ThemedButton extends GuiButton {
    public interface PressAction {
        void onPress(ThemedButton button);
    }

    private final PressAction onPress;
    private float hoverProgress;

    public ThemedButton(
            int id,
            int x,
            int y,
            int width,
            int height,
            String message) {
        this(id, x, y, width, height, message, null);
    }

    public ThemedButton(
            int id,
            int x,
            int y,
            int width,
            int height,
            String message,
            PressAction onPress) {
        super(id, x, y, width, height, message);
        this.onPress = onPress;
    }

    public static ThemedButton create(
            int id,
            int x,
            int y,
            int width,
            int height,
            String message,
            PressAction onPress) {
        return new ThemedButton(
                id, x, y, width, height, message, onPress);
    }

    @Override
    public boolean mousePressed(
            Minecraft minecraft, int mouseX, int mouseY) {
        boolean pressed =
                super.mousePressed(minecraft, mouseX, mouseY);
        if (pressed && onPress != null) {
            onPress.onPress(this);
        }
        return pressed;
    }

    @Override
    public void drawButton(
            Minecraft minecraft, int mouseX, int mouseY) {
        if (!visible) {
            return;
        }
        hovered = mouseX >= xPosition
                && mouseY >= yPosition
                && mouseX < xPosition + width
                && mouseY < yPosition + height;

        float target = hovered && enabled ? 1.0F : 0.0F;
        if (ThemeEngine.get().animationsEnabled()) {
            hoverProgress += (target - hoverProgress) * 0.25F;
            if (Math.abs(target - hoverProgress) < 0.01F) {
                hoverProgress = target;
            }
        } else {
            hoverProgress = target;
        }

        ThemeColors colors = ThemeEngine.get().colors();
        if (enabled) {
            drawThemedButtonRect(
                    xPosition,
                    yPosition,
                    width,
                    height,
                    hovered,
                    hoverProgress);
        } else {
            Gui.drawRect(
                    xPosition,
                    yPosition,
                    xPosition + width,
                    yPosition + height,
                    colors.buttonBorder);
            Gui.drawRect(
                    xPosition + 1,
                    yPosition + 1,
                    xPosition + width - 1,
                    yPosition + height - 1,
                    darken(colors.buttonBackground, 0.55F));
        }

        mouseDragged(minecraft, mouseX, mouseY);
        int textColor = enabled
                ? hovered
                        ? colors.textPrimary
                        : colors.buttonText
                : colors.textMuted;
        drawCenteredString(
                minecraft.fontRendererObj,
                displayString,
                xPosition + width / 2,
                yPosition + (height - 8) / 2,
                textColor);
    }

    public float getHoverProgress() {
        return hoverProgress;
    }

    /**
     * Modern themed-button renderer, adapted from GuiGraphics to Gui.drawRect.
     */
    public static void drawThemedButtonRect(
            int x,
            int y,
            int width,
            int height,
            boolean hovered,
            float hoverProgress) {
        ThemeColors colors = ThemeEngine.get().colors();
        float safeProgress =
                Math.max(0.0F, Math.min(1.0F, hoverProgress));
        int background = ThemeEngine.lerpColor(
                colors.buttonBackground,
                colors.buttonBackgroundHover,
                safeProgress);
        int border = ThemeEngine.lerpColor(
                colors.buttonBorder,
                colors.accent,
                safeProgress);

        Gui.drawRect(
                x, y, x + width, y + height, background);
        Gui.drawRect(x, y, x + width, y + 1, border);
        Gui.drawRect(
                x,
                y + height - 1,
                x + width,
                y + height,
                border);
        Gui.drawRect(x, y, x + 1, y + height, border);
        Gui.drawRect(
                x + width - 1,
                y,
                x + width,
                y + height,
                border);

        if (safeProgress > 0.1F
                && height > 2
                && ThemeEngine.get().scanlineEnabled()) {
            int scanY = y
                    + (int) ((System.currentTimeMillis() / 30L)
                            % height);
            int scanAlpha = (int) (20.0F * safeProgress);
            Gui.drawRect(
                    x + 1,
                    scanY,
                    x + width - 1,
                    Math.min(scanY + 2, y + height - 1),
                    scanAlpha << 24 | 0x00FFFFFF);
        }
        if (safeProgress > 0.05F) {
            int barAlpha =
                    (int) (255.0F * safeProgress);
            int barColor = barAlpha << 24
                    | colors.accent & 0x00FFFFFF;
            Gui.drawRect(
                    x,
                    y + 1,
                    x + 2,
                    y + height - 1,
                    barColor);
        }
    }

    private static int darken(int color, float factor) {
        int alpha = color >>> 24;
        int red =
                (int) ((color >>> 16 & 255) * factor);
        int green =
                (int) ((color >>> 8 & 255) * factor);
        int blue = (int) ((color & 255) * factor);
        return alpha << 24 | red << 16 | green << 8 | blue;
    }
}
