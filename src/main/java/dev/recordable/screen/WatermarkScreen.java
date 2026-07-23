package dev.recordable.screen;

import dev.recordable.RecordableConfig;
import dev.recordable.WatermarkSlot;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import org.lwjgl.input.Keyboard;

import java.io.IOException;
import java.util.List;

/**
 * Compact editor for the four V1-0.08 text/image watermark slots.
 */
public final class WatermarkScreen extends GuiScreen {
    private final GuiScreen parent;
    private GuiTextField nameField;
    private GuiTextField contentField;
    private int selected;

    public WatermarkScreen(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        buttonList.clear();
        ensureSelection();

        buttonList.add(new GuiButton(1, 10, 8, 55, 20, "Done"));
        buttonList.add(new GuiButton(2, 70, 8, 42, 20, "<"));
        buttonList.add(new GuiButton(3, 116, 8, 42, 20, ">"));
        buttonList.add(new GuiButton(4, 163, 8, 50, 20, "Add"));
        buttonList.add(new GuiButton(5, 217, 8, 58, 20, "Remove"));
        buttonList.add(new GuiButton(6, 280, 8, 78, 20, "Master"));

        nameField = new GuiTextField(
                20,
                fontRendererObj,
                90,
                43,
                Math.max(100, width - 105),
                18);
        nameField.setMaxStringLength(48);
        contentField = new GuiTextField(
                21,
                fontRendererObj,
                90,
                68,
                Math.max(100, width - 105),
                18);
        contentField.setMaxStringLength(512);

        int left = 16;
        int right = Math.max(220, width / 2 + 10);
        buttonList.add(new GuiButton(10, left, 100, 120, 20, "Enabled"));
        buttonList.add(new GuiButton(11, left + 125, 100, 120, 20, "Kind"));
        buttonList.add(new GuiButton(12, left, 124, 229, 20, "Position"));
        buttonList.add(new GuiButton(13, left, 148, 229, 20, "Animation"));
        buttonList.add(new GuiButton(14, left, 172, 112, 20, "Opacity -"));
        buttonList.add(new GuiButton(15, left + 117, 172, 112, 20, "Opacity +"));
        buttonList.add(new GuiButton(16, left, 196, 112, 20, "Scale -"));
        buttonList.add(new GuiButton(17, left + 117, 196, 112, 20, "Scale +"));
        buttonList.add(new GuiButton(18, left, 220, 112, 20, "Rotate -"));
        buttonList.add(new GuiButton(19, left + 117, 220, 112, 20, "Rotate +"));
        buttonList.add(new GuiButton(20, left, 244, 112, 20, "Shadow"));
        buttonList.add(new GuiButton(21, left + 117, 244, 112, 20, "Timed"));
        buttonList.add(new GuiButton(22, right, 100, 150, 20, "Start -"));
        buttonList.add(new GuiButton(23, right + 155, 100, 150, 20, "Start +"));
        buttonList.add(new GuiButton(24, right, 124, 150, 20, "End -"));
        buttonList.add(new GuiButton(25, right + 155, 124, 150, 20, "End +"));
        buttonList.add(new GuiButton(26, right, 148, 150, 20, "Padding -"));
        buttonList.add(new GuiButton(27, right + 155, 148, 150, 20, "Padding +"));
        buttonList.add(new GuiButton(28, right, 172, 305, 20, "Live Preview"));

        loadFields();
        updateButtons();
    }

    @Override
    public void onGuiClosed() {
        commitFields();
        RecordableConfig.get().save();
        Keyboard.enableRepeatEvents(false);
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button == null || !button.enabled) return;
        commitFields();
        RecordableConfig config = RecordableConfig.get();
        List<WatermarkSlot> slots = config.watermarkSlots;
        WatermarkSlot slot = selectedSlot();

