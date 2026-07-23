package dev.recordable;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

public final class PlatformUtils {
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
}
