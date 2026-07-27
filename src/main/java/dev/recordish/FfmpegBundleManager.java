package dev.recordish;

import net.minecraft.client.Minecraft;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Locates and, on explicit request, downloads a desktop FFmpeg distribution.
 */
public final class FfmpegBundleManager {
    private static final String WINDOWS_URL =
        "https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip";
    private static final String WINDOWS_HASH_URL =
        "https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip.sha256";
    private static final String LINUX_URL =
        "https://johnvansickle.com/ffmpeg/releases/ffmpeg-release-amd64-static.tar.xz";
    private static final String LINUX_HASH_URL =
        "https://johnvansickle.com/ffmpeg/releases/ffmpeg-release-amd64-static.tar.xz.md5";
    private static final String MAC_URL = "https://evermeet.cx/ffmpeg/get/zip";
    private static final int CONNECT_TIMEOUT = 15000;
    private static final int READ_TIMEOUT = 30000;
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final long MAX_ARCHIVE_SIZE =
        512L * 1024L * 1024L;
    private static final long MAX_BINARY_SIZE =
        300L * 1024L * 1024L;

    public enum Status {
        NOT_FOUND, CHECKING, DOWNLOADING, AVAILABLE, ERROR
    }

    public interface ProgressListener {
        void onProgress(DownloadProgress progress);
    }

    public static final class DownloadProgress {
        private final String phase;
        private final long bytesDownloaded;
        private final long totalBytes;

        public DownloadProgress(String phase, long bytesDownloaded, long totalBytes) {
            this.phase = phase == null ? "" : phase;
            this.bytesDownloaded = Math.max(0L, bytesDownloaded);
            this.totalBytes = Math.max(0L, totalBytes);
        }

        public String getPhase() {
            return phase;
        }

        public long getBytesDownloaded() {
            return bytesDownloaded;
        }

        public long getTotalBytes() {
            return totalBytes;
        }

        public double getFraction() {
            if (totalBytes <= 0L) return 0.0D;
            return Math.max(0.0D, Math.min(1.0D, bytesDownloaded / (double) totalBytes));
        }

        public String displayPercent() {
            return totalBytes <= 0L
                ? humanBytes(bytesDownloaded)
                : String.format(Locale.ROOT, "%.1f%%", getFraction() * 100.0D);
        }

