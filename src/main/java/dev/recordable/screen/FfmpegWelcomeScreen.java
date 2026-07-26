package dev.recordable.screen;

import dev.recordable.FfmpegBundleManager;
import dev.recordable.RecordableConfig;
import dev.recordable.theme.ThemeEngine;
import dev.recordable.theme.ThemedButton;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.fml.common.Loader;

import org.lwjgl.input.Keyboard;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * First-run welcome screen shown when no usable FFmpeg executable is found.
 *
 * <p>Opening this screen never starts network activity. The download button
 * only opens the detailed consent screen; its own Download button remains the
 * sole network-starting action.</p>
 */
public final class FfmpegWelcomeScreen extends GuiScreen {
    private static final int DOWNLOAD_ID = 1;
    private static final int DISMISS_ID = 2;

    private static final int REFERENCE_PANEL_WIDTH = 500;
    private static final int REFERENCE_PANEL_HEIGHT = 240;
    private static final int PANEL_COLOR = 0xE0101010;
    private static final int BORDER_COLOR = 0xFF444444;
    private static final int TITLE_COLOR = 0xFFFFFFFF;
    private static final int TEXT_COLOR = 0xFFCCCCCC;
    private static final int HIGHLIGHT_COLOR = 0xFF88FF88;

    private final GuiScreen parent;
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;

    public FfmpegWelcomeScreen(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        ThemeEngine.get().loadFromConfig();
        buttonList.clear();

        panelWidth = Math.min(
                REFERENCE_PANEL_WIDTH,
                Math.max(260, width - 16));
        panelHeight = Math.min(
                REFERENCE_PANEL_HEIGHT,
                Math.max(200, height - 16));
        panelX = (width - panelWidth) / 2;
        panelY = (height - panelHeight) / 2;

        int buttonWidth = 140;
        int buttonHeight = 20;
        int gap = 10;
        int buttonY =
                panelY + panelHeight - buttonHeight - 16;
        boolean automatic =
                FfmpegBundleManager.isAutoDownloadSupported();

        if (automatic) {
            int totalWidth = buttonWidth * 2 + gap;
            int startX = (width - totalWidth) / 2;
            buttonList.add(new ThemedButton(
                    DOWNLOAD_ID,
                    startX,
                    buttonY,
                    buttonWidth,
                    buttonHeight,
                    "Download FFmpeg"));
            buttonList.add(new ThemedButton(
                    DISMISS_ID,
                    startX + buttonWidth + gap,
                    buttonY,
                    buttonWidth,
                    buttonHeight,
                    "Dismiss"));
        } else {
            buttonList.add(new ThemedButton(
                    DISMISS_ID,
                    (width - buttonWidth) / 2,
                    buttonY,
                    buttonWidth,
                    buttonHeight,
                    "Dismiss"));
        }
    }

    @Override
    protected void actionPerformed(GuiButton button)
            throws IOException {
        if (button == null || !button.enabled) {
            return;
        }
        if (button.id == DOWNLOAD_ID) {
            markHandled();
            mc.displayGuiScreen(
                    new FfmpegDownloadScreen(parent));
        } else if (button.id == DISMISS_ID) {
            dismiss();
        }
    }

    private void dismiss() {
        markHandled();
        if (mc != null) {
            mc.displayGuiScreen(parent);
        }
    }

    private static void markHandled() {
        RecordableConfig config = RecordableConfig.get();
        config.ffmpegFirstRunShown = true;
        config.save();
    }

