package dev.recordable.screen;

import dev.recordable.RecordableConfig;
import dev.recordable.WatermarkSlot;
import dev.recordable.theme.ThemeColors;
import dev.recordable.theme.ThemeEngine;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Visual position editor for the recording HUD and watermark layers.
 *
 * <p>The recording overlay is drawn in {@code overlayScale} coordinates while
 * the microphone indicator and live watermark renderer use ordinary scaled
 * screen coordinates. This editor deliberately handles both coordinate spaces
 * so dragging remains WYSIWYG when the overlay scale is not 100%.</p>
 */
public final class OverlayPositionScreen extends GuiScreen {
    private static final int BUTTON_DONE = 1;
    private static final int BUTTON_RESET = 2;
    private static final int BUTTON_PANEL = 3;
    private static final int BUTTON_CANCEL = 4;

    private static final int PANEL_WIDTH = 154;
    private static final int PANEL_TOP = 36;
    private static final int ROW_HEIGHT = 16;
    private static final int BUTTON_HEIGHT = 20;
    private static final int RESIZE_HANDLE = 6;

    private static final int BORDER_IDLE = 0x88FFFFFF;
    private static final int BORDER_HOVER = 0xCCFFFF00;
    private static final int BORDER_SELECTED = 0xFF44FF44;
    private static final int SELECTED_FILL = 0x2244FF44;
    private static final int RESIZE_HANDLE_COLOR = 0xFFFF8844;
    private static final int LABEL_BACKGROUND = 0xCC000000;
    private static final int COORD_COLOR = 0xFFAAFFAA;
    private static final int HEADER_COLOR = 0xFFFFFFFF;
    private static final int HINT_COLOR = 0xFFB0B0B0;

    private final GuiScreen parent;
    private final List<String> layerOrder = new ArrayList<String>();
    private final List<Element> elements = new ArrayList<Element>();

    private Snapshot original;
    private boolean resolved;
    private boolean openingWatermarkEditor;

    private float overlayScale = 1.0F;
    private int virtualWidth;
    private int virtualHeight;

    private Element hoveredElement;
    private Element draggedElement;
    private int dragOffsetX;
    private int dragOffsetY;
    private Element resizeElement;
    private ResizeEdge activeResize = ResizeEdge.NONE;
    private ResizeEdge hoveredResize = ResizeEdge.NONE;
    private int resizeOriginalX;
    private int resizeOriginalY;
    private int resizeOriginalWidth;
    private int resizeOriginalHeight;

    private String selectedLayerId;
    private WatermarkSlot selectedWatermark;
    private int panelScroll;
    private boolean panelOpen = true;
    private boolean layersOpen = true;
    private boolean opacityOpen;
    private boolean watermarksOpen = true;
    private final List<OpacityEntry> opacityEntries =
            new ArrayList<OpacityEntry>();
    private OpacityEntry draggingOpacity;

