package dev.recordish;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * No-network smoke coverage for the Record-able to Recordish migration.
 */
public final class IdentityMigrationSmoke {
    private IdentityMigrationSmoke() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) {
            throw new IllegalArgumentException(
                    "Expected a dedicated migration smoke directory.");
        }
        Path root = Paths.get(arguments[0]).toAbsolutePath().normalize();
        Path configDirectory = root.resolve("config");
        Files.createDirectories(configDirectory);

        Path legacyConfig = configDirectory.resolve("recordable.json");
        Files.write(
                legacyConfig,
                ("{\n"
                        + "  \"fps\": 30,\n"
                        + "  \"filenamePattern\": \"legacy-{datetime}\"\n"
                        + "}\n").getBytes(StandardCharsets.UTF_8));

        RecordishConfig.initialize(root.toFile(), configDirectory.toFile());
        RecordishConfig migrated = RecordishConfig.get();
        if (migrated.fps != 30
                || !"legacy-{datetime}".equals(migrated.filenamePattern)) {
            throw new IllegalStateException(
                    "Legacy settings were not imported.");
        }
        if (!Files.isRegularFile(configDirectory.resolve("recordish.json"))) {
            throw new IllegalStateException(
                    "Migrated Recordish config was not written.");
        }
        if (!Files.isRegularFile(legacyConfig)) {
            throw new IllegalStateException(
                    "Legacy config should remain recoverable.");
        }

        Path dataRoot = root.resolve("data");
        Path legacyData = dataRoot.resolve("recordable");
        Files.createDirectories(legacyData);
        if (!legacyData.equals(RecordishPaths.preferredDirectory(
                dataRoot,
                "recordish",
                "recordable"))) {
            throw new IllegalStateException(
                    "Existing legacy data directory was not selected.");
        }
        Path currentData = dataRoot.resolve("recordish");
        Files.createDirectories(currentData);
        if (!currentData.equals(RecordishPaths.preferredDirectory(
                dataRoot,
                "recordish",
                "recordable"))) {
            throw new IllegalStateException(
                    "Recordish data directory did not take precedence.");
        }

        if (!RecordishConfig.DEFAULT_FILENAME_PATTERN.startsWith(
                "recordish-")) {
            throw new IllegalStateException(
                    "Default filename still has the old identity.");
        }
        System.out.println("RECORDISH_IDENTITY_MIGRATION_SMOKE_OK=" + root);
    }
}
