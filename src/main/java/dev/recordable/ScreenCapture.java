package dev.recordable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.shader.Framebuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.ARBBufferObject;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GLContext;

import java.nio.ByteBuffer;

/**
 * Reads Minecraft's visible main framebuffer into packed, top-down RGB24
 * frames.
 *
 * <p>When pixel-buffer objects are available, two PBOs are used in a
 * ping-pong arrangement: each call maps the previous render's readback before
 * submitting the current one. Consequently, the first call after
 * {@link #prepare(int, int, int)} or a source resize returns {@code null}.
 * Drivers that return {@code null} from the map operation five consecutive
 * times are moved permanently to synchronous readback for that capture
 * session.</p>
 *
 * <p>All methods that make OpenGL calls must run on Minecraft's render thread
 * with a current context.</p>
 */
public final class ScreenCapture {
    private static final int BYTES_PER_PIXEL = 3;
    private static final int PBO_COUNT = 2;
    private static final int MAX_PBO_MAP_FAILURES = 5;
    private static final int BLACK_WARNING_THRESHOLD = 15;
    private static final int SOURCE_MAIN_FRAMEBUFFER = 0;
    private static final int SOURCE_BACK_BUFFER = 1;
    private static final int SOURCE_MAIN_TEXTURE = 2;
    private static final int SOURCE_COUNT = 3;

    /*
     * Numeric values are shared by the core and ARB variants. Keeping them
     * here avoids requiring GL21/ARBPixelBufferObject classes merely for
     * constants on older LWJGL2 drivers.
     */
    private static final int GL_FRAMEBUFFER_BINDING_VALUE = 0x8CA6;
    private static final int GL_PIXEL_PACK_BUFFER_VALUE = 0x88EB;
    private static final int GL_PIXEL_PACK_BUFFER_BINDING_VALUE = 0x88ED;
    private static final int GL_STREAM_READ_VALUE = 0x88E1;
    private static final int GL_READ_ONLY_VALUE = 0x88B8;

    private static final int PBO_API_UNDETECTED = -1;
    private static final int PBO_API_NONE = 0;
    private static final int PBO_API_CORE = 1;
    private static final int PBO_API_ARB = 2;

    private final int[] pboIds = new int[PBO_COUNT];
    private final long[] pboCaptureNanos =
        new long[PBO_COUNT];

    private FrameBufferPool pool;
    private ByteBuffer synchronousReadBuffer;
    private byte[] rawConvertScratch;

    private int outputWidth;
    private int outputHeight;
    private int outputByteSize;
    private boolean prepared;

    private int pboApi = PBO_API_UNDETECTED;
    private boolean pboReadbackDisabled;
    private int pboWriteIndex;
    private boolean hasPendingPboFrame;
    private int pboSourceWidth;
    private int pboSourceHeight;
    private int pboSourceByteSize;
    private int consecutivePboMapFailures;

    private int sourceWidth;
    private int sourceHeight;
    private int backingTextureWidth;
    private int backingTextureHeight;
    private int sourceFramebuffer;
    private long totalFramesProduced;
    private long totalBlackFrames;
    private int consecutiveBlackFrames;
    private double lastAverageBrightness = -1.0D;
    private double lastBlackPixelRatio;
    private int lastGlError = GL11.GL_NO_ERROR;
    private boolean synchronousFailureLogged;
    private int sourceMode;

    /**
     * Prepares a capture session.
     *
     * <p>Odd dimensions are rounded down to the nearest even value (with a
     * minimum of 2) so the resulting stream is valid for common YUV420 video
     * encoders.</p>
     */
    public synchronized void prepare(int width, int height, int pooledFrames) {
        deletePbosSafely();

        outputWidth = makeEven(width);
        outputHeight = makeEven(height);
        outputByteSize = checkedByteSize(outputWidth, outputHeight);
        pool = new FrameBufferPool(
            outputByteSize,
            pooledFrames,
            RecordableConfig.get().frameBufferPoolingEnabled);

        synchronousReadBuffer = null;
        rawConvertScratch = null;
        pboApi = PBO_API_UNDETECTED;
        pboReadbackDisabled = false;
        pboWriteIndex = 0;
        pboCaptureNanos[0] = 0L;
        pboCaptureNanos[1] = 0L;
        hasPendingPboFrame = false;
        pboSourceWidth = 0;
        pboSourceHeight = 0;
        pboSourceByteSize = 0;
        consecutivePboMapFailures = 0;

        sourceWidth = 0;
        sourceHeight = 0;
        backingTextureWidth = 0;
        backingTextureHeight = 0;
        sourceFramebuffer = 0;
        totalFramesProduced = 0L;
        totalBlackFrames = 0L;
        consecutiveBlackFrames = 0;
        lastAverageBrightness = -1.0D;
        lastBlackPixelRatio = 0.0D;
        lastGlError = GL11.GL_NO_ERROR;
        synchronousFailureLogged = false;
        sourceMode = SOURCE_MAIN_FRAMEBUFFER;
        prepared = true;
    }

