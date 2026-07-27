package dev.recordish.screen;

import dev.recordish.RecordishConfig;
import dev.recordish.RecordishMod;
import dev.recordish.WatermarkImageStore;
import dev.recordish.WatermarkSlot;
import dev.recordish.theme.CycleButton;
import dev.recordish.theme.ThemeEngine;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import org.lwjgl.input.Keyboard;

import java.awt.FileDialog;
import java.awt.Frame;
import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;

/**
 * V1-0.09 Watermark / Branding editor, adapted to Forge 1.8.9.
 */
public final class WatermarkScreen extends GuiScreen {
    private static final int PANEL_COLOR = 0xD0101010;
    private static final int PANEL_BORDER_COLOR = 0xFF424242;
    private static final int TEXT_COLOR = 0xFFD0D0D0;
    private static final int HEADER_COLOR = 0xFFFFFFFF;
    private static final int HIGHLIGHT_COLOR = 0xFF88CC88;
    private static final int WARNING_COLOR = 0xFFFFCC44;
    private static final int ROW_HEIGHT = 22;

    private static final int MASTER_ID = 1;
    private static final int PREVIEW_ID = 2;
    private static final int ADD_TEXT_ID = 3;
    private static final int ADD_IMAGE_ID = 4;
    private static final int PRESET_ID = 5;
    private static final int BACK_ID = 6;
    private static final int SLOT_ID_BASE = 100;
    private static final int TYPE_ID = 200;
    private static final int POSITION_ID = 201;
    private static final int ANIMATION_ID = 202;
    private static final int STOP_ID = 210;
    private static final int ADD_COLOR_ID = 211;
    private static final int REMOVE_COLOR_ID = 212;
    private static final int BROWSE_ID = 220;

    private final GuiScreen parent;

    private int panelLeft;
    private int panelTop;
    private int panelWidth;
    private int panelBottom;
    private int listTop;
    private int listLeft;
    private int listWidth;
    private int editLeft;
    private int editWidth;

    private int selectedIndex = -1;
    private int gradientStopIndex;
    private GuiTextField textField;
    private GuiTextField hexField;
    private String statusMessage = "";
    private boolean rebuildPending;

    public WatermarkScreen(GuiScreen parent) {
        this.parent = parent;
    }

    private List<WatermarkSlot> slots() {
        return RecordishConfig.get().watermarkSlots;
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        ThemeEngine.get().applyPreset(RecordishConfig.get().uiTheme);

        panelWidth = Math.max(
                420,
                Math.min((int) (width * 0.90D), 720));
        panelLeft = (width - panelWidth) / 2;
        panelTop = Math.max(8, (int) (height * 0.05D));
        panelBottom = Math.min(
                height - 8,
                panelTop + Math.max(340, (int) (height * 0.88D)));
        listLeft = panelLeft + 12;
        listWidth = (panelWidth - 36) / 2;
        editLeft = listLeft + listWidth + 12;
        editWidth = panelWidth - 24 - listWidth - 12;
        listTop = panelTop + 78;

        if (selectedIndex >= slots().size()) {
            selectedIndex = slots().size() - 1;
        }
        rebuildPanelWidgets();
    }

