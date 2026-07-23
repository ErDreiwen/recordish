package dev.recordable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Capture-pipeline and environment diagnostics for Forge 1.8.9.
 */
public final class CaptureDiagnostics {
    public enum Status {
        OK,
        WARN,
        FAIL,
        INFO
    }

    public static final class Check {
        private final Status status;
        private final String label;
        private final String detail;

        public Check(Status status, String label, String detail) {
            this.status = status == null ? Status.INFO : status;
            this.label = label == null ? "" : label;
            this.detail = detail == null ? "" : detail;
        }

        public Status status() { return status; }
        public String label() { return label; }
        public String detail() { return detail; }
        public Status getStatus() { return status; }
        public String getLabel() { return label; }
        public String getDetail() { return detail; }
    }

    public static final class LiveStats {
        private final long framesProduced;
        private final long blackFrames;
        private final int consecutiveBlack;
        private final boolean persistentBlack;
        private final String sourceName;
        private final int sourceWidth;
        private final int sourceHeight;
        private final int renderTargetWidth;
        private final int renderTargetHeight;
        private final boolean sizeMismatch;
        private final boolean usingPbos;
        private final int pboMapFailures;
        private final int lastGlError;

        public LiveStats(
                long framesProduced,
                long blackFrames,
                int consecutiveBlack,
                boolean persistentBlack,
                String sourceName,
                int sourceWidth,
                int sourceHeight,
                int renderTargetWidth,
                int renderTargetHeight,
                boolean sizeMismatch) {
            this(
                    framesProduced,
                    blackFrames,
                    consecutiveBlack,
                    persistentBlack,
                    sourceName,
                    sourceWidth,
                    sourceHeight,
                    renderTargetWidth,
                    renderTargetHeight,
                    sizeMismatch,
                    false,
                    0,
                    0);
        }

        public LiveStats(
                long framesProduced,
                long blackFrames,
                int consecutiveBlack,
                boolean persistentBlack,
                String sourceName,
                int sourceWidth,
                int sourceHeight,
                int renderTargetWidth,
                int renderTargetHeight,
                boolean sizeMismatch,
                boolean usingPbos,
                int pboMapFailures,
                int lastGlError) {
            this.framesProduced = Math.max(0L, framesProduced);
            this.blackFrames = Math.max(0L, blackFrames);
            this.consecutiveBlack = Math.max(0, consecutiveBlack);
            this.persistentBlack = persistentBlack;
            this.sourceName = sourceName == null ? "Framebuffer" : sourceName;
            this.sourceWidth = sourceWidth;
            this.sourceHeight = sourceHeight;
            this.renderTargetWidth = renderTargetWidth;
            this.renderTargetHeight = renderTargetHeight;
            this.sizeMismatch = sizeMismatch;
            this.usingPbos = usingPbos;
            this.pboMapFailures = Math.max(0, pboMapFailures);
            this.lastGlError = lastGlError;
        }

        public long framesProduced() { return framesProduced; }
        public long blackFrames() { return blackFrames; }
        public int consecutiveBlack() { return consecutiveBlack; }
        public boolean persistentBlack() { return persistentBlack; }
        public String sourceName() { return sourceName; }
        public int sourceWidth() { return sourceWidth; }
        public int sourceHeight() { return sourceHeight; }
        public int renderTargetWidth() { return renderTargetWidth; }
        public int renderTargetHeight() { return renderTargetHeight; }
        public boolean sizeMismatch() { return sizeMismatch; }
        public boolean usingPbos() { return usingPbos; }
        public int pboMapFailures() { return pboMapFailures; }
        public int lastGlError() { return lastGlError; }
        public long getFramesProduced() { return framesProduced; }
        public long getBlackFrames() { return blackFrames; }
        public int getConsecutiveBlack() { return consecutiveBlack; }
        public boolean isPersistentBlack() { return persistentBlack; }
        public String getSourceName() { return sourceName; }
        public int getSourceWidth() { return sourceWidth; }
        public int getSourceHeight() { return sourceHeight; }
        public int getRenderTargetWidth() { return renderTargetWidth; }
        public int getRenderTargetHeight() { return renderTargetHeight; }
        public boolean isSizeMismatch() { return sizeMismatch; }
        public boolean isUsingPbos() { return usingPbos; }
        public int getPboMapFailures() { return pboMapFailures; }
        public int getLastGlError() { return lastGlError; }
    }

