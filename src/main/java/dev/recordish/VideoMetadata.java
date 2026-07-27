package dev.recordish;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight gallery metadata with cached FFprobe/FFmpeg extraction.
 */
public final class VideoMetadata {
    private static final DateTimeFormatter FILE_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter DISPLAY_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Pattern FILENAME_TIMESTAMP =
            Pattern.compile("(\\d{8}-\\d{6})");
    private static final Pattern DURATION_PATTERN = Pattern.compile(
            "Duration:\\s*(\\d+):(\\d+):(\\d+(?:\\.\\d+)?)");
    private static final Pattern TIME_PATTERN = Pattern.compile(
            "time=\\s*(\\d+):(\\d+):(\\d+(?:\\.\\d+)?)");
    private static final Logger LOGGER = Logger.getLogger("Recordish");

    private static final Object CACHE_LOCK = new Object();
    private static final Map<Path, CacheEntry> CACHE =
            new HashMap<Path, CacheEntry>();
    private static volatile ProbeStatus cachedProbeStatus;

    public final Path file;
    public final String filename;
    public final long sizeBytes;
    public final String sizeDisplay;
    public final String durationDisplay;
    public final double durationSeconds;
    public final String recordedAtDisplay;
    public final long modifiedMillis;
    public final Path thumbnailPath;

    private VideoMetadata(
            Path file,
            long sizeBytes,
            String durationDisplay,
            double durationSeconds,
            String recordedAtDisplay,
            long modifiedMillis,
            Path thumbnailPath) {
        this.file = file;
        this.filename = file == null || file.getFileName() == null
                ? "?"
                : file.getFileName().toString();
        this.sizeBytes = Math.max(0L, sizeBytes);
        this.sizeDisplay = String.format(
                Locale.ROOT,
                "%.2f MB",
                this.sizeBytes / (1024.0D * 1024.0D));
        this.durationDisplay = durationDisplay == null ? "?" : durationDisplay;
        this.durationSeconds = durationSeconds;
        this.recordedAtDisplay = recordedAtDisplay == null ? "?" : recordedAtDisplay;
        this.modifiedMillis = Math.max(0L, modifiedMillis);
        this.thumbnailPath = thumbnailPath;
    }

    public Path getFile() {
        return file;
    }

