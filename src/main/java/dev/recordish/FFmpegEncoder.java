package dev.recordish;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded asynchronous raw-frame writer for an external FFmpeg process.
 */
public final class FFmpegEncoder {
    private static final DateTimeFormatter FILE_TIME =
        DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final int MAX_DUPLICATES_PER_FRAME = 10;
    private static volatile String cachedSoftwareCodec;
    private static volatile String cachedSoftwareCodecKey;

    private final RecordishConfig config;
    private final int inputWidth;
    private final int inputHeight;
    private final int outputWidth;
    private final int outputHeight;
    private final int fps;
    private final String filePrefix;
    private final ArrayBlockingQueue<CapturedFrame> queue;
    private final int queueCapacity;
    private final CopyOnWriteArrayList<PauseSpan> pauseSpans =
        new CopyOnWriteArrayList<PauseSpan>();
    private final AtomicBoolean accepting = new AtomicBoolean(false);
    private final AtomicLong submittedFrames = new AtomicLong();
    private final AtomicLong writtenFrames = new AtomicLong();
    private final AtomicLong droppedFrames = new AtomicLong();
    private final ByteArrayOutputStream stderrTail = new ByteArrayOutputStream();

    private volatile Process process;
    private OutputStream input;
    private volatile Thread writerThread;
    private Thread errorThread;
    private volatile Throwable failure;
    private volatile long writerStartedNanos;
    private volatile long lastWriteProgressNanos;
    private Path temporaryVideo;
    private Path requestedOutput;

    public FFmpegEncoder(
            RecordishConfig config,
            int inputWidth,
            int inputHeight,
            int outputWidth,
            int outputHeight,
            int fps) {
        this(
                config,
                inputWidth,
                inputHeight,
                outputWidth,
                outputHeight,
                fps,
                null);
    }

    public FFmpegEncoder(
            RecordishConfig config,
            int inputWidth,
            int inputHeight,
            int outputWidth,
            int outputHeight,
            int fps,
            String filePrefix) {
        this.config = config;
        this.inputWidth = inputWidth;
        this.inputHeight = inputHeight;
        this.outputWidth = outputWidth;
        this.outputHeight = outputHeight;
        this.fps = fps;
        this.filePrefix = sanitizePrefix(filePrefix);
        this.queueCapacity = config.perfModeGamePriority ? 6 : 12;
        this.queue = new ArrayBlockingQueue<CapturedFrame>(queueCapacity);
    }

    public synchronized void start() throws IOException {
        if (process != null) throw new IllegalStateException("Encoder already started.");
        FfmpegBundleManager.FfmpegStatus status = FfmpegBundleManager.detectFfmpeg();
        if (!status.isFound()) {
            throw new IOException(status.getError() == null ? "FFmpeg was not found." : status.getError());
        }
        long ffmpegGeneration =
                FfmpegBundleManager.getStatusGeneration(status);

        boolean autoClip = isAutoClipPrefix(filePrefix);
        Path outputDirectory = autoClip
                ? StorageManager.getAutoClipTriggerDirectory(
                        config,
                        filePrefix)
                : config.getOutputDirectory();
        if (outputDirectory == null) {
            throw new IOException(
                    "The recording output directory is not configured.");
        }
        Files.createDirectories(outputDirectory);
        String stem = filePrefix.isEmpty()
                ? RecordishConfig.resolveFilenamePattern(
                        config.filenamePattern)
                : autoClip
                        ? "recordish-"
                                + FILE_TIME.format(LocalDateTime.now())
                        : "recordish-" + filePrefix + "-"
                                + FILE_TIME.format(LocalDateTime.now());
        requestedOutput = uniquePath(outputDirectory, stem, config.getFormat());
        temporaryVideo = uniquePath(outputDirectory, stem + ".video", "mkv");

        List<String> command = buildCommand(
                status,
                ffmpegGeneration);
        RecordishMod.LOGGER.info("Starting FFmpeg video encoder: {}", redactCommand(command));
        process = new ProcessBuilder(command)
            .directory(outputDirectory.toFile())
            .start();
        input = new BufferedOutputStream(process.getOutputStream(), 1024 * 1024);
        accepting.set(true);
        startErrorReader();
        writerStartedNanos = System.nanoTime();
        lastWriteProgressNanos = writerStartedNanos;
        writerThread = new Thread(this::writerLoop, "Recordish-VideoWriter");
        writerThread.setDaemon(true);
        writerThread.start();
    }