    public static final class SelfTestResult {
        private final boolean producedFrame;
        private final boolean valid;
        private final boolean black;
        private final double averageBrightness;
        private final double blackPixelRatio;
        private final int width;
        private final int height;
        private final String note;

        public SelfTestResult(
                boolean producedFrame,
                boolean black,
                double averageBrightness,
                int width,
                int height,
                String note) {
            this(
                    producedFrame,
                    producedFrame && !black,
                    black,
                    averageBrightness,
                    black ? 1.0D : 0.0D,
                    width,
                    height,
                    note);
        }

        public SelfTestResult(
                boolean producedFrame,
                boolean valid,
                boolean black,
                double averageBrightness,
                double blackPixelRatio,
                int width,
                int height,
                String note) {
            this.producedFrame = producedFrame;
            this.valid = valid;
            this.black = black;
            this.averageBrightness = averageBrightness;
            this.blackPixelRatio = blackPixelRatio;
            this.width = width;
            this.height = height;
            this.note = note == null ? "" : note;
        }

        public boolean producedFrame() { return producedFrame; }
        public boolean valid() { return valid; }
        public boolean black() { return black; }
        public double avgBrightness() { return averageBrightness; }
        public double blackPixelRatio() { return blackPixelRatio; }
        public int width() { return width; }
        public int height() { return height; }
        public String note() { return note; }
        public boolean isProducedFrame() { return producedFrame; }
        public boolean isValid() { return valid; }
        public boolean isBlack() { return black; }
        public double getAverageBrightness() { return averageBrightness; }
        public double getBlackPixelRatio() { return blackPixelRatio; }
        public int getWidth() { return width; }
        public int getHeight() { return height; }
        public String getNote() { return note; }
    }

    public static final class Inputs {
        private final int windowWidth;
        private final int windowHeight;
        private final int renderTargetWidth;
        private final int renderTargetHeight;
        private final int guiScale;
        private final boolean recordingActive;
        private final LiveStats liveStats;
        private final SelfTestResult selfTest;
        private final boolean selfTestPending;
        private final String selfTestStatus;

        public Inputs(
                int windowWidth,
                int windowHeight,
                int renderTargetWidth,
                int renderTargetHeight,
                int guiScale,
                boolean recordingActive,
                LiveStats liveStats,
                SelfTestResult selfTest) {
            this(
                    windowWidth,
                    windowHeight,
                    renderTargetWidth,
                    renderTargetHeight,
                    guiScale,
                    recordingActive,
                    liveStats,
                    selfTest,
                    false,
                    selfTest == null ? "Not run." : "Complete.");
        }

        public Inputs(
                int windowWidth,
                int windowHeight,
                int renderTargetWidth,
                int renderTargetHeight,
                int guiScale,
                boolean recordingActive,
                LiveStats liveStats,
                SelfTestResult selfTest,
                boolean selfTestPending,
                String selfTestStatus) {
            this.windowWidth = windowWidth;
            this.windowHeight = windowHeight;
            this.renderTargetWidth = renderTargetWidth;
            this.renderTargetHeight = renderTargetHeight;
            this.guiScale = guiScale;
            this.recordingActive = recordingActive;
            this.liveStats = liveStats;
            this.selfTest = selfTest;
            this.selfTestPending = selfTestPending;
            this.selfTestStatus = selfTestStatus == null
                    ? ""
                    : selfTestStatus;
        }

        public int windowWidth() { return windowWidth; }
        public int windowHeight() { return windowHeight; }
        public int renderTargetWidth() { return renderTargetWidth; }
        public int renderTargetHeight() { return renderTargetHeight; }
        public int guiScale() { return guiScale; }
        public boolean recordingActive() { return recordingActive; }
        public LiveStats liveStats() { return liveStats; }
        public SelfTestResult selfTest() { return selfTest; }
        public boolean selfTestPending() { return selfTestPending; }
        public String selfTestStatus() { return selfTestStatus; }
        public int getWindowWidth() { return windowWidth; }
        public int getWindowHeight() { return windowHeight; }
        public int getRenderTargetWidth() { return renderTargetWidth; }
        public int getRenderTargetHeight() { return renderTargetHeight; }
        public int getGuiScale() { return guiScale; }
        public boolean isRecordingActive() { return recordingActive; }
        public LiveStats getLiveStats() { return liveStats; }
        public SelfTestResult getSelfTest() { return selfTest; }
        public boolean isSelfTestPending() { return selfTestPending; }
        public String getSelfTestStatus() { return selfTestStatus; }
    }

