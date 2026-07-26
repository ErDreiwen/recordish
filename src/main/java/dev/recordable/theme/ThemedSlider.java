package dev.recordable.theme;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;

import java.util.Locale;

/**
 * Java 8 themed numeric slider with the modern retro track marks.
 */
public class ThemedSlider extends ThemedButton {
    public interface SliderAction {
        void onChange(double value);
    }

    private final String labelFormat;
    private final double minimum;
    private final double maximum;
    private final SliderAction onChange;
    private double progress;
    private boolean dragging;

    public ThemedSlider(
            int id,
            int x,
            int y,
            int width,
            int height,
            String labelFormat,
            double minimum,
            double maximum,
            double currentValue,
            SliderAction onChange) {
        super(id, x, y, width, height, "");
        this.labelFormat = labelFormat == null
                ? "%d"
                : labelFormat;
        this.minimum = minimum;
        this.maximum = maximum <= minimum
                ? minimum + 1.0D
                : maximum;
        this.onChange = onChange;
        setActualValue(currentValue, false);
    }

    public static ThemedSlider create(
            int id,
            int x,
            int y,
            int width,
            int height,
            String labelFormat,
            double minimum,
            double maximum,
            double currentValue,
            SliderAction onChange) {
        return new ThemedSlider(
                id,
                x,
                y,
                width,
                height,
                labelFormat,
                minimum,
                maximum,
                currentValue,
                onChange);
    }

    @Override
    public boolean mousePressed(
            Minecraft minecraft, int mouseX, int mouseY) {
        boolean pressed =
                super.mousePressed(minecraft, mouseX, mouseY);
        if (pressed) {
            dragging = true;
            updateFromMouse(mouseX);
        }
        return pressed;
    }

    @Override
    protected void mouseDragged(
            Minecraft minecraft, int mouseX, int mouseY) {
        if (dragging) {
            updateFromMouse(mouseX);
        }
    }

    @Override
    public void mouseReleased(int mouseX, int mouseY) {
        dragging = false;
        super.mouseReleased(mouseX, mouseY);
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
        if (dragging) {
            updateFromMouse(mouseX);
        }
        drawThemedBar(
                xPosition,
                yPosition,
                width,
                height,
                progress,
                hovered && enabled);
        ThemeColors colors = ThemeEngine.get().colors();
        drawCenteredString(
                minecraft.fontRendererObj,
                displayString,
                xPosition + width / 2,
                yPosition + (height - 8) / 2,
                enabled ? colors.textPrimary : colors.textMuted);
    }

    public double getActualValue() {
        return minimum + progress * (maximum - minimum);
    }

    public void setActualValue(double value) {
        setActualValue(value, false);
    }

    private void setActualValue(
            double value, boolean notify) {
        double safe = Math.max(
                minimum, Math.min(maximum, value));
        double old = getActualValue();
        progress =
                (safe - minimum) / (maximum - minimum);
        updateMessage();
        if (notify
                && onChange != null
                && Math.abs(old - safe) > 0.000001D) {
            onChange.onChange(safe);
        }
    }

    private void updateFromMouse(int mouseX) {
        int trackWidth = Math.max(1, width - 8);
        double newProgress =
                (mouseX - (xPosition + 4)) / (double) trackWidth;
        newProgress = Math.max(
                0.0D, Math.min(1.0D, newProgress));
        setActualValue(
                minimum + newProgress * (maximum - minimum),
                true);
    }

    private void updateMessage() {
        displayString = String.format(
                Locale.ROOT,
                labelFormat,
                (int) Math.round(getActualValue()));
    }

    public static void drawThemedBar(
            int x,
            int y,
            int width,
            int height,
            double progress,
            boolean hovered) {
        ThemeColors colors = ThemeEngine.get().colors();
        double safeProgress = Math.max(
                0.0D, Math.min(1.0D, progress));
        Gui.drawRect(
                x,
                y,
                x + width,
                y + height,
                colors.buttonBackground);
        Gui.drawRect(
                x, y, x + width, y + 1, colors.buttonBorder);
        Gui.drawRect(
                x,
                y + height - 1,
                x + width,
                y + height,
                colors.buttonBorder);
        Gui.drawRect(
                x, y, x + 1, y + height, colors.buttonBorder);
        Gui.drawRect(
                x + width - 1,
                y,
                x + width,
                y + height,
                colors.buttonBorder);

        int filledWidth =
                (int) (safeProgress * (width - 4));
        Gui.drawRect(
                x + 2,
                y + 2,
                x + 2 + filledWidth,
                y + height - 2,
                hovered ? colors.accentHover : colors.accent);

        ThemePreset preset = ThemeEngine.get().preset();
        if (preset == ThemePreset.VHS
                || preset == ThemePreset.CINEMA) {
            for (int index = 0; index <= 10; index++) {
                int tickX = x + 2
                        + (int) ((width - 4)
                                * (index / 10.0F));
                Gui.drawRect(
                        tickX,
                        y + height - 4,
                        tickX + 1,
                        y + height - 1,
                        colors.textMuted & 0x60FFFFFF);
            }
        }

        int thumbX = x + 2 + filledWidth - 2;
        Gui.drawRect(
                thumbX,
                y + 1,
                thumbX + 4,
                y + height - 1,
                colors.textPrimary);
        if (hovered) {
            Gui.drawRect(
                    thumbX - 1,
                    y,
                    thumbX + 5,
                    y + height,
                    colors.accentHover & 0x40FFFFFF);
        }
    }
}
