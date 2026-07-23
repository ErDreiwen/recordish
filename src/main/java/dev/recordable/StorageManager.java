package dev.recordable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Pure-Java storage backend for Record-able's gallery and storage dashboard.
 */
public final class StorageManager {
    private static final String[] VIDEO_EXTENSIONS = {
            ".mp4", ".mkv", ".webm", ".mov", ".avi", ".gif"
    };
    private static final long BYTES_PER_MEBIBYTE = 1024L * 1024L;
    private static final int MAX_CAPTURED_PROCESS_BYTES = 4 * 1024 * 1024;
    private static final Logger LOGGER = Logger.getLogger("Record-able");

    private static volatile FfmpegCommandProvider ffmpegCommandProvider =
            new DefaultFfmpegCommandProvider();

    private StorageManager() {
    }

    /** Lightweight information that does not require probing the media file. */
    public static final class StoredFile {
        private final Path path;
        private final String filename;
        private final long sizeBytes;
        private final long modifiedMillis;
        private final boolean protectedFlag;

        public StoredFile(Path path, String filename, long sizeBytes,
                          long modifiedMillis, boolean protectedFlag) {
            this.path = path;
            this.filename = filename == null ? "" : filename;
            this.sizeBytes = Math.max(0L, sizeBytes);
            this.modifiedMillis = Math.max(0L, modifiedMillis);
            this.protectedFlag = protectedFlag;
        }

        public Path path() {
            return path;
        }

        public String filename() {
            return filename;
        }

        public long sizeBytes() {
            return sizeBytes;
        }

        public long modifiedMillis() {
            return modifiedMillis;
        }

        public boolean protectedFlag() {
            return protectedFlag;
        }

        public Path getPath() {
            return path;
        }

        public String getFilename() {
            return filename;
        }

        public long getSizeBytes() {
            return sizeBytes;
        }

        public long getModifiedMillis() {
            return modifiedMillis;
        }

        public boolean isProtected() {
            return protectedFlag;
        }

        public String sizeDisplay() {
            return humanReadable(sizeBytes);
        }
    }

    /** Aggregate recording-folder and volume statistics. */
    public static final class StorageStats {
        private final long recordingsBytes;
        private final int recordingCount;
        private final long diskFreeBytes;
        private final long diskTotalBytes;
        private final int diskUsedPercent;

        public StorageStats(long recordingsBytes, int recordingCount,
                            long diskFreeBytes, long diskTotalBytes,
                            int diskUsedPercent) {
            this.recordingsBytes = Math.max(0L, recordingsBytes);
            this.recordingCount = Math.max(0, recordingCount);
            this.diskFreeBytes = diskFreeBytes;
            this.diskTotalBytes = diskTotalBytes;
            this.diskUsedPercent = Math.max(0, Math.min(100, diskUsedPercent));
        }

        public long recordingsBytes() {
            return recordingsBytes;
        }

        public int recordingCount() {
            return recordingCount;
        }

        public long diskFreeBytes() {
            return diskFreeBytes;
        }

        public long diskTotalBytes() {
            return diskTotalBytes;
        }

        public int diskUsedPercent() {
            return diskUsedPercent;
        }

        public long getRecordingsBytes() {
            return recordingsBytes;
        }

        public int getRecordingCount() {
            return recordingCount;
        }

        public long getDiskFreeBytes() {
            return diskFreeBytes;
        }

        public long getDiskTotalBytes() {
            return diskTotalBytes;
        }

        public int getDiskUsedPercent() {
            return diskUsedPercent;
        }

        public String recordingsDisplay() {
            return humanReadable(recordingsBytes);
        }

        public String diskFreeDisplay() {
            return humanReadable(diskFreeBytes);
        }

        public String diskTotalDisplay() {
            return humanReadable(diskTotalBytes);
        }
    }

    /** Outcome of an age/size cleanup pass. */
    public static final class CleanupResult {
        private final int filesDeleted;
        private final long bytesFreed;
        private final List<String> deletedNames;

        public CleanupResult(int filesDeleted, long bytesFreed, List<String> deletedNames) {
            this.filesDeleted = Math.max(0, filesDeleted);
            this.bytesFreed = Math.max(0L, bytesFreed);
            this.deletedNames = deletedNames == null
                    ? Collections.<String>emptyList()
                    : Collections.unmodifiableList(new ArrayList<String>(deletedNames));
        }