    public boolean submit(CapturedFrame frame) {
        if (frame == null) return false;
        if (!accepting.get() || failure != null) {
            frame.release();
            return false;
        }
        submittedFrames.incrementAndGet();
        if (!queue.offer(frame)) {
            droppedFrames.incrementAndGet();
            frame.release();
            return false;
        }
        return true;
    }

    public synchronized Path finishVideo() throws IOException {
        if (process == null) throw new IllegalStateException("Encoder was never started.");
        accepting.set(false);
        try {
            if (writerThread != null) writerThread.join(120000L);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while finishing video.", exception);
        }
        if (writerThread != null && writerThread.isAlive()) {
            closeInput();
            writerThread.interrupt();
            throw new IOException("Timed out while draining captured video frames.");
        }
        closeInput();
        waitForProcess();
        releaseQueuedFrames();
        if (failure != null) {
            throw new IOException("Video encoding failed: " + failure.getMessage(), failure);
        }
        if (!Files.isRegularFile(temporaryVideo) || Files.size(temporaryVideo) <= 0L) {
            throw new IOException("FFmpeg did not create a video stream. " + getErrorTail());
        }
        return temporaryVideo;
    }

    public synchronized void abort() {
        accepting.set(false);
        closeInput();
        if (process != null) process.destroyForcibly();
        releaseQueuedFrames();
    }

    /**
     * Emergency shutdown path that must not wait for {@link #finishVideo()}'s
     * monitor. The partially written MKV is deliberately left on disk for
     * recovery.
     */
    public void forceAbort() {
        accepting.set(false);
        Process activeProcess = process;
        if (activeProcess != null) {
            activeProcess.destroyForcibly();
        }
        Thread activeWriter = writerThread;
        if (activeWriter != null && activeWriter != Thread.currentThread()) {
            activeWriter.interrupt();
        }
        releaseQueuedFrames();
    }

