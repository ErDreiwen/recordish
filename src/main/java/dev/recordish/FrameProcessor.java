package dev.recordish;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.imageio.ImageIO;

/**
 * Applies the privacy-sensitive software pass after readback.
 *
 * <p>Filters, watermarks, and visible HUD effects are already present in the
 * final framebuffer captured by the mixin. Only baked censor regions belong
 * here, which also keeps replay and montage frames on the same privacy path.</p>
 */
public final class FrameProcessor {
    private final Map<String, CachedImage> images = new ConcurrentHashMap<String, CachedImage>();
    private long frameNumber;

    public void process(CapturedFrame frame, RecordishConfig config, long elapsedMillis, String username) {
        if (frame == null || config == null) return;
        processBakedCensors(frame, config);
        frameNumber++;
    }

    /**
     * Applies configured bake-in regions to a normal recording frame.
     */
    public void processBakedCensors(CapturedFrame frame, RecordishConfig config) {
        if (frame == null || config == null) return;
        if (config.streamerModeEnabled && config.bakeInOverlay) {
            drawCensors(frame, config);
        }
    }

    /**
     * Applies the privacy semantics of an active recording to replay frames.
     * Replay capture can run while the normal recorder is idle, so it cannot
     * rely on the live HUD pass to provide non-baked censor regions.
     */
    public void processReplayCensors(
            CapturedFrame frame,
            RecordishConfig config) {
        if (frame == null || config == null) return;
        if (config.streamerModeEnabled
                && (config.bakeInOverlay
                    || !config.censorOverlayHidden)) {
            drawCensors(frame, config);
        }
    }

