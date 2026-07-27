package dev.recordish.theme;

import net.minecraft.client.Minecraft;

/**
 * Multi-value button with left-click/next and right-click/previous actions.
 *
 * <p>Minecraft 1.8.9 only dispatches left clicks to GuiButton, so screens call
 * {@link #mousePressedSecondary(Minecraft, int, int)} from their right-click
 * handler. This preserves the V1-0.09 interaction without invasive mixins.</p>
 */
public final class CycleButton extends ThemedButton {
    public interface CycleAction {
        void onPress(CycleButton button);
    }

    private final CycleAction onPrimary;
    private final CycleAction onSecondary;

    public CycleButton(
            int id,
            int x,
            int y,
            int width,
            int height,
            String message,
            CycleAction onPrimary,
            CycleAction onSecondary) {
        super(id, x, y, width, height, message);
        this.onPrimary = onPrimary;
        this.onSecondary = onSecondary;
    }

    public static CycleButton create(
            int id,
            int x,
            int y,
            int width,
            int height,
            String message,
            CycleAction onPrimary,
            CycleAction onSecondary) {
        return new CycleButton(
                id,
                x,
                y,
                width,
                height,
                message,
                onPrimary,
                onSecondary);
    }

    @Override
    public boolean mousePressed(
            Minecraft minecraft, int mouseX, int mouseY) {
        boolean pressed =
                super.mousePressed(minecraft, mouseX, mouseY);
        if (pressed && onPrimary != null) {
            onPrimary.onPress(this);
        }
        return pressed;
    }

    public boolean mousePressedSecondary(
            Minecraft minecraft, int mouseX, int mouseY) {
        if (!enabled
                || !visible
                || onSecondary == null
                || !contains(mouseX, mouseY)) {
            return false;
        }
        onSecondary.onPress(this);
        playPressSound(minecraft.getSoundHandler());
        return true;
    }

    public boolean contains(int mouseX, int mouseY) {
        return mouseX >= xPosition
                && mouseY >= yPosition
                && mouseX < xPosition + width
                && mouseY < yPosition + height;
    }
}
