package dev.recordable;

import net.minecraft.client.Minecraft;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Disk-backed rolling RGB frame buffer. The render thread only scales/copies
 * accepted frames into a small bounded queue; a dedicated writer owns chunk
 * I/O and eviction.
 */
public final class ReplayBuffer {
    public enum SaveResult {
        ACCEPTED,
        INACTIVE,
        BUSY,
        WARMING_UP,
        FAILED
    }

    private static final ReplayBuffer INSTANCE = new ReplayBuffer();
    private static final DateTimeFormatter FILE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final long CHUNK_MAX_BYTES =
            32L * 1024L * 1024L;
    private static final int WRITE_QUEUE_CAPACITY = 4;
    private static final long SAVE_ABORT_GRACE_MILLIS = 2000L;
    private static final String RECOVERY_MARKER =
            ".preserve-replay-chunks";
    static final int MAX_REPLAY_WIDTH = 1280;
    static final int MAX_REPLAY_HEIGHT = 720;
    static final int MAX_REPLAY_FPS = 30;

    private final Object lock = new Object();
    private final Object saveLifecycleLock = new Object();
    private final Deque<FrameRef> index = new ArrayDeque<FrameRef>();
    private final Map<Integer, Chunk> chunks =
            new HashMap<Integer, Chunk>();
    private final Deque<Integer> pendingChunkDeletes =
            new ArrayDeque<Integer>();
    private final ArrayBlockingQueue<PendingFrame> writeQueue =
            new ArrayBlockingQueue<PendingFrame>(WRITE_QUEUE_CAPACITY);
    private final AtomicBoolean active = new AtomicBoolean(false);
    private final AtomicBoolean saving = new AtomicBoolean(false);
    private final AtomicLong diskBytes = new AtomicLong();
    private final AtomicLong droppedFrames = new AtomicLong();

    private volatile int sourceWidth;
    private volatile int sourceHeight;
    private volatile int storedWidth;
    private volatile int storedHeight;
    private volatile int targetFps;
    private volatile long frameIntervalNanos;
    private volatile long lastAcceptedNanos;
    private volatile long durationMillis;
    private volatile long diskBudgetBytes;
    private volatile int maximumFrameCount;
    private volatile int requestedDurationSeconds;
    private volatile int effectiveDurationSeconds;
    private volatile boolean pinnedForSave;
    private volatile boolean writerRunning;
    private volatile boolean preserveStorageOnShutdown;
    private volatile boolean shutdownRequested;
    private volatile boolean saveAbortRequested;
    private Path bufferDirectory;
    private Chunk currentChunk;
    private int nextChunkId;
    private Thread writerThread;
    private volatile Thread saveThread;
    private volatile Process saveProcess;
    private final AtomicLong submittedFrameSequence = new AtomicLong();
    private final AtomicLong writtenFrameSequence = new AtomicLong();

    private ReplayBuffer() {
    }

    public static ReplayBuffer getInstance() {
        return INSTANCE;
    }

    public void start(
            int width,
            int height,
            int fps,
            int durationSeconds) {
        start(
                width,
                height,
                fps,
                durationSeconds,
                RecordableConfig.get().replayBufferQuality);
    }

    public void start(
            int width,
            int height,
            int fps,
            int durationSeconds,
            String quality) {
        if (saving.get()) {
            RecordableMessages.send(
                    ChatCategory.REPLAY_BUFFER,
                    "Wait for the current replay save to finish.");
            return;
        }
        haltWriter();
        shutdownRequested = false;
        saveAbortRequested = false;
        int evenWidth = makeEven(Math.max(2, width));
        int evenHeight = makeEven(Math.max(2, height));
        int[] replayDimensions = resolveStoredDimensions(
                evenWidth,
                evenHeight,
                quality);
        int replayWidth = replayDimensions[0];
        int replayHeight = replayDimensions[1];
        int replayFps = resolveTargetFps(
                quality,
                evenHeight,
                fps);
        int seconds = Math.max(1, durationSeconds);
        long budget = calculateDiskBudget(
                RecordableConfig.get().getOutputDirectory(),
                replayWidth,
                replayHeight,
                replayFps,
                seconds);
        long bytesPerSecond = replayWidth * (long) replayHeight
                * 3L * replayFps;
        long budgetSeconds = bytesPerSecond <= 0L
                ? seconds
                : budget / bytesPerSecond;
        int retainedSeconds = (int) Math.max(
                1L,
                Math.min((long) seconds, budgetSeconds));

        synchronized (lock) {
            resetStorageLocked();
            preserveStorageOnShutdown = false;
            sourceWidth = evenWidth;
            sourceHeight = evenHeight;
            storedWidth = replayWidth;
            storedHeight = replayHeight;
            targetFps = replayFps;
            frameIntervalNanos = Math.max(
                    1L,
                    1_000_000_000L / replayFps);
            lastAcceptedNanos = 0L;
            requestedDurationSeconds = seconds;
            effectiveDurationSeconds = retainedSeconds;
            durationMillis = retainedSeconds * 1000L;
            maximumFrameCount = replayFps * retainedSeconds;
            diskBudgetBytes = budget;
            submittedFrameSequence.set(0L);
            writtenFrameSequence.set(0L);
            try {
                bufferDirectory = RecordableConfig.get()
                        .getOutputDirectory()
                        .resolve(".recordable-replay-buffer")
                        .toAbsolutePath()
                        .normalize();
                clearBufferDirectory(bufferDirectory);
                Files.createDirectories(bufferDirectory);
                openNewChunkLocked();
            } catch (IOException exception) {
                RecordableMod.LOGGER.error(
                        "Unable to initialize replay buffer storage.",
                        exception);
                resetStorageLocked();
                active.set(false);
                return;
            }
            active.set(true);
            startWriterLocked();
        }
        OpenALLoopbackCapture.getInstance().enableRollingBuffer(
            retainedSeconds * 1000L);
        RecordableMod.LOGGER.info(
                "Replay buffer started: source={}x{}, stored={}x{} @ {} FPS, requested={}s, effective={}s, budget={}",
                sourceWidth,
                sourceHeight,
                storedWidth,
                storedHeight,
                targetFps,
                seconds,
                retainedSeconds,
                RecordingManager.formatBytes(diskBudgetBytes));
        if (retainedSeconds < seconds) {
            String warning = "Replay buffer can retain about "
                    + retainedSeconds + "s of the requested "
                    + seconds + "s at " + storedWidth + "x"
                    + storedHeight + " @ " + targetFps
                    + " FPS. Lower replay quality/FPS or free disk space.";
            RecordableMessages.send(
                    ChatCategory.WARNINGS,
                    warning);
            RecordableMod.LOGGER.warn(warning);
        }
    }

