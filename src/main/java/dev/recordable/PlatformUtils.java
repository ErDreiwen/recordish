package dev.recordable;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PlatformUtils {
    private static final Pattern DSHOW_AUDIO_DEVICE = Pattern.compile(
            "\"([^\"]+)\"\\s+\\(audio\\)",
            Pattern.CASE_INSENSITIVE);

    public enum Platform {
        WINDOWS("Windows"), LINUX("Linux"), MACOS("macOS"), UNKNOWN("Unknown");

        private final String displayName;

        Platform(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    private PlatformUtils() {
    }

    public static Platform detectPlatform() {
        String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (name.contains("win")) return Platform.WINDOWS;
        if (name.contains("mac") || name.contains("darwin")) return Platform.MACOS;
        if (name.contains("nux") || name.contains("nix") || name.contains("aix")) return Platform.LINUX;
        return Platform.UNKNOWN;
    }

    public static boolean isWindows() {
        return detectPlatform() == Platform.WINDOWS;
    }

    public static boolean isLinux() {
        return detectPlatform() == Platform.LINUX;
    }

    public static boolean isMacOS() {
        return detectPlatform() == Platform.MACOS;
    }

    public static String getPlatformId() {
        if (isWindows()) return "windows";
        if (isLinux()) return "linux";
        if (isMacOS()) return "macos";
        return "unknown";
    }

    public static String getAudioMethodDescription() {
        if (isWindows()) return "DirectShow (Stereo Mix)";
        if (isLinux()) return "JavaSound (monitor source)";
        if (isMacOS()) return "JavaSound (loopback device)";
        return "Unknown";
    }

    /**
     * Resolves only a Windows capture endpoint that plausibly represents
     * system output. DirectShow exposes recording endpoints, not arbitrary
     * speaker devices, so selecting the first microphone would silently put
     * the wrong audio in a recording.
     */
    static String findWindowsSystemAudioDevice(
            String ffmpegExecutable,
            String configuredDevice) {
        if (!isWindows() || isBlank(ffmpegExecutable)) {
            return null;
        }
        List<String> devices =
                listWindowsDirectShowAudioDevices(ffmpegExecutable);
        String configured = configuredDevice == null
                ? ""
                : configuredDevice.trim();
        boolean automatic = configured.isEmpty()
                || "auto".equalsIgnoreCase(configured)
                || "default".equalsIgnoreCase(configured)
                || "openal".equalsIgnoreCase(configured);

        if (!automatic) {
            if (!isLoopbackAudioDeviceName(configured)) {
                RecordableMod.LOGGER.warn(
                        "Configured game-audio device '{}' does not look like "
                                + "a DirectShow loopback endpoint; not using it "
                                + "as system audio.",
                        configured);
                return null;
            }
            for (String device : devices) {
                if (device.equalsIgnoreCase(configured)) {
                    return device;
                }
            }
            RecordableMod.LOGGER.warn(
                    "Configured DirectShow loopback endpoint '{}' was not "
                            + "reported by FFmpeg.",
                    configured);
            return null;
        }

        String[] priorities = {
                "stereo mix",
                "stereomix",
                "what u hear",
                "what you hear",
                "wave out mix",
                "waveout mix",
                "rec. playback",
                "loopback",
                "monitor of",
                ".monitor",
                "cable output",
                "virtual cable output",
                "voicemeeter output",
                "voicemeeter aux output"
        };
        for (String priority : priorities) {
            for (String device : devices) {
                if (device.toLowerCase(Locale.ROOT).contains(priority)) {
                    return device;
                }
            }
        }
        RecordableMod.LOGGER.info(
                "DirectShow reported {} audio capture device(s), but none "
                        + "was a recognizable system-loopback endpoint.",
                Integer.valueOf(devices.size()));
        return null;
    }

    static List<String> listWindowsDirectShowAudioDevices(
            String ffmpegExecutable) {
        if (!isWindows() || isBlank(ffmpegExecutable)) {
            return Collections.emptyList();
        }
        List<String> arguments = new ArrayList<String>();
        Collections.addAll(
                arguments,
                "-hide_banner",
                "-list_devices", "true",
                "-f", "dshow",
                "-i", "dummy");
        try {
            StorageManager.ProcessResult result =
                    StorageManager.runProcess(
                            StorageManager.createMediaProcess(
                                    ffmpegExecutable,
                                    arguments),
                            8L,
                            TimeUnit.SECONDS);
            if (result.timedOut()) {
                RecordableMod.LOGGER.warn(
                        "Timed out while listing DirectShow audio devices.");
                return Collections.emptyList();
            }
            List<String> devices = new ArrayList<String>();
            Matcher matcher = DSHOW_AUDIO_DEVICE.matcher(result.output());
            while (matcher.find()) {
                String candidate = matcher.group(1).trim();
                if (!candidate.isEmpty()
                        && !containsIgnoreCase(devices, candidate)) {
                    devices.add(candidate);
                }
            }
            RecordableMod.LOGGER.info(
                    "DirectShow audio capture devices: {}",
                    devices);
            return devices;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (IOException exception) {
            RecordableMod.LOGGER.warn(
                    "Unable to list DirectShow audio devices.",
                    exception);
        }
        return Collections.emptyList();
    }

    static boolean isLoopbackAudioDeviceName(String value) {
        String name = value == null
                ? ""
                : value.toLowerCase(Locale.ROOT);
        return name.contains("stereo mix")
                || name.contains("stereomix")
                || name.contains("what u hear")
                || name.contains("what you hear")
                || name.contains("wave out mix")
                || name.contains("waveout mix")
                || name.contains("rec. playback")
                || name.contains("loopback")
                || name.contains("monitor of")
                || name.contains("monitor source")
                || name.contains(".monitor")
                || name.contains("cable output")
                || name.contains("virtual cable output")
                || name.contains("voicemeeter output")
                || name.contains("voicemeeter aux output");
    }

    public static String executableName(String baseName) {
        return isWindows() ? baseName + ".exe" : baseName;
    }

    public static boolean open(Path path) {
        if (path == null) return false;
        File file = path.toFile();
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop desktop = Desktop.getDesktop();
                if (desktop.isSupported(Desktop.Action.OPEN)) {
                    desktop.open(file);
                    return true;
                }
            }
            Process process;
            if (isWindows()) {
                process = new ProcessBuilder("explorer.exe", file.getAbsolutePath()).start();
            } else if (isMacOS()) {
                process = new ProcessBuilder("open", file.getAbsolutePath()).start();
            } else {
                process = new ProcessBuilder("xdg-open", file.getAbsolutePath()).start();
            }
            return process != null;
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            RecordableMod.LOGGER.warn("Could not open {}", file, exception);
            return false;
        }
    }

    private static boolean containsIgnoreCase(
            List<String> values,
            String candidate) {
        for (String value : values) {
            if (value.equalsIgnoreCase(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