    private void rebuildPanelWidgets() {
        buttonList.clear();
        textField = null;
        hexField = null;
        rebuildPending = false;

        RecordishConfig config = RecordishConfig.get();
        int topY = panelTop + 32;

        buttonList.add(new GuiButton(
                MASTER_ID,
                listLeft,
                topY,
                120,
                18,
                "Watermarks: "
                        + (config.watermarksEnabled ? "ON" : "OFF")));
        buttonList.add(new GuiButton(
                PREVIEW_ID,
                listLeft + 128,
                topY,
                130,
                18,
                "Live Preview: "
                        + (config.showWatermarksLive ? "ON" : "OFF")));

        int third = (editWidth - 16) / 3;
        GuiButton addText = new GuiButton(
                ADD_TEXT_ID,
                editLeft,
                topY,
                third,
                18,
                "+ Text");
        addText.enabled =
                slots().size() < RecordishConfig.MAX_WATERMARK_SLOTS;
        buttonList.add(addText);

        GuiButton addImage = new GuiButton(
                ADD_IMAGE_ID,
                editLeft + third + 8,
                topY,
                third,
                18,
                "+ Image");
        addImage.enabled =
                slots().size() < RecordishConfig.MAX_WATERMARK_SLOTS;
        buttonList.add(addImage);

        buttonList.add(new GuiButton(
                PRESET_ID,
                editLeft + 2 * third + 16,
                topY,
                third,
                18,
                "Preset: User"));

        List<WatermarkSlot> list = slots();
        for (int index = 0; index < list.size(); index++) {
            WatermarkSlot slot = list.get(index);
            if (slot == null) {
                continue;
            }
            int rowY = listTop + index * ROW_HEIGHT;
            int id = SLOT_ID_BASE + index * 3;
            buttonList.add(new GuiButton(
                    id,
                    listLeft,
                    rowY,
                    listWidth - 88,
                    18,
                    (slot.enabled ? "[x] " : "[ ] ")
                            + truncate(slot.name, 14)));
            buttonList.add(new GuiButton(
                    id + 1,
                    listLeft + listWidth - 84,
                    rowY,
                    38,
                    18,
                    slot.enabled ? "On" : "Off"));
            buttonList.add(new GuiButton(
                    id + 2,
                    listLeft + listWidth - 42,
                    rowY,
                    38,
                    18,
                    "Del"));
        }

        WatermarkSlot selected = selectedSlot();
        if (selected != null) {
            selected.sanitize();
            buildEditor(selected);
        }

        buttonList.add(new GuiButton(
                BACK_ID,
                (width - 120) / 2,
                panelBottom - 28,
                120,
                20,
                "Back"));
    }

