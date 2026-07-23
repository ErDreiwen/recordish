package dev.recordable.screen;

import dev.recordable.CensorRegion;
import dev.recordable.RecordableConfig;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import org.lwjgl.input.Keyboard;

import java.io.IOException;
import java.util.List;

/**
 * Full-screen normalized censor-region editor. Regions can be selected, moved,
 * stretched, labelled, styled, enabled, and removed over the live game view.
 */
public final class CensorOverlayEditorScreen extends GuiScreen {
    private final GuiScreen parent;
    private GuiTextField labelField;
    private int selected = -1;
    private DragMode dragMode = DragMode.NONE;
    private int dragOffsetX;
    private int dragOffsetY;

    public CensorOverlayEditorScreen(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        buttonList.clear();
        int x = 5;
        buttonList.add(new GuiButton(1, x, 5, 55, 20, "Done"));
        x += 58;
        buttonList.add(new GuiButton(2, x, 5, 48, 20, "Add"));
        x += 51;
        buttonList.add(new GuiButton(3, x, 5, 55, 20, "Delete"));
        x += 58;
        buttonList.add(new GuiButton(4, x, 5, 65, 20, "Style"));
        x += 68;
        buttonList.add(new GuiButton(5, x, 5, 68, 20, "Gradient"));
        x += 71;
        buttonList.add(new GuiButton(6, x, 5, 55, 20, "Label"));
        x += 58;
        buttonList.add(new GuiButton(7, x, 5, 60, 20, "Enabled"));

        labelField = new GuiTextField(
                30,
                fontRendererObj,
                85,
                31,
                Math.max(80, Math.min(250, width - 90)),
                18);
        labelField.setMaxStringLength(64);
        select(selected);
        updateButtons();
    }