    public static final class EnvironmentReport {
        private final long createdAtMillis;
        private final List<Check> checks;
        private final Status verdict;

        EnvironmentReport(long createdAtMillis, List<Check> checks) {
            this.createdAtMillis = createdAtMillis;
            this.checks = Collections.unmodifiableList(
                    new ArrayList<Check>(checks));
            this.verdict = overallVerdict(checks);
        }

        public long getCreatedAtMillis() { return createdAtMillis; }
        public List<Check> getChecks() { return checks; }
        public Status getVerdict() { return verdict; }

        public String toText() {
            StringBuilder text = new StringBuilder();
            text.append("Record-able Capture Diagnostics\n");
            text.append("Verdict: ")
                    .append(verdict)
                    .append(" - ")
                    .append(verdictSummary(verdict))
                    .append("\n\n");
            for (Check check : checks) {
                text.append('[')
                        .append(check.status())
                        .append("] ")
                        .append(check.label())
                        .append(": ")
                        .append(check.detail())
                        .append('\n');
            }
            return text.toString();
        }
    }

    private static final Object SELF_TEST_LOCK = new Object();
    private static final int SELF_TEST_MAX_ATTEMPTS = 8;
    private static volatile boolean selfTestPending;
    private static volatile boolean selfTestCancelRequested;
    private static volatile String selfTestStatus = "Not run.";
    private static volatile SelfTestResult lastSelfTest;

    /*
     * These fields are touched only from the render-thread hook. The test owns
     * this ScreenCapture and every frame it returns; it never asks the active
     * RecordingManager for a frame and therefore can never release one owned
     * by the encoder or replay buffer.
     */
    private static ScreenCapture selfTestCapture;
    private static int selfTestAttempts;

    private CaptureDiagnostics() {
    }

    /**
     * Queues a self-test. The actual GPU readback occurs only from
     * {@link #onRenderFrame()}.
     */
    public static boolean requestSelfTest() {
        synchronized (SELF_TEST_LOCK) {
            if (selfTestPending) {
                return false;
            }
            selfTestPending = true;
            selfTestCancelRequested = false;
            lastSelfTest = null;
            selfTestStatus = "Waiting for a rendered frame...";
            return true;
        }
    }

    public static void cancelSelfTest() {
        synchronized (SELF_TEST_LOCK) {
            if (!selfTestPending) {
                return;
            }
            selfTestCancelRequested = true;
            selfTestStatus = "Cancelling on the render thread...";
        }
    }

    /**
     * Render-thread hook, called after the normal recording/replay capture hook.
     */
    public static void onRenderFrame() {
        if (!selfTestPending) {
            return;
        }

        if (selfTestCancelRequested) {
            finishSelfTestOnRenderThread(null, "Self-test cancelled.");
            return;
        }

        RecordingManager manager = RecordingManager.getInstance();
        if (manager.isActiveOrStopping()) {
            selfTestStatus =
                    "Deferred until the active recording has finished.";
            return;
        }

        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft == null
                || minecraft.displayWidth <= 0
                || minecraft.displayHeight <= 0) {
            finishSelfTestOnRenderThread(
                    new SelfTestResult(
                            false,
                            false,
                            false,
                            -1.0D,
                            0.0D,
                            0,
                            0,
                            "Minecraft framebuffer is unavailable."),
                    "Self-test failed.");
            return;
        }

