package dev.recordable.screen;

import dev.recordable.PlatformUtils;
import dev.recordable.RecordableMod;
import dev.recordable.StorageManager;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

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
    private static final String ANDROID_HELP =
            "Not available on Android. Recordings are auto-saved to your "
                    + "gallery (Movies/Record-able) - open them from your "
                    + "Gallery or Files app.";

    private final File videoFile;
    private final GuiScreen parent;
    private String errorMessage;
    private GuiButton openVideoButton;
    private GuiButton openFolderButton;
    private boolean androidRuntime;

    public VideoPlayerScreen(Path videoPath, GuiScreen parent) {
        this.videoFile = videoPath.toFile();
        this.parent = parent;
        RecordableMod.LOGGER.info(
                "Video player opened for {}",
                videoFile.getName());
    }

    @Override
    public void initGui() {
        buttonList.clear();
        int centerX = width / 2;
        int y = height / 2 + 20;
        androidRuntime = isAndroidRuntime();
        boolean videoAvailable = isVideoAvailable();

        openVideoButton = new GuiButton(
                BUTTON_OPEN,
                centerX - BUTTON_WIDTH / 2,
                y,
                BUTTON_WIDTH,
                BUTTON_HEIGHT,
                androidRuntime
                        ? "Open Video (Not available on Android)"
                        : "\u25B6 Open Video");
        openVideoButton.enabled = !androidRuntime && videoAvailable;
        buttonList.add(openVideoButton);

        openFolderButton = new GuiButton(
                BUTTON_FOLDER,
                centerX - BUTTON_WIDTH / 2,
                y + 30,
                BUTTON_WIDTH,
                BUTTON_HEIGHT,
                androidRuntime
                        ? "Open Folder (Not available on Android)"
                        : "\u25A3 Open Recordings Folder");
        openFolderButton.enabled = !androidRuntime;
        buttonList.add(openFolderButton);

        buttonList.add(new GuiButton(
                BUTTON_BACK,
                centerX - BUTTON_WIDTH / 2,
                y + 60,
                BUTTON_WIDTH,
                BUTTON_HEIGHT,
                "Back"));

        if (!videoAvailable) {
            markVideoMissing();
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button == null || !button.enabled) {
            return;
        }
        if (button.id == BUTTON_OPEN) {
            if (!isVideoAvailable()) {
                markVideoMissing();
                return;
            }
            if (!PlatformUtils.open(videoFile.toPath())) {
                if (isVideoAvailable()) {
                    errorMessage =
                            "Failed to open video in the default player.";
                } else {
                    markVideoMissing();
                }
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
    public void updateScreen() {
        super.updateScreen();
        if (!isVideoAvailable()) {
            markVideoMissing();
        }
    }

    @Override
    public void drawScreen(
            int mouseX,
            int mouseY,
            float partialTicks) {
        drawDefaultBackground();
        dev.recordable.theme.ThemedPanel.drawMenuBackdrop(width, height);

        drawCenteredString(
                fontRendererObj,
                "\u25B6 Video Player",
                width / 2,
                40,
                0xFFFFFFFF);
        drawCenteredString(
                fontRendererObj,
                fontRendererObj.trimStringToWidth(
                        videoFile.getName(),
                        Math.max(80, width - 24)),
                width / 2,
                height / 2 - 80,
                0xFFFFFFFF);

        if (errorMessage != null) {
            drawCenteredString(
                    fontRendererObj,
                    fontRendererObj.trimStringToWidth(
                            errorMessage,
                            Math.max(80, width - 24)),
                    width / 2,
                    height - 60,
                    0xFFFF5555);
        }
        super.drawScreen(mouseX, mouseY, partialTicks);

        if (androidRuntime
                && (contains(openVideoButton, mouseX, mouseY)
                    || contains(openFolderButton, mouseX, mouseY))) {
            List<String> lines =
                    fontRendererObj.listFormattedStringToWidth(
                            ANDROID_HELP,
                            Math.min(
                                    280,
                                    Math.max(120, width - 40)));
            drawHoveringText(lines, mouseX, mouseY);
        }
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

    private static boolean contains(
            GuiButton button,
            int mouseX,
            int mouseY) {
        return button != null
                && button.visible
                && mouseX >= button.xPosition
                && mouseY >= button.yPosition
                && mouseX < button.xPosition + button.width
                && mouseY < button.yPosition + button.height;
    }

    private boolean isVideoAvailable() {
        Path path = videoFile.toPath();
        return Files.isRegularFile(path)
                && StorageManager.isCompleteVideoFile(path);
    }

    private void markVideoMissing() {
        if (openVideoButton != null) {
            openVideoButton.enabled = false;
        }
        errorMessage =
                "This recording is no longer available. Go back to refresh.";
    }

    private static boolean isAndroidRuntime() {
        String os = lowerProperty("os.name");
        String vm = lowerProperty("java.vm.name");
        String runtime = lowerProperty("java.runtime.name");
        String vendor = lowerProperty("java.vendor");
        String javaHome = lowerProperty("java.home");
        boolean pojavEnvironment = false;
        try {
            pojavEnvironment =
                    System.getenv("POJAV_RENDERER") != null
                    || System.getenv("POJAV_NATIVEDIR") != null;
        } catch (SecurityException ignored) {
            // A restricted launcher can deny environment access. The stable
            // JVM properties above still cover standard Android runtimes.
        }
        return os.contains("android")
                || vm.contains("dalvik")
                || runtime.contains("android")
                || vendor.contains("android")
                || javaHome.contains("/data/user/")
                || pojavEnvironment;
    }

    private static String lowerProperty(String key) {
        try {
            return System.getProperty(key, "")
                    .toLowerCase(Locale.ROOT);
        } catch (SecurityException ignored) {
            return "";
        }
    }
}
