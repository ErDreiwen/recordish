package dev.recordable;

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
    private static final long TOAST_DURATION_MS = 5000L;
    private static final long TOAST_FADE_MS = 500L;
    private static final Random AUDIO_RANDOM = new Random();

    private static final SimpleDateFormat VHS_DATE =
            new SimpleDateFormat("MMM dd yyyy", Locale.ENGLISH);
    private static final SimpleDateFormat VHS_TIME =
            new SimpleDateFormat("hh:mm a", Locale.ENGLISH);

    private static float simulatedAudioLevel = 0.08F;
    private static String displayedToast;
    private static long toastStartedAt;

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
        String estimatedSize = estimatedSize(manager);

        String title;
        if (paused) {
            title = (blink ? "|| " : "   ") + "PAUSED";
        } else {
            title = "REC";
        }
        if (config.showRecordingTimer) {
            title += " " + formatTimer(elapsed);
        }

        List<String> lines = new ArrayList<String>();
        List<Integer> colors = new ArrayList<Integer>();
        lines.add(title);
        colors.add(paused ? 0xFFFFD166 : 0xFFFFFFFF);

        lines.add("Cap " + rounded(manager.getCaptureFpsEstimate())
                + " | Enc " + rounded(manager.getEncoderFpsEstimate()) + " FPS");
        colors.add(0xFFE0E0E0);

        if (config.showEstimatedFileSize) {
            lines.add("Est. size: " + estimatedSize);
            colors.add(0xFFFFFFFF);
        }

        String queueLine = "Queue: " + queueLabel(manager);
        if (config.showPerformanceStats) {
            queueLine += " | Mem " + manager.getUsedMemoryMiB() + " MiB";
        }
        lines.add(queueLine);
        colors.add(queueColor(manager));

        int widest = 0;
        for (String line : lines) {
            widest = Math.max(widest, font.getStringWidth(line));
        }

        int panelWidth = widest + 18;
        int panelHeight = lines.size() * 11 + 9;
        int[] position = resolvePosition(
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

        int accent = 0xFF000000 | config.getOverlayColorRgb();
        Gui.drawRect(x, y, x + panelWidth, y + panelHeight, 0xB0000000);
        Gui.drawRect(x, y, x + panelWidth, y + 2, withAlpha(accent, 190));

        int textX = x + 6;
        if (!paused && blink) {
            Gui.drawRect(x + 6, y + 7, x + 13, y + 14, accent);
            textX = x + 17;
        }

        int lineY = y + 5;
        for (int index = 0; index < lines.size(); index++) {
            drawText(font, lines.get(index), index == 0 ? textX : x + 6,
                    lineY, colors.get(index).intValue());
            lineY += 11;
        }
    }

    private static void renderVhs(FontRenderer font, RecordableConfig config,
                                  RecordingManager manager, int width, int height) {
        updateAudioLevel(manager.isMicrophoneActive());
        List<String> layers = parseLayerOrder(config.hudLayerOrder);

        for (String layer : layers) {
            if ("Corners".equals(layer)) {
                if (config.hudCornersVisible && config.vhsShowBrackets) {
                    int color = RecordableConfig.applyOpacity(
                            RecordableConfig.parseArgbColor(
                                    config.vhsBracketColor, 0xC8FFFFFF),
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
                    renderVhsPlayRec(font, config, manager);
                }
            } else if ("Timestamp".equals(layer)) {
                if (config.hudTimestampVisible && config.showRecordingTimer) {
                    renderVhsTimestamp(font, config, manager, width);
                }
            } else if ("SP".equals(layer)) {
                if (config.hudSpVisible && config.vhsShowSp) {
                    int color = RecordableConfig.applyOpacity(
                            RecordableConfig.parseArgbColor(config.vhsSpColor, 0xFFFFFFFF),
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

        if (config.hudPerfVisible) {
            renderVhsPerformanceLine(font, config, manager, height);
        }
    }

    private static void renderVhsPlayRec(FontRenderer font, RecordableConfig config,
                                         RecordingManager manager) {
        int x = Math.max(0, config.hudPlayRecX);
        int y = Math.max(0, config.hudPlayRecY);
        int opacity = config.hudPlayRecOpacity;

        if (config.vhsShowPlay) {
            int playColor = RecordableConfig.applyOpacity(
                    RecordableConfig.parseArgbColor(config.vhsPlayColor, 0xFFFFFFFF),
                    opacity);
            drawText(font, manager.isPaused() ? "PAUSE ||" : "PLAY >",
                    x, y, playColor);
            y += 12;
        }

        int recTextColor = RecordableConfig.applyOpacity(
                RecordableConfig.parseArgbColor(config.vhsRecTextColor, 0xFFFFFFFF),
                opacity);
        String label = manager.isPaused() ? "PAUSED" : "REC";
        if (blinkOn()) {
            int dotColor = RecordableConfig.applyOpacity(
                    RecordableConfig.parseArgbColor(config.vhsRecDotColor, 0xFFCC1E1E),
                    opacity);
            Gui.drawRect(x, y + 2, x + 7, y + 9, dotColor);
        }
        drawText(font, label, x + 10, y, recTextColor);
    }

    private static void renderVhsTimestamp(FontRenderer font, RecordableConfig config,
                                           RecordingManager manager, int width) {
        int color = RecordableConfig.applyOpacity(
                RecordableConfig.parseArgbColor(config.vhsTimestampColor, 0xFFFFFFFF),
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
            String date = VHS_DATE.format(now).toUpperCase(Locale.ROOT);
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

        if (config.showEstimatedFileSize) {
            String size = "EST " + estimatedSize(manager);
            cursorY -= 11;
            drawTextRight(font, size, right, cursorY,
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
        int opacity = config.hudPerfOpacity;
        String fps = "Cap " + rounded(manager.getCaptureFpsEstimate())
                + " Enc " + rounded(manager.getEncoderFpsEstimate()) + " FPS  ";
        String queue = "Q " + queueLabel(manager);
        int x = Math.max(4, config.hudPerfOffsetX);
        int y = height - 11;
        int width = font.getStringWidth(fps) + font.getStringWidth(queue);
        Gui.drawRect(x - 2, y - 2, x + width + 2, y + 10,
                RecordableConfig.applyOpacity(0x88000000, opacity));
        drawText(font, fps, x, y,
                RecordableConfig.applyOpacity(0xFFE0E0E0, opacity));
        drawText(font, queue, x + font.getStringWidth(fps), y,
                RecordableConfig.applyOpacity(queueColor(manager), opacity));
    }

    private static void renderVhsPerformancePanel(FontRenderer font,
                                                  RecordableConfig config,
                                                  RecordingManager manager,
                                                  int width, int height) {
        List<String> lines = new ArrayList<String>();
        lines.add("Capture " + rounded(manager.getCaptureFpsEstimate()) + " FPS");
        lines.add("Encoder " + rounded(manager.getEncoderFpsEstimate()) + " FPS");
        lines.add("Memory " + manager.getUsedMemoryMiB() + " MiB");
        lines.add("Queue " + queueLabel(manager));
        if (config.showEstimatedFileSize) {
            lines.add("Est. " + estimatedSize(manager));
        }

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
            int color = index == 3 ? queueColor(manager) : 0xFFD0D0D0;
            drawText(font, lines.get(index), x + 2, y + index * 10,
                    RecordableConfig.applyOpacity(color, opacity));
        }
    }

    private static void renderSynthwave(FontRenderer font, RecordableConfig config,
                                        RecordingManager manager, int width, int height) {
        boolean paused = manager.isPaused();
        String title = paused ? "PAUSED" : "REC";
        if (config.showRecordingTimer) {
            title += " " + formatTimer(manager.getEffectiveRecordingMillis());
        }

        List<String> lines = new ArrayList<String>();
        lines.add(title);
        if (config.showEstimatedFileSize) {
            lines.add("EST " + estimatedSize(manager));
        }
        if (config.showPerformanceStats) {
            lines.add("C " + rounded(manager.getCaptureFpsEstimate())
                    + " E " + rounded(manager.getEncoderFpsEstimate())
                    + " | Q " + queueLabel(manager));
        }

        int widest = 0;
        for (String line : lines) {
            widest = Math.max(widest, font.getStringWidth(line));
        }
        int panelWidth = widest + 25;
        int panelHeight = Math.max(18, lines.size() * 10 + 8);
        int[] position = resolvePosition(
                config,
                width,
                height,
                panelWidth,
                panelHeight,
                config.hudSynthX,
                config.hudSynthY,
                10);
        int x = position[0];
        int y = position[1];

        int accent = 0xFF000000 | config.getOverlayColorRgb();
        int cyan = 0xFF00E5FF;
        Gui.drawRect(x, y, x + panelWidth, y + panelHeight, 0xE61A0B2E);
        Gui.drawRect(x, y, x + panelWidth, y + 1, accent);
        Gui.drawRect(x, y + panelHeight - 1, x + panelWidth, y + panelHeight, cyan);
        Gui.drawRect(x, y, x + 1, y + panelHeight, accent);
        Gui.drawRect(x + panelWidth - 1, y,
                x + panelWidth, y + panelHeight, cyan);

        if (blinkOn()) {
            Gui.drawRect(x + 6, y + 6, x + 12, y + 12,
                    paused ? 0xFFFFD166 : accent);
        }
        for (int index = 0; index < lines.size(); index++) {
            int color = index == 0
                    ? (paused ? 0xFFFFD166 : cyan)
                    : (index == lines.size() - 1 && config.showPerformanceStats
                    ? queueColor(manager) : 0xFFD7B8FF);
            drawText(font, lines.get(index), x + 16, y + 4 + index * 10, color);
        }
    }

    private static void renderMicrophoneIndicator(ScaledResolution resolution,
                                                  FontRenderer font,
                                                  RecordableConfig config,
                                                  RecordingManager manager) {
        if (!config.hudMicVisible) {
            return;
        }

        boolean live = manager.isMicrophoneActive();
        boolean pushToTalk = config.microphonePushToTalk;
        if (!config.captureMicrophone && !live) {
            return;
        }

        String label;
        if (pushToTalk) {
            label = live ? "MIC (PTT LIVE)" : "MIC (PTT)";
        } else {
            label = live ? "MIC" : "MIC IDLE";
        }
        int opacity = config.hudMicOpacity;
        int panelWidth = font.getStringWidth(label) + 19;
        int x = config.hudMicX < 0
                ? (resolution.getScaledWidth() - panelWidth) / 2
                : config.hudMicX;
        int y = Math.max(0, config.hudMicY);
        x = clamp(x, 0, Math.max(0, resolution.getScaledWidth() - panelWidth));

        Gui.drawRect(x, y, x + panelWidth, y + 13,
                RecordableConfig.applyOpacity(0xA0000000, opacity));
        int dot = live ? 0xFFFF3030 : 0xFF555555;
        Gui.drawRect(x + 4, y + 4, x + 10, y + 10,
                RecordableConfig.applyOpacity(dot, opacity));
        drawText(font, label, x + 14, y + 2,
                RecordableConfig.applyOpacity(
                        live ? 0xFFFFFFFF : 0xFF888888, opacity));
        if (pushToTalk && live) {
            Gui.drawRect(x, y + 12, x + panelWidth, y + 13,
                    RecordableConfig.applyOpacity(0xFFFF3030, opacity));
        }
    }

    private static void renderToast(ScaledResolution resolution, FontRenderer font,
                                    RecordableConfig config, RecordingManager manager) {
        String message = manager.getPendingToastMessage();
        if (message == null || message.trim().isEmpty()) {
            displayedToast = null;
            toastStartedAt = 0L;
            return;
        }
        if (!config.showPostRecordingToast) {
            manager.dismissToast();
            displayedToast = null;
            toastStartedAt = 0L;
            return;
        }

        long now = System.currentTimeMillis();
        if (!message.equals(displayedToast)) {
            displayedToast = message;
            toastStartedAt = now;
        }
        long age = Math.max(0L, now - toastStartedAt);
        if (age >= TOAST_DURATION_MS) {
            manager.dismissToast();
            displayedToast = null;
            toastStartedAt = 0L;
            return;
        }

        int maximumTextWidth = Math.max(20, resolution.getScaledWidth() - 50);
        String visibleMessage = font.trimStringToWidth(message, maximumTextWidth);
        int toastWidth = font.getStringWidth(visibleMessage) + 24;
        int toastHeight = 24;
        int x = (resolution.getScaledWidth() - toastWidth) / 2;
        int y = resolution.getScaledHeight() - toastHeight - 40;
        int opacity = 255;
        long fadeStart = TOAST_DURATION_MS - TOAST_FADE_MS;
        if (age > fadeStart) {
            opacity = (int) ((TOAST_DURATION_MS - age) * 255L / TOAST_FADE_MS);
        }

        Gui.drawRect(x, y, x + toastWidth, y + toastHeight,
                withAlpha(0xFF1A1A1A, opacity * 221 / 255));
        Gui.drawRect(x, y, x + toastWidth, y + 1,
                withAlpha(0xFF44AA44, opacity));
        drawText(font, visibleMessage, x + 12, y + 8,
                withAlpha(0xFFFFFFFF, opacity));
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

    private static int[] resolvePosition(RecordableConfig config,
                                         int screenWidth, int screenHeight,
                                         int panelWidth, int panelHeight,
                                         int customX, int customY, int margin) {
        int x;
        int y;
        RecordableConfig.OverlayPosition position = config.overlayPosition;
        if (position == null) {
            position = RecordableConfig.OverlayPosition.TOP_LEFT;
        }
        switch (position) {
            case TOP_RIGHT:
                x = screenWidth - panelWidth - margin;
                y = margin;
                break;
            case BOTTOM_LEFT:
                x = margin;
                y = screenHeight - panelHeight - margin;
                break;
            case BOTTOM_RIGHT:
                x = screenWidth - panelWidth - margin;
                y = screenHeight - panelHeight - margin;
                break;
            case CENTER_TOP:
                x = (screenWidth - panelWidth) / 2;
                y = margin;
                break;
            case TOP_LEFT:
            default:
                x = margin;
                y = margin;
                break;
        }
        if (customX >= 0) {
            x = customX;
        }
        if (customY >= 0) {
            y = customY;
        }
        return new int[]{
                clamp(x, 0, Math.max(0, screenWidth - panelWidth)),
                clamp(y, 0, Math.max(0, screenHeight - panelHeight))
        };
    }

    private static String queueLabel(RecordingManager manager) {
        Object queueHealth = manager.getQueueHealth();
        String value = queueHealth == null
                ? "OK"
                : queueHealth.toString().trim().toUpperCase(Locale.ROOT);
        if (value.contains("CRITICAL") || value.contains("DROP")) {
            return "DROPPING";
        }
        if (value.contains("SLOW") || value.contains("WARN")) {
            return "SLOW";
        }
        return "OK";
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

    private static String estimatedSize(RecordingManager manager) {
        String value = manager.getEstimatedFileSize();
        return value == null || value.trim().isEmpty() ? "starting..." : value;
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