    public OverlayPositionScreen(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        buttonList.clear();
        ThemeEngine.get().applyPreset(RecordableConfig.get().uiTheme);
        if (original == null) {
            RecordableConfig config = RecordableConfig.get();
            original = new Snapshot(config);
            readLayerOrder(config);
        }

        buildOpacityEntries();
        int buttonWidth = 60;
        int gap = 4;
        int totalWidth = buttonWidth * 4 + gap * 3;
        int startX = (width - totalWidth) / 2;
        int buttonY = height - BUTTON_HEIGHT - 6;
        buttonList.add(new GuiButton(
                BUTTON_DONE,
                startX,
                buttonY,
                buttonWidth,
                BUTTON_HEIGHT,
                "Done"));
        buttonList.add(new GuiButton(
                BUTTON_RESET,
                startX + buttonWidth + gap,
                buttonY,
                buttonWidth,
                BUTTON_HEIGHT,
                "Reset All"));
        buttonList.add(new GuiButton(
                BUTTON_PANEL,
                startX + (buttonWidth + gap) * 2,
                buttonY,
                buttonWidth,
                BUTTON_HEIGHT,
                panelOpen ? "\u25B6 Panel" : "\u25C0 Panel"));
        buttonList.add(new GuiButton(
                BUTTON_CANCEL,
                startX + (buttonWidth + gap) * 3,
                buttonY,
                buttonWidth,
                BUTTON_HEIGHT,
                "Cancel"));
        updateCoordinateSpace();
        rebuildElements();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return true;
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button == null || !button.enabled) {
            return;
        }
        if (button.id == BUTTON_DONE) {
            saveAndClose();
        } else if (button.id == BUTTON_RESET) {
            resetAll();
            buildOpacityEntries();
        } else if (button.id == BUTTON_PANEL) {
            panelOpen = !panelOpen;
            button.displayString =
                    panelOpen ? "\u25B6 Panel" : "\u25C0 Panel";
        } else if (button.id == BUTTON_CANCEL) {
            cancelAndClose();
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        updateCoordinateSpace();
        rebuildElements();

        Gui.drawRect(0, 0, width, height, 0xFF1A1A1A);
        Gui.drawRect(0, 0, width, height, 0x66000000);

        int scaledMouseX = (int) Math.floor(mouseX / (double) overlayScale);
        int scaledMouseY = (int) Math.floor(mouseY / (double) overlayScale);
        updateHover(mouseX, mouseY, scaledMouseX, scaledMouseY);

        GlStateManager.pushMatrix();
        try {
            GlStateManager.scale(overlayScale, overlayScale, 1.0F);
            drawFilterPreview();
            drawGuides();

            List<Element> ordered = orderedElements();
            for (Element element : ordered) {
                drawElement(element);
            }
        } finally {
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            GlStateManager.popMatrix();
        }

        if (panelOpen) {
            drawPanel(mouseX, mouseY);
        }

        drawCenteredString(
                fontRendererObj,
                "Overlay Position Editor",
                width / 2,
                4,
                HEADER_COLOR);
        String hint;
        if (activeResize != ResizeEdge.NONE) {
            hint = "Drag to resize \u00B7 Release to confirm";
        } else if (draggedElement != null) {
            hint = "Release to drop \u00B7 Coordinates update in real time";
        } else {
            hint = "Drag to move \u00B7 Drag handles to resize "
                    + "\u00B7 Right-click to reset \u00B7 ESC to cancel";
        }
        drawCenteredString(
                fontRendererObj,
                hint,
                width / 2,
                15,
                HINT_COLOR);
        if (overlayScale != 1.0F) {
            drawCenteredString(
                    fontRendererObj,
                    "Scale " + Math.round(overlayScale * 100.0F) + "%",
                    width / 2,
                    26,
                    0xFF777744);
        }

        if (draggedElement != null
                || activeResize != ResizeEdge.NONE) {
            Element active = draggedElement != null
                    ? draggedElement
                    : resizeElement;
            String value = activeResize != ResizeEdge.NONE
                    ? active.id + " " + active.w + "\u00D7" + active.h
                    : active.id + " " + displayCoordinates(active);
            int valueWidth = fontRendererObj.getStringWidth(value) + 8;
            int tx = mouseX + 14;
            int ty = mouseY - 14;
            if (tx + valueWidth > width) {
                tx = mouseX - valueWidth - 4;
            }
            if (ty < 0) {
                ty = mouseY + 18;
            }
            Gui.drawRect(
                    tx - 2,
                    ty - 2,
                    tx + valueWidth,
                    ty + 12,
                    LABEL_BACKGROUND);
            fontRendererObj.drawStringWithShadow(
                    value,
                    tx + 2,
                    ty,
                    COORD_COLOR);
        }

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void updateCoordinateSpace() {
        RecordableConfig config = RecordableConfig.get();
        overlayScale = clamp(config.overlayScale / 100.0F, 0.5F, 2.0F);
        virtualWidth = Math.max(1, (int) Math.floor(width / overlayScale));
        virtualHeight = Math.max(1, (int) Math.floor(height / overlayScale));
    }

    private void drawGuides() {
        int centerX = virtualWidth / 2;
        int centerY = virtualHeight / 2;
        Gui.drawRect(centerX, 0, centerX + 1, virtualHeight, 0x44FFFFFF);
        Gui.drawRect(0, centerY, virtualWidth, centerY + 1, 0x44FFFFFF);
        Gui.drawRect(
                virtualWidth / 3, 0, virtualWidth / 3 + 1,
                virtualHeight, 0x22FFFFFF);
        Gui.drawRect(
                virtualWidth * 2 / 3, 0, virtualWidth * 2 / 3 + 1,
                virtualHeight, 0x22FFFFFF);
        Gui.drawRect(
                0, virtualHeight / 3, virtualWidth,
                virtualHeight / 3 + 1, 0x22FFFFFF);
        Gui.drawRect(
                0, virtualHeight * 2 / 3, virtualWidth,
                virtualHeight * 2 / 3 + 1, 0x22FFFFFF);
    }

    private void drawFilterPreview() {
        RecordableConfig config = RecordableConfig.get();
        if (config.filterVhsVisible) {
            int alpha = 8 + config.filterVhsIntensity * 20 / 100;
            int color = alpha << 24;
            for (int y = 3; y < virtualHeight; y += 8) {
                Gui.drawRect(0, y, virtualWidth, y + 1, color);
            }
        }
        if (config.filterLcdMoireVisible) {
            int alpha = 3 + config.filterLcdMoireIntensity * 10 / 100;
            int color = (alpha << 24) | 0x0060B0FF;
            for (int x = 4; x < virtualWidth; x += 12) {
                Gui.drawRect(x, 0, x + 1, virtualHeight, color);
            }
        }
        if (config.filterCrtVisible) {
            int alpha = 8 + config.filterCrtIntensity * 24 / 100;
            int shade = alpha << 24;
            int thickness = Math.max(2, Math.min(14, virtualWidth / 30));
            Gui.drawRect(0, 0, virtualWidth, thickness, shade);
            Gui.drawRect(0, virtualHeight - thickness,
                    virtualWidth, virtualHeight, shade);
            Gui.drawRect(0, 0, thickness, virtualHeight, shade);
            Gui.drawRect(virtualWidth - thickness, 0,
                    virtualWidth, virtualHeight, shade);
        }
    }

    private void rebuildElements() {
        elements.clear();
        RecordableConfig config = RecordableConfig.get();
        RecordableConfig.OverlayStyleHud style = config.overlayStyleHud;
        if (style == null) {
            style = RecordableConfig.OverlayStyleHud.CLASSIC;
        }

        if (style == RecordableConfig.OverlayStyleHud.VHS) {
            addVhsElements(config);
        } else if (style == RecordableConfig.OverlayStyleHud.CLASSIC) {
            addClassicElement(config);
        } else if (style == RecordableConfig.OverlayStyleHud.SYNTHWAVE) {
            addSynthwaveElement(config);
        }

        addMicrophoneElement(config);
        addWatermarkElements(config);
    }

    private void addVhsElements(RecordableConfig config) {
        int playWidth = Math.max(
                playLabelWidth(),
                fontRendererObj.getStringWidth("REC") + 12) + 8;
        int playHeight = config.vhsShowPlay ? 24 : 12;
        Element play = new Element(
                "PLAY/REC", "PLAY / REC",
                config.hudPlayRecX,
                config.hudPlayRecY,
                config.hudPlayRecW > 0 ? config.hudPlayRecW : playWidth,
                config.hudPlayRecH > 0 ? config.hudPlayRecH : playHeight);
        play.resizable = true;
        elements.add(play);

        int timestampWidth = fontRendererObj.getStringWidth("00:12:34") + 6;
        int timestampActualWidth = config.hudTimestampW > 0
                ? config.hudTimestampW
                : timestampWidth;
        Element timestamp = new Element(
                "Timestamp", "Timestamp",
                virtualWidth - Math.max(0, config.hudTimestampOffsetX)
                        - timestampActualWidth,
                config.hudTimestampY,
                timestampActualWidth,
                config.hudTimestampH > 0 ? config.hudTimestampH : 12);
        timestamp.resizable = true;
        elements.add(timestamp);

        Element corners = new Element(
                "Corners", "Corner frame",
                config.hudCornersX,
                config.hudCornersY,
                Math.max(20, config.hudCornersWidth),
                Math.max(20, config.hudCornersHeight));
        corners.resizable = true;
        elements.add(corners);

        int spWidth = fontRendererObj.getStringWidth("SP") + 6;
        Element sp = new Element(
                "SP", "SP",
                config.hudSpX,
                virtualHeight - config.hudSpOffsetY,
                config.hudSpW > 0 ? config.hudSpW : spWidth,
                config.hudSpH > 0 ? config.hudSpH : 12);
        sp.resizable = true;
        elements.add(sp);

        int detailsHeight = 4;
        if (config.vhsShowDate) detailsHeight += 22;
        if (config.vhsShowTapeCounter) detailsHeight += 11;
        if (config.vhsShowAudioMeter) detailsHeight += 12;
        if (config.vhsShowBattery) detailsHeight += 13;
        detailsHeight = Math.max(22, detailsHeight);
        int detailsWidth = 80;
        int detailsActualWidth = config.hudDetailsW > 0
                ? config.hudDetailsW
                : detailsWidth;
        int detailsActualHeight = config.hudDetailsH > 0
                ? config.hudDetailsH
                : detailsHeight;
        Element details = new Element(
                "Details", "Details",
                virtualWidth - Math.max(0, config.hudDetailsOffsetX)
                        - detailsActualWidth,
                virtualHeight - Math.max(0, config.hudDetailsOffsetY)
                        - detailsActualHeight,
                detailsActualWidth,
                detailsActualHeight);
        details.resizable = true;
        elements.add(details);

        String[] performanceLines = previewPerformanceLines(config);
        int performanceWidth = 0;
        for (String line : performanceLines) {
            performanceWidth = Math.max(
                    performanceWidth,
                    fontRendererObj.getStringWidth(line));
        }
        performanceWidth += 10;
        int performanceHeight = performanceLines.length * 10 + 6;
        int performanceActualWidth = config.hudPerfW > 0
                ? config.hudPerfW
                : performanceWidth;
        int performanceActualHeight = config.hudPerfH > 0
                ? config.hudPerfH
                : performanceHeight;
        Element performance = new Element(
                "Perf", "Performance",
                virtualWidth - Math.max(0, config.hudPerfOffsetX)
                        - performanceActualWidth,
                virtualHeight - Math.max(0, config.hudPerfOffsetY)
                        - performanceActualHeight,
                performanceActualWidth,
                performanceActualHeight);
        performance.resizable = true;
        elements.add(performance);
    }

    private void addClassicElement(RecordableConfig config) {
        String[] lines = previewClassicLines(config);
        int widest = fontRendererObj.getStringWidth(lines[0]) + 13;
        for (int index = 1; index < lines.length; index++) {
            widest = Math.max(
                    widest,
                    fontRendererObj.getStringWidth(lines[index]));
        }
        int panelWidth = widest + 22;
        int panelHeight = 12 + (lines.length - 1) * 11;
        int[] position = resolvePanelPosition(
                config, panelWidth, panelHeight,
                config.hudClassicX, config.hudClassicY, 10);
        elements.add(new Element(
                "Classic", "Classic HUD",
                position[0], position[1], panelWidth, panelHeight));
    }

    private void addSynthwaveElement(RecordableConfig config) {
        String line = "REC 00:12:34";
        int panelWidth = fontRendererObj.getStringWidth(line) + 22;
        int panelHeight = 16;
        int[] position = resolvePanelPosition(
                config, panelWidth, panelHeight,
                config.hudSynthX, config.hudSynthY, 0);
        elements.add(new Element(
                "Synthwave", "Synthwave HUD",
                position[0], position[1], panelWidth, panelHeight));
    }

    private void addMicrophoneElement(RecordableConfig config) {
        String text = config.microphonePushToTalk
                ? "MIC (PTT)"
                : "MIC";
        int actualWidth = fontRendererObj.getStringWidth(text) + 27;
        int actualHeight = 13;
        int actualX = config.hudMicX < 0
                ? (width - actualWidth) / 2
                : config.hudMicX;
        int actualY = Math.max(0, config.hudMicY);
        Element element = new Element(
                "Mic", "Microphone",
                toVirtual(actualX),
                toVirtual(actualY),
                Math.max(1, toVirtualCeil(actualWidth)),
                Math.max(1, toVirtualCeil(actualHeight)));
        elements.add(element);
    }

    private void addWatermarkElements(RecordableConfig config) {
        if (config.watermarkSlots == null) {
            return;
        }
        for (int index = 0; index < config.watermarkSlots.size(); index++) {
            WatermarkSlot slot = config.watermarkSlots.get(index);
            if (slot == null || !slot.enabled) {
                continue;
            }
            String preview = watermarkPreviewText(slot);
            float watermarkScale = clamp(slot.scale / 100.0F, 0.1F, 2.0F);
            int actualWidth;
            int actualHeight;
            if (slot.kind == WatermarkSlot.Kind.IMAGE) {
                int size = Math.max(
                        14,
                        Math.round(Math.min(100.0F, width * 0.09F)
                                * watermarkScale));
                actualWidth = size;
                actualHeight = size;
            } else {
                actualWidth = Math.max(
                        8,
                        Math.round(fontRendererObj.getStringWidth(preview)
                                * watermarkScale));
                actualHeight = Math.max(
                        8,
                        Math.round(fontRendererObj.FONT_HEIGHT
                                * watermarkScale));
            }
            int[] actualPosition = watermarkPosition(
                    slot, actualWidth, actualHeight);
            Element element = new Element(
                    "WM:" + index,
                    slot.name == null || slot.name.trim().isEmpty()
                            ? "Watermark " + (index + 1)
                            : slot.name,
                    toVirtual(actualPosition[0]),
                    toVirtual(actualPosition[1]),
                    Math.max(2, toVirtualCeil(actualWidth)),
                    Math.max(2, toVirtualCeil(actualHeight)));
            element.watermark = slot;
            elements.add(element);
        }
    }

    private List<Element> orderedElements() {
        List<Element> ordered = new ArrayList<Element>();
        for (String layer : layerOrder) {
            Element element = findLayerElement(layer);
            if (element != null) {
                ordered.add(element);
            }
        }
        for (WatermarkSlot slot : RecordableConfig.get().watermarkSlots) {
            Element element = findWatermarkElement(slot);
            if (element != null) {
                ordered.add(element);
            }
        }
        return ordered;
    }

    private void buildOpacityEntries() {
        opacityEntries.clear();
        final RecordableConfig config = RecordableConfig.get();
        RecordableConfig.OverlayStyleHud style = config.overlayStyleHud;
        if (style == RecordableConfig.OverlayStyleHud.VHS) {
            opacityEntries.add(new OpacityEntry(
                    "PLAY/REC", "REC",
                    () -> Integer.valueOf(config.hudPlayRecOpacity),
                    value -> config.hudPlayRecOpacity = value.intValue()));
            opacityEntries.add(new OpacityEntry(
                    "Timestamp", "Time",
                    () -> Integer.valueOf(config.hudTimestampOpacity),
                    value -> config.hudTimestampOpacity = value.intValue()));
            opacityEntries.add(new OpacityEntry(
                    "Corners", "Corners",
                    () -> Integer.valueOf(config.hudCornersOpacity),
                    value -> config.hudCornersOpacity = value.intValue()));
            opacityEntries.add(new OpacityEntry(
                    "SP", "SP",
                    () -> Integer.valueOf(config.hudSpOpacity),
                    value -> config.hudSpOpacity = value.intValue()));
            opacityEntries.add(new OpacityEntry(
                    "Details", "Details",
                    () -> Integer.valueOf(config.hudDetailsOpacity),
                    value -> config.hudDetailsOpacity = value.intValue()));
            opacityEntries.add(new OpacityEntry(
                    "Perf", "Perf",
                    () -> Integer.valueOf(config.hudPerfOpacity),
                    value -> config.hudPerfOpacity = value.intValue()));
        }
        opacityEntries.add(new OpacityEntry(
                "Filter:VHS", "VHS",
                () -> Integer.valueOf(config.filterVhsIntensity),
                value -> config.filterVhsIntensity = value.intValue()));
        opacityEntries.add(new OpacityEntry(
                "Filter:LCD_MOIRE", "LCD Moire",
                () -> Integer.valueOf(config.filterLcdMoireIntensity),
                value -> config.filterLcdMoireIntensity = value.intValue()));
        opacityEntries.add(new OpacityEntry(
                "Filter:CRT", "CRT",
                () -> Integer.valueOf(config.filterCrtIntensity),
                value -> config.filterCrtIntensity = value.intValue()));
    }

    private List<String> shownLayers() {
        List<String> shown = new ArrayList<String>();
        for (String id : layerOrder) {
            if (rowApplicable(PanelRow.layer(id))) {
                shown.add(id);
            }
        }
        return shown;
    }

    private void moveShownLayer(
            List<String> shown, int index, int direction) {
        int target = index + direction;
        if (index < 0
                || target < 0
                || index >= shown.size()
                || target >= shown.size()) {
            return;
        }
        int fullIndex = layerOrder.indexOf(shown.get(index));
        int fullTarget = layerOrder.indexOf(shown.get(target));
        if (fullIndex < 0 || fullTarget < 0) {
            return;
        }
        Collections.swap(layerOrder, fullIndex, fullTarget);
        RecordableConfig config = RecordableConfig.get();
        config.hudLayerOrder = join(layerOrder);
        config.save();
    }

    private static String elementIcon(String id) {
        if ("PLAY/REC".equals(id)) return "\u25CF";
        if ("Timestamp".equals(id)) return "\u23F1";
        if ("Details".equals(id)) return "\u2139";
        if ("SP".equals(id)) return "\u25B6";
        if ("Perf".equals(id)) return "\u2261";
        if ("Corners".equals(id)) return "\u2B1C";
        if ("Mic".equals(id)) return "\u266A";
        if ("Classic".equals(id) || "Synthwave".equals(id)) {
            return "\u25A4";
        }
        if ("Filter:VHS".equals(id)
                || "Filter:LCD_MOIRE".equals(id)
                || "Filter:CRT".equals(id)) {
            return "\u25A3";
        }
        return "\u2022";
    }

    private static String layerDisplayName(String id) {
        if ("Filter:VHS".equals(id)) return "VHS Filter";
        if ("Filter:LCD_MOIRE".equals(id)) return "LCD Moire Filter";
        if ("Filter:CRT".equals(id)) return "CRT Filter";
        return id;
    }

    private String displayCoordinates(Element element) {
        if ("Timestamp".equals(element.id)) {
            return "\u2190" + element.x + " " + element.y;
        }
        if ("SP".equals(element.id)) {
            return element.x + " \u2191" + element.y;
        }
        if ("Details".equals(element.id)
                || "Perf".equals(element.id)) {
            return "\u2190" + element.x + " \u2191" + element.y;
        }
        if ("Corners".equals(element.id)) {
            return element.x + "," + element.y + " "
                    + element.w + "\u00D7" + element.h;
        }
        return element.x + "," + element.y;
    }

    private ResizeEdge hitTestResizeHandle(
            Element element, int mouseX, int mouseY) {
        if (!element.resizable) {
            return ResizeEdge.NONE;
        }
        int size = RESIZE_HANDLE;
        if (mouseX >= element.x - size
                && mouseX <= element.x + size
                && mouseY >= element.y - size
                && mouseY <= element.y + size) {
            return ResizeEdge.TOP_LEFT;
        }
        if (mouseX >= element.x + element.w - size
                && mouseX <= element.x + element.w + size
                && mouseY >= element.y - size
                && mouseY <= element.y + size) {
            return ResizeEdge.TOP_RIGHT;
        }
        if (mouseX >= element.x - size
                && mouseX <= element.x + size
                && mouseY >= element.y + element.h - size
                && mouseY <= element.y + element.h + size) {
            return ResizeEdge.BOTTOM_LEFT;
        }
        if (mouseX >= element.x + element.w - size
                && mouseX <= element.x + element.w + size
                && mouseY >= element.y + element.h - size
                && mouseY <= element.y + element.h + size) {
            return ResizeEdge.BOTTOM_RIGHT;
        }
        return ResizeEdge.NONE;
    }

    private static boolean nearCornerBracket(
            Element element, int mouseX, int mouseY) {
        int zone = 40;
        boolean nearTopLeft = mouseX < element.x + zone
                && mouseY < element.y + zone;
        boolean nearTopRight = mouseX > element.x + element.w - zone
                && mouseY < element.y + zone;
        boolean nearBottomLeft = mouseX < element.x + zone
                && mouseY > element.y + element.h - zone;
        boolean nearBottomRight = mouseX > element.x + element.w - zone
                && mouseY > element.y + element.h - zone;
        return nearTopLeft || nearTopRight
                || nearBottomLeft || nearBottomRight;
    }

    private void openWatermarkEditor() {
        if (mc != null) {
            openingWatermarkEditor = true;
            mc.displayGuiScreen(new WatermarkScreen(this));
        }
    }

    private void updateHover(
            int mouseX, int mouseY, int scaledMouseX, int scaledMouseY) {
        hoveredElement = null;
        hoveredResize = ResizeEdge.NONE;
        if (draggedElement != null
                || activeResize != ResizeEdge.NONE
                || panelOpen
                        && mouseX >= width - PANEL_WIDTH - 10) {
            return;
        }
        List<Element> ordered = orderedElements();
        for (int index = ordered.size() - 1; index >= 0; index--) {
            Element element = ordered.get(index);
            if (!isElementVisible(element) || !element.resizable) {
                continue;
            }
            ResizeEdge edge = hitTestResizeHandle(
                    element,
                    scaledMouseX,
                    scaledMouseY);
            if (edge != ResizeEdge.NONE) {
                hoveredResize = edge;
                hoveredElement = element;
                return;
            }
        }
        for (int index = ordered.size() - 1; index >= 0; index--) {
            Element element = ordered.get(index);
            if (!isElementVisible(element)) {
                continue;
            }
            if (element.contains(scaledMouseX, scaledMouseY)) {
                if ("Corners".equals(element.id)
                        && !nearCornerBracket(
                                element,
                                scaledMouseX,
                                scaledMouseY)) {
                    continue;
                }
                hoveredElement = element;
                return;
            }
        }
    }

    private void drawElement(Element element) {
        boolean visible = isElementVisible(element);
        boolean hovered = sameElement(element, hoveredElement);
        boolean dragged = sameElement(element, draggedElement);
        boolean resizing = activeResize != ResizeEdge.NONE
                && sameElement(element, resizeElement);

        if (visible) {
            drawElementContent(element);
        } else {
            int ghostBorder = 0x33FF4444;
            Gui.drawRect(
                    element.x,
                    element.y,
                    element.x + element.w,
                    element.y + element.h,
                    0x08FF4444);
            drawBorder(
                    element.x,
                    element.y,
                    element.x + element.w,
                    element.y + element.h,
                    ghostBorder);
            String hiddenLabel = "\u2298 " + element.id;
            int hiddenWidth =
                    fontRendererObj.getStringWidth(hiddenLabel) + 4;
            int hiddenY = element.y - 10;
            if (hiddenY < 0) {
                hiddenY = element.y + element.h + 1;
            }
            Gui.drawRect(
                    element.x,
                    hiddenY,
                    element.x + hiddenWidth,
                    hiddenY + 9,
                    0x66000000);
            fontRendererObj.drawStringWithShadow(
                    hiddenLabel,
                    element.x + 2,
                    hiddenY + 1,
                    0x55FF6666);
            return;
        }

        int border = dragged || resizing
                ? BORDER_SELECTED
                : hovered ? BORDER_HOVER : BORDER_IDLE;
        int fill = dragged || resizing
                ? SELECTED_FILL
                : hovered ? 0x18FFFF00 : 0x08FFFFFF;
        Gui.drawRect(
                element.x, element.y,
                element.x + element.w, element.y + element.h,
                fill);
        drawBorder(
                element.x, element.y,
                element.x + element.w, element.y + element.h,
                border);

        if (element.resizable) {
            drawResizeHandles(element, hovered);
        }

        String tag = elementIcon(element.id) + " " + element.id;
        int tagWidth = fontRendererObj.getStringWidth(tag) + 4;
        int tagY = element.y - 10;
        if (tagY < 0) {
            tagY = element.y + element.h + 1;
        }
        Gui.drawRect(
                element.x, tagY,
                element.x + tagWidth, tagY + 10,
                LABEL_BACKGROUND);
        fontRendererObj.drawStringWithShadow(
                tag,
                element.x + 2,
                tagY + 1,
                border);
    }

    private void drawResizeHandles(Element element, boolean hovered) {
        int half = RESIZE_HANDLE / 2;
        drawHandle(
                element.x - half,
                element.y - half,
                hovered && hoveredResize == ResizeEdge.TOP_LEFT
                        ? RESIZE_HANDLE_COLOR
                        : 0x88FFFFFF);
        drawHandle(
                element.x + element.w - half,
                element.y - half,
                hovered && hoveredResize == ResizeEdge.TOP_RIGHT
                        ? RESIZE_HANDLE_COLOR
                        : 0x88FFFFFF);
        drawHandle(
                element.x - half,
                element.y + element.h - half,
                hovered && hoveredResize == ResizeEdge.BOTTOM_LEFT
                        ? RESIZE_HANDLE_COLOR
                        : 0x88FFFFFF);
        drawHandle(
                element.x + element.w - half,
                element.y + element.h - half,
                hovered && hoveredResize == ResizeEdge.BOTTOM_RIGHT
                        ? RESIZE_HANDLE_COLOR
                        : 0x88FFFFFF);
    }

    private static void drawHandle(int x, int y, int color) {
        Gui.drawRect(
                x,
                y,
                x + RESIZE_HANDLE,
                y + RESIZE_HANDLE,
                color);
        drawBorder(
                x,
                y,
                x + RESIZE_HANDLE,
                y + RESIZE_HANDLE,
                0xAA000000);
    }

    private int playLabelWidth() {
        return fontRendererObj.getStringWidth("PLAY") + 7;
    }

    private String[] previewPerformanceLines(RecordableConfig config) {
        return new String[]{
                "Cap 60 | Enc 60 FPS",
                "Mem 1024 MiB | Drop 0",
                "Queue: " + previewQueueOccupancy(config) + " | 100%"
        };
    }

    private String[] previewClassicLines(RecordableConfig config) {
        return new String[]{
                "REC 00:12:34",
                "60 FPS  drop 0",
                "Size: 12.3 MB",
                "1920x1080  Queue: "
                        + previewQueueOccupancy(config)
                        + " (OK)"
        };
    }

    private static String previewQueueOccupancy(RecordableConfig config) {
        int capacity = config != null && config.perfModeGamePriority ? 6 : 12;
        return "0/" + capacity;
    }

    private static ThemeColors activeOverlaySkin(RecordableConfig config) {
        if (config == null || !config.overlaySkinEnabled) {
            return null;
        }
        return ThemeColors.forPreset(config.uiTheme);
    }

    private void drawPlayLabel(int x, int y, int color) {
        String label = "PLAY";
        fontRendererObj.drawStringWithShadow(label, x, y, color);
        drawPlayTriangle(
                x + fontRendererObj.getStringWidth(label) + 3,
                y + 1,
                color);
    }

    private static void drawPlayTriangle(int x, int y, int color) {
        if (((color >>> 24) & 255) < 4) return;
        Gui.drawRect(x, y, x + 1, y + 8, color);
        Gui.drawRect(x + 1, y + 1, x + 2, y + 7, color);
        Gui.drawRect(x + 2, y + 2, x + 3, y + 6, color);
        Gui.drawRect(x + 3, y + 3, x + 4, y + 5, color);
    }

    private static void drawRecordDot(int x, int y, int color) {
        if (((color >>> 24) & 255) < 4) return;
        Gui.drawRect(x + 2, y, x + 5, y + 1, color);
        Gui.drawRect(x + 1, y + 1, x + 6, y + 2, color);
        Gui.drawRect(x, y + 2, x + 7, y + 5, color);
        Gui.drawRect(x + 1, y + 5, x + 6, y + 6, color);
        Gui.drawRect(x + 2, y + 6, x + 5, y + 7, color);
    }

    private static void drawMicrophoneGlyph(int x, int y, int color) {
        if (((color >>> 24) & 255) < 4) return;
        Gui.drawRect(x + 2, y, x + 6, y + 6, color);
        Gui.drawRect(x + 1, y + 1, x + 2, y + 6, color);
        Gui.drawRect(x + 6, y + 1, x + 7, y + 6, color);
        Gui.drawRect(x, y + 4, x + 1, y + 7, color);
        Gui.drawRect(x + 7, y + 4, x + 8, y + 7, color);
        Gui.drawRect(x + 1, y + 7, x + 7, y + 8, color);
        Gui.drawRect(x + 3, y + 8, x + 5, y + 10, color);
        Gui.drawRect(x + 1, y + 9, x + 7, y + 10, color);
    }

    private static void drawAudioMeterPreview(int x, int y, int opacity) {
        int background = RecordableConfig.applyOpacity(
                0xFF333333,
                opacity);
        int level = RecordableConfig.applyOpacity(0xFF44CC44, opacity);
        Gui.drawRect(x, y, x + 55, y + 3, background);
        Gui.drawRect(x, y + 4, x + 55, y + 7, background);
        Gui.drawRect(x, y, x + 30, y + 3, level);
        Gui.drawRect(x, y + 4, x + 28, y + 7, level);
    }

    private void drawBatteryPreview(int x, int y, int opacity) {
        int shell = RecordableConfig.applyOpacity(0xFFAAAAAA, opacity);
        Gui.drawRect(x, y, x + 24, y + 10, shell);
        Gui.drawRect(
                x + 1,
                y + 1,
                x + 23,
                y + 9,
                RecordableConfig.applyOpacity(0xFF222222, opacity));
        Gui.drawRect(x + 24, y + 2, x + 26, y + 8, shell);
        Gui.drawRect(
                x + 2,
                y + 2,
                x + 18,
                y + 8,
                RecordableConfig.applyOpacity(0xFF44CC44, opacity));
        fontRendererObj.drawString(
                "98%",
                x + 29,
                y + 1,
                RecordableConfig.applyOpacity(0xFFCCCCCC, opacity));
    }

    private void drawElementContent(Element element) {
        RecordableConfig config = RecordableConfig.get();
        ThemeColors skin = activeOverlaySkin(config);
        if (element.watermark != null) {
            drawWatermarkPreview(element);
        } else if ("PLAY/REC".equals(element.id)) {
            int opacity = config.hudPlayRecOpacity;
            int play = RecordableConfig.applyOpacity(
                    skin != null
                            ? skin.textPrimary
                            : RecordableConfig.parseArgbColor(
                                    config.vhsPlayColor, 0xFFFFFFFF),
                    opacity);
            int rec = RecordableConfig.applyOpacity(
                    skin != null
                            ? skin.textPrimary
                            : RecordableConfig.parseArgbColor(
                                    config.vhsRecTextColor, 0xFFFFFFFF),
                    opacity);
            int dot = RecordableConfig.applyOpacity(
                    skin != null
                            ? skin.accent
                            : RecordableConfig.parseArgbColor(
                                    config.vhsRecDotColor, 0xFFCC1E1E),
                    opacity);
            int y = element.y;
            if (config.vhsShowPlay) {
                drawPlayLabel(element.x + 2, y + 1, play);
                y += 12;
            }
            drawRecordDot(element.x + 2, y + 2, dot);
            fontRendererObj.drawString("REC", element.x + 11, y + 1, rec);
        } else if ("Timestamp".equals(element.id)) {
            fontRendererObj.drawString(
                    "00:12:34",
                    element.x + 2,
                    element.y + 2,
                    RecordableConfig.applyOpacity(
                            skin != null
                                    ? skin.textPrimary
                                    : RecordableConfig.parseArgbColor(
                                            config.vhsTimestampColor,
                                            0xFFFFFFFF),
                            config.hudTimestampOpacity));
        } else if ("Corners".equals(element.id)) {
            int color = RecordableConfig.applyOpacity(
                    skin != null
                            ? skin.accent
                            : RecordableConfig.parseArgbColor(
                                    config.vhsBracketColor, 0xC8FFFFFF),
                    config.hudCornersOpacity);
            drawCornerBrackets(
                    element.x, element.y,
                    element.x + element.w, element.y + element.h,
                    Math.min(20, Math.min(element.w, element.h) / 2),
                    2,
                    color);
        } else if ("SP".equals(element.id)) {
            fontRendererObj.drawString(
                    "SP", element.x + 1, element.y + 1,
                    RecordableConfig.applyOpacity(
                            skin != null
                                    ? skin.textPrimary
                                    : RecordableConfig.parseArgbColor(
                                            config.vhsSpColor, 0xFFFFFFFF),
                            config.hudSpOpacity));
        } else if ("Details".equals(element.id)) {
            int color = RecordableConfig.applyOpacity(
                    RecordableConfig.parseArgbColor(
                            config.vhsDateColor, 0xFFFFFFFF),
                    config.hudDetailsOpacity);
            int right = element.x + element.w;
            int cursorY = element.y + element.h;
            if (config.vhsShowDate) {
                cursorY -= 22;
                drawRight("12:34 PM", right, cursorY, color);
                drawRight("Jul 23 2026", right, cursorY + 11, color);
                cursorY -= 4;
            } else {
                cursorY -= 4;
            }
            if (config.vhsShowTapeCounter) {
                cursorY -= 11;
                drawRight(
                        "TC 0012",
                        right,
                        cursorY,
                        RecordableConfig.applyOpacity(
                                0xFFCCCCCC,
                                config.hudDetailsOpacity));
            }
            if (config.vhsShowAudioMeter) {
                cursorY -= 12;
                drawAudioMeterPreview(
                        right - 60,
                        cursorY,
                        config.hudDetailsOpacity);
            }
            if (config.vhsShowBattery) {
                cursorY -= 13;
                drawBatteryPreview(
                        right - 50,
                        cursorY,
                        config.hudDetailsOpacity);
            }
        } else if ("Perf".equals(element.id)) {
            int opacity = config.hudPerfOpacity;
            Gui.drawRect(
                    element.x, element.y,
                    element.x + element.w, element.y + element.h,
                    RecordableConfig.applyOpacity(0x99000000, opacity));
            String[] lines = previewPerformanceLines(config);
            for (int index = 0; index < lines.length; index++) {
                fontRendererObj.drawString(
                        lines[index],
                        element.x + 3,
                        element.y + 3 + index * 10,
                        RecordableConfig.applyOpacity(
                                index == lines.length - 1
                                        ? 0xFF9BE28F
                                        : 0xFFD0D0D0,
                                opacity));
            }
        } else if ("Classic".equals(element.id)) {
            int accent = skin != null
                    ? 0xFF000000 | (skin.accent & 0x00FFFFFF)
                    : 0xFF000000 | config.getOverlayColorRgb();
            int panelBackground = skin != null
                    ? skin.panelBackground
                    : 0x99000000;
            Gui.drawRect(
                    element.x - 3, element.y - 3,
                    element.x + element.w, element.y + element.h,
                    panelBackground);
            Gui.drawRect(
                    element.x - 3, element.y - 3,
                    element.x + element.w, element.y - 2,
                    RecordableConfig.applyOpacity(accent, 67));
            String[] lines = previewClassicLines(config);
            drawRecordDot(element.x, element.y + 2, accent);
            fontRendererObj.drawStringWithShadow(
                    lines[0], element.x + 13, element.y, 0xFFFFFFFF);
            fontRendererObj.drawStringWithShadow(
                    lines[1],
                    element.x,
                    element.y + 12,
                    skin != null ? skin.textSecondary : 0xFFE0E0E0);
            fontRendererObj.drawStringWithShadow(
                    lines[2], element.x, element.y + 23, 0xFFFFFFFF);
            fontRendererObj.drawStringWithShadow(
                    lines[3], element.x, element.y + 34, 0xFF9BE28F);
        } else if ("Synthwave".equals(element.id)) {
            int magenta = skin != null ? skin.accent : 0xFFFF2D95;
            int cyan = skin != null ? skin.accentHover : 0xFF00E5FF;
            int panelBackground = skin != null
                    ? skin.panelBackground
                    : 0xE61A0B2E;
            int textColor = skin != null ? skin.textPrimary : cyan;
            Gui.drawRect(
                    element.x, element.y,
                    element.x + element.w, element.y + element.h,
                    panelBackground);
            Gui.drawRect(
                    element.x, element.y,
                    element.x + element.w, element.y + 1,
                    magenta);
            Gui.drawRect(
                    element.x, element.y + element.h - 1,
                    element.x + element.w, element.y + element.h,
                    cyan);
            Gui.drawRect(
                    element.x, element.y,
                    element.x + 1, element.y + element.h,
                    magenta);
            Gui.drawRect(
                    element.x + element.w - 1, element.y,
                    element.x + element.w, element.y + element.h,
                    cyan);
            drawRecordDot(element.x + 6, element.y + 5, magenta);
            fontRendererObj.drawStringWithShadow(
                    "REC 00:12:34",
                    element.x + 16,
                    element.y + 4,
                    textColor);
        } else if ("Mic".equals(element.id)) {
            int opacity = config.hudMicOpacity;
            boolean live = !config.microphonePushToTalk;
            String label = config.microphonePushToTalk
                    ? "MIC (PTT)"
                    : "MIC";
            Gui.drawRect(
                    element.x, element.y,
                    element.x + element.w, element.y + element.h,
                    RecordableConfig.applyOpacity(0xA0000000, opacity));
            int text = RecordableConfig.applyOpacity(
                    live ? 0xFFFFFFFF : 0xFF888888,
                    opacity);
            drawRecordDot(
                    element.x + 3,
                    element.y + 3,
                    RecordableConfig.applyOpacity(
                            live ? 0xFFFF3030 : 0xFF555555,
                            opacity));
            drawMicrophoneGlyph(element.x + 13, element.y + 2, text);
            fontRendererObj.drawStringWithShadow(
                    label, element.x + 23, element.y + 2, text);
        }
    }

    private void drawWatermarkPreview(Element element) {
        WatermarkSlot slot = element.watermark;
        if (slot.kind == WatermarkSlot.Kind.IMAGE) {
            int color = RecordableConfig.applyOpacity(
                    0xFF8AB8FF, slot.opacity);
            Gui.drawRect(
                    element.x + 1, element.y + 1,
                    element.x + element.w - 1,
                    element.y + element.h - 1,
                    RecordableConfig.applyOpacity(0x99406080, slot.opacity));
            drawBorder(
                    element.x + 2, element.y + 2,
                    element.x + element.w - 2,
                    element.y + element.h - 2,
                    color);
            String image = "IMG";
            fontRendererObj.drawString(
                    image,
                    element.x + Math.max(
                            1,
                            (element.w
                                    - fontRendererObj.getStringWidth(image)) / 2),
                    element.y + Math.max(
                            1,
                            (element.h - fontRendererObj.FONT_HEIGHT) / 2),
                    color);
            return;
        }

        String preview = watermarkPreviewText(slot);
        int color = parseWatermarkColor(slot);
        float localScale = clamp(
                slot.scale / 100.0F / overlayScale,
                0.05F,
                4.0F);
        GlStateManager.pushMatrix();
        try {
            GlStateManager.translate(element.x, element.y, 0.0F);
            GlStateManager.scale(localScale, localScale, 1.0F);
            fontRendererObj.drawString(
                    preview, 0, 0, color, slot.textShadow);
        } finally {
            GlStateManager.popMatrix();
        }
    }

    private void drawUnscaledText(
            String text, int virtualX, int virtualY, int color) {
        GlStateManager.pushMatrix();
        try {
            GlStateManager.translate(virtualX, virtualY, 0.0F);
            float inverse = 1.0F / overlayScale;
            GlStateManager.scale(inverse, inverse, 1.0F);
            fontRendererObj.drawString(
                    text, 10, 3, color, true);
        } finally {
            GlStateManager.popMatrix();
        }
    }

    private int officialPanelContentHeight() {
        int contentHeight = ROW_HEIGHT;
        if (layersOpen) {
            contentHeight += shownLayers().size() * ROW_HEIGHT;
        }
        contentHeight += 2;
        contentHeight += 2;
        contentHeight += ROW_HEIGHT;
        if (opacityOpen) {
            contentHeight += opacityEntries.size() * (ROW_HEIGHT - 1);
        }
        contentHeight += 2;
        contentHeight += ROW_HEIGHT;
        if (watermarksOpen) {
            List<WatermarkSlot> slots =
                    RecordableConfig.get().watermarkSlots;
            int rows = slots == null || slots.isEmpty()
                    ? 1
                    : slots.size();
            contentHeight += rows * ROW_HEIGHT;
            contentHeight += ROW_HEIGHT;
        }
        contentHeight += 4;
        return contentHeight;
    }

    private int officialPanelHeight() {
        int maximumHeight = Math.max(1, height - 68);
        return Math.min(
                maximumHeight,
                officialPanelContentHeight() + 4);
    }

    private void clampOfficialPanelScroll() {
        int maximum = Math.max(
                0,
                officialPanelContentHeight() + 4
                        - officialPanelHeight());
        panelScroll = clamp(panelScroll, 0, maximum);
    }

    private void drawPanel(int mouseX, int mouseY) {
        RecordableConfig config = RecordableConfig.get();
        int panelX = width - PANEL_WIDTH - 4;
        int panelHeight = officialPanelHeight();
        int panelBottom = PANEL_TOP + panelHeight;
        ThemeColors theme = ThemeEngine.get().colors();

        Gui.drawRect(
                panelX - 1,
                PANEL_TOP - 1,
                panelX + PANEL_WIDTH + 1,
                panelBottom + 1,
                theme.panelBorder);
        Gui.drawRect(
                panelX,
                PANEL_TOP,
                panelX + PANEL_WIDTH,
                panelBottom,
                theme.panelBackground);

        clampOfficialPanelScroll();
        int y = PANEL_TOP + 2 - panelScroll;
        int innerWidth = PANEL_WIDTH - 6;
        int left = panelX + 3;

        y = drawSectionHeader(
                layersOpen ? "\u25BE Layers" : "\u25B8 Layers",
                layersOpen,
                left,
                y,
                innerWidth,
                mouseX,
                mouseY,
                panelBottom);
        if (layersOpen) {
            List<String> shown = shownLayers();
            for (int index = 0; index < shown.size(); index++) {
                String id = shown.get(index);
                boolean visible = rowVisible(PanelRow.layer(id));
                if (y >= PANEL_TOP
                        && y + ROW_HEIGHT <= panelBottom) {
                    boolean elementHovered = hoveredElement != null
                            && hoveredElement.id.equals(id);
                    boolean rowHovered = mouseX >= left
                            && mouseX <= left + innerWidth
                            && mouseY >= y
                            && mouseY < y + ROW_HEIGHT;
                    if (elementHovered) {
                        Gui.drawRect(
                                left,
                                y,
                                left + innerWidth,
                                y + ROW_HEIGHT - 1,
                                0x22FFFF44);
                    } else if (rowHovered) {
                        Gui.drawRect(
                                left,
                                y,
                                left + innerWidth,
                                y + ROW_HEIGHT - 1,
                                0x12FFFFFF);
                    }

                    int eyeX = left + 1;
                    boolean eyeHovered = mouseX >= eyeX
                            && mouseX <= eyeX + 10
                            && mouseY >= y
                            && mouseY < y + ROW_HEIGHT;
                    fontRendererObj.drawStringWithShadow(
                            visible ? "\u25C9" : "\u25CE",
                            eyeX,
                            y + 3,
                            visible
                                    ? eyeHovered
                                            ? 0xFFAAFFAA
                                            : 0xFF66BB66
                                    : eyeHovered
                                            ? 0xFFFF8888
                                            : 0xFF884444);

                    String display = elementIcon(id) + " "
                            + fontRendererObj.trimStringToWidth(
                                    layerDisplayName(id),
                                    innerWidth - 42);
                    fontRendererObj.drawStringWithShadow(
                            display,
                            left + 13,
                            y + 3,
                            visible
                                    ? elementHovered
                                            ? 0xFFFFFF88
                                            : 0xFFCCCCCC
                                    : 0xFF666666);

                    int arrowX = left + innerWidth - 14;
                    if (index > 0) {
                        boolean hovered = mouseX >= arrowX
                                && mouseX <= arrowX + 10
                                && mouseY >= y
                                && mouseY <= y + 7;
                        fontRendererObj.drawStringWithShadow(
                                "\u25B2",
                                arrowX,
                                y,
                                hovered
                                        ? 0xFFFFFF44
                                        : 0xFF555555);
                    }
                    if (index < shown.size() - 1) {
                        boolean hovered = mouseX >= arrowX
                                && mouseX <= arrowX + 10
                                && mouseY >= y + 8
                                && mouseY <= y + ROW_HEIGHT;
                        fontRendererObj.drawStringWithShadow(
                                "\u25BC",
                                arrowX,
                                y + 8,
                                hovered
                                        ? 0xFFFFFF44
                                        : 0xFF555555);
                    }
                }
                y += ROW_HEIGHT;
            }
        }

        y = drawSectionHeader(
                opacityOpen ? "\u25BE Opacity" : "\u25B8 Opacity",
                opacityOpen,
                left,
                y,
                innerWidth,
                mouseX,
                mouseY,
                panelBottom);
        if (opacityOpen) {
            for (OpacityEntry entry : opacityEntries) {
                if (y >= PANEL_TOP
                        && y + ROW_HEIGHT - 2 <= panelBottom) {
                    drawOpacityRow(
                            entry,
                            left,
                            y,
                            innerWidth,
                            mouseX,
                            mouseY);
                }
                y += ROW_HEIGHT - 1;
            }
        }

        if (y >= PANEL_TOP && y + 2 <= panelBottom) {
            Gui.drawRect(
                    left + 4,
                    y,
                    left + innerWidth - 4,
                    y + 1,
                    0x33FFFFFF);
        }
        y += 2;

        y = drawSectionHeader(
                watermarksOpen
                        ? "\u25BE Watermarks"
                        : "\u25B8 Watermarks",
                watermarksOpen,
                left,
                y,
                innerWidth,
                mouseX,
                mouseY,
                panelBottom);
        if (watermarksOpen) {
            List<WatermarkSlot> slots = config.watermarkSlots;
            if (slots == null || slots.isEmpty()) {
                if (y >= PANEL_TOP
                        && y + ROW_HEIGHT <= panelBottom) {
                    fontRendererObj.drawStringWithShadow(
                            "  (none - add below)",
                            left + 2,
                            y + 3,
                            0xFF777777);
                }
                y += ROW_HEIGHT;
            } else {
                for (WatermarkSlot slot : slots) {
                    boolean enabled = slot != null && slot.enabled;
                    if (y >= PANEL_TOP
                            && y + ROW_HEIGHT <= panelBottom) {
                        boolean rowHovered = mouseX >= left
                                && mouseX <= left + innerWidth
                                && mouseY >= y
                                && mouseY < y + ROW_HEIGHT;
                        if (rowHovered) {
                            Gui.drawRect(
                                    left,
                                    y,
                                    left + innerWidth,
                                    y + ROW_HEIGHT - 1,
                                    0x12FFFFFF);
                        }
                        int eyeX = left + 1;
                        boolean eyeHovered = mouseX >= eyeX
                                && mouseX <= eyeX + 12
                                && mouseY >= y
                                && mouseY < y + ROW_HEIGHT;
                        fontRendererObj.drawStringWithShadow(
                                enabled ? "\u25C9" : "\u25CE",
                                eyeX,
                                y + 3,
                                enabled
                                        ? eyeHovered
                                                ? 0xFFAAFFAA
                                                : 0xFF66BB66
                                        : eyeHovered
                                                ? 0xFFFF8888
                                                : 0xFF884444);
                        String name = slot != null
                                && !isBlank(slot.name)
                                ? slot.name
                                : "Watermark";
                        String display = "\u25A4 "
                                + fontRendererObj.trimStringToWidth(
                                        name,
                                        innerWidth - 42);
                        fontRendererObj.drawStringWithShadow(
                                display,
                                left + 13,
                                y + 3,
                                enabled
                                        ? 0xFFCCCCCC
                                        : 0xFF777777);
                        int editX = left + innerWidth - 22;
                        boolean editHovered = mouseX >= editX
                                && mouseX <= editX + 20
                                && mouseY >= y
                                && mouseY < y + ROW_HEIGHT;
                        fontRendererObj.drawStringWithShadow(
                                "Edit",
                                editX,
                                y + 3,
                                editHovered
                                        ? 0xFFFFCC44
                                        : 0xFF8899BB);
                    }
                    y += ROW_HEIGHT;
                }
            }
            if (y >= PANEL_TOP
                    && y + ROW_HEIGHT <= panelBottom) {
                boolean hovered = mouseX >= left
                        && mouseX <= left + innerWidth
                        && mouseY >= y
                        && mouseY < y + ROW_HEIGHT;
                fontRendererObj.drawStringWithShadow(
                        "\u2795 Open Watermark Editor",
                        left + 4,
                        y + 3,
                        hovered ? 0xFF66CCFF : 0xFF6699CC);
            }
        }
    }

    private int drawSectionHeader(
            String label,
            boolean open,
            int x,
            int y,
            int sectionWidth,
            int mouseX,
            int mouseY,
            int panelBottom) {
        if (y >= PANEL_TOP && y + ROW_HEIGHT <= panelBottom) {
            boolean hovered = mouseX >= x
                    && mouseX <= x + sectionWidth
                    && mouseY >= y
                    && mouseY < y + ROW_HEIGHT;
            ThemeColors theme = ThemeEngine.get().colors();
            Gui.drawRect(
                    x,
                    y,
                    x + sectionWidth,
                    y + ROW_HEIGHT - 1,
                    hovered
                            ? theme.sectionHover
                            : theme.sectionBackground);
            Gui.drawRect(
                    x,
                    y + 2,
                    x + 2,
                    y + ROW_HEIGHT - 3,
                    theme.accent);
            fontRendererObj.drawStringWithShadow(
                    label,
                    x + 5,
                    y + 4,
                    theme.accent);
        }
        return y + ROW_HEIGHT;
    }

    private void drawOpacityRow(
            OpacityEntry entry,
            int x,
            int y,
            int rowWidth,
            int mouseX,
            int mouseY) {
        int value = clamp(entry.getter.get().intValue(), 0, 100);
        fontRendererObj.drawStringWithShadow(
                entry.label + " " + value + "%",
                x + 2,
                y + 1,
                0xFFAAAAAA);
        int barX = x + 2;
        int barY = y + 10;
        int barWidth = rowWidth - 4;
        Gui.drawRect(
                barX,
                barY,
                barX + barWidth,
                barY + 3,
                0xFF222222);
        int filled = (int) (barWidth * value / 100.0D);
        Gui.drawRect(
                barX,
                barY,
                barX + filled,
                barY + 3,
                ThemeEngine.get().colors().accent);
        if (mouseX >= barX
                && mouseX <= barX + barWidth
                && mouseY >= y
                && mouseY <= y + ROW_HEIGHT - 2) {
            Gui.drawRect(
                    barX + filled - 1,
                    barY - 1,
                    barX + filled + 2,
                    barY + 4,
                    0xFFFFFFFF);
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton)
            throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (mouseY >= height - BUTTON_HEIGHT - 6) {
            return;
        }

        if (panelOpen && mouseX >= width - PANEL_WIDTH - 10) {
            handlePanelClick(mouseX, mouseY, mouseButton);
            return;
        }

        int scaledX = (int) Math.floor(mouseX / (double) overlayScale);
        int scaledY = (int) Math.floor(mouseY / (double) overlayScale);
        List<Element> ordered = orderedElements();

        for (int index = ordered.size() - 1; index >= 0; index--) {
            Element element = ordered.get(index);
            ResizeEdge edge = hitTestResizeHandle(
                    element,
                    scaledX,
                    scaledY);
            if (edge != ResizeEdge.NONE && mouseButton == 0) {
                select(element);
                activeResize = edge;
                resizeElement = element;
                resizeOriginalX = element.x;
                resizeOriginalY = element.y;
                resizeOriginalWidth = element.w;
                resizeOriginalHeight = element.h;
                return;
            }
        }

        Element clicked = null;
        for (int index = ordered.size() - 1; index >= 0; index--) {
            Element element = ordered.get(index);
            if (!isElementVisible(element)) {
                continue;
            }
            if (element.contains(scaledX, scaledY)) {
                if ("Corners".equals(element.id)
                        && !nearCornerBracket(
                                element,
                                scaledX,
                                scaledY)) {
                    continue;
                }
                clicked = element;
                break;
            }
        }
        if (clicked == null) {
            return;
        }

        select(clicked);
        if (mouseButton == 1) {
            resetElement(clicked);
        } else if (mouseButton == 0 && isElementVisible(clicked)) {
            draggedElement = clicked;
            dragOffsetX = scaledX - clicked.x;
            dragOffsetY = scaledY - clicked.y;
        }
    }

    private void handlePanelClick(int mouseX, int mouseY, int mouseButton) {
        RecordableConfig config = RecordableConfig.get();
        int panelX = width - PANEL_WIDTH - 4;
        int innerWidth = PANEL_WIDTH - 6;
        int left = panelX + 3;
        int y = PANEL_TOP + 2 - panelScroll;

        if (mouseY >= y
                && mouseY < y + ROW_HEIGHT
                && mouseX >= left
                && mouseX <= left + innerWidth
                && mouseButton == 0) {
            layersOpen = !layersOpen;
            return;
        }
        y += ROW_HEIGHT;

        if (layersOpen) {
            List<String> shown = shownLayers();
            for (int index = 0; index < shown.size(); index++) {
                String id = shown.get(index);
                if (mouseY >= y
                        && mouseY < y + ROW_HEIGHT
                        && mouseButton == 0) {
                    int eyeX = left + 1;
                    if (mouseX >= eyeX && mouseX <= eyeX + 12) {
                        toggleLayer(id);
                        config.save();
                        return;
                    }
                    int arrowX = left + innerWidth - 14;
                    if (index > 0
                            && mouseX >= arrowX
                            && mouseX <= arrowX + 14
                            && mouseY < y + 8) {
                        moveShownLayer(shown, index, -1);
                        return;
                    }
                    if (index < shown.size() - 1
                            && mouseX >= arrowX
                            && mouseX <= arrowX + 14
                            && mouseY >= y + 8) {
                        moveShownLayer(shown, index, 1);
                        return;
                    }
                }
                y += ROW_HEIGHT;
            }
        }
        y += 2;

        if (mouseY >= y
                && mouseY < y + ROW_HEIGHT
                && mouseX >= left
                && mouseX <= left + innerWidth
                && mouseButton == 0) {
            opacityOpen = !opacityOpen;
            return;
        }
        y += ROW_HEIGHT;

        if (opacityOpen) {
            int barX = left + 2;
            int barWidth = innerWidth - 4;
            for (OpacityEntry entry : opacityEntries) {
                if (mouseY >= y
                        && mouseY < y + ROW_HEIGHT - 1) {
                    if (mouseButton == 0) {
                        int value = clamp(
                                (mouseX - barX) * 100
                                        / Math.max(1, barWidth),
                                0,
                                100);
                        entry.setter.accept(Integer.valueOf(value));
                        draggingOpacity = entry;
                        config.save();
                        return;
                    }
                    if (mouseButton == 1) {
                        entry.setter.accept(Integer.valueOf(100));
                        config.save();
                        return;
                    }
                }
                y += ROW_HEIGHT - 1;
            }
        }
        y += 2;

        if (mouseY >= y
                && mouseY < y + ROW_HEIGHT
                && mouseX >= left
                && mouseX <= left + innerWidth
                && mouseButton == 0) {
            watermarksOpen = !watermarksOpen;
            return;
        }
        y += ROW_HEIGHT;

        if (watermarksOpen) {
            List<WatermarkSlot> slots = config.watermarkSlots;
            if (slots == null || slots.isEmpty()) {
                y += ROW_HEIGHT;
            } else {
                for (WatermarkSlot slot : slots) {
                    if (mouseY >= y
                            && mouseY < y + ROW_HEIGHT
                            && mouseButton == 0) {
                        int editX = left + innerWidth - 22;
                        if (mouseX >= editX
                                && mouseX <= editX + 20) {
                            openWatermarkEditor();
                            return;
                        }
                        if (slot != null) {
                            slot.enabled = !slot.enabled;
                            config.save();
                        }
                        return;
                    }
                    y += ROW_HEIGHT;
                }
            }
            if (mouseY >= y
                    && mouseY < y + ROW_HEIGHT
                    && mouseX >= left
                    && mouseX <= left + innerWidth
                    && mouseButton == 0) {
                openWatermarkEditor();
            }
        }
    }

    @Override
    protected void mouseClickMove(
            int mouseX,
            int mouseY,
            int clickedMouseButton,
            long timeSinceLastClick) {
        if (clickedMouseButton != 0) {
            return;
        }
        if (draggingOpacity != null) {
            int panelX = width - PANEL_WIDTH - 4;
            int barX = panelX + 5;
            int barWidth = PANEL_WIDTH - 10;
            int value = clamp(
                    (mouseX - barX) * 100
                            / Math.max(1, barWidth),
                    0,
                    100);
            draggingOpacity.setter.accept(Integer.valueOf(value));
            return;
        }

        int scaledX = (int) Math.floor(mouseX / (double) overlayScale);
        int scaledY = (int) Math.floor(mouseY / (double) overlayScale);
        if (activeResize != ResizeEdge.NONE && resizeElement != null) {
            applyResize(resizeElement, scaledX, scaledY);
            return;
        }

        if (draggedElement != null) {
            int newX = clamp(
                    scaledX - dragOffsetX,
                    0,
                    Math.max(0, virtualWidth - draggedElement.w));
            int newY = clamp(
                    scaledY - dragOffsetY,
                    0,
                    Math.max(0, virtualHeight - draggedElement.h));
            applyPosition(draggedElement, newX, newY);
            draggedElement.x = newX;
            draggedElement.y = newY;
        }
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        super.mouseReleased(mouseX, mouseY, state);
        draggingOpacity = null;
        if (state == 0) {
            if (activeResize != ResizeEdge.NONE) {
                activeResize = ResizeEdge.NONE;
                resizeElement = null;
                RecordableConfig.get().save();
            }
            if (draggedElement != null) {
                draggedElement = null;
                RecordableConfig.get().save();
            }
        }
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel == 0) {
            return;
        }
        int mouseX = Mouse.getEventX() * width
                / Math.max(1, mc.displayWidth);
        if (!panelOpen || mouseX < width - PANEL_WIDTH - 10) {
            return;
        }
        panelScroll += wheel > 0 ? -8 : 8;
        clampOfficialPanelScroll();
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode)
            throws IOException {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            cancelAndClose();
            return;
        }
        if (keyCode == Keyboard.KEY_R) {
            resetSelected();
            return;
        }
        if (keyCode == Keyboard.KEY_V) {
            toggleSelected();
            return;
        }
        if (keyCode == Keyboard.KEY_PRIOR) {
            moveSelected(1);
            return;
        }
        if (keyCode == Keyboard.KEY_NEXT) {
            moveSelected(-1);
            return;
        }
        if (keyCode == Keyboard.KEY_LEFT
                || keyCode == Keyboard.KEY_RIGHT
                || keyCode == Keyboard.KEY_UP
                || keyCode == Keyboard.KEY_DOWN) {
            nudgeSelected(keyCode);
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    private void nudgeSelected(int keyCode) {
        Element element = selectedElement();
        if (element == null) {
            return;
        }
        int amount = Keyboard.isKeyDown(Keyboard.KEY_LSHIFT)
                || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT)
                ? 10
                : 1;
        int x = element.x;
        int y = element.y;
        if (keyCode == Keyboard.KEY_LEFT) x -= amount;
        if (keyCode == Keyboard.KEY_RIGHT) x += amount;
        if (keyCode == Keyboard.KEY_UP) y -= amount;
        if (keyCode == Keyboard.KEY_DOWN) y += amount;
        x = clamp(x, 0, Math.max(0, virtualWidth - element.w));
        y = clamp(y, 0, Math.max(0, virtualHeight - element.h));
        applyPosition(element, x, y);
    }