    private List<String> buildCommand(
            FfmpegBundleManager.FfmpegStatus status,
            long ffmpegGeneration) throws IOException {
        List<String> command = new ArrayList<String>();
        String executable = status.getExecutable();
        add(command, executable, "-hide_banner", "-loglevel", "warning", "-y");
        add(command, "-f", "rawvideo", "-pix_fmt", "rgb24");
        add(command, "-video_size", inputWidth + "x" + inputHeight);
        add(command, "-framerate", Integer.toString(fps), "-i", "pipe:0", "-an");

        List<String> filters = new ArrayList<String>();
        if (outputWidth != inputWidth || outputHeight != inputHeight) {
            filters.add("scale=" + outputWidth + ":" + outputHeight + ":flags=lanczos");
        }
        String smoothFilter = SmoothMotion.buildFilter(config, fps);
        if (smoothFilter != null) {
            filters.add(smoothFilter);
        }
        if (!filters.isEmpty()) {
            add(command, "-vf", join(filters, ","));
        }

        if (config.isWebmFormat()) {
            /*
             * Command construction runs on Minecraft's recording thread. Use
             * only an asynchronously populated listing here. The official
             * V1-0.09 path selects libvpx-vp9 directly, so a cold cache uses
             * that same bundled-FFmpeg default without launching a probe.
             */
            String webmCodec = resolveWebmCodec(
                    ffmpegGeneration,
                    executable);
            add(command, "-c:v", webmCodec, "-crf", Integer.toString(config.getVp9Crf()));
            add(command, "-b:v", config.isAutoBitrate() ? "0" : config.resolveBitrate(outputWidth, outputHeight));
            add(command, "-row-mt", "1", "-deadline", "realtime", "-cpu-used",
                "performance".equals(config.quality) ? "8" : "5");
        } else {
            RecordishConfig.VideoEncoder selected =
                    resolveAvailableEncoder(
                            config.encoder,
                            ffmpegGeneration,
                            executable);
            String codec = selected == RecordishConfig.VideoEncoder.SOFTWARE
                ? resolveSoftwareCodecForStart(
                        status,
                        ffmpegGeneration)
                : selected.ffmpegCodec;
            add(command, "-c:v", codec);
            if (selected == RecordishConfig.VideoEncoder.SOFTWARE) {
                if ("libx264".equals(codec)) {
                    add(command, "-preset", config.getX264Preset());
                    if (config.isAutoBitrate()) {
                        add(command, "-crf", Integer.toString(config.getX264Crf()));
                    } else {
                        add(command, "-b:v", config.resolveBitrate(outputWidth, outputHeight));
                    }
                } else {
                    add(command, "-q:v",
                        "high".equals(config.quality) ? "2"
                            : ("performance".equals(config.quality) ? "8" : "5"));
                }
            } else {
                add(command, "-b:v", config.resolveBitrate(outputWidth, outputHeight));
                add(command, "-maxrate", config.resolveBitrate(outputWidth, outputHeight));
                add(command, "-bufsize", doubleBitrate(config.resolveBitrate(outputWidth, outputHeight)));
                if (selected == RecordishConfig.VideoEncoder.NVIDIA) {
                    add(command, "-preset", "p4", "-tune", "hq");
                } else if (selected == RecordishConfig.VideoEncoder.AMD) {
                    add(command, "-quality", "balanced");
                }
            }
            add(command, "-pix_fmt", "yuv420p");
        }

        add(command, "-r", Integer.toString(fps), "-vsync", "cfr");
        /*
         * RecordingManager watches the growing temporary file and requests a
         * normal stop at the configured limit. FFmpeg's -fs option exits the
         * child process underneath the raw-frame writer, which turns an
         * otherwise valid size-limited recording into a broken-pipe failure.
         */
        add(command, "-f", "matroska", temporaryVideo.toAbsolutePath().toString());
        return command;
    }

    private static String resolveSoftwareCodec() {
        /*
         * Encoder discovery runs on the FFmpeg warmup/settings worker.
         * Never retain FFmpegEncoder.class while detect/query waits for a
         * child process: cold recording startup also needs this cache lock.
         */
        for (int attempt = 0; attempt < 2; attempt++) {
            FfmpegBundleManager.FfmpegStatus status =
                    FfmpegBundleManager.detectFfmpeg();
            long generation =
                    FfmpegBundleManager.getStatusGeneration(status);
            String executableKey = softwareCodecCacheKey(
                    status,
                    generation);
            synchronized (FFmpegEncoder.class) {
                String cached = cachedSoftwareCodec;
                if (cached != null
                        && executableKey.equals(
                                cachedSoftwareCodecKey)) {
                    return cached;
                }
            }

            String resolved;
            if (!status.isFound()) {
                resolved = "mpeg4";
            } else {
                List<String> encoders =
                        FfmpegBundleManager.queryEncoders();
                if (!FfmpegBundleManager.isCurrentFfmpegStatus(
                        status,
                        generation)) {
                    continue;
                }
                if (encoders.contains("libx264")) {
                    resolved = "libx264";
                } else if (encoders.contains("mpeg4")) {
                    resolved = "mpeg4";
                } else if (encoders.contains("libxvid")) {
                    resolved = "libxvid";
                } else {
                    /*
                     * MPEG-4 is built into normal FFmpeg distributions and is
                     * the official mod's last-resort software fallback when
                     * probing fails.
                     */
                    resolved = "mpeg4";
                }
            }

            synchronized (FFmpegEncoder.class) {
                if (!FfmpegBundleManager.isCurrentFfmpegStatus(
                        status,
                        generation)) {
                    continue;
                }
                String cached = cachedSoftwareCodec;
                if (cached != null
                        && executableKey.equals(
                                cachedSoftwareCodecKey)) {
                    return cached;
                }
                cachedSoftwareCodec = resolved;
                cachedSoftwareCodecKey = executableKey;
                return resolved;
            }
        }

        /*
         * Repeated invalidation means no discovery result is authoritative.
         * Return the safe fallback without poisoning the exact-generation
         * cache; a later warmup can publish normally.
         */
        return "mpeg4";
    }

