package dev.recordish;

import net.minecraft.client.Minecraft;
import net.minecraft.util.EnumChatFormatting;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Owns the Forge 1.8.9 recording lifecycle and keeps all OpenGL work on the
 * Minecraft render thread.
 */
public final class RecordingManager {
    public enum State {
        IDLE,
        STARTING,
        RECORDING,
        PAUSED,
        STOPPING
    }

    public enum StopReason {
        MANUAL,
        DISCONNECT,
        SHUTDOWN,
        AUTO,
        FILE_SIZE_LIMIT,
        DISK_SPACE
    }

    public enum QueueHealth {
        OK,
        SLOW,
        CRITICAL
    }

    /**
     * Published under {@link #lock} at the same instant as {@link State#STOPPING}.
     *
     * <p>A thread is not a safe completion token: there is a short interval
     * between changing state and starting the worker where {@code isAlive()}
     * is false. Minecraft can enter JVM shutdown in exactly that interval.
     * These latches exist before STOPPING becomes visible, so every shutdown
     * path has something deterministic to await.</p>
     */
    private static final class FinalizationHandle {
        private final StopReason reason;
        private final FFmpegEncoder encoder;
        private final AudioCaptureSession audio;
        private final Path requestedOutput;
        private final Path temporaryVideo;
        private final CountDownLatch mediaSealed = new CountDownLatch(1);
        private final CountDownLatch completed = new CountDownLatch(1);

        private volatile Thread worker;
        private volatile Path sealedVideo;
        private volatile Throwable failure;

        private FinalizationHandle(
                StopReason reason,
                FFmpegEncoder encoder,
                AudioCaptureSession audio) {
            this.reason = reason;
            this.encoder = encoder;
            this.audio = audio;
            this.requestedOutput = encoder == null
                    ? null
                    : encoder.getRequestedOutput();
            this.temporaryVideo = encoder == null
                    ? null
                    : encoder.getTemporaryVideo();
        }
    }

    private static final RecordingManager INSTANCE = new RecordingManager();
    private static final long DISK_CHECK_INTERVAL_NANOS = 5_000_000_000L;
    /*
     * FFmpegEncoder itself permits up to 120 seconds to drain queued frames
     * and another 120 seconds to write the Matroska trailer. Do not cut a
     * healthy seal operation off at the old outer 45-second limit.
     */
    private static final long SHUTDOWN_MEDIA_SEAL_TIMEOUT_MILLIS = 270_000L;
    private static final long SHUTDOWN_MUX_TIMEOUT_MILLIS = 120_000L;
    private static final long SHUTDOWN_INTERRUPT_GRACE_MILLIS = 10_000L;
    private static final long SHUTDOWN_REPLAY_TIMEOUT_MILLIS = 15_000L;
    private static final int CAPTURE_SOURCE_SWITCHES_TO_EXHAUST = 3;
    private static final int BLACK_CONFIRMATION_FRAMES = 15;
    private static final long DROPPED_FRAME_WARNING_THRESHOLD = 100L;

    private final Object lock = new Object();
    private final FrameProcessor frameProcessor = new FrameProcessor();
    private final AtomicLong capturedFrames = new AtomicLong();
    private final AtomicLong skippedFrames = new AtomicLong();
    private final AtomicLong failedCaptures = new AtomicLong();
    private final List<RecordingBookmark> bookmarks =
            Collections.synchronizedList(new ArrayList<RecordingBookmark>());

    private volatile State state = State.IDLE;
    private volatile boolean microphoneActive;
    private ScreenCapture screenCapture;
    private ScreenCapture replayScreenCapture;
    private FFmpegEncoder encoder;
    private AudioCaptureSession audioSession;
    private volatile FinalizationHandle activeFinalization;
    private Path currentOutputFile;
    private Path lastOutputFile;
    private long startedAtNanos;
    private long lastFrameCaptureNanos;
    private long frameIntervalNanos;
    private long baseFrameIntervalNanos;
    private long lastGovernorCheckNanos;
    private int governorCurrentFps;
    private long pauseStartedAtNanos;
    private long totalPausedNanos;
    private long lastDiskCheckNanos;
    private long lastReplayCaptureNanos;
    private int replayCaptureWidth;
    private int replayCaptureHeight;
    private int recordingWidth;
    private int recordingHeight;
    private int recordingFps;
    private int consecutiveCaptureFailures;
    private int bookmarkCounter;
    private boolean firstFrameTimelineAligned;
    private volatile boolean blackScreenWarned;
    private volatile boolean droppedFrameWarningSent;
    private long observedCaptureFrames;
    private long observedBlackFrames;
    private String observedCaptureSource;
    private int blackCaptureSourceSwitches;
    private int blackFramesAfterSourceExhaustion;
    private volatile String pendingToastMessage;
    private volatile long pendingToastExpiresAtMillis;
    private volatile Throwable lastFailure;

    private RecordingManager() {
    }

    public static RecordingManager getInstance() {
        return INSTANCE;
    }

