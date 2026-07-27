package dev.recordish.screen;

import dev.recordish.CensorRegion;
import dev.recordish.RecordishConfig;
import dev.recordish.theme.CycleButton;
import dev.recordish.theme.ThemeEngine;
import dev.recordish.theme.ThemedButton;
import dev.recordish.theme.ThemedPanel;
import dev.recordish.theme.ThemedToggle;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import org.lwjgl.input.Keyboard;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * V1-0.09 Streamer Mode editor, adapted to Forge 1.8.9.
 */
public final class StreamerModeScreen extends GuiScreen {
    private static final int WIDGET_HEIGHT = 20;
    private static final int ROW_SPACING = 22;
    private static final int PANEL_W = 200;
    private static final double MIN_SIZE = 0.03D;

    private final GuiScreen parent;
    private final List<ColorPickerWidget> colorPickers =
            new ArrayList<ColorPickerWidget>();

    private GuiTextField labelField;
    private GuiButton bakeInOverlayToggle;
    private int canvasX;
    private int canvasY;
    private int canvasWidth;
    private int canvasHeight;
    private int selected = -1;
    private int dragMode;
    private double pressFx;
    private double pressFy;
    private double originalX;
    private double originalY;
    private double originalWidth;
    private double originalHeight;
    private boolean rebuildPending;

    public StreamerModeScreen(GuiScreen parent) {
        this.parent = parent;
    }

    private List<CensorRegion> regions() {
        return RecordishConfig.get().censorRegions;
    }

    private CensorRegion selectedRegion() {
        List<CensorRegion> list = regions();
        return selected >= 0 && selected < list.size()
                ? list.get(selected)
                : null;
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        ThemeEngine.get().applyPreset(RecordishConfig.get().uiTheme);
        computeCanvas();
        rebuildControls();
    }

    private void computeCanvas() {
        int areaX = PANEL_W + 12;
        int areaWidth = width - areaX - 16;
        int areaY = 40;
        int areaHeight = height - areaY - 16;
        int fittedWidth = areaWidth;
        int fittedHeight = fittedWidth * 9 / 16;
        if (fittedHeight > areaHeight) {
            fittedHeight = areaHeight;
            fittedWidth = fittedHeight * 16 / 9;
        }
        canvasWidth = Math.max(80, fittedWidth);
        canvasHeight = Math.max(45, fittedHeight);
        canvasX = areaX + (areaWidth - canvasWidth) / 2;
        canvasY = areaY + (areaHeight - canvasHeight) / 2;
    }

