package dev.recordable.screen;

import dev.recordable.FfmpegBundleManager;
import dev.recordable.PlatformUtils;
import dev.recordable.RecordableConfig;
import dev.recordable.RecordableMod;
import dev.recordable.theme.ThemedPanel;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * V1-0.09 FFmpeg setup and explicit-download-consent screen.
 *
 * <p>The modern composition is retained: a scrollable diagnostic body, a
 * fixed progress/footer region, state-dependent primary actions, and the
 * Test/Paste/Copy manual-override toolbar. The actual installer remains the
 * hardened Forge backend; no network request is made until Download is
 * pressed here.</p>
 */
public final class FfmpegDownloadScreen extends GuiScreen
        implements FfmpegBundleManager.ProgressListener {
    private static final int DOWNLOAD_ID = 1;
    private static final int CANCEL_ID = 2;
    private static final int OPEN_FOLDER_ID = 3;
    private static final int TEST_ID = 4;
    private static final int PASTE_PATH_ID = 5;
    private static final int COPY_COMMAND_ID = 6;

    private static final int PANEL_COLOR = 0xD0101010;
    private static final int PANEL_BORDER_COLOR = 0xFF424242;
    private static final int HEADER_COLOR = 0xFFFFFFFF;
    private static final int TEXT_COLOR = 0xFFD0D0D0;
    private static final int HIGHLIGHT_COLOR = 0xFF88CC88;
    private static final int WARNING_COLOR = 0xFFFFCC44;
    private static final int ERROR_COLOR = 0xFFFF7777;
    private static final int PROGRESS_BACKGROUND = 0xFF202020;
    private static final int PROGRESS_BORDER = 0xFF606060;
    private static final int LINE_HEIGHT = 12;
    private static final int SCROLLBAR_WIDTH = 6;

    private final GuiScreen parent;
    private final List<Line> lines = new ArrayList<Line>();
    private final Map<Integer, String> tooltips =
            new HashMap<Integer, String>();

    private volatile FfmpegBundleManager.DownloadProgress
            currentProgress = FfmpegBundleManager.getProgress();
    private String testReport;

    private int panelLeft;
    private int panelTop;
    private int panelWidth;
    private int panelBottom;
    private int panelBodyTop;
    private int panelBodyBottom;
    private int contentHeight;
    private int scrollOffset;
    private int mainRowY;
    private int toolbarRowY;
    private int progressBarTop;
    private int textWrapWidth = 400;

    private GuiButton downloadButton;
    private GuiButton cancelButton;
    private GuiButton openFolderButton;
    private boolean listenerRegistered;
    private boolean availabilityPersisted;
    private boolean draggingScrollbar;
    private boolean draggingBody;
    private int lastDragY;

    public FfmpegDownloadScreen(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        buttonList.clear();
        tooltips.clear();
        downloadButton = null;
        cancelButton = null;
        openFolderButton = null;

        panelWidth = Math.min(
                width - 16,
                Math.max(
                        320,
                        Math.min((int) (width * 0.9D), 640)));
        panelWidth = Math.max(200, panelWidth);
        panelLeft = (width - panelWidth) / 2;
        panelTop = Math.max(8, (int) (height * 0.08D));
        panelBottom = Math.min(
                height - 8,
                panelTop
                        + Math.max(
                                280,
                                (int) (height * 0.84D)));
        textWrapWidth = Math.max(80, panelWidth - 28);

        rebuildLines();

        panelBodyTop = panelTop + 22;
        mainRowY = panelBottom - 28;
        toolbarRowY = mainRowY - 26;
        progressBarTop = toolbarRowY - 26;
        panelBodyBottom = progressBarTop - 14;
        contentHeight = lines.size() * LINE_HEIGHT;
        scrollOffset = clamp(
                scrollOffset, 0, maximumScroll());

        int buttonWidth = Math.min(
                160, Math.max(80, (panelWidth - 20) / 2));
        int buttonHeight = 20;
        int centerX = panelLeft + panelWidth / 2;
        boolean automatic =
                FfmpegBundleManager.isAutoDownloadSupported();
        boolean downloading =
                FfmpegBundleManager.isDownloading();
        boolean available = isFfmpegAvailable();

        if (available) {
            cancelButton = new GuiButton(
                    CANCEL_ID,
                    centerX - buttonWidth / 2,
                    mainRowY,
                    buttonWidth,
                    buttonHeight,
                    "Close");
            buttonList.add(cancelButton);
        } else if (automatic) {
            String label = downloading
                    ? "Downloading\u2026"
                    : "Download FFmpeg ("
                            + FfmpegBundleManager
                                    .getEstimatedDownloadSize()
                            + ")";
            downloadButton = new GuiButton(
                    DOWNLOAD_ID,
                    centerX - buttonWidth - 4,
                    mainRowY,
                    buttonWidth,
                    buttonHeight,
                    label);
            downloadButton.enabled = !downloading;
            buttonList.add(downloadButton);
            tooltips.put(
                    DOWNLOAD_ID,
                    "Downloads FFmpeg from "
                            + FfmpegBundleManager
                                    .getDownloadSourceDescription()
                            + ". The binary will be stored at "
                            + FfmpegBundleManager
                                    .getBundleDirectory()
                            + ". The archive is HTTPS-authenticated and "
                            + "strictly verified against the upstream hash "
                            + "where one is published.");

            cancelButton = new GuiButton(
                    CANCEL_ID,
                    centerX + 4,
                    mainRowY,
                    buttonWidth,
                    buttonHeight,
                    downloading ? "Hide" : "Cancel");
            buttonList.add(cancelButton);
        } else {
            openFolderButton = new GuiButton(
                    OPEN_FOLDER_ID,
                    centerX - buttonWidth - 4,
                    mainRowY,
                    buttonWidth,
                    buttonHeight,
                    "Open FFmpeg Folder");
            buttonList.add(openFolderButton);
            cancelButton = new GuiButton(
                    CANCEL_ID,
                    centerX + 4,
                    mainRowY,
                    buttonWidth,
                    buttonHeight,
                    "Close");
            buttonList.add(cancelButton);
        }

        if (!downloading) {
            int gap = 6;
            int totalWidth = panelWidth - 24;
            int third = (totalWidth - gap * 2) / 3;
            int buttonX = panelLeft + 12;
            buttonList.add(new GuiButton(
                    TEST_ID,
                    buttonX,
                    toolbarRowY,
                    third,
                    buttonHeight,
                    "Test FFmpeg"));
            buttonList.add(new GuiButton(
                    PASTE_PATH_ID,
                    buttonX + third + gap,
                    toolbarRowY,
                    third,
                    buttonHeight,
                    "Paste FFmpeg Path"));
            buttonList.add(new GuiButton(
                    COPY_COMMAND_ID,
                    buttonX + (third + gap) * 2,
                    toolbarRowY,
                    totalWidth - (third + gap) * 2,
                    buttonHeight,
                    "Copy Termux Cmd"));
            tooltips.put(
                    TEST_ID,
                    "Re-probe every configured FFmpeg location and show "
                            + "what was found or why detection failed.");
            tooltips.put(
                    PASTE_PATH_ID,
                    "Set the FFmpeg path from the clipboard, then test it "
                            + "immediately.");
            tooltips.put(
                    COPY_COMMAND_ID,
                    "Copy 'pkg install ffmpeg' for use in Termux.");
        }

        if (!listenerRegistered) {
            FfmpegBundleManager.addProgressListener(this);
            listenerRegistered = true;
        }
        currentProgress = FfmpegBundleManager.getProgress();
    }

    @Override
    protected void actionPerformed(GuiButton button)
            throws IOException {
        if (button == null || !button.enabled) {
            return;
        }
        switch (button.id) {
            case DOWNLOAD_ID:
                startDownload();
                break;
            case CANCEL_ID:
                closeToParent();
                break;
            case OPEN_FOLDER_ID:
                openBundleFolder();
                break;
            case TEST_ID:
                runTest();
                break;
            case PASTE_PATH_ID:
                pastePathFromClipboard();
                break;
            case COPY_COMMAND_ID:
                copyTermuxCommand();
                break;
            default:
                break;
        }
    }

    private void runTest() {
        testReport = testFfmpegVerbose();
        rebuildAndReinitialize();
    }

    private void copyTermuxCommand() {
        setClipboardString("pkg install ffmpeg");
        testReport = "Copied to clipboard:\n"
                + "  pkg install ffmpeg\n\n"
                + "1. Open Termux (from F-Droid) and paste + run it.\n"
                + "2. Run 'which ffmpeg' and copy the printed path.\n"
                + "3. Come back and click 'Paste FFmpeg Path'.";
        rebuildAndReinitialize();
    }

    private void pastePathFromClipboard() {
        String clipboard = getClipboardString();
        if (isBlank(clipboard)) {
            testReport = "Clipboard is empty.\n"
                    + "Copy your ffmpeg path first, e.g.\n"
                    + "C:\\ffmpeg\\bin\\ffmpeg.exe";
            rebuildAndReinitialize();
            return;
        }

        String path = clipboard.trim();
        RecordableConfig config = RecordableConfig.get();
        config.ffmpegPath = path;
        config.save();
        testReport = "Set FFmpeg path to:\n"
                + "  " + path + "\n\n"
                + testFfmpegVerbose();
        rebuildAndReinitialize();
    }

    private String testFfmpegVerbose() {
        FfmpegBundleManager.invalidateCache();
        FfmpegBundleManager.FfmpegStatus status =
                FfmpegBundleManager.detectFfmpeg();
        if (status.isFound()) {
            return "RESULT: FFmpeg FOUND\n"
                    + "[OK] Executable: "
                    + status.getExecutable()
                    + "\n[OK] "
                    + (isBlank(status.getVersion())
                            ? "Version probe succeeded."
                            : status.getVersion());
        }
        return "RESULT: FFmpeg NOT FOUND\n"
                + "[FAIL] "
                + (isBlank(status.getError())
                        ? "No usable executable was found."
                        : status.getError());
    }

    private void startDownload() {
        if (FfmpegBundleManager.isDownloading()) {
            return;
        }
        if (!FfmpegBundleManager.isAutoDownloadSupported()) {
            testReport = "RESULT: FAILED\n"
                    + "Automatic FFmpeg download is not available on "
                    + PlatformUtils.detectPlatform().getDisplayName()
                    + ".";
            rebuildAndReinitialize();
            return;
        }

        currentProgress = new FfmpegBundleManager.DownloadProgress(
                "starting", 0L, 0L);
        FfmpegBundleManager.downloadAsync().whenComplete(
                (success, failure) -> {
                    Minecraft client = Minecraft.getMinecraft();
                    if (client == null) {
                        return;
                    }
                    client.addScheduledTask(new Runnable() {
                        @Override
                        public void run() {
                            currentProgress =
                                    FfmpegBundleManager.getProgress();
                            if (failure != null) {
                                testReport =
                                        "RESULT: FAILED\n[FAIL] "
                                                + safeMessage(failure);
                            } else if (Boolean.TRUE.equals(success)) {
                                persistBundledExecutable();
                            }
                            rebuildAndReinitialize();
                        }
                    });
                });

        /*
         * downloadAsync marks the backend DOWNLOADING synchronously, so this
         * immediately switches to the exact in-flight footer (Hide only; no
         * diagnostic toolbar) without starting a second request.
         */
        rebuildAndReinitialize();
    }

    private void openBundleFolder() {
        try {
            Files.createDirectories(
                    FfmpegBundleManager.getBundleDirectory());
            if (!PlatformUtils.open(
                    FfmpegBundleManager.getBundleDirectory())) {
                testReport = "Could not open the FFmpeg folder:\n  "
                        + FfmpegBundleManager.getBundleDirectory();
                rebuildAndReinitialize();
            }
        } catch (Exception exception) {
            testReport = "Could not open the FFmpeg folder:\n  "
                    + safeMessage(exception);
            RecordableMod.LOGGER.warn(
                    "[FfmpegDownloadScreen] Could not open folder.",
                    exception);
            rebuildAndReinitialize();
        }
    }

    private void persistBundledExecutable() {
        if (availabilityPersisted) {
            return;
        }
        Path managedExecutable =
                FfmpegBundleManager.getManagedExecutablePath();
        if (!Files.isRegularFile(managedExecutable)) {
            return;
        }

        RecordableConfig config = RecordableConfig.get();
        String previousPath = config.bundledFfmpegPath;
        boolean previousUseBundled = config.useBundledFfmpeg;
        config.bundledFfmpegPath =
                managedExecutable.toString();
        config.useBundledFfmpeg = true;
        FfmpegBundleManager.invalidateCache();
        FfmpegBundleManager.FfmpegStatus detected =
                FfmpegBundleManager.detectFfmpeg();
        if (!detected.isFound()) {
            config.bundledFfmpegPath = previousPath;
            config.useBundledFfmpeg = previousUseBundled;
            FfmpegBundleManager.invalidateCache();
            return;
        }
        config.bundledFfmpegPath = detected.getExecutable();
        config.ffmpegFirstRunShown = true;
        config.save();
        availabilityPersisted = true;
    }

    private void rebuildAndReinitialize() {
        initGui();
    }

    private void rebuildLines() {
        lines.clear();
        boolean available = isFfmpegAvailable();
        boolean automatic =
                FfmpegBundleManager.isAutoDownloadSupported();
        PlatformUtils.Platform platform =
                PlatformUtils.detectPlatform();

        addHeader("FFmpeg Setup");
        addBlank();

        if (available) {
            addHighlight("\u2713 FFmpeg is installed and ready.");
            addBlank();
            addText("Location:");
            FfmpegBundleManager.FfmpegStatus detected =
                    FfmpegBundleManager.detectFfmpeg();
            addText("  " + truncate(
                    detected.isFound()
                            ? detected.getExecutable()
                            : configuredPath(),
                    70));
            addBlank();
            addText("You can close this screen and start recording.");
            return;
        }

        addText("Record-able needs FFmpeg to encode video. To keep this mod");
        addText("Modrinth-friendly we do not bundle the FFmpeg binary.");
        addText("You download it once, from an official upstream, and the");
        addText("mod stores it inside your Minecraft folder.");
        addBlank();

        addHighlight(
                "Platform: " + platform.getDisplayName());
        if (automatic) {
            addText("Source:    "
                    + FfmpegBundleManager
                            .getDownloadSourceDescription());
            addText("Size:      "
                    + FfmpegBundleManager
                            .getEstimatedDownloadSize());
            addText("Saved to:  " + truncate(
                    FfmpegBundleManager
                            .getBundleDirectory()
                            .toString(),
                    60));
            addBlank();
            addHighlight("Integrity:");
            switch (platform) {
                case WINDOWS:
                    addText("  \u2022 Downloaded over HTTPS from gyan.dev");
                    addText("  \u2022 SHA-256 strictly verified against gyan.dev's");
                    addText("    published .sha256 sibling file.");
                    break;
                case LINUX:
                    addText("  \u2022 Downloaded over HTTPS from johnvansickle.com");
                    addText("  \u2022 MD5 strictly verified against the published");
                    addText("    .md5 sibling file.");
                    break;
                case MACOS:
                    addText("  \u2022 Downloaded over HTTPS from evermeet.cx");
                    addText("  \u2022 HTTPS host authentication; no sibling hash");
                    addText("    file is published by the upstream.");
                    break;
                default:
                    break;
            }
            addBlank();
            addText("Click 'Download FFmpeg' to start. The mod will not");
            addText("contact the internet until you do.");
        } else {
            addWarning(
                    "Auto-download not available for this platform.");
            addText(
                    FfmpegBundleManager
                            .getManualInstallInstructions());
        }

        if (FfmpegBundleManager.getStatus()
                == FfmpegBundleManager.Status.ERROR) {
            addBlank();
            addError("Last error: "
                    + (isBlank(FfmpegBundleManager.getLastError())
                            ? "unknown"
                            : FfmpegBundleManager.getLastError()));
            addText(
                    "If the problem persists, see manual install above.");
        }
        appendManualOverrideSection();
    }

    private void appendManualOverrideSection() {
        addBlank();
        addHeader("Manual override / diagnostics");
        String configured = configuredPath();
        if (!isBlank(configured)) {
            addHighlight("Current FFmpeg path (settings):");
            addText("  " + truncate(configured.trim(), 64));
        } else {
            addText("No manual FFmpeg path set.");
        }
        addText("\u2022 'Paste FFmpeg Path' - set it from the clipboard.");
        addText("\u2022 'Test FFmpeg' - re-probe and show what was tried.");
        addText("\u2022 'Copy Termux Cmd' - copies 'pkg install ffmpeg'.");

        if (!isBlank(testReport)) {
            addBlank();
            addHeader("Last test result");
            String[] reportLines = testReport.split("\n", -1);
            for (String raw : reportLines) {
                String line = raw.replace("\t", "    ");
                int color = line.contains("[OK]")
                        || line.startsWith(
                                "RESULT: FFmpeg FOUND")
                        || line.startsWith("RESULT: OK")
                        ? HIGHLIGHT_COLOR
                        : line.contains("[FAIL]")
                                || line.contains("NOT FOUND")
                                || line.startsWith(
                                        "RESULT: FAILED")
                                ? ERROR_COLOR
                                : TEXT_COLOR;
                if (line.length() == 0) {
                    lines.add(new Line("", color, false));
                } else {
                    for (String wrapped : wrapToWidth(
                            line, textWrapWidth)) {
                        lines.add(
                                new Line(wrapped, color, false));
                    }
                }
            }
        }
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        currentProgress = FfmpegBundleManager.getProgress();
        if (FfmpegBundleManager.getStatus()
                == FfmpegBundleManager.Status.AVAILABLE) {
            persistBundledExecutable();
        }
    }

    @Override
    public void onProgress(
            FfmpegBundleManager.DownloadProgress progress) {
        if (progress != null) {
            currentProgress = progress;
        }
    }

    @Override
    public void drawScreen(
            int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        ThemedPanel.drawMenuBackdrop(width, height);

        int accent = 0xFF000000
                | RecordableConfig.get()
                        .getMenuAccentColorRgb();
        int left = panelLeft - 6;
        int right = panelLeft + panelWidth + 6;
        Gui.drawRect(
                left,
                panelTop - 6,
                right,
                panelBottom,
                PANEL_COLOR);
        Gui.drawRect(
                left,
                panelTop - 6,
                right,
                panelTop - 5,
                accent);
        Gui.drawRect(
                left,
                panelBottom - 1,
                right,
                panelBottom,
                PANEL_BORDER_COLOR);
        Gui.drawRect(
                left,
                panelTop - 6,
                left + 1,
                panelBottom,
                PANEL_BORDER_COLOR);
        Gui.drawRect(
                right - 1,
                panelTop - 6,
                right,
                panelBottom,
                PANEL_BORDER_COLOR);

        drawCenteredString(
                fontRendererObj,
                "Record-able: FFmpeg Setup",
                width / 2,
                panelTop,
                HEADER_COLOR);

        int textLeft = panelLeft + 14;
        for (int index = 0; index < lines.size(); index++) {
            int y = panelBodyTop
                    + index * LINE_HEIGHT
                    - scrollOffset;
            if (y < panelBodyTop - LINE_HEIGHT
                    || y > panelBodyBottom - 2) {
                continue;
            }
            Line line = lines.get(index);
            if (line.text.length() == 0) {
                continue;
            }
            fontRendererObj.drawStringWithShadow(
                    (line.bold ? "\u00A7l" : "")
                            + line.text,
                    textLeft,
                    y,
                    line.color);
        }

        renderScrollbar(accent);
        renderProgress(accent);
        super.drawScreen(mouseX, mouseY, partialTicks);
        renderTooltip(mouseX, mouseY);
    }

    private void renderProgress(int accent) {
        if (!FfmpegBundleManager.isDownloading()) {
            return;
        }
        int barWidth = panelWidth - 36;
        int barHeight = 14;
        int barLeft = panelLeft + 18;
        int barTop = progressBarTop;
        Gui.drawRect(
                barLeft,
                barTop,
                barLeft + barWidth,
                barTop + barHeight,
                PROGRESS_BACKGROUND);
        drawBorder(
                barLeft,
                barTop,
                barLeft + barWidth,
                barTop + barHeight,
                PROGRESS_BORDER);

        FfmpegBundleManager.DownloadProgress snapshot =
                currentProgress == null
                        ? FfmpegBundleManager.getProgress()
                        : currentProgress;
        double fraction = snapshot == null
                ? 0.0D
                : snapshot.getFraction();
        if (fraction > 0.0D) {
            int fillWidth = (int) Math.round(
                    (barWidth - 2)
                            * Math.min(
                                    1.0D,
                                    Math.max(0.0D, fraction)));
            Gui.drawRect(
                    barLeft + 1,
                    barTop + 1,
                    barLeft + 1 + fillWidth,
                    barTop + barHeight - 1,
                    accent);
        }

        String phase = snapshot == null
                ? ""
                : snapshot.getPhase();
        long downloaded = snapshot == null
                ? 0L
                : snapshot.getBytesDownloaded();
        long total = snapshot == null
                ? 0L
                : snapshot.getTotalBytes();
        String label = (isBlank(phase)
                ? ""
                : capitalize(phase) + " \u00B7 ")
                + humanBytes(downloaded)
                + (total > 0L
                        ? " (" + snapshot.displayPercent() + ")"
                        : "");
        fontRendererObj.drawString(
                label,
                barLeft,
                barTop - 11,
                TEXT_COLOR);
    }

    private void renderScrollbar(int accent) {
        int viewportHeight =
                panelBodyBottom - panelBodyTop;
        int maximum = maximumScroll();
        if (maximum <= 0 || viewportHeight <= 0) {
            return;
        }
        int barLeft = panelLeft + panelWidth
                - SCROLLBAR_WIDTH - 2;
        int barRight = barLeft + SCROLLBAR_WIDTH;
        Gui.drawRect(
                barLeft,
                panelBodyTop,
                barRight,
                panelBodyBottom,
                0x40000000);
        int thumbHeight = Math.max(
                28,
                (int) (viewportHeight
                        * (viewportHeight
                                / (double) contentHeight)));
        thumbHeight = Math.min(viewportHeight, thumbHeight);
        int available = viewportHeight - thumbHeight;
        int thumbTop = panelBodyTop
                + (available <= 0
                        ? 0
                        : (int) ((scrollOffset
                                / (double) maximum)
                                * available));
        Gui.drawRect(
                barLeft,
                thumbTop,
                barRight,
                thumbTop + thumbHeight,
                accent);
    }

    private void renderTooltip(int mouseX, int mouseY) {
        for (GuiButton button : buttonList) {
            if (mouseX >= button.xPosition
                    && mouseY >= button.yPosition
                    && mouseX < button.xPosition + button.width
                    && mouseY < button.yPosition + button.height) {
                String tooltip = tooltips.get(button.id);
                if (!isBlank(tooltip)) {
                    drawHoveringText(
                            fontRendererObj
                                    .listFormattedStringToWidth(
                                            tooltip,
                                            Math.min(
                                                    300,
                                                    Math.max(
                                                            120,
                                                            width - 40))),
                            mouseX,
                            mouseY);
                }
                return;
            }
        }
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0 && maximumScroll() > 0) {
            scrollOffset = clamp(
                    scrollOffset
                            + (wheel > 0 ? -20 : 20),
                    0,
                    maximumScroll());
        }
    }

    @Override
    protected void mouseClicked(
            int mouseX, int mouseY, int mouseButton)
            throws IOException {
        if (mouseButton == 0
                && isOverScrollbar(mouseX, mouseY)) {
            draggingScrollbar = true;
            lastDragY = mouseY;
            scrollToMouse(mouseY);
            return;
        }
        if (mouseButton == 0
                && isContentScrollable()
                && isOverBody(mouseX, mouseY)) {
            draggingBody = true;
            lastDragY = mouseY;
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void mouseClickMove(
            int mouseX,
            int mouseY,
            int clickedMouseButton,
            long timeSinceLastClick) {
        if (clickedMouseButton == 0
                && draggingScrollbar) {
            scrollToMouse(mouseY);
            lastDragY = mouseY;
            return;
        }
        if (clickedMouseButton == 0 && draggingBody) {
            panBy(mouseY - lastDragY);
            lastDragY = mouseY;
            return;
        }
        super.mouseClickMove(
                mouseX,
                mouseY,
                clickedMouseButton,
                timeSinceLastClick);
    }

    @Override
    protected void mouseReleased(
            int mouseX, int mouseY, int state) {
        draggingScrollbar = false;
        draggingBody = false;
        super.mouseReleased(mouseX, mouseY, state);
    }

    private boolean isOverScrollbar(
            int mouseX, int mouseY) {
        if (maximumScroll() <= 0) {
            return false;
        }
        int barLeft = panelLeft + panelWidth
                - SCROLLBAR_WIDTH - 2;
        int barRight = barLeft + SCROLLBAR_WIDTH;
        return mouseX >= barLeft - 8
                && mouseX <= barRight + 6
                && mouseY >= panelBodyTop
                && mouseY <= panelBodyBottom;
    }

    private boolean isContentScrollable() {
        return maximumScroll() > 0;
    }

    private boolean isOverBody(int mouseX, int mouseY) {
        return mouseX >= panelLeft
                && mouseX <= panelLeft + panelWidth
                && mouseY >= panelBodyTop
                && mouseY <= panelBodyBottom;
    }

    private void panBy(int deltaY) {
        scrollOffset = clamp(
                scrollOffset - deltaY,
                0,
                maximumScroll());
    }

    private void scrollToMouse(int mouseY) {
        int viewportHeight =
                panelBodyBottom - panelBodyTop;
        int maximum = maximumScroll();
        if (maximum <= 0 || viewportHeight <= 0) {
            return;
        }
        int thumbHeight = Math.max(
                28,
                (int) (viewportHeight
                        * (viewportHeight
                                / (double) contentHeight)));
        thumbHeight = Math.min(viewportHeight, thumbHeight);
        int available = viewportHeight - thumbHeight;
        if (available <= 0) {
            scrollOffset = 0;
            return;
        }
        double relative = (mouseY
                - panelBodyTop
                - thumbHeight / 2.0D)
                / available;
        relative = Math.max(
                0.0D, Math.min(1.0D, relative));
        scrollOffset =
                (int) Math.round(relative * maximum);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode)
            throws IOException {
        int viewport =
                panelBodyBottom - panelBodyTop;
        if (keyCode == Keyboard.KEY_ESCAPE) {
            closeToParent();
            return;
        }
        if (keyCode == Keyboard.KEY_DOWN) {
            scrollOffset = clamp(
                    scrollOffset + LINE_HEIGHT,
                    0,
                    maximumScroll());
            return;
        }
        if (keyCode == Keyboard.KEY_UP) {
            scrollOffset = clamp(
                    scrollOffset - LINE_HEIGHT,
                    0,
                    maximumScroll());
            return;
        }
        if (keyCode == Keyboard.KEY_PRIOR) {
            scrollOffset = clamp(
                    scrollOffset - viewport,
                    0,
                    maximumScroll());
            return;
        }
        if (keyCode == Keyboard.KEY_NEXT) {
            scrollOffset = clamp(
                    scrollOffset + viewport,
                    0,
                    maximumScroll());
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void onGuiClosed() {
        if (listenerRegistered) {
            FfmpegBundleManager.removeProgressListener(this);
            listenerRegistered = false;
        }
        draggingScrollbar = false;
        draggingBody = false;
        super.onGuiClosed();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private void closeToParent() {
        if (mc != null) {
            mc.displayGuiScreen(parent);
        }
    }

    private int maximumScroll() {
        return Math.max(
                0,
                contentHeight
                        - Math.max(
                                1,
                                panelBodyBottom - panelBodyTop));
    }

    private static boolean isFfmpegAvailable() {
        return FfmpegBundleManager.getStatus()
                == FfmpegBundleManager.Status.AVAILABLE;
    }

    private static String configuredPath() {
        RecordableConfig config = RecordableConfig.get();
        if (!isBlank(config.ffmpegPath)) {
            return config.ffmpegPath;
        }
        if (!isBlank(config.bundledFfmpegPath)) {
            return config.bundledFfmpegPath;
        }
        return "";
    }

    private void addHeader(String text) {
        addWrapped(text, HEADER_COLOR, true);
    }

    private void addText(String text) {
        addWrapped(text, TEXT_COLOR, false);
    }

    private void addHighlight(String text) {
        addWrapped(text, HIGHLIGHT_COLOR, false);
    }

    private void addWarning(String text) {
        addWrapped(text, WARNING_COLOR, false);
    }

    private void addError(String text) {
        addWrapped(text, ERROR_COLOR, false);
    }

    private void addBlank() {
        lines.add(new Line("", TEXT_COLOR, false));
    }

    private void addWrapped(
            String text, int color, boolean bold) {
        if (isBlank(text)) {
            lines.add(new Line("", color, bold));
            return;
        }
        String[] paragraphs = text.split("\n", -1);
        for (String paragraph : paragraphs) {
            if (paragraph.length() == 0) {
                lines.add(new Line("", color, bold));
                continue;
            }
            for (String wrapped : wrapToWidth(
                    paragraph, textWrapWidth)) {
                lines.add(new Line(wrapped, color, bold));
            }
        }
    }

    private List<String> wrapToWidth(
            String text, int maximumWidth) {
        List<String> output = new ArrayList<String>();
        if (text == null || text.length() == 0) {
            output.add("");
            return output;
        }
        if (maximumWidth <= 8
                || fontRendererObj.getStringWidth(text)
                        <= maximumWidth) {
            output.add(text);
            return output;
        }

        int indentCount = 0;
        while (indentCount < text.length()
                && text.charAt(indentCount) == ' ') {
            indentCount++;
        }
        String indent = text.substring(0, indentCount);
        String continuationIndent = indent + "  ";
        String[] words =
                text.substring(indentCount).split(" ");
        String currentIndent = indent;
        StringBuilder line =
                new StringBuilder(currentIndent);
        boolean hasWord = false;

        for (String rawWord : words) {
            String word = rawWord;
            if (word.length() == 0) {
                continue;
            }
            while (fontRendererObj.getStringWidth(
                    currentIndent + word) > maximumWidth
                    && word.length() > 1) {
                if (hasWord) {
                    output.add(line.toString());
                    currentIndent = continuationIndent;
                    line.setLength(0);
                    line.append(currentIndent);
                    hasWord = false;
                }
                int fit = 1;
                while (fit < word.length()
                        && fontRendererObj.getStringWidth(
                                currentIndent
                                        + word.substring(
                                                0, fit + 1))
                                <= maximumWidth) {
                    fit++;
                }
                output.add(
                        currentIndent + word.substring(0, fit));
                word = word.substring(fit);
                currentIndent = continuationIndent;
                line.setLength(0);
                line.append(currentIndent);
            }

            if (!hasWord) {
                line.setLength(0);
                line.append(currentIndent).append(word);
                hasWord = true;
            } else if (fontRendererObj.getStringWidth(
                    line + " " + word) <= maximumWidth) {
                line.append(' ').append(word);
            } else {
                output.add(line.toString());
                currentIndent = continuationIndent;
                line.setLength(0);
                line.append(currentIndent).append(word);
                hasWord = true;
            }
        }
        if (hasWord) {
            output.add(line.toString());
        }
        if (output.isEmpty()) {
            output.add(text);
        }
        return output;
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

    private static String truncate(
            String value, int maximumLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maximumLength
                ? value
                : "\u2026"
                        + value.substring(
                                value.length()
                                        - maximumLength + 1);
    }

    private static String capitalize(String value) {
        if (isBlank(value)) {
            return "";
        }
        return Character.toUpperCase(value.charAt(0))
                + value.substring(1);
    }

    private static String humanBytes(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024L) {
            return String.format(
                    Locale.ROOT,
                    "%.1f KB",
                    Double.valueOf(bytes / 1024.0D));
        }
        return String.format(
                Locale.ROOT,
                "%.1f MB",
                Double.valueOf(
                        bytes / (1024.0D * 1024.0D)));
    }

    private static String safeMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current != null
                && current.getCause() != null
                && current.getCause() != current) {
            current = current.getCause();
        }
        if (current == null) {
            return "unknown error";
        }
        String message = current.getMessage();
        return isBlank(message)
                ? current.getClass().getSimpleName()
                : message;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static int clamp(
            int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static final class Line {
        private final String text;
        private final int color;
        private final boolean bold;

        private Line(String text, int color, boolean bold) {
            this.text = text == null ? "" : text;
            this.color = color;
            this.bold = bold;
        }
    }
}
