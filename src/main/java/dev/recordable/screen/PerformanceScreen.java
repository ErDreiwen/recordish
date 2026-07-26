package dev.recordable.screen;

import dev.recordable.RecordableConfig;
import dev.recordable.RecordableMod;
import dev.recordable.SmoothMotion;
import dev.recordable.theme.CycleButton;
import dev.recordable.theme.ThemedButton;
import dev.recordable.theme.ThemedPanel;
import dev.recordable.theme.ThemedToggle;
import dev.recordable.theme.ThemeEngine;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;

import org.lwjgl.input.Keyboard;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * V1-0.09 performance category, adapted pixel-for-pixel to Forge 1.8.9.
 */
public final class PerformanceScreen extends GuiScreen {
    private static final int WIDGET_HEIGHT = 20;
    private static final int ROW_SPACING = 24;
    private static final int PANEL_WIDTH = 340;
    private static final int[] MIN_FPS_VALUES = {
            30, 45, 60, 90, 120
    };

    private final GuiScreen parent;
    private final Map<Integer, String> tooltips =
            new HashMap<Integer, String>();
    private int panelX;
    private int panelY;
    private int panelBottom;
    private int renderedPanelWidth;

    public PerformanceScreen(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        buttonList.clear();
        tooltips.clear();
        final RecordableConfig config = RecordableConfig.get();
        if (config == null) {
            closeToParent();
            return;
        }
        ThemeEngine.get().loadFromConfig();
        config.selectedDevicePreset =
                RecordableConfig.sanitizeDevicePreset(
                        config.selectedDevicePreset);

        renderedPanelWidth = Math.min(
                PANEL_WIDTH, Math.max(240, width - 16));
        panelX = (width - renderedPanelWidth) / 2;
        panelY = 30;
        panelBottom = height - 20;

        int innerWidth = renderedPanelWidth - 24;
        int gap = 8;
        int halfWidth = (innerWidth - gap) / 2;
        int leftColumn = panelX + 12;
        int rightColumn = leftColumn + halfWidth + gap;
        int y = panelY + 34;

        buttonList.add(CycleButton.create(
                1,
                leftColumn,
                y,
                halfWidth,
                WIDGET_HEIGHT,
                presetLabel(config),
                new CycleButton.CycleAction() {
                    @Override
                    public void onPress(CycleButton button) {
                        config.selectedDevicePreset = nextPreset(
                                config.selectedDevicePreset);
                        config.save();
                        button.displayString = presetLabel(config);
                    }
                },
                new CycleButton.CycleAction() {
                    @Override
                    public void onPress(CycleButton button) {
                        config.selectedDevicePreset = previousPreset(
                                config.selectedDevicePreset);
                        config.save();
                        button.displayString = presetLabel(config);
                    }
                }));
        tip(
                1,
                "Pick a one-click quality profile tuned for your device "
                        + "class (low-end, balanced, high-end and more). "
                        + "Left-click cycles forward, right-click goes back. "
                        + "Nothing is changed until you press Apply Preset.");

        buttonList.add(ThemedButton.create(
                2,
                rightColumn,
                y,
                halfWidth,
                WIDGET_HEIGHT,
                "Apply Preset",
                new ThemedButton.PressAction() {
                    @Override
                    public void onPress(ThemedButton button) {
                        try {
                            config.applyDevicePreset(
                                    config.selectedDevicePreset);
                            config.save();
                        } catch (Throwable throwable) {
                            RecordableMod.LOGGER.warn(
                                    "Failed to apply device preset {}.",
                                    config.selectedDevicePreset,
                                    throwable);
                        }
                        if (mc != null) {
                            mc.displayGuiScreen(
                                    new PerformanceScreen(parent));
                        }
                    }
                }));
        tip(
                2,
                "Applies the selected device preset right now, "
                        + "overwriting your resolution, FPS, quality and "
                        + "related recording settings with values tuned "
                        + "for that device.");
        y += ROW_SPACING;

        buttonList.add(ThemedToggle.create(
                3,
                leftColumn,
                y,
                halfWidth,
                WIDGET_HEIGHT,
                "Smooth Motion",
                config.smoothMotionEnabled,
                new ThemedToggle.ToggleAction() {
                    @Override
                    public void onChange(boolean value) {
                        config.smoothMotionEnabled = value;
                        config.save();
                    }
                }));
        tip(
                3,
                "Adds frame blending / motion blur to recordings so fast "
                        + "movement looks smoother and more cinematic. "
                        + "Costs a little extra processing while recording.");

        buttonList.add(CycleButton.create(
                4,
                rightColumn,
                y,
                halfWidth,
                WIDGET_HEIGHT,
                motionLabel(config),
                new CycleButton.CycleAction() {
                    @Override
                    public void onPress(CycleButton button) {
                        cycleMotionMode(config, button);
                    }
                },
                new CycleButton.CycleAction() {
                    @Override
                    public void onPress(CycleButton button) {
                        cycleMotionMode(config, button);
                    }
                }));
        tip(
                4,
                "Chooses how Smooth Motion is produced: Blend mixes "
                        + "nearby frames together, Motion estimates movement "
                        + "between frames. Left or right-click to switch.");
        y += ROW_SPACING;

        buttonList.add(ThemedToggle.create(
                5,
                leftColumn,
                y,
                halfWidth,
                WIDGET_HEIGHT,
                "Frame Pooling",
                config.frameBufferPoolingEnabled,
                new ThemedToggle.ToggleAction() {
                    @Override
                    public void onChange(boolean value) {
                        config.frameBufferPoolingEnabled = value;
                        config.save();
                    }
                }));
        tip(
                5,
                "Reuses frame memory buffers instead of allocating new "
                        + "ones every frame. Reduces stutter and "
                        + "garbage-collection lag during long recordings.");

        buttonList.add(ThemedToggle.create(
                6,
                rightColumn,
                y,
                halfWidth,
                WIDGET_HEIGHT,
                "Perf Optimizer",
                config.perfOptimizerEnabled,
                new ThemedToggle.ToggleAction() {
                    @Override
                    public void onChange(boolean value) {
                        config.perfOptimizerEnabled = value;
                        config.save();
                    }
                }));
        tip(
                6,
                "Automatically lowers recording quality on the fly when "
                        + "your game FPS drops too low, so gameplay stays "
                        + "smooth. Works together with the Min FPS target.");
        y += ROW_SPACING;

        buttonList.add(ThemedToggle.create(
                7,
                leftColumn,
                y,
                halfWidth,
                WIDGET_HEIGHT,
                "Auto Adjust",
                config.perfAutoAdjust,
                new ThemedToggle.ToggleAction() {
                    @Override
                    public void onChange(boolean value) {
                        config.perfAutoAdjust = value;
                        config.save();
                    }
                }));
        tip(
                7,
                "Lets the Performance Optimizer actually change settings "
                        + "by itself. When off, the optimizer only watches "
                        + "and warns but will not modify anything.");

        buttonList.add(ThemedToggle.create(
                8,
                rightColumn,
                y,
                halfWidth,
                WIDGET_HEIGHT,
                "Optimizer Overlay",
                config.perfShowStatsOverlay,
                new ThemedToggle.ToggleAction() {
                    @Override
                    public void onChange(boolean value) {
                        config.perfShowStatsOverlay = value;
                        config.save();
                    }
                }));
        tip(
                8,
                "Shows a small live diagnostic overlay with the "
                        + "optimizer's current decisions and FPS readings. "
                        + "It is only baked into recordings if Bake in "
                        + "Overlay is on.");
        y += ROW_SPACING;

        buttonList.add(ThemedToggle.create(
                9,
                leftColumn,
                y,
                halfWidth,
                WIDGET_HEIGHT,
                "Perf Stats HUD",
                config.showPerformanceStats,
                new ThemedToggle.ToggleAction() {
                    @Override
                    public void onChange(boolean value) {
                        config.showPerformanceStats = value;
                        config.save();
                    }
                }));
        tip(
                9,
                "Shows an on-screen performance readout (FPS, frame time, "
                        + "dropped frames) while recording. This is separate "
                        + "from the main recording info overlay.");

        buttonList.add(CycleButton.create(
                10,
                rightColumn,
                y,
                halfWidth,
                WIDGET_HEIGHT,
                minimumFpsLabel(config),
                new CycleButton.CycleAction() {
                    @Override
                    public void onPress(CycleButton button) {
                        config.perfMinFps =
                                nextMinimumFps(config.perfMinFps);
                        config.save();
                        button.displayString =
                                minimumFpsLabel(config);
                    }
                },
                new CycleButton.CycleAction() {
                    @Override
                    public void onPress(CycleButton button) {
                        config.perfMinFps =
                                previousMinimumFps(config.perfMinFps);
                        config.save();
                        button.displayString =
                                minimumFpsLabel(config);
                    }
                }));
        tip(
                10,
                "The target FPS the Performance Optimizer tries to "
                        + "protect. If game FPS falls below this, the "
                        + "optimizer lowers recording quality to recover. "
                        + "Left or right-click to change.");

        buttonList.add(ThemedButton.create(
                11,
                (width - 120) / 2,
                panelBottom - 26,
                120,
                WIDGET_HEIGHT,
                "Done",
                new ThemedButton.PressAction() {
                    @Override
                    public void onPress(ThemedButton button) {
                        closeToParent();
                    }
                }));
    }