        public int filesDeleted() {
            return filesDeleted;
        }

        public long bytesFreed() {
            return bytesFreed;
        }

        public List<String> deletedNames() {
            return deletedNames;
        }

        public int getFilesDeleted() {
            return filesDeleted;
        }

        public long getBytesFreed() {
            return bytesFreed;
        }

        public List<String> getDeletedNames() {
            return deletedNames;
        }

        public String bytesFreedDisplay() {
            return humanReadable(bytesFreed);
        }
    }

    /**
     * Hook for the recorder's FFmpeg discovery/bundle layer. The default
     * implementation uses config paths, environment overrides, and PATH.
     */
    public interface FfmpegCommandProvider {
        String findFfmpeg(RecordableConfig config);

        String findFfprobe(RecordableConfig config);

        ProcessBuilder createProcess(String executable, List<String> arguments);
    }

    /** Captured result used by the metadata and chapter helpers. */
    public static final class ProcessResult {
        private final int exitCode;
        private final String output;
        private final boolean timedOut;

        private ProcessResult(int exitCode, String output, boolean timedOut) {
            this.exitCode = exitCode;
            this.output = output == null ? "" : output;
            this.timedOut = timedOut;
        }

        public int exitCode() {
            return exitCode;
        }

        public String output() {
            return output;
        }

        public boolean timedOut() {
            return timedOut;
        }

        public boolean succeeded() {
            return !timedOut && exitCode == 0;
        }
    }

    public static void setFfmpegCommandProvider(FfmpegCommandProvider provider) {
        ffmpegCommandProvider = provider == null
                ? new DefaultFfmpegCommandProvider()
                : provider;
    }

    public static FfmpegCommandProvider getFfmpegCommandProvider() {
        return ffmpegCommandProvider;
    }

