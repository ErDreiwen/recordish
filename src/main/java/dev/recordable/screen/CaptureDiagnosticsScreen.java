package dev.recordable.screen;

import dev.recordable.CaptureDiagnostics;
import dev.recordable.RecordableConfig;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Non-blocking capture diagnostics UI for Forge 1.8.9.
 *
 * <p>GPU readback remains owned by the normal render hook, while external
 * probes and report file writes run on daemon workers. The screen only polls
 * immutable results and updates widgets on the Minecraft client thread.</p>
 */
public final class CaptureDiagnosticsScreen extends GuiScreen {
    private static final int DONE_ID = 1;
    private static final int REFRESH_ID = 2;
    private static final int SELF_TEST_ID = 3;
    private static final int COPY_ID = 4;
    private static final int SAVE_ID = 5;
    private static final int SETTINGS_ID = 6;

    private static final int CONTENT_TOP = 64;
    private static final int FOOTER_HEIGHT = 66;
    private static final int LINE_HEIGHT = 10;
    private static final DateTimeFormatter FILE_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final GuiScreen parent;
    private final List<DisplayLine> displayLines =
            new ArrayList<DisplayLine>();

    private volatile CaptureDiagnostics.EnvironmentReport report;
    private volatile boolean reportLoading;
    private volatile boolean reportSaving;
    private volatile int reportGeneration;

    private GuiButton refreshButton;
    private GuiButton selfTestButton;
    private GuiButton copyButton;
    private GuiButton saveButton;

    private CaptureDiagnostics.EnvironmentReport renderedReport;
    private int renderedWidth = -1;
    private int scrollPixels;
    private boolean watchingSelfTest;
    private boolean ownsSelfTest;
    private String transientStatus = "";
    private long transientStatusUntil;

    public CaptureDiagnosticsScreen(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        buttonList.clear();

        int gap = 4;
        int margin = 8;
        int buttonWidth = Math.max(
                54,
                (width - margin * 2 - gap * 2) / 3);
        int firstRowY = height - 49;
        int secondRowY = height - 25;

        buttonList.add(new GuiButton(
                DONE_ID,
                margin,
                firstRowY,
                buttonWidth,
                20,
                parent == null ? "Done" : "Back"));
        refreshButton = new GuiButton(
                REFRESH_ID,
                margin + buttonWidth + gap,
                firstRowY,
                buttonWidth,
                20,
                "Refresh");
        buttonList.add(refreshButton);
        selfTestButton = new GuiButton(
                SELF_TEST_ID,
                margin + (buttonWidth + gap) * 2,
                firstRowY,
                buttonWidth,
                20,
                "GPU Self-Test");
        buttonList.add(selfTestButton);

        copyButton = new GuiButton(
                COPY_ID,
                margin,
                secondRowY,
                buttonWidth,
                20,
                "Copy Report");
        buttonList.add(copyButton);
        saveButton = new GuiButton(
                SAVE_ID,
                margin + buttonWidth + gap,
                secondRowY,
                buttonWidth,
                20,
                "Save Report");
        buttonList.add(saveButton);
        buttonList.add(new GuiButton(
                SETTINGS_ID,
                margin + (buttonWidth + gap) * 2,
                secondRowY,
                buttonWidth,
                20,
                "Settings"));

        renderedReport = null;
        renderedWidth = -1;
        watchingSelfTest = CaptureDiagnostics.isSelfTestPending();
        syncButtons();
        if (report == null && !reportLoading) {
            refreshReportAsync();
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button == null || !button.enabled) {
            return;
        }

        switch (button.id) {
            case DONE_ID:
                mc.displayGuiScreen(parent);
                break;
            case REFRESH_ID:
                refreshReportAsync();
                break;
            case SELF_TEST_ID:
                toggleSelfTest();
                break;
            case COPY_ID:
                copyReport();
                break;
            case SAVE_ID:
                saveReportAsync();
                break;
            case SETTINGS_ID:
                mc.displayGuiScreen(new RecordableSettingsScreen(this));
                break;
            default:
                break;
        }
    }