    public void stop() {
        active.set(false);
        OpenALLoopbackCapture.getInstance().disableRollingBuffer();
        haltWriter();
        synchronized (lock) {
            if (!saving.get()
                    && !preserveStorageOnShutdown) {
                resetStorageLocked();
            }
        }
    }

    /**
     * Stops capture and gives an in-flight replay encode a bounded opportunity
     * to finish. If it exceeds the deadline, FFmpeg is terminated and the raw
     * chunk files are left in place for recovery instead of being deleted.
     */
    public void shutdownAndAwait(long timeoutMillis) {
        shutdownRequested = true;
        active.set(false);
        OpenALLoopbackCapture.getInstance().disableRollingBuffer();
        haltWriter();

        Thread pending;
        synchronized (saveLifecycleLock) {
            pending = saveThread;
        }
        if (pending == null || pending == Thread.currentThread()) {
            synchronized (lock) {
                if (!saving.get()
                        && !preserveStorageOnShutdown) {
                    resetStorageLocked();
                }
            }
            return;
        }

        boolean interrupted = false;
        long deadline = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(
                        Math.max(0L, timeoutMillis));
        while (pending.isAlive()) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0L) break;
            try {
                pending.join(Math.max(
                        1L,
                        TimeUnit.NANOSECONDS.toMillis(remaining)));
            } catch (InterruptedException exception) {
                interrupted = true;
                break;
            }
        }

        if (pending.isAlive()) {
            preserveStorageOnShutdown = true;
            Process child;
            synchronized (saveLifecycleLock) {
                saveAbortRequested = true;
                child = saveProcess;
            }
            if (child != null && child.isAlive()) {
                child.destroy();
                try {
                    if (!child.waitFor(
                            SAVE_ABORT_GRACE_MILLIS,
                            TimeUnit.MILLISECONDS)) {
                        child.destroyForcibly();
                    }
                } catch (InterruptedException exception) {
                    child.destroyForcibly();
                    interrupted = true;
                }
            }
            pending.interrupt();
            try {
                pending.join(SAVE_ABORT_GRACE_MILLIS);
            } catch (InterruptedException exception) {
                interrupted = true;
            }

            Path recoveryDirectory;
            synchronized (lock) {
                closeStorageLocked();
                recoveryDirectory =
                        preserveRecoveryDirectoryLocked(
                                !pending.isAlive());
            }
            RecordableMod.LOGGER.error(
                    "Replay save exceeded the {} second shutdown limit. "
                            + "FFmpeg was stopped and recoverable raw chunks "
                            + "were preserved at {}.",
                    Math.max(0L, timeoutMillis) / 1000L,
                    recoveryDirectory == null
                            ? "<unknown>"
                            : recoveryDirectory.toAbsolutePath());
        } else {
            synchronized (lock) {
                if (!saving.get()
                        && !preserveStorageOnShutdown) {
                    resetStorageLocked();
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    public void addFrame(byte[] rgbPixels) {
        addFrame(
                rgbPixels,
                sourceWidth,
                sourceHeight,
                System.nanoTime());
    }

    public void addFrame(
            byte[] rgbPixels,
            long capturedAtNanos) {
        addFrame(
                rgbPixels,
                sourceWidth,
                sourceHeight,
                capturedAtNanos);
    }

    public void addFrame(
            byte[] rgbPixels,
            int frameWidth,
            int frameHeight,
            long capturedAtNanos) {
        if (!active.get() || rgbPixels == null) return;
        if (frameWidth <= 0 || frameHeight <= 0) return;
        long required = frameWidth * (long) frameHeight * 3L;
        if (required > Integer.MAX_VALUE
                || rgbPixels.length < (int) required) {
            return;
        }

        long nowNanos = capturedAtNanos > 0L
                ? capturedAtNanos
                : System.nanoTime();
        long prior = lastAcceptedNanos;
        if (prior != 0L
                && nowNanos - prior < frameIntervalNanos - 1_000_000L) {
            return;
        }
        lastAcceptedNanos = nowNanos;

        byte[] stored = scaleRgb(
                rgbPixels,
                frameWidth,
                frameHeight,
                storedWidth,
                storedHeight);
        PendingFrame pending =
                new PendingFrame(
                    stored,
                    nowNanos / 1_000_000L);
        if (!writeQueue.offer(pending)) {
            droppedFrames.incrementAndGet();
        } else {
            submittedFrameSequence.incrementAndGet();
        }
    }

    private void startWriterLocked() {
        writerRunning = true;
        writerThread = new Thread(new Runnable() {
            @Override
            public void run() {
                writerLoop();
            }
        }, "Recordable-ReplayWriter");
        writerThread.setDaemon(true);
        writerThread.start();
    }

    private void haltWriter() {
        Thread thread;
        synchronized (lock) {
            writerRunning = false;
            thread = writerThread;
            writerThread = null;
        }
        if (thread == null) {
            writeQueue.clear();
            return;
        }
        try {
            thread.join(5000L);
            if (thread.isAlive()) {
                thread.interrupt();
                thread.join(1000L);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        writeQueue.clear();
    }

    private void writerLoop() {
        try {
            while (writerRunning || !writeQueue.isEmpty()) {
                PendingFrame frame =
                        writeQueue.poll(100L, TimeUnit.MILLISECONDS);
                if (frame == null) continue;
                appendFrame(frame);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (Throwable throwable) {
            active.set(false);
            RecordableMod.LOGGER.error(
                    "Replay buffer writer failed.",
                    throwable);
        }
    }

    private void appendFrame(PendingFrame frame) throws IOException {
        synchronized (lock) {
            if ((!active.get() && !writerRunning)
                    || currentChunk == null
                    || currentChunk.output == null) {
                return;
            }
            long offset = currentChunk.bytesWritten;
            currentChunk.output.write(frame.pixels);
            currentChunk.bytesWritten += frame.pixels.length;
            currentChunk.references++;
            index.addLast(new FrameRef(
                    currentChunk.id,
                    offset,
                    frame.pixels.length,
                    frame.timestampMillis));
            diskBytes.addAndGet(frame.pixels.length);
            writtenFrameSequence.incrementAndGet();

            if (currentChunk.bytesWritten >= CHUNK_MAX_BYTES) {
                closeChunk(currentChunk);
                openNewChunkLocked();
            }
            evictLocked(frame.timestampMillis);
        }
    }

    private void evictLocked(long nowMillis) {
        long cutoff = nowMillis - durationMillis;
        while (!index.isEmpty()
                && index.peekFirst().timestampMillis < cutoff) {
            evictOldestLocked();
        }
        while (!index.isEmpty()
                && diskBytes.get() > diskBudgetBytes) {
            evictOldestLocked();
        }
        while (!index.isEmpty()
                && index.size() > maximumFrameCount) {
            evictOldestLocked();
        }
    }

    private void evictOldestLocked() {
        FrameRef frame = index.pollFirst();
        if (frame == null) return;
        diskBytes.addAndGet(-frame.length);
        Chunk chunk = chunks.get(frame.chunkId);
        if (chunk == null) return;
        chunk.references--;
        if (chunk.references <= 0 && chunk != currentChunk) {
            if (pinnedForSave) {
                pendingChunkDeletes.addLast(chunk.id);
            } else {
                removeChunkLocked(chunk);
            }
        }
    }

    public SaveResult saveBuffer() {
        return saveBuffer("replay");
    }

    public SaveResult saveBuffer(final String prefix) {
        return saveBuffer(prefix, 0);
    }

    public SaveResult saveBuffer(
            final String prefix,
            int maximumSeconds) {
        return requestSave(
                prefix,
                maximumSeconds,
                0L,
                true,
                false);
    }

    public SaveResult trySaveBuffer(
            final String prefix,
            int maximumSeconds) {
        return requestSave(
                prefix,
                maximumSeconds,
                0L,
                false,
                true);
    }

    public SaveResult trySaveBuffer(
            final String prefix,
            int maximumSeconds,
            long maximumTimestampMillis) {
        return requestSave(
                prefix,
                maximumSeconds,
                maximumTimestampMillis,
                false,
                true);
    }

    private SaveResult requestSave(
            final String prefix,
            int maximumSeconds,
            long maximumTimestampMillis,
            boolean notifyUnavailable,
            boolean automaticClip) {
        if (!active.get() || shutdownRequested) {
            if (notifyUnavailable) {
                RecordableMessages.send(
                        ChatCategory.REPLAY_BUFFER,
                        "Replay buffer is not active.");
            }
            return SaveResult.INACTIVE;
        }
        if (!saving.compareAndSet(false, true)) {
            if (notifyUnavailable) {
                RecordableMessages.send(
                        ChatCategory.REPLAY_BUFFER,
                        "Replay buffer is already being saved.");
            }
            return SaveResult.BUSY;
        }
        if (!active.get() || shutdownRequested) {
            saving.set(false);
            return SaveResult.INACTIVE;
        }

        final FrameRef[] frames;
        synchronized (lock) {
            try {
                if (currentChunk != null
                        && currentChunk.output != null) {
                    currentChunk.output.flush();
                }
            } catch (IOException ignored) {
            }
            // FrameRef is immutable. Pinning its backing chunks below makes
            // this a stable save view while the writer keeps appending new
            // frames to the live rolling index.
            FrameRef[] snapshot =
                    index.toArray(new FrameRef[index.size()]);
            int lastExclusive = snapshot.length;
            if (maximumTimestampMillis > 0L) {
                while (lastExclusive > 0
                        && snapshot[lastExclusive - 1]
                                .timestampMillis
                            > maximumTimestampMillis) {
                    lastExclusive--;
                }
            }
            if (maximumSeconds > 0 && lastExclusive > 0) {
                long newest = maximumTimestampMillis > 0L
                        ? maximumTimestampMillis
                        : snapshot[lastExclusive - 1]
                                .timestampMillis;
                long cutoff = newest
                        - maximumSeconds * 1000L;
                int first = 0;
                while (first < lastExclusive
                        && snapshot[first].timestampMillis < cutoff) {
                    first++;
                }
                frames = new FrameRef[lastExclusive - first];
                System.arraycopy(
                        snapshot,
                        first,
                        frames,
                        0,
                        frames.length);
            } else if (lastExclusive < snapshot.length) {
                frames = new FrameRef[lastExclusive];
                System.arraycopy(
                        snapshot,
                        0,
                        frames,
                        0,
                        lastExclusive);
            } else {
                frames = snapshot;
            }
            pinnedForSave = true;
        }
        if (frames.length < 2) {
            finishSave();
            if (notifyUnavailable) {
                RecordableMessages.send(
                        ChatCategory.REPLAY_BUFFER,
                        "Replay buffer is still warming up.");
            }
            return SaveResult.WARMING_UP;
        }
        final byte[] audioSnapshot =
                snapshotAudio(frames, automaticClip);

        RecordableMessages.send(
                ChatCategory.REPLAY_BUFFER,
                "Saving the last " + getBufferedSeconds()
                        + " seconds...");
        Thread pendingSave = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Path saved = encodeSnapshot(
                            frames,
                            prefix,
                            audioSnapshot);
                    notifyClient(
                            ChatCategory.REPLAY_BUFFER,
                            "Replay saved: " + saved.getFileName());
                } catch (Throwable throwable) {
                    RecordableMod.LOGGER.error(
                            "Unable to save replay buffer.",
                            throwable);
                    notifyClient(
                            ChatCategory.WARNINGS,
                            "Replay save failed: "
                                    + safeMessage(throwable));
                } finally {
                    finishSave();
                    synchronized (saveLifecycleLock) {
                        if (saveThread == Thread.currentThread()) {
                            saveThread = null;
                        }
                    }
                }
            }
        }, "Recordable-ReplaySave");
        pendingSave.setDaemon(true);
        Throwable startFailure = null;
        boolean cancelledForShutdown = false;
        synchronized (saveLifecycleLock) {
            if (shutdownRequested || !active.get()) {
                cancelledForShutdown = true;
            } else {
                saveAbortRequested = false;
                saveThread = pendingSave;
                try {
                    pendingSave.start();
                } catch (Throwable throwable) {
                    saveThread = null;
                    startFailure = throwable;
                }
            }
        }
        if (cancelledForShutdown) {
            finishSave();
            return SaveResult.INACTIVE;
        }
        if (startFailure != null) {
            finishSave();
            RecordableMod.LOGGER.error(
                    "Unable to start replay save thread.",
                    startFailure);
            if (notifyUnavailable) {
                RecordableMessages.send(
                        ChatCategory.WARNINGS,
                        "Replay save could not be started.");
            }
            return SaveResult.FAILED;
        }
        return SaveResult.ACCEPTED;
    }

    private byte[] snapshotAudio(
            FrameRef[] frames,
            boolean automaticClip) {
        if (frames == null || frames.length < 2) return null;
        RecordableConfig config = RecordableConfig.get();
        if (!config.captureAudio
                || !config.trackGameAudio
                || (automaticClip && !config.autoClipAudio)) {
            return null;
        }
        try {
            return OpenALLoopbackCapture.getInstance()
                    .extractAudio(
                            frames[0].timestampMillis,
                            frames[frames.length - 1]
                                    .timestampMillis);
        } catch (Throwable throwable) {
            RecordableMod.LOGGER.debug(
                    "Unable to snapshot replay audio.",
                    throwable);
            return null;
        }
    }

    private Path encodeSnapshot(
            FrameRef[] frames,
            String requestedPrefix,
            byte[] audioSnapshot)
            throws Exception {
        ensureSaveNotAborted();
        FfmpegBundleManager.FfmpegStatus status =
                FfmpegBundleManager.detectFfmpeg();
        ensureSaveNotAborted();
        if (!status.isFound()) {
            throw new IOException("FFmpeg is not available.");
        }

        RecordableConfig config = RecordableConfig.get();
        String prefix = sanitizePrefix(requestedPrefix);
        boolean autoClip = prefix.startsWith("on-")
                || "auto-clip".equals(prefix);
        Path directory = autoClip
                ? StorageManager.getAutoClipTriggerDirectory(
                        config,
                        prefix)
                : config.getOutputDirectory();
        if (directory == null) {
            throw new IOException(
                    "The replay output directory is not configured.");
        }
        Files.createDirectories(directory);
        Path output = uniquePath(
                directory,
                prefix + "-" + FILE_TIME.format(LocalDateTime.now()),
                config.getFormat());

        int effectiveFps = Math.max(
            1,
            Math.min(120, targetFps));
        Path audioInput = null;
        Process process = null;
        try {
            ensureSaveNotAborted();
            if (audioSnapshot != null
                    && audioSnapshot.length > 0) {
                audioInput = Files.createTempFile(
                    bufferDirectory,
                    "clip-audio-",
                    ".pcm");
                Files.write(audioInput, audioSnapshot);
                ensureSaveNotAborted();
            }

            List<String> command = new ArrayList<String>();
            Collections.addAll(
                    command,
                    status.getExecutable(),
                    "-nostdin",
                    "-hide_banner",
                    "-loglevel", "warning",
                    "-y",
                    "-f", "rawvideo",
                    "-pix_fmt", "rgb24",
                    "-video_size", storedWidth + "x" + storedHeight,
                    "-framerate", Integer.toString(effectiveFps),
                    "-i", "pipe:0");
            if (audioInput != null) {
                Collections.addAll(
                    command,
                    "-f", "s16le",
                    "-ar", Integer.toString(
                        OpenALLoopbackCapture.SAMPLE_RATE),
                    "-ac", Integer.toString(
                        OpenALLoopbackCapture.CHANNELS),
                    "-i", audioInput.toAbsolutePath().toString());
            }
            String smooth =
                SmoothMotion.buildFilter(config, effectiveFps);
            if (smooth != null) {
                Collections.addAll(command, "-vf", smooth);
            }
            if (config.isWebmFormat()) {
                Collections.addAll(
                    command,
                    "-c:v", "libvpx-vp9",
                    "-crf", Integer.toString(config.getVp9Crf()),
                    "-b:v", "0",
                    "-deadline", "realtime",
                    "-cpu-used", "6");
            } else {
                ensureSaveNotAborted();
                boolean supportsX264 = FfmpegBundleManager
                        .supportsEncoder("libx264");
                ensureSaveNotAborted();
                String codec = supportsX264
                        ? "libx264"
                        : "mpeg4";
                Collections.addAll(command, "-c:v", codec);
                if ("libx264".equals(codec)) {
                    Collections.addAll(
                            command,
                            "-preset", "fast",
                            "-crf", "23");
                } else {
                    Collections.addAll(command, "-q:v", "5");
                }
                Collections.addAll(
                    command,
                    "-pix_fmt", "yuv420p");
            }
            if (audioInput == null) {
                command.add("-an");
            } else {
                Collections.addAll(
                    command,
                    "-map", "0:v:0",
                    "-map", "1:a:0",
                    "-c:a", config.isWebmFormat()
                        ? "libopus"
                        : "aac",
                    "-b:a", Math.max(
                        32,
                        config.audioBitrateKbps) + "k",
                    "-shortest");
            }
            if ("mp4".equals(config.getFormat())
                    || "mov".equals(config.getFormat())) {
                Collections.addAll(
                    command,
                    "-movflags", "+faststart");
            }
            command.add(output.toAbsolutePath().toString());

            ensureSaveNotAborted();
            process = new ProcessBuilder(command).start();
            boolean abortAfterLaunch;
            synchronized (saveLifecycleLock) {
                saveProcess = process;
                abortAfterLaunch = saveAbortRequested;
            }
            if (abortAfterLaunch) {
                process.destroyForcibly();
                throw new IOException(
                        "Replay save aborted during shutdown.");
            }
            ByteArrayOutputStream errors =
                new ByteArrayOutputStream();
            Thread errorReader = streamReader(
                    process.getErrorStream(),
                    errors,
                    "Recordable-ReplayFFmpegLog");
            Map<Integer, RandomAccessFile> openFiles =
                    new HashMap<Integer, RandomAccessFile>();
            try {
                OutputStream input = new BufferedOutputStream(
                        process.getOutputStream(),
                        1024 * 1024);
                try {
                    byte[] buffer = null;
                    byte[] previous = null;
                    long framesWritten = 0L;
                    long firstTimestamp =
                        frames[0].timestampMillis;
                    for (FrameRef frame : frames) {
                        ensureSaveNotAborted();
                        RandomAccessFile source =
                            openFiles.get(frame.chunkId);
                        if (source == null) {
                            Path chunkPath;
                            synchronized (lock) {
                                Chunk chunk =
                                    chunks.get(frame.chunkId);
                                chunkPath = chunk == null
                                    ? null
                                    : chunk.path;
                            }
                            if (chunkPath == null
                                    || !Files.isRegularFile(
                                        chunkPath)) {
                                continue;
                            }
                            source = new RandomAccessFile(
                                    chunkPath.toFile(),
                                    "r");
                            openFiles.put(
                                frame.chunkId,
                                source);
                        }
                        if (buffer == null
                                || buffer.length != frame.length) {
                            buffer = new byte[frame.length];
                        }
                        source.seek(frame.offset);
                        source.readFully(buffer);

                        long desiredIndex = Math.max(
                            framesWritten,
                            Math.round(
                                (frame.timestampMillis
                                    - firstTimestamp)
                                    * effectiveFps
                                    / 1000.0D));
                        while (previous != null
                                && framesWritten
                                    < desiredIndex) {
                            ensureSaveNotAborted();
                            input.write(previous);
                            framesWritten++;
                        }
                        // Write each captured frame exactly once. Timing gaps
                        // are represented only by the previous-frame loop.
                        input.write(buffer);
                        framesWritten++;
                        if (previous == null
                                || previous.length
                                    != buffer.length) {
                            previous =
                                new byte[buffer.length];
                        }
                        System.arraycopy(
                            buffer,
                            0,
                            previous,
                            0,
                            buffer.length);
                    }
                    input.flush();
                } finally {
                    input.close();
                }
            } finally {
                for (RandomAccessFile file : openFiles.values()) {
                    try {
                        file.close();
                    } catch (IOException ignored) {
                    }
                }
            }

            if (!process.waitFor(2L, TimeUnit.HOURS)) {
                process.destroyForcibly();
                throw new IOException(
                    "FFmpeg timed out while saving replay.");
            }
            errorReader.join(2000L);
            if (process.exitValue() != 0
                    || !Files.isRegularFile(output)
                    || Files.size(output) <= 0L) {
                if (!preserveStorageOnShutdown) {
                    Files.deleteIfExists(output);
                }
                throw new IOException(
                        "FFmpeg exited with code "
                                + process.exitValue()
                                + ": "
                                + tail(
                                    errors.toByteArray(),
                                    500));
            }
            return output;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroy();
                try {
                    if (!process.waitFor(
                            SAVE_ABORT_GRACE_MILLIS,
                            TimeUnit.MILLISECONDS)) {
                        process.destroyForcibly();
                    }
                } catch (InterruptedException exception) {
                    process.destroyForcibly();
                    Thread.currentThread().interrupt();
                }
            }
            synchronized (saveLifecycleLock) {
                if (saveProcess == process) {
                    saveProcess = null;
                }
            }
            if (audioInput != null) {
                try {
                    Files.deleteIfExists(audioInput);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private void ensureSaveNotAborted() throws IOException {
        if (saveAbortRequested
                || Thread.currentThread().isInterrupted()) {
            throw new IOException(
                    "Replay save aborted during shutdown.");
        }
    }

    private void finishSave() {
        synchronized (lock) {
            saving.set(false);
            pinnedForSave = false;
            if (preserveStorageOnShutdown) {
                // Snapshot FrameRefs do not contribute to Chunk.references.
                // Keep every pinned chunk after an aborted shutdown because
                // it may be the only remaining copy of a snapshot frame.
                pendingChunkDeletes.clear();
            } else {
                while (!pendingChunkDeletes.isEmpty()) {
                    Integer id = pendingChunkDeletes.pollFirst();
                    Chunk chunk = id == null ? null : chunks.get(id);
                    if (chunk != null
                            && chunk.references <= 0
                            && chunk != currentChunk) {
                        removeChunkLocked(chunk);
                    }
                }
            }
            if (!active.get()) {
                if (preserveStorageOnShutdown) {
                    closeStorageLocked();
                } else {
                    resetStorageLocked();
                }
            }
        }
    }

    private void openNewChunkLocked() throws IOException {
        int id = nextChunkId++;
        Path path = bufferDirectory.resolve(
                String.format(Locale.ROOT, "chunk-%06d.raw", id));
        Chunk chunk = new Chunk(id, path);
        chunk.output = new BufferedOutputStream(
                Files.newOutputStream(path),
                64 * 1024);
        chunks.put(id, chunk);
        currentChunk = chunk;
    }

    private void removeChunkLocked(Chunk chunk) {
        closeChunk(chunk);
        try {
            Files.deleteIfExists(chunk.path);
        } catch (IOException ignored) {
        }
        chunks.remove(chunk.id);
    }

    private void resetStorageLocked() {
        writeQueue.clear();
        closeStorageLocked();
        for (Chunk chunk : chunks.values()) {
            try {
                Files.deleteIfExists(chunk.path);
            } catch (IOException ignored) {
            }
        }
        chunks.clear();
        index.clear();
        pendingChunkDeletes.clear();
        currentChunk = null;
        nextChunkId = 0;
        diskBytes.set(0L);
        droppedFrames.set(0L);
        if (bufferDirectory != null) {
            try {
                Files.deleteIfExists(bufferDirectory);
            } catch (IOException ignored) {
            }
        }
    }

    private void closeStorageLocked() {
        writeQueue.clear();
        for (Chunk chunk : chunks.values()) {
            closeChunk(chunk);
        }
    }

    private Path preserveRecoveryDirectoryLocked(
            boolean canMoveNow) {
        Path directory = bufferDirectory;
        if (directory == null || !Files.isDirectory(directory)) {
            return directory;
        }
        try {
            Files.write(
                    directory.resolve(RECOVERY_MARKER),
                    ("Record-able replay save was interrupted during JVM "
                            + "shutdown. The chunk-*.raw files are RGB24 "
                            + storedWidth + "x" + storedHeight + " frames at "
                            + targetFps + " FPS.\n")
                            .getBytes(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            RecordableMod.LOGGER.warn(
                    "Unable to write replay recovery marker.",
                    exception);
        }
        if (!canMoveNow) {
            return directory;
        }
        try {
            Path recovery = uniqueRecoveryDirectory(
                    directory.getParent());
            Files.move(directory, recovery);
            bufferDirectory = recovery;
            return recovery;
        } catch (IOException exception) {
            RecordableMod.LOGGER.warn(
                    "Unable to move replay chunks into a durable recovery directory.",
                    exception);
            return directory;
        }
    }

    private static void closeChunk(Chunk chunk) {
        if (chunk == null || chunk.output == null) return;
        try {
            chunk.output.flush();
        } catch (IOException ignored) {
        }
        try {
            chunk.output.close();
        } catch (IOException ignored) {
        }
        chunk.output = null;
    }

    private static void clearBufferDirectory(Path directory)
            throws IOException {
        if (directory == null || !Files.exists(directory)) return;
        if (!".recordable-replay-buffer".equals(
                directory.getFileName().toString())) {
            throw new IOException(
                    "Refusing to clear an unexpected replay path: "
                            + directory);
        }
        if (Files.isRegularFile(
                directory.resolve(RECOVERY_MARKER))) {
            Files.move(
                    directory,
                    uniqueRecoveryDirectory(
                            directory.getParent()));
            return;
        }
        try (DirectoryStream<Path> entries =
                Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                if (Files.isRegularFile(entry)
                        && entry.getFileName().toString()
                                .startsWith("chunk-")) {
                    Files.deleteIfExists(entry);
                }
            }
        }
    }

    private static Path uniqueRecoveryDirectory(Path parent)
            throws IOException {
        if (parent == null) {
            throw new IOException(
                    "Replay recovery directory has no parent.");
        }
        String base = ".recordable-replay-recovery-"
                + System.currentTimeMillis();
        Path candidate = parent.resolve(base);
        int suffix = 2;
        while (Files.exists(candidate)) {
            candidate = parent.resolve(base + "-" + suffix++);
        }
        return candidate;
    }

    private static byte[] scaleRgb(
            byte[] source,
            int sourceWidth,
            int sourceHeight,
            int targetWidth,
            int targetHeight) {
        byte[] target = new byte[targetWidth * targetHeight * 3];
        if (sourceWidth == targetWidth
                && sourceHeight == targetHeight) {
            System.arraycopy(
                    source,
                    0,
                    target,
                    0,
                    target.length);
            return target;
        }
        long scaleX = ((long) sourceWidth << 16) / targetWidth;
        long scaleY = ((long) sourceHeight << 16) / targetHeight;
        for (int y = 0; y < targetHeight; y++) {
            int sourceY = Math.min(
                    sourceHeight - 1,
                    (int) ((y * scaleY) >> 16));
            for (int x = 0; x < targetWidth; x++) {
                int sourceX = Math.min(
                        sourceWidth - 1,
                        (int) ((x * scaleX) >> 16));
                int sourceIndex =
                        (sourceY * sourceWidth + sourceX) * 3;
                int targetIndex =
                        (y * targetWidth + x) * 3;
                target[targetIndex] = source[sourceIndex];
                target[targetIndex + 1] = source[sourceIndex + 1];
                target[targetIndex + 2] = source[sourceIndex + 2];
            }
        }
        return target;
    }

    /**
     * Caps a standalone replay readback without changing its aspect ratio.
     * Shared recording frames may be larger, but are downscaled to the same
     * storage ceiling by {@link #resolveStoredDimensions(int, int, String)}.
     */
    static int[] capSourceDimensions(int width, int height) {
        int safeWidth = makeEven(Math.max(2, width));
        int safeHeight = makeEven(Math.max(2, height));
        double scale = Math.min(
                1.0D,
                Math.min(
                        MAX_REPLAY_WIDTH / (double) safeWidth,
                        MAX_REPLAY_HEIGHT / (double) safeHeight));
        return scaledDimensions(safeWidth, safeHeight, scale);
    }

    static int[] resolveStoredDimensions(
            int width,
            int height,
            String quality) {
        int safeWidth = makeEven(Math.max(2, width));
        int safeHeight = makeEven(Math.max(2, height));
        int[] preset = RecordableConfig.resolveReplayPreset(
                quality,
                safeHeight,
                MAX_REPLAY_FPS);
        int qualityHeight = preset[0] <= 0
                ? safeHeight
                : Math.min(safeHeight, makeEven(preset[0]));
        double scale = Math.min(
                qualityHeight / (double) safeHeight,
                Math.min(
                        MAX_REPLAY_WIDTH / (double) safeWidth,
                        MAX_REPLAY_HEIGHT / (double) safeHeight));
        return scaledDimensions(safeWidth, safeHeight, scale);
    }

    static int resolveTargetFps(
            String quality,
            int sourceHeight,
            int requestedFps) {
        int safeRequestedFps = Math.max(1, requestedFps);
        int[] preset = RecordableConfig.resolveReplayPreset(
                quality,
                Math.max(2, sourceHeight),
                safeRequestedFps);
        return Math.max(
                1,
                Math.min(
                        MAX_REPLAY_FPS,
                        Math.min(
                                safeRequestedFps,
                                Math.max(1, preset[1]))));
    }

    private static int[] scaledDimensions(
            int width,
            int height,
            double requestedScale) {
        double scale = Math.max(0.0D, Math.min(1.0D, requestedScale));
        int scaledWidth = makeEven(Math.max(
                2,
                (int) Math.round(width * scale)));
        int scaledHeight = makeEven(Math.max(
                2,
                (int) Math.round(height * scale)));
        return new int[]{
                Math.min(MAX_REPLAY_WIDTH, scaledWidth),
                Math.min(MAX_REPLAY_HEIGHT, scaledHeight)};
    }

    private static long calculateDiskBudget(
            Path directory,
            int width,
            int height,
            int fps,
            int seconds) {
        long required = width * (long) height * 3L
                * fps * seconds;
        long maximum = 4L * 1024L * 1024L * 1024L;
        long free = -1L;
        try {
            Files.createDirectories(directory);
            free = Files.getFileStore(directory).getUsableSpace();
        } catch (IOException ignored) {
        }
        long freeCap = free > 0L
                ? Math.max(
                        64L * 1024L * 1024L,
                        free / 2L)
                : maximum;
        return Math.max(
                64L * 1024L * 1024L,
                Math.min(required, Math.min(maximum, freeCap)));
    }

    public static String checkMemorySafety(
            int width,
            int height,
            int fps,
            int durationSeconds) {
        RecordableConfig config = RecordableConfig.get();
        long free = DiskSpaceGuardian.getFreeSpaceMB(
                config.getOutputDirectory());
        int[] dimensions = resolveStoredDimensions(
                width,
                height,
                config.replayBufferQuality);
        int targetFps = resolveTargetFps(
                config.replayBufferQuality,
                height,
                fps);
        long estimatedMb = dimensions[0]
                * (long) dimensions[1] * 3L
                * targetFps * Math.max(1, durationSeconds)
                / (1024L * 1024L);
        if (free >= 0L
                && free < Math.min(estimatedMb, 256L) + 100L) {
            return "Replay buffer needs more free disk space.";
        }
        return null;
    }

    public boolean isActive() {
        return active.get();
    }

    public boolean isSaving() {
        return saving.get();
    }

    public long getSubmittedFrameSequence() {
        return submittedFrameSequence.get();
    }

    public long getWrittenFrameSequence() {
        return writtenFrameSequence.get();
    }

    public int getRequestedDurationSeconds() {
        return requestedDurationSeconds;
    }

    public int getEffectiveDurationSeconds() {
        return effectiveDurationSeconds;
    }

    public int getBufferedFrameCount() {
        synchronized (lock) {
            return index.size();
        }
    }

    public int getBufferedSeconds() {
        synchronized (lock) {
            if (index.size() < 2) return 0;
            FrameRef first = index.peekFirst();
            FrameRef last = index.peekLast();
            return first == null || last == null
                    ? 0
                    : (int) Math.max(
                            0L,
                            (last.timestampMillis
                                    - first.timestampMillis) / 1000L);
        }
    }

    public long getCurrentDiskMB() {
        return diskBytes.get() / (1024L * 1024L);
    }

    public long getDiskBudgetMB() {
        return diskBudgetBytes / (1024L * 1024L);
    }

    public long getDroppedFrames() {
        return droppedFrames.get();
    }

    public int getStoredWidth() {
        return storedWidth;
    }

    public int getStoredHeight() {
        return storedHeight;
    }

    public int getSourceWidth() {
        return sourceWidth;
    }

    public int getSourceHeight() {
        return sourceHeight;
    }

    public int getTargetFps() {
        return targetFps;
    }

    private static Thread streamReader(
            final InputStream input,
            final ByteArrayOutputStream output,
            String name) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    BufferedInputStream buffered =
                            new BufferedInputStream(input);
                    byte[] buffer = new byte[4096];
                    int read;
                    while ((read = buffered.read(buffer)) >= 0) {
                        synchronized (output) {
                            if (output.size() > 65536) output.reset();
                            output.write(buffer, 0, read);
                        }
                    }
                } catch (IOException ignored) {
                }
            }
        }, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void notifyClient(
            final ChatCategory category,
            final String message) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft == null) return;
        minecraft.addScheduledTask(new Runnable() {
            @Override
            public void run() {
                RecordableMessages.send(category, message);
            }
        });
    }

    private static Path uniquePath(
            Path directory,
            String stem,
            String extension) {
        Path path = directory.resolve(stem + "." + extension);
        int suffix = 2;
        while (Files.exists(path)) {
            path = directory.resolve(
                    stem + "-" + suffix++ + "." + extension);
        }
        return path;
    }

    private static String sanitizePrefix(String prefix) {
        String value = prefix == null
                ? "replay"
                : prefix.toLowerCase(Locale.ROOT)
                        .replaceAll("[^a-z0-9._-]+", "-")
                        .replaceAll("^-+|-+$", "");
        return value.isEmpty() ? "replay" : value;
    }

    private static String tail(byte[] value, int maximum) {
        String text = new String(value, StandardCharsets.UTF_8)
                .replace('\r', ' ')
                .replace('\n', ' ')
                .trim();
        return text.length() <= maximum
                ? text
                : text.substring(text.length() - maximum);
    }

    private static String safeMessage(Throwable throwable) {
        if (throwable == null) return "unknown error";
        String message = throwable.getMessage();
        return message == null || message.trim().isEmpty()
                ? throwable.getClass().getSimpleName()
                : message;
    }

    private static int makeEven(int value) {
        int safe = Math.max(2, value);
        return (safe & 1) == 0 ? safe : safe - 1;
    }

    private static final class PendingFrame {
        final byte[] pixels;
        final long timestampMillis;

        PendingFrame(byte[] pixels, long timestampMillis) {
            this.pixels = pixels;
            this.timestampMillis = timestampMillis;
        }
    }

    private static final class FrameRef {
        final int chunkId;
        final long offset;
        final int length;
        final long timestampMillis;

        FrameRef(
                int chunkId,
                long offset,
                int length,
                long timestampMillis) {
            this.chunkId = chunkId;
            this.offset = offset;
            this.length = length;
            this.timestampMillis = timestampMillis;
        }
    }

    private static final class Chunk {
        final int id;
        final Path path;
        BufferedOutputStream output;
        long bytesWritten;
        int references;

        Chunk(int id, Path path) {
            this.id = id;
            this.path = path;
        }
    }
}