    private void buildEditor(final WatermarkSlot slot) {
        final RecordishConfig config = RecordishConfig.get();
        int y = listTop;
        int editorWidth = editWidth;

        buttonList.add(CycleButton.create(
                TYPE_ID,
                editLeft,
                y,
                editorWidth,
                18,
                "Type: " + slot.kind,
                button -> {
                    toggleKind(slot);
                    config.save();
                    rebuildPending = true;
                },
                button -> {
                    toggleKind(slot);
                    config.save();
                    rebuildPending = true;
                }));
        y += ROW_HEIGHT;

        if (slot.kind == WatermarkSlot.Kind.TEXT) {
            textField = new GuiTextField(
                    300,
                    fontRendererObj,
                    editLeft,
                    y,
                    editorWidth,
                    18);
            textField.setMaxStringLength(256);
            textField.setText(slot.text == null ? "" : slot.text);
        } else {
            String fileLabel = isBlank(slot.imagePath)
                    ? "Browse\u2026 (no image)"
                    : "Image: " + truncate(slot.imagePath, 22);
            buttonList.add(new GuiButton(
                    BROWSE_ID,
                    editLeft,
                    y,
                    editorWidth,
                    18,
                    fileLabel));
        }
        y += ROW_HEIGHT;

        if (slot.kind == WatermarkSlot.Kind.TEXT) {
            List<String> colors = slot.textColors;
            if (colors == null || colors.isEmpty()) {
                colors = new ArrayList<String>();
                colors.add(isBlank(slot.textColor)
                        ? "#FFFFFFFF"
                        : slot.textColor);
                slot.textColors = colors;
            }
            if (gradientStopIndex >= colors.size()) {
                gradientStopIndex = colors.size() - 1;
            }
            if (gradientStopIndex < 0) {
                gradientStopIndex = 0;
            }

            hexField = new GuiTextField(
                    301,
                    fontRendererObj,
                    editLeft,
                    y,
                    editorWidth,
                    18);
            hexField.setMaxStringLength(9);
            hexField.setText(colors.get(gradientStopIndex));
            y += ROW_HEIGHT;

            int third = (editorWidth - 8) / 3;
            buttonList.add(new GuiButton(
                    STOP_ID,
                    editLeft,
                    y,
                    third,
                    18,
                    "Stop " + (gradientStopIndex + 1)
                            + "/" + colors.size()));
            GuiButton addColor = new GuiButton(
                    ADD_COLOR_ID,
                    editLeft + third + 4,
                    y,
                    third,
                    18,
                    "+ Color");
            addColor.enabled = colors.size() < 10;
            buttonList.add(addColor);
            GuiButton removeColor = new GuiButton(
                    REMOVE_COLOR_ID,
                    editLeft + 2 * (third + 4),
                    y,
                    third,
                    18,
                    "- Color");
            removeColor.enabled = colors.size() > 1;
            buttonList.add(removeColor);
            y += ROW_HEIGHT;
        }

        buttonList.add(CycleButton.create(
                POSITION_ID,
                editLeft,
                y,
                editorWidth,
                18,
                "Pos: " + slot.position,
                button -> {
                    WatermarkSlot.Position[] values =
                            WatermarkSlot.Position.values();
                    slot.position = values[
                            (slot.position.ordinal() + 1)
                                    % values.length];
                    config.save();
                    rebuildPending = true;
                },
                button -> {
                    WatermarkSlot.Position[] values =
                            WatermarkSlot.Position.values();
                    slot.position = values[
                            (slot.position.ordinal() - 1 + values.length)
                                    % values.length];
                    config.save();
                    rebuildPending = true;
                }));
        y += ROW_HEIGHT;

        buttonList.add(CycleButton.create(
                ANIMATION_ID,
                editLeft,
                y,
                editorWidth,
                18,
                "Anim: " + slot.animation,
                button -> {
                    WatermarkSlot.Animation[] values =
                            WatermarkSlot.Animation.values();
                    slot.animation = values[
                            (slot.animation.ordinal() + 1)
                                    % values.length];
                    config.save();
                    rebuildPending = true;
                },
                button -> {
                    WatermarkSlot.Animation[] values =
                            WatermarkSlot.Animation.values();
                    slot.animation = values[
                            (slot.animation.ordinal() - 1 + values.length)
                                    % values.length];
                    config.save();
                    rebuildPending = true;
                }));
        y += ROW_HEIGHT;

        buttonList.add(new IntSlider(
                230,
                editLeft,
                y,
                editorWidth,
                18,
                "Opacity",
                slot.opacity,
                0,
                100,
                value -> {
                    slot.opacity = value;
                    config.save();
                }));
        y += ROW_HEIGHT;
        buttonList.add(new IntSlider(
                231,
                editLeft,
                y,
                editorWidth,
                18,
                "Scale",
                slot.scale,
                10,
                400,
                value -> {
                    slot.scale = value;
                    config.save();
                }));
        y += ROW_HEIGHT;
        buttonList.add(new IntSlider(
                232,
                editLeft,
                y,
                editorWidth,
                18,
                "Rotation",
                slot.rotation,
                -180,
                180,
                value -> {
                    slot.rotation = value;
                    config.save();
                }));
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button == null || !button.enabled) {
            return;
        }
        RecordishConfig config = RecordishConfig.get();
        if (button.id == MASTER_ID) {
            config.watermarksEnabled = !config.watermarksEnabled;
            config.save();
            rebuildPending = true;
        } else if (button.id == PREVIEW_ID) {
            config.showWatermarksLive = !config.showWatermarksLive;
            config.save();
            rebuildPending = true;
        } else if (button.id == ADD_TEXT_ID) {
            addSlot(WatermarkSlot.Kind.TEXT);
        } else if (button.id == ADD_IMAGE_ID) {
            addSlot(WatermarkSlot.Kind.IMAGE);
        } else if (button.id == PRESET_ID) {
            applyUsernamePreset();
        } else if (button.id == BACK_ID) {
            closeToParent();
        } else if (button.id >= SLOT_ID_BASE
                && button.id < TYPE_ID) {
            int relative = button.id - SLOT_ID_BASE;
            int slotIndex = relative / 3;
            int action = relative % 3;
            if (slotIndex >= 0 && slotIndex < slots().size()) {
                if (action == 0) {
                    selectedIndex = slotIndex;
                    rebuildPending = true;
                } else if (action == 1) {
                    WatermarkSlot slot = slots().get(slotIndex);
                    if (slot != null) {
                        slot.enabled = !slot.enabled;
                        config.save();
                        rebuildPending = true;
                    }
                } else {
                    slots().remove(slotIndex);
                    if (selectedIndex >= slots().size()) {
                        selectedIndex = slots().size() - 1;
                    }
                    config.save();
                    rebuildPending = true;
                }
            }
        } else if (button.id == STOP_ID) {
            WatermarkSlot slot = selectedSlot();
            if (slot != null && slot.textColors != null
                    && !slot.textColors.isEmpty()) {
                gradientStopIndex =
                        (gradientStopIndex + 1) % slot.textColors.size();
                rebuildPending = true;
            }
        } else if (button.id == ADD_COLOR_ID) {
            addGradientStop();
        } else if (button.id == REMOVE_COLOR_ID) {
            removeGradientStop();
        } else if (button.id == BROWSE_ID) {
            WatermarkSlot slot = selectedSlot();
            if (slot != null) {
                openImagePicker(slot);
            }
        }
    }

    private void addSlot(WatermarkSlot.Kind kind) {
        if (slots().size() >= RecordishConfig.MAX_WATERMARK_SLOTS) {
            return;
        }
        String prefix = kind == WatermarkSlot.Kind.TEXT
                ? "Text "
                : "Image ";
        WatermarkSlot slot = new WatermarkSlot(
                prefix + (slots().size() + 1),
                kind);
        slot.enabled = true;
        slots().add(slot);
        selectedIndex = slots().size() - 1;
        RecordishConfig.get().save();
        rebuildPending = true;
    }

    private void applyUsernamePreset() {
        if (slots().size() >= RecordishConfig.MAX_WATERMARK_SLOTS) {
            return;
        }
        WatermarkSlot slot = new WatermarkSlot(
                "Username Stamp",
                WatermarkSlot.Kind.TEXT);
        slot.enabled = true;
        slot.text = "{username} \u2022 {date}";
        slot.position = WatermarkSlot.Position.BOTTOM_RIGHT;
        slot.opacity = 70;
        slots().add(slot);
        while (slots().size() > RecordishConfig.MAX_WATERMARK_SLOTS) {
            slots().remove(slots().size() - 1);
        }
        RecordishConfig config = RecordishConfig.get();
        config.watermarksEnabled = true;
        config.save();
        statusMessage = "Applied username preset.";
        rebuildPending = true;
    }

    private void addGradientStop() {
        WatermarkSlot slot = selectedSlot();
        if (slot == null || slot.textColors == null
                || slot.textColors.isEmpty()
                || slot.textColors.size() >= 10) {
            return;
        }
        slot.textColors.add(
                slot.textColors.get(slot.textColors.size() - 1));
        gradientStopIndex = slot.textColors.size() - 1;
        slot.textColor = slot.textColors.get(0);
        RecordishConfig.get().save();
        rebuildPending = true;
    }

    private void removeGradientStop() {
        WatermarkSlot slot = selectedSlot();
        if (slot == null || slot.textColors == null
                || slot.textColors.size() <= 1) {
            return;
        }
        int index = Math.max(
                0,
                Math.min(gradientStopIndex, slot.textColors.size() - 1));
        slot.textColors.remove(index);
        if (gradientStopIndex >= slot.textColors.size()) {
            gradientStopIndex = slot.textColors.size() - 1;
        }
        slot.textColor = slot.textColors.get(0);
        RecordishConfig.get().save();
        rebuildPending = true;
    }

    private static void toggleKind(WatermarkSlot slot) {
        slot.kind = slot.kind == WatermarkSlot.Kind.TEXT
                ? WatermarkSlot.Kind.IMAGE
                : WatermarkSlot.Kind.TEXT;
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton)
            throws IOException {
        if (mouseButton == 1) {
            for (GuiButton button : buttonList) {
                if (button instanceof CycleButton
                        && ((CycleButton) button).mousePressedSecondary(
                                mc,
                                mouseX,
                                mouseY)) {
                    applyPendingRebuild();
                    return;
                }
            }
        }

        if (textField != null) {
            textField.mouseClicked(mouseX, mouseY, mouseButton);
        }
        if (hexField != null) {
            hexField.mouseClicked(mouseX, mouseY, mouseButton);
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
        applyPendingRebuild();
    }

    private void applyPendingRebuild() {
        if (rebuildPending && mc.currentScreen == this) {
            rebuildPanelWidgets();
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode)
            throws IOException {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            closeToParent();
            return;
        }

        WatermarkSlot slot = selectedSlot();
        if (textField != null
                && textField.isFocused()
                && textField.textboxKeyTyped(typedChar, keyCode)) {
            if (slot != null) {
                slot.text = textField.getText();
                RecordishConfig.get().save();
            }
            return;
        }
        if (hexField != null
                && hexField.isFocused()
                && hexField.textboxKeyTyped(typedChar, keyCode)) {
            if (slot != null
                    && slot.textColors != null
                    && !slot.textColors.isEmpty()) {
                String normalized = normalizeHex(hexField.getText());
                if (normalized != null) {
                    int index = Math.max(
                            0,
                            Math.min(
                                    gradientStopIndex,
                                    slot.textColors.size() - 1));
                    slot.textColors.set(index, normalized);
                    slot.textColor = slot.textColors.get(0);
                    RecordishConfig.get().save();
                }
            }
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        if (textField != null) {
            textField.updateCursorCounter();
        }
        if (hexField != null) {
            hexField.updateCursorCounter();
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        dev.recordish.theme.ThemedPanel.drawMenuBackdrop(width, height);

        int accent = 0xFF000000
                | RecordishConfig.get().getMenuAccentColorRgb();
        int left = panelLeft - 6;
        int right = panelLeft + panelWidth + 6;
        Gui.drawRect(left, panelTop - 6, right, panelBottom, PANEL_COLOR);
        Gui.drawRect(left, panelTop - 6, right, panelTop - 5, accent);
        Gui.drawRect(
                left,
                panelBottom - 1,
                right,
                panelBottom,
                PANEL_BORDER_COLOR);
        Gui.drawRect(
                left,
                panelTop - 6,
                left + 1,
                panelBottom,
                PANEL_BORDER_COLOR);
        Gui.drawRect(
                right - 1,
                panelTop - 6,
                right,
                panelBottom,
                PANEL_BORDER_COLOR);

        drawCenteredString(
                fontRendererObj,
                "Watermark / Branding",
                width / 2,
                panelTop,
                0xFFFFFFFF);
        fontRendererObj.drawStringWithShadow(
                "\u00A7lSlots (" + slots().size() + "/"
                        + RecordishConfig.MAX_WATERMARK_SLOTS + ")",
                listLeft,
                listTop - 12,
                HEADER_COLOR);

        WatermarkSlot selected = selectedSlot();
        if (selected != null) {
            fontRendererObj.drawStringWithShadow(
                    "\u00A7lEdit: " + truncate(selected.name, 18),
                    editLeft,
                    listTop - 12,
                    HEADER_COLOR);
        } else {
            fontRendererObj.drawStringWithShadow(
                    "Select a slot to edit",
                    editLeft,
                    listTop - 12,
                    TEXT_COLOR);
        }

        if (slots().isEmpty()) {
            fontRendererObj.drawStringWithShadow(
                    "No watermarks. Use + Text / + Image.",
                    listLeft,
                    listTop + 4,
                    TEXT_COLOR);
        }

        if (!RecordishConfig.get().watermarksEnabled) {
            drawCenteredString(
                    fontRendererObj,
                    "Watermarks are OFF - enable to render on recordings.",
                    width / 2,
                    panelBottom - 44,
                    WARNING_COLOR);
        } else if (!statusMessage.isEmpty()) {
            drawCenteredString(
                    fontRendererObj,
                    statusMessage,
                    width / 2,
                    panelBottom - 44,
                    HIGHLIGHT_COLOR);
        }

        super.drawScreen(mouseX, mouseY, partialTicks);
        if (textField != null) {
            textField.drawTextBox();
        }
        if (hexField != null) {
            hexField.drawTextBox();
        }
    }

    @Override
    public void onGuiClosed() {
        RecordishConfig.get().save();
        Keyboard.enableRepeatEvents(false);
        super.onGuiClosed();
    }

    private void closeToParent() {
        mc.displayGuiScreen(parent);
    }

    private void openImagePicker(final WatermarkSlot slot) {
        Thread picker = new Thread(new Runnable() {
            @Override
            public void run() {
                String selectedPath = null;
                FileDialog dialog = null;
                try {
                    dialog = new FileDialog(
                            (Frame) null,
                            "Select watermark image",
                            FileDialog.LOAD);
                    dialog.setFilenameFilter(new FilenameFilter() {
                        @Override
                        public boolean accept(File directory, String name) {
                            String lower = name == null
                                    ? ""
                                    : name.toLowerCase(Locale.ROOT);
                            return lower.endsWith(".png")
                                    || lower.endsWith(".jpg")
                                    || lower.endsWith(".jpeg");
                        }
                    });
                    dialog.setFile("*.png;*.jpg;*.jpeg");
                    dialog.setVisible(true);
                    if (dialog.getFile() != null) {
                        selectedPath = new File(
                                dialog.getDirectory(),
                                dialog.getFile()).getAbsolutePath();
                    }
                } catch (Throwable throwable) {
                    RecordishMod.LOGGER.warn(
                            "Watermark file picker failed", throwable);
                } finally {
                    if (dialog != null) {
                        dialog.dispose();
                    }
                }

                final String result = selectedPath;
                Minecraft.getMinecraft().addScheduledTask(new Runnable() {
                    @Override
                    public void run() {
                        if (!isBlank(result)) {
                            String stored =
                                    WatermarkImageStore.importImage(result);
                            if (stored != null) {
                                slot.imagePath = stored;
                                RecordishConfig.get().save();
                                statusMessage =
                                        "Imported image: " + stored;
                            } else {
                                statusMessage =
                                        "Could not import that image.";
                            }
                        }
                        if (mc.currentScreen == WatermarkScreen.this) {
                            rebuildPanelWidgets();
                        }
                    }
                });
            }
        }, "recordish-watermark-picker");
        picker.setDaemon(true);
        picker.start();
    }

    private WatermarkSlot selectedSlot() {
        return selectedIndex >= 0 && selectedIndex < slots().size()
                ? slots().get(selectedIndex)
                : null;
    }

    private static String normalizeHex(String input) {
        if (input == null) {
            return null;
        }
        String value = input.trim();
        if (value.startsWith("#")) {
            value = value.substring(1);
        }
        if (value.length() != 6 && value.length() != 8) {
            return null;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            boolean hex = character >= '0' && character <= '9'
                    || character >= 'a' && character <= 'f'
                    || character >= 'A' && character <= 'F';
            if (!hex) {
                return null;
            }
        }
        return "#" + value.toUpperCase(Locale.ROOT);
    }

    private static String truncate(String value, int maximum) {
        if (value == null) {
            return "";
        }
        return value.length() > maximum
                ? value.substring(0, maximum - 1) + "\u2026"
                : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /**
     * Legacy equivalent of AbstractSliderButton with continuous drag updates.
     */
    private static final class IntSlider extends GuiButton {
        private final String label;
        private final int minimum;
        private final int maximum;
        private final IntConsumer setter;
        private double progress;
        private boolean dragging;

        private IntSlider(
                int id,
                int x,
                int y,
                int width,
                int height,
                String label,
                int current,
                int minimum,
                int maximum,
                IntConsumer setter) {
            super(id, x, y, width, height, "");
            this.label = label;
            this.minimum = minimum;
            this.maximum = maximum;
            this.setter = setter;
            progress = (current - minimum)
                    / (double) Math.max(1, maximum - minimum);
            progress = Math.max(0.0D, Math.min(1.0D, progress));
            updateMessage();
        }

        @Override
        public boolean mousePressed(
                Minecraft minecraft,
                int mouseX,
                int mouseY) {
            boolean pressed = super.mousePressed(
                    minecraft,
                    mouseX,
                    mouseY);
            if (pressed) {
                dragging = true;
                updateFromMouse(mouseX);
            }
            return pressed;
        }

        @Override
        protected void mouseDragged(
                Minecraft minecraft,
                int mouseX,
                int mouseY) {
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
                Minecraft minecraft,
                int mouseX,
                int mouseY) {
            super.drawButton(minecraft, mouseX, mouseY);
            if (!visible) {
                return;
            }
            int trackLeft = xPosition + 4;
            int trackRight = xPosition + width - 4;
            int trackY = yPosition + height - 4;
            Gui.drawRect(
                    trackLeft,
                    trackY,
                    trackRight,
                    trackY + 1,
                    0xFF555555);
            int knob = trackLeft + (int) Math.round(
                    progress * (trackRight - trackLeft));
            Gui.drawRect(
                    knob - 1,
                    trackY - 2,
                    knob + 2,
                    trackY + 3,
                    0xFFFFFFFF);
        }

        private void updateFromMouse(int mouseX) {
            progress = (mouseX - (xPosition + 4))
                    / (double) Math.max(1, width - 8);
            progress = Math.max(0.0D, Math.min(1.0D, progress));
            int current = current();
            setter.accept(current);
            updateMessage();
        }

        private int current() {
            return (int) Math.round(
                    progress * (maximum - minimum)) + minimum;
        }

        private void updateMessage() {
            displayString = label + ": " + current();
        }
    }
}