    private void drawCensors(
            CapturedFrame frame,
            RecordishConfig config) {
        byte[] pixels = frame.getPixels();
        int width = frame.getWidth();
        int height = frame.getHeight();

        /*
         * Filters and watermarks are rendered into Minecraft's framebuffer by
         * LiveEffectsRenderer before the final-frame capture hook runs. Drawing
         * them here as well would visibly double every effect. Streamer censor
         * regions are the intentional software pass for bake-in recording and
         * for replay privacy when no normal recording is active.
         */
        BufferedImage image = wrapRgb(pixels, width, height);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            drawCensors(graphics, width, height, config);
        } finally {
            graphics.dispose();
        }
        drawCensorLabels(pixels, width, height, config);
    }

    private void applyFilters(byte[] pixels, int width, int height, RecordishConfig config) {
        if (config.filterVhsVisible) {
            int intensity = config.filterVhsIntensity;
            int stride = width * 3;
            for (int y = 0; y < height; y++) {
                boolean scan = (y & 3) == 0;
                int lineNoise = pseudoNoise((int) (frameNumber + y * 31L)) * intensity / 100;
                for (int x = 0; x < width; x++) {
                    int offset = y * stride + x * 3;
                    int noise = ((lineNoise + pseudoNoise(x + y * 7)) % 11 - 5) * intensity / 100;
                    pixels[offset] = clampByte((pixels[offset] & 255) + noise + (scan ? intensity / 24 : 0));
                    pixels[offset + 1] = clampByte((pixels[offset + 1] & 255) + noise);
                    pixels[offset + 2] = clampByte((pixels[offset + 2] & 255) + noise - (scan ? intensity / 18 : 0));
                }
            }
        }
        if (config.filterLcdMoireVisible) {
            int intensity = config.filterLcdMoireIntensity;
            int stride = width * 3;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int offset = y * stride + x * 3;
                    int channel = x % 3;
                    int boost = intensity / 10;
                    pixels[offset + channel] = clampByte((pixels[offset + channel] & 255) + boost);
                    if ((y & 1) == 1) {
                        pixels[offset] = clampByte((pixels[offset] & 255) - intensity / 22);
                        pixels[offset + 1] = clampByte((pixels[offset + 1] & 255) - intensity / 22);
                        pixels[offset + 2] = clampByte((pixels[offset + 2] & 255) - intensity / 22);
                    }
                }
            }
        }
        if (config.filterCrtVisible) {
            int intensity = config.filterCrtIntensity;
            int stride = width * 3;
            double centerX = width / 2.0D;
            double centerY = height / 2.0D;
            double maxDistance = Math.sqrt(centerX * centerX + centerY * centerY);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int offset = y * stride + x * 3;
                    double dx = x - centerX;
                    double dy = y - centerY;
                    double vignette = Math.sqrt(dx * dx + dy * dy) / maxDistance;
                    int darken = (int) (vignette * vignette * intensity * 0.55D);
                    if ((y & 1) == 1) darken += intensity / 14;
                    pixels[offset] = clampByte((pixels[offset] & 255) - darken);
                    pixels[offset + 1] = clampByte((pixels[offset + 1] & 255) - darken);
                    pixels[offset + 2] = clampByte((pixels[offset + 2] & 255) - darken);
                }
            }
        }
    }

    private void drawCensors(Graphics2D graphics, int width, int height, RecordishConfig config) {
        for (CensorRegion region : config.censorRegions) {
            if (region == null || !region.enabled) continue;
            int x = (int) Math.round(region.x * width);
            int y = (int) Math.round(region.y * height);
            int w = Math.max(1, (int) Math.round(region.width * width));
            int h = Math.max(1, (int) Math.round(region.height * height));
            Color start = new Color(region.color | 0xFF000000, true);
            if (region.style == CensorRegion.Style.GRADIENT) {
                Color end = new Color(region.colorEnd | 0xFF000000, true);
                int endX = x + w;
                int endY = y;
                if (region.gradientDirection == CensorRegion.GradientDirection.VERTICAL) {
                    endX = x;
                    endY = y + h;
                } else if (region.gradientDirection == CensorRegion.GradientDirection.DIAGONAL) {
                    endY = y + h;
                }
                graphics.setPaint(new GradientPaint(x, y, start, endX, endY, end));
            } else {
                graphics.setColor(start);
            }
            graphics.fillRect(x, y, w, h);
        }
    }

    private static void drawCensorLabels(
            byte[] pixels,
            int frameWidth,
            int frameHeight,
            RecordishConfig config) {
        for (CensorRegion region : config.censorRegions) {
            if (region == null
                    || !region.enabled
                    || !region.showLabel
                    || region.label == null
                    || region.label.trim().isEmpty()) {
                continue;
            }
            int x = (int) Math.round(region.x * frameWidth);
            int y = (int) Math.round(region.y * frameHeight);
            int width = Math.max(
                1,
                (int) Math.round(region.width * frameWidth));
            int height = Math.max(
                1,
                (int) Math.round(region.height * frameHeight));
            String label = region.label.trim();
            int baseWidth = CensorFont.textWidth(label);
            if (baseWidth <= 0) {
                continue;
            }
            int scale = Math.min(
                (int) Math.floor(width * 0.85D / baseWidth),
                (int) Math.floor(
                    height * 0.60D / CensorFont.GLYPH_H));
            if (scale < 1) {
                continue;
            }
            int textWidth = baseWidth * scale;
            int textHeight = CensorFont.GLYPH_H * scale;
            CensorFont.drawText(
                pixels,
                frameWidth,
                frameHeight,
                x + (width - textWidth) / 2,
                y + (height - textHeight) / 2,
                label,
                region.textColor & 0xFFFFFF,
                scale);
        }
    }

    private void drawWatermarks(
            Graphics2D graphics,
            int width,
            int height,
            RecordishConfig config,
            long elapsedMillis,
            String username) {
        double seconds = elapsedMillis / 1000.0D;
        for (WatermarkSlot slot : config.watermarkSlots) {
            if (slot == null || !slot.visibleAt(seconds)) continue;
            float animationAlpha = resolveAnimationAlpha(slot, elapsedMillis);
            float alpha = Math.max(0.0F, Math.min(1.0F, slot.opacity / 100.0F * animationAlpha));
            if (alpha <= 0.0F) continue;

            Image image = null;
            String text = null;
            int contentWidth;
            int contentHeight;
            Font font = null;
            if (slot.kind == WatermarkSlot.Kind.IMAGE) {
                image = loadImage(slot.imagePath);
                if (image == null) continue;
                contentWidth = Math.max(1, image.getWidth(null) * slot.scale / 100);
                contentHeight = Math.max(1, image.getHeight(null) * slot.scale / 100);
            } else {
                text = slot.resolveText(username);
                font = new Font("SansSerif", Font.BOLD, Math.max(8, 14 * slot.scale / 100));
                graphics.setFont(font);
                FontMetrics metrics = graphics.getFontMetrics();
                contentWidth = Math.max(1, metrics.stringWidth(text));
                contentHeight = Math.max(1, metrics.getHeight());
            }

            int[] point = resolvePosition(slot, width, height, contentWidth, contentHeight);
            if (slot.animation == WatermarkSlot.Animation.SLIDE) {
                point[0] -= (int) ((1.0F - animationAlpha) * (contentWidth + slot.padding));
            }
            Graphics2D layer = (Graphics2D) graphics.create();
            try {
                layer.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
                double centerX = point[0] + contentWidth / 2.0D;
                double centerY = point[1] + contentHeight / 2.0D;
                layer.rotate(Math.toRadians(slot.rotation), centerX, centerY);
                if (image != null) {
                    layer.drawImage(image, point[0], point[1], contentWidth, contentHeight, null);
                } else {
                    drawWatermarkText(layer, slot, text, font, point[0], point[1], contentWidth, contentHeight);
                }
            } finally {
                layer.dispose();
            }
        }
    }

    private void drawWatermarkText(
            Graphics2D graphics,
            WatermarkSlot slot,
            String text,
            Font font,
            int x,
            int y,
            int width,
            int height) {
        graphics.setFont(font);
        FontMetrics metrics = graphics.getFontMetrics();
        int baseline = y + (height - metrics.getHeight()) / 2 + metrics.getAscent();
        if (slot.textShadow) {
            graphics.setColor(new Color(0, 0, 0, 170));
            graphics.drawString(text, x + 1, baseline + 1);
        }
        java.util.List<String> colors = slot.effectiveColors();
        Color first = parseColor(colors.get(0), Color.WHITE);
        Color last = parseColor(colors.get(colors.size() - 1), first);
        if (colors.size() > 1) {
            graphics.setPaint(new GradientPaint(x, baseline, first, x + Math.max(1, width), baseline, last));
        } else {
            graphics.setColor(first);
        }
        graphics.drawString(text, x, baseline);
    }

    private int[] resolvePosition(WatermarkSlot slot, int screenW, int screenH, int width, int height) {
        int padding = slot.padding;
        int left = padding;
        int centerX = (screenW - width) / 2;
        int right = screenW - width - padding;
        int top = padding;
        int centerY = (screenH - height) / 2;
        int bottom = screenH - height - padding;
        switch (slot.position) {
            case TOP_CENTER: return new int[]{centerX, top};
            case TOP_RIGHT: return new int[]{right, top};
            case MIDDLE_LEFT: return new int[]{left, centerY};
            case CENTER: return new int[]{centerX, centerY};
            case MIDDLE_RIGHT: return new int[]{right, centerY};
            case BOTTOM_LEFT: return new int[]{left, bottom};
            case BOTTOM_CENTER: return new int[]{centerX, bottom};
            case BOTTOM_RIGHT: return new int[]{right, bottom};
            case CUSTOM: return new int[]{slot.customX, slot.customY};
            case TOP_LEFT:
            default:
                return new int[]{left, top};
        }
    }

    private float resolveAnimationAlpha(WatermarkSlot slot, long elapsedMillis) {
        if (slot.animation == null || slot.animation == WatermarkSlot.Animation.NONE) return 1.0F;
        int duration = Math.max(100, slot.animationDurationMs);
        if (slot.animation == WatermarkSlot.Animation.PULSE) {
            double phase = (elapsedMillis % duration) / (double) duration * Math.PI * 2.0D;
            return (float) (0.65D + 0.35D * (Math.sin(phase) * 0.5D + 0.5D));
        }
        return Math.min(1.0F, elapsedMillis / (float) duration);
    }

    private Image loadImage(String path) {
        if (path == null || path.trim().isEmpty()) return null;
        try {
            java.nio.file.Path resolved =
                    WatermarkImageStore.resolve(path);
            if (resolved == null) return null;
            File file = resolved.toFile().getAbsoluteFile();
            if (!file.isFile()) return null;
            String key = file.getPath();
            long modified = file.lastModified();
            CachedImage cached = images.get(key);
            if (cached != null && cached.modified == modified) return cached.image;
            BufferedImage loaded = ImageIO.read(file);
            if (loaded != null) images.put(key, new CachedImage(loaded, modified));
            return loaded;
        } catch (Exception exception) {
            return null;
        }
    }

    private static BufferedImage wrapRgb(byte[] pixels, int width, int height) {
        DataBufferByte buffer = new DataBufferByte(pixels, pixels.length);
        WritableRaster raster = Raster.createInterleavedRaster(
            buffer, width, height, width * 3, 3, new int[]{0, 1, 2}, null);
        ComponentColorModel model = new ComponentColorModel(
            ColorSpace.getInstance(ColorSpace.CS_sRGB),
            new int[]{8, 8, 8},
            false,
            false,
            Transparency.OPAQUE,
            DataBuffer.TYPE_BYTE);
        return new BufferedImage(model, raster, false, null);
    }

    private static Color parseColor(String value, Color fallback) {
        if (value == null) return fallback;
        String normalized = value.trim();
        if (normalized.startsWith("#")) normalized = normalized.substring(1);
        try {
            if (normalized.length() == 8) return new Color((int) Long.parseLong(normalized, 16), true);
            if (normalized.length() == 6) return new Color(Integer.parseInt(normalized, 16));
        } catch (NumberFormatException ignored) {
        }
        return fallback;
    }

    private static int pseudoNoise(int seed) {
        int value = seed * 1103515245 + 12345;
        return (value >>> 16) & 255;
    }

    private static byte clampByte(int value) {
        return (byte) Math.max(0, Math.min(255, value));
    }

    private static final class CachedImage {
        final BufferedImage image;
        final long modified;

        CachedImage(BufferedImage image, long modified) {
            this.image = image;
            this.modified = modified;
        }
    }
}
