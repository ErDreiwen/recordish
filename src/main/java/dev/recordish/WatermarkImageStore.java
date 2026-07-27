package dev.recordish;

import net.minecraft.client.Minecraft;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Managed on-disk store for user-supplied watermark images.
 *
 * <p>Images live in {@code .minecraft/recordish/watermarks}. New config
 * values contain only the managed filename, while {@link #resolve(String)}
 * continues to accept legacy absolute paths.</p>
 */
public final class WatermarkImageStore {
    private static final byte[] PNG_SIGNATURE = {
            (byte) 0x89,
            0x50,
            0x4E,
            0x47,
            0x0D,
            0x0A,
            0x1A,
            0x0A
    };

    private WatermarkImageStore() {
    }

    /**
     * Returns the watermark directory, creating it when necessary.
     */
    public static Path getWatermarksDir() {
        Path gameDirectory = Minecraft.getMinecraft().mcDataDir.toPath();
        Path directory = RecordishPaths.preferredDirectory(
                gameDirectory.resolve("recordish").resolve("watermarks"),
                gameDirectory.resolve("recordable").resolve("watermarks"));
        try {
            Files.createDirectories(directory);
        } catch (IOException exception) {
            RecordishMod.LOGGER.warn(
                    "[Recordish] Could not create watermark dir {}: {}",
                    directory,
                    exception.getMessage());
        }
        return directory;
    }

    /**
     * Returns whether a path or filename has a supported image extension.
     */
    public static boolean isSupported(String pathOrName) {
        if (pathOrName == null) {
            return false;
        }
        String value = pathOrName.toLowerCase(Locale.ROOT);
        return value.endsWith(".png")
                || value.endsWith(".jpg")
                || value.endsWith(".jpeg");
    }

    /**
     * Resolves a managed filename without permitting relative path traversal.
     * Legacy absolute paths are intentionally retained for old configurations.
     */
    public static Path resolve(String filename) {
        if (isBlank(filename)) {
            return null;
        }
        try {
            Path configured = Paths.get(filename);
            if (configured.isAbsolute()) {
                return configured.normalize();
            }
            Path fileName = configured.getFileName();
            if (fileName == null || isBlank(fileName.toString())) {
                return null;
            }
            return getWatermarksDir().resolve(fileName.toString());
        } catch (Exception exception) {
            return null;
        }
    }

    /**
     * Copies a selected image into the managed store.
     *
     * @return the portable stored filename, or {@code null} when validation or
     *         copying fails
     */
    public static String importImage(String sourcePath) {
        if (isBlank(sourcePath)) {
            return null;
        }
        try {
            Path source = Paths.get(sourcePath);
            if (!Files.isRegularFile(source)) {
                RecordishMod.LOGGER.warn(
                        "[Recordish] Watermark image not a file: {}",
                        sourcePath);
                return null;
            }

            String base = source.getFileName().toString();
            if (!isSupported(base)) {
                RecordishMod.LOGGER.warn(
                        "[Recordish] Unsupported watermark image type: {}",
                        base);
                return null;
            }

            Path directory = getWatermarksDir();
            String stem = base;
            String extension = "";
            int dot = base.lastIndexOf('.');
            if (dot > 0) {
                stem = base.substring(0, dot);
                extension = base.substring(dot);
            }

            Path destination = directory.resolve(base);
            if (Files.exists(destination)) {
                if (sameContent(source, destination)) {
                    return destination.getFileName().toString();
                }
                int suffix = 1;
                do {
                    destination = directory.resolve(
                            stem + "_" + suffix + extension);
                    suffix++;
                } while (Files.exists(destination) && suffix < 10000);
            }

            Files.copy(
                    source,
                    destination,
                    StandardCopyOption.REPLACE_EXISTING);
            RecordishMod.LOGGER.info(
                    "[Recordish] Imported watermark image: {} -> {}",
                    sourcePath,
                    destination.getFileName());
            return destination.getFileName().toString();
        } catch (Exception exception) {
            RecordishMod.LOGGER.warn(
                    "[Recordish] Failed to import watermark image {}: {}",
                    sourcePath,
                    exception.getMessage());
            return null;
        }
    }

    /**
     * Lists supported managed watermark filenames.
     */
    public static List<String> listImages() {
        final List<String> images = new ArrayList<String>();
        Path directory = getWatermarksDir();
        try {
            if (Files.isDirectory(directory)) {
                try (Stream<Path> paths = Files.list(directory)) {
                    paths.filter(Files::isRegularFile)
                            .filter(path -> isSupported(
                                    path.getFileName().toString()))
                            .sorted()
                            .forEach(path -> images.add(
                                    path.getFileName().toString()));
                }
            }
        } catch (IOException exception) {
            RecordishMod.LOGGER.warn(
                    "[Recordish] Could not list watermark images: {}",
                    exception.getMessage());
        }
        return images;
    }

    /**
     * Reads PNG input unchanged and normalizes JPG/JPEG input to PNG bytes.
     */
    public static byte[] readAsPngBytes(Path path) {
        if (path == null) {
            return null;
        }
        try {
            if (!Files.isRegularFile(path)) {
                return null;
            }
            byte[] raw = Files.readAllBytes(path);
            if (isPng(raw)) {
                return raw;
            }

            BufferedImage image;
            try (ByteArrayInputStream input =
                         new ByteArrayInputStream(raw)) {
                image = ImageIO.read(input);
            }
            if (image == null) {
                RecordishMod.LOGGER.warn(
                        "[Recordish] Watermark image not decodable: {}",
                        path.getFileName());
                return null;
            }

            if (image.getType() != BufferedImage.TYPE_INT_ARGB) {
                BufferedImage argb = new BufferedImage(
                        image.getWidth(),
                        image.getHeight(),
                        BufferedImage.TYPE_INT_ARGB);
                Graphics2D graphics = argb.createGraphics();
                try {
                    graphics.drawImage(image, 0, 0, null);
                } finally {
                    graphics.dispose();
                }
                image = argb;
            }

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", output)) {
                RecordishMod.LOGGER.warn(
                        "[Recordish] No PNG writer available for "
                                + "watermark: {}",
                        path.getFileName());
                return null;
            }
            return output.toByteArray();
        } catch (Throwable throwable) {
            RecordishMod.LOGGER.warn(
                    "[Recordish] Failed to decode watermark image '{}': {}",
                    path.getFileName(),
                    throwable.toString());
            return null;
        }
    }

    private static boolean isPng(byte[] data) {
        if (data == null || data.length < PNG_SIGNATURE.length) {
            return false;
        }
        for (int index = 0; index < PNG_SIGNATURE.length; index++) {
            if (data[index] != PNG_SIGNATURE[index]) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameContent(Path first, Path second) {
        try {
            if (Files.size(first) != Files.size(second)) {
                return false;
            }
            byte[] firstBytes = Files.readAllBytes(first);
            byte[] secondBytes = Files.readAllBytes(second);
            if (firstBytes.length != secondBytes.length) {
                return false;
            }
            for (int index = 0; index < firstBytes.length; index++) {
                if (firstBytes[index] != secondBytes[index]) {
                    return false;
                }
            }
            return true;
        } catch (Exception exception) {
            return false;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
