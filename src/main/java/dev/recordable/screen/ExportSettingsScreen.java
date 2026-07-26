package dev.recordable.screen;

import dev.recordable.RecordableConfig;
import dev.recordable.theme.CycleButton;
import dev.recordable.theme.ThemeEngine;
import dev.recordable.theme.ThemedButton;
import dev.recordable.theme.ThemedSlider;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Keyboard;

import java.io.IOException;
import java.util.Locale;

/**
 * V1-0.09 dedicated export settings, adapted to Forge 1.8.9.
 */
public final class ExportSettingsScreen extends GuiScreen {
    private static final int WIDGET_HEIGHT = 20;
    private static final int ROW_SPACING = 24;
    private static final int PANEL_W = 400;

    private static final String[] EXPORT_FORMATS = {
            "mp4", "mkv", "mov", "avi", "webm"
    };
    private static final String[] VIDEO_CODECS = {
            "h264", "h265", "vp9"
    };
    private static final String[] AUDIO_CODECS = {
            "aac", "mp3", "opus"
    };
    private static final String[] EXPORT_RESOLUTIONS = {
            "native", "1080p", "720p", "480p"
    };
    private static final int[] EXPORT_FPS_VALUES = {
            0, 24, 30, 60, 120
    };

    private final GuiScreen parent;
    private int panelX;
    private int panelY;
    private int panelBottom;

