package dev.recordish;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Opt-in live-network smoke test for the managed FFmpeg installer.
 */
public final class FfmpegInstallerSmoke {
    private FfmpegInstallerSmoke() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) {
            throw new IllegalArgumentException(
                "Expected a dedicated empty destination directory.");
        }
        Path destination = Paths.get(arguments[0])
            .toAbsolutePath()
            .normalize();
        if (Files.exists(destination)) {
            throw new IllegalStateException(
                "Refusing to reuse an existing installer smoke directory: "
                    + destination);
        }
        if (!destination.toString().contains("ffmpeg-installer-smoke")) {
            throw new IllegalArgumentException(
                "Installer smoke destination must include "
                    + "'ffmpeg-installer-smoke'.");
        }

        FfmpegBundleManager.FfmpegStatus status =
            FfmpegBundleManager.installForCurrentPlatform(destination);
        if (!status.isFound()) {
            throw new IllegalStateException(
                "Published FFmpeg failed its live probe: "
                    + status.getError());
        }

        Path executable = destination.resolve(
            PlatformUtils.executableName("ffmpeg"));
        if (!Files.isRegularFile(executable)
                || Files.size(executable) <= 0L) {
            throw new IllegalStateException(
                "Installer did not publish FFmpeg at " + executable);
        }

        System.out.println(
            "RECORDISH_FFMPEG_INSTALLER_SMOKE_OK=" + executable);
        System.out.println("VERSION=" + status.getVersion());
        System.out.println("SIZE=" + Files.size(executable));
    }
}