    private void applyPosition(Element element, int x, int y) {
        RecordableConfig config = RecordableConfig.get();
        if (element.watermark != null) {
            element.watermark.position = WatermarkSlot.Position.CUSTOM;
            element.watermark.customX = toScreen(x);
            element.watermark.customY = toScreen(y);
        } else if ("PLAY/REC".equals(element.id)) {
            config.hudPlayRecX = x;
            config.hudPlayRecY = y;
        } else if ("Timestamp".equals(element.id)) {
            config.hudTimestampOffsetX =
                    Math.max(0, virtualWidth - x - element.w);
            config.hudTimestampY = y;
        } else if ("Corners".equals(element.id)) {
            config.hudCornersX = x;
            config.hudCornersY = y;
        } else if ("SP".equals(element.id)) {
            config.hudSpX = x;
            config.hudSpOffsetY = Math.max(0, virtualHeight - y);
        } else if ("Details".equals(element.id)) {
            config.hudDetailsOffsetX =
                    Math.max(0, virtualWidth - x - element.w);
            config.hudDetailsOffsetY =
                    Math.max(0, virtualHeight - y - element.h);
        } else if ("Perf".equals(element.id)) {
            config.hudPerfOffsetX =
                    Math.max(0, virtualWidth - x - element.w);
            config.hudPerfOffsetY =
                    Math.max(0, virtualHeight - y - element.h);
        } else if ("Mic".equals(element.id)) {
            config.hudMicX = toScreen(x);
            config.hudMicY = toScreen(y);
        } else if ("Classic".equals(element.id)) {
            config.hudClassicX = x;
            config.hudClassicY = y;
        } else if ("Synthwave".equals(element.id)) {
            config.hudSynthX = x;
            config.hudSynthY = y;
        }
    }