    /**
     * Captures the visible Minecraft framebuffer.
     *
     * @return a pooled top-down RGB24 frame, or {@code null} while the PBO
     *         pipeline warms up or when no current display/context exists
     */
    public synchronized CapturedFrame capture() {
        if (!prepared || pool == null) {
            throw new IllegalStateException("ScreenCapture has not been prepared.");
        }
        if (!hasCurrentGlContext()) {
            return null;
        }

        SourceInfo source = resolveSource();
        if (source == null) {
            return null;
        }
        recordSourceInfo(source);

        /*
         * glGetTexImage is deliberately synchronous. It is the compatibility
         * fallback for OptiFine/shader paths where framebuffer reads can be
         * black even though Minecraft's color texture is valid.
         */
        if (source.textureReadback) {
            if (hasPendingPboFrame || pboIds[0] != 0 || pboIds[1] != 0) {
                deletePbosSafely();
            }
            return captureTextureSynchronously(source);
        }

        if (pboApi == PBO_API_UNDETECTED) {
            pboApi = detectPboApi();
            if (pboApi == PBO_API_NONE) {
                RecordableMod.LOGGER.info(
                    "Pixel-buffer objects are unavailable; Record-able will use synchronous framebuffer reads.");
            }
        }

        if (pboReadbackDisabled || pboApi == PBO_API_NONE) {
            return captureSynchronously(source);
        }

        CapturedFrame readyFrame = null;
        try {
            /*
             * Map the previous frame before binding/submitting this frame.
             * This ordering is intentional: it preserves the two-PBO pipeline
             * and avoids immediately waiting on the read just issued.
             */
            if (hasPendingPboFrame) {
                int submittedSourceMode = sourceMode;
                readyFrame = mapPendingPboFrame();
                if (sourceMode != submittedSourceMode) {
                    deletePbosSafely();
                    return readyFrame;
                }
                if (readyFrame == null) {
                    consecutivePboMapFailures++;
                    if (consecutivePboMapFailures >= MAX_PBO_MAP_FAILURES) {
                        RecordableMod.LOGGER.warn(
                            "PBO mapping returned null {} times consecutively; "
                                + "switching to synchronous framebuffer capture.",
                            Integer.valueOf(consecutivePboMapFailures));
                        disablePboReadback();
                        return captureSynchronously(source);
                    }
                } else {
                    consecutivePboMapFailures = 0;
                }
            }

            if (pboIds[0] == 0
                    || source.width != pboSourceWidth
                    || source.height != pboSourceHeight) {
                initializePbos(source.width, source.height);
            }

            submitAsyncRead(source);
            return readyFrame;
        } catch (Throwable throwable) {
            RecordableMod.LOGGER.warn(
                "Asynchronous framebuffer capture failed; switching to synchronous readback.",
                throwable);
            disablePboReadback();

            /*
             * A mapped previous frame is still valid even if submitting the
             * new read failed. Return it instead of leaking its pooled array.
             */
            if (readyFrame != null) {
                return readyFrame;
            }
            return captureSynchronously(source);
        }
    }

    private CapturedFrame captureSynchronously(SourceInfo source) {
        try {
            return captureSynchronouslyInternal(source);
        } catch (Throwable throwable) {
            if (!synchronousFailureLogged) {
                synchronousFailureLogged = true;
                RecordableMod.LOGGER.warn(
                    "Synchronous framebuffer capture failed; frames will be skipped until readback recovers.",
                    throwable);
            }
            return null;
        }
    }