    public ExportSettingsScreen(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        buttonList.clear();
        final RecordableConfig config = RecordableConfig.get();
        if (config == null) {
            closeToParent();
            return;
        }
        ThemeEngine.get().applyPreset(config.uiTheme);

        panelX = (width - PANEL_W) / 2;
        panelY = 30;
        panelBottom = height - 20;

        int innerWidth = PANEL_W - 24;
        int gap = 8;
        int halfWidth = (innerWidth - gap) / 2;
        int columnLeft = panelX + 12;
        int columnRight = columnLeft + halfWidth + gap;
        int y = panelY + 34;

        buttonList.add(CycleButton.create(
                1,
                columnLeft,
                y,
                innerWidth,
                WIDGET_HEIGHT,
                "Format: " + getFormatDisplay(config),
                button -> {
                    config.exportFormat =
                            nextValue(config.exportFormat, EXPORT_FORMATS);
                    config.save();
                    button.displayString =
                            "Format: " + getFormatDisplay(config);
                },
                button -> {
                    config.exportFormat =
                            previousValue(config.exportFormat, EXPORT_FORMATS);
                    config.save();
                    button.displayString =
                            "Format: " + getFormatDisplay(config);
                }));
        y += ROW_SPACING;

        buttonList.add(CycleButton.create(
                2,
                columnLeft,
                y,
                halfWidth,
                WIDGET_HEIGHT,
                "Codec: " + getVideoCodecDisplay(config),
                button -> {
                    config.exportVideoCodec = nextValue(
                            config.exportVideoCodec,
                            VIDEO_CODECS);
                    config.save();
                    button.displayString =
                            "Codec: " + getVideoCodecDisplay(config);
                },
                button -> {
                    config.exportVideoCodec = previousValue(
                            config.exportVideoCodec,
                            VIDEO_CODECS);
                    config.save();
                    button.displayString =
                            "Codec: " + getVideoCodecDisplay(config);
                }));
        buttonList.add(ThemedSlider.create(
                3,
                columnRight,
                y,
                halfWidth,
                WIDGET_HEIGHT,
                "Bitrate: %d Mbps",
                0,
                50,
                config.exportVideoBitrateMbps,
                value -> {
                    config.exportVideoBitrateMbps =
                            (int) Math.round(value);
                    config.save();
                }));
        y += ROW_SPACING;

        buttonList.add(CycleButton.create(
                4,
                columnLeft,
                y,
                halfWidth,
                WIDGET_HEIGHT,
                "Audio: " + getAudioCodecDisplay(config),
                button -> {
                    config.exportAudioCodec = nextValue(
                            config.exportAudioCodec,
                            AUDIO_CODECS);
                    config.save();
                    button.displayString =
                            "Audio: " + getAudioCodecDisplay(config);
                },
                button -> {
                    config.exportAudioCodec = previousValue(
                            config.exportAudioCodec,
                            AUDIO_CODECS);
                    config.save();
                    button.displayString =
                            "Audio: " + getAudioCodecDisplay(config);
                }));
        buttonList.add(ThemedSlider.create(
                5,
                columnRight,
                y,
                halfWidth,
                WIDGET_HEIGHT,
                "ABR: %d kbps",
                0,
                320,
                config.exportAudioBitrateKbps,
                value -> {
                    config.exportAudioBitrateKbps =
                            (int) Math.round(value);
                    config.save();
                }));
        y += ROW_SPACING;

        buttonList.add(CycleButton.create(
                6,
                columnLeft,
                y,
                halfWidth,
                WIDGET_HEIGHT,
                "Res: " + getResolutionDisplay(config),
                button -> {
                    config.exportResolution = nextValue(
                            config.exportResolution,
                            EXPORT_RESOLUTIONS);
                    config.save();
                    button.displayString =
                            "Res: " + getResolutionDisplay(config);
                },
                button -> {
                    config.exportResolution = previousValue(
                            config.exportResolution,
                            EXPORT_RESOLUTIONS);
                    config.save();
                    button.displayString =
                            "Res: " + getResolutionDisplay(config);
                }));
        buttonList.add(CycleButton.create(
                7,
                columnRight,
                y,
                halfWidth,
                WIDGET_HEIGHT,
                "FPS: " + getFpsDisplay(config),
                button -> {
                    config.exportFps =
                            nextFps(config.exportFps);
                    config.save();
                    button.displayString =
                            "FPS: " + getFpsDisplay(config);
                },
                button -> {
                    config.exportFps =
                            previousFps(config.exportFps);
                    config.save();
                    button.displayString =
                            "FPS: " + getFpsDisplay(config);
                }));
        y += ROW_SPACING + 10;

        buttonList.add(ThemedButton.create(
                8,
                columnLeft,
                y,
                innerWidth,
                WIDGET_HEIGHT,
                "Reset to Defaults",
                button -> {
                    config.exportFormat = "";
                    config.exportVideoCodec = "";
                    config.exportVideoBitrateMbps = 0;
                    config.exportAudioCodec = "";
                    config.exportAudioBitrateKbps = 0;
                    config.exportResolution = "";
                    config.exportFps = 0;
                    config.save();
                    mc.displayGuiScreen(
                            new ExportSettingsScreen(parent));
                }));

        buttonList.add(ThemedButton.create(
                9,
                columnLeft,
                height - 40,
                innerWidth,
                WIDGET_HEIGHT,
                "Done",
                button -> closeToParent()));
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        // V1-0.09 controls dispatch through their own callbacks.
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
                    return;
                }
            }
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        super.drawScreen(mouseX, mouseY, partialTicks);
        drawCenteredString(
                fontRendererObj,
                "Export Settings",
                width / 2,
                panelY + 10,
                0xFFFFFF);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode)
            throws IOException {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            closeToParent();
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    private void closeToParent() {
        mc.displayGuiScreen(parent);
    }

    private static String getFormatDisplay(RecordableConfig config) {
        return isEmpty(config.exportFormat)
                ? "Auto"
                : config.exportFormat.toUpperCase(Locale.ROOT);
    }

    private static String getVideoCodecDisplay(RecordableConfig config) {
        return isEmpty(config.exportVideoCodec)
                ? "Auto"
                : config.exportVideoCodec.toUpperCase(Locale.ROOT);
    }

    private static String getAudioCodecDisplay(RecordableConfig config) {
        return isEmpty(config.exportAudioCodec)
                ? "Auto"
                : config.exportAudioCodec.toUpperCase(Locale.ROOT);
    }

    private static String getResolutionDisplay(RecordableConfig config) {
        return isEmpty(config.exportResolution)
                ? "Recording"
                : config.exportResolution;
    }

    private static String getFpsDisplay(RecordableConfig config) {
        return config.exportFps == 0
                ? "Recording"
                : Integer.toString(config.exportFps);
    }

    private static String nextValue(String current, String[] values) {
        String safeCurrent = current == null ? "" : current;
        if (safeCurrent.isEmpty()) {
            return values[0];
        }
        for (int index = 0; index < values.length; index++) {
            if (values[index].equals(safeCurrent)) {
                return index == values.length - 1
                        ? ""
                        : values[index + 1];
            }
        }
        return values[0];
    }

    private static String previousValue(String current, String[] values) {
        String safeCurrent = current == null ? "" : current;
        if (safeCurrent.isEmpty()) {
            return values[values.length - 1];
        }
        for (int index = 0; index < values.length; index++) {
            if (values[index].equals(safeCurrent)) {
                return index == 0
                        ? ""
                        : values[index - 1];
            }
        }
        return values[0];
    }

    private static int nextFps(int current) {
        for (int index = 0; index < EXPORT_FPS_VALUES.length; index++) {
            if (EXPORT_FPS_VALUES[index] == current) {
                return index == EXPORT_FPS_VALUES.length - 1
                        ? EXPORT_FPS_VALUES[0]
                        : EXPORT_FPS_VALUES[index + 1];
            }
        }
        return EXPORT_FPS_VALUES[0];
    }

    private static int previousFps(int current) {
        for (int index = 0; index < EXPORT_FPS_VALUES.length; index++) {
            if (EXPORT_FPS_VALUES[index] == current) {
                return index == 0
                        ? EXPORT_FPS_VALUES[
                                EXPORT_FPS_VALUES.length - 1]
                        : EXPORT_FPS_VALUES[index - 1];
            }
        }
        return EXPORT_FPS_VALUES[0];
    }

    private static boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }
}
