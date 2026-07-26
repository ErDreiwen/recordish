package dev.recordable;

import dev.recordable.theme.ThemeColors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.util.ResourceLocation;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/**
 * In-game recording HUD for the Forge 1.8.9 port.
 *
 * <p>The renderer is intentionally self-contained so it can be called directly
 * from Forge's overlay event without pulling a modern DrawContext abstraction
 * into the legacy client.</p>
 */
public final class RecordingOverlay {
    private static final ResourceLocation GUI_ICONS =
            new ResourceLocation("textures/gui/icons.png");
    private static final String[] VHS_LAYERS = {
            "Corners", "PLAY/REC", "Timestamp", "SP", "Details", "Perf"
    };
    private static final int TOAST_PANEL_FILL = 0xFF9E9E9E;
    private static final int TOAST_PANEL_BORDER = 0xFF474747;
    private static final int TOAST_TEXT_COLOR = 0xFFA6E000;
    private static final int TOAST_LOGO_RED = 0xFFD40000;
    private static final int TOAST_LOGO_ORANGE = 0xFFFF3B00;
    private static final int TOAST_LOGO_WHITE = 0xFFFFFFFF;
    private static final int TOAST_LOGO_SIZE = 16;
    private static final int TOAST_BORDER = 2;
    private static final int TOAST_MARGIN = 10;
    private static final Random AUDIO_RANDOM = new Random();

    private static final SimpleDateFormat VHS_DATE =
            new SimpleDateFormat("MMM dd yyyy", Locale.ENGLISH);
    private static final SimpleDateFormat VHS_TIME =
            new SimpleDateFormat("hh:mm a", Locale.ENGLISH);

    private static float simulatedAudioLevel = 0.08F;

    private RecordingOverlay() {
    }

    /**
     * Draws the active recording HUD and any pending post-recording toast.
     */
    public static void render(ScaledResolution resolution) {
        if (resolution == null) {
            return;
        }

        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft == null || minecraft.fontRendererObj == null) {
            return;
        }

        RecordableConfig config = RecordableConfig.get();
        RecordingManager manager = RecordingManager.getInstance();
        if (config == null || manager == null) {
            return;
        }

        FontRenderer font = minecraft.fontRendererObj;
        TextureManager textures = minecraft.getTextureManager();
        renderToast(resolution, font, config, manager);

        if (!manager.isActiveOrStopping()) {
            return;
        }

        renderMicrophoneIndicator(resolution, font, config, manager);
        if (!config.showOverlay) {
            return;
        }

        RecordableConfig.OverlayStyleHud style = config.overlayStyleHud;
        if (style == null) {
            style = RecordableConfig.OverlayStyleHud.CLASSIC;
        }