    public void initialize() {
        /*
         * Executable probes can wait on a broken custom path or PATH entry.
         * Warm executable, codec-listing, and hardware-preflight caches on a
         * daemon so a configured WebM or hardware recording works directly
         * after launch without blocking Minecraft's render thread.
         */
        FfmpegBundleManager.invalidateCache();
        Thread warmup = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    FfmpegBundleManager.FfmpegStatus status =
                            FfmpegBundleManager.detectFfmpeg();
                    if (status.isFound()) {
                        FFmpegEncoder.detectAvailableEncoders();
                    }
                } catch (Throwable throwable) {
                    RecordishMod.LOGGER.debug(
                            "Background FFmpeg capability warmup failed.",
                            throwable);
                }
            }
        }, "Recordish-FFmpeg-Warmup");
        warmup.setDaemon(true);
        warmup.start();
    }

    public static boolean isInGameState(Minecraft minecraft) {
        return minecraft != null
                && minecraft.theWorld != null
                && minecraft.thePlayer != null;
    }

    public void toggleRecording() {
        State snapshot = state;
        if (snapshot == State.IDLE) {
            startRecording();
        } else if (snapshot == State.RECORDING
                || snapshot == State.PAUSED) {
            stopRecording(StopReason.MANUAL);
        } else if (snapshot == State.STARTING) {
            RecordishMessages.send(
                    ChatCategory.RECORDING,
                    "The recorder is still starting.");
        } else {
            RecordishMessages.send(
                    ChatCategory.RECORDING,
                    "The recorder is already finalizing.");
        }
    }

    public void startRecording() {
        startRecording(null, true);
    }

    public void startRecording(String filePrefix) {
        startRecording(filePrefix, false);
    }

    private void startRecording(
            String filePrefix,
            boolean allowAutoEnable) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (!isInGameState(minecraft)) {
            RecordishMessages.send(
                    ChatCategory.RECORDING,
                    "Join a world or server before recording.");
            return;
        }

        RecordishConfig config = RecordishConfig.get();
        config.sanitize();
        synchronized (lock) {
            if (state != State.IDLE) return;
            if (!config.enabled) {
                if (!allowAutoEnable) {
                    RecordishMessages.send(
                            ChatCategory.RECORDING,
                            "Recordish is disabled in its settings.");
                    return;
                }
                config.enabled = true;
                config.save();
                RecordishMessages.send(
                        ChatCategory.RECORDING,
                        "\u00a7aRecordish was disabled. "
                                + "Auto-enabled and starting recording...");
            }
            state = State.STARTING;
            lastFailure = null;
        }

        FFmpegEncoder newEncoder = null;
        ScreenCapture newCapture = null;
        AudioCaptureSession newAudio = null;
        try {
            FfmpegBundleManager.FfmpegStatus status =
                    FfmpegBundleManager.detectFfmpeg();
            if (!status.isFound()) {
                throw new IOException(status.getError() == null
                        ? "FFmpeg is required. Open Recordish settings to install it."
                        : status.getError());
            }

            Path outputDirectory = config.getOutputDirectory();
            DiskSpaceGuardian.DiskCheckResult disk =
                    DiskSpaceGuardian.check(outputDirectory, config);
            if (disk.isBlocked()) {
                throw new IOException(stripFormatting(disk.getMessage()));
            }
            if (disk.isWarning()) {
                RecordishMessages.send(
                        ChatCategory.WARNINGS,
                        disk.getMessage());
            }

            int nativeWidth = makeEven(Math.max(2, minecraft.displayWidth));
            int nativeHeight = makeEven(Math.max(2, minecraft.displayHeight));
            RecordishConfig.CaptureDimensions dimensions =
                    config.resolveCaptureDimensions(nativeWidth, nativeHeight);
            int width = makeEven(dimensions.getWidth());
            int height = makeEven(dimensions.getHeight());
            int fps = Math.max(1, config.getFps());

            newEncoder = new FFmpegEncoder(
                    config,
                    width,
                    height,
                    width,
                    height,
                    fps,
                    filePrefix);
            newEncoder.start();

            newCapture = new ScreenCapture();
            newCapture.prepare(
                    width,
                    height,
                    config.perfModeGamePriority ? 8 : 16);

            closeReplayCapture();
            configureReplayBuffer(config, width, height);

            if (config.captureAudio || config.captureMicrophone) {
                newAudio = AudioCaptureSession.start(
                        config,
                        outputDirectory,
                        newEncoder.getRequestedOutput());
            }

            synchronized (lock) {
                if (state != State.STARTING) {
                    throw new IOException("Recording start was cancelled.");
                }
                encoder = newEncoder;
                screenCapture = newCapture;
                audioSession = newAudio;
                currentOutputFile = newEncoder.getRequestedOutput();
                lastOutputFile = currentOutputFile;
                recordingWidth = width;
                recordingHeight = height;
                recordingFps = fps;
                startedAtNanos = newAudio != null
                        && newAudio.getVideoStartNanos() > 0L
                            ? newAudio.getVideoStartNanos()
                            : System.nanoTime();
                lastFrameCaptureNanos = 0L;
                frameIntervalNanos = Math.max(
                        1L,
                        1_000_000_000L / recordingFps);
                baseFrameIntervalNanos = frameIntervalNanos;
                lastGovernorCheckNanos = 0L;
                governorCurrentFps = recordingFps;
                pauseStartedAtNanos = 0L;
                totalPausedNanos = 0L;
                lastDiskCheckNanos = startedAtNanos;
                consecutiveCaptureFailures = 0;
                firstFrameTimelineAligned = false;
                capturedFrames.set(0L);
                skippedFrames.set(0L);
                failedCaptures.set(0L);
                bookmarks.clear();
                bookmarkCounter = 0;
                blackScreenWarned = false;
                droppedFrameWarningSent = false;
                observedCaptureFrames = 0L;
                observedBlackFrames = 0L;
                observedCaptureSource = null;
                blackCaptureSourceSwitches = 0;
                blackFramesAfterSourceExhaustion = 0;
                microphoneActive = newAudio != null
                        && newAudio.hasMicrophoneAudio()
                        && config.captureMicrophone
                        && !config.microphonePushToTalk;
                state = State.RECORDING;
            }

            PerformanceMetrics.getInstance().reset();
            PerformanceOptimizer.getInstance().reset();
            String audioDescription = newAudio == null
                    ? ""
                    : newAudio.getStatusDescription();
            RecordishMessages.send(
                    ChatCategory.RECORDING,
                    "Recording started: "
                            + currentOutputFile.getFileName()
                            + " (" + width + "x" + height
                            + " @ " + fps + " FPS"
                            + (audioDescription.isEmpty()
                                    ? ""
                                    : ", " + audioDescription)
                            + ")");
            if (config.captureAudio
                    && config.trackGameAudio
                    && (newAudio == null
                        || !newAudio.hasGameAudio())) {
                RecordishMessages.send(
                    ChatCategory.WARNINGS,
                    newAudio != null
                            && newAudio.hasMicrophoneAudio()
                        ? "Game audio is unavailable; this recording has microphone audio only."
                        : "Game audio is unavailable; this recording is video only.");
            }
            if (config.captureMicrophone
                    && config.trackMicAudio
                    && (newAudio == null
                        || !newAudio.hasMicrophoneAudio())) {
                RecordishMessages.send(
                    ChatCategory.WARNINGS,
                    "The selected microphone is unavailable; recording continues without it.");
            }
        } catch (Throwable throwable) {
            if (newAudio != null) newAudio.abort();
            if (newCapture != null) newCapture.close();
            if (newEncoder != null) newEncoder.abort();
            synchronized (lock) {
                encoder = null;
                screenCapture = null;
                audioSession = null;
                state = State.IDLE;
                lastFailure = throwable;
            }
            RecordishMod.LOGGER.error("Unable to start recording.", throwable);
            RecordishMessages.error(
                    "Could not start recording: " + safeMessage(throwable));
        }
    }

    /**
     * Called from Forge's render-tick END phase while Minecraft's main
     * framebuffer is still available.
     */
    public void onRenderFrame() {
        if (state != State.RECORDING) {
            captureReplayFrame();
            return;
        }

        RecordishConfig frameConfig = RecordishConfig.get();
        ReplayBuffer activeReplay =
                prepareReplayForSharedCapture(frameConfig);
        final long now = System.nanoTime();
        updateAdaptiveCaptureRate(now);
        if (lastFrameCaptureNanos != 0L
                && now - lastFrameCaptureNanos < frameIntervalNanos) {
            return;
        }
        if (lastFrameCaptureNanos == 0L
                || now - lastFrameCaptureNanos > frameIntervalNanos * 4L) {
            lastFrameCaptureNanos = now;
        } else {
            lastFrameCaptureNanos += frameIntervalNanos;
        }

        ScreenCapture activeCapture = screenCapture;
        FFmpegEncoder activeEncoder = encoder;
        if (activeCapture == null || activeEncoder == null) {
            failedCaptures.incrementAndGet();
            return;
        }
        Throwable encoderFailure = activeEncoder.pollFailure();
        if (encoderFailure != null) {
            lastFailure = encoderFailure;
            RecordishMessages.error(
                "Recording stopped because FFmpeg failed: "
                    + safeMessage(encoderFailure));
            stopRecording(StopReason.AUTO);
            return;
        }

        CapturedFrame frame = null;
        try {
            frame = activeCapture.capture();
            // The asynchronous PBO path intentionally has no frame on its
            // first call.
            if (frame == null) return;
            maybeWarnBlackScreen(activeCapture);

            FrameValidator.ValidationResult validation =
                    FrameValidator.validate(frame);
            if (!validation.isValid()) {
                consecutiveCaptureFailures++;
                if (consecutiveCaptureFailures == 1
                        || consecutiveCaptureFailures % 15 == 0) {
                    RecordishMod.LOGGER.warn(
                            "Capture diagnostic ({} consecutive): {}",
                            consecutiveCaptureFailures,
                            validation.getMessage());
                }
                // Near-black and uniform frames can be legitimate fades,
                // menus, or loading screens. Only malformed buffers are
                // unsafe to pass to FFmpeg.
                if (validation.getMessage().startsWith("No ")
                        || validation.getMessage()
                                .startsWith("Unexpected ")) {
                    failedCaptures.incrementAndGet();
                    frame.release();
                    return;
                }
            } else {
                consecutiveCaptureFailures = 0;
            }

            Minecraft minecraft = Minecraft.getMinecraft();
            String username = minecraft.thePlayer == null
                    ? "Player"
                    : minecraft.thePlayer.getName();
            frameProcessor.process(
                    frame,
                    frameConfig,
                    getEffectiveRecordingMillis(),
                    username);
            if (activeReplay != null && activeReplay.isActive()) {
                try {
                    /*
                     * ReplayBuffer copies/downscales synchronously, before
                     * the encoder assumes ownership and eventually releases
                     * this pooled frame.
                     */
                    activeReplay.addFrame(
                            frame.getPixels(),
                            frame.getWidth(),
                            frame.getHeight(),
                            frame.getCapturedAtNanos());
                } catch (Throwable replayFailure) {
                    RecordishMod.LOGGER.warn(
                            "Unable to copy a recording frame into the replay "
                                    + "buffer.",
                            replayFailure);
                }
            }
            if (activeEncoder.submit(frame)) {
                alignTimelineToFirstFrame(
                    frame.getCapturedAtNanos());
                capturedFrames.incrementAndGet();
            } else {
                skippedFrames.incrementAndGet();
            }
        } catch (Throwable throwable) {
            if (frame != null) {
                try {
                    frame.release();
                } catch (Throwable ignored) {
                }
            }
            failedCaptures.incrementAndGet();
            lastFailure = throwable;
            if (failedCaptures.get() <= 3L
                    || failedCaptures.get() % 60L == 0L) {
                RecordishMod.LOGGER.error(
                        "Failed to capture a recording frame.",
                        throwable);
            }
        } finally {
            maybeWarnDroppedFrames(activeEncoder);
        }

        enforceLimits(now);
    }

    /**
     * ScreenCapture rotates among three read sources every 15 black frames and
     * resets its consecutive counter at each rotation. Track those rotations
     * across the session, then require one more black confirmation window
     * before presenting an actionable warning.
     */
    private void maybeWarnBlackScreen(ScreenCapture capture) {
        if (blackScreenWarned || capture == null) return;

        long totalFrames = capture.getTotalFramesProduced();
        long totalBlackFrames = capture.getTotalBlackFrames();
        long newFrames = totalFrames - observedCaptureFrames;
        long newBlackFrames = totalBlackFrames - observedBlackFrames;
        observedCaptureFrames = totalFrames;
        observedBlackFrames = totalBlackFrames;
        if (newFrames <= 0L) return;

        String source = capture.getReadSourceName();
        if (newBlackFrames < newFrames) {
            observedCaptureSource = source;
            blackCaptureSourceSwitches = 0;
            blackFramesAfterSourceExhaustion = 0;
            return;
        }

        boolean sourcesWereExhausted =
                blackCaptureSourceSwitches
                        >= CAPTURE_SOURCE_SWITCHES_TO_EXHAUST;
        if (observedCaptureSource == null) {
            observedCaptureSource = source;
        } else if (!observedCaptureSource.equals(source)) {
            observedCaptureSource = source;
            blackCaptureSourceSwitches++;
        }
        if (sourcesWereExhausted) {
            blackFramesAfterSourceExhaustion +=
                    (int) Math.min(Integer.MAX_VALUE, newBlackFrames);
        }
        if (blackCaptureSourceSwitches
                    < CAPTURE_SOURCE_SWITCHES_TO_EXHAUST
                || blackFramesAfterSourceExhaustion
                    < BLACK_CONFIRMATION_FRAMES) {
            return;
        }

        blackScreenWarned = true;
        RecordishMod.LOGGER.error(
                "Screen capture stayed black after every capture source was "
                        + "tried ({} black / {} total; source {}).",
                Long.valueOf(totalBlackFrames),
                Long.valueOf(totalFrames),
                source);
        RecordishMessages.send(
                ChatCategory.WARNINGS,
                "\u00a7eRecordish is capturing only black frames after "
                        + "trying every source. Disable shaders or OptiFine "
                        + "Fast Render, update your GPU driver, or try the "
                        + "Software encoder.");
    }

    private void maybeWarnDroppedFrames(FFmpegEncoder activeEncoder) {
        if (droppedFrameWarningSent) return;

        long dropped = skippedFrames.get()
                + failedCaptures.get()
                + (activeEncoder == null
                    ? 0L
                    : activeEncoder.getDroppedFrames());
        if (dropped < DROPPED_FRAME_WARNING_THRESHOLD) return;

        droppedFrameWarningSent = true;
        RecordishMod.LOGGER.warn(
                "Performance issue detected - droppedFrames={} queue={}/{}.",
                Long.valueOf(dropped),
                Integer.valueOf(activeEncoder == null
                        ? 0
                        : activeEncoder.getQueueSize()),
                Integer.valueOf(activeEncoder == null
                        ? 0
                        : activeEncoder.getQueueCapacity()));
        RecordishMessages.send(
                ChatCategory.RECORDING,
                "\u00a7eRecordish is dropping frames. Try 720p/30fps "
                        + "or Quality=Performance for smoother recording.");
    }

    private void alignTimelineToFirstFrame(
            long capturedAtNanos) {
        if (firstFrameTimelineAligned
                || capturedAtNanos <= 0L) {
            return;
        }
        AudioCaptureSession session;
        synchronized (lock) {
            if (firstFrameTimelineAligned
                    || state != State.RECORDING) {
                return;
            }
            firstFrameTimelineAligned = true;
            startedAtNanos = capturedAtNanos;
            lastDiskCheckNanos = capturedAtNanos;
            session = audioSession;
        }
        if (session != null) {
            session.alignVideoStartNanos(capturedAtNanos);
        }
    }

    private void enforceLimits(long now) {
        RecordishConfig config = RecordishConfig.get();
        FFmpegEncoder activeEncoder = encoder;
        if (activeEncoder != null && config.maxFileSizeMB > 0) {
            long maximum =
                    config.maxFileSizeMB * 1024L * 1024L;
            if (activeEncoder.getTemporarySizeBytes() >= maximum) {
                stopRecording(StopReason.FILE_SIZE_LIMIT);
                return;
            }
        }

        if (now - lastDiskCheckNanos < DISK_CHECK_INTERVAL_NANOS) {
            return;
        }
        lastDiskCheckNanos = now;
        DiskSpaceGuardian.DiskCheckResult result =
                DiskSpaceGuardian.check(config.getOutputDirectory(), config);
        if (result.isBlocked()) {
            RecordishMessages.send(
                    ChatCategory.WARNINGS,
                    result.getMessage());
            stopRecording(StopReason.DISK_SPACE);
        }
    }

    /**
     * Reduces current-session readback pressure in small steps when either the
     * game or FFmpeg queue is struggling, then restores the requested capture
     * rate after recovery. The encoder output remains at the requested CFR.
     */
    private void updateAdaptiveCaptureRate(long nowNanos) {
        RecordishConfig config = RecordishConfig.get();
        if (!config.perfOptimizerEnabled
                || !config.perfAutoAdjust
                || !config.perfActionLowerFps) {
            if (baseFrameIntervalNanos > 0L
                    && frameIntervalNanos
                        != baseFrameIntervalNanos) {
                frameIntervalNanos = baseFrameIntervalNanos;
                governorCurrentFps = recordingFps;
            }
            return;
        }
        if (nowNanos - lastGovernorCheckNanos
                < 1_000_000_000L) {
            return;
        }
        lastGovernorCheckNanos = nowNanos;

        FFmpegEncoder active = encoder;
        double queueRatio = active == null
                || active.getQueueCapacity() <= 0
            ? 0.0D
            : active.getQueueSize()
                / (double) active.getQueueCapacity();
        int gameFps = Minecraft.getDebugFPS();
        int minimum = Math.max(10, config.perfMinFps);
        int baseFps = Math.max(1, recordingFps);
        int current = governorCurrentFps <= 0
            ? baseFps
            : governorCurrentFps;
        int next = current;
        if ((gameFps > 0 && gameFps < minimum)
                || queueRatio >= 0.75D) {
            next = Math.max(
                Math.min(15, baseFps),
                current - 5);
        } else if ((gameFps <= 0
                    || gameFps > minimum + 5)
                && queueRatio < 0.35D
                && current < baseFps) {
            next = Math.min(baseFps, current + 5);
        }
        if (next != current) {
            governorCurrentFps = next;
            frameIntervalNanos = Math.max(
                1L,
                1_000_000_000L / next);
            RecordishMod.LOGGER.info(
                "Adaptive capture rate changed from {} to {} FPS "
                    + "(game FPS {}, encoder queue {}%).",
                Integer.valueOf(current),
                Integer.valueOf(next),
                Integer.valueOf(gameFps),
                Integer.valueOf(
                    (int) Math.round(queueRatio * 100.0D)));
        }
    }

    public void togglePause() {
        if (state == State.RECORDING) {
            pauseRecording();
        } else if (state == State.PAUSED) {
            resumeRecording();
        }
    }

    public void pauseRecording() {
        AudioCaptureSession session;
        FFmpegEncoder activeEncoder;
        long pauseAt;
        synchronized (lock) {
            if (state != State.RECORDING) return;
            state = State.PAUSED;
            pauseStartedAtNanos = System.nanoTime();
            pauseAt = pauseStartedAtNanos;
            lastFrameCaptureNanos = 0L;
            session = audioSession;
            activeEncoder = encoder;
        }
        if (activeEncoder != null) activeEncoder.markPaused(pauseAt);
        if (session != null) session.pause();
        RecordishMessages.send(
                ChatCategory.RECORDING,
                "Recording paused.");
    }

    public void resumeRecording() {
        AudioCaptureSession session;
        FFmpegEncoder activeEncoder;
        long resumedAt;
        synchronized (lock) {
            if (state != State.PAUSED) return;
            long now = System.nanoTime();
            resumedAt = now;
            if (pauseStartedAtNanos > 0L) {
                totalPausedNanos += now - pauseStartedAtNanos;
            }
            pauseStartedAtNanos = 0L;
            lastFrameCaptureNanos = 0L;
            session = audioSession;
            activeEncoder = encoder;
            state = State.RECORDING;
        }
        if (activeEncoder != null) activeEncoder.markResumed(resumedAt);
        closeReplayCapture();
        if (session != null) session.resume();
        RecordishMessages.send(
                ChatCategory.RECORDING,
                "Recording resumed.");
    }

    public void stopRecording() {
        stopRecording(StopReason.MANUAL);
    }

    public void stopRecording(StopReason reason) {
        stopRecording(reason, true);
    }

    private void stopRecording(
            StopReason requestedReason,
            boolean closeRenderResources) {
        final FFmpegEncoder finishingEncoder;
        final AudioCaptureSession finishingAudio;
        final ScreenCapture finishingCapture;
        final List<RecordingBookmark> finishingBookmarks;
        final long duration;
        final StopReason reason = requestedReason == null
                ? StopReason.MANUAL
                : requestedReason;
        final FinalizationHandle finalization;

        synchronized (lock) {
            if (state != State.RECORDING
                    && state != State.PAUSED
                    && state != State.STARTING) {
                return;
            }
            if (state == State.PAUSED && pauseStartedAtNanos > 0L) {
                totalPausedNanos += System.nanoTime()
                        - pauseStartedAtNanos;
                pauseStartedAtNanos = 0L;
            }
            duration = getEffectiveRecordingMillisLocked();
            state = State.STOPPING;
            finishingEncoder = encoder;
            finishingAudio = audioSession;
            finishingCapture = screenCapture;
            finishingBookmarks = snapshotBookmarks();
            finalization = new FinalizationHandle(
                    reason,
                    finishingEncoder,
                    finishingAudio);
            activeFinalization = finalization;
            screenCapture = null;
            microphoneActive = false;
        }

        RecordishMod.LOGGER.info(
                "Recording stop requested: reason={} requestedOutput={} "
                        + "temporaryVideo={} capturedFrames={} droppedFrames={} "
                        + "failedCaptures={}",
                reason,
                finalization.requestedOutput,
                finalization.temporaryVideo,
                Long.valueOf(capturedFrames.get()),
                Long.valueOf(getDroppedFrames()),
                Long.valueOf(failedCaptures.get()));

        // PBOs must be released on the client/render thread.
        if (finishingCapture != null && closeRenderResources) {
            try {
                finishingCapture.close();
            } catch (Throwable closeFailure) {
                RecordishMod.LOGGER.warn(
                        "Unable to close screen capture cleanly.",
                        closeFailure);
            }
        } else if (finishingCapture != null) {
            RecordishMod.LOGGER.info(
                    "Emergency shutdown is leaving screen-capture OpenGL "
                            + "objects for JVM teardown.");
        }

        if (closeRenderResources) {
            RecordishMessages.send(
                    ChatCategory.RECORDING,
                    "Finalizing recording...");
        }

        Thread finalizer = new Thread(new Runnable() {
            @Override
            public void run() {
                runFinalization(
                        finalization,
                        finishingBookmarks,
                        duration);
            }
        }, "Recordish-Finalizer");
        // Non-daemon on purpose: a normal game shutdown should not abandon an
        // otherwise recoverable FFmpeg stream halfway through its trailer.
        finalizer.setDaemon(false);
        finalization.worker = finalizer;
        try {
            finalizer.start();
        } catch (Throwable startFailure) {
            RecordishMod.LOGGER.error(
                    "Could not start the recording finalizer thread; "
                            + "finalizing on the caller thread.",
                    startFailure);
            finalization.worker = Thread.currentThread();
            runFinalization(
                    finalization,
                    finishingBookmarks,
                    duration);
        }
    }

    private void runFinalization(
            FinalizationHandle finalization,
            List<RecordingBookmark> finishingBookmarks,
            long duration) {
        try {
            finalizeRecording(
                    finalization,
                    finishingBookmarks,
                    duration);
        } finally {
            // A failure before finishVideo() must still release shutdown's
            // phase-A waiter.
            finalization.mediaSealed.countDown();
            finalization.completed.countDown();
            synchronized (lock) {
                if (activeFinalization == finalization) {
                    activeFinalization = null;
                }
            }
        }
    }

    private void finalizeRecording(
            FinalizationHandle finalization,
            List<RecordingBookmark> finishingBookmarks,
            long duration) {
        FFmpegEncoder finishingEncoder = finalization.encoder;
        AudioCaptureSession finishingAudio = finalization.audio;
        StopReason reason = finalization.reason;
        Path finalized = null;
        Throwable failure = null;
        List<AudioCaptureSession.AudioTrack> audioTracks =
                Collections.<AudioCaptureSession.AudioTrack>emptyList();
        RecordishMod.LOGGER.info(
                "Recording finalizer started: reason={} requestedOutput={} "
                        + "temporaryVideo={}",
                reason,
                finalization.requestedOutput,
                finalization.temporaryVideo);
        try {
            audioTracks = finishingAudio == null
                    ? Collections
                            .<AudioCaptureSession.AudioTrack>emptyList()
                    : finishingAudio.finish();
            if (finishingEncoder == null) {
                throw new IOException(
                        "The video encoder was unavailable while stopping.");
            }
            Path temporaryVideo = finishingEncoder.finishVideo();
            finalization.sealedVideo = temporaryVideo;
            finalization.mediaSealed.countDown();
            RecordishMod.LOGGER.info(
                    "Recording media sealed: reason={} temporaryVideo={} "
                            + "bytes={} audioTracks={} rawAudio={}",
                    reason,
                    temporaryVideo,
                    Long.valueOf(safeFileSize(temporaryVideo)),
                    Integer.valueOf(audioTracks.size()),
                    audioTrackPaths(audioTracks));
            finalized = RecordingFinalizer.finalizeRecording(
                    temporaryVideo,
                    finishingEncoder.getRequestedOutput(),
                    RecordishConfig.get(),
                    audioTracks);

            if (!finishingBookmarks.isEmpty()) {
                saveBookmarkFile(finalized, finishingBookmarks);
                RecordishConfig config = RecordishConfig.get();
                if (config.exportChapterFile) {
                    ChapterManager.writeChapterTextFile(
                            finalized,
                            finishingBookmarks);
                }
                if (config.embedChaptersInVideo) {
                    Path chaptered = ChapterManager.embedChapters(
                            finalized,
                            finishingBookmarks,
                            duration);
                    if (chaptered != null) finalized = chaptered;
                }
            }
        } catch (Throwable throwable) {
            failure = throwable;
            finalization.failure = throwable;
            if (finishingAudio != null) {
                finishingAudio.preserveRawOutputs();
                finishingAudio.abort();
            }
            if (finishingEncoder != null) {
                finishingEncoder.abort();
            }
            if (finalized == null) {
                finalized = RecordingFinalizer.publishRecoverableVideo(
                        finalization.sealedVideo,
                        finalization.requestedOutput);
            }
            RecordishMod.LOGGER.error(
                    "Unable to finalize recording normally. reason={} "
                            + "requestedOutput={} temporaryVideo={} "
                            + "recoveredOutput={}",
                    reason,
                    finalization.requestedOutput,
                    finalization.temporaryVideo,
                    finalized,
                    throwable);
        }

        final Path result = finalized;
        final Throwable resultFailure = failure;
        final boolean recoveryResult = isRecoveryVideo(result);
        synchronized (lock) {
            encoder = null;
            audioSession = null;
            currentOutputFile = result;
            if (result != null) lastOutputFile = result;
            lastFailure = resultFailure;
            state = State.IDLE;
        }

        if (resultFailure == null && result != null) {
            RecordishMod.LOGGER.info(
                    "Recording finalization completed: reason={} output={} "
                            + "bytes={} durationMs={}",
                    reason,
                    result,
                    Long.valueOf(safeFileSize(result)),
                    Long.valueOf(duration));
        } else if (recoveryResult) {
            RecordishMod.LOGGER.warn(
                    "Recording finalization fell back to a playable recovery "
                            + "file: reason={} output={} bytes={} cause={}",
                    reason,
                    result,
                    Long.valueOf(safeFileSize(result)),
                    safeMessage(resultFailure));
        } else if (result != null) {
            RecordishMod.LOGGER.warn(
                    "Recording output was saved, but optional post-processing "
                            + "failed: reason={} output={} bytes={} cause={}",
                    reason,
                    result,
                    Long.valueOf(safeFileSize(result)),
                    safeMessage(resultFailure));
        }

        Minecraft minecraft = Minecraft.getMinecraft();
        Runnable notification = new Runnable() {
            @Override
            public void run() {
                if (resultFailure == null && result != null) {
                    if (RecordishConfig.get()
                            .showPostRecordingToast) {
                        RecordishMessages.send(
                                ChatCategory.RECORDING,
                                "Saved recording: "
                                        + result.getFileName());
                    }
                } else if (recoveryResult) {
                    RecordishMessages.send(
                            ChatCategory.WARNINGS,
                            "Final MP4 mux failed, but a playable recovery "
                                    + "video was saved: "
                                    + result.getFileName());
                } else if (result != null) {
                    RecordishMessages.send(
                            ChatCategory.WARNINGS,
                            "Recording saved, but optional post-processing "
                                    + "failed: "
                                    + result.getFileName());
                } else {
                    RecordishMessages.error(
                            "Recording finalization failed: "
                                    + safeMessage(resultFailure));
                }
            }
        };
        if (minecraft != null) {
            minecraft.addScheduledTask(notification);
        }
    }

    public String addBookmark() {
        return addBookmark(null);
    }

    public String addBookmark(String requestedDescription) {
        if (state != State.RECORDING
                || !RecordishConfig.get().bookmarksEnabled) {
            return null;
        }
        bookmarkCounter++;
        String description = isBlank(requestedDescription)
                ? "Bookmark " + bookmarkCounter
                : requestedDescription.trim();
        RecordingBookmark bookmark = new RecordingBookmark(
                getEffectiveRecordingMillis(),
                description);
        bookmarks.add(bookmark);
        RecordishMessages.send(
                ChatCategory.BOOKMARKS,
                description + " added at "
                        + formatDuration(bookmark.getTimestampMs()) + ".");
        return description;
    }

    public void setPushToTalkActive(boolean active) {
        RecordishConfig config = RecordishConfig.get();
        AudioCaptureSession session = audioSession;
        microphoneActive = session != null
                && session.hasMicrophoneAudio()
                && config.captureMicrophone
                && (!config.microphonePushToTalk || active);
        if (session != null) {
            session.setMicrophoneGate(microphoneActive);
        }
    }

    public void stopForDisconnect() {
        if (RecordishConfig.get().stopOnDisconnect) {
            stopRecording(StopReason.DISCONNECT);
        }
    }

    public void shutdown() {
        shutdown(true);
    }

    /**
     * Begins the normal render-thread shutdown without blocking Minecraft's
     * own quit method before it can set {@code running=false}. The later
     * applet shutdown hook performs the bounded wait.
     */
    public void requestShutdown() {
        RecordishMod.LOGGER.info(
                "Recording shutdown requested: state={}",
                state);
        stopRecording(StopReason.SHUTDOWN, true);
    }

    /**
     * Last-resort JVM-hook path. The Minecraft render context may already be
     * gone, so it seals media without attempting PBO/OpenGL cleanup.
     */
    public void emergencyShutdown() {
        shutdown(false);
    }

    private void shutdown(boolean closeRenderResources) {
        RecordishMod.LOGGER.info(
                "Recording shutdown entered: state={} renderCleanup={}",
                state,
                Boolean.valueOf(closeRenderResources));
        stopRecording(StopReason.SHUTDOWN, closeRenderResources);
        awaitFinalizer();
        if (closeRenderResources) {
            closeReplayCapture();
        }
        ReplayBuffer.getInstance().shutdownAndAwait(
                SHUTDOWN_REPLAY_TIMEOUT_MILLIS);
        RecordishConfig.get().save();
        RecordishMod.LOGGER.info(
                "Recording shutdown completed: state={} activeFinalizer={}",
                state,
                Boolean.valueOf(activeFinalization != null));
    }

    /**
     * Waits in two stages. The first stage is the data-safety boundary: once
     * FFmpeg has written and verified the Matroska trailer, a playable video
     * exists even if the requested MP4/audio mux cannot finish. The second
     * stage waits for the normal final container.
     */
    private void awaitFinalizer() {
        FinalizationHandle pending = activeFinalization;
        if (pending == null || pending.worker == Thread.currentThread()) {
            return;
        }

        RecordishMod.LOGGER.info(
                "Awaiting recording media seal during shutdown: reason={} "
                        + "temporaryVideo={} timeoutSeconds={}",
                pending.reason,
                pending.temporaryVideo,
                Long.valueOf(
                        SHUTDOWN_MEDIA_SEAL_TIMEOUT_MILLIS / 1000L));

        boolean interrupted = false;
        boolean sealedSignal = false;
        try {
            sealedSignal = pending.mediaSealed.await(
                    SHUTDOWN_MEDIA_SEAL_TIMEOUT_MILLIS,
                    TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            interrupted = true;
        }

        if (!sealedSignal) {
            interruptFinalization(
                    pending,
                    "media seal",
                    true);
        } else if (pending.completed.getCount() > 0L) {
            RecordishMod.LOGGER.info(
                    "Recording media seal finished; awaiting final mux: "
                            + "sealedVideo={} timeoutSeconds={}",
                    pending.sealedVideo,
                    Long.valueOf(SHUTDOWN_MUX_TIMEOUT_MILLIS / 1000L));
            boolean completed = false;
            try {
                completed = pending.completed.await(
                        SHUTDOWN_MUX_TIMEOUT_MILLIS,
                        TimeUnit.MILLISECONDS);
            } catch (InterruptedException exception) {
                interrupted = true;
            }
            if (!completed) {
                interruptFinalization(
                        pending,
                        "final mux",
                        false);
            }
        }

        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void interruptFinalization(
            FinalizationHandle pending,
            String stage,
            boolean abortEncoder) {
        if (pending.audio != null) {
            pending.audio.preserveRawOutputs();
        }
        RecordishMod.LOGGER.error(
                "Recording {} exceeded its shutdown limit. Interrupting "
                        + "finalization while preserving recoverable media: "
                        + "requestedOutput={} temporaryVideo={} sealedVideo={}",
                stage,
                pending.requestedOutput,
                pending.temporaryVideo,
                pending.sealedVideo);

        Thread worker = pending.worker;
        if (worker != null && worker != Thread.currentThread()) {
            worker.interrupt();
        }
        if (abortEncoder && pending.encoder != null) {
            pending.encoder.forceAbort();
        }
        try {
            pending.completed.await(
                    SHUTDOWN_INTERRUPT_GRACE_MILLIS,
                    TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        if (pending.completed.getCount() > 0L) {
            RecordishMod.LOGGER.error(
                    "The recording finalizer did not stop after interruption. "
                            + "JVM shutdown will continue; recovery paths are "
                            + "listed above.");
        }
    }

    public State getState() {
        return state;
    }

    public boolean isRecording() {
        return state == State.RECORDING;
    }

    public boolean isPaused() {
        return state == State.PAUSED;
    }

    public boolean isActiveOrStopping() {
        return state != State.IDLE;
    }

    public boolean isMicrophoneActive() {
        return microphoneActive;
    }

    /**
     * Returns whether this recording owns a usable microphone capture stream.
     *
     * <p>This is deliberately separate from {@link #isMicrophoneActive()}:
     * Push-to-Talk keeps the stream open while its gate is idle.</p>
     */
    public boolean isMicrophoneCapturing() {
        AudioCaptureSession session = audioSession;
        return session != null && session.hasMicrophoneAudio();
    }

    public long getEffectiveRecordingMillis() {
        synchronized (lock) {
            return getEffectiveRecordingMillisLocked();
        }
    }

    private long getEffectiveRecordingMillisLocked() {
        if (startedAtNanos == 0L || state == State.IDLE) return 0L;
        long now = System.nanoTime();
        long paused = totalPausedNanos;
        if (state == State.PAUSED && pauseStartedAtNanos > 0L) {
            paused += now - pauseStartedAtNanos;
        }
        return Math.max(
                0L,
                (now - startedAtNanos - paused) / 1_000_000L);
    }

    public long getCapturedFrames() {
        return capturedFrames.get();
    }

    public long getDroppedFrames() {
        FFmpegEncoder active = encoder;
        return skippedFrames.get()
                + failedCaptures.get()
                + (active == null ? 0L : active.getDroppedFrames());
    }

    public QueueHealth getQueueHealth() {
        FFmpegEncoder active = encoder;
        if (active == null) return QueueHealth.OK;
        int size = active.getQueueSize();
        int capacity = Math.max(1, active.getQueueCapacity());
        double ratio = size / (double) capacity;
        if (ratio >= 0.9D) return QueueHealth.CRITICAL;
        if (ratio >= 0.5D) return QueueHealth.SLOW;
        return QueueHealth.OK;
    }

    public int getQueueSize() {
        FFmpegEncoder active = encoder;
        return active == null ? 0 : Math.max(0, active.getQueueSize());
    }

    public int getQueueCapacity() {
        FFmpegEncoder active = encoder;
        return active == null ? 0 : Math.max(0, active.getQueueCapacity());
    }

    public double getCaptureFpsEstimate() {
        long elapsed = getEffectiveRecordingMillis();
        return elapsed <= 0L
                ? 0.0D
                : capturedFrames.get() * 1000.0D / elapsed;
    }

    public double getEncoderFpsEstimate() {
        FFmpegEncoder active = encoder;
        long elapsed = getEffectiveRecordingMillis();
        return active == null || elapsed <= 0L
                ? 0.0D
                : active.getWrittenFrames() * 1000.0D / elapsed;
    }

    public long getUsedMemoryMiB() {
        Runtime runtime = Runtime.getRuntime();
        return Math.max(
                0L,
                runtime.totalMemory() - runtime.freeMemory())
                / (1024L * 1024L);
    }

    public int getRecordingWidth() {
        return recordingWidth;
    }

    public int getRecordingHeight() {
        return recordingHeight;
    }

    public int getRecordingFps() {
        return recordingFps;
    }

    public Path getCurrentOutputFile() {
        return currentOutputFile == null
                ? lastOutputFile
                : currentOutputFile;
    }

    public Path getCurrentOutputDirectory() {
        return RecordishConfig.get().getOutputDirectory();
    }

    public long getCurrentFileSizeBytes() {
        FFmpegEncoder active = encoder;
        if (active != null) return active.getTemporarySizeBytes();
        Path path = getCurrentOutputFile();
        try {
            return path != null && Files.isRegularFile(path)
                    ? Files.size(path)
                    : 0L;
        } catch (IOException ignored) {
            return 0L;
        }
    }

    public String getEstimatedFileSize() {
        long bytes = getCurrentFileSizeBytes();
        if (bytes <= 0L) {
            RecordishConfig config = RecordishConfig.get();
            long millis = getEffectiveRecordingMillis();
            long bitsPerSecond = parseBitrateBits(
                    config.resolveBitrate(
                            Math.max(2, recordingWidth),
                            Math.max(2, recordingHeight)));
            bytes = bitsPerSecond * millis / 8000L;
        }
        return formatBytes(bytes);
    }

    public String getPendingToastMessage() {
        if (pendingToastMessage != null
                && System.currentTimeMillis()
                        > pendingToastExpiresAtMillis) {
            pendingToastMessage = null;
        }
        return pendingToastMessage;
    }

    public void dismissToast() {
        pendingToastMessage = null;
    }

    public Throwable getLastFailure() {
        return lastFailure;
    }

    public List<RecordingBookmark> getBookmarks() {
        return Collections.unmodifiableList(snapshotBookmarks());
    }

    public int getBookmarkCount() {
        return bookmarks.size();
    }

    private List<RecordingBookmark> snapshotBookmarks() {
        synchronized (bookmarks) {
            return new ArrayList<RecordingBookmark>(bookmarks);
        }
    }

    private static void saveBookmarkFile(
            Path videoFile,
            List<RecordingBookmark> entries) {
        if (videoFile == null || entries == null || entries.isEmpty()) return;
        try {
            String filename = videoFile.getFileName().toString();
            int dot = filename.lastIndexOf('.');
            String stem = dot > 0
                    ? filename.substring(0, dot)
                    : filename;
            Path output =
                    videoFile.resolveSibling(stem + "_bookmarks.txt");
            List<String> lines = new ArrayList<String>();
            lines.add("Recording Bookmarks for: " + filename);
            lines.add("Generated by Recordish");
            lines.add("");
            for (RecordingBookmark entry : entries) {
                lines.add(entry.toFileLine());
            }
            Files.write(
                    output,
                    lines,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        } catch (IOException exception) {
            RecordishMod.LOGGER.warn(
                    "Unable to save recording bookmarks.",
                    exception);
        }
    }

    public static String formatDuration(long elapsedMillis) {
        long totalSeconds = Math.max(0L, elapsedMillis) / 1000L;
        long hours = totalSeconds / 3600L;
        long minutes = totalSeconds % 3600L / 60L;
        long seconds = totalSeconds % 60L;
        return hours > 0L
                ? String.format(
                        Locale.ROOT,
                        "%d:%02d:%02d",
                        hours,
                        minutes,
                        seconds)
                : String.format(
                        Locale.ROOT,
                        "%02d:%02d",
                        minutes,
                        seconds);
    }

    public static String formatBytes(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        double kib = bytes / 1024.0D;
        if (kib < 1024.0D) {
            return String.format(Locale.ROOT, "%.1f KiB", kib);
        }
        double mib = kib / 1024.0D;
        if (mib < 1024.0D) {
            return String.format(Locale.ROOT, "%.1f MiB", mib);
        }
        return String.format(Locale.ROOT, "%.2f GiB", mib / 1024.0D);
    }

    private static long safeFileSize(Path path) {
        try {
            return path != null && Files.isRegularFile(path)
                    ? Files.size(path)
                    : 0L;
        } catch (IOException | SecurityException ignored) {
            return 0L;
        }
    }

    private static boolean isRecoveryVideo(Path path) {
        if (path == null || path.getFileName() == null) {
            return false;
        }
        String name = path.getFileName().toString()
                .toLowerCase(Locale.ROOT);
        return name.endsWith(".mkv")
                && (name.contains("-recovered.mkv")
                    || name.matches(".*-recovered-\\d+\\.mkv"));
    }

    private static String audioTrackPaths(
            List<AudioCaptureSession.AudioTrack> tracks) {
        if (tracks == null || tracks.isEmpty()) {
            return "[]";
        }
        StringBuilder paths = new StringBuilder("[");
        for (AudioCaptureSession.AudioTrack track : tracks) {
            if (track == null || track.getPath() == null) {
                continue;
            }
            if (paths.length() > 1) {
                paths.append(", ");
            }
            paths.append(track.getPath().toAbsolutePath());
        }
        return paths.append(']').toString();
    }

    private static long parseBitrateBits(String value) {
        if (isBlank(value)) return 8_000_000L;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        long multiplier = 1L;
        if (normalized.endsWith("m")) {
            multiplier = 1_000_000L;
            normalized = normalized.substring(
                    0,
                    normalized.length() - 1);
        } else if (normalized.endsWith("k")) {
            multiplier = 1000L;
            normalized = normalized.substring(
                    0,
                    normalized.length() - 1);
        }
        try {
            return Math.max(
                    1L,
                    Long.parseLong(normalized) * multiplier);
        } catch (NumberFormatException ignored) {
            return 8_000_000L;
        }
    }

    private static int makeEven(int value) {
        int safe = Math.max(2, value);
        return (safe & 1) == 0 ? safe : safe - 1;
    }

    private static String safeMessage(Throwable throwable) {
        if (throwable == null) return "unknown error";
        String message = throwable.getMessage();
        return isBlank(message)
                ? throwable.getClass().getSimpleName()
                : stripFormatting(message);
    }

    private static String stripFormatting(String value) {
        if (value == null) return "";
        return EnumChatFormatting.getTextWithoutFormattingCodes(value);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /**
     * Keeps the rolling buffer active during a normal recording while using
     * that recording's already-captured frame as the replay source.
     */
    private ReplayBuffer prepareReplayForSharedCapture(
            RecordishConfig config) {
        ReplayBuffer replay = ReplayBuffer.getInstance();
        if (!shouldBufferReplay(config)) {
            closeReplayCapture();
            if (replay.isActive()) {
                replay.stop();
            }
            return null;
        }

        if (replayScreenCapture != null) {
            closeReplayCapture();
        }
        configureReplayBuffer(
                config,
                makeEven(Math.max(2, recordingWidth)),
                makeEven(Math.max(2, recordingHeight)));
        return replay.isActive() ? replay : null;
    }

    private void captureReplayFrame() {
        RecordishConfig config = RecordishConfig.get();
        Minecraft minecraft = Minecraft.getMinecraft();
        ReplayBuffer replay = ReplayBuffer.getInstance();
        if (!shouldBufferReplay(config)
                || !isInGameState(minecraft)) {
            closeReplayCapture();
            if (replay.isActive()) {
                replay.stop();
            }
            return;
        }

        int nativeWidth = makeEven(Math.max(2, minecraft.displayWidth));
        int nativeHeight = makeEven(Math.max(2, minecraft.displayHeight));
        RecordishConfig.CaptureDimensions dimensions =
                config.resolveCaptureDimensions(nativeWidth, nativeHeight);
        int[] sourceDimensions = ReplayBuffer.capSourceDimensions(
                dimensions.getWidth(),
                dimensions.getHeight());
        int width = sourceDimensions[0];
        int height = sourceDimensions[1];
        int fps = ReplayBuffer.resolveTargetFps(
                config.replayBufferQuality,
                height,
                requestedReplayFps(config));
        long now = System.nanoTime();
        long interval = Math.max(1L, 1_000_000_000L / fps);
        if (lastReplayCaptureNanos != 0L
                && now - lastReplayCaptureNanos < interval) {
            return;
        }
        lastReplayCaptureNanos = now;

        configureReplayBuffer(
            config,
            width,
            height);
        if (!replay.isActive()) return;

        try {
            if (replayScreenCapture == null
                    || replayCaptureWidth != width
                    || replayCaptureHeight != height) {
                closeReplayCapture();
                replayScreenCapture = new ScreenCapture();
                replayScreenCapture.prepare(width, height, 6);
                replayCaptureWidth = width;
                replayCaptureHeight = height;
            }
            CapturedFrame frame = replayScreenCapture.capture();
            if (frame == null) return;
            try {
                frameProcessor.processReplayCensors(frame, config);
                replay.addFrame(
                        frame.getPixels(),
                        frame.getWidth(),
                        frame.getHeight(),
                        frame.getCapturedAtNanos());
            } finally {
                frame.release();
            }
        } catch (Throwable throwable) {
            RecordishMod.LOGGER.warn(
                    "Replay frame capture failed.",
                    throwable);
        }
    }

    private void configureReplayBuffer(
            RecordishConfig config,
            int width,
            int height) {
        if (!shouldBufferReplay(config)) return;
        ReplayBuffer replay = ReplayBuffer.getInstance();
        int fps = requestedReplayFps(config);
        int effectiveFps = ReplayBuffer.resolveTargetFps(
                config.replayBufferQuality,
                height,
                fps);
        int[] expectedDimensions =
                ReplayBuffer.resolveStoredDimensions(
                        width,
                        height,
                        config.replayBufferQuality);
        int expectedWidth = expectedDimensions[0];
        int expectedHeight = expectedDimensions[1];
        int duration = requestedReplayDuration(config);
        if (replay.isActive()
                && replay.getStoredWidth() == expectedWidth
                && replay.getStoredHeight() == expectedHeight
                && replay.getTargetFps() == effectiveFps
                && replay.getRequestedDurationSeconds()
                    >= duration) {
            return;
        }
        // A save pins the current snapshot's backing chunks. Keep accepting
        // frames into the existing buffer until that snapshot is released,
        // then retry any requested reconfiguration on the next render.
        if (replay.isSaving()) {
            return;
        }
        String warning = ReplayBuffer.checkMemorySafety(
                width,
                height,
                fps,
                duration);
        if (warning != null) {
            RecordishMessages.send(
                    ChatCategory.WARNINGS,
                    warning);
            return;
        }
        replay.start(
                width,
                height,
                fps,
                duration,
                config.replayBufferQuality);
    }

    private static boolean shouldBufferReplay(
            RecordishConfig config) {
        return config != null
            && (config.replayBufferEnabled
                || isKillMontageCaptureRequested(config));
    }

    private static boolean isKillMontageCaptureRequested(
            RecordishConfig config) {
        return isKillMontageConfigured(config)
            && AutoClipManager.getInstance()
                .isKillMontageCaptureArmed();
    }

    private static boolean isKillMontageConfigured(
            RecordishConfig config) {
        return config != null
            && config.autoClipEnabled
            && config.autoClipKillMontage
            && (config.autoClipOnKill
                || config.autoClipOnPlayerKill);
    }

    private static int requestedReplayFps(
            RecordishConfig config) {
        if (config == null) return 30;
        int montageFps = Math.max(1, config.autoClipFps);
        if (config.replayBufferEnabled) {
            return isKillMontageConfigured(config)
                ? Math.max(config.getFps(), montageFps)
                : config.getFps();
        }
        return montageFps;
    }

    private static int requestedReplayDuration(
            RecordishConfig config) {
        if (config == null) return 30;
        int duration = config.replayBufferEnabled
            ? config.replayBufferDurationSeconds
            : 1;
        if (isKillMontageConfigured(config)) {
            duration = Math.max(
                duration,
                Math.max(
                    1,
                    Math.max(0, config.autoClipKillPreSeconds)
                        + Math.max(
                            0,
                            config.autoClipKillPostSeconds)));
        }
        return Math.max(1, duration);
    }

    private void closeReplayCapture() {
        ScreenCapture capture = replayScreenCapture;
        replayScreenCapture = null;
        replayCaptureWidth = 0;
        replayCaptureHeight = 0;
        lastReplayCaptureNanos = 0L;
        if (capture != null) {
            try {
                capture.close();
            } catch (Throwable throwable) {
                RecordishMod.LOGGER.debug(
                        "Unable to close replay capture.",
                        throwable);
            }
        }
    }

}