    private void rebuildControls() {
        buttonList.clear();
        colorPickers.clear();
        labelField = null;
        bakeInOverlayToggle = null;
        rebuildPending = false;

        final RecordishConfig config = RecordishConfig.get();
        if (config == null) {
            closeToParent();
            return;
        }

        int widgetX = 14;
        int widgetWidth = PANEL_W - 24;
        int y = 32;

        buttonList.add(ThemedToggle.create(
                1,
                widgetX,
                y,
                widgetWidth,
                WIDGET_HEIGHT,
                "Streamer Mode",
                config.streamerModeEnabled,
                value -> {
                    config.streamerModeEnabled = value;
                    config.save();
                }));
        y += ROW_SPACING;

        buttonList.add(ThemedToggle.create(
                2,
                widgetX,
                y,
                widgetWidth,
                WIDGET_HEIGHT,
                "Show Preview",
                config.streamerShowCensorPreview,
                value -> {
                    config.streamerShowCensorPreview = value;
                    config.save();
                }));
        y += ROW_SPACING;

        bakeInOverlayToggle = ThemedToggle.create(
                3,
                widgetX,
                y,
                widgetWidth,
                WIDGET_HEIGHT,
                "Bake in Overlay",
                config.bakeInOverlay,
                value -> {
                    config.bakeInOverlay = value;
                    config.save();
                });
        buttonList.add(bakeInOverlayToggle);
        y += ROW_SPACING;

        buttonList.add(ThemedButton.create(
                4,
                widgetX,
                y,
                widgetWidth,
                WIDGET_HEIGHT,
                "Add Region",
                button -> {
                    addRegion();
                    rebuildPending = true;
                }));
        y += ROW_SPACING;

        buttonList.add(ThemedButton.create(
                5,
                widgetX,
                y,
                widgetWidth,
                WIDGET_HEIGHT,
                "Edit On Screen (Live)",
                button -> mc.displayGuiScreen(
                        new CensorOverlayEditorScreen(this))));
        y += ROW_SPACING;

        final CensorRegion selectedRegion = selectedRegion();
        if (selectedRegion == null) {
            buttonList.add(ThemedButton.create(
                    6,
                    widgetX,
                    y,
                    widgetWidth,
                    WIDGET_HEIGHT,
                    "Clear All",
                    button -> {
                        regions().clear();
                        selected = -1;
                        config.save();
                        rebuildPending = true;
                    }));
            y += ROW_SPACING + 4;
        } else {
            if (selectedRegion.style == null) {
                selectedRegion.style = CensorRegion.Style.SOLID;
            }
            if (selectedRegion.gradientDirection == null) {
                selectedRegion.gradientDirection =
                        CensorRegion.GradientDirection.HORIZONTAL;
            }

            buttonList.add(CycleButton.create(
                    10,
                    widgetX,
                    y,
                    widgetWidth,
                    WIDGET_HEIGHT,
                    "Style: " + selectedRegion.style.name(),
                    button -> {
                        selectedRegion.style =
                                nextStyle(selectedRegion.style);
                        config.save();
                        rebuildPending = true;
                    },
                    button -> {
                        selectedRegion.style =
                                previousStyle(selectedRegion.style);
                        config.save();
                        rebuildPending = true;
                    }));
            y += ROW_SPACING;

            colorPickers.add(new ColorPickerWidget(
                    fontRendererObj,
                    200,
                    widgetX,
                    y,
                    widgetWidth,
                    WIDGET_HEIGHT,
                    "Color",
                    toHex(selectedRegion.color),
                    hex -> {
                        selectedRegion.color = fromHex(hex);
                        config.save();
                    }));
            y += ROW_SPACING;

            if (selectedRegion.style == CensorRegion.Style.GRADIENT) {
                colorPickers.add(new ColorPickerWidget(
                        fontRendererObj,
                        201,
                        widgetX,
                        y,
                        widgetWidth,
                        WIDGET_HEIGHT,
                        "Color 2",
                        toHex(selectedRegion.colorEnd),
                        hex -> {
                            selectedRegion.colorEnd = fromHex(hex);
                            config.save();
                        }));
                y += ROW_SPACING;

                buttonList.add(CycleButton.create(
                        11,
                        widgetX,
                        y,
                        widgetWidth,
                        WIDGET_HEIGHT,
                        "Gradient: "
                                + selectedRegion.gradientDirection.name(),
                        button -> {
                            selectedRegion.gradientDirection =
                                    nextDirection(
                                            selectedRegion.gradientDirection);
                            config.save();
                            button.displayString = "Gradient: "
                                    + selectedRegion.gradientDirection.name();
                        },
                        button -> {
                            selectedRegion.gradientDirection =
                                    previousDirection(
                                            selectedRegion.gradientDirection);
                            config.save();
                            button.displayString = "Gradient: "
                                    + selectedRegion.gradientDirection.name();
                        }));
                y += ROW_SPACING;
            }

            buttonList.add(ThemedToggle.create(
                    12,
                    widgetX,
                    y,
                    widgetWidth,
                    WIDGET_HEIGHT,
                    "Show Text",
                    selectedRegion.showLabel,
                    value -> {
                        selectedRegion.showLabel = value;
                        config.save();
                        rebuildPending = true;
                    }));
            y += ROW_SPACING;

            labelField = new GuiTextField(
                    300,
                    fontRendererObj,
                    widgetX,
                    y,
                    widgetWidth,
                    18);
            labelField.setMaxStringLength(40);
            labelField.setText(selectedRegion.label == null
                    ? ""
                    : selectedRegion.label);
            y += ROW_SPACING;

            if (selectedRegion.showLabel) {
                colorPickers.add(new ColorPickerWidget(
                        fontRendererObj,
                        202,
                        widgetX,
                        y,
                        widgetWidth,
                        WIDGET_HEIGHT,
                        "Text Color",
                        toHex(selectedRegion.textColor),
                        hex -> {
                            selectedRegion.textColor = fromHex(hex);
                            config.save();
                        }));
                y += ROW_SPACING;
            }

            buttonList.add(ThemedButton.create(
                    13,
                    widgetX,
                    y,
                    widgetWidth,
                    WIDGET_HEIGHT,
                    "Remove Selected",
                    button -> {
                        removeSelected();
                        rebuildPending = true;
                    }));
            y += ROW_SPACING + 4;
        }

        buttonList.add(ThemedButton.create(
                20,
                widgetX,
                height - 28,
                widgetWidth,
                WIDGET_HEIGHT,
                "Done",
                button -> closeToParent()));
    }