    /**
     * Resolves the software fallback without probing. Recording startup calls
     * this on Minecraft's thread, so only exact-generation listing data may
     * improve the default MPEG-4 fallback.
     */
    private static String resolveSoftwareCodecForStart(
            FfmpegBundleManager.FfmpegStatus status,
            long ffmpegGeneration) {
        String executableKey = softwareCodecCacheKey(
                status,
                ffmpegGeneration);
        synchronized (FFmpegEncoder.class) {
            String cached = cachedSoftwareCodec;
            if (cached != null
                    && executableKey.equals(
                            cachedSoftwareCodecKey)) {
                return cached;
            }
        }

        Boolean x264Support = null;
        Boolean mpeg4Support = null;
        Boolean xvidSupport = null;
        if (status != null && status.isFound()) {
            x264Support =
                    FfmpegBundleManager.getCachedEncoderSupport(
                            ffmpegGeneration,
                            status.getExecutable(),
                            "libx264");
            mpeg4Support =
                    FfmpegBundleManager.getCachedEncoderSupport(
                            ffmpegGeneration,
                            status.getExecutable(),
                            "mpeg4");
            xvidSupport =
                    FfmpegBundleManager.getCachedEncoderSupport(
                            ffmpegGeneration,
                            status.getExecutable(),
                            "libxvid");
        }

        /*
         * V1-0.09 and the managed desktop bundle prefer libx264. Do not cache
         * this cold-start default: once the asynchronous listing arrives, the
         * exact same FFmpeg generation must be allowed to refresh to its
         * proven best codec (or a proven MPEG-4 fallback).
         */
        if (x264Support == null
                && mpeg4Support == null
                && xvidSupport == null) {
            return "libx264";
        }

        String resolved;
        if (Boolean.TRUE.equals(x264Support)) {
            resolved = "libx264";
        } else if (Boolean.TRUE.equals(mpeg4Support)) {
            resolved = "mpeg4";
        } else if (Boolean.TRUE.equals(xvidSupport)) {
            resolved = "libxvid";
        } else {
            resolved = "mpeg4";
        }
        synchronized (FFmpegEncoder.class) {
            String cached = cachedSoftwareCodec;
            if (cached != null
                    && executableKey.equals(
                            cachedSoftwareCodecKey)) {
                return cached;
            }
            if (FfmpegBundleManager.isCurrentFfmpegStatus(
                    status,
                    ffmpegGeneration)) {
                cachedSoftwareCodec = resolved;
                cachedSoftwareCodecKey = executableKey;
            }
        }
        return resolved;
    }

    private static String softwareCodecCacheKey(
            FfmpegBundleManager.FfmpegStatus status,
            long ffmpegGeneration) {
        return ffmpegGeneration
                + "\n"
                + (status != null && status.isFound()
                        ? status.getExecutable() + "\n"
                                + status.getVersion()
                        : "<ffmpeg-unavailable>");
    }

