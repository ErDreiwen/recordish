package dev.recordish.theme;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;

/**
 * Themed ON/OFF button with the modern Recordish label convention.
 */
public final class ThemedToggle extends ThemedButton {
    public interface ToggleAction {
        void onChange(boolean value);
    }

    private final String label;
    private final ToggleAction onChange;
    private boolean value;

    public ThemedToggle(
            int id,
            int x,
            int y,
            int width,
            int height,
            String label,
            boolean initialValue,
            ToggleAction onChange) {
        super(
                id,
                x,
                y,
                width,
                height,
                format(label, initialValue));
        this.label = label == null ? "" : label;
        value = initialValue;
        this.onChange = onChange;
    }

    public static ThemedToggle create(
            int id,
            int x,
            int y,
            int width,
            int height,
            String label,
            boolean initialValue,
            ToggleAction onChange) {
        return new ThemedToggle(
                id,
                x,
                y,
                width,
                height,
                label,
                initialValue,
                onChange);
    }

    @Override
    public boolean mousePressed(
            Minecraft minecraft, int mouseX, int mouseY) {
        boolean pressed =
                super.mousePressed(minecraft, mouseX, mouseY);
        if (pressed) {
            setValue(!value, true);
        }
        return pressed;
    }

    public boolean getValue() {
        return value;
    }

    public void setValue(boolean value) {
        setValue(value, false);
    }

    private void setValue(
            boolean value, boolean notify) {
        this.value = value;
        displayString = format(label, value);
        if (notify && onChange != null) {
            onChange.onChange(value);
        }
    }

    public static void drawToggleTrack(
            int x,
            int y,
            int trackWidth,
            int trackHeight,
            float animationProgress) {
        ThemeColors colors = ThemeEngine.get().colors();
        float progress = Math.max(
                0.0F, Math.min(1.0F, animationProgress));
        int trackColor = ThemeEngine.lerpColor(
                colors.textMuted & 0x40FFFFFF,
                colors.accent & 0x80FFFFFF,
                progress);
        Gui.drawRect(
                x,
                y,
                x + trackWidth,
                y + trackHeight,
                trackColor);
        int thumbWidth = 8;
        int thumbX = x + 2
                + (int) ((trackWidth - thumbWidth - 4)
                        * progress);
        int thumbColor = ThemeEngine.lerpColor(
                colors.textMuted, colors.accent, progress);
        Gui.drawRect(
                thumbX,
                y + 1,
                thumbX + thumbWidth,
                y + trackHeight - 1,
                thumbColor);
    }

    private static String format(
            String label, boolean value) {
        return (label == null ? "" : label)
                + ": "
                + (value ? "ON" : "OFF");
    }
}