    @Override
    public void onGuiClosed() {
        commitLabel();
        RecordableConfig.get().save();
        Keyboard.enableRepeatEvents(false);
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button == null || !button.enabled) return;
        List<CensorRegion> regions = regions();
        CensorRegion region = selectedRegion();
        if (button.id == 1) {
            mc.displayGuiScreen(parent);
        } else if (button.id == 2) {
            commitLabel();
            CensorRegion added = new CensorRegion(
                    0.35,
                    0.35,
                    0.30,
                    0.12,
                    defaultStyle(),
                    "Censor " + (regions.size() + 1));
            added.showLabel = true;
            regions.add(added);
            select(regions.size() - 1);
            save();
        } else if (button.id == 3 && region != null) {
            regions.remove(selected);
            select(Math.min(selected, regions.size() - 1));
            save();
        } else if (button.id == 4 && region != null) {
            region.style = region.style == CensorRegion.Style.SOLID
                    ? CensorRegion.Style.GRADIENT
                    : CensorRegion.Style.SOLID;
            save();
        } else if (button.id == 5 && region != null) {
            CensorRegion.GradientDirection[] values =
                    CensorRegion.GradientDirection.values();
            region.gradientDirection = values[
                    (region.gradientDirection.ordinal() + 1)
                            % values.length];
            save();
        } else if (button.id == 6 && region != null) {
            region.showLabel = !region.showLabel;
            save();
        } else if (button.id == 7 && region != null) {
            region.enabled = !region.enabled;
            save();
        }
        updateButtons();
    }

    private CensorRegion.Style defaultStyle() {
        return "GRADIENT".equals(
                RecordableConfig.get().streamerDefaultCensorStyle)
                ? CensorRegion.Style.GRADIENT
                : CensorRegion.Style.SOLID;
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        Gui.drawRect(0, 0, width, 55, 0xD0101010);
        drawCenteredString(
                fontRendererObj,
                "Censor Overlay Editor",
                width / 2,
                31,
                0xFFFFFF);
        fontRendererObj.drawString(
                "Label:",
                48,
                36,
                0xBBBBBB);
        labelField.drawTextBox();

        List<CensorRegion> regions = regions();
        for (int index = 0; index < regions.size(); index++) {
            CensorRegion region = regions.get(index);
            if (region == null) continue;
            int left = toX(region.x);
            int top = toY(region.y);
            int right = toX(region.x + region.width);
            int bottom = toY(region.y + region.height);
            int alpha = region.enabled ? 0xC0000000 : 0x60000000;
            drawRegion(region, left, top, right, bottom, alpha);
            int border = index == selected ? 0xFFFFFF00 : 0xFFEEEEEE;
            drawBorder(left, top, right, bottom, border);
            String text = region.showLabel
                    ? region.label
                    : "Region " + (index + 1);
            fontRendererObj.drawStringWithShadow(
                    fontRendererObj.trimStringToWidth(
                            text == null ? "Censor" : text,
                            Math.max(10, right - left - 6)),
                    left + 3,
                    top + 3,
                    0xFFFFFF);
            if (index == selected) {
                Gui.drawRect(
                        right - 8,
                        bottom - 8,
                        right,
                        bottom,
                        0xFFFFFF00);
            }
        }

        fontRendererObj.drawStringWithShadow(
                "Click a region to select; drag to move; drag yellow corner to resize.",
                8,
                height - 20,
                0xFFFFFF);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void drawRegion(
            CensorRegion region,
            int left,
            int top,
            int right,
            int bottom,
            int alpha) {
        int first = alpha | (region.color & 0xFFFFFF);
        int second = alpha | (region.colorEnd & 0xFFFFFF);
        if (region.style == CensorRegion.Style.GRADIENT) {
            if (region.gradientDirection
                    == CensorRegion.GradientDirection.VERTICAL) {
                drawGradientRect(left, top, right, bottom, first, second);
            } else {
                int strips = Math.max(1, Math.min(64, right - left));
                for (int strip = 0; strip < strips; strip++) {
                    float fraction = strips <= 1
                            ? 0.0F
                            : strip / (float) (strips - 1);
                    int color = blend(first, second, fraction);
                    int x0 = left + (right - left) * strip / strips;
                    int x1 = left + (right - left) * (strip + 1) / strips;
                    Gui.drawRect(x0, top, x1, bottom, color);
                }
            }
        } else {
            Gui.drawRect(left, top, right, bottom, first);
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton)
            throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        labelField.mouseClicked(mouseX, mouseY, mouseButton);
        if (mouseButton != 0 || mouseY < 55) return;

        commitLabel();
        List<CensorRegion> regions = regions();
        for (int index = regions.size() - 1; index >= 0; index--) {
            CensorRegion region = regions.get(index);
            int left = toX(region.x);
            int top = toY(region.y);
            int right = toX(region.x + region.width);
            int bottom = toY(region.y + region.height);
            if (mouseX < left || mouseX > right
                    || mouseY < top || mouseY > bottom) {
                continue;
            }
            select(index);
            if (mouseX >= right - 10 && mouseY >= bottom - 10) {
                dragMode = DragMode.RESIZE;
            } else {
                dragMode = DragMode.MOVE;
                dragOffsetX = mouseX - left;
                dragOffsetY = mouseY - top;
            }
            return;
        }
        select(-1);
    }

    @Override
    protected void mouseClickMove(
            int mouseX,
            int mouseY,
            int clickedMouseButton,
            long timeSinceLastClick) {
        CensorRegion region = selectedRegion();
        if (region == null || clickedMouseButton != 0) return;
        if (dragMode == DragMode.MOVE) {
            double newX = (mouseX - dragOffsetX) / (double) width;
            double newY = (mouseY - dragOffsetY) / (double) height;
            region.x = clamp(newX, 0.0D, 1.0D - region.width);
            region.y = clamp(newY, 0.0D, 1.0D - region.height);
        } else if (dragMode == DragMode.RESIZE) {
            region.width = clamp(
                    mouseX / (double) width - region.x,
                    0.02D,
                    1.0D - region.x);
            region.height = clamp(
                    mouseY / (double) height - region.y,
                    0.02D,
                    1.0D - region.y);
        }
    }

    @Override
    protected void mouseReleased(
            int mouseX,
            int mouseY,
            int state) {
        super.mouseReleased(mouseX, mouseY, state);
        if (dragMode != DragMode.NONE) save();
        dragMode = DragMode.NONE;
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode)
            throws IOException {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            commitLabel();
            mc.displayGuiScreen(parent);
            return;
        }
        if (labelField.textboxKeyTyped(typedChar, keyCode)) {
            if (keyCode == Keyboard.KEY_RETURN) {
                commitLabel();
                labelField.setFocused(false);
            }
            return;
        }
        if (keyCode == Keyboard.KEY_DELETE && selectedRegion() != null) {
            regions().remove(selected);
            select(Math.min(selected, regions().size() - 1));
            save();
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    private void select(int index) {
        commitLabel();
        selected = index >= 0 && index < regions().size() ? index : -1;
        CensorRegion region = selectedRegion();
        if (labelField != null) {
            labelField.setText(
                    region == null || region.label == null
                            ? ""
                            : region.label);
        }
        updateButtons();
    }

    private void commitLabel() {
        CensorRegion region = selectedRegion();
        if (region == null || labelField == null) return;
        String value = labelField.getText().trim();
        region.label = value.isEmpty() ? "Censor" : value;
        region.sanitize();
    }

    private void updateButtons() {
        boolean hasSelection = selectedRegion() != null;
        for (GuiButton button : buttonList) {
            if (button.id >= 3 && button.id <= 7) {
                button.enabled = hasSelection;
            }
        }
        CensorRegion region = selectedRegion();
        if (region == null) return;
        button(4).displayString = region.style == CensorRegion.Style.GRADIENT
                ? "Gradient"
                : "Solid";
        button(5).displayString = shortDirection(region.gradientDirection);
        button(6).displayString = region.showLabel
                ? "Label: ON"
                : "Label: OFF";
        button(7).displayString = region.enabled
                ? "Enabled"
                : "Disabled";
    }

    private GuiButton button(int id) {
        for (GuiButton button : buttonList) {
            if (button.id == id) return button;
        }
        return new GuiButton(-1, 0, 0, "");
    }

    private static String shortDirection(
            CensorRegion.GradientDirection direction) {
        if (direction == CensorRegion.GradientDirection.VERTICAL) {
            return "Vertical";
        }
        if (direction == CensorRegion.GradientDirection.DIAGONAL) {
            return "Diagonal";
        }
        return "Horizontal";
    }

    private List<CensorRegion> regions() {
        return RecordableConfig.get().censorRegions;
    }

    private CensorRegion selectedRegion() {
        List<CensorRegion> list = regions();
        return selected >= 0 && selected < list.size()
                ? list.get(selected)
                : null;
    }

    private void save() {
        CensorRegion region = selectedRegion();
        if (region != null) region.sanitize();
        RecordableConfig.get().save();
        updateButtons();
    }

    private int toX(double normalized) {
        return (int) Math.round(clamp(normalized, 0.0D, 1.0D) * width);
    }

    private int toY(double normalized) {
        return (int) Math.round(clamp(normalized, 0.0D, 1.0D) * height);
    }

    private static void drawBorder(
            int left,
            int top,
            int right,
            int bottom,
            int color) {
        Gui.drawRect(left, top, right, top + 1, color);
        Gui.drawRect(left, bottom - 1, right, bottom, color);
        Gui.drawRect(left, top, left + 1, bottom, color);
        Gui.drawRect(right - 1, top, right, bottom, color);
    }

    private static int blend(int first, int second, float amount) {
        float value = Math.max(0.0F, Math.min(1.0F, amount));
        int a = (int) (((first >>> 24) & 255) * (1.0F - value)
                + ((second >>> 24) & 255) * value);
        int r = (int) (((first >>> 16) & 255) * (1.0F - value)
                + ((second >>> 16) & 255) * value);
        int g = (int) (((first >>> 8) & 255) * (1.0F - value)
                + ((second >>> 8) & 255) * value);
        int b = (int) ((first & 255) * (1.0F - value)
                + (second & 255) * value);
        return a << 24 | r << 16 | g << 8 | b;
    }

    private static double clamp(
            double value,
            double minimum,
            double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private enum DragMode {
        NONE,
        MOVE,
        RESIZE
    }
}