    /**
     * Returns only encoder choices that can actually initialize on this
     * machine. FFmpeg may list a hardware encoder even when its matching GPU
     * or driver is absent, so the same device preflight used by recording is
     * applied before exposing the option in settings.
     */
    public static List<RecordishConfig.VideoEncoder> detectAvailableEncoders() {
        List<RecordishConfig.VideoEncoder> available =
            new ArrayList<RecordishConfig.VideoEncoder>();
        available.add(RecordishConfig.VideoEncoder.SOFTWARE);
        resolveSoftwareCodec();
        for (RecordishConfig.VideoEncoder encoder
                : RecordishConfig.VideoEncoder.values()) {
            if (encoder != RecordishConfig.VideoEncoder.SOFTWARE
                    && FfmpegBundleManager.isEncoderUsable(
                        encoder.ffmpegCodec)) {
                available.add(encoder);
            }
        }
        return available;
    }

    public static String getCachedSoftwareCodec() {
        return cachedSoftwareCodec;
    }

    static String getCachedSoftwareCodecKey() {
        return cachedSoftwareCodecKey;
    }

    static String resolveSoftwareCodecForStartForTest(
            FfmpegBundleManager.FfmpegStatus status,
            long ffmpegGeneration) {
        return resolveSoftwareCodecForStart(
                status,
                ffmpegGeneration);
    }

    static String resolveWebmCodec(
            long ffmpegGeneration,
            String executable) throws IOException {
        Boolean vp9Support =
                FfmpegBundleManager.getCachedEncoderSupport(
                        ffmpegGeneration,
                        executable,
                        "libvpx-vp9");
        Boolean vp8Support =
                FfmpegBundleManager.getCachedEncoderSupport(
                        ffmpegGeneration,
                        executable,
                        "libvpx");
        if (Boolean.TRUE.equals(vp9Support)) {
            return "libvpx-vp9";
        }
        if (Boolean.TRUE.equals(vp8Support)) {
            return "libvpx";
        }
        if (Boolean.FALSE.equals(vp9Support)
                && Boolean.FALSE.equals(vp8Support)) {
            throw new IOException(
                    "This FFmpeg installation has no supported WebM "
                            + "video encoder (libvpx-vp9 or libvpx).");
        }
        return "libvpx-vp9";
    }

    private RecordishConfig.VideoEncoder resolveAvailableEncoder(
            RecordishConfig.VideoEncoder selected,
            long ffmpegGeneration,
            String executable) {
        if (selected == null || selected == RecordishConfig.VideoEncoder.SOFTWARE) {
            return RecordishConfig.VideoEncoder.SOFTWARE;
        }
        /*
         * Hardware preflight is intentionally probe-capable only in the
         * asynchronous settings discovery path. Recording startup consumes a
         * cached result and otherwise falls back immediately.
         */
        Boolean cachedUsability =
                FfmpegBundleManager.getCachedEncoderUsability(
                        ffmpegGeneration,
                        executable,
                        selected.ffmpegCodec);
        if (Boolean.TRUE.equals(cachedUsability)) {
            return selected;
        }
        RecordishMod.LOGGER.warn(
                cachedUsability == null
                        ? "{} has no current device preflight; "
                                + "falling back to software encoding."
                        : "{} is unavailable or failed its device preflight; "
                                + "falling back to software encoding.",
                selected.displayName);
        return RecordishConfig.VideoEncoder.SOFTWARE;
    }

