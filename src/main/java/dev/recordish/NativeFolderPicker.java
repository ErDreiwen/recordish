package dev.recordish;

import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.util.function.Consumer;

/**
 * Desktop folder chooser used by the legacy Forge port.
 *
 * <p>The modern mod uses LWJGL 3's TinyFileDialogs binding. Minecraft 1.8.9
 * ships LWJGL 2, so this port provides the same asynchronous interaction with
 * Java's platform file chooser instead.</p>
 */
public final class NativeFolderPicker {
    private NativeFolderPicker() {
    }

    public static boolean isSupported() {
        try {
            return !GraphicsEnvironment.isHeadless();
        } catch (Throwable throwable) {
            return false;
        }
    }

    /**
     * Opens a directory-only chooser without blocking Minecraft's render
     * thread. The callback receives an absolute path, or {@code null} when the
     * chooser is cancelled or cannot be opened.
     */
    public static void pickFolder(
            final String title,
            final String defaultPath,
            final Consumer<String> onResult) {
        if (onResult == null) {
            return;
        }
        if (!isSupported()) {
            onResult.accept(null);
            return;
        }

        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                String result = null;
                try {
                    JFileChooser chooser = new JFileChooser(
                            normalizeDefaultDirectory(defaultPath));
                    chooser.setDialogTitle(title == null
                            ? "Select folder"
                            : title);
                    chooser.setFileSelectionMode(
                            JFileChooser.DIRECTORIES_ONLY);
                    chooser.setAcceptAllFileFilterUsed(false);
                    if (chooser.showOpenDialog(null)
                            == JFileChooser.APPROVE_OPTION
                            && chooser.getSelectedFile() != null) {
                        result = chooser.getSelectedFile()
                                .getAbsoluteFile()
                                .toPath()
                                .normalize()
                                .toString();
                    }
                } catch (Throwable throwable) {
                    RecordishMod.LOGGER.warn(
                            "[NativeFolderPicker] Folder dialog failed.",
                            throwable);
                }

                try {
                    onResult.accept(result);
                } catch (Throwable throwable) {
                    RecordishMod.LOGGER.warn(
                            "[NativeFolderPicker] Picker callback failed.",
                            throwable);
                }
            }
        });
    }

    private static File normalizeDefaultDirectory(String defaultPath) {
        File fallback = new File(
                System.getProperty("user.home", "."));
        if (defaultPath == null || defaultPath.trim().isEmpty()) {
            return fallback;
        }
        try {
            File candidate = new File(defaultPath.trim())
                    .getAbsoluteFile();
            if (candidate.isFile()) {
                candidate = candidate.getParentFile();
            }
            while (candidate != null && !candidate.isDirectory()) {
                candidate = candidate.getParentFile();
            }
            return candidate == null ? fallback : candidate;
        } catch (RuntimeException exception) {
            return fallback;
        }
    }
}
