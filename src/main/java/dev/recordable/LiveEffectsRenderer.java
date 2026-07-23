package dev.recordable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Live, in-game previews for filters, watermarks and streamer censor regions.
 *
 * <p>The HUD pass is intended for {@code RenderGameOverlayEvent.Post(ALL)}.
 * The screen pass is intended for {@code GuiScreenEvent.DrawScreenEvent.Post}
 * and repeats only the privacy censor so an inventory or other open screen
 * cannot cover it.</p>
 */
public final class LiveEffectsRenderer {
    private static final String FILTER_VHS = "Filter:VHS";
    private static final String FILTER_LCD = "Filter:LCD_MOIRE";
    private static final String FILTER_CRT = "Filter:CRT";
    private static final String[] DEFAULT_FILTER_ORDER = {
        FILTER_VHS, FILTER_LCD, FILTER_CRT
    };

    private static final long TEXTURE_RETRY_MS = 5000L;
    private static final long TEXTURE_PRUNE_INTERVAL_MS = 10000L;
    private static final long TEXTURE_UNUSED_TTL_MS = 30000L;
    private static final long MAX_IMAGE_FILE_BYTES = 64L * 1024L * 1024L;
    private static final long MAX_IMAGE_PIXELS = 32L * 1024L * 1024L;
    private static final int MAX_IMAGE_DIMENSION = 8192;

    private static final Map<String, CachedImageTexture> IMAGE_TEXTURES =
        new HashMap<String, CachedImageTexture>();
    private static final FloatBuffer COLOR_READ_BUFFER =
        BufferUtils.createFloatBuffer(4);

    private static long filterTick;
    private static long watermarkPreviewAnchorMs;
    private static long lastTexturePruneMs;
    private static long lastRenderFailureLogMs;

    private LiveEffectsRenderer() {
    }

    /**
     * Renders live filters first, followed by watermark slots and censor
     * regions. The normal recording HUD can therefore be rendered afterward
     * without being obscured by a filter tint.
     */
    public static void renderHud(ScaledResolution resolution) {
        if (resolution == null) {
            return;
        }

        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft == null || minecraft.fontRendererObj == null) {
            return;
        }
        RecordableConfig config = RecordableConfig.get();
        RecordingManager manager = RecordingManager.getInstance();
        if (config == null || manager == null) {
            return;
        }

        long nowMs = System.currentTimeMillis();
        pruneTextureCache(nowMs);

        boolean recording = manager.isActiveOrStopping();
        boolean filters = recording
            && config.showOverlay
            && config.showFiltersLive
            && hasVisibleFilter(config);
        boolean watermarks = shouldRenderWatermarks(config, recording);
        boolean censors = minecraft.theWorld != null
            && shouldRenderCensors(config, recording);
        if (!filters && !watermarks && !censors) {
            return;
        }