    private void applyResize(Element element, int mouseX, int mouseY) {
        int newX = resizeOriginalX;
        int newY = resizeOriginalY;
        int newWidth = resizeOriginalWidth;
        int newHeight = resizeOriginalHeight;

        if (activeResize == ResizeEdge.TOP_LEFT) {
            int deltaX = mouseX - resizeOriginalX;
            int deltaY = mouseY - resizeOriginalY;
            newX += deltaX;
            newY += deltaY;
            newWidth -= deltaX;
            newHeight -= deltaY;
        } else if (activeResize == ResizeEdge.TOP_RIGHT) {
            int deltaY = mouseY - resizeOriginalY;
            newY += deltaY;
            newWidth = mouseX - resizeOriginalX;
            newHeight -= deltaY;
        } else if (activeResize == ResizeEdge.BOTTOM_LEFT) {
            int deltaX = mouseX - resizeOriginalX;
            newX += deltaX;
            newWidth -= deltaX;
            newHeight = mouseY - resizeOriginalY;
        } else if (activeResize == ResizeEdge.BOTTOM_RIGHT) {
            newWidth = mouseX - resizeOriginalX;
            newHeight = mouseY - resizeOriginalY;
        } else {
            return;
        }

        int minimumWidth = 20;
        int minimumHeight = 10;
        if (newWidth < minimumWidth) {
            newWidth = minimumWidth;
            newX = resizeOriginalX + resizeOriginalWidth
                    - minimumWidth;
        }
        if (newHeight < minimumHeight) {
            newHeight = minimumHeight;
            newY = resizeOriginalY + resizeOriginalHeight
                    - minimumHeight;
        }
        newX = Math.max(0, newX);
        newY = Math.max(0, newY);
        newWidth = Math.max(
                minimumWidth,
                Math.min(virtualWidth - newX, newWidth));
        newHeight = Math.max(
                minimumHeight,
                Math.min(virtualHeight - newY, newHeight));

        RecordableConfig config = RecordableConfig.get();
        if ("Corners".equals(element.id)) {
            config.hudCornersX = newX;
            config.hudCornersY = newY;
            config.hudCornersWidth = newWidth;
            config.hudCornersHeight = newHeight;
        } else if ("PLAY/REC".equals(element.id)) {
            config.hudPlayRecX = newX;
            config.hudPlayRecY = newY;
            config.hudPlayRecW = newWidth;
            config.hudPlayRecH = newHeight;
        } else if ("Timestamp".equals(element.id)) {
            config.hudTimestampOffsetX =
                    virtualWidth - newX - newWidth;
            config.hudTimestampY = newY;
            config.hudTimestampW = newWidth;
            config.hudTimestampH = newHeight;
        } else if ("SP".equals(element.id)) {
            config.hudSpX = newX;
            config.hudSpOffsetY = virtualHeight - newY;
            config.hudSpW = newWidth;
            config.hudSpH = newHeight;
        } else if ("Details".equals(element.id)) {
            config.hudDetailsOffsetX =
                    virtualWidth - newX - newWidth;
            config.hudDetailsOffsetY =
                    virtualHeight - newY - newHeight;
            config.hudDetailsW = newWidth;
            config.hudDetailsH = newHeight;
        } else if ("Perf".equals(element.id)) {
            config.hudPerfOffsetX =
                    virtualWidth - newX - newWidth;
            config.hudPerfOffsetY =
                    virtualHeight - newY - newHeight;
            config.hudPerfW = newWidth;
            config.hudPerfH = newHeight;
        }

        element.x = newX;
        element.y = newY;
        element.w = newWidth;
        element.h = newHeight;
    }

