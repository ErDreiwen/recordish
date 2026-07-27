package dev.recordish;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Centralized runtime paths and compatibility fallbacks for the Recordish
 * identity migration.
 */
public final class RecordishPaths {
    private RecordishPaths() {
    }

    /**
     * Uses the new directory for fresh installs while keeping an existing
     * legacy directory visible until the user chooses to move its contents.
     */
    public static Path preferredDirectory(
            Path parent,
            String currentName,
            String legacyName) {
        return preferredDirectory(
                parent.resolve(currentName),
                parent.resolve(legacyName));
    }

    /**
     * Chooses between complete current and legacy directory paths.
     */
    public static Path preferredDirectory(
            Path current,
            Path legacy) {
        if (Files.exists(current) || !Files.exists(legacy)) {
            return current;
        }
        return legacy;
    }

    /**
     * Reads the Recordish environment variable first, then its legacy alias.
     */
    public static String firstEnvironment(
            String currentName,
            String legacyName) {
        String current = System.getenv(currentName);
        if (!isBlank(current)) {
            return current;
        }
        return System.getenv(legacyName);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