        private static String humanBytes(long bytes) {
            if (bytes < 1024L) return bytes + " B";
            if (bytes < 1024L * 1024L) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0D);
            return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0D * 1024.0D));
        }
    }

    public static final class FfmpegStatus {
        private final boolean found;
        private final String executable;
        private final String version;
        private final String error;

        private FfmpegStatus(boolean found, String executable, String version, String error) {
            this.found = found;
            this.executable = executable;
            this.version = version;
            this.error = error;
        }

        public boolean isFound() {
            return found;
        }

        public String getExecutable() {
            return executable;
        }

        public String getVersion() {
            return version;
        }

        public String getError() {
            return error;
        }
    }

    private static final AtomicReference<Status> STATUS =
        new AtomicReference<Status>(Status.NOT_FOUND);
    private static final AtomicBoolean DOWNLOADING = new AtomicBoolean(false);
    private static final List<ProgressListener> LISTENERS =
        new CopyOnWriteArrayList<ProgressListener>();
    private static volatile DownloadProgress progress = new DownloadProgress("idle", 0L, 0L);
    private static volatile FfmpegStatus cachedStatus;
    private static volatile String cachedCandidateKey;
    private static volatile String lastError;
    private static final AtomicLong CACHE_GENERATION =
        new AtomicLong();
    private static volatile EncoderListingCache encoderListingCache;
    private static final Map<String, Boolean> ENCODER_PREFLIGHT =
        new HashMap<String, Boolean>();

    private FfmpegBundleManager() {
    }

    public static Path getBundleDirectory() {
        Path gameDirectory = Minecraft.getMinecraft().mcDataDir.toPath();
        return RecordishPaths.preferredDirectory(
                gameDirectory.resolve("recordish")
                        .resolve("ffmpeg")
                        .resolve("bin"),
                gameDirectory.resolve("recordable")
                        .resolve("ffmpeg")
                        .resolve("bin"));
    }

    public static Path getManagedExecutablePath() {
        return getBundleDirectory()
            .resolve(PlatformUtils.executableName("ffmpeg"));
    }

    public static FfmpegStatus detectFfmpeg() {
        /*
         * Never hold the cache monitor while a child process is running.
         * Settings performs these probes on a worker, but recording starts on
         * Minecraft's thread; a method-level monitor here would make that
         * thread wait behind an unrelated 8-second version probe.
         */
        while (true) {
            RecordishConfig config = RecordishConfig.get();
            List<String> candidates = buildCandidateList(config);
            String candidateKey = candidateKey(candidates);
            long probeGeneration;
            synchronized (FfmpegBundleManager.class) {
                if (DOWNLOADING.get()) {
                    return new FfmpegStatus(
                            false,
                            null,
                            null,
                            "FFmpeg is still downloading.");
                }
                if (cachedStatus != null
                        && candidateKey.equals(cachedCandidateKey)) {
                    return cachedStatus;
                }
                if (cachedStatus != null) {
                    cachedStatus = null;
                    cachedCandidateKey = null;
                    advanceCacheGeneration();
                }
                probeGeneration = CACHE_GENERATION.get();
                STATUS.set(Status.CHECKING);
            }

            RecordishMod.LOGGER.info(
                "[FFmpeg] Starting executable probe across {} candidate(s).",
                candidates.size());
            StringBuilder failures = new StringBuilder();
            FfmpegStatus detected = null;
            String detectedCandidate = null;
            for (String candidate : candidates) {
                RecordishMod.LOGGER.info(
                    "[FFmpeg] Invoking probe: {} -hide_banner -version",
                    candidate);
                FfmpegStatus result = probe(candidate);
                if (result.isFound()) {
                    detected = result;
                    detectedCandidate = candidate;
                    break;
                }
                if (result.getError() != null) {
                    RecordishMod.LOGGER.info(
                        "[FFmpeg] Probe did not find a usable executable at '{}': {}",
                        candidate,
                        result.getError());
                    if (failures.length() > 0) failures.append("; ");
                    failures.append(candidate)
                            .append(": ")
                            .append(result.getError());
                }
            }

            String failure = failures.length() == 0
                    ? "FFmpeg was not found."
                    : failures.toString();
            FfmpegStatus result = detected == null
                    ? new FfmpegStatus(false, null, null, failure)
                    : detected;

            synchronized (FfmpegBundleManager.class) {
                /*
                 * A concurrent probe may already have published this exact
                 * candidate set. Reuse it instead of advancing the generation
                 * a second time.
                 */
                if (cachedStatus != null
                        && candidateKey.equals(cachedCandidateKey)) {
                    return cachedStatus;
                }
                /*
                 * Invalidation, installation, or another executable publish
                 * makes this process result stale. Re-snapshot configuration
                 * and retry without ever publishing the stale result.
                 */
                if (CACHE_GENERATION.get() != probeGeneration
                        || DOWNLOADING.get()
                        || !candidateKey.equals(candidateKey(
                                buildCandidateList(
                                        RecordishConfig.get())))) {
                    if (DOWNLOADING.get()) {
                        return new FfmpegStatus(
                                false,
                                null,
                                null,
                                "FFmpeg is still downloading.");
                    }
                    continue;
                }

                cachedStatus = result;
                cachedCandidateKey = candidateKey;
                advanceCacheGeneration();
                if (result.isFound()) {
                    STATUS.set(Status.AVAILABLE);
                    lastError = null;
                    if (!"ffmpeg".equals(detectedCandidate)) {
                        config.bundledFfmpegPath = detectedCandidate;
                    }
                    RecordishMod.LOGGER.info(
                        "[FFmpeg] Probe succeeded: executable='{}', version='{}'.",
                        detectedCandidate,
                        result.getVersion());
                } else {
                    lastError = failure;
                    STATUS.set(Status.NOT_FOUND);
                    RecordishMod.LOGGER.info(
                        "[FFmpeg] Executable probe completed without finding FFmpeg: {}",
                        failure);
                }
                return result;
            }
        }
    }

    public static synchronized void invalidateCache() {
        cachedStatus = null;
        cachedCandidateKey = null;
        advanceCacheGeneration();
        if (!DOWNLOADING.get()) STATUS.set(Status.NOT_FOUND);
    }

    /**
     * Returns the last probe result without starting a process. GUI code uses
     * this as an immediate loading-state hint before scheduling a real probe
     * away from Minecraft's render thread.
     */
    public static FfmpegStatus getCachedFfmpegStatus() {
        return cachedStatus;
    }

    public static long getCacheGeneration() {
        return CACHE_GENERATION.get();
    }

    /**
     * Returns the exact cache generation owning {@code status}, or {@code -1}
     * when that probe result has already been invalidated or replaced.
     */
    public static synchronized long getStatusGeneration(
            FfmpegStatus status) {
        return !DOWNLOADING.get()
                && status != null
                && status == cachedStatus
                ? CACHE_GENERATION.get()
                : -1L;
    }

    /**
     * Atomically validates an asynchronous probe result and its generation.
     */
    public static synchronized boolean isCurrentFfmpegStatus(
            FfmpegStatus status,
            long generation) {
        return isCurrentFfmpegStatusLocked(
                status,
                generation);
    }

    public static String getFfprobeExecutable() {
        FfmpegStatus ffmpeg = detectFfmpeg();
        if (!ffmpeg.isFound()) return null;
        try {
            Path executable = java.nio.file.Paths.get(ffmpeg.getExecutable());
            if (executable.getParent() != null) {
                Path sibling = executable.getParent().resolve(PlatformUtils.executableName("ffprobe"));
                if (probe(sibling.toString()).isFound()) return sibling.toString();
            }
        } catch (Exception ignored) {
        }
        return probe("ffprobe").isFound() ? "ffprobe" : null;
    }

    public static List<String> queryEncoders() {
        while (true) {
            FfmpegStatus status = detectFfmpeg();
            long generation = getStatusGeneration(status);
            if (generation < 0L) {
                if (DOWNLOADING.get()) {
                    return Collections.emptyList();
                }
                continue;
            }

            synchronized (FfmpegBundleManager.class) {
                if (!isCurrentFfmpegStatusLocked(
                        status,
                        generation)) {
                    continue;
                }
                EncoderListingCache cached =
                        encoderListingCache;
                if (cached != null
                        && cached.generation == generation
                        && sameExecutable(
                                cached.executable,
                                status.getExecutable())) {
                    return cached.encoders;
                }
                if (!status.isFound()) {
                    List<String> unavailable =
                            Collections.emptyList();
                    encoderListingCache =
                            new EncoderListingCache(
                                    generation,
                                    null,
                                    unavailable);
                    return unavailable;
                }
            }

            ProcessResult processResult = runCommand(
                    asList(
                            status.getExecutable(),
                            "-hide_banner",
                            "-encoders"),
                    10);
            List<String> available = new ArrayList<String>();
            if (processResult.exitCode == 0) {
                for (String encoder : new String[]{
                        "libx264",
                        "mpeg4",
                        "libxvid",
                        "h264_nvenc",
                        "h264_amf",
                        "h264_qsv",
                        "libvpx-vp9",
                        "libvpx"}) {
                    if (encoderListingContains(
                            processResult.output,
                            encoder)) {
                        available.add(encoder);
                    }
                }
            }
            List<String> immutable =
                    Collections.unmodifiableList(available);

            synchronized (FfmpegBundleManager.class) {
                if (!isCurrentFfmpegStatusLocked(
                        status,
                        generation)) {
                    continue;
                }
                EncoderListingCache cached =
                        encoderListingCache;
                if (cached != null
                        && cached.generation == generation
                        && sameExecutable(
                                cached.executable,
                                status.getExecutable())) {
                    return cached.encoders;
                }
                encoderListingCache =
                        new EncoderListingCache(
                                generation,
                                status.getExecutable(),
                                immutable);
                return immutable;
            }
        }
    }

    public static boolean supportsEncoder(String encoder) {
        return queryEncoders().contains(encoder);
    }

    /**
     * Verifies that a listed encoder can initialize on this machine. FFmpeg
     * often advertises NVENC/AMF/QSV even when the corresponding GPU or driver
     * is unavailable.
     */
    public static boolean isEncoderUsable(
            String encoder) {
        if (encoder == null || encoder.trim().isEmpty()) {
            return false;
        }
        while (true) {
            FfmpegStatus status = detectFfmpeg();
            long generation = getStatusGeneration(status);
            if (generation < 0L) {
                if (DOWNLOADING.get()) {
                    return false;
                }
                continue;
            }
            if (!status.isFound()) {
                return false;
            }
            String key = preflightKey(
                    generation,
                    status.getExecutable(),
                    encoder);
            synchronized (FfmpegBundleManager.class) {
                if (!isCurrentFfmpegStatusLocked(
                        status,
                        generation)) {
                    continue;
                }
                Boolean cached =
                        ENCODER_PREFLIGHT.get(key);
                if (cached != null) {
                    return cached.booleanValue();
                }
            }

            List<String> listedEncoders = queryEncoders();
            synchronized (FfmpegBundleManager.class) {
                if (!isCurrentFfmpegStatusLocked(
                        status,
                        generation)) {
                    continue;
                }
                Boolean cached =
                        ENCODER_PREFLIGHT.get(key);
                if (cached != null) {
                    return cached.booleanValue();
                }
                if (!listedEncoders.contains(encoder)) {
                    ENCODER_PREFLIGHT.put(
                            key,
                            Boolean.FALSE);
                    return false;
                }
            }

            ProcessResult processResult = runCommand(asList(
                status.getExecutable(),
                "-nostdin",
                "-hide_banner",
                "-loglevel", "error",
                "-f", "lavfi",
                /*
                 * Current NVENC drivers reject 64x64 H.264 frames even when
                 * the encoder is fully usable at recording resolutions. Keep
                 * this probe small, but above the hardware minimum so
                 * capability detection does not hide a valid NVIDIA encoder.
                 */
                "-i", "color=c=black:s=256x256:r=1",
                "-frames:v", "1",
                "-c:v", encoder,
                "-f", "null",
                "-"), 15);
            boolean usable = processResult.exitCode == 0;
            synchronized (FfmpegBundleManager.class) {
                if (!isCurrentFfmpegStatusLocked(
                        status,
                        generation)) {
                    continue;
                }
                Boolean cached =
                        ENCODER_PREFLIGHT.get(key);
                if (cached != null) {
                    return cached.booleanValue();
                }
                ENCODER_PREFLIGHT.put(
                        key,
                        Boolean.valueOf(usable));
            }
            if (!usable) {
                RecordishMod.LOGGER.warn(
                    "FFmpeg encoder {} is listed but failed its device preflight: {}",
                    encoder,
                    firstLine(processResult.output));
            }
            return usable;
        }
    }

    /**
     * Returns cached listing support for the current exact FFmpeg generation,
     * or {@code null} when no listing has been populated. This never starts a
     * process and is intentionally package-private for encoder startup and
     * smoke-test assertions.
     */
    static synchronized Boolean getCachedEncoderSupport(
            String encoder) {
        FfmpegStatus status = cachedStatus;
        return getCachedEncoderSupport(
                CACHE_GENERATION.get(),
                status == null ? null : status.getExecutable(),
                encoder);
    }

    static synchronized Boolean getCachedEncoderSupport(
            long generation,
            String executable,
            String encoder) {
        if (encoder == null || encoder.trim().isEmpty()) {
            return Boolean.FALSE;
        }
        EncoderListingCache cached = encoderListingCache;
        FfmpegStatus status = cachedStatus;
        if (cached == null
                || status == null
                || !status.isFound()
                || generation != CACHE_GENERATION.get()
                || cached.generation != generation
                || !sameExecutable(
                        executable,
                        status.getExecutable())
                || !sameExecutable(
                        cached.executable,
                        executable)) {
            return null;
        }
        return Boolean.valueOf(cached.encoders.contains(encoder));
    }

    /**
     * Returns a cached device-preflight result for the current exact FFmpeg
     * generation, or {@code null} when that encoder has not been preflighted.
     * No process is launched.
     */
    static synchronized Boolean getCachedEncoderUsability(
            String encoder) {
        FfmpegStatus status = cachedStatus;
        return getCachedEncoderUsability(
                CACHE_GENERATION.get(),
                status == null ? null : status.getExecutable(),
                encoder);
    }

    static synchronized Boolean getCachedEncoderUsability(
            long generation,
            String executable,
            String encoder) {
        FfmpegStatus status = cachedStatus;
        if (status == null
                || !status.isFound()
                || encoder == null
                || encoder.trim().isEmpty()
                || generation != CACHE_GENERATION.get()
                || !sameExecutable(
                        executable,
                        status.getExecutable())) {
            return null;
        }
        return ENCODER_PREFLIGHT.get(preflightKey(
                generation,
                executable,
                encoder));
    }

    static synchronized long getEncoderListingCacheGeneration() {
        EncoderListingCache cached = encoderListingCache;
        return cached == null ? -1L : cached.generation;
    }

    static synchronized int getEncoderPreflightCacheSize() {
        return ENCODER_PREFLIGHT.size();
    }

    public static Status getStatus() {
        return STATUS.get();
    }

    public static String getLastError() {
        return lastError;
    }

    public static DownloadProgress getProgress() {
        return progress;
    }

    public static boolean isDownloading() {
        return DOWNLOADING.get();
    }

    public static void addProgressListener(ProgressListener listener) {
        if (listener != null) LISTENERS.add(listener);
    }

    public static void removeProgressListener(ProgressListener listener) {
        LISTENERS.remove(listener);
    }

    public static boolean isAutoDownloadSupported() {
        PlatformUtils.Platform platform = PlatformUtils.detectPlatform();
        return isX86_64()
            && (platform == PlatformUtils.Platform.WINDOWS
                || platform == PlatformUtils.Platform.LINUX
                || platform == PlatformUtils.Platform.MACOS);
    }

    public static String getDownloadSourceDescription() {
        switch (PlatformUtils.detectPlatform()) {
            case WINDOWS:
                return "gyan.dev FFmpeg release essentials (Windows x64)";
            case LINUX:
                return "johnvansickle.com static FFmpeg (Linux x64)";
            case MACOS:
                return "evermeet.cx static FFmpeg (macOS Intel)";
            default:
                return "unsupported platform";
        }
    }

    public static String getEstimatedDownloadSize() {
        switch (PlatformUtils.detectPlatform()) {
            case WINDOWS:
                return "~103 MB";
            case LINUX:
            case MACOS:
                return "~80 MB";
            default:
                return "n/a";
        }
    }

    /**
     * Plain-text manual installation guidance used by the V1-0.09 welcome and
     * setup screens.
     */
    public static String getManualInstallInstructions() {
        switch (PlatformUtils.detectPlatform()) {
            case WINDOWS:
                return "Manual install: download "
                    + "ffmpeg-release-essentials.zip from "
                    + "https://www.gyan.dev/ffmpeg/builds/ and extract "
                    + "ffmpeg.exe to " + getBundleDirectory() + ".";
            case LINUX:
                return "Manual install: use your distribution package "
                    + "manager, or download a static build from "
                    + "https://johnvansickle.com/ffmpeg/ and place "
                    + "'ffmpeg' at " + getBundleDirectory() + ".";
            case MACOS:
                return "Manual install: run 'brew install ffmpeg', or "
                    + "download from https://evermeet.cx/ffmpeg/ and place "
                    + "'ffmpeg' at " + getBundleDirectory() + ".";
            default:
                return "Install FFmpeg from https://ffmpeg.org/ and add it "
                    + "to PATH, or configure its executable path manually.";
        }
    }

    public static CompletableFuture<Boolean> downloadAsync() {
        PlatformUtils.Platform platform = PlatformUtils.detectPlatform();
        RecordishMod.LOGGER.info(
            "[FFmpeg] Explicit download invocation: platform={}, source='{}', destination='{}'.",
            platform.getDisplayName(),
            getDownloadSourceDescription(),
            getBundleDirectory());
        if (!isAutoDownloadSupported()) {
            lastError = "Automatic FFmpeg download is not supported on "
                + platform.getDisplayName()
                + " / " + System.getProperty("os.arch", "unknown architecture")
                + ". Install FFmpeg manually, then set Custom FFmpeg in "
                + "Recordish's Storage settings.";
            STATUS.set(Status.ERROR);
            RecordishMod.LOGGER.error("[FFmpeg] {}", lastError);
            return CompletableFuture.completedFuture(false);
        }
        synchronized (FfmpegBundleManager.class) {
            if (!DOWNLOADING.compareAndSet(false, true)) {
                RecordishMod.LOGGER.info(
                    "[FFmpeg] A download is already in progress; the duplicate request was ignored.");
                return CompletableFuture.completedFuture(false);
            }
            /*
             * Installing replaces the managed executable. Invalidate every
             * executable-derived result in the same transition that makes the
             * download visible, so no in-flight listing/preflight can publish
             * against the file transaction.
             */
            cachedStatus = null;
            cachedCandidateKey = null;
            advanceCacheGeneration();
            STATUS.set(Status.DOWNLOADING);
            lastError = null;
        }
        fireProgress("starting", 0L, 0L);
        return CompletableFuture.supplyAsync(() -> {
            try {
                FfmpegStatus detected = installForCurrentPlatform(
                    getBundleDirectory());
                synchronized (FfmpegBundleManager.class) {
                    List<String> candidates = buildCandidateList(
                            RecordishConfig.get());
                    cachedStatus = detected;
                    cachedCandidateKey = candidates.contains(
                            detected.getExecutable())
                                    ? candidateKey(candidates)
                                    : null;
                    advanceCacheGeneration();
                    lastError = null;
                    STATUS.set(Status.AVAILABLE);
                    DOWNLOADING.set(false);
                }
                fireProgress("done", 1L, 1L);
                RecordishMod.LOGGER.info(
                    "[FFmpeg] Download, extraction, and probe succeeded. "
                        + "FFmpeg is ready at '{}' ({}).",
                    detected.getExecutable(),
                    detected.getVersion());
                return true;
            } catch (Throwable exception) {
                String failedPhase = progress.getPhase();
                String detail = exception.getMessage() == null
                    ? exception.getClass().getSimpleName() : exception.getMessage();
                synchronized (FfmpegBundleManager.class) {
                    lastError = detail
                        + " Retry the download, or set a working executable in "
                        + "Storage > Custom FFmpeg.";
                    STATUS.set(Status.ERROR);
                    DOWNLOADING.set(false);
                }
                fireProgress("error", 0L, 0L);
                RecordishMod.LOGGER.error(
                    "[FFmpeg] Download/install failed during phase '{}': {}",
                    failedPhase,
                    lastError,
                    exception);
                if (exception instanceof Error) {
                    throw (Error) exception;
                }
                return false;
            }
        });
    }

    static FfmpegStatus installForCurrentPlatform(Path destination)
            throws IOException {
        if (destination == null) {
            throw new IOException(
                "FFmpeg installation destination is unavailable.");
        }
        destination = destination.toAbsolutePath().normalize();
        Files.createDirectories(destination.getParent());
        Path temporary = Files.createTempDirectory(
            destination.getParent(),
            ".recordish-install-");
        Path staged = temporary.resolve("staged-bin");
        Files.createDirectories(staged);
        boolean preserveTemporary = false;
        try {
            PlatformUtils.Platform platform = PlatformUtils.detectPlatform();
            RecordishMod.LOGGER.info(
                "[FFmpeg] Selected source '{}' ({}) for {}.",
                getDownloadSourceDescription(),
                sourceUrl(platform),
                platform.getDisplayName());
            if (platform == PlatformUtils.Platform.WINDOWS) {
                Path archive = temporary.resolve("ffmpeg.zip");
                downloadFile(WINDOWS_URL, archive, "downloading");
                verifyRemoteHash(archive, WINDOWS_HASH_URL, "SHA-256");
                fireProgress("extracting", 0L, 0L);
                RecordishMod.LOGGER.info(
                    "[FFmpeg] Extracting Windows archive '{}' into '{}'.",
                    archive,
                    staged);
                extractZipBinaries(archive, staged);
            } else if (platform == PlatformUtils.Platform.MACOS) {
                Path archive = temporary.resolve("ffmpeg.zip");
                downloadFile(MAC_URL, archive, "downloading");
                fireProgress("extracting", 0L, 0L);
                RecordishMod.LOGGER.info(
                    "[FFmpeg] No upstream checksum endpoint is available for "
                        + "the macOS source; continuing with HTTPS validation.");
                RecordishMod.LOGGER.info(
                    "[FFmpeg] Extracting macOS archive '{}' into '{}'.",
                    archive,
                    staged);
                extractZipBinaries(archive, staged);
                markExecutables(staged);
            } else if (platform == PlatformUtils.Platform.LINUX) {
                Path archive = temporary.resolve("ffmpeg.tar.xz");
                downloadFile(LINUX_URL, archive, "downloading");
                verifyRemoteHash(archive, LINUX_HASH_URL, "MD5");
                fireProgress("extracting", 0L, 0L);
                RecordishMod.LOGGER.info(
                    "[FFmpeg] Extracting validated Linux binaries from '{}' into '{}'.",
                    archive,
                    staged);
                extractLinuxBinaries(archive, staged);
                markExecutables(staged);
            } else {
                throw new IOException("Unsupported operating system.");
            }

            validateStagedInstallation(staged);
            PublishTransaction transaction;
            try {
                transaction = publishStagedBinaries(
                    staged,
                    destination,
                    temporary.resolve("backup-bin"));
            } catch (RollbackFailedException rollbackFailure) {
                preserveTemporary = true;
                throw rollbackFailure;
            }
            try {
                fireProgress("probing", 0L, 0L);
                Path installedExecutable = destination.resolve(
                    PlatformUtils.executableName("ffmpeg"));
                RecordishMod.LOGGER.info(
                    "[FFmpeg] Probing the published executable at '{}'.",
                    installedExecutable);
                FfmpegStatus detected = probe(
                    installedExecutable.toString());
                if (!detected.isFound()) {
                    throw new IOException(
                        "FFmpeg was published but could not be executed. "
                            + "Check antivirus or file permissions, then "
                            + "retry.");
                }
                RecordishMod.LOGGER.info(
                    "[FFmpeg] Validated installation was published to '{}' "
                        + "and passed its live probe.",
                    destination);
                return detected;
            } catch (IOException liveProbeFailure) {
                try {
                    transaction.rollback();
                } catch (IOException rollbackFailure) {
                    preserveTemporary = true;
                    IOException recoveryFailure = new IOException(
                        liveProbeFailure.getMessage()
                            + " Restoring the previous installation also "
                            + "failed; its backup was preserved at "
                            + temporary.resolve("backup-bin") + ".",
                        liveProbeFailure);
                    recoveryFailure.addSuppressed(rollbackFailure);
                    throw recoveryFailure;
                }
                throw liveProbeFailure;
            }
        } finally {
            if (preserveTemporary) {
                RecordishMod.LOGGER.error(
                    "[FFmpeg] Installer recovery was incomplete. Preserving "
                        + "the recovery directory at '{}'.",
                    temporary);
            } else {
                deleteRecursively(temporary);
            }
        }
    }

    private static void downloadFile(String source, Path destination, String phase) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(source).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT);
        connection.setReadTimeout(READ_TIMEOUT);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "Recordish-Forge-1.8.9/1.0");
        int code = connection.getResponseCode();
        long total = connection.getContentLengthLong();
        RecordishMod.LOGGER.info(
            "[FFmpeg] HTTP {} from '{}' (declared size: {}).",
            code,
            connection.getURL(),
            describeBytes(total));
        if (code < 200 || code >= 300) {
            connection.disconnect();
            throw new IOException("Download returned HTTP " + code + " from " + source);
        }
        if (total > MAX_ARCHIVE_SIZE) {
            connection.disconnect();
            throw new IOException(
                "FFmpeg archive is larger than the "
                    + describeBytes(MAX_ARCHIVE_SIZE)
                    + " safety limit.");
        }
        long count = 0L;
        Files.createDirectories(destination.getParent());
        try (InputStream input = new BufferedInputStream(connection.getInputStream());
             OutputStream output = Files.newOutputStream(destination)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                output.write(buffer, 0, read);
                count += read;
                if (count > MAX_ARCHIVE_SIZE) {
                    throw new IOException(
                        "FFmpeg archive exceeded the "
                            + describeBytes(MAX_ARCHIVE_SIZE)
                            + " safety limit while downloading.");
                }
                fireProgress(phase, count, total);
            }
        } finally {
            connection.disconnect();
        }
        if (total > 0L && count != total) {
            throw new IOException(
                "FFmpeg download ended early: expected " + total
                    + " bytes but received " + count + " bytes.");
        }
        RecordishMod.LOGGER.info(
            "[FFmpeg] Download complete: received {} and saved archive to '{}'.",
            describeBytes(count),
            destination);
    }

    private static void verifyRemoteHash(Path archive, String hashUrl, String algorithm) throws IOException {
        fireProgress("checksum", 0L, 0L);
        RecordishMod.LOGGER.info(
            "[FFmpeg] Retrieving {} checksum from '{}'.",
            algorithm,
            hashUrl);
        String expected;
        try {
            expected = downloadSmallText(hashUrl);
        } catch (IOException exception) {
            throw new IOException(
                "Unable to retrieve the required upstream " + algorithm
                    + " checksum from " + hashUrl + ".",
                exception);
        }
        int length = "MD5".equalsIgnoreCase(algorithm) ? 32 : 64;
        java.util.regex.Matcher matcher =
            PatternHolder.hex(length).matcher(expected.toLowerCase(Locale.ROOT));
        if (!matcher.find()) {
            throw new IOException(
                "The checksum response from " + hashUrl
                    + " did not contain a usable " + algorithm + " hash.");
        }
        String expectedHash = matcher.group();
        String actual = computeHash(archive, algorithm);
        RecordishMod.LOGGER.info(
            "[FFmpeg] Computed {} checksum for '{}': {}.",
            algorithm,
            archive,
            actual);
        if (!expectedHash.equalsIgnoreCase(actual)) {
            throw new IOException(
                "FFmpeg " + algorithm + " checksum mismatch (expected "
                    + expectedHash + ", received " + actual + ").");
        }
        RecordishMod.LOGGER.info(
            "[FFmpeg] {} checksum verified successfully against the upstream value.",
            algorithm);
    }

    private static String downloadSmallText(String source) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(source).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT);
        connection.setReadTimeout(READ_TIMEOUT);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "Recordish-Forge-1.8.9/1.0");
        int code = connection.getResponseCode();
        RecordishMod.LOGGER.info(
            "[FFmpeg] Checksum endpoint returned HTTP {} from '{}'.",
            code,
            connection.getURL());
        if (code < 200 || code >= 300) {
            connection.disconnect();
            throw new IOException(
                "Checksum request returned HTTP " + code + " from " + source);
        }
        try (InputStream input = connection.getInputStream();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            copyLimited(
                input,
                output,
                64L * 1024L,
                "checksum response");
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
    }

    private static String computeHash(Path file, String algorithm) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) digest.update(buffer, 0, read);
                }
            }
            StringBuilder result = new StringBuilder();
            for (byte value : digest.digest()) result.append(String.format(Locale.ROOT, "%02x", value & 255));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IOException("Hash algorithm unavailable: " + algorithm, exception);
        }
    }

    private static void extractZipBinaries(Path archive, Path destination) throws IOException {
        boolean foundFfmpeg = false;
        List<String> installed = new ArrayList<String>();
        try (ZipInputStream zip = new ZipInputStream(
                new BufferedInputStream(Files.newInputStream(archive)))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = entry.getName().replace('\\', '/');
                String filename = name.substring(name.lastIndexOf('/') + 1);
                if (!"ffmpeg.exe".equalsIgnoreCase(filename)
                        && !"ffprobe.exe".equalsIgnoreCase(filename)
                        && !"ffmpeg".equals(filename)
                        && !"ffprobe".equals(filename)) {
                    continue;
                }
                Path target = destination.resolve(filename).normalize();
                if (!target.getParent().equals(destination.normalize())) {
                    throw new IOException("Unsafe path in FFmpeg archive.");
                }
                if (entry.getSize() > MAX_BINARY_SIZE) {
                    throw new IOException(
                        "FFmpeg archive entry is unexpectedly large: "
                            + entry.getName());
                }
                try (OutputStream output = Files.newOutputStream(target)) {
                    copyLimited(
                        zip,
                        output,
                        MAX_BINARY_SIZE,
                        entry.getName());
                }
                installed.add(target.toString());
                if (filename.toLowerCase(Locale.ROOT).startsWith("ffmpeg")) foundFfmpeg = true;
            }
        }
        if (!foundFfmpeg) throw new IOException("FFmpeg executable was not present in the archive.");
        RecordishMod.LOGGER.info(
            "[FFmpeg] Extracted {} executable file(s): {}",
            installed.size(),
            installed);
    }

    private static void extractLinuxBinaries(
            Path archive,
            Path destination) throws IOException {
        ProcessResult listing = runCommand(
            asList("tar", "-tJf", archive.toString()),
            60);
        if (listing.exitCode != 0) {
            throw new IOException(
                "Unable to inspect the FFmpeg archive: " + listing.output);
        }

        String ffmpegEntry = null;
        String ffprobeEntry = null;
        for (String raw : listing.output.split("\\r?\\n")) {
            String entry = raw.trim().replace('\\', '/');
            if (entry.isEmpty()) {
                continue;
            }
            validateArchiveEntryPath(entry);
            String filename = entry.substring(entry.lastIndexOf('/') + 1);
            if ("ffmpeg".equals(filename) && ffmpegEntry == null) {
                ffmpegEntry = entry;
            } else if ("ffprobe".equals(filename)
                    && ffprobeEntry == null) {
                ffprobeEntry = entry;
            }
        }
        if (ffmpegEntry == null) {
            throw new IOException(
                "FFmpeg executable was not present in the Linux archive.");
        }

        validateTarRegularEntry(archive, ffmpegEntry);
        extractTarEntry(
            archive,
            ffmpegEntry,
            destination.resolve("ffmpeg"));
        if (ffprobeEntry != null) {
            validateTarRegularEntry(archive, ffprobeEntry);
            extractTarEntry(
                archive,
                ffprobeEntry,
                destination.resolve("ffprobe"));
        }
    }

    private static void validateArchiveEntryPath(String entry)
            throws IOException {
        if (entry.startsWith("/")
                || entry.startsWith("\\")
                || entry.matches("^[A-Za-z]:.*")) {
            throw new IOException(
                "Unsafe absolute path in FFmpeg archive: " + entry);
        }
        for (String part : entry.split("/")) {
            if ("..".equals(part)) {
                throw new IOException(
                    "Unsafe traversal path in FFmpeg archive: " + entry);
            }
        }
    }

    private static void validateTarRegularEntry(
            Path archive,
            String entry) throws IOException {
        ProcessResult metadata = runCommand(
            asList(
                "tar",
                "-tvJf",
                archive.toString(),
                "--",
                entry),
            30);
        if (metadata.exitCode != 0) {
            throw new IOException(
                "Unable to inspect archive entry '" + entry + "': "
                    + metadata.output);
        }
        String line = firstLine(metadata.output);
        if (line.isEmpty() || line.charAt(0) != '-') {
            throw new IOException(
                "FFmpeg archive entry is not a regular file: " + entry);
        }
    }

    private static void extractTarEntry(
            Path archive,
            String entry,
            Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Path partial = target.resolveSibling(
            target.getFileName().toString() + ".partial");
        Files.deleteIfExists(partial);

        Process process = null;
        final ByteArrayOutputStream errors = new ByteArrayOutputStream();
        Thread errorReader = null;
        try {
            process = new ProcessBuilder(asList(
                "tar",
                "-xJOf",
                archive.toString(),
                "--",
                entry)).start();
            final Process running = process;
            errorReader = new Thread(() -> {
                try (InputStream input = running.getErrorStream()) {
                    byte[] buffer = new byte[4096];
                    int read;
                    while ((read = input.read(buffer)) >= 0) {
                        if (read > 0) {
                            errors.write(buffer, 0, read);
                        }
                    }
                } catch (IOException ignored) {
                }
            }, "Recordish-TarErrorReader");
            errorReader.setDaemon(true);
            errorReader.start();

            try (InputStream input =
                    new BufferedInputStream(process.getInputStream());
                 OutputStream output = Files.newOutputStream(partial)) {
                copyLimited(
                    input,
                    output,
                    MAX_BINARY_SIZE,
                    entry);
            }
            if (!process.waitFor(120, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException(
                    "Timed out extracting FFmpeg archive entry: " + entry);
            }
            errorReader.join(1000L);
            if (process.exitValue() != 0) {
                throw new IOException(
                    "Unable to extract FFmpeg archive entry '" + entry
                        + "': "
                        + new String(
                            errors.toByteArray(),
                            StandardCharsets.UTF_8));
            }
            validateBinaryFile(partial, entry);
            moveReplacing(partial, target);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException(
                "Interrupted while extracting FFmpeg archive entry: "
                    + entry,
                exception);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            try {
                Files.deleteIfExists(partial);
            } catch (IOException cleanupFailure) {
                RecordishMod.LOGGER.warn(
                    "[FFmpeg] Could not remove partial archive entry '{}'.",
                    partial,
                    cleanupFailure);
            }
        }
    }

    private static void validateStagedInstallation(Path staged)
            throws IOException {
        Path executable = staged.resolve(
            PlatformUtils.executableName("ffmpeg"));
        validateBinaryFile(executable, "ffmpeg");
        Path probe = staged.resolve(
            PlatformUtils.executableName("ffprobe"));
        if (Files.exists(probe)) {
            validateBinaryFile(probe, "ffprobe");
        }

        fireProgress("probing", 0L, 0L);
        FfmpegStatus status = probe(executable.toString());
        if (!status.isFound()) {
            throw new IOException(
                "The downloaded FFmpeg executable failed its staged "
                    + "self-test: " + status.getError());
        }
        RecordishMod.LOGGER.info(
            "[FFmpeg] Staged executable passed its probe: {}.",
            status.getVersion());
    }

    private static void validateBinaryFile(Path file, String label)
            throws IOException {
        if (!Files.isRegularFile(file) || Files.isSymbolicLink(file)) {
            throw new IOException(
                "FFmpeg archive entry is not a regular file: " + label);
        }
        long size = Files.size(file);
        if (size <= 0L || size > MAX_BINARY_SIZE) {
            throw new IOException(
                "FFmpeg archive entry has an invalid size: " + label
                    + " (" + describeBytes(size) + ").");
        }
    }

    private static PublishTransaction publishStagedBinaries(
            Path staged,
            Path destination,
            Path backupDirectory) throws IOException {
        Files.createDirectories(destination);
        Files.createDirectories(backupDirectory);

        List<Path> sources = new ArrayList<Path>();
        for (String name : new String[]{
                PlatformUtils.executableName("ffmpeg"),
                PlatformUtils.executableName("ffprobe")}) {
            Path source = staged.resolve(name);
            if (Files.isRegularFile(source)
                    && !Files.isSymbolicLink(source)) {
                sources.add(source);
            }
        }

        Map<Path, Path> backups = new HashMap<Path, Path>();
        List<Path> published = new ArrayList<Path>();
        PublishTransaction transaction =
            new PublishTransaction(backups, published);
        try {
            for (Path source : sources) {
                Path target = destination.resolve(source.getFileName());
                if (Files.exists(target)) {
                    Path backup =
                        backupDirectory.resolve(source.getFileName());
                    moveReplacing(target, backup);
                    backups.put(target, backup);
                }
                moveReplacing(source, target);
                published.add(target);
            }
        } catch (IOException publishFailure) {
            IOException rollbackFailure = null;
            try {
                transaction.rollback();
            } catch (IOException exception) {
                rollbackFailure = exception;
            }
            IOException failure = new IOException(
                "Unable to publish the validated FFmpeg installation; "
                    + (rollbackFailure == null
                        ? "the previous installation was restored."
                        : "restoring the previous installation also failed."),
                publishFailure);
            if (rollbackFailure != null) {
                throw new RollbackFailedException(
                    "Unable to publish the validated FFmpeg installation, "
                        + "and restoring the previous installation failed. "
                        + "Its backup will be preserved at "
                        + backupDirectory + ".",
                    publishFailure,
                    rollbackFailure);
            }
            throw failure;
        }
        return transaction;
    }

    private static final class RollbackFailedException
            extends IOException {
        private RollbackFailedException(
                String message,
                Throwable cause,
                Throwable rollbackFailure) {
            super(message, cause);
            if (rollbackFailure != null) {
                addSuppressed(rollbackFailure);
            }
        }
    }

    private static void moveReplacing(Path source, Path target)
            throws IOException {
        Files.createDirectories(target.getParent());
        try {
            Files.move(
                source,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException atomicFailure) {
            Path transfer = Files.createTempFile(
                target.getParent(),
                "." + target.getFileName().toString() + ".",
                ".recordish-transfer");
            try {
                Files.copy(
                    source,
                    transfer,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.COPY_ATTRIBUTES);
                if (Files.size(source) != Files.size(transfer)) {
                    throw new IOException(
                        "Transfer size mismatch while publishing "
                            + target.getFileName() + ".");
                }
                try {
                    Files.move(
                        transfer,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException transferAtomicFailure) {
                    try {
                        Files.move(
                            transfer,
                            target,
                            StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException transferFailure) {
                        transferFailure.addSuppressed(
                            transferAtomicFailure);
                        throw transferFailure;
                    }
                }
                try {
                    Files.delete(source);
                } catch (IOException cleanupFailure) {
                    RecordishMod.LOGGER.warn(
                        "[FFmpeg] Published '{}' but could not remove "
                            + "the source copy '{}'.",
                        target,
                        source,
                        cleanupFailure);
                }
            } catch (IOException transferFailure) {
                try {
                    Files.deleteIfExists(transfer);
                } catch (IOException cleanupFailure) {
                    transferFailure.addSuppressed(cleanupFailure);
                }
                if (transferFailure != atomicFailure) {
                    transferFailure.addSuppressed(atomicFailure);
                }
                throw transferFailure;
            }
        }
    }

    private static final class PublishTransaction {
        private final Map<Path, Path> backups;
        private final List<Path> published;
        private boolean rolledBack;

        private PublishTransaction(
                Map<Path, Path> backups,
                List<Path> published) {
            this.backups = backups;
            this.published = published;
        }

        private void rollback() throws IOException {
            if (rolledBack) {
                return;
            }
            rolledBack = true;
            IOException failure = null;
            for (Path target : published) {
                if (!backups.containsKey(target)) {
                    try {
                        Files.deleteIfExists(target);
                    } catch (IOException exception) {
                        if (failure == null) {
                            failure = exception;
                        } else {
                            failure.addSuppressed(exception);
                        }
                    }
                }
            }
            for (Map.Entry<Path, Path> backup : backups.entrySet()) {
                try {
                    if (Files.exists(backup.getValue())) {
                        moveReplacing(
                            backup.getValue(),
                            backup.getKey());
                    }
                } catch (IOException exception) {
                    if (failure == null) {
                        failure = exception;
                    } else {
                        failure.addSuppressed(exception);
                    }
                }
            }
            if (failure != null) {
                throw new IOException(
                    "The previous FFmpeg installation could not be fully "
                        + "restored.",
                    failure);
            }
        }
    }

    private static long copyLimited(
            InputStream input,
            OutputStream output,
            long maximumBytes,
            String label) throws IOException {
        long count = 0L;
        byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read == 0) {
                continue;
            }
            count += read;
            if (count > maximumBytes) {
                throw new IOException(
                    "FFmpeg archive entry exceeds the safety limit: "
                        + label);
            }
            output.write(buffer, 0, read);
        }
        return count;
    }

    private static void copyFoundBinary(Path root, Path destination, String name) throws IOException {
        Path found = findFile(root, name);
        if (found != null) {
            Path target = destination.resolve(name);
            Files.copy(found, target, StandardCopyOption.REPLACE_EXISTING);
            RecordishMod.LOGGER.info(
                "[FFmpeg] Installed extracted '{}' binary at '{}'.",
                name,
                target);
        } else if ("ffmpeg".equals(name)) {
            throw new IOException("FFmpeg executable was not present in the archive.");
        }
    }

    private static Path findFile(Path root, String name) throws IOException {
        final Path[] found = new Path[1];
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (found[0] == null && name.equals(file.getFileName().toString())) {
                    found[0] = file;
                    return FileVisitResult.TERMINATE;
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return found[0];
    }

    private static void markExecutables(Path directory) {
        for (String name : new String[]{"ffmpeg", "ffprobe"}) {
            directory.resolve(name).toFile().setExecutable(true, false);
        }
    }

    private static FfmpegStatus probe(String candidate) {
        if (candidate == null || candidate.trim().isEmpty()) {
            return new FfmpegStatus(false, candidate, null, "empty path");
        }
        ProcessResult result = runCommand(asList(candidate, "-hide_banner", "-version"), 8);
        if (result.exitCode == 0 && result.output.toLowerCase(Locale.ROOT).contains("ffmpeg version")) {
            String line = firstLine(result.output);
            return new FfmpegStatus(true, candidate, line, null);
        }
        return new FfmpegStatus(false, candidate, null,
            result.output.trim().isEmpty() ? "not executable" : firstLine(result.output));
    }

    static ProcessResult runCommand(List<String> command, int timeoutSeconds) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            final Process running = process;
            final ByteArrayOutputStream output = new ByteArrayOutputStream();
            Thread reader = new Thread(() -> {
                try (InputStream input = running.getInputStream()) {
                    byte[] buffer = new byte[4096];
                    int read;
                    while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
                } catch (IOException ignored) {
                }
            }, "Recordish-ProcessReader");
            reader.setDaemon(true);
            reader.start();
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                reader.join(1000L);
                return new ProcessResult(-1, "timed out");
            }
            reader.join(1000L);
            return new ProcessResult(process.exitValue(),
                new String(output.toByteArray(), StandardCharsets.UTF_8));
        } catch (Exception exception) {
            if (process != null) process.destroyForcibly();
            return new ProcessResult(-1, exception.getMessage() == null
                ? exception.getClass().getSimpleName() : exception.getMessage());
        }
    }

    static final class ProcessResult {
        final int exitCode;
        final String output;

        ProcessResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output == null ? "" : output;
        }
    }

    private static final class EncoderListingCache {
        final long generation;
        final String executable;
        final List<String> encoders;

        EncoderListingCache(
                long generation,
                String executable,
                List<String> encoders) {
            this.generation = generation;
            this.executable = executable;
            this.encoders = encoders;
        }
    }

    /**
     * Advances all executable-derived caches as one generation. Callers hold
     * the class monitor whenever executable state is replaced or invalidated.
     */
    private static void advanceCacheGeneration() {
        CACHE_GENERATION.incrementAndGet();
        encoderListingCache = null;
        ENCODER_PREFLIGHT.clear();
    }

    /**
     * The caller holds {@code FfmpegBundleManager.class}.
     */
    private static boolean isCurrentFfmpegStatusLocked(
            FfmpegStatus status,
            long generation) {
        return !DOWNLOADING.get()
                && status != null
                && status == cachedStatus
                && generation == CACHE_GENERATION.get();
    }

    private static String preflightKey(
            long generation,
            String executable,
            String encoder) {
        return generation
                + "\n"
                + (executable == null ? "" : executable)
                + "\n"
                + encoder;
    }

    private static boolean sameExecutable(
            String first,
            String second) {
        return first == null ? second == null : first.equals(second);
    }

    private static List<String> buildCandidateList(
            RecordishConfig config) {
        List<String> candidates = new ArrayList<String>();
        addCandidate(candidates, config.ffmpegPath);
        addCandidate(
                candidates,
                System.getenv("RECORDISH_FFMPEG_PATH"));
        addCandidate(
                candidates,
                System.getenv("RECORDABLE_FFMPEG_PATH"));
        if (config.useBundledFfmpeg) {
            addCandidate(
                    candidates,
                    config.bundledFfmpegPath);
            addCandidate(
                    candidates,
                    getBundleDirectory()
                            .resolve(
                                    PlatformUtils.executableName(
                                            "ffmpeg"))
                            .toString());
        }
        addCandidate(candidates, "ffmpeg");
        return candidates;
    }

    private static String candidateKey(
            List<String> candidates) {
        StringBuilder key = new StringBuilder();
        for (String candidate : candidates) {
            if (key.length() > 0) {
                key.append('\n');
            }
            key.append(candidate.length())
                    .append(':')
                    .append(candidate);
        }
        return key.toString();
    }

    private static void addCandidate(List<String> values, String candidate) {
        if (candidate == null || candidate.trim().isEmpty()) return;
        String normalized = candidate.trim();
        if (!values.contains(normalized)) values.add(normalized);
    }

    private static List<String> asList(String... values) {
        List<String> result = new ArrayList<String>(values.length);
        Collections.addAll(result, values);
        return result;
    }

    private static String firstLine(String value) {
        int newline = value.indexOf('\n');
        return (newline < 0 ? value : value.substring(0, newline)).trim();
    }

    private static boolean encoderListingContains(
            String listing,
            String encoder) {
        if (listing == null || encoder == null) return false;
        String[] lines = listing.split("\\r?\\n");
        for (String line : lines) {
            String[] fields = line.trim().split("\\s+", 3);
            if (fields.length >= 2
                    && encoder.equals(fields[1])) {
                return true;
            }
        }
        return false;
    }

    private static String sourceUrl(PlatformUtils.Platform platform) {
        if (platform == PlatformUtils.Platform.WINDOWS) return WINDOWS_URL;
        if (platform == PlatformUtils.Platform.LINUX) return LINUX_URL;
        if (platform == PlatformUtils.Platform.MACOS) return MAC_URL;
        return "none";
    }

    private static boolean isX86_64() {
        String architecture = System.getProperty(
            "os.arch",
            "").toLowerCase(Locale.ROOT);
        return "amd64".equals(architecture)
            || "x86_64".equals(architecture)
            || "x64".equals(architecture);
    }

    private static String describeBytes(long bytes) {
        if (bytes < 0L) return "unknown";
        if (bytes < 1024L) return bytes + " B";
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0D);
        }
        return String.format(
            Locale.ROOT,
            "%.1f MB",
            bytes / (1024.0D * 1024.0D));
    }

    private static void fireProgress(String phase, long downloaded, long total) {
        progress = new DownloadProgress(phase, downloaded, total);
        for (ProgressListener listener : LISTENERS) {
            try {
                listener.onProgress(progress);
            } catch (RuntimeException exception) {
                RecordishMod.LOGGER.debug("FFmpeg progress listener failed.", exception);
            }
        }
    }

    private static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) return;
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                    if (error != null) throw error;
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException exception) {
            RecordishMod.LOGGER.debug("Unable to remove temporary FFmpeg directory {}", root, exception);
        }
    }

    private static final class PatternHolder {
        private static java.util.regex.Pattern hex(int length) {
            return java.util.regex.Pattern.compile("[0-9a-f]{" + length + "}");
        }
    }
}