    private void setOpacityFromMouse(int mouseX) {
        int left = panelLeft() + 7;
        int right = width - 7;
        int opacity = clamp(
                (mouseX - left) * 100 / Math.max(1, right - left),
                0,
                100);
        setSelectedOpacity(opacity);
    }

    private Integer selectedOpacity() {
        RecordableConfig config = RecordableConfig.get();
        if (selectedWatermark != null) {
            return Integer.valueOf(selectedWatermark.opacity);
        }
        if ("PLAY/REC".equals(selectedLayerId)) {
            return Integer.valueOf(config.hudPlayRecOpacity);
        }
        if ("Timestamp".equals(selectedLayerId)) {
            return Integer.valueOf(config.hudTimestampOpacity);
        }
        if ("Corners".equals(selectedLayerId)) {
            return Integer.valueOf(config.hudCornersOpacity);
        }
        if ("SP".equals(selectedLayerId)) {
            return Integer.valueOf(config.hudSpOpacity);
        }
        if ("Details".equals(selectedLayerId)) {
            return Integer.valueOf(config.hudDetailsOpacity);
        }
        if ("Perf".equals(selectedLayerId)) {
            return Integer.valueOf(config.hudPerfOpacity);
        }
        if ("Mic".equals(selectedLayerId)) {
            return Integer.valueOf(config.hudMicOpacity);
        }
        if ("Filter:VHS".equals(selectedLayerId)) {
            return Integer.valueOf(config.filterVhsIntensity);
        }
        if ("Filter:LCD_MOIRE".equals(selectedLayerId)) {
            return Integer.valueOf(config.filterLcdMoireIntensity);
        }
        if ("Filter:CRT".equals(selectedLayerId)) {
            return Integer.valueOf(config.filterCrtIntensity);
        }
        return null;
    }

