package dev.recordable;

/**
 * One packed, top-down RGB24 frame.
 *
 * <p>The backing array belongs to a {@link FrameBufferPool}. Consumers must
 * call {@link #release()} exactly once when they have finished with it. The
 * release operation is idempotent so an encoder shutdown race cannot put the
 * same array into the pool twice.</p>
 */
public final class CapturedFrame {
    private final byte[] pixels;
    private final int width;
    private final int height;
    private final long capturedAtNanos;
    private final FrameBufferPool owner;
    private boolean released;

    CapturedFrame(byte[] pixels, int width, int height, long capturedAtNanos, FrameBufferPool owner) {
        if (pixels == null) {
            throw new IllegalArgumentException("pixels");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Frame dimensions must be positive.");
        }
        if (owner == null) {
            throw new IllegalArgumentException("owner");
        }
        this.pixels = pixels;
        this.width = width;
        this.height = height;
        this.capturedAtNanos = capturedAtNanos;
        this.owner = owner;
    }

    public byte[] getPixels() {
        return pixels;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public long getCapturedAtNanos() {
        return capturedAtNanos;
    }

    public synchronized void release() {
        if (!released) {
            released = true;
            owner.release(pixels);
        }
    }

    public synchronized boolean isReleased() {
        return released;
    }
}
