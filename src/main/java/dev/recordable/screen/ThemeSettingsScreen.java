package dev.recordable.screen;

import dev.recordable.RecordableConfig;
import dev.recordable.theme.CycleButton;
import dev.recordable.theme.ThemeColors;
import dev.recordable.theme.ThemeEngine;
import dev.recordable.theme.ThemePreset;
import dev.recordable.theme.ThemedButton;
import dev.recordable.theme.ThemedPanel;
import dev.recordable.theme.ThemedToggle;
import dev.recordable.theme.TypewriterText;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;

import org.lwjgl.input.Keyboard;

import java.io.IOException;

/**
 * V1-0.09 theme customization screen with its complete live preview.
 */
public final class ThemeSettingsScreen extends GuiScreen {
    private static final int WIDGET_HEIGHT = 20;
    private static final int ROW_SPACING = 24;

    private final GuiScreen parent;
    private TypewriterText titleAnimation;
    private int panelLeft;
    private int panelTop;
    private int panelWidth;
    private int panelBottom;
    private int previewLeft;
    private int previewTop;
    private int previewRight;
    private int previewBottom;
    private int descriptionY;

    public ThemeSettingsScreen(GuiScreen parent) {
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
        ThemeEngine.get().loadFromConfig();
        titleAnimation =
                new TypewriterText("[ THEME CONFIGURATION ]", 30);

        int totalWidth = Math.max(
                500, Math.min((int) (width * 0.88F), 800));
        /*
         * The modern layout assumes a >= 500px scaled viewport. Keep its
         * geometry pixel-exact at normal GUI scales while avoiding negative
         * coordinates on the smaller scaled viewports common in 1.8.9.
         */
        if (totalWidth > width - 8) {
            totalWidth = Math.max(240, width - 8);
        }
        int totalLeft = (width - totalWidth) / 2;
        panelWidth = totalWidth / 2 - 8;
        panelLeft = totalLeft;
        panelTop = Math.max(24, (int) (height * 0.08F));
        panelBottom = height - 36;

        previewLeft = totalLeft + panelWidth + 16;
        previewTop = panelTop;
        previewRight = totalLeft + totalWidth;
        previewBottom = panelBottom;

        int widgetLeft = panelLeft + 10;
        int widgetWidth = panelWidth - 20;
        int y = panelTop + 22;

        buttonList.add(CycleButton.create(
                1,
                widgetLeft,
                y,
                widgetWidth,
                WIDGET_HEIGHT,
                themeLabel(config),
                new CycleButton.CycleAction() {
                    @Override
                    public void onPress(CycleButton button) {
                        config.uiTheme = config.uiTheme.next();
                        ThemeEngine.get().applyPreset(config.uiTheme);
                        config.save();
                        button.displayString = themeLabel(config);
                    }
                },
                new CycleButton.CycleAction() {
                    @Override
                    public void onPress(CycleButton button) {
                        config.uiTheme = config.uiTheme.prev();
                        ThemeEngine.get().applyPreset(config.uiTheme);
                        config.save();
                        button.displayString = themeLabel(config);
                    }
                }));
        y += WIDGET_HEIGHT + 4;

        descriptionY = y;
        y += 14;

        buttonList.add(ThemedToggle.create(
                2,
                widgetLeft,
                y,
                widgetWidth,
                WIDGET_HEIGHT,
                "Scanlines",
                config.uiScanlines,
                new ThemedToggle.ToggleAction() {
                    @Override
                    public void onChange(boolean value) {
                        config.uiScanlines = value;
                        reloadAndSave(config);
                    }
                }));
        y += ROW_SPACING;

        buttonList.add(ThemedToggle.create(
                3,
                widgetLeft,
                y,
                widgetWidth,
                WIDGET_HEIGHT,
                "Film Grain",
                config.uiFilmGrain,
                new ThemedToggle.ToggleAction() {
                    @Override
                    public void onChange(boolean value) {
                        config.uiFilmGrain = value;
                        reloadAndSave(config);
                    }
                }));
        y += ROW_SPACING;

        buttonList.add(ThemedToggle.create(
                4,
                widgetLeft,
                y,
                widgetWidth,
                WIDGET_HEIGHT,
                "Glitch Effects",
                config.uiGlitchEffects,
                new ThemedToggle.ToggleAction() {
                    @Override
                    public void onChange(boolean value) {
                        config.uiGlitchEffects = value;
                        reloadAndSave(config);
                    }
                }));
        y += ROW_SPACING;

        buttonList.add(ThemedToggle.create(
                5,
                widgetLeft,
                y,
                widgetWidth,
                WIDGET_HEIGHT,
                "Vignette",
                config.uiVignette,
                new ThemedToggle.ToggleAction() {
                    @Override
                    public void onChange(boolean value) {
                        config.uiVignette = value;
                        reloadAndSave(config);
                    }
                }));
        y += ROW_SPACING;

        buttonList.add(ThemedToggle.create(
                6,
                widgetLeft,
                y,
                widgetWidth,
                WIDGET_HEIGHT,
                "Animations",
                config.uiAnimations,
                new ThemedToggle.ToggleAction() {
                    @Override
                    public void onChange(boolean value) {
                        config.uiAnimations = value;
                        reloadAndSave(config);
                    }
                }));
        y += ROW_SPACING + 8;

        buttonList.add(ThemedButton.create(
                7,
                widgetLeft,
                y,
                widgetWidth,
                WIDGET_HEIGHT,
                "Reset to Defaults",
                new ThemedButton.PressAction() {
                    @Override
                    public void onPress(ThemedButton button) {
                        config.uiTheme = ThemePreset.VHS;
                        config.uiScanlines = true;
                        config.uiFilmGrain = true;
                        config.uiGlitchEffects = true;
                        config.uiVignette = true;
                        config.uiAnimations = true;
                        config.uiCustomAccentColor = "";
                        reloadAndSave(config);
                        if (mc != null) {
                            mc.displayGuiScreen(
                                    new ThemeSettingsScreen(parent));
                        }
                    }
                }));

        buttonList.add(ThemedButton.create(
                8,
                (width - 120) / 2,
                panelBottom + 6,
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
        ThemedPanel.drawMenuBackdrop(width, height);
        ThemeColors colors = ThemeEngine.get().colors();

        ThemedPanel.drawPanel(
                panelLeft - 4,
                panelTop - 4,
                panelLeft + panelWidth + 4,
                panelBottom);
        if (titleAnimation != null) {
            titleAnimation.render(
                    fontRendererObj,
                    panelLeft + 10,
                    panelTop + 8,
                    colors.headerText);
        }

        ThemePreset preset = ThemeEngine.get().preset();
        fontRendererObj.drawStringWithShadow(
                preset.description,
                panelLeft + 10,
                descriptionY,
                colors.textMuted);

        renderPreview();
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void renderPreview() {
        ThemeColors colors = ThemeEngine.get().colors();
        ThemePreset preset = ThemeEngine.get().preset();
        if (preset == ThemePreset.CINEMA) {
            ThemedPanel.drawFilmPanel(
                    previewLeft,
                    previewTop,
                    previewRight,
                    previewBottom);
        } else {
            ThemedPanel.drawPanel(
                    previewLeft,
                    previewTop,
                    previewRight,
                    previewBottom);
        }

        int x = previewLeft + 14;
        int availableWidth = previewRight - previewLeft - 28;
        int y = previewTop + 12;

        ThemedPanel.drawSectionHeader(
                fontRendererObj,
                "Live Preview",
                x,
                y,
                availableWidth);
        y += 22;

        ThemedPanel.drawSectionHeader(
                fontRendererObj,
                "Recording Settings",
                x,
                y,
                availableWidth);
        y += 18;

        fontRendererObj.drawStringWithShadow(
                "Resolution: " + previewResolution(),
                x + 8,
                y,
                colors.textPrimary);
        y += 12;
        fontRendererObj.drawStringWithShadow(
                "FPS: " + previewFps(),
                x + 8,
                y,
                colors.textPrimary);
        y += 12;
        fontRendererObj.drawStringWithShadow(
                "Encoder: " + previewEncoderName(),
                x + 8,
                y,
                colors.textSecondary);
        y += 18;

        ThemedPanel.drawDivider(x, y, availableWidth);
        y += 14;

        ThemedPanel.drawVhsStatusBadge(
                fontRendererObj,
                "\u25B6 PLAY",
                x + 6,
                y,
                false);
        String recordText = "\u25CF REC";
        int recordTextWidth =
                fontRendererObj.getStringWidth(recordText);
        int recordX = Math.min(
                x + availableWidth - recordTextWidth - 8,
                x + availableWidth / 2 + 20);
        TypewriterText.renderFlickerText(
                fontRendererObj,
                recordText,
                recordX,
                y,
                colors.accent);
        y += 22;

        ThemedPanel.drawDivider(x, y, availableWidth);
        y += 14;

        fontRendererObj.drawStringWithShadow(
                "Quick Keys:",
                x + 6,
                y,
                colors.textMuted);
        y += 14;

        RecordableConfig config = RecordableConfig.get();
        String[] keyLabels = {
                keyDisplay(config.hotkeyToggleRecording),
                keyDisplay(config.hotkeyPauseResume),
                keyDisplay(config.hotkeyAddBookmark)
        };
        String[] keyDescriptions = {
                "Record", "Pause", "Bookmark"
        };
        int badgeHeight = 12;
        int maximumLabelWidth = 0;
        for (String keyLabel : keyLabels) {
            maximumLabelWidth = Math.max(
                    maximumLabelWidth,
                    fontRendererObj.getStringWidth(keyLabel));
        }
        int badgeWidth = maximumLabelWidth + 8;
        int badgeX = x + 10;
        for (int index = 0;
                index < keyLabels.length;
                index++) {
            drawKeyBadge(
                    badgeX,
                    y,
                    badgeWidth,
                    badgeHeight,
                    keyLabels[index],
                    keyDescriptions[index],
                    colors);
            y += badgeHeight + 4;
        }
        y += 6;

        ThemedPanel.drawDivider(x, y, availableWidth);
        y += 14;

        fontRendererObj.drawStringWithShadow(
                "Color Palette:",
                x + 6,
                y,
                colors.textMuted);
        y += 14;
        int[] swatches = {
                colors.accent,
                colors.accentHover,
                colors.accentDim,
                colors.textPrimary,
                colors.textSecondary,
                colors.textMuted,
                colors.panelBackground,
                colors.panelBorder,
                colors.headerUnderline
        };
        int availableSwatchWidth = availableWidth - 16;
        int swatchGap = 3;
        int swatchSize = Math.min(
                14,
                (availableSwatchWidth
                        - (swatches.length - 1) * swatchGap)
                        / swatches.length);
        swatchSize = Math.max(6, swatchSize);
        for (int index = 0;
                index < swatches.length;
                index++) {
            int swatchX = x + 8
                    + index * (swatchSize + swatchGap);
            if (y + swatchSize > previewBottom - 4) {
                break;
            }
            Gui.drawRect(
                    swatchX,
                    y,
                    swatchX + swatchSize,
                    y + swatchSize,
                    swatches[index]);
            drawBorder(
                    swatchX,
                    y,
                    swatchX + swatchSize,
                    y + swatchSize,
                    0xFF555555);
        }
    }

    private void drawKeyBadge(
            int x,
            int y,
            int badgeWidth,
            int badgeHeight,
            String keyLabel,
            String description,
            ThemeColors colors) {
        Gui.drawRect(
                x,
                y,
                x + badgeWidth,
                y + badgeHeight,
                colors.buttonBackground);
        drawBorder(
                x,
                y,
                x + badgeWidth,
                y + badgeHeight,
                colors.buttonBorder);
        int keyWidth =
                fontRendererObj.getStringWidth(keyLabel);
        int keyColor = "Not Bound".equals(keyLabel)
                ? colors.textMuted
                : colors.accent;
        fontRendererObj.drawStringWithShadow(
                keyLabel,
                x + (badgeWidth - keyWidth) / 2,
                y + 2,
                keyColor);
        fontRendererObj.drawStringWithShadow(
                description,
                x + badgeWidth + 6,
                y + 2,
                colors.textPrimary);
    }

    @Override
    protected void mouseClicked(
            int mouseX, int mouseY, int mouseButton)
            throws IOException {
        if (mouseButton == 1) {
            for (Object object : buttonList) {
                if (object instanceof CycleButton
                        && ((CycleButton) object)
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

    private static void reloadAndSave(
            RecordableConfig config) {
        ThemeEngine.get().loadFromConfig();
        config.save();
    }

    private static String themeLabel(
            RecordableConfig config) {
        ThemePreset preset =
                config.uiTheme == null
                        ? ThemePreset.VHS
                        : config.uiTheme;
        return "Theme: " + preset.displayName;
    }

    private static String previewEncoderName() {
        RecordableConfig config = RecordableConfig.get();
        if (config != null && config.encoder != null) {
            return config.encoder.displayName;
        }
        return RecordableConfig.VideoEncoder.SOFTWARE.displayName;
    }

    private static String previewResolution() {
        RecordableConfig config = RecordableConfig.get();
        if (config != null
                && config.resolution != null
                && !config.resolution.trim().isEmpty()) {
            return config.resolution;
        }
        return "1080p";
    }

    private static int previewFps() {
        RecordableConfig config = RecordableConfig.get();
        return config != null && config.fps > 0
                ? config.fps
                : 60;
    }

    private static String keyDisplay(int keyCode) {
        if (keyCode == Keyboard.KEY_NONE) {
            return "Not Bound";
        }
        String keyName = Keyboard.getKeyName(keyCode);
        return keyName == null || keyName.trim().isEmpty()
                ? "Not Bound"
                : keyName;
    }

    private static void drawBorder(
            int left,
            int top,
            int right,
            int bottom,
            int color) {
        Gui.drawRect(left, top, right, top + 1, color);
        Gui.drawRect(
                left, bottom - 1, right, bottom, color);
        Gui.drawRect(left, top, left + 1, bottom, color);
        Gui.drawRect(
                right - 1, top, right, bottom, color);
    }
}
