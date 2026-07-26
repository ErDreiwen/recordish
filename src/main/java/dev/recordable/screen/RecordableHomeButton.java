package dev.recordable.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.ScaledResolution;

/**
 * Compact main-menu entry point for Record-able.
 *
 * <p>The clapperboard is drawn from pixels instead of a font glyph, which
 * keeps the button legible on Minecraft 1.8.9 installations regardless of
 * the selected font or operating-system emoji support.</p>
 */
public final class RecordableHomeButton extends GuiButton {
    public static final int SIZE = 20;
    public static final String DEFAULT_TOOLTIP =
            "Video Collection (Record-able)";

    private final String tooltipText;

    public RecordableHomeButton(int buttonId, int x, int y) {
        this(buttonId, x, y, DEFAULT_TOOLTIP);
    }

    public RecordableHomeButton(
            int buttonId,
            int x,
            int y,
            String tooltipText) {
        super(buttonId, x, y, SIZE, SIZE, "");
        this.tooltipText = tooltipText == null ? "" : tooltipText;
    }

    @Override
    public void drawButton(
            Minecraft minecraft,
            int mouseX,
            int mouseY) {
        super.drawButton(minecraft, mouseX, mouseY);
        if (!visible) {
            return;
        }

        boolean hoveredNow = isHoveredAt(mouseX, mouseY);
        int outline = enabled ? 0xFF181818 : 0xFF444444;
        int light = !enabled
                ? 0xFF777777
                : (hoveredNow ? 0xFFFFFFFF : 0xFFE2E2E2);
        int face = !enabled
                ? 0xFF666666
                : (hoveredNow ? 0xFFE8F3FF : 0xFFBFC9D2);
        int stripe = enabled ? 0xFF30343A : 0xFF505050;

        drawClapperboard(xPosition, yPosition, outline, light, face, stripe);
    }

    /**
     * Returns whether the supplied scaled GUI coordinates are over the
     * button. Event handlers can use this without depending on GuiButton's
     * internal hover field.
     */
    public boolean isHoveredAt(int mouseX, int mouseY) {
        return visible
                && mouseX >= xPosition
                && mouseY >= yPosition
                && mouseX < xPosition + width
                && mouseY < yPosition + height;
    }

    public String getTooltipText() {
        return tooltipText;
    }

    /**
     * Draws the hover label after the owning screen has rendered its buttons.
     *
     * <p>Call this from a screen's final draw pass (or Forge's
     * {@code DrawScreenEvent.Post}) so the tooltip appears above the rest of
     * the menu.</p>
     */
    public void drawTooltip(
            Minecraft minecraft,
            int mouseX,
            int mouseY) {
        if (minecraft == null
                || tooltipText.isEmpty()
                || !isHoveredAt(mouseX, mouseY)) {
            return;
        }

        FontRenderer font = minecraft.fontRendererObj;
        ScaledResolution resolution = new ScaledResolution(minecraft);
        int padding = 4;
        int tooltipWidth = font.getStringWidth(tooltipText);
        int tooltipHeight = font.FONT_HEIGHT;
        int left = mouseX + 12;
        int top = mouseY - tooltipHeight - padding;

        if (left + tooltipWidth + padding * 2
                > resolution.getScaledWidth()) {
            left = mouseX - tooltipWidth - padding * 2 - 4;
        }
        left = Math.max(2, left);
        top = Math.max(
                2,
                Math.min(
                        top,
                        resolution.getScaledHeight()
                                - tooltipHeight
                                - padding * 2
                                - 2));

        int right = left + tooltipWidth + padding * 2;
        int bottom = top + tooltipHeight + padding * 2;
        Gui.drawRect(left - 1, top - 1, right + 1, bottom + 1,
                0xF0100010);
        Gui.drawRect(left, top, right, bottom, 0xF0100010);
        Gui.drawRect(left - 1, top - 1, right, top, 0xFF505000);
        Gui.drawRect(left - 1, bottom, right, bottom + 1, 0xFF280028);
        Gui.drawRect(left - 1, top, left, bottom, 0xFF505000);
        Gui.drawRect(right, top, right + 1, bottom + 1, 0xFF280028);
        font.drawStringWithShadow(
                tooltipText,
                left + padding,
                top + padding,
                0xFFFFFFFF);
    }

    private static void drawClapperboard(
            int buttonX,
            int buttonY,
            int outline,
            int light,
            int face,
            int stripe) {
        int left = buttonX + 4;
        int right = buttonX + 16;

        // Board body.
        Gui.drawRect(left, buttonY + 9, right, buttonY + 16, outline);
        Gui.drawRect(
                left + 1,
                buttonY + 10,
                right - 1,
                buttonY + 15,
                face);
        Gui.drawRect(
                left + 1,
                buttonY + 10,
                right - 1,
                buttonY + 11,
                stripe);

        // Hinged top slate.
        Gui.drawRect(
                left - 1,
                buttonY + 4,
                right + 1,
                buttonY + 9,
                outline);
        Gui.drawRect(
                left,
                buttonY + 5,
                right,
                buttonY + 8,
                light);
        Gui.drawRect(
                left + 2,
                buttonY + 5,
                left + 4,
                buttonY + 8,
                stripe);
        Gui.drawRect(
                left + 7,
                buttonY + 5,
                left + 9,
                buttonY + 8,
                stripe);
        Gui.drawRect(
                left + 11,
                buttonY + 5,
                right,
                buttonY + 8,
                stripe);

        // Hinge pin and a small recording mark.
        Gui.drawRect(left, buttonY + 7, left + 2, buttonY + 10, outline);
        Gui.drawRect(
                right - 4,
                buttonY + 12,
                right - 2,
                buttonY + 14,
                0xFFE05252);
    }
}