    private void writerLoop() {
        byte[] previousPixels = null;
        long firstTimelineNanos = Long.MIN_VALUE;
        long lastFrameIndex = -1L;
        long intervalNanos = Math.max(1L, 1_000_000_000L / Math.max(1, fps));
        try {
            while (accepting.get() || !queue.isEmpty()) {
                CapturedFrame frame = queue.poll(100L, TimeUnit.MILLISECONDS);
                if (frame == null) continue;
                try {
                    long timelineNanos = normalizeTimeline(frame.getCapturedAtNanos());
                    if (firstTimelineNanos == Long.MIN_VALUE) {
                        firstTimelineNanos = timelineNanos;
                    }
                    long frameIndex = Math.max(
                        lastFrameIndex + 1L,
                        Math.round((timelineNanos - firstTimelineNanos)
                            / (double) intervalNanos));
                    if (previousPixels != null) {
                        long duplicateLimit = Math.min(
                            frameIndex,
                            lastFrameIndex + 1L
                                + MAX_DUPLICATES_PER_FRAME);
                        for (long missing = lastFrameIndex + 1L;
                                missing < duplicateLimit;
                                missing++) {
                            input.write(previousPixels);
                            writtenFrames.incrementAndGet();
                            lastWriteProgressNanos =
                                System.nanoTime();
                        }
                    }
                    input.write(frame.getPixels());
                    writtenFrames.incrementAndGet();
                    lastWriteProgressNanos = System.nanoTime();
                    if (previousPixels == null
                            || previousPixels.length != frame.getPixels().length) {
                        previousPixels = new byte[frame.getPixels().length];
                    }
                    System.arraycopy(
                        frame.getPixels(),
                        0,
                        previousPixels,
                        0,
                        previousPixels.length);
                    lastFrameIndex = frameIndex;
                } finally {
                    frame.release();
                }
            }
            input.flush();
        } catch (Throwable throwable) {
            failure = throwable;
            accepting.set(false);
            RecordishMod.LOGGER.error("FFmpeg frame writer failed.", throwable);
        }
    }

    public void markPaused(long pauseStartedAtNanos) {
        pauseSpans.add(new PauseSpan(pauseStartedAtNanos));
    }

    public void markResumed(long resumedAtNanos) {
        for (int index = pauseSpans.size() - 1; index >= 0; index--) {
            PauseSpan span = pauseSpans.get(index);
            if (span.endNanos == Long.MAX_VALUE) {
                span.endNanos = Math.max(span.startNanos, resumedAtNanos);
                return;
            }
        }
    }

    private long normalizeTimeline(long capturedAtNanos) {
        long pausedNanos = 0L;
        for (PauseSpan span : pauseSpans) {
            if (capturedAtNanos <= span.startNanos) break;
            long end = Math.min(capturedAtNanos, span.endNanos);
            if (end > span.startNanos) {
                pausedNanos += end - span.startNanos;
            }
            if (capturedAtNanos < span.endNanos) break;
        }
        return capturedAtNanos - pausedNanos;
    }

    private void startErrorReader() {
        final InputStream error = new BufferedInputStream(process.getErrorStream());
        errorThread = new Thread(() -> {
            byte[] buffer = new byte[4096];
            try {
                int read;
                while ((read = error.read(buffer)) >= 0) {
                    synchronized (stderrTail) {
                        if (stderrTail.size() > 65536) stderrTail.reset();
                        stderrTail.write(buffer, 0, read);
                    }
                }
            } catch (IOException ignored) {
            } finally {
                try {
                    error.close();
                } catch (IOException ignored) {
                }
            }
        }, "Recordish-FFmpegLog");
        errorThread.setDaemon(true);
        errorThread.start();
    }