    private void addRegion() {
        RecordishConfig config = RecordishConfig.get();
        CensorRegion region = new CensorRegion(
                0.375D,
                0.45D,
                0.25D,
                0.10D,
                parseStyle(config.streamerDefaultCensorStyle),
                "Censor");
        regions().add(region);
        selected = regions().size() - 1;
        config.save();
    }

    private void removeSelected() {
        if (selected >= 0 && selected < regions().size()) {
            regions().remove(selected);
            selected = -1;
            RecordishConfig.get().save();
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        // The themed controls dispatch through their own callbacks.
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

        boolean pickerHit = false;
        for (ColorPickerWidget picker : colorPickers) {
            if (picker.contains(mouseX, mouseY)) {
                pickerHit = true;
            }
            picker.mouseClicked(mouseX, mouseY, mouseButton);
        }

        boolean labelHit = labelField != null
                && mouseX >= labelField.xPosition
                && mouseX < labelField.xPosition + labelField.getWidth()
                && mouseY >= labelField.yPosition
                && mouseY < labelField.yPosition + 18;
        if (labelField != null) {
            labelField.mouseClicked(mouseX, mouseY, mouseButton);
        }

        boolean controlHit = mouseButton == 0
                && isControlAt(mouseX, mouseY);
        super.mouseClicked(mouseX, mouseY, mouseButton);
        applyPendingRebuild();
        if (pickerHit || labelHit || controlHit) {
            return;
        }
        canvasPress(mouseX, mouseY, mouseButton);
    }

    private void applyPendingRebuild() {
        if (rebuildPending && mc.currentScreen == this) {
            rebuildControls();
        }
    }

    private boolean isControlAt(int mouseX, int mouseY) {
        for (GuiButton button : buttonList) {
            if (button.visible
                    && button.enabled
                    && mouseX >= button.xPosition
                    && mouseX < button.xPosition + button.width
                    && mouseY >= button.yPosition
                    && mouseY < button.yPosition + button.height) {
                return true;
            }
        }
        return false;
    }

    private void canvasPress(int mouseX, int mouseY, int mouseButton) {
        if (mouseButton != 0
                || mouseX < canvasX
                || mouseX > canvasX + canvasWidth
                || mouseY < canvasY
                || mouseY > canvasY + canvasHeight) {
            return;
        }
        double fx = (mouseX - canvasX) / (double) canvasWidth;
        double fy = (mouseY - canvasY) / (double) canvasHeight;
        List<CensorRegion> list = regions();
        int previousSelected = selected;

        if (selected >= 0 && selected < list.size()) {
            CensorRegion region = list.get(selected);
            int handleX = canvasX
                    + (int) ((region.x + region.width) * canvasWidth);
            int handleY = canvasY
                    + (int) ((region.y + region.height) * canvasHeight);
            if (Math.abs(mouseX - handleX) <= 6
                    && Math.abs(mouseY - handleY) <= 6) {
                dragMode = 2;
                pressFx = fx;
                pressFy = fy;
                originalX = region.x;
                originalY = region.y;
                originalWidth = region.width;
                originalHeight = region.height;
                return;
            }
        }

        for (int index = list.size() - 1; index >= 0; index--) {
            CensorRegion region = list.get(index);
            if (fx >= region.x
                    && fx <= region.x + region.width
                    && fy >= region.y
                    && fy <= region.y + region.height) {
                selected = index;
                dragMode = 1;
                pressFx = fx;
                pressFy = fy;
                originalX = region.x;
                originalY = region.y;
                originalWidth = region.width;
                originalHeight = region.height;
                if (selected != previousSelected) {
                    rebuildControls();
                }
                return;
            }
        }

        RecordishConfig config = RecordishConfig.get();
        CensorRegion created = new CensorRegion(
                clamp(fx, 0.0D, 1.0D - MIN_SIZE),
                clamp(fy, 0.0D, 1.0D - MIN_SIZE),
                MIN_SIZE,
                MIN_SIZE,
                parseStyle(config.streamerDefaultCensorStyle),
                "Censor");
        list.add(created);
        selected = list.size() - 1;
        dragMode = 3;
        pressFx = created.x;
        pressFy = created.y;
        originalX = created.x;
        originalY = created.y;
        originalWidth = MIN_SIZE;
        originalHeight = MIN_SIZE;
        rebuildControls();
    }

    @Override
    protected void mouseClickMove(
            int mouseX,
            int mouseY,
            int clickedMouseButton,
            long timeSinceLastClick) {
        if (dragMode != 0 && clickedMouseButton == 0) {
            canvasDrag(mouseX, mouseY);
        }
    }

    private void canvasDrag(int mouseX, int mouseY) {
        if (selected < 0 || selected >= regions().size()) {
            return;
        }
        CensorRegion region = regions().get(selected);
        double fx = clamp(
                (mouseX - canvasX) / (double) canvasWidth,
                0.0D,
                1.0D);
        double fy = clamp(
                (mouseY - canvasY) / (double) canvasHeight,
                0.0D,
                1.0D);
        if (dragMode == 1) {
            double deltaX = fx - pressFx;
            double deltaY = fy - pressFy;
            region.x = clamp(
                    originalX + deltaX,
                    0.0D,
                    1.0D - region.width);
            region.y = clamp(
                    originalY + deltaY,
                    0.0D,
                    1.0D - region.height);
        } else if (dragMode == 2) {
            region.width = clamp(
                    fx - region.x,
                    MIN_SIZE,
                    1.0D - region.x);
            region.height = clamp(
                    fy - region.y,
                    MIN_SIZE,
                    1.0D - region.y);
        } else if (dragMode == 3) {
            double x0 = Math.min(pressFx, fx);
            double y0 = Math.min(pressFy, fy);
            double x1 = Math.max(pressFx, fx);
            double y1 = Math.max(pressFy, fy);
            region.x = x0;
            region.y = y0;
            region.width = Math.max(MIN_SIZE, x1 - x0);
            region.height = Math.max(MIN_SIZE, y1 - y0);
        }
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        super.mouseReleased(mouseX, mouseY, state);
        if (dragMode != 0) {
            canvasRelease();
        }
    }

    private void canvasRelease() {
        if (selected >= 0 && selected < regions().size()) {
            regions().get(selected).sanitize();
        }
        dragMode = 0;
        RecordishConfig.get().save();
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode)
            throws IOException {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            closeToParent();
            return;
        }
        for (ColorPickerWidget picker : colorPickers) {
            if (picker.keyTyped(typedChar, keyCode)) {
                return;
            }
        }
        if (labelField != null && labelField.isFocused()) {
            if (keyCode == Keyboard.KEY_RETURN
                    || keyCode == Keyboard.KEY_NUMPADENTER) {
                labelField.setFocused(false);
                return;
            }
            if (labelField.textboxKeyTyped(typedChar, keyCode)) {
                CensorRegion region = selectedRegion();
                if (region != null) {
                    String value = labelField.getText();
                    region.label = value == null
                            || value.trim().isEmpty()
                            ? "Censor"
                            : value;
                    RecordishConfig.get().save();
                }
                return;
            }
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        if (labelField != null) {
            labelField.updateCursorCounter();
        }
        for (ColorPickerWidget picker : colorPickers) {
            picker.updateCursorCounter();
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        ThemedPanel.drawMenuBackdrop(width, height);
        RecordishConfig config = RecordishConfig.get();
        if (config == null) {
            super.drawScreen(mouseX, mouseY, partialTicks);
            return;
        }

        ThemedPanel.drawPanel(6, 6, PANEL_W, height - 6);
        drawCenteredString(
                fontRendererObj,
                "Streamer Mode",
                PANEL_W / 2,
                14,
                0xFFFFFFFF);

        Gui.drawRect(
                canvasX - 2,
                canvasY - 2,
                canvasX + canvasWidth + 2,
                canvasY + canvasHeight + 2,
                0xFF2A2A2A);
        Gui.drawRect(
                canvasX,
                canvasY,
                canvasX + canvasWidth,
                canvasY + canvasHeight,
                0xFF101014);
        Gui.drawRect(
                canvasX + canvasWidth / 3,
                canvasY,
                canvasX + canvasWidth / 3 + 1,
                canvasY + canvasHeight,
                0x22FFFFFF);
        Gui.drawRect(
                canvasX + canvasWidth * 2 / 3,
                canvasY,
                canvasX + canvasWidth * 2 / 3 + 1,
                canvasY + canvasHeight,
                0x22FFFFFF);
        Gui.drawRect(
                canvasX,
                canvasY + canvasHeight / 3,
                canvasX + canvasWidth,
                canvasY + canvasHeight / 3 + 1,
                0x22FFFFFF);
        Gui.drawRect(
                canvasX,
                canvasY + canvasHeight * 2 / 3,
                canvasX + canvasWidth,
                canvasY + canvasHeight * 2 / 3 + 1,
                0x22FFFFFF);

        List<CensorRegion> list = regions();
        for (int index = 0; index < list.size(); index++) {
            CensorRegion region = list.get(index);
            CensorRegion.Style style = region.style == null
                    ? CensorRegion.Style.SOLID
                    : region.style;
            CensorRegion.GradientDirection direction =
                    region.gradientDirection == null
                            ? CensorRegion.GradientDirection.HORIZONTAL
                            : region.gradientDirection;
            int x0 = canvasX + (int) (region.x * canvasWidth);
            int y0 = canvasY + (int) (region.y * canvasHeight);
            int x1 = canvasX
                    + (int) ((region.x + region.width) * canvasWidth);
            int y1 = canvasY
                    + (int) ((region.y + region.height) * canvasHeight);

            if (style == CensorRegion.Style.GRADIENT) {
                drawRegionGradient(
                        x0,
                        y0,
                        x1,
                        y1,
                        region.color & 0xFFFFFF,
                        region.colorEnd & 0xFFFFFF,
                        direction);
            } else {
                Gui.drawRect(
                        x0,
                        y0,
                        x1,
                        y1,
                        0xFF000000 | region.color & 0xFFFFFF);
            }

            int borderColor = index == selected
                    ? 0xFF44FF44
                    : 0xFFFFFFFF;
            drawBorder(x0, y0, x1, y1, borderColor);
            String tag = region.showLabel
                    && region.label != null
                    && !region.label.trim().isEmpty()
                    ? region.label
                    : style.name();
            fontRendererObj.drawStringWithShadow(
                    tag,
                    x0 + 3,
                    y0 + 3,
                    region.showLabel
                            ? 0xFF000000
                                    | region.textColor & 0xFFFFFF
                            : 0xFFFFFFFF);
            if (index == selected) {
                Gui.drawRect(
                        x1 - 5,
                        y1 - 5,
                        x1,
                        y1,
                        0xFFFF8844);
            }
        }

        String information = list.isEmpty()
                ? "Drag on the canvas to add a censor box"
                : "Regions: " + list.size()
                        + (selected >= 0
                                ? "  (selected #" + (selected + 1) + ")"
                                : "");
        fontRendererObj.drawStringWithShadow(
                information,
                canvasX,
                canvasY + canvasHeight + 4,
                0xFFB0B0B0);
        if (!config.streamerModeEnabled) {
            fontRendererObj.drawStringWithShadow(
                    "Streamer Mode is OFF - regions are saved but not applied",
                    canvasX,
                    canvasY - 12,
                    0xFFFFAA55);
        }

        super.drawScreen(mouseX, mouseY, partialTicks);
        if (labelField != null) {
            labelField.drawTextBox();
        }
        for (ColorPickerWidget picker : colorPickers) {
            picker.drawWidget();
        }
        drawBakeInOverlayTooltip(mouseX, mouseY);
    }

    private void drawBakeInOverlayTooltip(int mouseX, int mouseY) {
        GuiButton button = bakeInOverlayToggle;
        if (button == null
                || mouseX < button.xPosition
                || mouseX >= button.xPosition + button.width
                || mouseY < button.yPosition
                || mouseY >= button.yPosition + button.height) {
            return;
        }
        String tooltip = "Controls whether your Streamer Mode censor "
                + "blocks are baked into the recording.\n\n"
                + "OFF (default): the recording stays clean (no censor "
                + "in the saved video). Instead, the censor blocks appear "
                + "as a live on-screen overlay (like a watermark) that "
                + "obstructs scoreboards, coordinates, GUI elements and "
                + "inventories. Regions are layered (they stack), and you "
                + "can show/hide the overlay with the \"Toggle Censor "
                + "Overlay\" hotkey (set it in Options > Controls).\n\n"
                + "ON: the censor is baked into your recording. It may "
                + "also appear on your live screen (controlled by Show "
                + "Preview).";
        List<String> lines = new ArrayList<String>();
        String[] paragraphs = tooltip.split("\\n", -1);
        int tooltipWidth = Math.min(320, Math.max(160, width - 40));
        for (String paragraph : paragraphs) {
            if (paragraph.isEmpty()) {
                lines.add("");
            } else {
                lines.addAll(fontRendererObj.listFormattedStringToWidth(
                        paragraph,
                        tooltipWidth));
            }
        }
        drawHoveringText(lines, mouseX, mouseY);
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

    private static CensorRegion.Style nextStyle(CensorRegion.Style style) {
        CensorRegion.Style[] values = CensorRegion.Style.values();
        return values[(style.ordinal() + 1) % values.length];
    }

    private static CensorRegion.Style previousStyle(
            CensorRegion.Style style) {
        CensorRegion.Style[] values = CensorRegion.Style.values();
        return values[
                (style.ordinal() - 1 + values.length) % values.length];
    }

    private static CensorRegion.GradientDirection nextDirection(
            CensorRegion.GradientDirection direction) {
        CensorRegion.GradientDirection[] values =
                CensorRegion.GradientDirection.values();
        return values[(direction.ordinal() + 1) % values.length];
    }

    private static CensorRegion.GradientDirection previousDirection(
            CensorRegion.GradientDirection direction) {
        CensorRegion.GradientDirection[] values =
                CensorRegion.GradientDirection.values();
        return values[
                (direction.ordinal() - 1 + values.length) % values.length];
    }

    private static CensorRegion.Style parseStyle(String value) {
        if (value != null) {
            try {
                return CensorRegion.Style.valueOf(
                        value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // Fall through to the official default.
            }
        }
        return CensorRegion.Style.SOLID;
    }

    private static String toHex(int rgb) {
        return String.format(Locale.ROOT, "#%06X", rgb & 0xFFFFFF);
    }

    private static int fromHex(String value) {
        try {
            String normalized = value != null && value.startsWith("#")
                    ? value.substring(1)
                    : value;
            return Integer.parseInt(normalized, 16) & 0xFFFFFF;
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private static void drawRegionGradient(
            int x0,
            int y0,
            int x1,
            int y1,
            int colorStart,
            int colorEnd,
            CensorRegion.GradientDirection direction) {
        int startRed = colorStart >> 16 & 0xFF;
        int startGreen = colorStart >> 8 & 0xFF;
        int startBlue = colorStart & 0xFF;
        int endRed = colorEnd >> 16 & 0xFF;
        int endGreen = colorEnd >> 8 & 0xFF;
        int endBlue = colorEnd & 0xFF;
        boolean vertical =
                direction == CensorRegion.GradientDirection.VERTICAL;
        int span = vertical ? y1 - y0 : x1 - x0;
        if (span <= 0) {
            return;
        }
        int strips = Math.min(span, 48);
        for (int strip = 0; strip < strips; strip++) {
            double progress = strips == 1
                    ? 0.0D
                    : strip / (double) (strips - 1);
            int red = (int) Math.round(
                    startRed + (endRed - startRed) * progress);
            int green = (int) Math.round(
                    startGreen + (endGreen - startGreen) * progress);
            int blue = (int) Math.round(
                    startBlue + (endBlue - startBlue) * progress);
            int color = 0xFF000000 | red << 16 | green << 8 | blue;
            int verticalStart = y0 + (int) Math.round(
                    strip / (double) strips * (y1 - y0));
            int verticalEnd = y0 + (int) Math.round(
                    (strip + 1) / (double) strips * (y1 - y0));
            int horizontalStart = x0 + (int) Math.round(
                    strip / (double) strips * (x1 - x0));
            int horizontalEnd = x0 + (int) Math.round(
                    (strip + 1) / (double) strips * (x1 - x0));
            if (vertical) {
                Gui.drawRect(
                        x0,
                        verticalStart,
                        x1,
                        verticalEnd,
                        color);
            } else {
                Gui.drawRect(
                        horizontalStart,
                        y0,
                        horizontalEnd,
                        y1,
                        color);
            }
        }
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

    private static double clamp(
            double value,
            double minimum,
            double maximum) {
        return value < minimum
                ? minimum
                : value > maximum ? maximum : value;
    }
}