    @Override
    public void drawScreen(
            int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        ThemedPanel.drawPanel(
                panelX,
                panelY,
                panelX + renderedPanelWidth,
                panelBottom);
        drawCenteredString(
                fontRendererObj,
                "Performance",
                width / 2,
                panelY + 12,
                0xFFFFFFFF);
        drawCenteredString(
                fontRendererObj,
                "All performance options live here.",
                width / 2,
                panelY + 34
                        + 4 * ROW_SPACING
                        + WIDGET_HEIGHT
                        + 14,
                0xFFB0B0B0);
        super.drawScreen(mouseX, mouseY, partialTicks);
        drawTooltip(mouseX, mouseY);
    }

    private void drawTooltip(int mouseX, int mouseY) {
        for (GuiButton button : buttonList) {
            if (button.visible
                    && mouseX >= button.xPosition
                    && mouseY >= button.yPosition
                    && mouseX < button.xPosition + button.width
                    && mouseY < button.yPosition + button.height) {
                String tooltip = tooltips.get(button.id);
                if (tooltip != null) {
                    List<String> lines =
                            fontRendererObj
                                    .listFormattedStringToWidth(
                                            tooltip,
                                            Math.min(280,
                                                    Math.max(
                                                            120,
                                                            width - 40)));
                    drawHoveringText(lines, mouseX, mouseY);
                }
                return;
            }
        }
    }

