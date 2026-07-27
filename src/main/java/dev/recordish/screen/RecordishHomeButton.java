package dev.recordish.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.FloatBuffer;

/**
 * Compact main-menu entry point for Recordish.
 *
 * <p>The clapperboard is drawn from pixels instead of a font glyph, which
 * keeps the button legible on Minecraft 1.8.9 installations regardless of
 * the selected font or operating-system emoji support.</p>
 */
public final class RecordishHomeButton extends GuiButton {
    public static final int SIZE = 20;
    public static final String DEFAULT_TOOLTIP =
            "Video Collection (Recordish)";
    private static final String[] CLAPPER_MASK = {
            ".......###..",
            "....###.....",
            ".###........",
            "#...........",
            "##.#.#.#.#.#",
            "############",
            "############",
            "##..#.#...##",
            "############",
            "##.#...#..##",
            "############",
            "############"
    };
    /*
     * LWJGL 2 validates glGetFloat buffers against the command's maximum
     * return size (16), even though GL_CURRENT_COLOR writes only four values.
     */
    private static final FloatBuffer COLOR_STATE =
            BufferUtils.createFloatBuffer(16);

    private final String tooltipText;

    public RecordishHomeButton(int buttonId, int x, int y) {
        this(buttonId, x, y, DEFAULT_TOOLTIP);
    }

    public RecordishHomeButton(
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

        int light = !enabled
                ? 0xFF777777
                : 0xFFFFFFFF;
        int dark = !enabled
                ? 0xFF1D1D1D
                : 0xFF3F3F3F;

        drawClapperboard(xPosition, yPosition, light, dark);
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
            int light,
            int dark) {
        /*
         * The 26.2 emoji glyph occupies a 12x12 framebuffer-pixel mask at GUI
         * scale 2: a six-logical-pixel footprint plus a one-pixel text shadow.
         * Half-scale drawing preserves that exact silhouette on the legacy
         * renderer instead of turning it into a coarse floppy-disk shape.
         */
        int left = (buttonX + 7) * 2;
        int top = (buttonY + 7) * 2;
        boolean blendEnabled =
                GL11.glIsEnabled(GL11.GL_BLEND);
        boolean textureEnabled =
                GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
        boolean alphaEnabled =
                GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
        COLOR_STATE.clear();
        GL11.glGetFloat(
                GL11.GL_CURRENT_COLOR,
                COLOR_STATE);
        float previousRed = COLOR_STATE.get(0);
        float previousGreen = COLOR_STATE.get(1);
        float previousBlue = COLOR_STATE.get(2);
        float previousAlpha = COLOR_STATE.get(3);
        GlStateManager.pushMatrix();
        try {
            GlStateManager.scale(0.5F, 0.5F, 1.0F);
            drawMask(left + 1, top + 1, dark);
            drawMask(left, top, light);
        } finally {
            GlStateManager.popMatrix();
            restoreCapability(
                    GL11.GL_TEXTURE_2D,
                    textureEnabled);
            restoreCapability(
                    GL11.GL_BLEND,
                    blendEnabled);
            restoreCapability(
                    GL11.GL_ALPHA_TEST,
                    alphaEnabled);
            GlStateManager.color(
                    previousRed,
                    previousGreen,
                    previousBlue,
                    previousAlpha);
        }
    }

    private static void drawMask(
            int left,
            int top,
            int color) {
        for (int row = 0; row < CLAPPER_MASK.length; row++) {
            String pixels = CLAPPER_MASK[row];
            for (int column = 0;
                    column < pixels.length();
                    column++) {
                if (pixels.charAt(column) != '#') {
                    continue;
                }
                Gui.drawRect(
                        left + column,
                        top + row,
                        left + column + 1,
                        top + row + 1,
                        color);
            }
        }
    }

    private static void restoreCapability(
            int capability,
            boolean enabled) {
        if (capability == GL11.GL_TEXTURE_2D) {
            if (enabled) {
                GlStateManager.enableTexture2D();
            } else {
                GlStateManager.disableTexture2D();
            }
        } else if (capability == GL11.GL_BLEND) {
            if (enabled) {
                GlStateManager.enableBlend();
            } else {
                GlStateManager.disableBlend();
            }
        } else if (capability == GL11.GL_ALPHA_TEST) {
            if (enabled) {
                GlStateManager.enableAlpha();
            } else {
                GlStateManager.disableAlpha();
            }
        }
    }
}