    public static boolean isVideoFile(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        for (String extension : VIDEO_EXTENSIONS) {
            if (lower.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    /** Lists direct child recordings, newest first. */
    public static List<StoredFile> listRecordings(RecordableConfig config) {
        List<StoredFile> recordings = new ArrayList<StoredFile>();
        if (config == null) {
            return recordings;
        }
        Path directory = config.getOutputDirectory();
        if (directory == null || !Files.isDirectory(directory)) {
            return recordings;
        }

        collectRecordingsFromDirectory(directory, config, recordings);
        Path clipDirectory = config.getClipDirectory();
        if (clipDirectory != null
                && !clipDirectory.equals(directory)
                && Files.isDirectory(clipDirectory)) {
            collectRecordingsFromDirectory(
                    clipDirectory,
                    config,
                    recordings);
        }

        Collections.sort(recordings, new Comparator<StoredFile>() {
            @Override
            public int compare(StoredFile left, StoredFile right) {
                return Long.compare(right.modifiedMillis(), left.modifiedMillis());
            }
        });
        return recordings;
    }

    private static void collectRecordingsFromDirectory(
            Path directory,
            RecordableConfig config,
            List<StoredFile> recordings) {
        try (Stream<Path> stream = Files.list(directory)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> isVideoFile(filename(path)))
                    .forEach(path -> {
                        String name = filename(path);
                        recordings.add(new StoredFile(
                                path,
                                name,
                                safeSize(path),
                                safeModified(path),
                                isProtected(config, name)));
                    });
        } catch (IOException exception) {
            LOGGER.log(Level.WARNING, "Failed to list recordings in " + directory, exception);
        }
    }

    public static StorageStats computeStats(RecordableConfig config) {
        List<StoredFile> files = listRecordings(config);
        long recordingsBytes = 0L;
        for (StoredFile file : files) {
            recordingsBytes = saturatedAdd(recordingsBytes, file.sizeBytes());
        }

        long freeBytes = -1L;
        long totalBytes = -1L;
        int usedPercent = 0;
        if (config != null) {
            try {
                Path directory = config.getOutputDirectory();
                if (directory != null) {
                    Files.createDirectories(directory);
                    FileStore store = Files.getFileStore(directory);
                    totalBytes = store.getTotalSpace();
                    freeBytes = store.getUsableSpace();
                    if (totalBytes > 0L) {
                        usedPercent = (int) Math.max(0L, Math.min(
                                100L, 100L - (freeBytes * 100L / totalBytes)));
                    }
                }
            } catch (Exception exception) {
                LOGGER.log(Level.FINE, "Failed to read recording volume statistics", exception);
            }
        }

        return new StorageStats(
                recordingsBytes,
                files.size(),
                freeBytes,
                totalBytes,
                usedPercent);
    }

    public static void toggleProtected(RecordableConfig config, String filename) {
        if (config == null || isBlank(filename)) {
            return;
        }
        String normalized = filename.trim();
        if (config.storageProtectedFiles == null) {
            config.storageProtectedFiles = new ArrayList<String>();
        }
        if (config.storageProtectedFiles.contains(normalized)) {
            config.storageProtectedFiles.remove(normalized);
        } else {
            config.storageProtectedFiles.add(normalized);
        }
        config.save();
    }

    public static boolean isProtected(RecordableConfig config, String filename) {
        return config != null
                && !isBlank(filename)
                && config.storageProtectedFiles != null
                && config.storageProtectedFiles.contains(filename);
    }

    public static boolean isProtected(RecordableConfig config, Path file) {
        return file != null && isProtected(config, filename(file));
    }

    /**
     * Deletes one recording and its known marker/chapter sidecars. A protected
     * recording is never removed.
     */
    public static boolean deleteRecording(RecordableConfig config, Path file) {
        if (file == null || isProtected(config, file)) {
            return false;
        }
        boolean deleted = deleteWithSidecars(config, file);
        if (deleted) {
            VideoMetadata.clearCache();
        }
        return deleted;
    }

    /**
     * Runs the configured age rule, then enforces the configured total-size
     * cap by deleting the oldest unprotected recordings.
     */
    public static CleanupResult runCleanup(RecordableConfig config, boolean force) {
        List<String> deletedNames = new ArrayList<String>();
        if (config == null || (!force && !config.autoCleanupEnabled)) {
            return new CleanupResult(0, 0L, deletedNames);
        }

        long bytesFreed = 0L;
        List<StoredFile> files = listRecordings(config);
        long now = System.currentTimeMillis();
        long ageDays = Math.max(1L, config.autoCleanupOlderThanDays);
        long ageLimitMillis = ageDays * 24L * 60L * 60L * 1000L;

        for (StoredFile file : files) {
            if (file.protectedFlag()) {
                continue;
            }
            if (now - file.modifiedMillis() > ageLimitMillis
                    && deleteWithSidecars(config, file.path())) {
                deletedNames.add(file.filename());
                bytesFreed = saturatedAdd(bytesFreed, file.sizeBytes());
            }
        }

        if (config.autoCleanupMaxTotalMB > 0) {
            long maximumBytes = config.autoCleanupMaxTotalMB * BYTES_PER_MEBIBYTE;
            List<StoredFile> remaining = listRecordings(config);
            long totalBytes = 0L;
            for (StoredFile file : remaining) {
                totalBytes = saturatedAdd(totalBytes, file.sizeBytes());
            }

            Collections.sort(remaining, new Comparator<StoredFile>() {
                @Override
                public int compare(StoredFile left, StoredFile right) {
                    return Long.compare(left.modifiedMillis(), right.modifiedMillis());
                }
            });

            for (StoredFile file : remaining) {
                if (totalBytes <= maximumBytes) {
                    break;
                }
                if (file.protectedFlag()) {
                    continue;
                }
                if (deleteWithSidecars(config, file.path())) {
                    deletedNames.add(file.filename());
                    bytesFreed = saturatedAdd(bytesFreed, file.sizeBytes());
                    totalBytes = Math.max(0L, totalBytes - file.sizeBytes());
                }
            }
        }

        if (!deletedNames.isEmpty()) {
            VideoMetadata.clearCache();
        }
        return new CleanupResult(deletedNames.size(), bytesFreed, deletedNames);
    }

    /**
     * Recompresses a recording to H.264/AAC using the configured CRF.
     *
     * <p>The source is never deleted. A protected destination is never
     * overwritten, and FFmpeg writes to a temporary sibling before the final
     * move so a failed encode cannot destroy an existing output.</p>
     */
    public static Path compressRecording(RecordableConfig config, Path source) {
        if (config == null || source == null || !Files.isRegularFile(source)) {
            return null;
        }

        String executable = resolveFfmpegExecutable(config);
        if (isBlank(executable)) {
            LOGGER.warning("FFmpeg is unavailable; recording cannot be compressed.");
            return null;
        }

        String stem = stem(source);
        Path output = source.resolveSibling(stem + "_compressed.mp4");
        Path temporary = source.resolveSibling(stem + "_compressed.part.mp4");
        if (isProtected(config, output) || isProtected(config, temporary)) {
            LOGGER.warning("Refusing to overwrite a protected compression output: " + output);
            return null;
        }

        try {
            Files.deleteIfExists(temporary);
            List<String> arguments = new ArrayList<String>();
            Collections.addAll(arguments,
                    "-nostdin",
                    "-hide_banner",
                    "-y",
                    "-i", source.toAbsolutePath().toString(),
                    "-c:v", "libx264",
                    "-crf", Integer.toString(clamp(config.storageCompressionCrf, 0, 51)),
                    "-preset", "medium",
                    "-c:a", "aac",
                    temporary.toAbsolutePath().toString());

            ProcessResult result = runProcess(
                    ffmpegCommandProvider.createProcess(executable, arguments),
                    24L,
                    TimeUnit.HOURS);
            if (!result.succeeded() || !Files.isRegularFile(temporary)
                    || Files.size(temporary) <= 0L) {
                LOGGER.warning("FFmpeg compression failed"
                        + (result.timedOut() ? " (timed out)" : " with exit code " + result.exitCode())
                        + outputSuffix(result.output()));
                Files.deleteIfExists(temporary);
                return null;
            }

            try {
                Files.move(
                        temporary,
                        output,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveFailure) {
                Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
            }
            VideoMetadata.clearCache();
            return output;
        } catch (Exception exception) {
            LOGGER.log(Level.WARNING, "Compression failed for " + source, exception);
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
            }
            return null;
        }
    }

    static String resolveFfmpegExecutable(RecordableConfig config) {
        FfmpegCommandProvider provider = ffmpegCommandProvider;
        return provider == null ? null : provider.findFfmpeg(config);
    }

    static String resolveFfprobeExecutable(RecordableConfig config) {
        FfmpegCommandProvider provider = ffmpegCommandProvider;
        return provider == null ? null : provider.findFfprobe(config);
    }

    static ProcessBuilder createMediaProcess(String executable, List<String> arguments) {
        FfmpegCommandProvider provider = ffmpegCommandProvider;
        if (provider == null) {
            provider = new DefaultFfmpegCommandProvider();
        }
        return provider.createProcess(executable, arguments);
    }

    static ProcessResult runProcess(ProcessBuilder builder, long timeout, TimeUnit unit)
            throws IOException, InterruptedException {
        if (builder == null) {
            return new ProcessResult(-1, "", false);
        }

        builder.redirectErrorStream(true);
        Process process = builder.start();
        StreamCollector collector = new StreamCollector(process.getInputStream());
        Thread reader = new Thread(collector, "recordable-process-output");
        reader.setDaemon(true);
        reader.start();

        final boolean exited;
        try {
            exited = process.waitFor(timeout, unit);
        } catch (InterruptedException interrupted) {
            process.destroy();
            try {
                if (!process.waitFor(2L, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(2L, TimeUnit.SECONDS);
                }
            } catch (InterruptedException secondInterrupt) {
                process.destroyForcibly();
            }
            Thread.currentThread().interrupt();
            throw interrupted;
        }
        if (!exited) {
            process.destroy();
            if (!process.waitFor(2L, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(2L, TimeUnit.SECONDS);
            }
        }

        reader.join(2000L);
        if (reader.isAlive()) {
            try {
                process.getInputStream().close();
            } catch (IOException ignored) {
            }
            reader.join(500L);
        }

        int exitCode = exited ? process.exitValue() : -1;
        return new ProcessResult(exitCode, collector.output(), !exited);
    }

    private static boolean deleteWithSidecars(RecordableConfig config, Path file) {
        if (file == null || isProtected(config, file)) {
            return false;
        }
        final boolean deleted;
        try {
            deleted = Files.deleteIfExists(file);
        } catch (IOException exception) {
            LOGGER.log(Level.WARNING, "Failed to delete recording " + file, exception);
            return false;
        }
        if (!deleted) {
            return false;
        }

        String stem = stem(file);
        deleteBestEffort(file.resolveSibling(stem + "_markers.txt"));
        deleteBestEffort(file.resolveSibling(stem + "_chapters.txt"));
        deleteBestEffort(file.resolveSibling(stem + "_ffmeta.txt"));
        return true;
    }

    private static void deleteBestEffort(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException exception) {
            LOGGER.log(Level.FINE, "Unable to delete sidecar " + file, exception);
        }
    }

    private static String filename(Path path) {
        return path == null || path.getFileName() == null
                ? ""
                : path.getFileName().toString();
    }

    private static String stem(Path path) {
        String name = filename(path);
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static long safeSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException exception) {
            return 0L;
        }
    }

    private static long safeModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException exception) {
            return 0L;
        }
    }