    private CapturedFrame captureSynchronouslyInternal(SourceInfo source) {
        int sourceByteSize = checkedByteSize(source.width, source.height);
        ensureSynchronousReadBuffer(sourceByteSize);
        long capturedAtNanos = System.nanoTime();

        ReadbackState state = ReadbackState.capture(
            source.manageFramebufferBinding,
            pboApi != PBO_API_NONE && pboApi != PBO_API_UNDETECTED,
            this);
        try {
            state.bindSource(source);
            setTightPackState();
            if (state.managePackBuffer) {
                bindBuffer(0);
            }

            synchronousReadBuffer.clear();
            synchronousReadBuffer.limit(sourceByteSize);
            clearGlErrors();
            GL11.glReadPixels(
                0,
                0,
                source.width,
                source.height,
                GL12.GL_BGR,
                GL11.GL_UNSIGNED_BYTE,
                synchronousReadBuffer);
            lastGlError = GL11.glGetError();
            if (lastGlError != GL11.GL_NO_ERROR) {
                throw new IllegalStateException(
                    "OpenGL framebuffer read failed with error 0x"
                        + Integer.toHexString(lastGlError));
            }
        } finally {
            state.restore(this);
        }

        copyDirectBufferToScratch(synchronousReadBuffer, sourceByteSize);
        return buildFrameFromScratch(
            source.width,
            source.height,
            source.width,
            capturedAtNanos);
    }