    @Override
    public void drawScreen(
            int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        dev.recordable.theme.ThemedPanel.drawMenuBackdrop(width, height);

        Gui.drawRect(
                panelX,
                panelY,
                panelX + panelWidth,
                panelY + panelHeight,
                PANEL_COLOR);
        drawBorder(
                panelX,
                panelY,
                panelX + panelWidth,
                panelY + panelHeight,
                BORDER_COLOR);

        drawCenteredString(
                fontRendererObj,
                "FFmpeg Setup Required",
                width / 2,
                panelY + 12,
                TITLE_COLOR);

        int textX = panelX + 20;
        int textY = panelY + 36;
        int lineHeight = 12;
        int wrapWidth = panelWidth - 40;
        boolean automatic =
                FfmpegBundleManager.isAutoDownloadSupported();

        List<WelcomeLine> lines =
                new ArrayList<WelcomeLine>();
        if (isVulkanRendererLoaded()) {
            lines.add(new WelcomeLine(
                    "Warning: a Vulkan renderer (e.g. VulkanMod) is installed.",
                    TEXT_COLOR));
            lines.add(new WelcomeLine(
                    "Record-able captures OpenGL frames and does NOT support Vulkan;",
                    TEXT_COLOR));
            lines.add(new WelcomeLine(
                    "recordings will be black. Remove the Vulkan renderer to record.",
                    TEXT_COLOR));
            lines.add(new WelcomeLine("", TEXT_COLOR));
        }

        lines.add(new WelcomeLine(
                "Record-able requires FFmpeg to encode video and audio.",
                TEXT_COLOR));
        lines.add(new WelcomeLine("", TEXT_COLOR));
        lines.add(new WelcomeLine(
                "FFmpeg was not found on your system. Without it, recordings",
                TEXT_COLOR));
        lines.add(new WelcomeLine(
                "will be black and unusable.",
                TEXT_COLOR));
        lines.add(new WelcomeLine("", TEXT_COLOR));

        if (automatic) {
            lines.add(new WelcomeLine(
                    "Click \"Download FFmpeg\" to automatically download and install",
                    TEXT_COLOR));
            lines.add(new WelcomeLine(
                    "FFmpeg from a trusted source:",
                    HIGHLIGHT_COLOR));
            lines.add(new WelcomeLine(
                    FfmpegBundleManager
                            .getDownloadSourceDescription(),
                    TEXT_COLOR));
            lines.add(new WelcomeLine("", TEXT_COLOR));
            lines.add(new WelcomeLine(
                    "Download size: "
                            + FfmpegBundleManager
                                    .getEstimatedDownloadSize(),
                    HIGHLIGHT_COLOR));
            lines.add(new WelcomeLine("", TEXT_COLOR));
            lines.add(new WelcomeLine(
                    "Or click \"Dismiss\" to set it up manually later.",
                    TEXT_COLOR));
        } else {
            lines.add(new WelcomeLine(
                    "Auto-download is not available for your platform.",
                    TEXT_COLOR));
            lines.add(new WelcomeLine("", TEXT_COLOR));
            lines.add(new WelcomeLine(
                    "Manual installation instructions:",
                    TEXT_COLOR));
            lines.add(new WelcomeLine(
                    FfmpegBundleManager
                            .getManualInstallInstructions(),
                    TEXT_COLOR));
        }

        for (WelcomeLine line : lines) {
            if (line.text.length() == 0) {
                textY += lineHeight / 2;
                continue;
            }
            List<String> wrapped = wrapText(
                    line.text, wrapWidth);
            for (String wrappedLine : wrapped) {
                fontRendererObj.drawString(
                        wrappedLine,
                        textX,
                        textY,
                        line.color);
                textY += lineHeight;
            }
        }

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private List<String> wrapText(
            String text, int maximumWidth) {
        List<String> result = new ArrayList<String>();
        String[] words =
                (text == null ? "" : text).split(" ");
        StringBuilder line = new StringBuilder();
        for (String word : words) {
            String candidate = line.length() == 0
                    ? word
                    : line + " " + word;
            if (fontRendererObj.getStringWidth(candidate)
                    <= maximumWidth) {
                if (line.length() > 0) {
                    line.append(' ');
                }
                line.append(word);
            } else if (line.length() > 0) {
                result.add(line.toString());
                line = new StringBuilder(word);
            } else {
                result.add(word);
            }
        }
        if (line.length() > 0) {
            result.add(line.toString());
        }
        return result;
    }

    private static boolean isVulkanRendererLoaded() {
        try {
            return Loader.isModLoaded("vulkanmod")
                    || Loader.isModLoaded("vulkan");
        } catch (Throwable ignored) {
            return false;
        }
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

    @Override
    protected void keyTyped(char typedChar, int keyCode)
            throws IOException {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            dismiss();
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return true;
    }

    private static final class WelcomeLine {
        private final String text;
        private final int color;

        private WelcomeLine(String text, int color) {
            this.text = text == null ? "" : text;
            this.color = color;
        }
    }
}