    public static String humanReadable(long bytes) {
        if (bytes < 0L) {
            return "Unknown";
        }
        if (bytes < 1024L) {
            return bytes + " B";
        }
        double kibibytes = bytes / 1024.0D;
        if (kibibytes < 1024.0D) {
            return String.format(Locale.ROOT, "%.1f KB", kibibytes);
        }
        double mebibytes = kibibytes / 1024.0D;
        if (mebibytes < 1024.0D) {
            return String.format(Locale.ROOT, "%.1f MB", mebibytes);
        }
        return String.format(Locale.ROOT, "%.2f GB", mebibytes / 1024.0D);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static long saturatedAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private static String outputSuffix(String output) {
        if (isBlank(output)) {
            return "";
        }
        String normalized = output.replace('\r', ' ').replace('\n', ' ').trim();
        if (normalized.length() > 300) {
            normalized = normalized.substring(normalized.length() - 300);
        }
        return ": " + normalized;
    }

    private static final class StreamCollector implements Runnable {
        private final InputStream input;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();

        private StreamCollector(InputStream input) {
            this.input = input;
        }

        @Override
        public void run() {
            byte[] buffer = new byte[8192];
            try {
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    if (output.size() < MAX_CAPTURED_PROCESS_BYTES) {
                        int writable = Math.min(
                                count,
                                MAX_CAPTURED_PROCESS_BYTES - output.size());
                        output.write(buffer, 0, writable);
                    }
                }
            } catch (IOException ignored) {
            } finally {
                try {
                    input.close();
                } catch (IOException ignored) {
                }
            }
        }

        private String output() {
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static final class DefaultFfmpegCommandProvider
            implements FfmpegCommandProvider {
        @Override
        public String findFfmpeg(RecordableConfig config) {
            if (config != null && !isBlank(config.ffmpegPath)) {
                return config.ffmpegPath.trim();
            }
            String environment = System.getenv("RECORDABLE_FFMPEG_PATH");
            if (!isBlank(environment)) {
                return environment.trim();
            }
            if (config != null
                    && config.useBundledFfmpeg
                    && !isBlank(config.bundledFfmpegPath)) {
                return config.bundledFfmpegPath.trim();
            }
            try {
                FfmpegBundleManager.FfmpegStatus detected =
                        FfmpegBundleManager.detectFfmpeg();
                if (detected != null
                        && detected.isFound()
                        && !isBlank(detected.getExecutable())) {
                    return detected.getExecutable();
                }
            } catch (RuntimeException exception) {
                LOGGER.log(Level.FINE, "Bundled FFmpeg detection failed", exception);
            }
            return "ffmpeg";
        }

        @Override
        public String findFfprobe(RecordableConfig config) {
            String environment = System.getenv("RECORDABLE_FFPROBE_PATH");
            if (!isBlank(environment)) {
                return environment.trim();
            }

            try {
                String bundledProbe = FfmpegBundleManager.getFfprobeExecutable();
                if (!isBlank(bundledProbe)) {
                    return bundledProbe;
                }
            } catch (RuntimeException exception) {
                LOGGER.log(Level.FINE, "Bundled ffprobe detection failed", exception);
            }

            String ffmpeg = findFfmpeg(config);
            if (!isBlank(ffmpeg)) {
                try {
                    Path ffmpegPath = Paths.get(ffmpeg);
                    Path parent = ffmpegPath.getParent();
                    if (parent != null) {
                        String executable = PlatformUtils.isWindows()
                                ? "ffprobe.exe"
                                : "ffprobe";
                        Path sibling = parent.resolve(executable);
                        if (Files.isRegularFile(sibling)) {
                            return sibling.toAbsolutePath().toString();
                        }
                    }
                } catch (RuntimeException ignored) {
                }
            }
            return "ffprobe";
        }

        @Override
        public ProcessBuilder createProcess(String executable, List<String> arguments) {
            List<String> command = new ArrayList<String>();
            command.add(executable);
            if (arguments != null) {
                command.addAll(arguments);
            }
            return new ProcessBuilder(command);
        }
    }
}
