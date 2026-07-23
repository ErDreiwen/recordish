package dev.recordable;

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

    private static final RecordingManager INSTANCE = new RecordingManager();
    private static final long DISK_CHECK_INTERVAL_NANOS = 5_000_000_000L;
    private static final long SHUTDOWN_FINALIZER_TIMEOUT_MILLIS = 45_000L;
    private static final long SHUTDOWN_INTERRUPT_GRACE_MILLIS = 2_000L;
    private static final long SHUTDOWN_REPLAY_TIMEOUT_MILLIS = 15_000L;

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
    private volatile Thread finalizerThread;
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
    private volatile String pendingToastMessage;
    private volatile long pendingToastExpiresAtMillis;
    private volatile Throwable lastFailure;

    private RecordingManager() {
    }

    public static RecordingManager getInstance() {
        return INSTANCE;
    }

    public void initialize() {
        FfmpegBundleManager.detectFfmpeg();
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
            RecordableMessages.send(
                    ChatCategory.RECORDING,
                    "The recorder is still starting.");
        } else {
            RecordableMessages.send(
                    ChatCategory.RECORDING,
                    "The recorder is already finalizing.");
        }
    }

    public void startRecording() {
        startRecording(null);
    }

    public void startRecording(String filePrefix) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (!isInGameState(minecraft)) {
            RecordableMessages.send(
                    ChatCategory.RECORDING,
                    "Join a world or server before recording.");
            return;
        }

        RecordableConfig config = RecordableConfig.get();
        config.sanitize();
        if (!config.enabled) {
            RecordableMessages.send(
                    ChatCategory.RECORDING,
                    "Record-able is disabled in its settings.");
            return;
        }

        synchronized (lock) {
            if (state != State.IDLE) return;
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
                        ? "FFmpeg is required. Open Record-able settings to install it."
                        : status.getError());
            }

            Path outputDirectory = config.getOutputDirectory();
            DiskSpaceGuardian.DiskCheckResult disk =
                    DiskSpaceGuardian.check(outputDirectory, config);
            if (disk.isBlocked()) {
                throw new IOException(stripFormatting(disk.getMessage()));
            }
            if (disk.isWarning()) {
                RecordableMessages.send(
                        ChatCategory.WARNINGS,
                        disk.getMessage());
            }

            int nativeWidth = makeEven(Math.max(2, minecraft.displayWidth));
            int nativeHeight = makeEven(Math.max(2, minecraft.displayHeight));
            RecordableConfig.CaptureDimensions dimensions =
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
                microphoneActive = config.captureMicrophone
                        && !config.microphonePushToTalk;
                state = State.RECORDING;
            }

            PerformanceMetrics.getInstance().reset();
            PerformanceOptimizer.getInstance().reset();
            if (config.autoMarkerOnStart && config.markersEnabled) {
                addBookmark("Recording started");
            }
            String audioDescription = newAudio == null
                    ? ""
                    : newAudio.getStatusDescription();
            RecordableMessages.send(
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
                RecordableMessages.send(
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
                RecordableMessages.send(
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
            RecordableMod.LOGGER.error("Unable to start recording.", throwable);
            RecordableMessages.error(
                    "Could not start recording: " + safeMessage(throwable));
        }
    }

    /**
     * Called from Forge's render-tick END phase while Minecraft's main
     * framebuffer is still available.
     */
    public void onRenderFrame() {
        // The replay/montage cadence is independent of the main recording
        // cadence. In particular, a 60 FPS montage must not be capped by a
        // concurrent 30 FPS recording.
        captureReplayFrame();
        if (state != State.RECORDING) {
            return;
        }

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
            RecordableMessages.error(
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

            FrameValidator.ValidationResult validation =
                    FrameValidator.validate(frame);
            if (!validation.isValid()) {
                consecutiveCaptureFailures++;
                if (consecutiveCaptureFailures == 1
                        || consecutiveCaptureFailures % 15 == 0) {
                    RecordableMod.LOGGER.warn(
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
                    RecordableConfig.get(),
                    getEffectiveRecordingMillis(),
                    username);
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
                RecordableMod.LOGGER.error(
                        "Failed to capture a recording frame.",
                        throwable);
            }
        }

        enforceLimits(now);
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
        RecordableConfig config = RecordableConfig.get();
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
            RecordableMessages.send(
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
        RecordableConfig config = RecordableConfig.get();
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
            RecordableMod.LOGGER.info(
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
        RecordableMessages.send(
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
        RecordableMessages.send(
                ChatCategory.RECORDING,
                "Recording resumed.");
    }

    public void stopRecording() {
        stopRecording(StopReason.MANUAL);
    }

    public void stopRecording(StopReason reason) {
        final FFmpegEncoder finishingEncoder;
        final AudioCaptureSession finishingAudio;
        final ScreenCapture finishingCapture;
        final List<RecordingBookmark> finishingBookmarks;
        final long duration;

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
            screenCapture = null;
            microphoneActive = false;
        }

        // PBOs must be released on the client/render thread.
        if (finishingCapture != null) {
            try {
                finishingCapture.close();
            } catch (Throwable closeFailure) {
                RecordableMod.LOGGER.warn(
                        "Unable to close screen capture cleanly.",
                        closeFailure);
            }
        }

        RecordableMessages.send(
                ChatCategory.RECORDING,
                "Finalizing recording...");

        Thread finalizer = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    finalizeRecording(
                            finishingEncoder,
                            finishingAudio,
                            finishingBookmarks,
                            duration,
                            reason == null ? StopReason.MANUAL : reason);
                } finally {
                    if (finalizerThread == Thread.currentThread()) {
                        finalizerThread = null;
                    }
                }
            }
        }, "Recordable-Finalizer");
        // Non-daemon on purpose: a normal game shutdown should not abandon an
        // otherwise recoverable FFmpeg stream halfway through its trailer.
        finalizer.setDaemon(false);
        finalizerThread = finalizer;
        finalizer.start();
    }

    private void finalizeRecording(
            FFmpegEncoder finishingEncoder,
            AudioCaptureSession finishingAudio,
            List<RecordingBookmark> finishingBookmarks,
            long duration,
            StopReason reason) {
        Path finalized = null;
        Throwable failure = null;
        try {
            List<AudioCaptureSession.AudioTrack> audioTracks =
                    finishingAudio == null
                            ? Collections
                                    .<AudioCaptureSession.AudioTrack>emptyList()
                            : finishingAudio.finish();
            if (finishingEncoder == null) {
                throw new IOException(
                        "The video encoder was unavailable while stopping.");
            }
            Path temporaryVideo = finishingEncoder.finishVideo();
            finalized = RecordingFinalizer.finalizeRecording(
                    temporaryVideo,
                    finishingEncoder.getRequestedOutput(),
                    RecordableConfig.get(),
                    audioTracks);

            if (!finishingBookmarks.isEmpty()) {
                saveBookmarkFile(finalized, finishingBookmarks);
                RecordableConfig config = RecordableConfig.get();
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
            if (finishingAudio != null) finishingAudio.abort();
            if (finishingEncoder != null) {
                finishingEncoder.abort();
            }
            RecordableMod.LOGGER.error(
                    "Unable to finalize recording.",
                    throwable);
        }

        final Path result = finalized;
        final Throwable resultFailure = failure;
        synchronized (lock) {
            encoder = null;
            audioSession = null;
            currentOutputFile = result;
            if (result != null) lastOutputFile = result;
            lastFailure = resultFailure;
            state = State.IDLE;
        }

        Minecraft minecraft = Minecraft.getMinecraft();
        Runnable notification = new Runnable() {
            @Override
            public void run() {
                if (resultFailure == null && result != null) {
                    pendingToastMessage =
                            "Saved " + result.getFileName();
                    pendingToastExpiresAtMillis =
                            System.currentTimeMillis() + 6000L;
                    RecordableMessages.send(
                            ChatCategory.RECORDING,
                            "Saved recording: " + result.toAbsolutePath());
                } else {
                    RecordableMessages.error(
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
                || !RecordableConfig.get().bookmarksEnabled) {
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
        RecordableMessages.send(
                ChatCategory.BOOKMARKS,
                description + " added at "
                        + formatDuration(bookmark.getTimestampMs()) + ".");
        return description;
    }

    public void setPushToTalkActive(boolean active) {
        RecordableConfig config = RecordableConfig.get();
        microphoneActive = config.captureMicrophone
                && (!config.microphonePushToTalk || active);
        AudioCaptureSession session = audioSession;
        if (session != null) {
            session.setMicrophoneGate(microphoneActive);
        }
    }

    public void stopForDisconnect() {
        if (RecordableConfig.get().stopOnDisconnect) {
            stopRecording(StopReason.DISCONNECT);
        }
    }

    public void shutdown() {
        stopRecording(StopReason.SHUTDOWN);
        awaitFinalizer();
        closeReplayCapture();
        ReplayBuffer.getInstance().shutdownAndAwait(
                SHUTDOWN_REPLAY_TIMEOUT_MILLIS);
        RecordableConfig.get().save();
    }

    /**
     * Minecraft 1.8.9 exits the JVM directly from its shutdown path. Give the
     * finalizer time to write the FFmpeg trailer and audio mux, but do not let
     * a wedged child process hold shutdown forever.
     */
    private void awaitFinalizer() {
        Thread pending = finalizerThread;
        if (pending == null || pending == Thread.currentThread()) return;

        boolean interrupted = false;
        long deadline = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(
                        SHUTDOWN_FINALIZER_TIMEOUT_MILLIS);
        while (pending.isAlive()) {
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0L) break;
            try {
                long remainingMillis = Math.max(
                        1L,
                        TimeUnit.NANOSECONDS.toMillis(remainingNanos));
                pending.join(remainingMillis);
            } catch (InterruptedException exception) {
                interrupted = true;
                break;
            }
        }

        if (pending.isAlive()) {
            FFmpegEncoder activeEncoder;
            AudioCaptureSession activeAudio;
            synchronized (lock) {
                activeEncoder = encoder;
                activeAudio = audioSession;
            }

            if (activeAudio != null) {
                activeAudio.preserveRawOutputs();
            }
            Path temporaryVideo = activeEncoder == null
                    ? null
                    : activeEncoder.getTemporaryVideo();
            RecordableMod.LOGGER.error(
                    "Recording finalization exceeded the {} second shutdown "
                            + "limit. Interrupting it; temporary media will be "
                            + "preserved{}.",
                    SHUTDOWN_FINALIZER_TIMEOUT_MILLIS / 1000L,
                    temporaryVideo == null
                            ? ""
                            : " at " + temporaryVideo.toAbsolutePath());

            pending.interrupt();
            if (activeEncoder != null) {
                activeEncoder.forceAbort();
            }
            try {
                pending.join(SHUTDOWN_INTERRUPT_GRACE_MILLIS);
            } catch (InterruptedException exception) {
                interrupted = true;
            }
            if (pending.isAlive()) {
                RecordableMod.LOGGER.error(
                        "The recording finalizer did not stop after being "
                                + "interrupted. JVM shutdown will now continue.");
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
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
        return RecordableConfig.get().getOutputDirectory();
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
            RecordableConfig config = RecordableConfig.get();
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
            lines.add("Generated by Record-able");
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
            RecordableMod.LOGGER.warn(
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

    private void captureReplayFrame() {
        RecordableConfig config = RecordableConfig.get();
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
        if (replay.isSaving()) {
            // The snapshot pins its source chunks. Pausing capture prevents a
            // second rolling window from accumulating beside those chunks.
            closeReplayCapture();
            return;
        }

        int nativeWidth = makeEven(Math.max(2, minecraft.displayWidth));
        int nativeHeight = makeEven(Math.max(2, minecraft.displayHeight));
        RecordableConfig.CaptureDimensions dimensions =
                config.resolveCaptureDimensions(nativeWidth, nativeHeight);
        int width = makeEven(dimensions.getWidth());
        int height = makeEven(dimensions.getHeight());
        int[] preset = RecordableConfig.resolveReplayPreset(
                config.replayBufferQuality,
                height,
                requestedReplayFps(config));
        int fps = Math.max(1, preset[1]);
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
                        frame.getCapturedAtNanos());
            } finally {
                frame.release();
            }
        } catch (Throwable throwable) {
            RecordableMod.LOGGER.warn(
                    "Replay frame capture failed.",
                    throwable);
        }
    }

    private void configureReplayBuffer(
            RecordableConfig config,
            int width,
            int height) {
        if (!shouldBufferReplay(config)) return;
        ReplayBuffer replay = ReplayBuffer.getInstance();
        int fps = requestedReplayFps(config);
        int[] preset = RecordableConfig.resolveReplayPreset(
                config.replayBufferQuality,
                height,
                fps);
        int effectiveFps = Math.max(
                1,
                Math.min(fps, Math.max(1, preset[1])));
        int expectedHeight = preset[0] <= 0
                ? height
                : Math.min(height, makeEven(preset[0]));
        double scale = expectedHeight / (double) height;
        int expectedWidth = makeEven(Math.max(
                2,
                (int) Math.round(width * scale)));
        int duration = requestedReplayDuration(config);
        if (replay.isActive()
                && replay.getSourceWidth() == width
                && replay.getSourceHeight() == height
                && replay.getStoredWidth() == expectedWidth
                && replay.getStoredHeight() == expectedHeight
                && replay.getTargetFps() == effectiveFps
                && replay.getRequestedDurationSeconds()
                    >= duration) {
            return;
        }
        String warning = ReplayBuffer.checkMemorySafety(
                width,
                height,
                fps,
                duration);
        if (warning != null) {
            RecordableMessages.send(
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
            RecordableConfig config) {
        return config != null
            && (config.replayBufferEnabled
                || isKillMontageCaptureRequested(config));
    }

    private static boolean isKillMontageCaptureRequested(
            RecordableConfig config) {
        return isKillMontageConfigured(config)
            && AutoClipManager.getInstance()
                .isKillMontageCaptureArmed();
    }

    private static boolean isKillMontageConfigured(
            RecordableConfig config) {
        return config != null
            && config.autoClipEnabled
            && config.autoClipKillMontage
            && (config.autoClipOnKill
                || config.autoClipOnPlayerKill);
    }

    private static int requestedReplayFps(
            RecordableConfig config) {
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
            RecordableConfig config) {
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
                RecordableMod.LOGGER.debug(
                        "Unable to close replay capture.",
                        throwable);
            }
        }
    }

}