    public String getFilename() {
        return filename;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getSizeDisplay() {
        return sizeDisplay;
    }

    public String getDurationDisplay() {
        return durationDisplay;
    }

    public double getDurationSeconds() {
        return durationSeconds;
    }

    public String getRecordedAtDisplay() {
        return recordedAtDisplay;
    }

    public long getModifiedMillis() {
        return modifiedMillis;
    }

    public Path getThumbnailPath() {
        return thumbnailPath;
    }

    /**
     * Performs the full metadata read, including duration probing and thumbnail
     * extraction.
     */
    public static VideoMetadata read(Path videoFile) {
        if (videoFile == null) {
            return unknownMetadata();
        }

        try {
            Path normalized = videoFile.toAbsolutePath().normalize();
            long size = safeSize(normalized);
            long modified = safeModifiedMillis(normalized);
            VideoMetadata cached = cached(normalized, size, modified);
            if (cached != null) {
                return cached;
            }

            double duration = probeDurationSeconds(normalized);
            VideoMetadata metadata = new VideoMetadata(
                    normalized,
                    size,
                    duration <= 0.0D ? "?" : formatDuration(duration),
                    duration,
                    resolveRecordedAt(normalized, modified),
                    modified,
                    ensureThumbnail(normalized, modified));
            cache(normalized, size, modified, metadata);
            return metadata;
        } catch (Throwable throwable) {
            LOGGER.log(Level.WARNING, "Failed to read video metadata for " + videoFile, throwable);
            return new VideoMetadata(
                    videoFile,
                    safeSize(videoFile),
                    "?",
                    -1.0D,
                    "?",
                    safeModifiedMillis(videoFile),
                    null);
        }
    }

    /**
     * Reads size/date/thumbnail without waiting for a duration probe. A complete
     * cached result is returned when one already exists.
     */
    public static VideoMetadata readQuick(Path videoFile) {
        if (videoFile == null) {
            return unknownMetadata();
        }

        try {
            Path normalized = videoFile.toAbsolutePath().normalize();
            long size = safeSize(normalized);
            long modified = safeModifiedMillis(normalized);
            VideoMetadata cached = cached(normalized, size, modified);
            if (cached != null) {
                return cached;
            }

            return new VideoMetadata(
                    normalized,
                    size,
                    "...",
                    -1.0D,
                    resolveRecordedAt(normalized, modified),
                    modified,
                    ensureThumbnail(normalized, modified));
        } catch (Throwable throwable) {
            LOGGER.log(Level.FINE, "Failed to quick-read metadata for " + videoFile, throwable);
            return new VideoMetadata(
                    videoFile,
                    safeSize(videoFile),
                    "?",
                    -1.0D,
                    "?",
                    safeModifiedMillis(videoFile),
                    null);
        }
    }

    /** Probes and caches duration for one gallery entry. */
    public static VideoMetadata probeDurationFor(Path videoFile) {
        if (videoFile == null) {
            return null;
        }

        try {
            Path normalized = videoFile.toAbsolutePath().normalize();
            long size = safeSize(normalized);
            long modified = safeModifiedMillis(normalized);
            VideoMetadata cached = cached(normalized, size, modified);
            if (cached != null && cached.durationSeconds > 0.0D) {
                return cached;
            }

            double duration = probeDurationSeconds(normalized);
            VideoMetadata metadata = new VideoMetadata(
                    normalized,
                    size,
                    duration <= 0.0D ? "?" : formatDuration(duration),
                    duration,
                    resolveRecordedAt(normalized, modified),
                    modified,
                    ensureThumbnail(normalized, modified));
            cache(normalized, size, modified, metadata);
            return metadata;
        } catch (Throwable throwable) {
            LOGGER.log(Level.FINE, "Duration probe failed for " + videoFile, throwable);
            return null;
        }
    }

    public static void clearCache() {
        synchronized (CACHE_LOCK) {
            CACHE.clear();
        }
    }

    public static void clearProbeCache() {
        cachedProbeStatus = null;
    }

    public static boolean isFfprobeAvailable() {
        return detectFfprobe().available;
    }

    /**
     * Returns the comparator used by V1-0.08's gallery sort modes.
     */
    public static Comparator<VideoMetadata> comparatorFor(String sortMode) {
        String mode = sortMode == null
                ? "newest"
                : sortMode.trim().toLowerCase(Locale.ROOT);

        if ("oldest".equals(mode)) {
            return byModified(false);
        }
        if ("name_az".equals(mode)) {
            return byName(false);
        }
        if ("name_za".equals(mode)) {
            return byName(true);
        }
        if ("largest".equals(mode)) {
            return bySize(true);
        }
        if ("smallest".equals(mode)) {
            return bySize(false);
        }
        if ("longest".equals(mode)) {
            return byDuration(true);
        }
        if ("shortest".equals(mode)) {
            return byDuration(false);
        }
        return byModified(true);
    }

    public static void sort(List<VideoMetadata> metadata, String sortMode) {
        if (metadata != null) {
            Collections.sort(metadata, comparatorFor(sortMode));
        }
    }

    private static Comparator<VideoMetadata> byModified(final boolean reversed) {
        return new Comparator<VideoMetadata>() {
            @Override
            public int compare(VideoMetadata left, VideoMetadata right) {
                int comparison = Long.compare(
                        value(right, Field.MODIFIED),
                        value(left, Field.MODIFIED));
                return reversed ? comparison : -comparison;
            }
        };
    }

    private static Comparator<VideoMetadata> bySize(final boolean reversed) {
        return new Comparator<VideoMetadata>() {
            @Override
            public int compare(VideoMetadata left, VideoMetadata right) {
                int comparison = Long.compare(
                        value(right, Field.SIZE),
                        value(left, Field.SIZE));
                return reversed ? comparison : -comparison;
            }
        };
    }

    private static Comparator<VideoMetadata> byDuration(final boolean reversed) {
        return new Comparator<VideoMetadata>() {
            @Override
            public int compare(VideoMetadata left, VideoMetadata right) {
                double leftValue = left == null ? -1.0D : left.durationSeconds;
                double rightValue = right == null ? -1.0D : right.durationSeconds;
                int comparison = Double.compare(rightValue, leftValue);
                return reversed ? comparison : -comparison;
            }
        };
    }

    private static Comparator<VideoMetadata> byName(final boolean reversed) {
        return new Comparator<VideoMetadata>() {
            @Override
            public int compare(VideoMetadata left, VideoMetadata right) {
                String leftName = left == null || left.filename == null ? "" : left.filename;
                String rightName = right == null || right.filename == null ? "" : right.filename;
                int comparison = String.CASE_INSENSITIVE_ORDER.compare(leftName, rightName);
                return reversed ? -comparison : comparison;
            }
        };
    }

    private static long value(VideoMetadata metadata, Field field) {
        if (metadata == null) {
            return 0L;
        }
        return field == Field.SIZE ? metadata.sizeBytes : metadata.modifiedMillis;
    }

    private static double probeDurationSeconds(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return -1.0D;
        }

        ProbeStatus probe = detectFfprobe();
        if (probe.available) {
            double duration = runFfprobeDuration(probe.executable, file);
            if (duration > 0.0D) {
                return duration;
            }
        }

        double headerDuration = runFfmpegHeaderDuration(file);
        if (headerDuration > 0.0D) {
            return headerDuration;
        }
        return runFfmpegDecodeDuration(file);
    }