    private CapturedFrame captureTextureSynchronously(SourceInfo source) {
        int textureByteSize = checkedByteSize(
            source.textureWidth,
            source.textureHeight);
        ensureSynchronousReadBuffer(textureByteSize);

        int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int previousPackBuffer =
            pboApi != PBO_API_NONE && pboApi != PBO_API_UNDETECTED
                ? getBoundPackBuffer()
                : 0;
        int packAlignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
        int packRowLength = GL11.glGetInteger(GL11.GL_PACK_ROW_LENGTH);
        int packSkipRows = GL11.glGetInteger(GL11.GL_PACK_SKIP_ROWS);
        int packSkipPixels = GL11.glGetInteger(GL11.GL_PACK_SKIP_PIXELS);
        int packSwapBytes = GL11.glGetInteger(GL11.GL_PACK_SWAP_BYTES);
        int packLsbFirst = GL11.glGetInteger(GL11.GL_PACK_LSB_FIRST);
        long capturedAtNanos = System.nanoTime();
        try {
            if (pboApi != PBO_API_NONE && pboApi != PBO_API_UNDETECTED) {
                bindBuffer(0);
            }
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, source.texture);
            setTightPackState();
            synchronousReadBuffer.clear();
            synchronousReadBuffer.limit(textureByteSize);
            clearGlErrors();
            GL11.glGetTexImage(
                GL11.GL_TEXTURE_2D,
                0,
                GL12.GL_BGR,
                GL11.GL_UNSIGNED_BYTE,
                synchronousReadBuffer);
            lastGlError = GL11.glGetError();
            if (lastGlError != GL11.GL_NO_ERROR) {
                throw new IllegalStateException(
                    "OpenGL texture read failed with error 0x"
                        + Integer.toHexString(lastGlError));
            }
        } catch (Throwable throwable) {
            RecordableMod.LOGGER.warn(
                "Minecraft framebuffer-texture readback failed; rotating to the next capture source.",
                throwable);
            rotateCaptureSource();
            return null;
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, packAlignment);
            GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, packRowLength);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, packSkipRows);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, packSkipPixels);
            GL11.glPixelStorei(GL11.GL_PACK_SWAP_BYTES, packSwapBytes);
            GL11.glPixelStorei(GL11.GL_PACK_LSB_FIRST, packLsbFirst);
            if (pboApi != PBO_API_NONE && pboApi != PBO_API_UNDETECTED) {
                bindBuffer(previousPackBuffer);
            }
        }

        copyDirectBufferToScratch(synchronousReadBuffer, textureByteSize);
        return buildFrameFromScratch(
            source.width,
            source.height,
            source.textureWidth,
            capturedAtNanos);
    }

    private void initializePbos(int newSourceWidth, int newSourceHeight) {
        deletePbosSafely();

        pboSourceWidth = newSourceWidth;
        pboSourceHeight = newSourceHeight;
        pboSourceByteSize = checkedByteSize(newSourceWidth, newSourceHeight);
        pboWriteIndex = 0;
        pboCaptureNanos[0] = 0L;
        pboCaptureNanos[1] = 0L;
        hasPendingPboFrame = false;

        int previousBinding = getBoundPackBuffer();
        try {
            for (int index = 0; index < pboIds.length; index++) {
                pboIds[index] = generateBuffer();
                bindBuffer(pboIds[index]);
                allocateBuffer(pboSourceByteSize);
            }
        } finally {
            bindBuffer(previousBinding);
        }
    }

    private void submitAsyncRead(SourceInfo source) {
        ReadbackState state = ReadbackState.capture(
            source.manageFramebufferBinding,
            true,
            this);
        try {
            state.bindSource(source);
            setTightPackState();
            bindBuffer(pboIds[pboWriteIndex]);

            /*
             * Orphan the old store before each read so the driver can hand us
             * fresh storage rather than waiting for the old mapping.
             */
            allocateBuffer(pboSourceByteSize);
            clearGlErrors();
            pboCaptureNanos[pboWriteIndex] = System.nanoTime();
            GL11.glReadPixels(
                0,
                0,
                source.width,
                source.height,
                GL12.GL_BGR,
                GL11.GL_UNSIGNED_BYTE,
                0L);
            lastGlError = GL11.glGetError();
            if (lastGlError != GL11.GL_NO_ERROR) {
                throw new IllegalStateException(
                    "OpenGL PBO read failed with error 0x"
                        + Integer.toHexString(lastGlError));
            }

            pboWriteIndex = (pboWriteIndex + 1) % PBO_COUNT;
            hasPendingPboFrame = true;
        } finally {
            state.restore(this);
        }
    }

    private CapturedFrame mapPendingPboFrame() {
        int readIndex = (pboWriteIndex + 1) % PBO_COUNT;
        long capturedAtNanos = pboCaptureNanos[readIndex];
        int previousBinding = getBoundPackBuffer();
        ByteBuffer mapped = null;
        boolean needsUnmap = false;
        boolean mappingValid = false;
        try {
            bindBuffer(pboIds[readIndex]);
            mapped = mapBoundBuffer(pboSourceByteSize);
            if (mapped == null) {
                return null;
            }
            needsUnmap = true;
            copyDirectBufferToScratch(mapped, pboSourceByteSize);
            mappingValid = unmapBoundBuffer();
            needsUnmap = false;
        } finally {
            if (needsUnmap) {
                try {
                    unmapBoundBuffer();
                } catch (Throwable ignored) {
                    // The primary capture path will count this as a map failure.
                }
            }
            bindBuffer(previousBinding);
        }

        if (!mappingValid) {
            return null;
        }
        return buildFrameFromScratch(
            pboSourceWidth,
            pboSourceHeight,
            pboSourceWidth,
            capturedAtNanos > 0L
                ? capturedAtNanos
                : System.nanoTime());
    }

    private CapturedFrame buildFrameFromScratch(
            int nativeWidth,
            int nativeHeight,
            int sourceStrideWidth,
            long capturedAtNanos) {
        byte[] target = pool.acquire();
        copyFlipScaleBgrToRgb(
            rawConvertScratch,
            nativeWidth,
            nativeHeight,
            sourceStrideWidth,
            target,
            outputWidth,
            outputHeight);
        evaluateFrame(target);
        return new CapturedFrame(
            target,
            outputWidth,
            outputHeight,
            capturedAtNanos,
            pool);
    }

    private void copyDirectBufferToScratch(ByteBuffer source, int byteSize) {
        if (rawConvertScratch == null || rawConvertScratch.length < byteSize) {
            rawConvertScratch = new byte[byteSize];
        }
        int oldPosition = source.position();
        int oldLimit = source.limit();
        try {
            source.position(0);
            source.limit(byteSize);
            source.get(rawConvertScratch, 0, byteSize);
        } finally {
            source.limit(oldLimit);
            source.position(Math.min(oldPosition, oldLimit));
        }
    }

    /**
     * Nearest-neighbour fixed-point scale plus the two required transforms:
     * OpenGL bottom-up to video top-down, and BGR to RGB.
     */
    private static void copyFlipScaleBgrToRgb(
            byte[] sourceBottomUpBgr,
            int nativeWidth,
            int nativeHeight,
            int sourceStrideWidth,
            byte[] targetTopDownRgb,
            int targetWidth,
            int targetHeight) {
        int sourceStride = sourceStrideWidth * BYTES_PER_PIXEL;
        long scaleX = ((long) nativeWidth << 16) / targetWidth;
        long scaleY = ((long) nativeHeight << 16) / targetHeight;

        for (int targetY = 0; targetY < targetHeight; targetY++) {
            int sourceYTopDown = (int) ((targetY * scaleY) >> 16);
            sourceYTopDown = Math.min(nativeHeight - 1, sourceYTopDown);
            int sourceYBottomUp = nativeHeight - 1 - sourceYTopDown;
            int sourceRow = sourceYBottomUp * sourceStride;
            int targetRow = targetY * targetWidth * BYTES_PER_PIXEL;

            for (int targetX = 0; targetX < targetWidth; targetX++) {
                int sourceX = (int) ((targetX * scaleX) >> 16);
                sourceX = Math.min(nativeWidth - 1, sourceX);
                int sourceIndex = sourceRow + sourceX * BYTES_PER_PIXEL;
                int targetIndex = targetRow + targetX * BYTES_PER_PIXEL;

                targetTopDownRgb[targetIndex] = sourceBottomUpBgr[sourceIndex + 2];
                targetTopDownRgb[targetIndex + 1] = sourceBottomUpBgr[sourceIndex + 1];
                targetTopDownRgb[targetIndex + 2] = sourceBottomUpBgr[sourceIndex];
            }
        }
    }

    private void evaluateFrame(byte[] rgb) {
        totalFramesProduced++;
        lastAverageBrightness = FrameValidator.averageBrightness(rgb, outputWidth, outputHeight);
        lastBlackPixelRatio = FrameValidator.blackPixelRatio(rgb, outputWidth, outputHeight);

        if (lastBlackPixelRatio < FrameValidator.BLACK_FRAME_RATIO) {
            consecutiveBlackFrames = 0;
            return;
        }

        totalBlackFrames++;
        consecutiveBlackFrames++;
        if (consecutiveBlackFrames == BLACK_WARNING_THRESHOLD) {
            RecordableMod.LOGGER.warn(
                "Screen capture has produced {} consecutive near-black frames "
                    + "(source {}x{}, framebuffer {}, black sample ratio {}). "
                    + "A shader, OptiFine Fast Render, or GPU driver may be intercepting the main framebuffer.",
                Integer.valueOf(consecutiveBlackFrames),
                Integer.valueOf(sourceWidth),
                Integer.valueOf(sourceHeight),
                Integer.valueOf(sourceFramebuffer),
                Double.valueOf(lastBlackPixelRatio));
            rotateCaptureSource();
        }
    }

    private void rotateCaptureSource() {
        int previous = sourceMode;
        sourceMode = (sourceMode + 1) % SOURCE_COUNT;
        consecutiveBlackFrames = 0;
        hasPendingPboFrame = false;
        RecordableMod.LOGGER.warn(
            "Rotating capture source from {} to {} after persistent black frames.",
            sourceName(previous),
            sourceName(sourceMode));
    }

    private SourceInfo resolveSource() {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft == null) {
            return null;
        }

        Framebuffer framebuffer = minecraft.getFramebuffer();
        boolean mainFboEnabled = OpenGlHelper.isFramebufferEnabled()
            && framebuffer != null
            && framebuffer.framebufferObject > 0;

        if (mainFboEnabled && sourceMode != SOURCE_BACK_BUFFER) {
            int textureWidth = positiveOr(
                framebuffer.framebufferTextureWidth,
                framebuffer.framebufferWidth);
            int textureHeight = positiveOr(
                framebuffer.framebufferTextureHeight,
                framebuffer.framebufferHeight);

            /*
             * Read only the portion Minecraft actually renders into. FBO
             * backing textures can be larger than the visible framebuffer;
             * reading/scaling the allocation would introduce padded pixels.
             */
            int visibleWidth = Math.min(
                positiveOr(framebuffer.framebufferWidth, minecraft.displayWidth),
                textureWidth);
            int visibleHeight = Math.min(
                positiveOr(framebuffer.framebufferHeight, minecraft.displayHeight),
                textureHeight);
            if (visibleWidth <= 0 || visibleHeight <= 0) {
                return null;
            }

            boolean textureReadback = sourceMode == SOURCE_MAIN_TEXTURE
                && framebuffer.framebufferTexture > 0;
            return new SourceInfo(
                visibleWidth,
                visibleHeight,
                textureWidth,
                textureHeight,
                framebuffer.framebufferObject,
                OpenGlHelper.GL_COLOR_ATTACHMENT0,
                true,
                textureReadback,
                framebuffer.framebufferTexture);
        }

        int displayWidth = minecraft.displayWidth;
        int displayHeight = minecraft.displayHeight;
        if (displayWidth <= 0 || displayHeight <= 0) {
            return null;
        }
        return new SourceInfo(
            displayWidth,
            displayHeight,
            displayWidth,
            displayHeight,
            0,
            GL11.GL_BACK,
            canManageFramebufferBinding(),
            false,
            0);
    }

    private void recordSourceInfo(SourceInfo source) {
        sourceWidth = source.width;
        sourceHeight = source.height;
        backingTextureWidth = source.textureWidth;
        backingTextureHeight = source.textureHeight;
        sourceFramebuffer = source.framebuffer;
    }

    private static int positiveOr(int candidate, int fallback) {
        return candidate > 0 ? candidate : fallback;
    }

    private static int makeEven(int value) {
        int safe = Math.max(2, value);
        return (safe & 1) == 0 ? safe : safe - 1;
    }

    private static int checkedByteSize(int width, int height) {
        long requested = width * (long) height * BYTES_PER_PIXEL;
        if (width <= 0 || height <= 0 || requested > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                "Framebuffer is too large to capture: " + width + "x" + height);
        }
        return (int) requested;
    }

    private static String sourceName(int mode) {
        switch (mode) {
            case SOURCE_BACK_BUFFER:
                return "BACK_BUFFER";
            case SOURCE_MAIN_TEXTURE:
                return "MAIN_TEXTURE";
            case SOURCE_MAIN_FRAMEBUFFER:
            default:
                return "MAIN_FBO";
        }
    }

    private void ensureSynchronousReadBuffer(int byteSize) {
        if (synchronousReadBuffer == null || synchronousReadBuffer.capacity() < byteSize) {
            synchronousReadBuffer = BufferUtils.createByteBuffer(byteSize);
        }
    }

    private static boolean hasCurrentGlContext() {
        if (!Display.isCreated()) {
            return false;
        }
        try {
            GLContext.getCapabilities();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static int detectPboApi() {
        try {
            ContextCapabilities capabilities = GLContext.getCapabilities();
            if (capabilities.OpenGL21) {
                return PBO_API_CORE;
            }
            if (capabilities.GL_ARB_pixel_buffer_object) {
                return PBO_API_ARB;
            }
        } catch (Throwable ignored) {
        }
        return PBO_API_NONE;
    }

    private static boolean canManageFramebufferBinding() {
        try {
            ContextCapabilities capabilities = GLContext.getCapabilities();
            return capabilities.OpenGL30
                || capabilities.GL_ARB_framebuffer_object
                || capabilities.GL_EXT_framebuffer_object;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private int generateBuffer() {
        return pboApi == PBO_API_CORE
            ? GL15.glGenBuffers()
            : ARBBufferObject.glGenBuffersARB();
    }

    private void bindBuffer(int buffer) {
        if (pboApi == PBO_API_CORE) {
            GL15.glBindBuffer(GL_PIXEL_PACK_BUFFER_VALUE, buffer);
        } else if (pboApi == PBO_API_ARB) {
            ARBBufferObject.glBindBufferARB(GL_PIXEL_PACK_BUFFER_VALUE, buffer);
        }
    }

    private void allocateBuffer(int byteSize) {
        if (pboApi == PBO_API_CORE) {
            GL15.glBufferData(
                GL_PIXEL_PACK_BUFFER_VALUE,
                (long) byteSize,
                GL_STREAM_READ_VALUE);
        } else {
            ARBBufferObject.glBufferDataARB(
                GL_PIXEL_PACK_BUFFER_VALUE,
                (long) byteSize,
                GL_STREAM_READ_VALUE);
        }
    }

    private ByteBuffer mapBoundBuffer(int byteSize) {
        if (pboApi == PBO_API_CORE) {
            return GL15.glMapBuffer(
                GL_PIXEL_PACK_BUFFER_VALUE,
                GL_READ_ONLY_VALUE,
                (long) byteSize,
                null);
        }
        return ARBBufferObject.glMapBufferARB(
            GL_PIXEL_PACK_BUFFER_VALUE,
            GL_READ_ONLY_VALUE,
            (long) byteSize,
            null);
    }

    private boolean unmapBoundBuffer() {
        return pboApi == PBO_API_CORE
            ? GL15.glUnmapBuffer(GL_PIXEL_PACK_BUFFER_VALUE)
            : ARBBufferObject.glUnmapBufferARB(GL_PIXEL_PACK_BUFFER_VALUE);
    }

    private int getBoundPackBuffer() {
        if (pboApi == PBO_API_NONE || pboApi == PBO_API_UNDETECTED) {
            return 0;
        }
        return GL11.glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING_VALUE);
    }

    private void deleteBuffer(int buffer) {
        if (pboApi == PBO_API_CORE) {
            GL15.glDeleteBuffers(buffer);
        } else if (pboApi == PBO_API_ARB) {
            ARBBufferObject.glDeleteBuffersARB(buffer);
        }
    }

    private void disablePboReadback() {
        deletePbosSafely();
        pboReadbackDisabled = true;
        hasPendingPboFrame = false;
    }

    private void deletePbosSafely() {
        boolean hasBuffer = pboIds[0] != 0 || pboIds[1] != 0;
        if (!hasBuffer) {
            hasPendingPboFrame = false;
            return;
        }
        if (!hasCurrentGlContext()
                || pboApi == PBO_API_NONE
                || pboApi == PBO_API_UNDETECTED) {
            pboIds[0] = 0;
            pboIds[1] = 0;
            hasPendingPboFrame = false;
            return;
        }

        int previousBinding = 0;
        int firstDeletedId = pboIds[0];
        int secondDeletedId = pboIds[1];
        try {
            previousBinding = getBoundPackBuffer();
            for (int index = 0; index < pboIds.length; index++) {
                if (pboIds[index] != 0) {
                    deleteBuffer(pboIds[index]);
                    pboIds[index] = 0;
                }
            }
        } catch (Throwable throwable) {
            RecordableMod.LOGGER.debug("Failed to delete screen-capture PBOs.", throwable);
            pboIds[0] = 0;
            pboIds[1] = 0;
        } finally {
            /*
             * Never restore a name we just deleted. Our PBOs should not be
             * externally bound, but this makes shutdown/reprepare defensive.
             */
            if (previousBinding != firstDeletedId && previousBinding != secondDeletedId) {
                try {
                    bindBuffer(previousBinding);
                } catch (Throwable ignored) {
                }
            }
            hasPendingPboFrame = false;
        }
    }

    private static void setTightPackState() {
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, 0);
        GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, 0);
        GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, 0);
        GL11.glPixelStorei(GL11.GL_PACK_SWAP_BYTES, GL11.GL_FALSE);
        GL11.glPixelStorei(GL11.GL_PACK_LSB_FIRST, GL11.GL_FALSE);
    }

    private static void clearGlErrors() {
        for (int count = 0; count < 16; count++) {
            if (GL11.glGetError() == GL11.GL_NO_ERROR) {
                return;
            }
        }
    }

    public synchronized int getOutputWidth() {
        return outputWidth;
    }

    public synchronized int getOutputHeight() {
        return outputHeight;
    }

    public synchronized int getSourceWidth() {
        return sourceWidth;
    }

    public synchronized int getSourceHeight() {
        return sourceHeight;
    }

    public synchronized int getBackingTextureWidth() {
        return backingTextureWidth;
    }

    public synchronized int getBackingTextureHeight() {
        return backingTextureHeight;
    }

    public synchronized boolean isCroppingBackingTexture() {
        return sourceWidth > 0
            && sourceHeight > 0
            && (sourceWidth != backingTextureWidth || sourceHeight != backingTextureHeight);
    }

    public synchronized boolean isUsingPbos() {
        return prepared
            && !pboReadbackDisabled
            && (pboApi == PBO_API_CORE || pboApi == PBO_API_ARB);
    }

    public synchronized int getConsecutivePboMapFailures() {
        return consecutivePboMapFailures;
    }

    public synchronized long getTotalFramesProduced() {
        return totalFramesProduced;
    }

    public synchronized long getTotalBlackFrames() {
        return totalBlackFrames;
    }

    public synchronized int getConsecutiveBlackFrames() {
        return consecutiveBlackFrames;
    }

    public synchronized double getLastAverageBrightness() {
        return lastAverageBrightness;
    }

    public synchronized double getLastBlackPixelRatio() {
        return lastBlackPixelRatio;
    }

    public synchronized boolean isPersistentlyBlack() {
        return consecutiveBlackFrames >= BLACK_WARNING_THRESHOLD;
    }

    public synchronized int getLastGlError() {
        return lastGlError;
    }

    public synchronized String getReadSourceName() {
        return sourceName(sourceMode);
    }

    public synchronized void close() {
        deletePbosSafely();
        synchronousReadBuffer = null;
        rawConvertScratch = null;
        pool = null;
        prepared = false;
        outputWidth = 0;
        outputHeight = 0;
        outputByteSize = 0;
        sourceWidth = 0;
        sourceHeight = 0;
        backingTextureWidth = 0;
        backingTextureHeight = 0;
        sourceFramebuffer = 0;
    }

    private static final class SourceInfo {
        final int width;
        final int height;
        final int textureWidth;
        final int textureHeight;
        final int framebuffer;
        final int readBuffer;
        final boolean manageFramebufferBinding;
        final boolean textureReadback;
        final int texture;

        SourceInfo(
                int width,
                int height,
                int textureWidth,
                int textureHeight,
                int framebuffer,
                int readBuffer,
                boolean manageFramebufferBinding,
                boolean textureReadback,
                int texture) {
            this.width = width;
            this.height = height;
            this.textureWidth = textureWidth;
            this.textureHeight = textureHeight;
            this.framebuffer = framebuffer;
            this.readBuffer = readBuffer;
            this.manageFramebufferBinding = manageFramebufferBinding;
            this.textureReadback = textureReadback;
            this.texture = texture;
        }
    }

    /**
     * Snapshot of every GL readback state value this class changes.
     */
    private static final class ReadbackState {
        final boolean manageFramebuffer;
        final boolean managePackBuffer;
        final int previousFramebuffer;
        final int previousReadBuffer;
        final int previousPackBuffer;
        final int packAlignment;
        final int packRowLength;
        final int packSkipRows;
        final int packSkipPixels;
        final int packSwapBytes;
        final int packLsbFirst;

        int sourceReadBufferBefore;
        boolean sourceBound;

        private ReadbackState(
                boolean manageFramebuffer,
                boolean managePackBuffer,
                int previousFramebuffer,
                int previousReadBuffer,
                int previousPackBuffer,
                int packAlignment,
                int packRowLength,
                int packSkipRows,
                int packSkipPixels,
                int packSwapBytes,
                int packLsbFirst) {
            this.manageFramebuffer = manageFramebuffer;
            this.managePackBuffer = managePackBuffer;
            this.previousFramebuffer = previousFramebuffer;
            this.previousReadBuffer = previousReadBuffer;
            this.previousPackBuffer = previousPackBuffer;
            this.packAlignment = packAlignment;
            this.packRowLength = packRowLength;
            this.packSkipRows = packSkipRows;
            this.packSkipPixels = packSkipPixels;
            this.packSwapBytes = packSwapBytes;
            this.packLsbFirst = packLsbFirst;
        }

        static ReadbackState capture(
                boolean manageFramebuffer,
                boolean managePackBuffer,
                ScreenCapture owner) {
            return new ReadbackState(
                manageFramebuffer,
                managePackBuffer,
                manageFramebuffer
                    ? GL11.glGetInteger(GL_FRAMEBUFFER_BINDING_VALUE)
                    : 0,
                GL11.glGetInteger(GL11.GL_READ_BUFFER),
                managePackBuffer ? owner.getBoundPackBuffer() : 0,
                GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT),
                GL11.glGetInteger(GL11.GL_PACK_ROW_LENGTH),
                GL11.glGetInteger(GL11.GL_PACK_SKIP_ROWS),
                GL11.glGetInteger(GL11.GL_PACK_SKIP_PIXELS),
                GL11.glGetInteger(GL11.GL_PACK_SWAP_BYTES),
                GL11.glGetInteger(GL11.GL_PACK_LSB_FIRST));
        }

        void bindSource(SourceInfo source) {
            if (manageFramebuffer && source.framebuffer != previousFramebuffer) {
                OpenGlHelper.glBindFramebuffer(
                    OpenGlHelper.GL_FRAMEBUFFER,
                    source.framebuffer);
                sourceBound = true;
                sourceReadBufferBefore = GL11.glGetInteger(GL11.GL_READ_BUFFER);
            } else {
                sourceReadBufferBefore = previousReadBuffer;
            }
            GL11.glReadBuffer(source.readBuffer);
        }

        void restore(ScreenCapture owner) {
            try {
                if (managePackBuffer) {
                    owner.bindBuffer(previousPackBuffer);
                }
            } finally {
                GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, packAlignment);
                GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, packRowLength);
                GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, packSkipRows);
                GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, packSkipPixels);
                GL11.glPixelStorei(GL11.GL_PACK_SWAP_BYTES, packSwapBytes);
                GL11.glPixelStorei(GL11.GL_PACK_LSB_FIRST, packLsbFirst);

                if (sourceBound) {
                    GL11.glReadBuffer(sourceReadBufferBefore);
                    OpenGlHelper.glBindFramebuffer(
                        OpenGlHelper.GL_FRAMEBUFFER,
                        previousFramebuffer);
                }
                GL11.glReadBuffer(previousReadBuffer);
            }
        }
    }
}