    private void setSelectedOpacity(int opacity) {
        RecordableConfig config = RecordableConfig.get();
        int value = clamp(opacity, 0, 100);
        if (selectedWatermark != null) {
            selectedWatermark.opacity = value;
        } else if ("PLAY/REC".equals(selectedLayerId)) {
            config.hudPlayRecOpacity = value;
        } else if ("Timestamp".equals(selectedLayerId)) {
            config.hudTimestampOpacity = value;
        } else if ("Corners".equals(selectedLayerId)) {
            config.hudCornersOpacity = value;
        } else if ("SP".equals(selectedLayerId)) {
            config.hudSpOpacity = value;
        } else if ("Details".equals(selectedLayerId)) {
            config.hudDetailsOpacity = value;
        } else if ("Perf".equals(selectedLayerId)) {
            config.hudPerfOpacity = value;
        } else if ("Mic".equals(selectedLayerId)) {
            config.hudMicOpacity = value;
        } else if ("Filter:VHS".equals(selectedLayerId)) {
            config.filterVhsIntensity = value;
        } else if ("Filter:LCD_MOIRE".equals(selectedLayerId)) {
            config.filterLcdMoireIntensity = value;
        } else if ("Filter:CRT".equals(selectedLayerId)) {
            config.filterCrtIntensity = value;
        }
    }

    private void toggleSelected() {
        if (selectedWatermark != null) {
            selectedWatermark.enabled = !selectedWatermark.enabled;
        } else if (selectedLayerId != null) {
            toggleLayer(selectedLayerId);
        }
    }

    private void toggleRow(PanelRow row) {
        RecordableConfig config = RecordableConfig.get();
        if (row.type == RowType.LAYER) {
            toggleLayer(row.layerId);
        } else if (row.type == RowType.WATERMARK_MASTER) {
            config.watermarksEnabled = !config.watermarksEnabled;
        } else if (row.watermark != null) {
            row.watermark.enabled = !row.watermark.enabled;
        }
    }

    private void toggleLayer(String id) {
        RecordableConfig config = RecordableConfig.get();
        if ("Filter:VHS".equals(id)) {
            config.filterVhsVisible = !config.filterVhsVisible;
        } else if ("Filter:LCD_MOIRE".equals(id)) {
            config.filterLcdMoireVisible = !config.filterLcdMoireVisible;
        } else if ("Filter:CRT".equals(id)) {
            config.filterCrtVisible = !config.filterCrtVisible;
        } else {
            config.setElementVisible(id, !config.isElementVisible(id));
        }
    }

    private void moveSelected(int direction) {
        if (selectedWatermark != null) {
            moveWatermark(selectedWatermark, direction);
        } else if (selectedLayerId != null) {
            moveLayer(selectedLayerId, direction);
        }
    }

    private void moveRow(PanelRow row, int direction) {
        if (row.type == RowType.LAYER) {
            moveLayer(row.layerId, direction);
        } else if (row.type == RowType.WATERMARK && row.watermark != null) {
            moveWatermark(row.watermark, direction);
        }
    }

    private void moveLayer(String id, int direction) {
        int index = layerOrder.indexOf(id);
        int target = index + direction;
        if (index < 0 || target < 0 || target >= layerOrder.size()) {
            return;
        }
        Collections.swap(layerOrder, index, target);
        RecordableConfig.get().hudLayerOrder = join(layerOrder);
    }

    private void moveWatermark(WatermarkSlot slot, int direction) {
        List<WatermarkSlot> slots = RecordableConfig.get().watermarkSlots;
        int index = slots.indexOf(slot);
        int target = index + direction;
        if (index < 0 || target < 0 || target >= slots.size()) {
            return;
        }
        Collections.swap(slots, index, target);
    }

    private boolean canMove(PanelRow row, int direction) {
        if (row.type == RowType.LAYER) {
            int index = layerOrder.indexOf(row.layerId);
            int target = index + direction;
            return index >= 0 && target >= 0 && target < layerOrder.size();
        }
        if (row.type == RowType.WATERMARK && row.watermark != null) {
            List<WatermarkSlot> slots = RecordableConfig.get().watermarkSlots;
            int index = slots.indexOf(row.watermark);
            int target = index + direction;
            return index >= 0 && target >= 0 && target < slots.size();
        }
        return false;
    }

    private void resetSelected() {
        Element element = selectedElement();
        if (element != null) {
            resetElement(element);
            return;
        }
        if (selectedLayerId != null) {
            resetLayer(selectedLayerId);
        }
    }

    private void resetRow(PanelRow row) {
        if (row.type == RowType.LAYER) {
            resetLayer(row.layerId);
        } else if (row.type == RowType.WATERMARK && row.watermark != null) {
            resetWatermark(row.watermark);
        }
    }

    private void resetElement(Element element) {
        if (element.watermark != null) {
            resetWatermark(element.watermark);
        } else {
            resetLayer(element.id);
        }
    }

    private void resetLayer(String id) {
        RecordableConfig config = RecordableConfig.get();
        if ("PLAY/REC".equals(id)) {
            config.hudPlayRecX = 80;
            config.hudPlayRecY = 14;
            config.hudPlayRecW = 0;
            config.hudPlayRecH = 0;
        } else if ("Timestamp".equals(id)) {
            config.hudTimestampOffsetX = 14;
            config.hudTimestampY = 14;
            config.hudTimestampW = 0;
            config.hudTimestampH = 0;
        } else if ("Corners".equals(id)) {
            config.hudCornersX = 68;
            config.hudCornersY = 4;
            config.hudCornersWidth = 100;
            config.hudCornersHeight = 48;
        } else if ("SP".equals(id)) {
            config.hudSpX = 80;
            config.hudSpOffsetY = 24;
            config.hudSpW = 0;
            config.hudSpH = 0;
        } else if ("Details".equals(id)) {
            config.hudDetailsOffsetX = 14;
            config.hudDetailsOffsetY = 14;
            config.hudDetailsW = 0;
            config.hudDetailsH = 0;
        } else if ("Perf".equals(id)) {
            config.hudPerfOffsetX = 8;
            config.hudPerfOffsetY = 80;
            config.hudPerfW = 0;
            config.hudPerfH = 0;
        } else if ("Mic".equals(id)) {
            config.hudMicX = -1;
            config.hudMicY = 4;
            config.hudMicOpacity = 100;
        } else if ("Classic".equals(id)) {
            config.hudClassicX = -1;
            config.hudClassicY = -1;
        } else if ("Synthwave".equals(id)) {
            config.hudSynthX = -1;
            config.hudSynthY = -1;
        } else if ("Filter:VHS".equals(id)) {
            config.filterVhsIntensity = 75;
        } else if ("Filter:LCD_MOIRE".equals(id)) {
            config.filterLcdMoireIntensity = 75;
        } else if ("Filter:CRT".equals(id)) {
            config.filterCrtIntensity = 75;
        }
    }