        if (button.id == 1) {
            mc.displayGuiScreen(parent);
            return;
        } else if (button.id == 2 && !slots.isEmpty()) {
            selected = (selected - 1 + slots.size()) % slots.size();
        } else if (button.id == 3 && !slots.isEmpty()) {
            selected = (selected + 1) % slots.size();
        } else if (button.id == 4
                && slots.size() < RecordableConfig.MAX_WATERMARK_SLOTS) {
            WatermarkSlot added = new WatermarkSlot(
                    "Watermark " + (slots.size() + 1),
                    WatermarkSlot.Kind.TEXT);
            added.enabled = true;
            slots.add(added);
            selected = slots.size() - 1;
        } else if (button.id == 5 && slot != null) {
            slots.remove(selected);
            selected = Math.max(0, Math.min(selected, slots.size() - 1));
        } else if (button.id == 6) {
            config.watermarksEnabled = !config.watermarksEnabled;
        } else if (slot != null) {
            applySlotAction(button.id, slot, config);
        }
        config.save();
        loadFields();
        updateButtons();
    }

    private void applySlotAction(
            int id,
            WatermarkSlot slot,
            RecordableConfig config) {
        if (id == 10) {
            slot.enabled = !slot.enabled;
        } else if (id == 11) {
            slot.kind = slot.kind == WatermarkSlot.Kind.TEXT
                    ? WatermarkSlot.Kind.IMAGE
                    : WatermarkSlot.Kind.TEXT;
        } else if (id == 12) {
            WatermarkSlot.Position[] values =
                    WatermarkSlot.Position.values();
            slot.position = values[
                    (slot.position.ordinal() + 1) % values.length];
        } else if (id == 13) {
            WatermarkSlot.Animation[] values =
                    WatermarkSlot.Animation.values();
            slot.animation = values[
                    (slot.animation.ordinal() + 1) % values.length];
        } else if (id == 14) {
            slot.opacity -= 5;
        } else if (id == 15) {
            slot.opacity += 5;
        } else if (id == 16) {
            slot.scale -= 10;
        } else if (id == 17) {
            slot.scale += 10;
        } else if (id == 18) {
            slot.rotation -= 5;
        } else if (id == 19) {
            slot.rotation += 5;
        } else if (id == 20) {
            slot.textShadow = !slot.textShadow;
        } else if (id == 21) {
            slot.useTimeRange = !slot.useTimeRange;
        } else if (id == 22) {
            slot.startSeconds -= 1;
        } else if (id == 23) {
            slot.startSeconds += 1;
        } else if (id == 24) {
            slot.endSeconds -= 1;
        } else if (id == 25) {
            slot.endSeconds += 1;
        } else if (id == 26) {
            slot.padding -= 1;
        } else if (id == 27) {
            slot.padding += 1;
        } else if (id == 28) {
            config.showWatermarksLive = !config.showWatermarksLive;
        }
        slot.sanitize();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        Gui.drawRect(0, 0, width, height, 0xB0101010);
        drawCenteredString(
                fontRendererObj,
                "Watermark Editor",
                width / 2,
                14,
                0xFFFFFF);
        fontRendererObj.drawString("Name:", 45, 48, 0xCCCCCC);
        WatermarkSlot slot = selectedSlot();
        fontRendererObj.drawString(
                slot != null && slot.kind == WatermarkSlot.Kind.IMAGE
                        ? "Image path:"
                        : "Text:",
                20,
                73,
                0xCCCCCC);
        nameField.drawTextBox();
        contentField.drawTextBox();
        if (slot == null) {
            drawCenteredString(
                    fontRendererObj,
                    "Add a watermark slot to begin.",
                    width / 2,
                    115,
                    0xAAAAAA);
        } else {
            drawCenteredString(
                    fontRendererObj,
                    "Slot " + (selected + 1) + "/"
                            + RecordableConfig.get().watermarkSlots.size()
                            + "  |  Opacity " + slot.opacity + "%"
                            + "  |  Scale " + slot.scale + "%"
                            + "  |  Rotation " + slot.rotation + " deg",
                    width / 2,
                    height - 36,
                    0xDDDDDD);
            drawCenteredString(
                    fontRendererObj,
                    "Use {username}, {date}, and {time} in text watermarks.",
                    width / 2,
                    height - 22,
                    0x999999);
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton)
            throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        nameField.mouseClicked(mouseX, mouseY, mouseButton);
        contentField.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode)
            throws IOException {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            commitFields();
            mc.displayGuiScreen(parent);
            return;
        }
        if (nameField.textboxKeyTyped(typedChar, keyCode)
                || contentField.textboxKeyTyped(typedChar, keyCode)) {
            if (keyCode == Keyboard.KEY_RETURN) {
                commitFields();
            }
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    private void loadFields() {
        WatermarkSlot slot = selectedSlot();
        if (nameField == null || contentField == null) return;
        nameField.setText(slot == null || slot.name == null
                ? ""
                : slot.name);
        contentField.setText(slot == null
                ? ""
                : (slot.kind == WatermarkSlot.Kind.IMAGE
                    ? slot.imagePath
                    : slot.text));
    }

    private void commitFields() {
        WatermarkSlot slot = selectedSlot();
        if (slot == null || nameField == null || contentField == null) return;
        String name = nameField.getText().trim();
        slot.name = name.isEmpty() ? "Watermark" : name;
        if (slot.kind == WatermarkSlot.Kind.IMAGE) {
            slot.imagePath = contentField.getText().trim();
        } else {
            slot.text = contentField.getText();
        }
        slot.sanitize();
    }

    private void updateButtons() {
        RecordableConfig config = RecordableConfig.get();
        WatermarkSlot slot = selectedSlot();
        boolean has = slot != null;
        for (GuiButton button : buttonList) {
            if (button.id == 2 || button.id == 3 || button.id == 5
                    || button.id >= 10) {
                button.enabled = has;
            }
            if (button.id == 4) {
                button.enabled = config.watermarkSlots.size()
                        < RecordableConfig.MAX_WATERMARK_SLOTS;
            }
        }
        button(6).displayString = config.watermarksEnabled
                ? "Master: ON"
                : "Master: OFF";
        if (!has) return;
        button(10).displayString = slot.enabled
                ? "Enabled"
                : "Disabled";
        button(11).displayString = "Kind: " + slot.kind.name();
        button(12).displayString = "Position: "
                + slot.position.name().replace('_', ' ');
        button(13).displayString = "Animation: " + slot.animation.name();
        button(20).displayString = slot.textShadow
                ? "Shadow: ON"
                : "Shadow: OFF";
        button(21).displayString = slot.useTimeRange
                ? "Timed: ON"
                : "Timed: OFF";
        button(22).displayString = "Start - (" + slot.startSeconds + "s)";
        button(23).displayString = "Start + (" + slot.startSeconds + "s)";
        button(24).displayString = "End - (" + slot.endSeconds + "s)";
        button(25).displayString = "End + (" + slot.endSeconds + "s)";
        button(26).displayString = "Padding - (" + slot.padding + ")";
        button(27).displayString = "Padding + (" + slot.padding + ")";
        button(28).displayString = config.showWatermarksLive
                ? "Live Preview: ON"
                : "Live Preview: OFF";
    }

    private GuiButton button(int id) {
        for (GuiButton button : buttonList) {
            if (button.id == id) return button;
        }
        return new GuiButton(-1, 0, 0, "");
    }

    private void ensureSelection() {
        List<WatermarkSlot> slots =
                RecordableConfig.get().watermarkSlots;
        if (selected >= slots.size()) selected = Math.max(0, slots.size() - 1);
    }

    private WatermarkSlot selectedSlot() {
        List<WatermarkSlot> slots =
                RecordableConfig.get().watermarkSlots;
        return selected >= 0 && selected < slots.size()
                ? slots.get(selected)
                : null;
    }
}
