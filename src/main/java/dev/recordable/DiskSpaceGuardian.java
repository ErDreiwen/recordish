package dev.recordable;

import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Checks the recording volume before and during capture.
 *
 * <p>The thresholds intentionally match Record-able V1-0.08: the configured
 * percentage can block a recording, less than 100 MB always blocks, and the
 * configured minimum-free value produces a warning.</p>
 */
public final class DiskSpaceGuardian {
    private static final long BYTES_PER_MEBIBYTE = 1024L * 1024L;
    private static final long ABSOLUTE_BLOCK_FREE_MB = 100L;
    private static final Logger LOGGER = Logger.getLogger("Record-able");

    public enum DiskStatus {
        OK,
        WARNING,
        BLOCKED
    }

    /**
     * Java 8 replacement for the upstream record. Record-style and bean-style
     * accessors are both provided so UI code can use either convention.
     */
    public static final class DiskCheckResult {
        private final DiskStatus status;
        private final long freeSpaceMB;
        private final long totalSpaceMB;
        private final int usedPercent;
        private final String message;

        public DiskCheckResult(DiskStatus status, long freeSpaceMB, long totalSpaceMB,
                               int usedPercent, String message) {
            this.status = status == null ? DiskStatus.OK : status;
            this.freeSpaceMB = freeSpaceMB;
            this.totalSpaceMB = totalSpaceMB;
            this.usedPercent = usedPercent;
            this.message = message == null ? "" : message;
        }

        public DiskStatus status() {
            return status;
        }

        public long freeSpaceMB() {
            return freeSpaceMB;
        }

        public long totalSpaceMB() {
            return totalSpaceMB;
        }

        public int usedPercent() {
            return usedPercent;
        }

        public String message() {
            return message;
        }

        public DiskStatus getStatus() {
            return status;
        }

        public long getFreeSpaceMB() {
            return freeSpaceMB;
        }

        public long getTotalSpaceMB() {
            return totalSpaceMB;
        }

        public int getUsedPercent() {
            return usedPercent;
        }

        public String getMessage() {
            return message;
        }

        public boolean isBlocked() {
            return status == DiskStatus.BLOCKED;
        }

        public boolean isWarning() {
            return status == DiskStatus.WARNING;
        }
    }

    private DiskSpaceGuardian() {
    }

    /**
     * Checks the volume containing {@code outputDir}. Failure to query a volume
     * is deliberately non-blocking, matching upstream behavior.
     */
    public static DiskCheckResult check(Path outputDir, RecordableConfig config) {
        if (outputDir == null) {
            return unknown("Could not check disk space: output directory is unavailable.");
        }

        RecordableConfig effectiveConfig = config == null ? RecordableConfig.get() : config;
        try {
            Files.createDirectories(outputDir);
            FileStore store = Files.getFileStore(outputDir);
            long totalBytes = store.getTotalSpace();
            long freeBytes = store.getUsableSpace();
            if (totalBytes <= 0L) {
                return unknown("Could not determine disk space.");
            }

            long totalMB = totalBytes / BYTES_PER_MEBIBYTE;
            long freeMB = Math.max(0L, freeBytes / BYTES_PER_MEBIBYTE);
            int usedPercent = clampPercent(100L - (freeBytes * 100L / totalBytes));

            if (usedPercent >= effectiveConfig.diskSpaceBlockPercent
                    || freeMB < ABSOLUTE_BLOCK_FREE_MB) {
                return new DiskCheckResult(
                        DiskStatus.BLOCKED,
                        freeMB,
                        totalMB,
                        usedPercent,
                        "\u00a7c\u26d4 Disk is " + usedPercent + "% full (" + freeMB
                                + " MB free). Recording blocked to prevent disk full errors.");
            }

            if (usedPercent >= effectiveConfig.diskSpaceWarnPercent
                    || freeMB < effectiveConfig.diskSpaceMinFreeMB) {
                return new DiskCheckResult(
                        DiskStatus.WARNING,
                        freeMB,
                        totalMB,
                        usedPercent,
                        "\u00a7e\u26a0 Disk is " + usedPercent + "% full (" + freeMB
                                + " MB free). Recording may be cut short.");
            }

            return new DiskCheckResult(
                    DiskStatus.OK,
                    freeMB,
                    totalMB,
                    usedPercent,
                    "Disk space OK: " + freeMB + " MB free (" + usedPercent + "% used).");
        } catch (Exception exception) {
            LOGGER.log(Level.WARNING, "Failed to check disk space for " + outputDir, exception);
            String detail = exception.getMessage();
            return unknown("Could not check disk space"
                    + (isBlank(detail) ? "." : ": " + detail));
        }
    }

    /**
     * Returns usable space in MiB, or {@code -1} when the volume cannot be
     * queried.
     */
    public static long getFreeSpaceMB(Path path) {
        if (path == null) {
            return -1L;
        }
        try {
            Files.createDirectories(path);
            return Files.getFileStore(path).getUsableSpace() / BYTES_PER_MEBIBYTE;
        } catch (Exception exception) {
            LOGGER.log(Level.FINE, "Could not get free space for " + path, exception);
            return -1L;
        }
    }

    public static String getFormattedFreeSpace(Path path) {
        long freeMB = getFreeSpaceMB(path);
        if (freeMB < 0L) {
            return "Unknown";
        }
        if (freeMB >= 1024L) {
            return String.format(Locale.ROOT, "%.1f GB", freeMB / 1024.0D);
        }
        return freeMB + " MB";
    }

    private static DiskCheckResult unknown(String message) {
        return new DiskCheckResult(DiskStatus.OK, -1L, -1L, 0, message);
    }

    private static int clampPercent(long value) {
        return (int) Math.max(0L, Math.min(100L, value));
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
