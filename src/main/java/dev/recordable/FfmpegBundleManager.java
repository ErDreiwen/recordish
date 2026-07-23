package dev.recordable;

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
    private static volatile String lastError;
    private static final Map<String, Boolean> ENCODER_PREFLIGHT =
        new HashMap<String, Boolean>();

    private FfmpegBundleManager() {
    }

    public static Path getBundleDirectory() {
        return Minecraft.getMinecraft().mcDataDir.toPath()
            .resolve("recordable").resolve("ffmpeg").resolve("bin");
    }

    public static synchronized FfmpegStatus detectFfmpeg() {
        RecordableConfig config = RecordableConfig.get();
        List<String> candidates = new ArrayList<String>();
        addCandidate(candidates, config.ffmpegPath);
        addCandidate(candidates, System.getenv("RECORDABLE_FFMPEG_PATH"));
        if (config.useBundledFfmpeg) {
            addCandidate(candidates, config.bundledFfmpegPath);
            addCandidate(
                    candidates,
                    getBundleDirectory()
                            .resolve(PlatformUtils.executableName("ffmpeg"))
                            .toString());
        }
        addCandidate(candidates, "ffmpeg");

        if (cachedStatus != null
                && cachedStatus.isFound()
                && candidates.contains(cachedStatus.getExecutable())) {
            return cachedStatus;
        }
        if (cachedStatus != null && cachedStatus.isFound()) {
            cachedStatus = null;
            ENCODER_PREFLIGHT.clear();
        }
        STATUS.set(Status.CHECKING);

        StringBuilder failures = new StringBuilder();
        for (String candidate : candidates) {
            FfmpegStatus result = probe(candidate);
            if (result.isFound()) {
                cachedStatus = result;
                STATUS.set(Status.AVAILABLE);
                if (!"ffmpeg".equals(candidate)) {
                    config.bundledFfmpegPath = candidate;
                }
                return result;
            }
            if (result.getError() != null) {
                if (failures.length() > 0) failures.append("; ");
                failures.append(candidate).append(": ").append(result.getError());
            }
        }

        lastError = failures.length() == 0 ? "FFmpeg was not found." : failures.toString();
        cachedStatus = new FfmpegStatus(false, null, null, lastError);
        STATUS.set(Status.NOT_FOUND);
        return cachedStatus;
    }

    public static synchronized void invalidateCache() {
        cachedStatus = null;
        ENCODER_PREFLIGHT.clear();
        if (!DOWNLOADING.get()) STATUS.set(Status.NOT_FOUND);
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
        FfmpegStatus status = detectFfmpeg();
        if (!status.isFound()) return Collections.emptyList();
        ProcessResult result = runCommand(asList(status.getExecutable(), "-hide_banner", "-encoders"), 10);
        if (result.exitCode != 0) return Collections.emptyList();
        List<String> available = new ArrayList<String>();
        for (String encoder : new String[]{"libx264", "h264_nvenc", "h264_amf", "h264_qsv", "libvpx-vp9"}) {
            if (result.output.contains(encoder)) available.add(encoder);
        }
        return available;
    }

    public static boolean supportsEncoder(String encoder) {
        return queryEncoders().contains(encoder);
    }

    /**
     * Verifies that a listed encoder can initialize on this machine. FFmpeg
     * often advertises NVENC/AMF/QSV even when the corresponding GPU or driver
     * is unavailable.
     */
    public static synchronized boolean isEncoderUsable(
            String encoder) {
        FfmpegStatus status = detectFfmpeg();
        if (!status.isFound()
                || encoder == null
                || encoder.trim().isEmpty()
                || !supportsEncoder(encoder)) {
            return false;
        }
        String key = status.getExecutable() + "\n" + encoder;
        Boolean cached = ENCODER_PREFLIGHT.get(key);
        if (cached != null) return cached.booleanValue();

        ProcessResult result = runCommand(asList(
            status.getExecutable(),
            "-nostdin",
            "-hide_banner",
            "-loglevel", "error",
            "-f", "lavfi",
            "-i", "color=c=black:s=64x64:r=1",
            "-frames:v", "1",
            "-c:v", encoder,
            "-f", "null",
            "-"), 15);
        boolean usable = result.exitCode == 0;
        ENCODER_PREFLIGHT.put(key, Boolean.valueOf(usable));
        if (!usable) {
            RecordableMod.LOGGER.warn(
                "FFmpeg encoder {} is listed but failed its device preflight: {}",
                encoder,
                firstLine(result.output));
        }
        return usable;
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
        return platform == PlatformUtils.Platform.WINDOWS
            || platform == PlatformUtils.Platform.LINUX
            || platform == PlatformUtils.Platform.MACOS;
    }

    public static String getDownloadSourceDescription() {
        switch (PlatformUtils.detectPlatform()) {
            case WINDOWS:
                return "gyan.dev FFmpeg release essentials";
            case LINUX:
                return "johnvansickle.com static FFmpeg";
            case MACOS:
                return "evermeet.cx static FFmpeg";
            default:
                return "unsupported platform";
        }
    }

    public static CompletableFuture<Boolean> downloadAsync() {
        if (!isAutoDownloadSupported()) {
            lastError = "Automatic FFmpeg download is not supported on this operating system.";
            STATUS.set(Status.ERROR);
            return CompletableFuture.completedFuture(false);
        }
        if (!DOWNLOADING.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(false);
        }
        STATUS.set(Status.DOWNLOADING);
        lastError = null;
        fireProgress("starting", 0L, 0L);
        return CompletableFuture.supplyAsync(() -> {
            try {
                installForCurrentPlatform();
                invalidateCache();
                FfmpegStatus detected = detectFfmpeg();
                if (!detected.isFound()) {
                    throw new IOException("FFmpeg was extracted but could not be executed.");
                }
                fireProgress("done", 1L, 1L);
                STATUS.set(Status.AVAILABLE);
                return true;
            } catch (Exception exception) {
                lastError = exception.getMessage() == null
                    ? exception.getClass().getSimpleName() : exception.getMessage();
                STATUS.set(Status.ERROR);
                fireProgress("error", 0L, 0L);
                RecordableMod.LOGGER.error("FFmpeg download failed.", exception);
                return false;
            } finally {
                DOWNLOADING.set(false);
            }
        });
    }

    private static void installForCurrentPlatform() throws IOException {
        Path destination = getBundleDirectory();
        Files.createDirectories(destination);
        Path temporary = Files.createTempDirectory("recordable-ffmpeg-");
        try {
            PlatformUtils.Platform platform = PlatformUtils.detectPlatform();
            if (platform == PlatformUtils.Platform.WINDOWS) {
                Path archive = temporary.resolve("ffmpeg.zip");
                downloadFile(WINDOWS_URL, archive, "downloading");
                verifyRemoteHash(archive, WINDOWS_HASH_URL, "SHA-256");
                extractZipBinaries(archive, destination);
            } else if (platform == PlatformUtils.Platform.MACOS) {
                Path archive = temporary.resolve("ffmpeg.zip");
                downloadFile(MAC_URL, archive, "downloading");
                extractZipBinaries(archive, destination);
                markExecutables(destination);
            } else if (platform == PlatformUtils.Platform.LINUX) {
                Path archive = temporary.resolve("ffmpeg.tar.xz");
                downloadFile(LINUX_URL, archive, "downloading");
                verifyRemoteHash(archive, LINUX_HASH_URL, "MD5");
                Path extracted = temporary.resolve("extracted");
                Files.createDirectories(extracted);
                ProcessResult result = runCommand(asList(
                    "tar", "-xJf", archive.toString(), "-C", extracted.toString()), 120);
                if (result.exitCode != 0) {
                    throw new IOException("Unable to extract FFmpeg: " + result.output);
                }
                copyFoundBinary(extracted, destination, "ffmpeg");
                copyFoundBinary(extracted, destination, "ffprobe");
                markExecutables(destination);
            } else {
                throw new IOException("Unsupported operating system.");
            }
        } finally {
            deleteRecursively(temporary);
        }
    }

    private static void downloadFile(String source, Path destination, String phase) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(source).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT);
        connection.setReadTimeout(READ_TIMEOUT);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "Record-able-Forge-1.8.9/1.0");
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new IOException("Download returned HTTP " + code + " from " + source);
        }
        long total = connection.getContentLengthLong();
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
                fireProgress(phase, count, total);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static void verifyRemoteHash(Path archive, String hashUrl, String algorithm) throws IOException {
        String expected;
        try {
            expected = downloadSmallText(hashUrl);
        } catch (IOException exception) {
            RecordableMod.LOGGER.warn("Unable to retrieve FFmpeg checksum; HTTPS validation remains active.");
            return;
        }
        int length = "MD5".equalsIgnoreCase(algorithm) ? 32 : 64;
        java.util.regex.Matcher matcher =
            PatternHolder.hex(length).matcher(expected.toLowerCase(Locale.ROOT));
        if (!matcher.find()) {
            RecordableMod.LOGGER.warn("FFmpeg checksum response did not contain a usable {} hash.", algorithm);
            return;
        }
        String actual = computeHash(archive, algorithm);
        if (!matcher.group().equalsIgnoreCase(actual)) {
            throw new IOException("FFmpeg " + algorithm + " checksum mismatch.");
        }
    }

    private static String downloadSmallText(String source) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(source).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT);
        connection.setReadTimeout(READ_TIMEOUT);
        connection.setRequestProperty("User-Agent", "Record-able-Forge-1.8.9/1.0");
        try (InputStream input = connection.getInputStream();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) >= 0 && output.size() < 65536) {
                output.write(buffer, 0, read);
            }
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
                Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
                if (filename.toLowerCase(Locale.ROOT).startsWith("ffmpeg")) foundFfmpeg = true;
            }
        }
        if (!foundFfmpeg) throw new IOException("FFmpeg executable was not present in the archive.");
    }

    private static void copyFoundBinary(Path root, Path destination, String name) throws IOException {
        Path found = findFile(root, name);
        if (found != null) {
            Files.copy(found, destination.resolve(name), StandardCopyOption.REPLACE_EXISTING);
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
            }, "Recordable-ProcessReader");
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

    private static void fireProgress(String phase, long downloaded, long total) {
        progress = new DownloadProgress(phase, downloaded, total);
        for (ProgressListener listener : LISTENERS) {
            try {
                listener.onProgress(progress);
            } catch (RuntimeException exception) {
                RecordableMod.LOGGER.debug("FFmpeg progress listener failed.", exception);
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
            RecordableMod.LOGGER.debug("Unable to remove temporary FFmpeg directory {}", root, exception);
        }
    }

    private static final class PatternHolder {
        private static java.util.regex.Pattern hex(int length) {
            return java.util.regex.Pattern.compile("[0-9a-f]{" + length + "}");
        }
    }
}