    private void toggleSelfTest() {
        if (CaptureDiagnostics.isSelfTestPending()) {
            CaptureDiagnostics.cancelSelfTest();
            setStatus("Cancelling the GPU self-test...");
            watchingSelfTest = true;
            return;
        }

        if (CaptureDiagnostics.requestSelfTest()) {
            ownsSelfTest = true;
            watchingSelfTest = true;
            setStatus("GPU self-test queued for the next rendered frame.");
        } else {
            watchingSelfTest = CaptureDiagnostics.isSelfTestPending();
            setStatus("A GPU self-test is already running.");
        }
        syncButtons();
    }

    @Override
    public void updateScreen() {
        super.updateScreen();

        boolean pending = CaptureDiagnostics.isSelfTestPending();
        if (watchingSelfTest && !pending) {
            watchingSelfTest = false;
            ownsSelfTest = false;
            setStatus(CaptureDiagnostics.getSelfTestStatus());
            refreshReportAsync();
        }
        syncButtons();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        ensureDisplayLines();

        drawCenteredString(
                fontRendererObj,
                "Record-able Capture Diagnostics",
                width / 2,
                9,
                0xFFFFFFFF);
        drawVerdict();
        drawReport();
        drawStatus();

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void drawVerdict() {
        int left = 8;
        int right = Math.max(left + 1, width - 8);
        int top = 25;
        int bottom = 57;
        CaptureDiagnostics.EnvironmentReport current = report;
        CaptureDiagnostics.Status verdict = current == null
                ? CaptureDiagnostics.Status.INFO
                : current.getVerdict();

        Gui.drawRect(left, top, right, bottom, 0xA0000000);
        Gui.drawRect(left, top, left + 3, bottom, statusColor(verdict));

        String headline = reportLoading
                ? "Refreshing environment checks..."
                : current == null
                        ? "No diagnostics report is available."
                        : "Verdict: " + verdict;
        fontRendererObj.drawStringWithShadow(
                fontRendererObj.trimStringToWidth(
                        headline,
                        Math.max(20, right - left - 14)),
                left + 8,
                top + 5,
                current == null ? 0xFFB8C7D9 : statusColor(verdict));

        String summary = current == null
                ? "Run Refresh to inspect capture, FFmpeg, audio, and storage."
                : CaptureDiagnostics.verdictSummary(verdict);
        fontRendererObj.drawString(
                fontRendererObj.trimStringToWidth(
                        summary,
                        Math.max(20, right - left - 14)),
                left + 8,
                top + 18,
                0xFFC8C8C8);
    }

    private void drawReport() {
        int left = 8;
        int right = Math.max(left + 1, width - 8);
        int top = CONTENT_TOP;
        int bottom = Math.max(top + 1, height - FOOTER_HEIGHT);
        int textRight = right - 9;

        Gui.drawRect(left, top, right, bottom, 0x85000000);
        int maximum = maximumScrollPixels();
        scrollPixels = clamp(scrollPixels, 0, maximum);

        int y = top + 5 - scrollPixels;
        for (DisplayLine line : displayLines) {
            if (y + LINE_HEIGHT > top + 2 && y < bottom - 2) {
                fontRendererObj.drawString(
                        line.text,
                        left + 7 + line.indent,
                        y,
                        line.color);
            }
            y += LINE_HEIGHT;
        }

        if (displayLines.isEmpty() && !reportLoading) {
            drawCenteredString(
                    fontRendererObj,
                    "No checks to display.",
                    width / 2,
                    top + 12,
                    0xFFAAAAAA);
        }

        if (maximum > 0) {
            int trackTop = top + 3;
            int trackBottom = bottom - 3;
            Gui.drawRect(
                    textRight + 2,
                    trackTop,
                    textRight + 5,
                    trackBottom,
                    0xFF303030);
            int trackHeight = Math.max(1, trackBottom - trackTop);
            int viewport = Math.max(1, bottom - top - 10);
            int content = Math.max(viewport, contentHeight());
            int thumbHeight = Math.max(
                    10,
                    viewport * trackHeight / content);
            int thumbTravel = Math.max(0, trackHeight - thumbHeight);
            int thumbTop = trackTop
                    + (maximum == 0
                            ? 0
                            : scrollPixels * thumbTravel / maximum);
            Gui.drawRect(
                    textRight + 2,
                    thumbTop,
                    textRight + 5,
                    thumbTop + thumbHeight,
                    0xFF9AA9B8);
        }
    }

    private void drawStatus() {
        String status;
        if (CaptureDiagnostics.isSelfTestPending()) {
            status = "GPU self-test: "
                    + CaptureDiagnostics.getSelfTestStatus();
        } else if (reportSaving) {
            status = "Saving diagnostics report...";
        } else if (reportLoading) {
            status = "Running environment probes off the render thread...";
        } else if (System.currentTimeMillis() < transientStatusUntil) {
            status = transientStatus;
        } else {
            status = "Mouse wheel or Page Up/Down scrolls the checks.";
        }

        fontRendererObj.drawStringWithShadow(
                fontRendererObj.trimStringToWidth(
                        status == null ? "" : status,
                        Math.max(20, width - 18)),
                9,
                height - 62,
                0xFFB8C7D9);
    }

    private void ensureDisplayLines() {
        CaptureDiagnostics.EnvironmentReport current = report;
        int availableWidth = Math.max(40, width - 42);
        if (current == renderedReport && renderedWidth == availableWidth) {
            return;
        }

        renderedReport = current;
        renderedWidth = availableWidth;
        displayLines.clear();
        if (current == null) {
            scrollPixels = 0;
            return;
        }

        for (CaptureDiagnostics.Check check : current.getChecks()) {
            if (check == null) {
                continue;
            }
            CaptureDiagnostics.Status status = check.getStatus();
            displayLines.add(new DisplayLine(
                    "[" + status + "] " + check.getLabel(),
                    statusColor(status),
                    0));

            String detail = check.getDetail();
            List<String> wrapped = detail == null || detail.isEmpty()
                    ? Collections.singletonList("")
                    : fontRendererObj.listFormattedStringToWidth(
                            detail,
                            Math.max(20, availableWidth - 9));
            for (String line : wrapped) {
                displayLines.add(new DisplayLine(
                        line,
                        0xFFC8C8C8,
                        9));
            }
            displayLines.add(new DisplayLine("", 0xFFFFFFFF, 0));
        }
        scrollPixels = clamp(
                scrollPixels,
                0,
                maximumScrollPixels());
    }

    private void refreshReportAsync() {
        final int generation = ++reportGeneration;
        reportLoading = true;
        syncButtons();

        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                CaptureDiagnostics.EnvironmentReport built = null;
                String failure = null;
                try {
                    built = CaptureDiagnostics.buildEnvironmentReport();
                } catch (Throwable throwable) {
                    failure = safeMessage(throwable);
                }

                final CaptureDiagnostics.EnvironmentReport completed = built;
                final String error = failure;
                Minecraft.getMinecraft().addScheduledTask(new Runnable() {
                    @Override
                    public void run() {
                        if (generation != reportGeneration) {
                            return;
                        }
                        reportLoading = false;
                        if (completed != null) {
                            report = completed;
                            renderedReport = null;
                            scrollPixels = 0;
                            setStatus("Diagnostics report refreshed.");
                        } else {
                            setStatus("Diagnostics failed: " + error);
                        }
                        syncButtons();
                    }
                });
            }
        }, "Recordable-Diagnostics");
        worker.setDaemon(true);
        worker.start();
    }

    private void copyReport() {
        CaptureDiagnostics.EnvironmentReport current = report;
        if (current == null) {
            setStatus("There is no report to copy yet.");
            return;
        }
        try {
            GuiScreen.setClipboardString(current.toText());
            setStatus("Diagnostics report copied to the clipboard.");
        } catch (Throwable throwable) {
            setStatus("Clipboard unavailable: " + safeMessage(throwable));
        }
    }

    private void saveReportAsync() {
        final CaptureDiagnostics.EnvironmentReport current = report;
        if (current == null || reportSaving) {
            return;
        }
        final String text = current.toText();
        reportSaving = true;
        syncButtons();

        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                Path saved = null;
                String failure = null;
                try {
                    Path directory =
                            RecordableConfig.get().getOutputDirectory();
                    Files.createDirectories(directory);
                    saved = directory.resolve(
                            "recordable-diagnostics-"
                                    + LocalDateTime.now().format(FILE_TIME)
                                    + ".txt");
                    Files.write(
                            saved,
                            text.getBytes(StandardCharsets.UTF_8),
                            StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.WRITE);
                } catch (Throwable throwable) {
                    failure = safeMessage(throwable);
                }

                final Path completed = saved;
                final String error = failure;
                Minecraft.getMinecraft().addScheduledTask(new Runnable() {
                    @Override
                    public void run() {
                        reportSaving = false;
                        setStatus(completed == null
                                ? "Could not save report: " + error
                                : "Saved report to "
                                        + completed.toAbsolutePath());
                        syncButtons();
                    }
                });
            }
        }, "Recordable-DiagnosticsSave");
        worker.setDaemon(true);
        worker.start();
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode)
            throws IOException {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            mc.displayGuiScreen(parent);
            return;
        }
        if (keyCode == Keyboard.KEY_PRIOR) {
            scrollBy(-viewportHeight());
            return;
        }
        if (keyCode == Keyboard.KEY_NEXT) {
            scrollBy(viewportHeight());
            return;
        }
        if (keyCode == Keyboard.KEY_HOME) {
            scrollPixels = 0;
            return;
        }
        if (keyCode == Keyboard.KEY_END) {
            scrollPixels = maximumScrollPixels();
            return;
        }
        if (keyCode == Keyboard.KEY_C
                && (Keyboard.isKeyDown(Keyboard.KEY_LCONTROL)
                        || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL))) {
            copyReport();
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            scrollBy(wheel > 0 ? -LINE_HEIGHT * 3 : LINE_HEIGHT * 3);
        }
    }

    @Override
    public void onGuiClosed() {
        if (ownsSelfTest && CaptureDiagnostics.isSelfTestPending()) {
            CaptureDiagnostics.cancelSelfTest();
        }
        super.onGuiClosed();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private void syncButtons() {
        if (refreshButton != null) {
            refreshButton.enabled = !reportLoading;
        }
        if (selfTestButton != null) {
            boolean pending = CaptureDiagnostics.isSelfTestPending();
            selfTestButton.displayString = pending
                    ? "Cancel Test"
                    : "GPU Self-Test";
        }
        if (copyButton != null) {
            copyButton.enabled = report != null;
        }
        if (saveButton != null) {
            saveButton.enabled = report != null && !reportSaving;
        }
    }

    private void setStatus(String message) {
        transientStatus = message == null ? "" : message;
        transientStatusUntil = System.currentTimeMillis() + 6000L;
    }

    private int contentHeight() {
        return displayLines.size() * LINE_HEIGHT + 10;
    }

    private int viewportHeight() {
        return Math.max(
                LINE_HEIGHT,
                height - FOOTER_HEIGHT - CONTENT_TOP - 10);
    }

    private int maximumScrollPixels() {
        return Math.max(0, contentHeight() - viewportHeight());
    }

    private void scrollBy(int amount) {
        scrollPixels = clamp(
                scrollPixels + amount,
                0,
                maximumScrollPixels());
    }

    private static int statusColor(CaptureDiagnostics.Status status) {
        if (status == CaptureDiagnostics.Status.OK) {
            return 0xFF55DD77;
        }
        if (status == CaptureDiagnostics.Status.WARN) {
            return 0xFFFFCC55;
        }
        if (status == CaptureDiagnostics.Status.FAIL) {
            return 0xFFFF6666;
        }
        return 0xFF77BBDD;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
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
        return message == null || message.trim().isEmpty()
                ? current.getClass().getSimpleName()
                : message;
    }

    private static final class DisplayLine {
        private final String text;
        private final int color;
        private final int indent;

        private DisplayLine(String text, int color, int indent) {
            this.text = text == null ? "" : text;
            this.color = color;
            this.indent = indent;
        }
    }
}
