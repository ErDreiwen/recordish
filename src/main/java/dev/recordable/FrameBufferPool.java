package dev.recordable;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded heap-frame pool that prevents a 60 FPS recording from allocating a
 * fresh multi-megabyte byte array on every render.
 */
public final class FrameBufferPool {
    private final ArrayBlockingQueue<byte[]> buffers;
    private final int bufferSize;
    private final int capacity;
    private final boolean enabled;
    private final AtomicLong overflowAllocations = new AtomicLong();

    public FrameBufferPool(int bufferSize, int capacity) {
        this(bufferSize, capacity, true);
    }

    public FrameBufferPool(int bufferSize, int capacity, boolean enabled) {
        this.bufferSize = Math.max(1, bufferSize);
        this.capacity = Math.max(2, capacity);
        this.enabled = enabled;
        this.buffers = new ArrayBlockingQueue<byte[]>(this.capacity);
        if (enabled) {
            for (int index = 0; index < this.capacity; index++) {
                this.buffers.offer(new byte[this.bufferSize]);
            }
        }
    }

    public byte[] acquire() {
        if (!enabled) {
            return new byte[bufferSize];
        }
        byte[] buffer = buffers.poll();
        if (buffer != null) {
            return buffer;
        }
        overflowAllocations.incrementAndGet();
        return new byte[bufferSize];
    }

    public void release(byte[] buffer) {
        if (enabled && buffer != null && buffer.length == bufferSize) {
            buffers.offer(buffer);
        }
    }

    public int getBufferSize() {
        return bufferSize;
    }

    public int getCapacity() {
        return capacity;
    }

    public int getAvailableCount() {
        return buffers.size();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public long getOverflowAllocations() {
        return overflowAllocations.get();
    }
}