    private static void resetWatermark(WatermarkSlot slot) {
        slot.position = WatermarkSlot.Position.BOTTOM_RIGHT;
        slot.customX = 0;
        slot.customY = 0;
        slot.opacity = 80;
    }

    private void resetAll() {
        RecordableConfig config = RecordableConfig.get();
        String[] ids = RecordableConfig.defaultLayerOrder().split(",");
        config.hudPlayRecX = 80;
        config.hudPlayRecY = 14;
        config.hudTimestampOffsetX = 14;
        config.hudTimestampY = 14;
        config.hudSpX = 80;
        config.hudSpOffsetY = 24;
        config.hudPerfOffsetX = 8;
        config.hudPerfOffsetY = 80;
        config.hudDetailsOffsetX = 14;
        config.hudDetailsOffsetY = 14;
        config.hudCornersX = 68;
        config.hudCornersY = 4;
        config.hudCornersWidth = 100;
        config.hudCornersHeight = 48;
        config.hudPlayRecW = 0;
        config.hudPlayRecH = 0;
        config.hudTimestampW = 0;
        config.hudTimestampH = 0;
        config.hudSpW = 0;
        config.hudSpH = 0;
        config.hudPerfW = 0;
        config.hudPerfH = 0;
        config.hudDetailsW = 0;
        config.hudDetailsH = 0;
        config.hudPlayRecOpacity = 100;
        config.hudTimestampOpacity = 100;
        config.hudCornersOpacity = 100;
        config.hudSpOpacity = 100;
        config.hudDetailsOpacity = 100;
        config.hudPerfOpacity = 100;
        config.hudMicOpacity = 100;
        config.hudPlayRecVisible = true;
        config.hudTimestampVisible = true;
        config.hudCornersVisible = true;
        config.hudSpVisible = true;
        config.hudDetailsVisible = true;
        config.hudPerfVisible = true;
        config.hudMicX = -1;
        config.hudMicY = 4;
        config.hudMicVisible = true;
        layerOrder.clear();
        for (String id : ids) {
            String cleaned = id.trim();
            if (!cleaned.isEmpty()) {
                layerOrder.add(cleaned);
            }
        }
        config.hudLayerOrder = join(layerOrder);
        config.save();
    }

    private List<PanelRow> panelRows() {
        List<PanelRow> rows = new ArrayList<PanelRow>();
        for (String layer : layerOrder) {
            rows.add(PanelRow.layer(layer));
        }
        rows.add(PanelRow.watermarkMaster());
        List<WatermarkSlot> slots = RecordableConfig.get().watermarkSlots;
        if (slots != null) {
            for (WatermarkSlot slot : slots) {
                if (slot != null) {
                    rows.add(PanelRow.watermark(slot));
                }
            }
        }
        return rows;
    }

    private boolean rowVisible(PanelRow row) {
        RecordableConfig config = RecordableConfig.get();
        if (row.type == RowType.WATERMARK_MASTER) {
            return config.watermarksEnabled;
        }
        if (row.type == RowType.WATERMARK) {
            return row.watermark != null && row.watermark.enabled;
        }
        if ("Filter:VHS".equals(row.layerId)) {
            return config.filterVhsVisible;
        }
        if ("Filter:LCD_MOIRE".equals(row.layerId)) {
            return config.filterLcdMoireVisible;
        }
        if ("Filter:CRT".equals(row.layerId)) {
            return config.filterCrtVisible;
        }
        return config.isElementVisible(row.layerId);
    }

    private boolean rowApplicable(PanelRow row) {
        if (row.type != RowType.LAYER) {
            return true;
        }
        String id = row.layerId;
        if (id.startsWith("Filter:") || "Mic".equals(id)) {
            return true;
        }
        RecordableConfig.OverlayStyleHud style =
                RecordableConfig.get().overlayStyleHud;
        if (style == RecordableConfig.OverlayStyleHud.VHS) {
            return "Corners".equals(id)
                    || "PLAY/REC".equals(id)
                    || "Timestamp".equals(id)
                    || "SP".equals(id)
                    || "Details".equals(id)
                    || "Perf".equals(id);
        }
        if (style == RecordableConfig.OverlayStyleHud.SYNTHWAVE) {
            return "Synthwave".equals(id);
        }
        if (style == RecordableConfig.OverlayStyleHud.CLASSIC
                || style == null) {
            return "Classic".equals(id);
        }
        return false;
    }

    private String rowLabel(PanelRow row) {
        if (row.type == RowType.WATERMARK_MASTER) {
            return "Watermarks (master)";
        }
        if (row.type == RowType.WATERMARK) {
            String name = row.watermark == null
                    ? "Watermark"
                    : row.watermark.name;
            return "  W: " + (name == null ? "Watermark" : name);
        }
        if ("Filter:VHS".equals(row.layerId)) return "VHS filter";
        if ("Filter:LCD_MOIRE".equals(row.layerId)) return "LCD moire filter";
        if ("Filter:CRT".equals(row.layerId)) return "CRT filter";
        if ("PLAY/REC".equals(row.layerId)) return "PLAY / REC";
        return row.layerId;
    }

    private void select(Element element) {
        if (element.watermark != null) {
            selectedWatermark = element.watermark;
            selectedLayerId = null;
        } else {
            selectedLayerId = element.id;
            selectedWatermark = null;
        }
    }

    private void select(PanelRow row) {
        if (row.type == RowType.WATERMARK) {
            selectedWatermark = row.watermark;
            selectedLayerId = null;
        } else if (row.type == RowType.LAYER) {
            selectedLayerId = row.layerId;
            selectedWatermark = null;
        } else {
            selectedLayerId = null;
            selectedWatermark = null;
        }
    }

    private boolean isSelected(Element element) {
        return element.watermark != null
                ? element.watermark == selectedWatermark
                : element.id.equals(selectedLayerId);
    }

    private boolean isSelected(PanelRow row) {
        return row.type == RowType.WATERMARK
                ? row.watermark == selectedWatermark
                : row.type == RowType.LAYER
                    && row.layerId.equals(selectedLayerId);
    }

    private Element selectedElement() {
        if (selectedWatermark != null) {
            return findWatermarkElement(selectedWatermark);
        }
        return selectedLayerId == null
                ? null
                : findLayerElement(selectedLayerId);
    }

    private String selectedName() {
        if (selectedWatermark != null) {
            return selectedWatermark.name == null
                    ? "Watermark"
                    : selectedWatermark.name;
        }
        if (selectedLayerId == null) {
            return null;
        }
        return rowLabel(PanelRow.layer(selectedLayerId));
    }

    private boolean isElementVisible(Element element) {
        RecordableConfig config = RecordableConfig.get();
        if (element.watermark != null) {
            return element.watermark.enabled;
        }
        return config.isElementVisible(element.id);
    }

    private Element findLayerElement(String id) {
        for (Element element : elements) {
            if (element.watermark == null && element.id.equals(id)) {
                return element;
            }
        }
        return null;
    }

    private Element findWatermarkElement(WatermarkSlot slot) {
        for (Element element : elements) {
            if (element.watermark == slot) {
                return element;
            }
        }
        return null;
    }

    private static boolean sameElement(Element first, Element second) {
        if (first == null || second == null) {
            return false;
        }
        if (first.watermark != null || second.watermark != null) {
            return first.watermark != null
                    && first.watermark == second.watermark;
        }
        return first.id.equals(second.id);
    }

    private void readLayerOrder(RecordableConfig config) {
        layerOrder.clear();
        String defaults = RecordableConfig.defaultLayerOrder();
        List<String> allowed = new ArrayList<String>();
        for (String part : defaults.split(",")) {
            String id = part.trim();
            if (!id.isEmpty() && !allowed.contains(id)) {
                allowed.add(id);
            }
        }
        if (config.hudLayerOrder != null) {
            for (String part : config.hudLayerOrder.split(",")) {
                String id = part.trim();
                if (allowed.contains(id) && !layerOrder.contains(id)) {
                    layerOrder.add(id);
                }
            }
        }
        for (String id : allowed) {
            if (!layerOrder.contains(id)) {
                layerOrder.add(id);
            }
        }
        config.hudLayerOrder = join(layerOrder);
    }

    private int[] resolvePanelPosition(
            RecordableConfig config,
            int panelWidth,
            int panelHeight,
            int customX,
            int customY,
            int margin) {
        int x = margin;
        int y = margin;
        RecordableConfig.OverlayPosition position = config.overlayPosition;
        if (position == RecordableConfig.OverlayPosition.TOP_RIGHT) {
            x = virtualWidth - panelWidth - margin;
        } else if (position == RecordableConfig.OverlayPosition.BOTTOM_LEFT) {
            y = virtualHeight - panelHeight - margin;
        } else if (position
                == RecordableConfig.OverlayPosition.BOTTOM_RIGHT) {
            x = virtualWidth - panelWidth - margin;
            y = virtualHeight - panelHeight - margin;
        } else if (position
                == RecordableConfig.OverlayPosition.CENTER_TOP) {
            x = (virtualWidth - panelWidth) / 2;
        }
        if (customX >= 0) x = customX;
        if (customY >= 0) y = customY;
        return new int[]{
                clamp(x, 0, Math.max(0, virtualWidth - panelWidth)),
                clamp(y, 0, Math.max(0, virtualHeight - panelHeight))
        };
    }

    private int[] watermarkPosition(
            WatermarkSlot slot, int itemWidth, int itemHeight) {
        int padding = Math.max(0, slot.padding);
        int x;
        int y;
        WatermarkSlot.Position position = slot.position;
        if (position == null) {
            position = WatermarkSlot.Position.BOTTOM_RIGHT;
        }
        switch (position) {
            case CUSTOM:
                x = slot.customX;
                y = slot.customY;
                break;
            case TOP_LEFT:
                x = padding;
                y = padding;
                break;
            case TOP_CENTER:
                x = (width - itemWidth) / 2;
                y = padding;
                break;
            case TOP_RIGHT:
                x = width - itemWidth - padding;
                y = padding;
                break;
            case MIDDLE_LEFT:
                x = padding;
                y = (height - itemHeight) / 2;
                break;
            case CENTER:
                x = (width - itemWidth) / 2;
                y = (height - itemHeight) / 2;
                break;
            case MIDDLE_RIGHT:
                x = width - itemWidth - padding;
                y = (height - itemHeight) / 2;
                break;
            case BOTTOM_LEFT:
                x = padding;
                y = height - itemHeight - padding;
                break;
            case BOTTOM_CENTER:
                x = (width - itemWidth) / 2;
                y = height - itemHeight - padding;
                break;
            case BOTTOM_RIGHT:
            default:
                x = width - itemWidth - padding;
                y = height - itemHeight - padding;
                break;
        }
        return new int[]{
                clamp(x, 0, Math.max(0, width - itemWidth)),
                clamp(y, 0, Math.max(0, height - itemHeight))
        };
    }

    private String watermarkPreviewText(WatermarkSlot slot) {
        if (slot.kind == WatermarkSlot.Kind.IMAGE) {
            return "IMG";
        }
        String username = mc != null && mc.thePlayer != null
                ? mc.thePlayer.getName()
                : "Player";
        String text = slot.resolveText(username);
        if (text == null || text.isEmpty()) {
            text = slot.name == null ? "Watermark" : slot.name;
        }
        return trim(text, Math.max(40, width / 2));
    }

    private int parseWatermarkColor(WatermarkSlot slot) {
        String value = slot.textColor;
        if (slot.effectiveColors() != null
                && !slot.effectiveColors().isEmpty()) {
            value = slot.effectiveColors().get(0);
        }
        int color = RecordableConfig.parseArgbColor(value, 0xFFFFFFFF);
        return RecordableConfig.applyOpacity(color, slot.opacity);
    }

    private void drawRight(String text, int right, int y, int color) {
        fontRendererObj.drawString(
                text,
                right - fontRendererObj.getStringWidth(text),
                y,
                color);
    }

    private static void drawBorder(
            int left, int top, int right, int bottom, int color) {
        Gui.drawRect(left, top, right, top + 1, color);
        Gui.drawRect(left, bottom - 1, right, bottom, color);
        Gui.drawRect(left, top, left + 1, bottom, color);
        Gui.drawRect(right - 1, top, right, bottom, color);
    }