    private void waitForProcess() throws IOException {
        try {
            if (!process.waitFor(120L, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("FFmpeg timed out while finalizing video.");
            }
            if (errorThread != null) errorThread.join(2000L);
            if (process.exitValue() != 0) {
                throw new IOException("FFmpeg exited with code " + process.exitValue() + ": " + getErrorTail());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("Interrupted while waiting for FFmpeg.", exception);
        }
    }

    private void closeInput() {
        if (input == null) return;
        try {
            input.close();
        } catch (IOException ignored) {
        }
        input = null;
    }

    private void releaseQueuedFrames() {
        CapturedFrame frame;
        while ((frame = queue.poll()) != null) frame.release();
    }

    private String getErrorTail() {
        synchronized (stderrTail) {
            return new String(stderrTail.toByteArray(), StandardCharsets.UTF_8).trim();
        }
    }

    public Path getTemporaryVideo() {
        return temporaryVideo;
    }

    public Path getRequestedOutput() {
        return requestedOutput;
    }

    public long getSubmittedFrames() {
        return submittedFrames.get();
    }

    public long getWrittenFrames() {
        return writtenFrames.get();
    }

    public long getDroppedFrames() {
        return droppedFrames.get();
    }

    public int getQueueSize() {
        return queue.size();
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public Throwable getFailure() {
        return failure;
    }

    /**
     * Detects both writer-pipe failures and an FFmpeg process that exited
     * before the recording was stopped.
     */
    public synchronized Throwable pollFailure() {
        if (failure == null
                && accepting.get()
                && process != null
                && !process.isAlive()) {
            int exitCode;
            try {
                exitCode = process.exitValue();
            } catch (IllegalThreadStateException stillRunning) {
                return null;
            }
            failure = new IOException(
                "FFmpeg exited early with code " + exitCode
                    + (getErrorTail().isEmpty()
                        ? ""
                        : ": " + getErrorTail()));
            accepting.set(false);
        }
        if (failure == null
                && accepting.get()
                && process != null
                && process.isAlive()
                && queue.remainingCapacity() == 0
                && writerStartedNanos > 0L
                && System.nanoTime()
                    - Math.max(
                        writerStartedNanos,
                        lastWriteProgressNanos)
                    > 10_000_000_000L) {
            failure = new IOException(
                "FFmpeg stopped accepting video frames for more than 10 seconds."
                    + (getErrorTail().isEmpty()
                        ? ""
                        : " " + getErrorTail()));
            accepting.set(false);
            process.destroyForcibly();
        }
        return failure;
    }

    public long getTemporarySizeBytes() {
        try {
            return temporaryVideo == null ? 0L : Files.size(temporaryVideo);
        } catch (IOException ignored) {
            return 0L;
        }
    }

    private static Path uniquePath(Path directory, String stem, String extension) {
        Path candidate = directory.resolve(stem + "." + extension);
        int suffix = 1;
        while (Files.exists(candidate)) {
            candidate = directory.resolve(stem + "-" + suffix++ + "." + extension);
        }
        return candidate;
    }

    private static String sanitizePrefix(String requested) {
        if (requested == null) return "";
        String value = requested.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_-]+", "-")
                .replaceAll("^-+|-+$", "");
        return value.length() > 48
                ? value.substring(0, 48)
                : value;
    }

    private static boolean isAutoClipPrefix(String prefix) {
        return prefix != null
                && (prefix.startsWith("on-")
                        || "auto-clip".equals(prefix));
    }

    private static String doubleBitrate(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        try {
            if (normalized.endsWith("m")) {
                return (Integer.parseInt(normalized.substring(0, normalized.length() - 1)) * 2) + "M";
            }
            if (normalized.endsWith("k")) {
                return (Integer.parseInt(normalized.substring(0, normalized.length() - 1)) * 2) + "k";
            }
        } catch (NumberFormatException ignored) {
        }
        return value;
    }

    private static void add(List<String> command, String... values) {
        for (String value : values) command.add(value);
    }

    private static String join(List<String> values, String separator) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (builder.length() > 0) builder.append(separator);
            builder.append(value);
        }
        return builder.toString();
    }

    private static String redactCommand(List<String> command) {
        StringBuilder builder = new StringBuilder();
        for (String token : command) {
            if (builder.length() > 0) builder.append(' ');
            builder.append(token.indexOf(' ') >= 0 ? '"' + token + '"' : token);
        }
        return builder.toString();
    }

    private static final class PauseSpan {
        final long startNanos;
        volatile long endNanos = Long.MAX_VALUE;

        PauseSpan(long startNanos) {
            this.startNanos = startNanos;
        }
    }
}