    private static double runFfprobeDuration(String executable, Path file) {
        try {
            List<String> arguments = new ArrayList<String>();
            Collections.addAll(
                    arguments,
                    "-nostdin",
                    "-v", "error",
                    "-select_streams", "v:0",
                    "-show_entries", "format=duration:stream=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1",
                    file.toAbsolutePath().toString());
            StorageManager.ProcessResult result = StorageManager.runProcess(
                    StorageManager.createMediaProcess(executable, arguments),
                    10L,
                    TimeUnit.SECONDS);
            if (result.timedOut()) {
                return -1.0D;
            }

            String[] candidates = result.output().split("\\r?\\n");
            for (String candidate : candidates) {
                try {
                    double parsed = Double.parseDouble(candidate.trim());
                    if (parsed > 0.0D) {
                        return parsed;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (Throwable throwable) {
            LOGGER.log(Level.FINE, "FFprobe duration failed for " + file, throwable);
        }
        return -1.0D;
    }

    private static double runFfmpegHeaderDuration(Path file) {
        String ffmpeg = StorageManager.resolveFfmpegExecutable(RecordishConfig.get());
        if (isBlank(ffmpeg)) {
            return -1.0D;
        }

        try {
            List<String> arguments = new ArrayList<String>();
            Collections.addAll(
                    arguments,
                    "-nostdin",
                    "-hide_banner",
                    "-i", file.toAbsolutePath().toString());
            StorageManager.ProcessResult result = StorageManager.runProcess(
                    StorageManager.createMediaProcess(ffmpeg, arguments),
                    10L,
                    TimeUnit.SECONDS);
            Matcher matcher = DURATION_PATTERN.matcher(result.output());
            if (matcher.find()) {
                return seconds(
                        matcher.group(1),
                        matcher.group(2),
                        matcher.group(3));
            }
        } catch (Throwable throwable) {
            LOGGER.log(Level.FINE, "FFmpeg header duration failed for " + file, throwable);
        }
        return -1.0D;
    }

    private static double runFfmpegDecodeDuration(Path file) {
        String ffmpeg = StorageManager.resolveFfmpegExecutable(RecordishConfig.get());
        if (isBlank(ffmpeg)) {
            return -1.0D;
        }

        try {
            List<String> arguments = new ArrayList<String>();
            Collections.addAll(
                    arguments,
                    "-nostdin",
                    "-hide_banner",
                    "-i", file.toAbsolutePath().toString(),
                    "-f", "null",
                    "-");
            StorageManager.ProcessResult result = StorageManager.runProcess(
                    StorageManager.createMediaProcess(ffmpeg, arguments),
                    60L,
                    TimeUnit.SECONDS);

            double latest = -1.0D;
            Matcher matcher = TIME_PATTERN.matcher(result.output());
            while (matcher.find()) {
                double candidate = seconds(
                        matcher.group(1),
                        matcher.group(2),
                        matcher.group(3));
                if (candidate > latest) {
                    latest = candidate;
                }
            }
            return latest;
        } catch (Throwable throwable) {
            LOGGER.log(Level.FINE, "FFmpeg decode duration failed for " + file, throwable);
            return -1.0D;
        }
    }

    private static ProbeStatus detectFfprobe() {
        ProbeStatus cached = cachedProbeStatus;
        if (cached != null) {
            return cached;
        }

        String executable;
        try {
            executable = StorageManager.resolveFfprobeExecutable(RecordishConfig.get());
        } catch (Throwable throwable) {
            executable = "ffprobe";
        }

        ProbeStatus detected = probeExecutable(executable);
        cachedProbeStatus = detected;
        return detected;
    }

    private static ProbeStatus probeExecutable(String executable) {
        if (isBlank(executable)) {
            return new ProbeStatus(false, executable);
        }
        try {
            List<String> arguments = new ArrayList<String>();
            arguments.add("-version");
            StorageManager.ProcessResult result = StorageManager.runProcess(
                    StorageManager.createMediaProcess(executable, arguments),
                    5L,
                    TimeUnit.SECONDS);
            return new ProbeStatus(result.succeeded(), executable);
        } catch (Throwable throwable) {
            LOGGER.log(Level.FINE, "FFprobe executable unavailable: " + executable, throwable);
            return new ProbeStatus(false, executable);
        }
    }

    private static Path ensureThumbnail(Path videoFile, long modifiedMillis) {
        if (videoFile == null || !Files.isRegularFile(videoFile)) {
            return null;
        }

        RecordishConfig config;
        try {
            config = RecordishConfig.get();
        } catch (Throwable throwable) {
            return null;
        }
        String ffmpeg = StorageManager.resolveFfmpegExecutable(config);
        if (isBlank(ffmpeg)) {
            return null;
        }

        Path thumbnailDirectory = RecordishPaths.preferredDirectory(
                config.getOutputDirectory(),
                ".recordish-thumbnails",
                ".recordable-thumbnails");
        Path thumbnail = thumbnailDirectory.resolve(
                sha1(videoFile.toAbsolutePath().normalize().toString()
                        + ":"
                        + modifiedMillis)
                        + ".png");

        try {
            Files.createDirectories(thumbnailDirectory);
            if (!Files.isWritable(thumbnailDirectory)) {
                return null;
            }
            if (Files.isRegularFile(thumbnail) && safeSize(thumbnail) > 0L) {
                return thumbnail;
            }

            List<String> arguments = new ArrayList<String>();
            Collections.addAll(
                    arguments,
                    "-nostdin",
                    "-hide_banner",
                    "-loglevel", "error",
                    "-y",
                    "-i", videoFile.toAbsolutePath().toString(),
                    "-ss", "00:00:00.500",
                    "-frames:v", "1",
                    "-vf", "thumbnail,scale=192:-1",
                    thumbnail.toAbsolutePath().toString());
            StorageManager.ProcessResult result = StorageManager.runProcess(
                    StorageManager.createMediaProcess(ffmpeg, arguments),
                    4L,
                    TimeUnit.SECONDS);
            if (result.succeeded()
                    && Files.isRegularFile(thumbnail)
                    && safeSize(thumbnail) > 0L) {
                return thumbnail;
            }
            Files.deleteIfExists(thumbnail);
        } catch (Throwable throwable) {
            LOGGER.log(Level.FINE, "Thumbnail extraction failed for " + videoFile, throwable);
        }
        return null;
    }

    private static String resolveRecordedAt(Path videoFile, long modifiedMillis) {
        try {
            String name = videoFile == null || videoFile.getFileName() == null
                    ? ""
                    : videoFile.getFileName().toString();
            Matcher matcher = FILENAME_TIMESTAMP.matcher(name);
            if (matcher.find()) {
                LocalDateTime parsed = LocalDateTime.parse(
                        matcher.group(1),
                        FILE_TIMESTAMP);
                return DISPLAY_TIMESTAMP.format(parsed);
            }
        } catch (Throwable ignored) {
        }

        Instant instant = Instant.ofEpochMilli(Math.max(0L, modifiedMillis));
        return DISPLAY_TIMESTAMP.format(
                LocalDateTime.ofInstant(instant, ZoneId.systemDefault()));
    }

    public static String formatDuration(double durationSeconds) {
        long roundedSeconds = Math.max(0L, Math.round(durationSeconds));
        long hours = roundedSeconds / 3600L;
        long minutes = (roundedSeconds % 3600L) / 60L;
        long seconds = roundedSeconds % 60L;
        if (hours > 0L) {
            return String.format(
                    Locale.ROOT,
                    "%d:%02d:%02d",
                    hours,
                    minutes,
                    seconds);
        }
        return String.format(Locale.ROOT, "%02d:%02d", minutes, seconds);
    }

    private static double seconds(String hours, String minutes, String seconds) {
        try {
            return Integer.parseInt(hours) * 3600.0D
                    + Integer.parseInt(minutes) * 60.0D
                    + Double.parseDouble(seconds);
        } catch (NumberFormatException exception) {
            return -1.0D;
        }
    }

    private static VideoMetadata cached(Path path, long size, long modified) {
        synchronized (CACHE_LOCK) {
            CacheEntry entry = CACHE.get(path);
            if (entry != null
                    && entry.sizeBytes == size
                    && entry.modifiedMillis == modified) {
                return entry.metadata;
            }
            return null;
        }
    }

    private static void cache(
            Path path,
            long size,
            long modified,
            VideoMetadata metadata) {
        synchronized (CACHE_LOCK) {
            CACHE.put(path, new CacheEntry(size, modified, metadata));
        }
    }

    private static VideoMetadata unknownMetadata() {
        return new VideoMetadata(
                Paths.get("?"),
                0L,
                "?",
                -1.0D,
                "?",
                0L,
                null);
    }

    private static long safeSize(Path path) {
        if (path == null) {
            return 0L;
        }
        try {
            return Files.exists(path) ? Files.size(path) : 0L;
        } catch (IOException exception) {
            return 0L;
        }
    }

    private static long safeModifiedMillis(Path path) {
        if (path == null) {
            return 0L;
        }
        try {
            FileTime modified = Files.getLastModifiedTime(path);
            return modified.toMillis();
        } catch (IOException exception) {
            return 0L;
        }
    }

    private static String sha1(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte valueByte : hash) {
                builder.append(String.format(
                        Locale.ROOT,
                        "%02x",
                        valueByte & 0xFF));
            }
            return builder.toString();
        } catch (Throwable throwable) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private enum Field {
        MODIFIED,
        SIZE
    }

    private static final class CacheEntry {
        private final long sizeBytes;
        private final long modifiedMillis;
        private final VideoMetadata metadata;

        private CacheEntry(long sizeBytes, long modifiedMillis, VideoMetadata metadata) {
            this.sizeBytes = sizeBytes;
            this.modifiedMillis = modifiedMillis;
            this.metadata = metadata;
        }
    }

    private static final class ProbeStatus {
        private final boolean available;
        private final String executable;

        private ProbeStatus(boolean available, String executable) {
            this.available = available;
            this.executable = executable;
        }
    }
}