        try {
            if (selfTestCapture == null) {
                int[] dimensions = selfTestDimensions(
                        minecraft.displayWidth,
                        minecraft.displayHeight);
                selfTestCapture = new ScreenCapture();
                selfTestCapture.prepare(
                        dimensions[0],
                        dimensions[1],
                        3);
                selfTestAttempts = 0;
                selfTestStatus = "Warming up framebuffer readback...";
            }

            selfTestAttempts++;
            CapturedFrame frame = selfTestCapture.capture();
            if (frame == null) {
                if (selfTestAttempts >= SELF_TEST_MAX_ATTEMPTS) {
                    finishSelfTestOnRenderThread(
                            new SelfTestResult(
                                    false,
                                    false,
                                    false,
                                    -1.0D,
                                    0.0D,
                                    selfTestCapture.getOutputWidth(),
                                    selfTestCapture.getOutputHeight(),
                                    "No frame was returned after "
                                            + SELF_TEST_MAX_ATTEMPTS
                                            + " render attempts."),
                            "Self-test failed.");
                }
                return;
            }

            SelfTestResult result;
            try {
                FrameValidator.ValidationResult validation =
                        FrameValidator.validate(frame);
                result = new SelfTestResult(
                        true,
                        validation.isValid(),
                        validation.isBlack(),
                        validation.getAverageBrightness(),
                        validation.getBlackPixelRatio(),
                        frame.getWidth(),
                        frame.getHeight(),
                        validation.getMessage());
            } finally {
                // This is the private self-test frame, never an encoder/replay
                // frame. Release exactly once before disposing our capture.
                frame.release();
            }
            finishSelfTestOnRenderThread(
                    result,
                    result.isValid()
                            ? "Self-test passed."
                            : "Self-test found a capture problem.");
        } catch (Throwable throwable) {
            finishSelfTestOnRenderThread(
                    new SelfTestResult(
                            false,
                            false,
                            false,
                            -1.0D,
                            0.0D,
                            0,
                            0,
                            safeMessage(throwable)),
                    "Self-test failed.");
        }
    }

    public static boolean isSelfTestPending() {
        return selfTestPending;
    }

    public static String getSelfTestStatus() {
        return selfTestStatus;
    }

    public static SelfTestResult getLastSelfTest() {
        return lastSelfTest;
    }

    public static void clearSelfTestResult() {
        synchronized (SELF_TEST_LOCK) {
            if (!selfTestPending) {
                lastSelfTest = null;
                selfTestStatus = "Not run.";
            }
        }
    }

    private static void finishSelfTestOnRenderThread(
            SelfTestResult result,
            String status) {
        if (selfTestCapture != null) {
            try {
                selfTestCapture.close();
            } catch (Throwable ignored) {
            }
        }
        selfTestCapture = null;
        selfTestAttempts = 0;
        synchronized (SELF_TEST_LOCK) {
            lastSelfTest = result;
            selfTestStatus = status;
            selfTestPending = false;
            selfTestCancelRequested = false;
        }
    }

    public static Inputs collectInputs() {
        return collectInputs(null);
    }

    /**
     * Builds capture inputs. A live capture is optional because the current
     * manager deliberately keeps its owned ScreenCapture private.
     */
    public static Inputs collectInputs(ScreenCapture liveCapture) {
        Minecraft minecraft = Minecraft.getMinecraft();
        int windowWidth = minecraft == null
                ? -1
                : minecraft.displayWidth;
        int windowHeight = minecraft == null
                ? -1
                : minecraft.displayHeight;
        int renderWidth = -1;
        int renderHeight = -1;
        int guiScale = -1;

        if (minecraft != null) {
            int[] renderSize = readRenderTargetSize(
                    minecraft.getFramebuffer());
            if (renderSize != null) {
                renderWidth = renderSize[0];
                renderHeight = renderSize[1];
            }
            try {
                guiScale = new ScaledResolution(minecraft)
                        .getScaleFactor();
            } catch (Throwable ignored) {
                if (minecraft.gameSettings != null) {
                    guiScale = minecraft.gameSettings.guiScale;
                }
            }
        }

        return new Inputs(
                windowWidth,
                windowHeight,
                renderWidth,
                renderHeight,
                guiScale,
                RecordingManager.getInstance().isActiveOrStopping(),
                liveStats(liveCapture, windowWidth, windowHeight),
                lastSelfTest,
                selfTestPending,
                selfTestStatus);
    }

    public static LiveStats liveStats(
            ScreenCapture capture,
            int windowWidth,
            int windowHeight) {
        if (capture == null) {
            return null;
        }
        int sourceWidth = capture.getSourceWidth();
        int sourceHeight = capture.getSourceHeight();
        return new LiveStats(
                capture.getTotalFramesProduced(),
                capture.getTotalBlackFrames(),
                capture.getConsecutiveBlackFrames(),
                capture.isPersistentlyBlack(),
                capture.isUsingPbos()
                        ? "Framebuffer (PBO)"
                        : "Framebuffer (synchronous)",
                sourceWidth,
                sourceHeight,
                capture.getBackingTextureWidth(),
                capture.getBackingTextureHeight(),
                sourceWidth > 0
                        && sourceHeight > 0
                        && (sourceWidth != windowWidth
                                || sourceHeight != windowHeight),
                capture.isUsingPbos(),
                capture.getConsecutivePboMapFailures(),
                capture.getLastGlError());
    }

    /**
     * Builds screen/capture checks without running external process or device
     * probes.
     */
    public static List<Check> buildReport(Inputs input) {
        Inputs in = input == null
                ? collectInputs()
                : input;
        List<Check> checks = new ArrayList<Check>();

        checks.add(new Check(
                in.windowWidth() > 0 && in.windowHeight() > 0
                        ? Status.INFO
                        : Status.FAIL,
                "Window framebuffer",
                in.windowWidth() + " x " + in.windowHeight()));

        boolean renderKnown = in.renderTargetWidth() > 0
                && in.renderTargetHeight() > 0;
        if (renderKnown) {
            checks.add(new Check(
                    Status.INFO,
                    "Game render target",
                    in.renderTargetWidth() + " x "
                            + in.renderTargetHeight()));
            boolean mismatch = in.renderTargetWidth()
                            != in.windowWidth()
                    || in.renderTargetHeight()
                            != in.windowHeight();
            checks.add(new Check(
                    mismatch ? Status.FAIL : Status.OK,
                    "Framebuffer size match",
                    mismatch
                            ? "Window and game framebuffer sizes differ. Resize/shader changes may produce cropped or blank capture."
                            : "Window and game framebuffer sizes agree."));
        } else {
            checks.add(new Check(
                    Status.WARN,
                    "Game render target",
                    "Size unavailable; capture will rely on the window dimensions."));
        }

        checks.add(new Check(
                Status.INFO,
                "GUI scale",
                in.guiScale() > 0
                        ? Integer.toString(in.guiScale())
                        : "unknown"));
        checks.add(new Check(
                Status.INFO,
                "Recording active",
                in.recordingActive() ? "yes" : "no"));

        LiveStats live = in.liveStats();
        if (live != null) {
            checks.add(new Check(
                    Status.INFO,
                    "Capture source",
                    live.sourceName() + ", "
                            + live.sourceWidth() + "x"
                            + live.sourceHeight()));
            checks.add(new Check(
                    Status.INFO,
                    "Frames produced",
                    Long.toString(live.framesProduced())));

            if (live.persistentBlack()) {
                checks.add(new Check(
                        Status.FAIL,
                        "Black frames",
                        "Capture is persistently black ("
                                + live.blackFrames()
                                + " total). A shader or GPU driver may be redirecting the framebuffer."));
            } else if (live.blackFrames() > 0L
                    || live.consecutiveBlack() > 0) {
                checks.add(new Check(
                        Status.WARN,
                        "Black frames",
                        live.blackFrames() + " total, "
                                + live.consecutiveBlack()
                                + " consecutive."));
            } else {
                checks.add(new Check(
                        Status.OK,
                        "Black frames",
                        "None detected."));
            }

            if (live.sizeMismatch()) {
                checks.add(new Check(
                        Status.WARN,
                        "Live source size",
                        "Window size differs from the live capture source; frames are being rescaled."));
            }
            if (live.pboMapFailures() > 0) {
                checks.add(new Check(
                        Status.WARN,
                        "PBO readback",
                        live.pboMapFailures()
                                + " consecutive map failure(s)."));
            } else {
                checks.add(new Check(
                        Status.OK,
                        "Readback mode",
                        live.usingPbos()
                                ? "Asynchronous PBO"
                                : "Synchronous fallback"));
            }
            if (live.lastGlError() != 0) {
                checks.add(new Check(
                        Status.WARN,
                        "OpenGL error",
                        "Last capture GL error: "
                                + live.lastGlError()));
            }
        } else if (in.recordingActive()) {
            checks.add(new Check(
                    Status.INFO,
                    "Live capture stats",
                    "The active capture is manager-owned and was not borrowed by diagnostics."));
        }

        SelfTestResult selfTest = in.selfTest();
        if (in.selfTestPending()) {
            checks.add(new Check(
                    Status.INFO,
                    "Capture self-test",
                    isBlank(in.selfTestStatus())
                            ? "Waiting for render thread."
                            : in.selfTestStatus()));
        } else if (selfTest == null) {
            checks.add(new Check(
                    Status.INFO,
                    "Capture self-test",
                    "Not run."));
        } else if (!selfTest.producedFrame()) {
            checks.add(new Check(
                    Status.FAIL,
                    "Capture self-test",
                    "GPU readback produced no frame. "
                            + selfTest.note()));
        } else if (selfTest.black()) {
            checks.add(new Check(
                    Status.FAIL,
                    "Capture self-test",
                    "Captured frame is black (brightness "
                            + formatOne(selfTest.avgBrightness())
                            + ", black pixels "
                            + formatPercent(selfTest.blackPixelRatio())
                            + ")."));
        } else if (!selfTest.valid()) {
            checks.add(new Check(
                    Status.WARN,
                    "Capture self-test",
                    "Frame was produced but validation reported: "
                            + selfTest.note()));
        } else {
            checks.add(new Check(
                    Status.OK,
                    "Capture self-test",
                    selfTest.width() + "x" + selfTest.height()
                            + ", average brightness "
                            + formatOne(selfTest.avgBrightness())
                            + "."));
        }
        return checks;
    }

    public static EnvironmentReport buildEnvironmentReport() {
        return buildEnvironmentReport(null, true);
    }

    public static EnvironmentReport buildEnvironmentReport(
            ScreenCapture liveCapture) {
        return buildEnvironmentReport(liveCapture, true);
    }

    /**
     * Builds the full report. Expensive checks execute FFmpeg and enumerate
     * Java Sound mixers, so UI callers should run this method on a worker.
     */
    public static EnvironmentReport buildEnvironmentReport(
            ScreenCapture liveCapture,
            boolean includeExpensiveChecks) {
        List<Check> checks = new ArrayList<Check>();
        addEnvironmentChecks(checks);

        RecordableConfig config = RecordableConfig.get();
        addDiskChecks(checks, config);
        if (includeExpensiveChecks) {
            addFfmpegChecks(checks, config);
            addAudioChecks(checks, config);
        } else {
            checks.add(new Check(
                    Status.INFO,
                    "External probes",
                    "FFmpeg encoder and audio-device probes were skipped."));
        }
        checks.addAll(buildReport(collectInputs(liveCapture)));

        Throwable lastFailure =
                RecordingManager.getInstance().getLastFailure();
        if (lastFailure != null) {
            checks.add(new Check(
                    Status.WARN,
                    "Last recording failure",
                    safeMessage(lastFailure)));
        }
        return new EnvironmentReport(
                System.currentTimeMillis(),
                checks);
    }

    private static void addEnvironmentChecks(List<Check> checks) {
        Runtime runtime = Runtime.getRuntime();
        checks.add(new Check(
                Status.INFO,
                "Platform",
                PlatformUtils.detectPlatform().getDisplayName()
                        + " / "
                        + System.getProperty("os.arch", "unknown")
                        + " / "
                        + System.getProperty("os.version", "unknown")));
        checks.add(new Check(
                Status.INFO,
                "Java",
                System.getProperty("java.vendor", "unknown")
                        + " "
                        + System.getProperty("java.version", "unknown")));
        checks.add(new Check(
                Status.INFO,
                "Minecraft / Forge target",
                "Minecraft 1.8.9, Forge 11.15.1.2318"));
        checks.add(new Check(
                Status.INFO,
                "Processors",
                Integer.toString(runtime.availableProcessors())));
        checks.add(new Check(
                Status.INFO,
                "JVM memory",
                humanBytes(runtime.totalMemory() - runtime.freeMemory())
                        + " used / "
                        + humanBytes(runtime.maxMemory())
                        + " max"));
    }

    private static void addDiskChecks(
            List<Check> checks,
            RecordableConfig config) {
        Path output = config.getOutputDirectory();
        try {
            Files.createDirectories(output);
        } catch (Exception exception) {
            checks.add(new Check(
                    Status.FAIL,
                    "Output directory",
                    "Cannot create " + output + ": "
                            + safeMessage(exception)));
            return;
        }

        checks.add(new Check(
                Files.isWritable(output)
                        ? Status.OK
                        : Status.FAIL,
                "Output directory",
                output.toAbsolutePath().toString()));
        DiskSpaceGuardian.DiskCheckResult disk =
                DiskSpaceGuardian.check(output, config);
        Status status = disk.isBlocked()
                ? Status.FAIL
                : disk.isWarning()
                        ? Status.WARN
                        : Status.OK;
        checks.add(new Check(
                status,
                "Recording disk",
                disk.getMessage() + " Free: "
                        + DiskSpaceGuardian.getFormattedFreeSpace(output)));
    }

    private static void addFfmpegChecks(
            List<Check> checks,
            RecordableConfig config) {
        try {
            FfmpegBundleManager.FfmpegStatus ffmpeg =
                    FfmpegBundleManager.detectFfmpeg();
            if (!ffmpeg.isFound()) {
                checks.add(new Check(
                        Status.FAIL,
                        "FFmpeg",
                        isBlank(ffmpeg.getError())
                                ? "Not found."
                                : ffmpeg.getError()));
                return;
            }

            checks.add(new Check(
                    Status.OK,
                    "FFmpeg",
                    ffmpeg.getVersion() + " at "
                            + ffmpeg.getExecutable()));
            List<String> encoders =
                    FfmpegBundleManager.queryEncoders();
            checks.add(new Check(
                    encoders.isEmpty() ? Status.WARN : Status.INFO,
                    "Video encoders",
                    encoders.isEmpty()
                            ? "Encoder query returned no known codecs."
                            : join(encoders, ", ")));

            RecordableConfig.VideoEncoder selected =
                    config.encoder == null
                            ? RecordableConfig.VideoEncoder.SOFTWARE
                            : config.encoder;
            checks.add(new Check(
                    encoders.contains(selected.ffmpegCodec)
                            ? Status.OK
                            : Status.FAIL,
                    "Selected encoder",
                    selected.displayName
                            + (encoders.contains(selected.ffmpegCodec)
                                    ? " is available."
                                    : " (" + selected.ffmpegCodec
                                            + ") is unavailable.")));

            String ffprobe =
                    FfmpegBundleManager.getFfprobeExecutable();
            checks.add(new Check(
                    isBlank(ffprobe) ? Status.WARN : Status.OK,
                    "ffprobe",
                    isBlank(ffprobe)
                            ? "Not found; gallery metadata will be limited."
                            : ffprobe));
        } catch (Throwable throwable) {
            checks.add(new Check(
                    Status.FAIL,
                    "FFmpeg probe",
                    safeMessage(throwable)));
        }
    }

    private static void addAudioChecks(
            List<Check> checks,
            RecordableConfig config) {
        if (!config.captureAudio && !config.captureMicrophone) {
            checks.add(new Check(
                    Status.INFO,
                    "Audio capture",
                    "Disabled."));
            return;
        }

        try {
            List<AudioCaptureSession.AudioDevice> devices =
                    AudioCaptureSession.listDevices();
            checks.add(new Check(
                    devices.isEmpty() ? Status.WARN : Status.INFO,
                    "Audio devices",
                    devices.isEmpty()
                            ? "No Java Sound capture devices found."
                            : devices.size() + " compatible device(s): "
                                    + deviceNames(devices)));

            if (config.captureAudio) {
                boolean selected = selectedDeviceAvailable(
                        config.audioDevice,
                        devices);
                boolean loopback = hasLoopback(devices);
                checks.add(new Check(
                        selected && loopback
                                ? Status.OK
                                : Status.WARN,
                        "Game audio",
                        !selected
                                ? "Configured device is missing: "
                                        + config.audioDevice
                                : loopback
                                        ? "A loopback candidate is available."
                                        : "No loopback candidate found. Enable Stereo Mix, a monitor source, or a virtual loopback device."));
            }

            if (config.captureMicrophone) {
                boolean selected = selectedDeviceAvailable(
                        config.microphoneDevice,
                        devices);
                boolean microphone = hasMicrophoneCandidate(devices);
                checks.add(new Check(
                        selected && microphone
                                ? Status.OK
                                : Status.WARN,
                        "Microphone",
                        !selected
                                ? "Configured device is missing: "
                                        + config.microphoneDevice
                                : microphone
                                        ? "A microphone candidate is available."
                                        : "No non-loopback capture device was found."));
            }
        } catch (Throwable throwable) {
            checks.add(new Check(
                    Status.WARN,
                    "Audio device probe",
                    safeMessage(throwable)));
        }
    }

    public static Status overallVerdict(List<Check> checks) {
        Status worst = Status.OK;
        if (checks == null) {
            return Status.INFO;
        }
        for (Check check : checks) {
            if (check == null) {
                continue;
            }
            if (check.status() == Status.FAIL) {
                return Status.FAIL;
            }
            if (check.status() == Status.WARN) {
                worst = Status.WARN;
            }
        }
        return worst;
    }

    public static String verdictSummary(Status verdict) {
        if (verdict == Status.FAIL) {
            return "Capture problem detected; inspect failed checks.";
        }
        if (verdict == Status.WARN) {
            return "Capture may work, but something needs attention.";
        }
        return "Capture environment looks healthy.";
    }

    /**
     * Reads common Forge/Yarn/Mojmap framebuffer size members reflectively.
     */
    public static int[] readRenderTargetSize(Object target) {
        if (target == null) {
            return null;
        }
        int width = readIntMember(
                target,
                "getWidth",
                "width",
                "framebufferWidth",
                "framebufferTextureWidth",
                "textureWidth",
                "viewportWidth");
        int height = readIntMember(
                target,
                "getHeight",
                "height",
                "framebufferHeight",
                "framebufferTextureHeight",
                "textureHeight",
                "viewportHeight");
        return width > 0 && height > 0
                ? new int[]{width, height}
                : null;
    }

    private static int readIntMember(Object object, String... names) {
        Class<?> type = object.getClass();
        for (String name : names) {
            try {
                Method method = type.getMethod(name);
                Object value = method.invoke(object);
                if (value instanceof Integer
                        && ((Integer) value).intValue() > 0) {
                    return ((Integer) value).intValue();
                }
            } catch (Throwable ignored) {
            }
            try {
                Field field = type.getField(name);
                Object value = field.get(object);
                if (value instanceof Integer
                        && ((Integer) value).intValue() > 0) {
                    return ((Integer) value).intValue();
                }
            } catch (Throwable ignored) {
            }
        }
        return -1;
    }

    private static int[] selfTestDimensions(int width, int height) {
        int maximumWidth = 320;
        int maximumHeight = 180;
        double scale = Math.min(
                1.0D,
                Math.min(
                        maximumWidth / (double) Math.max(1, width),
                        maximumHeight / (double) Math.max(1, height)));
        int testWidth = makeEven(
                Math.max(2, (int) Math.round(width * scale)));
        int testHeight = makeEven(
                Math.max(2, (int) Math.round(height * scale)));
        return new int[]{testWidth, testHeight};
    }

    private static int makeEven(int value) {
        return (Math.max(2, value) + 1) & ~1;
    }

    private static boolean selectedDeviceAvailable(
            String selected,
            List<AudioCaptureSession.AudioDevice> devices) {
        if (isBlank(selected)
                || "auto".equalsIgnoreCase(selected)) {
            return !devices.isEmpty();
        }
        for (AudioCaptureSession.AudioDevice device : devices) {
            if (selected.equals(device.getId())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasLoopback(
            List<AudioCaptureSession.AudioDevice> devices) {
        for (AudioCaptureSession.AudioDevice device : devices) {
            if (device.isLoopbackCandidate()) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasMicrophoneCandidate(
            List<AudioCaptureSession.AudioDevice> devices) {
        for (AudioCaptureSession.AudioDevice device : devices) {
            if (!device.isLoopbackCandidate()) {
                return true;
            }
        }
        return false;
    }

    private static String deviceNames(
            List<AudioCaptureSession.AudioDevice> devices) {
        List<String> names = new ArrayList<String>();
        for (AudioCaptureSession.AudioDevice device : devices) {
            names.add(device.getDisplayName()
                    + (device.isLoopbackCandidate()
                            ? " (loopback)"
                            : ""));
        }
        return join(names, ", ");
    }

    private static String join(List<String> values, String delimiter) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() > 0) {
                result.append(delimiter);
            }
            result.append(value);
        }
        return result.toString();
    }

    private static String formatOne(double value) {
        return String.format(
                Locale.ROOT,
                "%.1f",
                Double.valueOf(value));
    }

    private static String formatPercent(double value) {
        return String.format(
                Locale.ROOT,
                "%.1f%%",
                Double.valueOf(value * 100.0D));
    }

    private static String humanBytes(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        double kibibytes = bytes / 1024.0D;
        if (kibibytes < 1024.0D) {
            return formatOne(kibibytes) + " KB";
        }
        double mebibytes = kibibytes / 1024.0D;
        if (mebibytes < 1024.0D) {
            return formatOne(mebibytes) + " MB";
        }
        return formatOne(mebibytes / 1024.0D) + " GB";
    }

    private static String safeMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current != null
                && current.getCause() != null
                && current.getCause() != current) {
            current = current.getCause();
        }
        if (current == null) {
            return "Unknown error";
        }
        String message = current.getMessage();
        return isBlank(message)
                ? current.getClass().getSimpleName()
                : message;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