    @Override
    protected void mouseClicked(
            int mouseX, int mouseY, int mouseButton)
            throws IOException {
        if (mouseButton == 1) {
            for (GuiButton button : buttonList) {
                if (button instanceof CycleButton
                        && ((CycleButton) button)
                                .mousePressedSecondary(
                                        mc, mouseX, mouseY)) {
                    return;
                }
            }
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
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
        if (mc != null) {
            mc.displayGuiScreen(parent);
        }
    }

    private void tip(int buttonId, String tooltip) {
        tooltips.put(buttonId, tooltip);
    }

    private static String nextPreset(String current) {
        String[] presets = RecordableConfig.DEVICE_PRESETS;
        for (int index = 0; index < presets.length; index++) {
            if (presets[index].equals(current)) {
                return presets[(index + 1) % presets.length];
            }
        }
        return presets.length == 0 ? current : presets[0];
    }

    private static String previousPreset(String current) {
        String[] presets = RecordableConfig.DEVICE_PRESETS;
        for (int index = 0; index < presets.length; index++) {
            if (presets[index].equals(current)) {
                return presets[(index - 1 + presets.length)
                        % presets.length];
            }
        }
        return presets.length == 0 ? current : presets[0];
    }

    private static int nextMinimumFps(int current) {
        for (int index = 0;
                index < MIN_FPS_VALUES.length;
                index++) {
            if (MIN_FPS_VALUES[index] == current) {
                return MIN_FPS_VALUES[
                        (index + 1) % MIN_FPS_VALUES.length];
            }
        }
        return MIN_FPS_VALUES[0];
    }

    private static int previousMinimumFps(int current) {
        for (int index = 0;
                index < MIN_FPS_VALUES.length;
                index++) {
            if (MIN_FPS_VALUES[index] == current) {
                return MIN_FPS_VALUES[
                        (index - 1 + MIN_FPS_VALUES.length)
                                % MIN_FPS_VALUES.length];
            }
        }
        return MIN_FPS_VALUES[0];
    }

    private static void cycleMotionMode(
            RecordableConfig config, CycleButton button) {
        config.smoothMotionMode = SmoothMotion.MODE_BLEND.equals(
                SmoothMotion.sanitizeMode(config.smoothMotionMode))
                ? SmoothMotion.MODE_MOTION
                : SmoothMotion.MODE_BLEND;
        config.save();
        button.displayString = motionLabel(config);
    }

    private static String presetLabel(
            RecordableConfig config) {
        return "Preset: "
                + devicePresetDisplayName(
                        config.selectedDevicePreset);
    }

    private static String minimumFpsLabel(
            RecordableConfig config) {
        return "Min FPS: " + config.perfMinFps;
    }

    private static String motionLabel(
            RecordableConfig config) {
        return "Motion: "
                + SmoothMotion.describe(config.smoothMotionMode);
    }

    private static String devicePresetDisplayName(
            String preset) {
        if ("android_phone".equals(preset)) {
            return "Android Phones";
        }
        if ("low_end_pc".equals(preset)) {
            return "Low-end PC";
        }
        if ("mid_end_pc".equals(preset)) {
            return "Mid-end PC";
        }
        if ("high_end_pc".equals(preset)) {
            return "High-end PC";
        }
        if ("nasa".equals(preset)) {
            return "N.A.S.A Super-Computer";
        }
        return "Custom";
    }
}
