package dev.recordable.screen;

import dev.recordable.PlatformUtils;
import dev.recordable.RecordableMod;
import dev.recordable.theme.ThemeColors;
import dev.recordable.theme.ThemeEngine;
import dev.recordable.theme.ThemedButton;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;

import java.io.File;
import java.nio.file.Path;

/**
 * The intentionally simple V1-0.09 video player hand-off screen.
 *
 * <p>Record-able does not ship a native H.264 decoder. "Open Video" hands the
 * recording to the operating system's configured player, matching the modern
 * implementation while keeping Minecraft responsive.</p>
 */
public final class VideoPlayerScreen extends GuiScreen {
    private static final int BUTTON_OPEN = 1;
    private static final int BUTTON_FOLDER = 2;
    private static final int BUTTON_BACK = 3;
    private static final int BUTTON_WIDTH = 200;
    private static final int BUTTON_HEIGHT = 20;

    private final File videoFile;
    private final GuiScreen parent;
    private String errorMessage;

    public VideoPlayerScreen(Path videoPath, GuiScreen parent) {
        this.videoFile = videoPath.toFile();
        this.parent = parent;
        RecordableMod.LOGGER.info(
                "Video player opened for {}",
                videoFile.getName());
    }

    @Override
    public void initGui() {
        ThemeEngine.get().loadFromConfig();
        buttonList.clear();
        int centerX = width / 2;
        int y = height / 2 + 20;
        buttonList.add(new ThemedButton(
                BUTTON_OPEN,
                centerX - BUTTON_WIDTH / 2,
                y,
                BUTTON_WIDTH,
                BUTTON_HEIGHT,
                "\u25B6 Open Video"));
        buttonList.add(new ThemedButton(
                BUTTON_FOLDER,
                centerX - BUTTON_WIDTH / 2,
                y + 30,
                BUTTON_WIDTH,
                BUTTON_HEIGHT,
                "Open Recordings Folder"));
        buttonList.add(new ThemedButton(
                BUTTON_BACK,
                centerX - BUTTON_WIDTH / 2,
                y + 60,
                BUTTON_WIDTH,
                BUTTON_HEIGHT,
                "Back"));
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button == null || !button.enabled) {
            return;
        }
        if (button.id == BUTTON_OPEN) {
            if (!PlatformUtils.open(videoFile.toPath())) {
                errorMessage =
                        "Failed to open video in the default player.";
            } else {
                errorMessage = null;
            }
        } else if (button.id == BUTTON_FOLDER) {
            File folder = videoFile.getParentFile();
            if (folder == null
                    || !PlatformUtils.open(folder.toPath())) {
                errorMessage =
                        "Failed to open the recordings folder.";
            } else {
                errorMessage = null;
            }
        } else if (button.id == BUTTON_BACK) {
            onClose();
        }
    }

    @Override
    public void drawScreen(
            int mouseX,
            int mouseY,
            float partialTicks) {
        drawDefaultBackground();
        ThemeColors colors = ThemeEngine.get().colors();

        drawCenteredString(
                fontRendererObj,
                "\u25B6 Video Player",
                width / 2,
                40,
                colors.headerText);
        drawCenteredString(
                fontRendererObj,
                fontRendererObj.trimStringToWidth(
                        videoFile.getName(),
                        Math.max(80, width - 24)),
                width / 2,
                height / 2 - 80,
                colors.textPrimary);

        if (errorMessage != null) {
            drawCenteredString(
                    fontRendererObj,
                    fontRendererObj.trimStringToWidth(
                            errorMessage,
                            Math.max(80, width - 24)),
                    width / 2,
                    height - 60,
                    colors.textError);
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    public void onClose() {
        if (mc != null) {
            mc.displayGuiScreen(parent);
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode)
            throws java.io.IOException {
        if (keyCode == org.lwjgl.input.Keyboard.KEY_ESCAPE) {
            onClose();
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