    private static void drawCornerBrackets(
            int left,
            int top,
            int right,
            int bottom,
            int length,
            int thickness,
            int color) {
        int safeLength = Math.max(2, length);
        Gui.drawRect(left, top, left + safeLength, top + thickness, color);
        Gui.drawRect(left, top, left + thickness, top + safeLength, color);
        Gui.drawRect(
                right - safeLength, top, right, top + thickness, color);
        Gui.drawRect(
                right - thickness, top, right, top + safeLength, color);
        Gui.drawRect(
                left, bottom - thickness, left + safeLength, bottom, color);
        Gui.drawRect(
                left, bottom - safeLength, left + thickness, bottom, color);
        Gui.drawRect(
                right - safeLength, bottom - thickness, right, bottom, color);
        Gui.drawRect(
                right - thickness, bottom - safeLength, right, bottom, color);
    }

    private int panelLeft() {
        return width - PANEL_WIDTH - 4;
    }

    private int toVirtual(int screenCoordinate) {
        return (int) Math.floor(screenCoordinate / (double) overlayScale);
    }

    private int toVirtualCeil(int screenCoordinate) {
        return (int) Math.ceil(screenCoordinate / (double) overlayScale);
    }

    private int toScreen(int virtualCoordinate) {
        return Math.round(virtualCoordinate * overlayScale);
    }

    private String trim(String value, int maximumPixels) {
        String safe = value == null ? "" : value;
        return fontRendererObj.trimStringToWidth(
                safe,
                Math.max(4, maximumPixels));
    }

    private static String join(List<String> values) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (builder.length() > 0) {
                builder.append(',');
            }
            builder.append(value);
        }
        return builder.toString();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static float clamp(
            float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private void saveAndClose() {
        RecordableConfig config = RecordableConfig.get();
        config.hudLayerOrder = join(layerOrder);
        config.save();
        resolved = true;
        mc.displayGuiScreen(parent);
    }

    private void cancelAndClose() {
        if (!resolved && original != null) {
            original.restore(RecordableConfig.get());
            RecordableConfig.get().save();
        }
        resolved = true;
        mc.displayGuiScreen(parent);
    }

    @Override
    public void onGuiClosed() {
        draggingOpacity = null;
        draggedElement = null;
        resizeElement = null;
        activeResize = ResizeEdge.NONE;
        if (openingWatermarkEditor) {
            openingWatermarkEditor = false;
            return;
        }
        if (!resolved && original != null) {
            original.restore(RecordableConfig.get());
            RecordableConfig.get().save();
            resolved = true;
        }
    }

    private enum ResizeEdge {
        NONE,
        TOP_LEFT,
        TOP_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_RIGHT
    }

    private static final class OpacityEntry {
        private final String id;
        private final String label;
        private final Supplier<Integer> getter;
        private final Consumer<Integer> setter;

        private OpacityEntry(
                String id,
                String label,
                Supplier<Integer> getter,
                Consumer<Integer> setter) {
            this.id = id;
            this.label = label;
            this.getter = getter;
            this.setter = setter;
        }
    }

    private static final class Element {
        private final String id;
        private final String label;
        private int x;
        private int y;
        private int w;
        private int h;
        private boolean resizable;
        private WatermarkSlot watermark;

        private Element(
                String id, String label, int x, int y, int w, int h) {
            this.id = id;
            this.label = label;
            this.x = x;
            this.y = y;
            this.w = Math.max(1, w);
            this.h = Math.max(1, h);
        }

        private boolean contains(int mouseX, int mouseY) {
            return mouseX >= x
                    && mouseX <= x + w
                    && mouseY >= y
                    && mouseY <= y + h;
        }
    }

    private enum RowType {
        LAYER,
        WATERMARK_MASTER,
        WATERMARK
    }

    private static final class PanelRow {
        private final RowType type;
        private final String layerId;
        private final WatermarkSlot watermark;

        private PanelRow(
                RowType type, String layerId, WatermarkSlot watermark) {
            this.type = type;
            this.layerId = layerId;
            this.watermark = watermark;
        }

        private static PanelRow layer(String id) {
            return new PanelRow(RowType.LAYER, id, null);
        }

        private static PanelRow watermarkMaster() {
            return new PanelRow(RowType.WATERMARK_MASTER, null, null);
        }

        private static PanelRow watermark(WatermarkSlot slot) {
            return new PanelRow(RowType.WATERMARK, null, slot);
        }
    }

    /**
     * Snapshot of every field this screen can mutate. It intentionally keeps
     * watermark objects as deep copies so Cancel also reverses slot reordering.
     */
    private static final class Snapshot {
        private final int hudPlayRecX;
        private final int hudPlayRecY;
        private final int hudTimestampOffsetX;
        private final int hudTimestampY;
        private final int hudSpX;
        private final int hudSpOffsetY;
        private final int hudPerfOffsetX;
        private final int hudPerfOffsetY;
        private final int hudDetailsOffsetX;
        private final int hudDetailsOffsetY;
        private final int hudCornersX;
        private final int hudCornersY;
        private final int hudCornersWidth;
        private final int hudCornersHeight;
        private final int hudPlayRecW;
        private final int hudPlayRecH;
        private final int hudTimestampW;
        private final int hudTimestampH;
        private final int hudSpW;
        private final int hudSpH;
        private final int hudPerfW;
        private final int hudPerfH;
        private final int hudDetailsW;
        private final int hudDetailsH;
        private final int hudPlayRecOpacity;
        private final int hudTimestampOpacity;
        private final int hudCornersOpacity;
        private final int hudSpOpacity;
        private final int hudDetailsOpacity;
        private final int hudPerfOpacity;
        private final String hudLayerOrder;
        private final boolean hudPlayRecVisible;
        private final boolean hudTimestampVisible;
        private final boolean hudCornersVisible;
        private final boolean hudSpVisible;
        private final boolean hudDetailsVisible;
        private final boolean hudPerfVisible;
        private final int hudMicX;
        private final int hudMicY;
        private final boolean hudMicVisible;
        private final int hudMicOpacity;
        private final int hudClassicX;
        private final int hudClassicY;
        private final boolean hudClassicVisible;
        private final int hudSynthX;
        private final int hudSynthY;
        private final boolean hudSynthVisible;
        private final boolean filterVhsVisible;
        private final boolean filterLcdMoireVisible;
        private final boolean filterCrtVisible;
        private final int filterVhsIntensity;
        private final int filterLcdMoireIntensity;
        private final int filterCrtIntensity;
        private final boolean watermarksEnabled;
        private final List<WatermarkSlot> watermarkSlots;

        private Snapshot(RecordableConfig config) {
            hudPlayRecX = config.hudPlayRecX;
            hudPlayRecY = config.hudPlayRecY;
            hudTimestampOffsetX = config.hudTimestampOffsetX;
            hudTimestampY = config.hudTimestampY;
            hudSpX = config.hudSpX;
            hudSpOffsetY = config.hudSpOffsetY;
            hudPerfOffsetX = config.hudPerfOffsetX;
            hudPerfOffsetY = config.hudPerfOffsetY;
            hudDetailsOffsetX = config.hudDetailsOffsetX;
            hudDetailsOffsetY = config.hudDetailsOffsetY;
            hudCornersX = config.hudCornersX;
            hudCornersY = config.hudCornersY;
            hudCornersWidth = config.hudCornersWidth;
            hudCornersHeight = config.hudCornersHeight;
            hudPlayRecW = config.hudPlayRecW;
            hudPlayRecH = config.hudPlayRecH;
            hudTimestampW = config.hudTimestampW;
            hudTimestampH = config.hudTimestampH;
            hudSpW = config.hudSpW;
            hudSpH = config.hudSpH;
            hudPerfW = config.hudPerfW;
            hudPerfH = config.hudPerfH;
            hudDetailsW = config.hudDetailsW;
            hudDetailsH = config.hudDetailsH;
            hudPlayRecOpacity = config.hudPlayRecOpacity;
            hudTimestampOpacity = config.hudTimestampOpacity;
            hudCornersOpacity = config.hudCornersOpacity;
            hudSpOpacity = config.hudSpOpacity;
            hudDetailsOpacity = config.hudDetailsOpacity;
            hudPerfOpacity = config.hudPerfOpacity;
            hudLayerOrder = config.hudLayerOrder;
            hudPlayRecVisible = config.hudPlayRecVisible;
            hudTimestampVisible = config.hudTimestampVisible;
            hudCornersVisible = config.hudCornersVisible;
            hudSpVisible = config.hudSpVisible;
            hudDetailsVisible = config.hudDetailsVisible;
            hudPerfVisible = config.hudPerfVisible;
            hudMicX = config.hudMicX;
            hudMicY = config.hudMicY;
            hudMicVisible = config.hudMicVisible;
            hudMicOpacity = config.hudMicOpacity;
            hudClassicX = config.hudClassicX;
            hudClassicY = config.hudClassicY;
            hudClassicVisible = config.hudClassicVisible;
            hudSynthX = config.hudSynthX;
            hudSynthY = config.hudSynthY;
            hudSynthVisible = config.hudSynthVisible;
            filterVhsVisible = config.filterVhsVisible;
            filterLcdMoireVisible = config.filterLcdMoireVisible;
            filterCrtVisible = config.filterCrtVisible;
            filterVhsIntensity = config.filterVhsIntensity;
            filterLcdMoireIntensity = config.filterLcdMoireIntensity;
            filterCrtIntensity = config.filterCrtIntensity;
            watermarksEnabled = config.watermarksEnabled;
            watermarkSlots = copyWatermarks(config.watermarkSlots);
        }

        private void restore(RecordableConfig config) {
            config.hudPlayRecX = hudPlayRecX;
            config.hudPlayRecY = hudPlayRecY;
            config.hudTimestampOffsetX = hudTimestampOffsetX;
            config.hudTimestampY = hudTimestampY;
            config.hudSpX = hudSpX;
            config.hudSpOffsetY = hudSpOffsetY;
            config.hudPerfOffsetX = hudPerfOffsetX;
            config.hudPerfOffsetY = hudPerfOffsetY;
            config.hudDetailsOffsetX = hudDetailsOffsetX;
            config.hudDetailsOffsetY = hudDetailsOffsetY;
            config.hudCornersX = hudCornersX;
            config.hudCornersY = hudCornersY;
            config.hudCornersWidth = hudCornersWidth;
            config.hudCornersHeight = hudCornersHeight;
            config.hudPlayRecW = hudPlayRecW;
            config.hudPlayRecH = hudPlayRecH;
            config.hudTimestampW = hudTimestampW;
            config.hudTimestampH = hudTimestampH;
            config.hudSpW = hudSpW;
            config.hudSpH = hudSpH;
            config.hudPerfW = hudPerfW;
            config.hudPerfH = hudPerfH;
            config.hudDetailsW = hudDetailsW;
            config.hudDetailsH = hudDetailsH;
            config.hudPlayRecOpacity = hudPlayRecOpacity;
            config.hudTimestampOpacity = hudTimestampOpacity;
            config.hudCornersOpacity = hudCornersOpacity;
            config.hudSpOpacity = hudSpOpacity;
            config.hudDetailsOpacity = hudDetailsOpacity;
            config.hudPerfOpacity = hudPerfOpacity;
            config.hudLayerOrder = hudLayerOrder;
            config.hudPlayRecVisible = hudPlayRecVisible;
            config.hudTimestampVisible = hudTimestampVisible;
            config.hudCornersVisible = hudCornersVisible;
            config.hudSpVisible = hudSpVisible;
            config.hudDetailsVisible = hudDetailsVisible;
            config.hudPerfVisible = hudPerfVisible;
            config.hudMicX = hudMicX;
            config.hudMicY = hudMicY;
            config.hudMicVisible = hudMicVisible;
            config.hudMicOpacity = hudMicOpacity;
            config.hudClassicX = hudClassicX;
            config.hudClassicY = hudClassicY;
            config.hudClassicVisible = hudClassicVisible;
            config.hudSynthX = hudSynthX;
            config.hudSynthY = hudSynthY;
            config.hudSynthVisible = hudSynthVisible;
            config.filterVhsVisible = filterVhsVisible;
            config.filterLcdMoireVisible = filterLcdMoireVisible;
            config.filterCrtVisible = filterCrtVisible;
            config.filterVhsIntensity = filterVhsIntensity;
            config.filterLcdMoireIntensity = filterLcdMoireIntensity;
            config.filterCrtIntensity = filterCrtIntensity;
            config.watermarksEnabled = watermarksEnabled;
            config.watermarkSlots = copyWatermarks(watermarkSlots);
        }

        private static List<WatermarkSlot> copyWatermarks(
                List<WatermarkSlot> source) {
            List<WatermarkSlot> copies = new ArrayList<WatermarkSlot>();
            if (source == null) {
                return copies;
            }
            for (WatermarkSlot slot : source) {
                copies.add(slot == null ? null : slot.copy());
            }
            return copies;
        }
    }
}