        GlStateSnapshot state = null;
        boolean matrixPushed = false;
        try {
            state = GlStateSnapshot.capture();
            GlStateManager.matrixMode(GL11.GL_MODELVIEW);
            GlStateManager.pushMatrix();
            matrixPushed = true;
            prepareOverlayState();

            if (filters) {
                renderFilters(config, resolution);
            }
            if (watermarks) {
                renderWatermarks(
                    minecraft,
                    manager,
                    config,
                    resolution,
                    recording,
                    nowMs);
            }
            if (censors) {
                renderCensorRegions(
                    minecraft.fontRendererObj,
                    config,
                    resolution);
            }
        } catch (Throwable throwable) {
            logRenderFailure("HUD", throwable);
        } finally {
            if (matrixPushed) {
                try {
                    GlStateManager.popMatrix();
                } catch (Throwable ignored) {
                }
            }
            if (state != null) {
                try {
                    state.restore();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * Re-renders censor regions over an active GuiScreen. Filters and
     * watermarks intentionally remain in the HUD pass, matching V1-0.08.
     */
    public static void renderScreen(ScaledResolution resolution) {
        if (resolution == null) {
            return;
        }

        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft == null
                || minecraft.currentScreen == null
                || minecraft.theWorld == null
                || minecraft.fontRendererObj == null) {
            return;
        }

        RecordableConfig config = RecordableConfig.get();
        RecordingManager manager = RecordingManager.getInstance();
        if (config == null
                || manager == null
                || !shouldRenderCensors(
                    config,
                    manager.isActiveOrStopping())) {
            return;
        }

        GlStateSnapshot state = null;
        boolean matrixPushed = false;
        try {
            state = GlStateSnapshot.capture();
            GlStateManager.matrixMode(GL11.GL_MODELVIEW);
            GlStateManager.pushMatrix();
            matrixPushed = true;
            prepareOverlayState();
            renderCensorRegions(
                minecraft.fontRendererObj,
                config,
                resolution);
        } catch (Throwable throwable) {
            logRenderFailure("screen", throwable);
        } finally {
            if (matrixPushed) {
                try {
                    GlStateManager.popMatrix();
                } catch (Throwable ignored) {
                }
            }
            if (state != null) {
                try {
                    state.restore();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void prepareOverlayState() {
        GlStateManager.disableLighting();
        GlStateManager.disableDepth();
        GlStateManager.depthMask(false);
        GlStateManager.enableAlpha();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(
            GL11.GL_SRC_ALPHA,
            GL11.GL_ONE_MINUS_SRC_ALPHA,
            GL11.GL_ONE,
            GL11.GL_ZERO);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private static boolean hasVisibleFilter(RecordableConfig config) {
        return config.filterVhsVisible
            || config.filterLcdMoireVisible
            || config.filterCrtVisible;
    }

    private static void renderFilters(
            RecordableConfig config,
            ScaledResolution resolution) {
        int width = resolution.getScaledWidth();
        int height = resolution.getScaledHeight();
        if (width <= 0 || height <= 0) {
            return;
        }

        for (String layer : resolveFilterOrder(config.hudLayerOrder)) {
            if (FILTER_VHS.equals(layer) && config.filterVhsVisible) {
                filterTick++;
                renderVhsFilter(
                    width,
                    height,
                    normalizeIntensity(config.filterVhsIntensity));
            } else if (FILTER_LCD.equals(layer)
                    && config.filterLcdMoireVisible) {
                filterTick++;
                renderLcdFilter(
                    width,
                    height,
                    normalizeIntensity(config.filterLcdMoireIntensity));
            } else if (FILTER_CRT.equals(layer)
                    && config.filterCrtVisible) {
                filterTick++;
                renderCrtFilter(
                    width,
                    height,
                    normalizeIntensity(config.filterCrtIntensity));
            }
        }
    }

    private static List<String> resolveFilterOrder(String configured) {
        Set<String> ordered = new LinkedHashSet<String>();
        if (configured != null) {
            String[] parts = configured.split(",");
            for (String part : parts) {
                String value = part.trim();
                if (FILTER_VHS.equals(value)
                        || FILTER_LCD.equals(value)
                        || FILTER_CRT.equals(value)) {
                    ordered.add(value);
                }
            }
        }
        for (String value : DEFAULT_FILTER_ORDER) {
            ordered.add(value);
        }
        return new ArrayList<String>(ordered);
    }

    private static float normalizeIntensity(int intensity) {
        return clamp(intensity, 0, 100) / 100.0F;
    }

    /**
     * VHS: warm tint, moving scanlines/tracking noise and red/blue edge
     * fringing.
     */
    private static void renderVhsFilter(int width, int height, float amount) {
        int tintAlpha = Math.round(30.0F * amount);
        if (tintAlpha > 0) {
            Gui.drawRect(
                0,
                0,
                width,
                height,
                argb(tintAlpha, 180, 120, 40));
        }

        int scanAlpha = Math.round(25.0F * amount);
        if (scanAlpha > 0) {
            int offset = (int) ((filterTick / 2L) % 4L);
            int color = argb(scanAlpha, 0, 0, 0);
            for (int y = offset; y < height; y += 4) {
                Gui.drawRect(0, y, width, Math.min(height, y + 1), color);
            }
        }

        int trackingY =
            (int) (((filterTick * 3L) / 2L) % (height + 60L)) - 30;
        int trackAlpha = Math.round(18.0F * amount);
        if (trackAlpha > 0 && trackingY > -10 && trackingY < height) {
            int bandHeight = 3 + (int) (filterTick % 3L);
            Gui.drawRect(
                0,
                trackingY,
                width,
                Math.min(height, trackingY + bandHeight),
                argb(trackAlpha, 200, 200, 200));
        }

        if ((filterTick % 8L) < 2L && amount > 0.3F) {
            int noiseY = (int) ((filterTick * 7L + 41L) % height);
            int noiseHeight = 1 + (int) (filterTick % 2L);
            Gui.drawRect(
                0,
                noiseY,
                width,
                Math.min(height, noiseY + noiseHeight),
                argb(Math.round(12.0F * amount), 255, 255, 255));
        }

        int fringeAlpha = Math.round(15.0F * amount);
        if (fringeAlpha > 0) {
            int fringeWidth = Math.max(1, Math.round(3.0F * amount));
            Gui.drawRect(
                0,
                0,
                fringeWidth,
                height,
                argb(fringeAlpha, 255, 60, 60));
            Gui.drawRect(
                width - fringeWidth,
                0,
                width,
                height,
                argb(fringeAlpha, 60, 60, 255));
        }
    }

    /**
     * LCD moire: mild dimming, RGB sub-pixel columns and moving interference
     * bands.
     */
    private static void renderLcdFilter(int width, int height, float amount) {
        int dimAlpha = Math.round(15.0F * amount);
        if (dimAlpha > 0) {
            Gui.drawRect(0, 0, width, height, argb(dimAlpha, 0, 0, 0));
        }

        int subPixelAlpha = Math.round(12.0F * amount);
        if (subPixelAlpha > 0) {
            for (int x = 0; x < width; x++) {
                int phase = x % 3;
                int red = phase == 0 ? 255 : 0;
                int green = phase == 1 ? 255 : 0;
                int blue = phase == 2 ? 255 : 0;
                Gui.drawRect(
                    x,
                    0,
                    x + 1,
                    height,
                    argb(subPixelAlpha, red, green, blue));
            }
        }

        int moireAlpha = Math.round(10.0F * amount);
        if (moireAlpha > 0) {
            float phase = filterTick * 0.05F;
            for (int y = 0; y < height; y += 6) {
                float sine = (float) Math.sin(y * 0.15F + phase);
                int bandAlpha = Math.round(
                    moireAlpha * Math.abs(sine));
                if (bandAlpha > 0) {
                    Gui.drawRect(
                        0,
                        y,
                        width,
                        Math.min(height, y + 3),
                        argb(bandAlpha, 128, 128, 128));
                }
            }
        }
    }

    /**
     * CRT: dark horizontal scanlines with a light warm phosphor tint.
     */
    private static void renderCrtFilter(int width, int height, float amount) {
        int scanAlpha = Math.round(30.0F * amount);
        if (scanAlpha > 0) {
            int scanColor = argb(scanAlpha, 0, 0, 0);
            for (int y = 0; y < height; y += 3) {
                Gui.drawRect(
                    0,
                    y,
                    width,
                    Math.min(height, y + 1),
                    scanColor);
            }
        }

        int glowAlpha = Math.round(8.0F * amount);
        if (glowAlpha > 0) {
            Gui.drawRect(
                0,
                0,
                width,
                height,
                argb(glowAlpha, 255, 240, 200));
        }
    }

    private static boolean shouldRenderWatermarks(
            RecordableConfig config,
            boolean recording) {
        return config.watermarksEnabled
            && config.watermarkSlots != null
            && !config.watermarkSlots.isEmpty()
            /*
             * V1-0.08 always renders watermarks while recording because its
             * final-frame hook bakes the live layer. showWatermarksLive gates
             * the non-recording placement preview.
             */
            && (recording || config.showWatermarksLive);
    }

    private static void renderWatermarks(
            Minecraft minecraft,
            RecordingManager manager,
            RecordableConfig config,
            ScaledResolution resolution,
            boolean recording,
            long nowMs) {
        long effectTimeMs;
        if (recording) {
            watermarkPreviewAnchorMs = 0L;
            effectTimeMs = manager.getEffectiveRecordingMillis();
        } else {
            if (watermarkPreviewAnchorMs == 0L) {
                watermarkPreviewAnchorMs = nowMs;
            }
            effectTimeMs = Math.max(0L, nowMs - watermarkPreviewAnchorMs);
        }

        double recordingSeconds = effectTimeMs / 1000.0D;
        String username = minecraft.thePlayer == null
            ? "Player"
            : minecraft.thePlayer.getName();
        int screenWidth = resolution.getScaledWidth();
        int screenHeight = resolution.getScaledHeight();

        for (WatermarkSlot slot : config.watermarkSlots) {
            if (slot == null || !slot.enabled) {
                continue;
            }
            if (recording && !slot.visibleAt(recordingSeconds)) {
                continue;
            }

            try {
                if (slot.kind == WatermarkSlot.Kind.IMAGE) {
                    renderImageWatermark(
                        minecraft,
                        slot,
                        screenWidth,
                        screenHeight,
                        effectTimeMs,
                        nowMs);
                } else {
                    renderTextWatermark(
                        minecraft.fontRendererObj,
                        slot,
                        screenWidth,
                        screenHeight,
                        username,
                        effectTimeMs);
                }
            } catch (Throwable throwable) {
                RecordableMod.LOGGER.debug(
                    "Unable to render watermark slot {}.",
                    slot.name,
                    throwable);
            }
        }
    }

    private static void renderImageWatermark(
            Minecraft minecraft,
            WatermarkSlot slot,
            int screenWidth,
            int screenHeight,
            long effectTimeMs,
            long nowMs) {
        CachedImageTexture texture = getImageTexture(
            minecraft,
            slot.imagePath,
            nowMs);
        if (texture == null || texture.texture == null) {
            return;
        }

        float animationAlpha = animationAlpha(slot, effectTimeMs);
        float opacity = clamp(slot.opacity, 0, 100) / 100.0F;
        float finalAlpha = opacity * animationAlpha;
        if (finalAlpha <= 0.01F) {
            return;
        }

        float userScale = Math.max(0.05F, slot.scale / 100.0F);
        float maximumBox = Math.max(
            1.0F,
            Math.min(100.0F, screenWidth * 0.09F));
        float fit = Math.min(
            maximumBox / texture.width,
            maximumBox / texture.height);
        fit = Math.min(1.0F, fit);
        float scale = fit * userScale;
        float scaledWidth = texture.width * scale;
        float scaledHeight = texture.height * scale;
        float[] position = computeWatermarkPosition(
            slot,
            screenWidth,
            screenHeight,
            scaledWidth,
            scaledHeight);
        float x = position[0] + animationSlideX(slot, effectTimeMs);
        float y = position[1];

        GlStateManager.pushMatrix();
        try {
            GlStateManager.translate(
                x + scaledWidth / 2.0F,
                y + scaledHeight / 2.0F,
                0.0F);
            if (slot.rotation != 0) {
                GlStateManager.rotate(
                    slot.rotation,
                    0.0F,
                    0.0F,
                    1.0F);
            }
            GlStateManager.scale(scale, scale, 1.0F);
            GlStateManager.translate(
                -texture.width / 2.0F,
                -texture.height / 2.0F,
                0.0F);

            GlStateManager.enableTexture2D();
            GlStateManager.enableAlpha();
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(
                GL11.GL_SRC_ALPHA,
                GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE,
                GL11.GL_ZERO);
            GlStateManager.color(1.0F, 1.0F, 1.0F, finalAlpha);
            GlStateManager.bindTexture(texture.texture.getGlTextureId());
            Gui.drawModalRectWithCustomSizedTexture(
                0,
                0,
                0.0F,
                0.0F,
                texture.width,
                texture.height,
                texture.width,
                texture.height);
        } finally {
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            GlStateManager.popMatrix();
        }
    }

    private static void renderTextWatermark(
            FontRenderer font,
            WatermarkSlot slot,
            int screenWidth,
            int screenHeight,
            String username,
            long effectTimeMs) {
        String text = slot.resolveText(username);
        if (text == null || text.isEmpty()) {
            return;
        }

        List<String> colorStops = slot.effectiveColors();
        if (colorStops == null || colorStops.isEmpty()) {
            return;
        }
        int baseColor = parseWatermarkColor(colorStops.get(0));
        int baseAlpha = (baseColor >>> 24) & 255;
        if (baseAlpha == 0) {
            baseAlpha = 255;
        }

        float opacity = clamp(slot.opacity, 0, 100) / 100.0F;
        int finalAlpha = Math.round(
            baseAlpha * opacity * animationAlpha(slot, effectTimeMs));
        finalAlpha = clamp(finalAlpha, 0, 255);
        /*
         * FontRenderer treats alpha values 0-3 as "no alpha supplied" and
         * promotes them to opaque, so skip that range during fades.
         */
        if (finalAlpha < 4) {
            return;
        }

        float scale = Math.max(0.1F, slot.scale / 100.0F);
        int textWidth = font.getStringWidth(text);
        int textHeight = font.FONT_HEIGHT;
        float scaledWidth = textWidth * scale;
        float scaledHeight = textHeight * scale;
        float[] position = computeWatermarkPosition(
            slot,
            screenWidth,
            screenHeight,
            scaledWidth,
            scaledHeight);
        float x = position[0] + animationSlideX(slot, effectTimeMs);
        float y = position[1];

        GlStateManager.pushMatrix();
        try {
            GlStateManager.translate(
                x + scaledWidth / 2.0F,
                y + scaledHeight / 2.0F,
                0.0F);
            if (slot.rotation != 0) {
                GlStateManager.rotate(
                    slot.rotation,
                    0.0F,
                    0.0F,
                    1.0F);
            }
            GlStateManager.scale(scale, scale, 1.0F);
            GlStateManager.translate(
                -textWidth / 2.0F,
                -textHeight / 2.0F,
                0.0F);
            GlStateManager.enableTexture2D();
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);

            if (colorStops.size() <= 1) {
                int color =
                    (finalAlpha << 24) | (baseColor & 0x00FFFFFF);
                font.drawString(text, 0.0F, 0.0F, color, slot.textShadow);
            } else {
                drawGradientText(
                    font,
                    text,
                    finalAlpha,
                    colorStops,
                    slot.textShadow);
            }
        } finally {
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            GlStateManager.popMatrix();
        }
    }

    private static float animationAlpha(
            WatermarkSlot slot,
            long effectTimeMs) {
        WatermarkSlot.Animation animation = slot.animation == null
            ? WatermarkSlot.Animation.NONE
            : slot.animation;
        double duration = Math.max(100, slot.animationDurationMs);

        if (animation == WatermarkSlot.Animation.FADE) {
            return (float) Math.min(1.0D, effectTimeMs / duration);
        }
        if (animation == WatermarkSlot.Animation.PULSE) {
            double phase = (effectTimeMs % (long) duration) / duration;
            return 0.45F + 0.55F
                * (float) ((1.0D
                    + Math.sin(phase * Math.PI * 2.0D)) / 2.0D);
        }
        return 1.0F;
    }

    private static float animationSlideX(
            WatermarkSlot slot,
            long effectTimeMs) {
        if (slot.animation != WatermarkSlot.Animation.SLIDE) {
            return 0.0F;
        }
        double duration = Math.max(100, slot.animationDurationMs);
        double progress = Math.min(1.0D, effectTimeMs / duration);
        return (float) ((1.0D - progress) * 60.0D);
    }

    private static float[] computeWatermarkPosition(
            WatermarkSlot slot,
            int screenWidth,
            int screenHeight,
            float contentWidth,
            float contentHeight) {
        int padding = Math.max(0, slot.padding);
        WatermarkSlot.Position position = slot.position == null
            ? WatermarkSlot.Position.BOTTOM_RIGHT
            : slot.position;
        float x;
        float y;

        switch (position) {
            case TOP_LEFT:
                x = padding;
                y = padding;
                break;
            case TOP_CENTER:
                x = (screenWidth - contentWidth) / 2.0F;
                y = padding;
                break;
            case TOP_RIGHT:
                x = screenWidth - contentWidth - padding;
                y = padding;
                break;
            case MIDDLE_LEFT:
                x = padding;
                y = (screenHeight - contentHeight) / 2.0F;
                break;
            case CENTER:
                x = (screenWidth - contentWidth) / 2.0F;
                y = (screenHeight - contentHeight) / 2.0F;
                break;
            case MIDDLE_RIGHT:
                x = screenWidth - contentWidth - padding;
                y = (screenHeight - contentHeight) / 2.0F;
                break;
            case BOTTOM_LEFT:
                x = padding;
                y = screenHeight - contentHeight - padding;
                break;
            case BOTTOM_CENTER:
                x = (screenWidth - contentWidth) / 2.0F;
                y = screenHeight - contentHeight - padding;
                break;
            case CUSTOM:
                x = slot.customX;
                y = slot.customY;
                break;
            case BOTTOM_RIGHT:
            default:
                x = screenWidth - contentWidth - padding;
                y = screenHeight - contentHeight - padding;
                break;
        }
        return new float[] {x, y};
    }

    private static void drawGradientText(
            FontRenderer font,
            String text,
            int alpha,
            List<String> colorStops,
            boolean shadow) {
        int count = colorStops.size();
        int[][] stops = new int[count][3];
        for (int index = 0; index < count; index++) {
            int color = parseWatermarkColor(colorStops.get(index));
            stops[index][0] = (color >>> 16) & 255;
            stops[index][1] = (color >>> 8) & 255;
            stops[index][2] = color & 255;
        }

        int totalWidth = Math.max(1, font.getStringWidth(text));
        int penX = 0;
        for (int index = 0; index < text.length(); index++) {
            String character = String.valueOf(text.charAt(index));
            int characterWidth = font.getStringWidth(character);
            float fraction =
                (penX + characterWidth / 2.0F) / totalWidth;
            int rgb = sampleGradient(stops, fraction);
            int color = (alpha << 24) | rgb;
            font.drawString(
                character,
                (float) penX,
                0.0F,
                color,
                shadow);
            penX += characterWidth;
        }
    }

    private static int sampleGradient(int[][] stops, float amount) {
        if (stops.length == 1) {
            return (stops[0][0] << 16)
                | (stops[0][1] << 8)
                | stops[0][2];
        }
        float normalized = clamp(amount, 0.0F, 1.0F);
        float scaled = normalized * (stops.length - 1);
        int index = (int) Math.floor(scaled);
        if (index >= stops.length - 1) {
            index = stops.length - 2;
        }
        float local = scaled - index;
        int[] first = stops[index];
        int[] second = stops[index + 1];
        int red = Math.round(
            first[0] + (second[0] - first[0]) * local);
        int green = Math.round(
            first[1] + (second[1] - first[1]) * local);
        int blue = Math.round(
            first[2] + (second[2] - first[2]) * local);
        return (red << 16) | (green << 8) | blue;
    }

    private static int parseWatermarkColor(String value) {
        if (value == null) {
            return 0xFFFFFFFF;
        }
        String normalized = value.trim();
        if (normalized.startsWith("#")) {
            normalized = normalized.substring(1);
        }
        try {
            if (normalized.length() == 6) {
                return 0xFF000000
                    | ((int) Long.parseLong(normalized, 16) & 0xFFFFFF);
            }
            if (normalized.length() == 8) {
                return (int) (Long.parseLong(normalized, 16)
                    & 0xFFFFFFFFL);
            }
        } catch (NumberFormatException ignored) {
        }
        return 0xFFFFFFFF;
    }

    private static CachedImageTexture getImageTexture(
            Minecraft minecraft,
            String configuredPath,
            long nowMs) {
        File file = resolveImageFile(minecraft, configuredPath);
        if (file == null) {
            return null;
        }

        String key;
        try {
            key = file.getCanonicalPath();
        } catch (IOException ignored) {
            key = file.getAbsolutePath();
        }

        CachedImageTexture entry = IMAGE_TEXTURES.get(key);
        if (entry == null) {
            entry = new CachedImageTexture();
            IMAGE_TEXTURES.put(key, entry);
        }
        entry.lastUsedAtMs = nowMs;

        if (!file.isFile()) {
            releaseTexture(entry);
            return null;
        }
        long modified = file.lastModified();
        long length = file.length();
        if (entry.texture != null
                && entry.loadedModified == modified
                && entry.loadedLength == length) {
            return entry;
        }
        if (entry.attemptedModified == modified
                && entry.attemptedLength == length
                && nowMs < entry.retryAfterMs) {
            return entry.texture == null ? null : entry;
        }

        boolean newFileRevision =
            entry.attemptedModified != modified
                || entry.attemptedLength != length;
        entry.attemptedModified = modified;
        entry.attemptedLength = length;
        entry.retryAfterMs = nowMs + TEXTURE_RETRY_MS;
        if (newFileRevision) {
            entry.failureLoggedForAttempt = false;
        }

        if (length <= 0L || length > MAX_IMAGE_FILE_BYTES) {
            logTextureFailureOnce(
                entry,
                "Watermark image is empty or larger than 64 MiB: " + key,
                null);
            return entry.texture == null ? null : entry;
        }

        BufferedImage image = null;
        DynamicTexture dynamicTexture = null;
        try {
            image = ImageIO.read(file);
            if (image == null) {
                throw new IOException("Unsupported or corrupt image data");
            }

            int width = image.getWidth();
            int height = image.getHeight();
            long pixels = width * (long) height;
            if (width <= 0
                    || height <= 0
                    || width > MAX_IMAGE_DIMENSION
                    || height > MAX_IMAGE_DIMENSION
                    || pixels > MAX_IMAGE_PIXELS) {
                throw new IOException(
                    "Image dimensions exceed the live watermark safety limit: "
                        + width + "x" + height);
            }

            dynamicTexture = new DynamicTexture(image);
            DynamicTexture oldTexture = entry.texture;
            entry.texture = dynamicTexture;
            entry.width = width;
            entry.height = height;
            entry.loadedModified = modified;
            entry.loadedLength = length;
            entry.retryAfterMs = 0L;
            entry.failureLoggedForAttempt = false;
            if (oldTexture != null && oldTexture != dynamicTexture) {
                try {
                    oldTexture.deleteGlTexture();
                } catch (Throwable ignored) {
                }
            }
            return entry;
        } catch (Throwable throwable) {
            if (dynamicTexture != null
                    && dynamicTexture != entry.texture) {
                try {
                    dynamicTexture.deleteGlTexture();
                } catch (Throwable ignored) {
                }
            }
            logTextureFailureOnce(
                entry,
                "Unable to load live watermark image " + key,
                throwable);
            return entry.texture == null ? null : entry;
        } finally {
            if (image != null) {
                image.flush();
            }
        }
    }

    private static File resolveImageFile(
            Minecraft minecraft,
            String configuredPath) {
        if (configuredPath == null
                || configuredPath.trim().isEmpty()) {
            return null;
        }

        String value = configuredPath.trim();
        File direct = new File(value);
        if (direct.isAbsolute()) {
            return direct;
        }

        /*
         * V1-0.08 stored only an imported filename. Honor only the final path
         * component so a config cannot escape the watermark directory.
         */
        String filename = direct.getName();
        if (filename.isEmpty()) {
            return null;
        }
        return new File(
            new File(minecraft.mcDataDir, "recordable/watermarks"),
            filename);
    }

    private static void pruneTextureCache(long nowMs) {
        if (nowMs - lastTexturePruneMs
                    < TEXTURE_PRUNE_INTERVAL_MS) {
            return;
        }
        lastTexturePruneMs = nowMs;

        Iterator<Map.Entry<String, CachedImageTexture>> iterator =
            IMAGE_TEXTURES.entrySet().iterator();
        while (iterator.hasNext()) {
            CachedImageTexture entry = iterator.next().getValue();
            if (nowMs - entry.lastUsedAtMs <= TEXTURE_UNUSED_TTL_MS) {
                continue;
            }
            releaseTexture(entry);
            iterator.remove();
        }
    }

    private static void releaseTexture(CachedImageTexture entry) {
        DynamicTexture texture = entry.texture;
        entry.texture = null;
        entry.width = 0;
        entry.height = 0;
        entry.loadedModified = Long.MIN_VALUE;
        entry.loadedLength = Long.MIN_VALUE;
        if (texture != null) {
            try {
                texture.deleteGlTexture();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void logTextureFailureOnce(
            CachedImageTexture entry,
            String message,
            Throwable throwable) {
        if (entry.failureLoggedForAttempt) {
            return;
        }
        entry.failureLoggedForAttempt = true;
        if (throwable == null) {
            RecordableMod.LOGGER.warn(message);
        } else {
            RecordableMod.LOGGER.warn(message, throwable);
        }
    }

    /**
     * Documented V1-0.08 behavior:
     *
     * <ul>
     *   <li>When baking is enabled, the live block is only a positioning
     *       preview and follows the preview toggle.</li>
     *   <li>When baking is disabled, an active recording shows the privacy
     *       block unless the censor hotkey hid it.</li>
     *   <li>Outside recording, either mode requires the preview toggle.</li>
     * </ul>
     */
    private static boolean shouldRenderCensors(
            RecordableConfig config,
            boolean recording) {
        if (!config.streamerModeEnabled
                || config.censorRegions == null
                || config.censorRegions.isEmpty()) {
            return false;
        }
        if (config.bakeInOverlay) {
            return config.streamerShowCensorPreview;
        }
        if (config.censorOverlayHidden) {
            return false;
        }
        return recording || config.streamerShowCensorPreview;
    }

    private static void renderCensorRegions(
            FontRenderer font,
            RecordableConfig config,
            ScaledResolution resolution) {
        int screenWidth = resolution.getScaledWidth();
        int screenHeight = resolution.getScaledHeight();
        for (CensorRegion region : config.censorRegions) {
            if (region == null || !region.enabled) {
                continue;
            }

            int left = clamp(
                (int) Math.floor(region.x * screenWidth),
                0,
                screenWidth);
            int top = clamp(
                (int) Math.floor(region.y * screenHeight),
                0,
                screenHeight);
            int right = clamp(
                (int) Math.ceil(
                    (region.x + region.width) * screenWidth),
                left,
                screenWidth);
            int bottom = clamp(
                (int) Math.ceil(
                    (region.y + region.height) * screenHeight),
                top,
                screenHeight);
            if (right <= left || bottom <= top) {
                continue;
            }

            int first = 0xFF000000 | (region.color & 0xFFFFFF);
            int second =
                0xFF000000 | (region.colorEnd & 0xFFFFFF);
            if (region.style == CensorRegion.Style.GRADIENT) {
                drawCensorGradient(
                    left,
                    top,
                    right,
                    bottom,
                    first,
                    second,
                    region.gradientDirection);
            } else {
                Gui.drawRect(left, top, right, bottom, first);
            }

            if (region.showLabel
                    && region.label != null
                    && !region.label.trim().isEmpty()) {
                int availableWidth = Math.max(0, right - left - 4);
                if (availableWidth <= 0) {
                    continue;
                }
                String label = font.trimStringToWidth(
                    region.label,
                    availableWidth);
                int textX = left
                    + Math.max(
                        2,
                        (right - left - font.getStringWidth(label)) / 2);
                int textY = top
                    + Math.max(
                        2,
                        (bottom - top - font.FONT_HEIGHT) / 2);
                font.drawStringWithShadow(
                    label,
                    (float) textX,
                    (float) textY,
                    0xFF000000 | (region.textColor & 0xFFFFFF));
            }
        }
    }

    private static void drawCensorGradient(
            int left,
            int top,
            int right,
            int bottom,
            int first,
            int second,
            CensorRegion.GradientDirection direction) {
        CensorRegion.GradientDirection effectiveDirection =
            direction == null
                ? CensorRegion.GradientDirection.HORIZONTAL
                : direction;

        int topLeft;
        int topRight;
        int bottomRight;
        int bottomLeft;
        if (effectiveDirection
                == CensorRegion.GradientDirection.VERTICAL) {
            topLeft = first;
            topRight = first;
            bottomRight = second;
            bottomLeft = second;
        } else if (effectiveDirection
                == CensorRegion.GradientDirection.DIAGONAL) {
            int middle = interpolateColor(first, second, 0.5F);
            topLeft = first;
            topRight = middle;
            bottomRight = second;
            bottomLeft = middle;
        } else {
            topLeft = first;
            topRight = second;
            bottomRight = second;
            bottomLeft = first;
        }
        drawColorQuad(
            left,
            top,
            right,
            bottom,
            topLeft,
            topRight,
            bottomRight,
            bottomLeft);
    }

    private static void drawColorQuad(
            int left,
            int top,
            int right,
            int bottom,
            int topLeft,
            int topRight,
            int bottomRight,
            int bottomLeft) {
        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(
            GL11.GL_SRC_ALPHA,
            GL11.GL_ONE_MINUS_SRC_ALPHA,
            GL11.GL_ONE,
            GL11.GL_ZERO);
        GlStateManager.shadeModel(GL11.GL_SMOOTH);
        try {
            Tessellator tessellator = Tessellator.getInstance();
            WorldRenderer renderer = tessellator.getWorldRenderer();
            renderer.begin(
                GL11.GL_QUADS,
                DefaultVertexFormats.POSITION_COLOR);
            addColorVertex(renderer, right, top, topRight);
            addColorVertex(renderer, left, top, topLeft);
            addColorVertex(renderer, left, bottom, bottomLeft);
            addColorVertex(renderer, right, bottom, bottomRight);
            tessellator.draw();
        } finally {
            GlStateManager.shadeModel(GL11.GL_FLAT);
            GlStateManager.enableTexture2D();
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        }
    }

    private static void addColorVertex(
            WorldRenderer renderer,
            int x,
            int y,
            int color) {
        renderer.pos(x, y, 0.0D)
            .color(
                (color >>> 16) & 255,
                (color >>> 8) & 255,
                color & 255,
                (color >>> 24) & 255)
            .endVertex();
    }

    private static int interpolateColor(
            int first,
            int second,
            float amount) {
        float value = clamp(amount, 0.0F, 1.0F);
        int alpha = Math.round(
            ((first >>> 24) & 255)
                + (((second >>> 24) & 255)
                    - ((first >>> 24) & 255)) * value);
        int red = Math.round(
            ((first >>> 16) & 255)
                + (((second >>> 16) & 255)
                    - ((first >>> 16) & 255)) * value);
        int green = Math.round(
            ((first >>> 8) & 255)
                + (((second >>> 8) & 255)
                    - ((first >>> 8) & 255)) * value);
        int blue = Math.round(
            (first & 255)
                + ((second & 255) - (first & 255)) * value);
        return argb(alpha, red, green, blue);
    }

    private static void logRenderFailure(
            String pass,
            Throwable throwable) {
        long now = System.currentTimeMillis();
        if (now - lastRenderFailureLogMs < 5000L) {
            return;
        }
        lastRenderFailureLogMs = now;
        RecordableMod.LOGGER.warn(
            "Unable to render Record-able live effects during the {} pass.",
            pass,
            throwable);
    }

    private static int argb(int alpha, int red, int green, int blue) {
        return ((alpha & 255) << 24)
            | ((red & 255) << 16)
            | ((green & 255) << 8)
            | (blue & 255);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static float clamp(
            float value,
            float minimum,
            float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static final class CachedImageTexture {
        DynamicTexture texture;
        int width;
        int height;
        long loadedModified = Long.MIN_VALUE;
        long loadedLength = Long.MIN_VALUE;
        long attemptedModified = Long.MIN_VALUE;
        long attemptedLength = Long.MIN_VALUE;
        long retryAfterMs;
        long lastUsedAtMs;
        boolean failureLoggedForAttempt;

        CachedImageTexture() {
        }
    }

    /**
     * Snapshot of every GL value this renderer intentionally changes. Restores
     * through GlStateManager where it maintains an internal cache, preventing a
     * raw glPopAttrib from leaving Minecraft's cache out of sync with the
     * actual driver state.
     */
    private static final class GlStateSnapshot {
        final boolean texture2d;
        final boolean blend;
        final boolean alphaTest;
        final boolean depthTest;
        final boolean lighting;
        final boolean depthMask;
        final int textureBinding;
        final int blendSourceRgb;
        final int blendDestinationRgb;
        final int blendSourceAlpha;
        final int blendDestinationAlpha;
        final int shadeModel;
        final int matrixMode;
        final float red;
        final float green;
        final float blue;
        final float alpha;

        private GlStateSnapshot(
                boolean texture2d,
                boolean blend,
                boolean alphaTest,
                boolean depthTest,
                boolean lighting,
                boolean depthMask,
                int textureBinding,
                int blendSourceRgb,
                int blendDestinationRgb,
                int blendSourceAlpha,
                int blendDestinationAlpha,
                int shadeModel,
                int matrixMode,
                float red,
                float green,
                float blue,
                float alpha) {
            this.texture2d = texture2d;
            this.blend = blend;
            this.alphaTest = alphaTest;
            this.depthTest = depthTest;
            this.lighting = lighting;
            this.depthMask = depthMask;
            this.textureBinding = textureBinding;
            this.blendSourceRgb = blendSourceRgb;
            this.blendDestinationRgb = blendDestinationRgb;
            this.blendSourceAlpha = blendSourceAlpha;
            this.blendDestinationAlpha = blendDestinationAlpha;
            this.shadeModel = shadeModel;
            this.matrixMode = matrixMode;
            this.red = red;
            this.green = green;
            this.blue = blue;
            this.alpha = alpha;
        }

        static GlStateSnapshot capture() {
            COLOR_READ_BUFFER.clear();
            GL11.glGetFloat(
                GL11.GL_CURRENT_COLOR,
                COLOR_READ_BUFFER);
            return new GlStateSnapshot(
                GL11.glIsEnabled(GL11.GL_TEXTURE_2D),
                GL11.glIsEnabled(GL11.GL_BLEND),
                GL11.glIsEnabled(GL11.GL_ALPHA_TEST),
                GL11.glIsEnabled(GL11.GL_DEPTH_TEST),
                GL11.glIsEnabled(GL11.GL_LIGHTING),
                GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK),
                GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D),
                GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB),
                GL11.glGetInteger(GL14.GL_BLEND_DST_RGB),
                GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA),
                GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA),
                GL11.glGetInteger(GL11.GL_SHADE_MODEL),
                GL11.glGetInteger(GL11.GL_MATRIX_MODE),
                COLOR_READ_BUFFER.get(0),
                COLOR_READ_BUFFER.get(1),
                COLOR_READ_BUFFER.get(2),
                COLOR_READ_BUFFER.get(3));
        }

        void restore() {
            GlStateManager.tryBlendFuncSeparate(
                blendSourceRgb,
                blendDestinationRgb,
                blendSourceAlpha,
                blendDestinationAlpha);
            setBlendEnabled(blend);
            setAlphaEnabled(alphaTest);
            setDepthEnabled(depthTest);
            setLightingEnabled(lighting);
            GlStateManager.depthMask(depthMask);

            GlStateManager.enableTexture2D();
            GlStateManager.bindTexture(textureBinding);
            if (!texture2d) {
                GlStateManager.disableTexture2D();
            }

            GlStateManager.shadeModel(shadeModel);
            GlStateManager.color(red, green, blue, alpha);
            GlStateManager.matrixMode(matrixMode);
        }

        private static void setBlendEnabled(boolean enabled) {
            if (enabled) {
                GlStateManager.enableBlend();
            } else {
                GlStateManager.disableBlend();
            }
        }

        private static void setAlphaEnabled(boolean enabled) {
            if (enabled) {
                GlStateManager.enableAlpha();
            } else {
                GlStateManager.disableAlpha();
            }
        }

        private static void setDepthEnabled(boolean enabled) {
            if (enabled) {
                GlStateManager.enableDepth();
            } else {
                GlStateManager.disableDepth();
            }
        }

        private static void setLightingEnabled(boolean enabled) {
            if (enabled) {
                GlStateManager.enableLighting();
            } else {
                GlStateManager.disableLighting();
            }
        }
    }
}
