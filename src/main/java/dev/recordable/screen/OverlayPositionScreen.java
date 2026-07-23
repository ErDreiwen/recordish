package dev.recordable.screen;

import dev.recordable.RecordableConfig;
import dev.recordable.WatermarkSlot;
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
    private static final int BUTTON_CANCEL = 3;

    private static final int PANEL_WIDTH = 176;
    private static final int PANEL_TOP = 33;
    private static final int LIST_TOP = 53;
    private static final int ROW_HEIGHT = 16;
    private static final int BUTTON_HEIGHT = 20;
    private static final int RESIZE_HANDLE = 5;

    private static final int BACKGROUND = 0xD20B0B11;
    private static final int PANEL_BACKGROUND = 0xEB11111A;
    private static final int PANEL_BORDER = 0xFF464656;
    private static final int ROW_BACKGROUND = 0x781C1C28;
    private static final int ROW_SELECTED = 0xB43A3050;
    private static final int ROW_HOVER = 0xA02A2939;
    private static final int BORDER_IDLE = 0x88FFFFFF;
    private static final int BORDER_HOVER = 0xFFFFFF55;
    private static final int BORDER_SELECTED = 0xFF55FF77;
    private static final int GHOST_BORDER = 0x77FF6666;
    private static final int LABEL_BACKGROUND = 0xD8000000;
    private static final int ACCENT = 0xFF8C6CFF;

    private final GuiScreen parent;
    private final List<String> layerOrder = new ArrayList<String>();
    private final List<Element> elements = new ArrayList<Element>();

    private Snapshot original;
    private boolean resolved;

    private float overlayScale = 1.0F;
    private int virtualWidth;
    private int virtualHeight;

    private Element hoveredElement;
    private Element draggedElement;
    private int dragOffsetX;
    private int dragOffsetY;
    private Element resizeElement;
    private boolean resizing;
    private boolean opacityDragging;

    private String selectedLayerId;
    private WatermarkSlot selectedWatermark;
    private int panelScroll;

    public OverlayPositionScreen(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        buttonList.clear();
        if (original == null) {
            RecordableConfig config = RecordableConfig.get();
            original = new Snapshot(config);
            readLayerOrder(config);
        }

        int buttonY = height - BUTTON_HEIGHT - 5;
        int available = Math.max(170, width - panelWidth());
        int startX = Math.max(5, (available - 190) / 2);
        buttonList.add(new GuiButton(
                BUTTON_DONE, startX, buttonY, 58, BUTTON_HEIGHT, "Done"));
        buttonList.add(new GuiButton(
                BUTTON_RESET, startX + 63, buttonY, 64, BUTTON_HEIGHT,
                "Reset All"));
        buttonList.add(new GuiButton(
                BUTTON_CANCEL, startX + 132, buttonY, 58, BUTTON_HEIGHT,
                "Cancel"));
        updateCoordinateSpace();
        rebuildElements();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
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
        } else if (button.id == BUTTON_CANCEL) {
            cancelAndClose();
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        updateCoordinateSpace();
        rebuildElements();

        Gui.drawRect(0, 0, width, height, BACKGROUND);

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

        drawPanel(mouseX, mouseY);

        int canvasWidth = Math.max(1, width - panelWidth());
        drawCenteredString(
                fontRendererObj,
                "Overlay Position Editor",
                canvasWidth / 2,
                6,
                0xFFFFFFFF);
        String hint;
        if (resizing) {
            hint = "Drag the handle to resize the VHS corner frame";
        } else if (draggedElement != null) {
            hint = "Release to place  |  Shift + arrows moves 10 px";
        } else {
            hint = "Drag to move  |  Right-click or R to reset  |  V toggles";
        }
        drawCenteredString(
                fontRendererObj,
                hint,
                canvasWidth / 2,
                18,
                0xFFB5B5C2);

        if (draggedElement != null || resizeElement != null) {
            Element active = draggedElement != null
                    ? draggedElement
                    : resizeElement;
            String value = active.label + "  "
                    + active.x + "," + active.y;
            if (resizing) {
                value += "  " + active.w + "x" + active.h;
            }
            int valueWidth = fontRendererObj.getStringWidth(value) + 8;
            int tx = Math.min(
                    mouseX + 12,
                    Math.max(0, width - panelWidth() - valueWidth - 2));
            int ty = Math.max(30, mouseY - 13);
            Gui.drawRect(tx, ty, tx + valueWidth, ty + 12,
                    LABEL_BACKGROUND);
            fontRendererObj.drawString(value, tx + 4, ty + 2, 0xFFB7FFBC);
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
        Gui.drawRect(centerX, 0, centerX + 1, virtualHeight, 0x38FFFFFF);
        Gui.drawRect(0, centerY, virtualWidth, centerY + 1, 0x38FFFFFF);
        Gui.drawRect(
                virtualWidth / 3, 0, virtualWidth / 3 + 1,
                virtualHeight, 0x20FFFFFF);
        Gui.drawRect(
                virtualWidth * 2 / 3, 0, virtualWidth * 2 / 3 + 1,
                virtualHeight, 0x20FFFFFF);
        Gui.drawRect(
                0, virtualHeight / 3, virtualWidth,
                virtualHeight / 3 + 1, 0x20FFFFFF);
        Gui.drawRect(
                0, virtualHeight * 2 / 3, virtualWidth,
                virtualHeight * 2 / 3 + 1, 0x20FFFFFF);
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
                60,
                Math.max(
                        fontRendererObj.getStringWidth("PLAY >"),
                        fontRendererObj.getStringWidth("REC") + 12) + 8);
        int playHeight = config.vhsShowPlay ? 24 : 12;
        elements.add(new Element(
                "PLAY/REC", "PLAY / REC",
                config.hudPlayRecX,
                config.hudPlayRecY,
                playWidth,
                playHeight));

        int timestampWidth = fontRendererObj.getStringWidth("00:12:34") + 6;
        elements.add(new Element(
                "Timestamp", "Timestamp",
                virtualWidth - Math.max(0, config.hudTimestampOffsetX)
                        - timestampWidth,
                config.hudTimestampY,
                timestampWidth,
                12));

        Element corners = new Element(
                "Corners", "Corner frame",
                config.hudCornersX,
                config.hudCornersY,
                Math.max(20, config.hudCornersWidth),
                Math.max(20, config.hudCornersHeight));
        corners.resizable = true;
        elements.add(corners);

        int spWidth = fontRendererObj.getStringWidth("SP") + 6;
        elements.add(new Element(
                "SP", "SP",
                config.hudSpX,
                virtualHeight - config.hudSpOffsetY,
                spWidth,
                11));

        int detailsHeight = 4;
        if (config.vhsShowDate) detailsHeight += 22;
        if (config.vhsShowTapeCounter) detailsHeight += 11;
        if (config.showEstimatedFileSize) detailsHeight += 11;
        if (config.vhsShowAudioMeter) detailsHeight += 12;
        if (config.vhsShowBattery) detailsHeight += 13;
        detailsHeight = Math.max(22, detailsHeight);
        int detailsWidth = 86;
        elements.add(new Element(
                "Details", "Details",
                virtualWidth - Math.max(0, config.hudDetailsOffsetX)
                        - detailsWidth,
                virtualHeight - Math.max(0, config.hudDetailsOffsetY)
                        - detailsHeight,
                detailsWidth,
                detailsHeight));

        String[] performanceLines = {
                "Capture 60 FPS",
                "Encoder 60 FPS",
                "Memory 1024 MiB",
                "Queue OK"
        };
        int performanceWidth = 0;
        for (String line : performanceLines) {
            performanceWidth = Math.max(
                    performanceWidth,
                    fontRendererObj.getStringWidth(line));
        }
        performanceWidth += 10;
        int performanceHeight = performanceLines.length * 10 + 6;
        elements.add(new Element(
                "Perf", "Performance",
                virtualWidth - Math.max(0, config.hudPerfOffsetX)
                        - performanceWidth,
                virtualHeight - Math.max(0, config.hudPerfOffsetY)
                        - performanceHeight,
                performanceWidth,
                performanceHeight));
    }

    private void addClassicElement(RecordableConfig config) {
        String[] lines = {
                "REC 00:12:34",
                "Cap 60 | Enc 60 FPS",
                "Est. size: 128 MiB",
                "Queue: OK | Mem 1024 MiB"
        };
        int widest = 0;
        for (String line : lines) {
            widest = Math.max(widest, fontRendererObj.getStringWidth(line));
        }
        int panelWidth = widest + 18;
        int panelHeight = lines.length * 11 + 9;
        int[] position = resolvePanelPosition(
                config, panelWidth, panelHeight,
                config.hudClassicX, config.hudClassicY, 10);
        elements.add(new Element(
                "Classic", "Classic HUD",
                position[0], position[1], panelWidth, panelHeight));
    }

    private void addSynthwaveElement(RecordableConfig config) {
        String[] lines = {
                "REC 00:12:34",
                "EST 128 MiB",
                "C 60 E 60 | Q OK"
        };
        int widest = 0;
        for (String line : lines) {
            widest = Math.max(widest, fontRendererObj.getStringWidth(line));
        }
        int panelWidth = widest + 25;
        int panelHeight = Math.max(18, lines.length * 10 + 8);
        int[] position = resolvePanelPosition(
                config, panelWidth, panelHeight,
                config.hudSynthX, config.hudSynthY, 10);
        elements.add(new Element(
                "Synthwave", "Synthwave HUD",
                position[0], position[1], panelWidth, panelHeight));
    }

    private void addMicrophoneElement(RecordableConfig config) {
        String text = config.microphonePushToTalk
                ? "MIC (PTT LIVE)"
                : "MIC";
        int actualWidth = fontRendererObj.getStringWidth(text) + 19;
        int actualHeight = 14;
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
            if (slot == null) {
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

    private void updateHover(
            int mouseX, int mouseY, int scaledMouseX, int scaledMouseY) {
        hoveredElement = null;
        if (draggedElement != null || resizing || mouseX >= panelLeft()) {
            return;
        }
        List<Element> ordered = orderedElements();
        for (int index = ordered.size() - 1; index >= 0; index--) {
            Element element = ordered.get(index);
            if (!isElementVisible(element)) {
                continue;
            }
            if (element.contains(scaledMouseX, scaledMouseY)) {
                hoveredElement = element;
                return;
            }
        }
    }

    private void drawElement(Element element) {
        boolean visible = isElementVisible(element);
        boolean selected = isSelected(element);
        boolean hovered = sameElement(element, hoveredElement);
        boolean dragged = sameElement(element, draggedElement);
        boolean activeResize = sameElement(element, resizeElement);

        if (visible) {
            drawElementContent(element);
        }

        int border;
        if (dragged || activeResize || selected) {
            border = BORDER_SELECTED;
        } else if (hovered) {
            border = BORDER_HOVER;
        } else if (!visible) {
            border = GHOST_BORDER;
        } else {
            border = BORDER_IDLE;
        }
        int fill = !visible
                ? 0x10FF5555
                : (selected ? 0x2255FF77 : (hovered ? 0x20FFFF55 : 0x0CFFFFFF));
        Gui.drawRect(
                element.x, element.y,
                element.x + element.w, element.y + element.h,
                fill);
        drawBorder(
                element.x, element.y,
                element.x + element.w, element.y + element.h,
                border);

        String tag = (visible ? "" : "[OFF] ") + element.label;
        int tagWidth = fontRendererObj.getStringWidth(tag) + 5;
        int tagY = element.y - 10;
        if (tagY < 0) {
            tagY = element.y + element.h + 1;
        }
        Gui.drawRect(
                element.x, tagY,
                element.x + tagWidth, tagY + 10,
                LABEL_BACKGROUND);
        fontRendererObj.drawString(tag, element.x + 2, tagY + 1, border);

        if (element.resizable && (selected || hovered || resizing)) {
            Gui.drawRect(
                    element.x + element.w - RESIZE_HANDLE,
                    element.y + element.h - RESIZE_HANDLE,
                    element.x + element.w + 1,
                    element.y + element.h + 1,
                    0xFFFF9A46);
        }
    }

    private void drawElementContent(Element element) {
        RecordableConfig config = RecordableConfig.get();
        if (element.watermark != null) {
            drawWatermarkPreview(element);
        } else if ("PLAY/REC".equals(element.id)) {
            int opacity = config.hudPlayRecOpacity;
            int play = RecordableConfig.applyOpacity(
                    RecordableConfig.parseArgbColor(
                            config.vhsPlayColor, 0xFFFFFFFF),
                    opacity);
            int rec = RecordableConfig.applyOpacity(
                    RecordableConfig.parseArgbColor(
                            config.vhsRecTextColor, 0xFFFFFFFF),
                    opacity);
            int dot = RecordableConfig.applyOpacity(
                    RecordableConfig.parseArgbColor(
                            config.vhsRecDotColor, 0xFFCC1E1E),
                    opacity);
            int y = element.y;
            if (config.vhsShowPlay) {
                fontRendererObj.drawString(
                        "PLAY >", element.x + 2, y + 1, play);
                y += 12;
            }
            Gui.drawRect(element.x + 2, y + 3,
                    element.x + 8, y + 9, dot);
            fontRendererObj.drawString("REC", element.x + 11, y + 1, rec);
        } else if ("Timestamp".equals(element.id)) {
            fontRendererObj.drawString(
                    "00:12:34",
                    element.x + 2,
                    element.y + 2,
                    RecordableConfig.applyOpacity(
                            RecordableConfig.parseArgbColor(
                                    config.vhsTimestampColor, 0xFFFFFFFF),
                            config.hudTimestampOpacity));
        } else if ("Corners".equals(element.id)) {
            int color = RecordableConfig.applyOpacity(
                    RecordableConfig.parseArgbColor(
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
                            RecordableConfig.parseArgbColor(
                                    config.vhsSpColor, 0xFFFFFFFF),
                            config.hudSpOpacity));
        } else if ("Details".equals(element.id)) {
            int color = RecordableConfig.applyOpacity(
                    RecordableConfig.parseArgbColor(
                            config.vhsDateColor, 0xFFFFFFFF),
                    config.hudDetailsOpacity);
            drawRight("12:34 PM", element.x + element.w - 2,
                    element.y + 2, color);
            drawRight("JUL 23 2026", element.x + element.w - 2,
                    element.y + 12, color);
            if (element.h >= 35) {
                drawRight("TC 0012", element.x + element.w - 2,
                        element.y + 24,
                        RecordableConfig.applyOpacity(
                                0xFFCCCCCC, config.hudDetailsOpacity));
            }
        } else if ("Perf".equals(element.id)) {
            int opacity = config.hudPerfOpacity;
            Gui.drawRect(
                    element.x, element.y,
                    element.x + element.w, element.y + element.h,
                    RecordableConfig.applyOpacity(0x99000000, opacity));
            String[] lines = {
                    "Capture 60 FPS",
                    "Encoder 60 FPS",
                    "Memory 1024 MiB",
                    "Queue OK"
            };
            for (int index = 0; index < lines.length; index++) {
                fontRendererObj.drawString(
                        lines[index],
                        element.x + 3,
                        element.y + 3 + index * 10,
                        RecordableConfig.applyOpacity(
                                index == 3 ? 0xFF9BE28F : 0xFFD0D0D0,
                                opacity));
            }
        } else if ("Classic".equals(element.id)) {
            int accent = 0xFF000000 | config.getOverlayColorRgb();
            Gui.drawRect(
                    element.x, element.y,
                    element.x + element.w, element.y + element.h,
                    0xB0000000);
            Gui.drawRect(
                    element.x, element.y,
                    element.x + element.w, element.y + 2,
                    RecordableConfig.applyOpacity(accent, 75));
            String[] lines = {
                    "REC 00:12:34",
                    "Cap 60 | Enc 60 FPS",
                    "Est. size: 128 MiB",
                    "Queue: OK | Mem 1024 MiB"
            };
            for (int index = 0; index < lines.length; index++) {
                fontRendererObj.drawString(
                        lines[index],
                        element.x + 6,
                        element.y + 5 + index * 11,
                        index == 3 ? 0xFF9BE28F : 0xFFE5E5E5);
            }
        } else if ("Synthwave".equals(element.id)) {
            int accent = 0xFF000000 | config.getOverlayColorRgb();
            Gui.drawRect(
                    element.x, element.y,
                    element.x + element.w, element.y + element.h,
                    0xE61A0B2E);
            Gui.drawRect(
                    element.x, element.y,
                    element.x + element.w, element.y + 1,
                    accent);
            Gui.drawRect(
                    element.x, element.y + element.h - 1,
                    element.x + element.w, element.y + element.h,
                    0xFF00E5FF);
            String[] lines = {
                    "REC 00:12:34",
                    "EST 128 MiB",
                    "C 60 E 60 | Q OK"
            };
            for (int index = 0; index < lines.length; index++) {
                fontRendererObj.drawString(
                        lines[index],
                        element.x + 8,
                        element.y + 4 + index * 10,
                        index == 0 ? 0xFF00E5FF : 0xFFD7B8FF);
            }
        } else if ("Mic".equals(element.id)) {
            int opacity = config.hudMicOpacity;
            Gui.drawRect(
                    element.x, element.y,
                    element.x + element.w, element.y + element.h,
                    RecordableConfig.applyOpacity(0xAA000000, opacity));
            drawUnscaledText(
                    config.microphonePushToTalk
                            ? "MIC (PTT LIVE)"
                            : "MIC",
                    element.x,
                    element.y,
                    RecordableConfig.applyOpacity(0xFFFF7777, opacity));
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

    private void drawPanel(int mouseX, int mouseY) {
        int left = panelLeft();
        int right = width;
        int sliderTop = sliderTop();
        int listBottom = sliderTop - 5;

        Gui.drawRect(left, PANEL_TOP, right, height, PANEL_BACKGROUND);
        Gui.drawRect(left, PANEL_TOP, left + 1, height, PANEL_BORDER);
        Gui.drawRect(left, PANEL_TOP, right, PANEL_TOP + 1, PANEL_BORDER);
        fontRendererObj.drawString(
                "Layers: back  ->  front",
                left + 7,
                PANEL_TOP + 7,
                0xFFFFFFFF);

        List<PanelRow> rows = panelRows();
        clampPanelScroll(rows.size(), listBottom - LIST_TOP);
        for (int index = 0; index < rows.size(); index++) {
            int rowY = LIST_TOP + index * ROW_HEIGHT - panelScroll;
            if (rowY < LIST_TOP || rowY + ROW_HEIGHT > listBottom) {
                continue;
            }
            drawPanelRow(
                    rows.get(index), index,
                    left, right, rowY, mouseX, mouseY);
        }

        int totalHeight = rows.size() * ROW_HEIGHT;
        int visibleHeight = Math.max(1, listBottom - LIST_TOP);
        if (totalHeight > visibleHeight) {
            int trackX = right - 3;
            Gui.drawRect(trackX, LIST_TOP, right - 1, listBottom,
                    0x55333344);
            int thumbHeight = Math.max(
                    12,
                    visibleHeight * visibleHeight / totalHeight);
            int maximumScroll = totalHeight - visibleHeight;
            int thumbTravel = visibleHeight - thumbHeight;
            int thumbY = LIST_TOP + (maximumScroll <= 0
                    ? 0
                    : panelScroll * thumbTravel / maximumScroll);
            Gui.drawRect(
                    trackX, thumbY,
                    right - 1, thumbY + thumbHeight,
                    ACCENT);
        }

        Gui.drawRect(left + 1, sliderTop - 1, right, sliderTop,
                PANEL_BORDER);
        drawOpacityControl(left, right, sliderTop);
    }

    private void drawPanelRow(
            PanelRow row,
            int rowIndex,
            int left,
            int right,
            int rowY,
            int mouseX,
            int mouseY) {
        boolean selected = isSelected(row);
        boolean hovered = mouseX >= left
                && mouseX < right
                && mouseY >= rowY
                && mouseY < rowY + ROW_HEIGHT;
        int background = selected
                ? ROW_SELECTED
                : (hovered ? ROW_HOVER : ROW_BACKGROUND);
        Gui.drawRect(
                left + 3, rowY,
                right - 4, rowY + ROW_HEIGHT - 1,
                background);

        boolean visible = rowVisible(row);
        int textColor = rowApplicable(row)
                ? 0xFFE4E4EC
                : 0xFF777783;
        fontRendererObj.drawString(
                visible ? "[x]" : "[ ]",
                left + 6,
                rowY + 4,
                visible ? 0xFF7CFF91 : 0xFFFF7777);
        String label = trim(
                rowLabel(row),
                Math.max(20, panelWidth() - 67));
        fontRendererObj.drawString(
                label,
                left + 27,
                rowY + 4,
                textColor);

        if (row.type != RowType.WATERMARK_MASTER) {
            int backX = right - 29;
            int frontX = right - 16;
            fontRendererObj.drawString(
                    "<", backX, rowY + 4,
                    canMove(row, -1) ? 0xFFFFFFFF : 0xFF55555D);
            fontRendererObj.drawString(
                    ">", frontX, rowY + 4,
                    canMove(row, 1) ? 0xFFFFFFFF : 0xFF55555D);
        }
    }

    private void drawOpacityControl(int left, int right, int top) {
        Integer opacity = selectedOpacity();
        String selectedName = selectedName();
        if (selectedName == null) {
            fontRendererObj.drawString(
                    "Select a layer",
                    left + 7,
                    top + 5,
                    0xFF9999A5);
            fontRendererObj.drawString(
                    "Eye toggles visibility; < > reorders",
                    left + 7,
                    top + 18,
                    0xFF777784);
            return;
        }

        fontRendererObj.drawString(
                trim(selectedName, panelWidth() - 68),
                left + 7,
                top + 5,
                0xFFFFFFFF);
        if (opacity == null) {
            fontRendererObj.drawString(
                    "No opacity control",
                    left + 7,
                    top + 19,
                    0xFF888894);
            return;
        }

        String percent = opacity + "%";
        fontRendererObj.drawString(
                percent,
                right - fontRendererObj.getStringWidth(percent) - 7,
                top + 5,
                0xFFCCCCD5);
        int barLeft = left + 7;
        int barRight = right - 7;
        int barTop = top + 20;
        Gui.drawRect(
                barLeft, barTop,
                barRight, barTop + 7,
                0xFF32323D);
        int filled = (barRight - barLeft)
                * clamp(opacity.intValue(), 0, 100) / 100;
        Gui.drawRect(
                barLeft, barTop,
                barLeft + filled, barTop + 7,
                ACCENT);
        Gui.drawRect(
                barLeft + filled - 1, barTop - 2,
                barLeft + filled + 1, barTop + 9,
                0xFFFFFFFF);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton)
            throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (mouseY >= height - BUTTON_HEIGHT - 6) {
            return;
        }

        if (mouseX >= panelLeft()) {
            handlePanelClick(mouseX, mouseY, mouseButton);
            return;
        }

        int scaledX = (int) Math.floor(mouseX / (double) overlayScale);
        int scaledY = (int) Math.floor(mouseY / (double) overlayScale);
        List<Element> ordered = orderedElements();

        for (int index = ordered.size() - 1; index >= 0; index--) {
            Element element = ordered.get(index);
            if (element.resizable
                    && nearResizeHandle(element, scaledX, scaledY)
                    && mouseButton == 0) {
                select(element);
                resizing = true;
                resizeElement = element;
                return;
            }
        }

        Element clicked = null;
        for (int index = ordered.size() - 1; index >= 0; index--) {
            Element element = ordered.get(index);
            if (element.contains(scaledX, scaledY)) {
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
        int left = panelLeft();
        int right = width;
        int opacityBarTop = sliderTop() + 18;
        if (mouseY >= opacityBarTop
                && mouseY <= opacityBarTop + 12
                && selectedOpacity() != null
                && mouseButton == 0) {
            opacityDragging = true;
            setOpacityFromMouse(mouseX);
            return;
        }

        int listBottom = sliderTop() - 5;
        if (mouseY < LIST_TOP || mouseY >= listBottom) {
            return;
        }
        List<PanelRow> rows = panelRows();
        int index = (mouseY - LIST_TOP + panelScroll) / ROW_HEIGHT;
        if (index < 0 || index >= rows.size()) {
            return;
        }
        int rowY = LIST_TOP + index * ROW_HEIGHT - panelScroll;
        if (rowY < LIST_TOP || rowY + ROW_HEIGHT > listBottom) {
            return;
        }

        PanelRow row = rows.get(index);
        select(row);
        if (mouseButton == 1) {
            resetRow(row);
            return;
        }
        if (mouseButton != 0) {
            return;
        }

        if (mouseX < left + 25) {
            toggleRow(row);
        } else if (mouseX >= right - 34 && mouseX < right - 21) {
            moveRow(row, -1);
        } else if (mouseX >= right - 21) {
            moveRow(row, 1);
        } else if (row.type == RowType.WATERMARK_MASTER) {
            toggleRow(row);
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
        if (opacityDragging) {
            setOpacityFromMouse(mouseX);
            return;
        }

        int scaledX = (int) Math.floor(mouseX / (double) overlayScale);
        int scaledY = (int) Math.floor(mouseY / (double) overlayScale);
        if (resizing && resizeElement != null) {
            int newWidth = clamp(
                    scaledX - resizeElement.x,
                    20,
                    Math.max(20, virtualWidth - resizeElement.x));
            int newHeight = clamp(
                    scaledY - resizeElement.y,
                    20,
                    Math.max(20, virtualHeight - resizeElement.y));
            RecordableConfig config = RecordableConfig.get();
            config.hudCornersWidth = newWidth;
            config.hudCornersHeight = newHeight;
            resizeElement.w = newWidth;
            resizeElement.h = newHeight;
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
        if (state == 0) {
            opacityDragging = false;
            resizing = false;
            resizeElement = null;
            draggedElement = null;
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
        if (mouseX < panelLeft()) {
            return;
        }
        panelScroll += wheel > 0 ? -ROW_HEIGHT : ROW_HEIGHT;
        clampPanelScroll(
                panelRows().size(),
                sliderTop() - 5 - LIST_TOP);
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
        for (String id : ids) {
            resetLayer(id.trim());
        }
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
        config.hudMicVisible = true;
        config.hudClassicVisible = true;
        config.hudSynthVisible = true;
        layerOrder.clear();
        for (String id : ids) {
            String cleaned = id.trim();
            if (!cleaned.isEmpty()) {
                layerOrder.add(cleaned);
            }
        }
        config.hudLayerOrder = join(layerOrder);
        if (config.watermarkSlots != null) {
            for (WatermarkSlot slot : config.watermarkSlots) {
                if (slot != null) {
                    resetWatermark(slot);
                }
            }
        }
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
            return config.watermarksEnabled && element.watermark.enabled;
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

    private boolean nearResizeHandle(Element element, int x, int y) {
        return x >= element.x + element.w - RESIZE_HANDLE - 2
                && x <= element.x + element.w + RESIZE_HANDLE
                && y >= element.y + element.h - RESIZE_HANDLE - 2
                && y <= element.y + element.h + RESIZE_HANDLE;
    }

    private int panelWidth() {
        return Math.min(PANEL_WIDTH, Math.max(142, width / 2));
    }

    private int panelLeft() {
        return width - panelWidth();
    }

    private int sliderTop() {
        return Math.max(LIST_TOP + ROW_HEIGHT, height - 69);
    }

    private void clampPanelScroll(int rowCount, int visibleHeight) {
        int maximum = Math.max(
                0,
                rowCount * ROW_HEIGHT - Math.max(1, visibleHeight));
        panelScroll = clamp(panelScroll, 0, maximum);
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
        opacityDragging = false;
        draggedElement = null;
        resizeElement = null;
        resizing = false;
        if (!resolved && original != null) {
            original.restore(RecordableConfig.get());
            RecordableConfig.get().save();
            resolved = true;
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