        float scale = clamp(config.overlayScale / 100.0F, 0.5F, 2.0F);
        GlStateManager.pushMatrix();
        try {
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            GlStateManager.enableBlend();
            GlStateManager.enableTexture2D();
            if (textures != null) {
                // Restore a known GUI texture before legacy HUD drawing. Text and
                // rectangles bind or toggle their own textures as necessary.
                textures.bindTexture(GUI_ICONS);
            }
            GlStateManager.scale(scale, scale, 1.0F);

            int width = Math.max(1, (int) (resolution.getScaledWidth() / scale));
            int height = Math.max(1, (int) (resolution.getScaledHeight() / scale));
            switch (style) {
                case CLASSIC:
                    if (config.hudClassicVisible) {
                        renderClassic(font, config, manager, width, height);
                    }
                    break;
                case VHS:
                    renderVhs(font, config, manager, width, height);
                    break;
                case SYNTHWAVE:
                    if (config.hudSynthVisible) {
                        renderSynthwave(font, config, manager, width, height);
                    }
                    break;
                case NONE:
                default:
                    break;
            }
        } finally {
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            GlStateManager.popMatrix();
        }
    }

    private static void renderClassic(FontRenderer font, RecordableConfig config,
                                      RecordingManager manager, int width, int height) {
        boolean paused = manager.isPaused();
        boolean blink = blinkOn();
        long elapsed = manager.getEffectiveRecordingMillis();
        String effectiveTime = RecordingManager.formatDuration(elapsed);
        ThemeColors skin = activeOverlaySkin(config);

        String firstLine;
        if (manager.getState() == RecordingManager.State.STOPPING) {
            firstLine = "REC stopping...";
        } else if (paused) {
            firstLine = "PAUSED " + effectiveTime;
        } else {
            firstLine = "REC " + effectiveTime;
        }

        long fileSizeBytes = manager.getCurrentFileSizeBytes();
        String fileSize = fileSizeBytes > 0L
                ? RecordingManager.formatBytes(fileSizeBytes)
                : "starting...";
        String secondLine = manager.getRecordingFps()
                + " FPS  drop " + manager.getDroppedFrames();
        String thirdLine = manager.getRecordingWidth()
                + "x" + manager.getRecordingHeight()
                + "  Queue: " + queueOccupancy(manager)
                + " (" + queueLabel(manager) + ")";

        ReplayBuffer replay = ReplayBuffer.getInstance();
        boolean replayActive = replay.isActive();
        String replayLine = replayActive
                ? "Replay: " + replay.getBufferedSeconds()
                        + "s buffered (" + replay.getBufferedFrameCount()
                        + " frames)"
                : "";

        int widest = Math.max(
                font.getStringWidth(firstLine) + 13,
                Math.max(
                        font.getStringWidth(secondLine),
                        Math.max(
                                font.getStringWidth("Size: " + fileSize),
                                font.getStringWidth(thirdLine))));
        if (replayActive) {
            widest = Math.max(
                    widest,
                    font.getStringWidth(replayLine) + 12);
        }

        int panelWidth = widest + 22;
        int panelHeight = 12 + (replayActive ? 4 : 3) * 11;
        int[] position = resolveClassicPosition(
                config,
                width,
                height,
                panelWidth,
                panelHeight,
                config.hudClassicX,
                config.hudClassicY,
                10);
        int x = position[0];
        int y = position[1];

        int accent = skin != null
                ? 0xFF000000 | (skin.accent & 0x00FFFFFF)
                : 0xFF000000 | config.getOverlayColorRgb();
        int panelBackground = skin != null
                ? skin.panelBackground
                : 0x99000000;
        Gui.drawRect(
                x - 3,
                y - 3,
                x + panelWidth,
                y + panelHeight,
                panelBackground);
        Gui.drawRect(
                x - 3,
                y - 3,
                x + panelWidth,
                y - 2,
                withAlpha(accent, 170));

        if (paused) {
            if (blink) {
                drawPauseGlyph(x, y + 1, 0xFFFFD166);
            }
        } else {
            drawRecordDot(x, y + 2, accent);
        }

        int lineY = y;
        drawText(
                font,
                firstLine,
                x + 13,
                lineY,
                paused ? 0xFFFFD166 : 0xFFFFFFFF);
        lineY += 12;
        drawText(
                font,
                secondLine,
                x,
                lineY,
                skin != null ? skin.textSecondary : 0xFFE0E0E0);
        lineY += 11;
        drawText(
                font,
                "Size: " + fileSize,
                x,
                lineY,
                fileSizeBytes > 0L ? 0xFFFFFFFF : 0xFFAAAAAA);
        lineY += 11;
        drawText(font, thirdLine, x, lineY, queueColor(manager));
        lineY += 11;
        if (replayActive) {
            drawReplayGlyph(x, lineY + 1, 0xFF88CCFF);
            drawText(font, replayLine, x + 12, lineY, 0xFF88CCFF);
        }
    }

    private static void renderVhs(FontRenderer font, RecordableConfig config,
                                  RecordingManager manager, int width, int height) {
        updateAudioLevel(manager.isMicrophoneActive());
        ThemeColors skin = activeOverlaySkin(config);
        List<String> layers = parseLayerOrder(config.hudLayerOrder);

        for (String layer : layers) {
            if ("Corners".equals(layer)) {
                if (config.hudCornersVisible && config.vhsShowBrackets) {
                    int color = RecordableConfig.applyOpacity(
                            skin != null
                                    ? skin.accent
                                    : RecordableConfig.parseArgbColor(
                                            config.vhsBracketColor,
                                            0xC8FFFFFF),
                            config.hudCornersOpacity);
                    drawCornerBrackets(
                            config.hudCornersX,
                            config.hudCornersY,
                            config.hudCornersX + Math.max(8, config.hudCornersWidth),
                            config.hudCornersY + Math.max(8, config.hudCornersHeight),
                            20,
                            2,
                            color);
                }
            } else if ("PLAY/REC".equals(layer)) {
                if (config.hudPlayRecVisible) {
                    renderVhsPlayRec(font, config, skin);
                }
            } else if ("Timestamp".equals(layer)) {
                if (config.hudTimestampVisible) {
                    renderVhsTimestamp(
                            font,
                            config,
                            manager,
                            skin,
                            width);
                }
            } else if ("SP".equals(layer)) {
                if (config.hudSpVisible && config.vhsShowSp) {
                    int color = RecordableConfig.applyOpacity(
                            skin != null
                                    ? skin.textPrimary
                                    : RecordableConfig.parseArgbColor(
                                            config.vhsSpColor,
                                            0xFFFFFFFF),
                            config.hudSpOpacity);
                    drawText(font, "SP", config.hudSpX,
                            height - config.hudSpOffsetY, color);
                }
            } else if ("Details".equals(layer)) {
                if (config.hudDetailsVisible) {
                    renderVhsDetails(font, config, manager, width, height);
                }
            } else if ("Perf".equals(layer)) {
                if (config.hudPerfVisible && config.showPerformanceStats) {
                    renderVhsPerformancePanel(font, config, manager, width, height);
                }
            }
        }

        // V1-0.09 treats this compact health line as part of the VHS skin,
        // independent of the optional larger performance panel.
        renderVhsPerformanceLine(font, config, manager, height);
    }

    private static void renderVhsPlayRec(FontRenderer font, RecordableConfig config,
                                         ThemeColors skin) {
        int x = Math.max(0, config.hudPlayRecX);
        int y = Math.max(0, config.hudPlayRecY);
        int opacity = config.hudPlayRecOpacity;

        if (config.vhsShowPlay) {
            int playColor = RecordableConfig.applyOpacity(
                    skin != null
                            ? skin.textPrimary
                            : RecordableConfig.parseArgbColor(
                                    config.vhsPlayColor,
                                    0xFFFFFFFF),
                    opacity);
            drawPlayLabel(font, x, y, playColor);
            y += 12;
        }

        int recTextColor = RecordableConfig.applyOpacity(
                skin != null
                        ? skin.textPrimary
                        : RecordableConfig.parseArgbColor(
                                config.vhsRecTextColor,
                                0xFFFFFFFF),
                opacity);
        if (blinkOn()) {
            int dotColor = RecordableConfig.applyOpacity(
                    skin != null
                            ? skin.accent
                            : RecordableConfig.parseArgbColor(
                                    config.vhsRecDotColor,
                                    0xFFCC1E1E),
                    opacity);
            drawRecordDot(x, y + 2, dotColor);
        }
        drawText(font, "REC", x + 10, y, recTextColor);
    }

    private static void renderVhsTimestamp(FontRenderer font, RecordableConfig config,
                                           RecordingManager manager,
                                           ThemeColors skin,
                                           int width) {
        int color = RecordableConfig.applyOpacity(
                skin != null
                        ? skin.textPrimary
                        : RecordableConfig.parseArgbColor(
                                config.vhsTimestampColor,
                                0xFFFFFFFF),
                config.hudTimestampOpacity);
        String timer = formatTimer(manager.getEffectiveRecordingMillis());
        int x = width - Math.max(0, config.hudTimestampOffsetX)
                - font.getStringWidth(timer);
        drawText(font, timer, x, Math.max(0, config.hudTimestampY), color);
    }

    private static void renderVhsDetails(FontRenderer font, RecordableConfig config,
                                         RecordingManager manager, int width, int height) {
        int opacity = config.hudDetailsOpacity;
        int right = width - Math.max(0, config.hudDetailsOffsetX);
        int cursorY = height - Math.max(0, config.hudDetailsOffsetY);
        Date now = new Date();

        if (config.vhsShowDate) {
            int color = RecordableConfig.applyOpacity(
                    RecordableConfig.parseArgbColor(config.vhsDateColor, 0xFFFFFFFF),
                    opacity);
            String time = VHS_TIME.format(now).toUpperCase(Locale.ROOT);
            String date = VHS_DATE.format(now);
            cursorY -= 22;
            drawTextRight(font, time, right, cursorY, color);
            drawTextRight(font, date, right, cursorY + 11, color);
            cursorY -= 4;
        } else {
            cursorY -= 4;
        }

        if (config.vhsShowTapeCounter) {
            String counter = String.format(
                    Locale.ROOT,
                    "TC %04d",
                    Math.max(0L, manager.getEffectiveRecordingMillis() / 1000L));
            cursorY -= 11;
            drawTextRight(font, counter, right, cursorY,
                    RecordableConfig.applyOpacity(0xFFCCCCCC, opacity));
        }

        if (config.vhsShowAudioMeter) {
            cursorY -= 12;
            drawAudioMeter(right - 60, cursorY, 55, 8, opacity);
        }

        if (config.vhsShowBattery) {
            cursorY -= 13;
            drawBatteryIndicator(font, right - 50, cursorY, manager, opacity);
        }
    }

    private static void renderVhsPerformanceLine(FontRenderer font,
                                                 RecordableConfig config,
                                                 RecordingManager manager,
                                                 int height) {
        String fps = manager.getRecordingFps()
                + " FPS  drop " + manager.getDroppedFrames() + "  ";
        String queue = "Q " + queueOccupancy(manager)
                + " " + queueLabel(manager);
        int x = 4;
        int y = height - 11;
        int width = font.getStringWidth(fps) + font.getStringWidth(queue);
        Gui.drawRect(x - 2, y - 2, x + width + 2, y + 10,
                0x88000000);
        drawText(font, fps, x, y, 0xFFE0E0E0);
        drawText(
                font,
                queue,
                x + font.getStringWidth(fps),
                y,
                queueColor(manager));
    }

    private static void renderVhsPerformancePanel(FontRenderer font,
                                                  RecordableConfig config,
                                                  RecordingManager manager,
                                                  int width, int height) {
        List<String> lines = new ArrayList<String>();
        lines.add("Cap " + rounded(manager.getCaptureFpsEstimate())
                + " | Enc " + rounded(manager.getEncoderFpsEstimate()) + " FPS");
        lines.add("Mem " + manager.getUsedMemoryMiB()
                + " MiB | Drop " + manager.getDroppedFrames());
        PerformanceMetrics metrics = PerformanceMetrics.getInstance();
        metrics.updateQueueStats(
                manager.getQueueSize(),
                manager.getQueueCapacity());
        lines.add("Queue: " + queueOccupancy(manager)
                + " | " + Math.round(metrics.getBufferHealthPercent())
                + "%");

        int widest = 0;
        for (String line : lines) {
            widest = Math.max(widest, font.getStringWidth(line));
        }
        int panelWidth = widest + 10;
        int panelHeight = lines.size() * 10 + 6;
        int x = width - panelWidth - Math.max(0, config.hudPerfOffsetX);
        int y = height - panelHeight - Math.max(0, config.hudPerfOffsetY);
        int opacity = config.hudPerfOpacity;

        Gui.drawRect(x - 2, y - 2, x + panelWidth, y + panelHeight,
                RecordableConfig.applyOpacity(0x99000000, opacity));
        for (int index = 0; index < lines.size(); index++) {
            int color = index == 2 ? queueColor(manager) : 0xFFD0D0D0;
            drawText(font, lines.get(index), x + 2, y + index * 10,
                    RecordableConfig.applyOpacity(color, opacity));
        }
    }

    private static void renderSynthwave(FontRenderer font, RecordableConfig config,
                                        RecordingManager manager, int width, int height) {
        String line = "REC "
                + formatTimer(manager.getEffectiveRecordingMillis());
        int panelWidth = font.getStringWidth(line) + 22;
        int panelHeight = 16;
        // The official Synthwave panel has its own absolute placement and
        // defaults to the desktop safe-area origin, independent of the Classic
        // overlay-position preset.
        int x = clamp(
                config.hudSynthX >= 0 ? config.hudSynthX : 0,
                0,
                Math.max(0, width - panelWidth));
        int y = clamp(
                config.hudSynthY >= 0 ? config.hudSynthY : 0,
                0,
                Math.max(0, height - panelHeight));

        ThemeColors skin = activeOverlaySkin(config);
        int magenta = skin != null ? skin.accent : 0xFFFF2D95;
        int cyan = skin != null ? skin.accentHover : 0xFF00E5FF;
        int panelBackground = skin != null
                ? skin.panelBackground
                : 0xE61A0B2E;
        int textColor = skin != null ? skin.textPrimary : cyan;
        Gui.drawRect(
                x,
                y,
                x + panelWidth,
                y + panelHeight,
                panelBackground);
        Gui.drawRect(x, y, x + panelWidth, y + 1, magenta);
        Gui.drawRect(x, y + panelHeight - 1, x + panelWidth, y + panelHeight, cyan);
        Gui.drawRect(x, y, x + 1, y + panelHeight, magenta);
        Gui.drawRect(x + panelWidth - 1, y,
                x + panelWidth, y + panelHeight, cyan);

        if (blinkOn()) {
            drawRecordDot(x + 6, y + 5, magenta);
        }
        drawText(font, line, x + 16, y + 4, textColor);
    }

    private static void renderMicrophoneIndicator(ScaledResolution resolution,
                                                  FontRenderer font,
                                                  RecordableConfig config,
                                                  RecordingManager manager) {
        if (!config.hudMicVisible) {
            return;
        }

        if (!manager.isMicrophoneCapturing()) {
            return;
        }

        boolean live = manager.isMicrophoneActive();
        boolean pushToTalk = config.microphonePushToTalk;
        String label = pushToTalk ? "MIC (PTT)" : "MIC";
        int opacity = config.hudMicOpacity;
        int panelWidth = font.getStringWidth(label) + 27;
        int x = config.hudMicX < 0
                ? (resolution.getScaledWidth() - panelWidth) / 2
                : config.hudMicX;
        int y = Math.max(0, config.hudMicY);
        x = clamp(x, 0, Math.max(0, resolution.getScaledWidth() - panelWidth));

        Gui.drawRect(x, y, x + panelWidth, y + 13,
                RecordableConfig.applyOpacity(0xA0000000, opacity));
        int dot = live ? 0xFFFF3030 : 0xFF555555;
        int text = RecordableConfig.applyOpacity(
                live ? 0xFFFFFFFF : 0xFF888888,
                opacity);
        drawRecordDot(
                x + 3,
                y + 3,
                RecordableConfig.applyOpacity(dot, opacity));
        drawMicrophoneGlyph(x + 13, y + 2, text);
        drawText(font, label, x + 23, y + 2, text);
        if (pushToTalk && live) {
            Gui.drawRect(x, y + 12, x + panelWidth, y + 13,
                    RecordableConfig.applyOpacity(0xFFFF3030, opacity));
        }
    }

    private static void renderToast(ScaledResolution resolution, FontRenderer font,
                                    RecordableConfig config, RecordingManager manager) {
        List<ToastQueue.Entry> entries = ToastQueue.active();
        if (entries.isEmpty()) return;
        long now = System.currentTimeMillis();
        int stackY = 8 + TOAST_LOGO_SIZE / 2;
        for (ToastQueue.Entry entry : entries) {
            float alpha = entry.alpha(now);
            if (alpha <= 0.02F) continue;
            int maxTextWidth = Math.min(
                    150,
                    Math.max(70, font.getStringWidth(entry.message) + 6));
            List<String> lines = wrapToast(
                    font,
                    entry.message,
                    maxTextWidth);
            if (lines.isEmpty()) {
                stackY += 4;
                continue;
            }
            int textWidth = 0;
            for (String line : lines) {
                textWidth = Math.max(
                        textWidth,
                        font.getStringWidth(line));
            }
            int lineHeight = font.FONT_HEIGHT + 2;
            int panelWidth = textWidth + 14;
            int topPadding = TOAST_LOGO_SIZE / 2 + 4;
            int panelHeight = topPadding
                    + lines.size() * lineHeight + 4;
            int panelX = resolution.getScaledWidth()
                    - panelWidth - TOAST_MARGIN;
            panelX += Math.round(
                    (1.0F - entry.slideProgress(now)) * 14.0F);
            int panelY = stackY;
            int opacity = clamp(
                    Math.round(alpha * 255.0F),
                    0,
                    255);

            Gui.drawRect(
                    panelX - TOAST_BORDER,
                    panelY - TOAST_BORDER,
                    panelX + panelWidth + TOAST_BORDER,
                    panelY + panelHeight + TOAST_BORDER,
                    withAlpha(TOAST_PANEL_BORDER, opacity));
            Gui.drawRect(
                    panelX,
                    panelY,
                    panelX + panelWidth,
                    panelY + panelHeight,
                    withAlpha(TOAST_PANEL_FILL, opacity));

            int logoX = panelX
                    + (panelWidth - TOAST_LOGO_SIZE) / 2;
            int logoY = panelY - TOAST_LOGO_SIZE / 2;
            drawToastLogo(
                    logoX,
                    logoY,
                    TOAST_LOGO_SIZE,
                    opacity);

            int textY = panelY + topPadding;
            for (String line : lines) {
                int lineX = panelX
                        + (panelWidth
                            - font.getStringWidth(line)) / 2;
                drawText(
                        font,
                        line,
                        lineX,
                        textY,
                        withAlpha(TOAST_TEXT_COLOR, opacity));
                textY += lineHeight;
            }
            stackY += panelHeight + TOAST_LOGO_SIZE / 2 + 8;
        }
    }

    private static List<String> wrapToast(
            FontRenderer font,
            String message,
            int maximumWidth) {
        List<String> result = new ArrayList<String>();
        if (message == null || message.isEmpty()) return result;
        StringBuilder current = new StringBuilder();
        for (String word : message.split(" ")) {
            String candidate = current.length() == 0
                    ? word
                    : current + " " + word;
            if (font.getStringWidth(candidate) > maximumWidth
                    && current.length() > 0) {
                result.add(current.toString());
                current = new StringBuilder(word);
            } else {
                current = new StringBuilder(candidate);
            }
        }
        if (current.length() > 0) result.add(current.toString());
        return result;
    }

    private static void drawToastLogo(
            int x,
            int y,
            int size,
            int opacity) {
        Gui.drawRect(
                x,
                y,
                x + size,
                y + size,
                withAlpha(TOAST_LOGO_RED, opacity));
        float centerX = x + size / 2.0F;
        float centerY = y + size * 0.42F;
        fillEllipse(
                centerX,
                centerY,
                size * 0.30F,
                size * 0.27F,
                withAlpha(TOAST_LOGO_ORANGE, opacity));
        fillTriangle(
                centerX,
                y + size * 0.30F,
                x + size * 0.22F,
                y + size * 0.92F,
                x + size * 0.78F,
                y + size * 0.92F,
                withAlpha(TOAST_LOGO_WHITE, opacity));
    }

    private static void fillEllipse(
            float centerX,
            float centerY,
            float radiusX,
            float radiusY,
            int color) {
        int top = (int) Math.floor(centerY - radiusY);
        int bottom = (int) Math.ceil(centerY + radiusY);
        for (int py = top; py < bottom; py++) {
            float dy = (py + 0.5F - centerY) / radiusY;
            if (dy < -1.0F || dy > 1.0F) continue;
            float halfWidth = radiusX
                    * (float) Math.sqrt(
                            Math.max(0.0F, 1.0F - dy * dy));
            int left = Math.round(centerX - halfWidth);
            int right = Math.round(centerX + halfWidth);
            if (right > left) {
                Gui.drawRect(left, py, right, py + 1, color);
            }
        }
    }

    private static void fillTriangle(
            float apexX,
            float apexY,
            float leftX,
            float baseY,
            float rightX,
            float ignoredBaseY,
            int color) {
        int top = (int) Math.floor(apexY);
        int bottom = (int) Math.ceil(baseY);
        float height = Math.max(1.0F, baseY - apexY);
        for (int py = top; py < bottom; py++) {
            float progress = (py + 0.5F - apexY) / height;
            int rowLeft = Math.round(
                    apexX + (leftX - apexX) * progress);
            int rowRight = Math.round(
                    apexX + (rightX - apexX) * progress);
            if (rowRight > rowLeft) {
                Gui.drawRect(
                        rowLeft,
                        py,
                        rowRight,
                        py + 1,
                        color);
            }
        }
    }

    private static List<String> parseLayerOrder(String configuredOrder) {
        List<String> defaults = Arrays.asList(VHS_LAYERS);
        Set<String> valid = new HashSet<String>(defaults);
        List<String> result = new ArrayList<String>();
        if (configuredOrder != null) {
            String[] parts = configuredOrder.split(",");
            for (String part : parts) {
                String value = part.trim();
                if (valid.contains(value) && !result.contains(value)) {
                    result.add(value);
                }
            }
        }
        for (String value : defaults) {
            if (!result.contains(value)) {
                result.add(value);
            }
        }
        return result;
    }

    private static void drawCornerBrackets(int left, int top, int right, int bottom,
                                           int length, int thickness, int color) {
        int safeLength = Math.max(2, Math.min(length,
                Math.min(Math.max(2, right - left), Math.max(2, bottom - top))));
        int safeThickness = Math.max(1, thickness);

        Gui.drawRect(left, top, left + safeLength, top + safeThickness, color);
        Gui.drawRect(left, top, left + safeThickness, top + safeLength, color);
        Gui.drawRect(right - safeLength, top, right, top + safeThickness, color);
        Gui.drawRect(right - safeThickness, top, right, top + safeLength, color);
        Gui.drawRect(left, bottom - safeThickness, left + safeLength, bottom, color);
        Gui.drawRect(left, bottom - safeLength, left + safeThickness, bottom, color);
        Gui.drawRect(right - safeLength, bottom - safeThickness, right, bottom, color);
        Gui.drawRect(right - safeThickness, bottom - safeLength, right, bottom, color);
    }

    private static void drawAudioMeter(int x, int y, int width, int height,
                                       int opacity) {
        int barHeight = Math.max(1, height / 2 - 1);
        int background = RecordableConfig.applyOpacity(0xFF333333, opacity);
        Gui.drawRect(x, y, x + width, y + barHeight, background);
        Gui.drawRect(x, y + barHeight + 1,
                x + width, y + barHeight * 2 + 1, background);

        float rightLevel = clamp(
                simulatedAudioLevel + AUDIO_RANDOM.nextFloat() * 0.15F - 0.075F,
                0.0F,
                1.0F);
        int leftWidth = Math.round(simulatedAudioLevel * width);
        int rightWidth = Math.round(rightLevel * width);
        Gui.drawRect(x, y, x + leftWidth, y + barHeight,
                RecordableConfig.applyOpacity(
                        audioColor(simulatedAudioLevel), opacity));
        Gui.drawRect(x, y + barHeight + 1,
                x + rightWidth, y + barHeight * 2 + 1,
                RecordableConfig.applyOpacity(audioColor(rightLevel), opacity));
    }

    private static void drawBatteryIndicator(FontRenderer font, int x, int y,
                                             RecordingManager manager, int opacity) {
        int width = 24;
        int height = 10;
        int shell = RecordableConfig.applyOpacity(0xFFAAAAAA, opacity);
        Gui.drawRect(x, y, x + width, y + height, shell);
        Gui.drawRect(x + 1, y + 1, x + width - 1, y + height - 1,
                RecordableConfig.applyOpacity(0xFF222222, opacity));
        Gui.drawRect(x + width, y + 2, x + width + 2, y + height - 2, shell);

        float percent = Math.max(
                0.05F,
                1.0F - manager.getEffectiveRecordingMillis() / (2.0F * 3600000.0F));
        int fillWidth = Math.round(percent * (width - 4));
        Gui.drawRect(x + 2, y + 2, x + 2 + fillWidth, y + height - 2,
                RecordableConfig.applyOpacity(audioColor(percent), opacity));
        drawText(font, Math.round(percent * 100.0F) + "%",
                x + width + 5, y + 1,
                RecordableConfig.applyOpacity(0xFFCCCCCC, opacity));
    }

    private static void updateAudioLevel(boolean microphoneActive) {
        float minimum = microphoneActive ? 0.25F : 0.05F;
        float spread = microphoneActive ? 0.60F : 0.12F;
        float target = minimum + AUDIO_RANDOM.nextFloat() * spread;
        simulatedAudioLevel += (target - simulatedAudioLevel) * 0.15F;
        simulatedAudioLevel = clamp(simulatedAudioLevel, 0.02F, 1.0F);
    }

    private static int audioColor(float level) {
        if (level < 0.6F) {
            return 0xFF44CC44;
        }
        if (level < 0.85F) {
            return 0xFFCCCC44;
        }
        return 0xFFCC4444;
    }

    private static int[] resolveClassicPosition(
            RecordableConfig config,
            int screenWidth,
            int screenHeight,
            int panelWidth,
            int panelHeight,
            int customX,
            int customY,
            int margin) {
        if (customX >= 0 && customY >= 0) {
            return new int[]{
                    clamp(
                            customX,
                            0,
                            Math.max(0, screenWidth - panelWidth)),
                    clamp(
                            customY,
                            0,
                            Math.max(0, screenHeight - panelHeight))
            };
        }

        int x;
        int y;
        RecordableConfig.OverlayPosition position = config.overlayPosition;
        if (position == null) {
            position = RecordableConfig.OverlayPosition.TOP_LEFT;
        }
        switch (position) {
            case TOP_RIGHT:
                x = screenWidth - panelWidth - margin - 130;
                y = margin;
                break;
            case BOTTOM_LEFT:
                x = margin;
                y = screenHeight - panelHeight - margin - 50;
                break;
            case BOTTOM_RIGHT:
                x = screenWidth - panelWidth - margin - 130;
                y = screenHeight - panelHeight - margin - 50;
                break;
            case CENTER_TOP:
                x = (screenWidth - panelWidth) / 2;
                y = margin + 70;
                break;
            case TOP_LEFT:
            default:
                x = margin;
                y = margin;
                break;
        }
        int maximumX = Math.max(margin, screenWidth - panelWidth - margin);
        int maximumY = Math.max(margin, screenHeight - panelHeight - margin);
        return new int[]{
                clamp(x, margin, maximumX),
                clamp(y, margin, maximumY)
        };
    }

    private static String queueLabel(RecordingManager manager) {
        RecordingManager.QueueHealth queueHealth = manager.getQueueHealth();
        if (queueHealth == RecordingManager.QueueHealth.CRITICAL) {
            return "DROPPING";
        }
        if (queueHealth == RecordingManager.QueueHealth.SLOW) {
            return "SLOW";
        }
        return "OK";
    }

    private static String queueOccupancy(RecordingManager manager) {
        return manager.getQueueSize() + "/" + manager.getQueueCapacity();
    }

    /**
     * The 1.8.9 font can replace several of the official HUD symbols with a
     * missing-glyph box. These small pixel primitives preserve their intent
     * independently of the selected language or Unicode-font setting.
     */
    private static void drawRecordDot(int x, int y, int color) {
        if (((color >>> 24) & 255) < 4) return;
        Gui.drawRect(x + 2, y, x + 5, y + 1, color);
        Gui.drawRect(x + 1, y + 1, x + 6, y + 2, color);
        Gui.drawRect(x, y + 2, x + 7, y + 5, color);
        Gui.drawRect(x + 1, y + 5, x + 6, y + 6, color);
        Gui.drawRect(x + 2, y + 6, x + 5, y + 7, color);
    }

    private static void drawPauseGlyph(int x, int y, int color) {
        if (((color >>> 24) & 255) < 4) return;
        Gui.drawRect(x, y, x + 3, y + 8, color);
        Gui.drawRect(x + 5, y, x + 8, y + 8, color);
    }

    private static void drawPlayLabel(
            FontRenderer font,
            int x,
            int y,
            int color) {
        String label = "PLAY";
        drawText(font, label, x, y, color);
        int triangleX = x + font.getStringWidth(label) + 3;
        drawPlayTriangle(triangleX, y + 1, color);
    }

    private static void drawPlayTriangle(int x, int y, int color) {
        if (((color >>> 24) & 255) < 4) return;
        Gui.drawRect(x, y, x + 1, y + 8, color);
        Gui.drawRect(x + 1, y + 1, x + 2, y + 7, color);
        Gui.drawRect(x + 2, y + 2, x + 3, y + 6, color);
        Gui.drawRect(x + 3, y + 3, x + 4, y + 5, color);
    }

    private static void drawReplayGlyph(int x, int y, int color) {
        if (((color >>> 24) & 255) < 4) return;
        Gui.drawRect(x + 2, y, x + 7, y + 1, color);
        Gui.drawRect(x + 1, y + 1, x + 2, y + 3, color);
        Gui.drawRect(x, y + 3, x + 1, y + 7, color);
        Gui.drawRect(x + 1, y + 7, x + 3, y + 8, color);
        Gui.drawRect(x + 3, y + 8, x + 7, y + 9, color);
        Gui.drawRect(x + 7, y + 6, x + 8, y + 8, color);
        Gui.drawRect(x + 6, y, x + 9, y + 1, color);
        Gui.drawRect(x + 8, y, x + 9, y + 4, color);
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

    /**
     * V1-0.09 lets the recording HUD inherit the active menu palette without
     * changing any of the user's stored per-element VHS colors.
     */
    private static ThemeColors activeOverlaySkin(RecordableConfig config) {
        if (config == null || !config.overlaySkinEnabled) {
            return null;
        }
        return ThemeColors.forPreset(config.uiTheme);
    }

    private static int queueColor(RecordingManager manager) {
        String label = queueLabel(manager);
        if ("DROPPING".equals(label)) {
            return 0xFFFF7070;
        }
        if ("SLOW".equals(label)) {
            return 0xFFFFD166;
        }
        return 0xFF9BE28F;
    }

    private static boolean blinkOn() {
        return (System.currentTimeMillis() / 500L) % 2L == 0L;
    }

    private static String formatTimer(long milliseconds) {
        long totalSeconds = Math.max(0L, milliseconds / 1000L);
        long hours = totalSeconds / 3600L;
        long minutes = totalSeconds % 3600L / 60L;
        long seconds = totalSeconds % 60L;
        return String.format(
                Locale.ROOT, "%02d:%02d:%02d",
                Long.valueOf(hours),
                Long.valueOf(minutes),
                Long.valueOf(seconds));
    }

    private static long rounded(double value) {
        return Math.max(0L, Math.round(value));
    }

    private static int withAlpha(int argb, int alpha) {
        return (clamp(alpha, 0, 255) << 24) | (argb & 0x00FFFFFF);
    }

    private static void drawText(FontRenderer font, String text,
                                 int x, int y, int color) {
        if (((color >>> 24) & 255) < 4) {
            return;
        }
        font.drawStringWithShadow(text, x, y, color);
    }

    private static void drawTextRight(FontRenderer font, String text,
                                      int right, int y, int color) {
        drawText(font, text, right - font.getStringWidth(text), y, color);
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
