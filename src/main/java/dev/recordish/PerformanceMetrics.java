package dev.recordish;

import net.minecraft.client.Minecraft;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lightweight rolling recording/performance metrics.
 *
 * <p>No metrics backend is loaded and no allocation occurs on the hot frame
 * path beyond the optional, once-per-second manager sample.</p>
 */
public final class PerformanceMetrics {
    private static final PerformanceMetrics INSTANCE = new PerformanceMetrics();
    private static final long RATE_WINDOW_NANOS = 5_000_000_000L;
    private static final int LATENCY_WINDOW_SIZE = 120;

    private final AtomicLong framesCaptured = new AtomicLong();
    private final AtomicLong framesDropped = new AtomicLong();
    private final AtomicLong framesEncoded = new AtomicLong();
    private final AtomicLong adaptiveDrops = new AtomicLong();
    private final AtomicLong queueSize = new AtomicLong();
    private final AtomicLong queueCapacity = new AtomicLong(1L);
    private final AtomicLong memoryUsedMiB = new AtomicLong();
    private final AtomicLong fileSizeBytes = new AtomicLong();
    private final AtomicLong gameFps = new AtomicLong();

    private final Object latencyLock = new Object();
    private final long[] captureLatencyNanos =
            new long[LATENCY_WINDOW_SIZE];
    private final long[] encodeLatencyNanos =
            new long[LATENCY_WINDOW_SIZE];
    private int captureLatencyIndex;
    private int captureLatencyCount;
    private long captureLatencySum;
    private int encodeLatencyIndex;
    private int encodeLatencyCount;
    private long encodeLatencySum;

    private final Object rateLock = new Object();
    private final Deque<RateSample> rateSamples =
            new ArrayDeque<RateSample>();
    private long lastSampleNanos;
    private long lastManagerCaptured;
    private long lastManagerDropped;
    private int tickCounter;

    private volatile double averageCaptureLatencyMs;
    private volatile double averageEncodeLatencyMs;
    private volatile double rollingCaptureFps;
    private volatile double rollingEncoderFps;
    private volatile double rollingDropFps;
    private volatile double bufferHealthPercent = 100.0D;
    private volatile RecordingManager.QueueHealth queueHealth =
            RecordingManager.QueueHealth.OK;

    private PerformanceMetrics() {
    }

    public static PerformanceMetrics getInstance() {
        return INSTANCE;
    }

    /**
     * Cheap client-tick hook. The manager is sampled once per second.
     */
    public void onClientTick() {
        tickCounter++;
        if (tickCounter < 20) {
            return;
        }
        tickCounter = 0;
        updateGameFps(Minecraft.getDebugFPS());
        sampleFromManager(RecordingManager.getInstance());
    }

    public void recordFrameCapture(long durationNanos) {
        framesCaptured.incrementAndGet();
        if (durationNanos >= 0L) {
            addCaptureLatency(durationNanos);
        }
    }

    public void recordFrameEncode(long durationNanos) {
        framesEncoded.incrementAndGet();
        if (durationNanos >= 0L) {
            addEncodeLatency(durationNanos);
        }
    }

    public void recordFrameDrop() {
        framesDropped.incrementAndGet();
    }

    public void recordAdaptiveDrop() {
        adaptiveDrops.incrementAndGet();
    }

    public void updateQueueStats(int size, int capacity) {
        int safeCapacity = Math.max(1, capacity);
        int safeSize = Math.max(0, Math.min(size, safeCapacity));
        queueSize.set(safeSize);
        queueCapacity.set(safeCapacity);
        bufferHealthPercent = Math.max(
                0.0D,
                100.0D - safeSize * 100.0D / safeCapacity);
        double ratio = safeSize / (double) safeCapacity;
        queueHealth = ratio >= 0.9D
                ? RecordingManager.QueueHealth.CRITICAL
                : ratio >= 0.5D
                        ? RecordingManager.QueueHealth.SLOW
                        : RecordingManager.QueueHealth.OK;
    }

    /**
     * Compatibility update for callers that already calculate capture/encode
     * rates. Values are folded into the rolling display values.
     */
    public void updateFps(long captureFps, long encodeFps) {
        rollingCaptureFps = Math.max(0.0D, captureFps);
        rollingEncoderFps = Math.max(0.0D, encodeFps);
    }

    public void updateGameFps(long fps) {
        gameFps.set(Math.max(0L, fps));
    }

    public void updateMemory(long usedMiB) {
        memoryUsedMiB.set(Math.max(0L, usedMiB));
    }

    public void updateFileSize(long bytes) {
        fileSizeBytes.set(Math.max(0L, bytes));
    }

    /**
     * Pulls a low-frequency snapshot from the current recording manager and
     * computes a five-second rolling capture/drop rate.
     */
    public void sampleFromManager(RecordingManager manager) {
        if (manager == null) {
            return;
        }

        long now = System.nanoTime();
        long captured = Math.max(0L, manager.getCapturedFrames());
        long dropped = Math.max(0L, manager.getDroppedFrames());
        framesCaptured.set(captured);
        framesDropped.set(dropped);
        double encoderEstimateNow = Math.max(
                0.0D,
                manager.getEncoderFpsEstimate());
        framesEncoded.set(Math.max(
                framesEncoded.get(),
                Math.round(
                        encoderEstimateNow
                                * manager.getEffectiveRecordingMillis()
                                / 1000.0D)));
        updateMemory(manager.getUsedMemoryMiB());
        updateFileSize(manager.getCurrentFileSizeBytes());
        queueHealth = manager.getQueueHealth();
        if (queueHealth == RecordingManager.QueueHealth.CRITICAL) {
            bufferHealthPercent = 10.0D;
        } else if (queueHealth == RecordingManager.QueueHealth.SLOW) {
            bufferHealthPercent = 45.0D;
        } else {
            bufferHealthPercent = 100.0D;
        }

        synchronized (rateLock) {
            if (lastSampleNanos == 0L
                    || captured < lastManagerCaptured
                    || dropped < lastManagerDropped) {
                rateSamples.clear();
                lastSampleNanos = now;
                lastManagerCaptured = captured;
                lastManagerDropped = dropped;
                rollingCaptureFps = manager.isActiveOrStopping()
                        ? Math.max(0.0D, manager.getCaptureFpsEstimate())
                        : 0.0D;
                rollingEncoderFps = manager.isActiveOrStopping()
                        ? Math.max(0.0D, manager.getEncoderFpsEstimate())
                        : 0.0D;
                rollingDropFps = 0.0D;
                return;
            }

            long duration = Math.max(1L, now - lastSampleNanos);
            long capturedDelta = Math.max(
                    0L,
                    captured - lastManagerCaptured);
            long droppedDelta = Math.max(
                    0L,
                    dropped - lastManagerDropped);
            rateSamples.addLast(new RateSample(
                    now,
                    duration,
                    capturedDelta,
                    droppedDelta));
            lastSampleNanos = now;
            lastManagerCaptured = captured;
            lastManagerDropped = dropped;

            while (!rateSamples.isEmpty()
                    && now - rateSamples.peekFirst().endedAtNanos
                            > RATE_WINDOW_NANOS) {
                rateSamples.removeFirst();
            }

            long totalDuration = 0L;
            long totalCaptured = 0L;
            long totalDropped = 0L;
            for (RateSample sample : rateSamples) {
                totalDuration += sample.durationNanos;
                totalCaptured += sample.captured;
                totalDropped += sample.dropped;
            }
            if (totalDuration > 0L) {
                rollingCaptureFps = totalCaptured
                        * 1_000_000_000.0D / totalDuration;
                rollingDropFps = totalDropped
                        * 1_000_000_000.0D / totalDuration;
            }

            double encoderEstimate = manager.isActiveOrStopping()
                    ? encoderEstimateNow
                    : 0.0D;
            rollingEncoderFps = rollingEncoderFps <= 0.0D
                    ? encoderEstimate
                    : rollingEncoderFps * 0.65D
                            + encoderEstimate * 0.35D;
        }
    }

    public void reset() {
        framesCaptured.set(0L);
        framesDropped.set(0L);
        framesEncoded.set(0L);
        adaptiveDrops.set(0L);
        queueSize.set(0L);
        queueCapacity.set(1L);
        memoryUsedMiB.set(0L);
        fileSizeBytes.set(0L);
        gameFps.set(0L);
        averageCaptureLatencyMs = 0.0D;
        averageEncodeLatencyMs = 0.0D;
        rollingCaptureFps = 0.0D;
        rollingEncoderFps = 0.0D;
        rollingDropFps = 0.0D;
        bufferHealthPercent = 100.0D;
        queueHealth = RecordingManager.QueueHealth.OK;
        tickCounter = 0;

        synchronized (latencyLock) {
            captureLatencyIndex = 0;
            captureLatencyCount = 0;
            captureLatencySum = 0L;
            encodeLatencyIndex = 0;
            encodeLatencyCount = 0;
            encodeLatencySum = 0L;
        }
        synchronized (rateLock) {
            rateSamples.clear();
            lastSampleNanos = 0L;
            lastManagerCaptured = 0L;
            lastManagerDropped = 0L;
        }
    }

    public Snapshot snapshot() {
        return new Snapshot(
                framesCaptured.get(),
                framesDropped.get(),
                framesEncoded.get(),
                adaptiveDrops.get(),
                averageCaptureLatencyMs,
                averageEncodeLatencyMs,
                rollingCaptureFps,
                rollingEncoderFps,
                rollingDropFps,
                bufferHealthPercent,
                queueSize.get(),
                queueCapacity.get(),
                queueHealth,
                memoryUsedMiB.get(),
                fileSizeBytes.get(),
                gameFps.get());
    }

    private void addCaptureLatency(long durationNanos) {
        synchronized (latencyLock) {
            if (captureLatencyCount == LATENCY_WINDOW_SIZE) {
                captureLatencySum -=
                        captureLatencyNanos[captureLatencyIndex];
            } else {
                captureLatencyCount++;
            }
            captureLatencyNanos[captureLatencyIndex] = durationNanos;
            captureLatencySum += durationNanos;
            captureLatencyIndex =
                    (captureLatencyIndex + 1) % LATENCY_WINDOW_SIZE;
            averageCaptureLatencyMs = captureLatencyCount == 0
                    ? 0.0D
                    : captureLatencySum
                            / (double) captureLatencyCount
                            / 1_000_000.0D;
        }
    }

    private void addEncodeLatency(long durationNanos) {
        synchronized (latencyLock) {
            if (encodeLatencyCount == LATENCY_WINDOW_SIZE) {
                encodeLatencySum -=
                        encodeLatencyNanos[encodeLatencyIndex];
            } else {
                encodeLatencyCount++;
            }
            encodeLatencyNanos[encodeLatencyIndex] = durationNanos;
            encodeLatencySum += durationNanos;
            encodeLatencyIndex =
                    (encodeLatencyIndex + 1) % LATENCY_WINDOW_SIZE;
            averageEncodeLatencyMs = encodeLatencyCount == 0
                    ? 0.0D
                    : encodeLatencySum
                            / (double) encodeLatencyCount
                            / 1_000_000.0D;
        }
    }

    public double getAvgCaptureLatencyMs() {
        return averageCaptureLatencyMs;
    }

    public double getAvgEncodeLatencyMs() {
        return averageEncodeLatencyMs;
    }

    public double getBufferHealthPercent() {
        return bufferHealthPercent;
    }

    public long getTotalCaptured() {
        return framesCaptured.get();
    }

    public long getTotalDropped() {
        return framesDropped.get();
    }

    public long getTotalEncoded() {
        return framesEncoded.get();
    }

    public long getTotalAdaptiveDrops() {
        return adaptiveDrops.get();
    }

    public long getAdaptiveDrops() {
        return adaptiveDrops.get();
    }

    public long getCaptureFps() {
        return Math.round(rollingCaptureFps);
    }

    public long getEncoderFps() {
        return Math.round(rollingEncoderFps);
    }

    public double getRollingCaptureFps() {
        return rollingCaptureFps;
    }

    public double getRollingEncoderFps() {
        return rollingEncoderFps;
    }

    public double getRollingDropFps() {
        return rollingDropFps;
    }

    public long getMemoryUsedMiB() {
        return memoryUsedMiB.get();
    }

    public long getFileSizeBytes() {
        return fileSizeBytes.get();
    }

    public long getQueueSize() {
        return queueSize.get();
    }

    public long getQueueCapacity() {
        return queueCapacity.get();
    }

    public RecordingManager.QueueHealth getQueueHealth() {
        return queueHealth;
    }

    public long getGameFps() {
        return gameFps.get();
    }

    public String getCompactSummary() {
        return String.format(
                Locale.ROOT,
                "Cap %.1f FPS / %.1fms | Enc %.1f FPS / %.1fms | Q %.0f%%",
                rollingCaptureFps,
                averageCaptureLatencyMs,
                rollingEncoderFps,
                averageEncodeLatencyMs,
                bufferHealthPercent);
    }

    public static final class Snapshot {
        private final long framesCaptured;
        private final long framesDropped;
        private final long framesEncoded;
        private final long adaptiveDrops;
        private final double captureLatencyMs;
        private final double encodeLatencyMs;
        private final double captureFps;
        private final double encoderFps;
        private final double dropFps;
        private final double queueHealthPercent;
        private final long queueSize;
        private final long queueCapacity;
        private final RecordingManager.QueueHealth queueHealth;
        private final long memoryUsedMiB;
        private final long fileSizeBytes;
        private final long gameFps;

        Snapshot(
                long framesCaptured,
                long framesDropped,
                long framesEncoded,
                long adaptiveDrops,
                double captureLatencyMs,
                double encodeLatencyMs,
                double captureFps,
                double encoderFps,
                double dropFps,
                double queueHealthPercent,
                long queueSize,
                long queueCapacity,
                RecordingManager.QueueHealth queueHealth,
                long memoryUsedMiB,
                long fileSizeBytes,
                long gameFps) {
            this.framesCaptured = framesCaptured;
            this.framesDropped = framesDropped;
            this.framesEncoded = framesEncoded;
            this.adaptiveDrops = adaptiveDrops;
            this.captureLatencyMs = captureLatencyMs;
            this.encodeLatencyMs = encodeLatencyMs;
            this.captureFps = captureFps;
            this.encoderFps = encoderFps;
            this.dropFps = dropFps;
            this.queueHealthPercent = queueHealthPercent;
            this.queueSize = queueSize;
            this.queueCapacity = queueCapacity;
            this.queueHealth = queueHealth;
            this.memoryUsedMiB = memoryUsedMiB;
            this.fileSizeBytes = fileSizeBytes;
            this.gameFps = gameFps;
        }

        public long getFramesCaptured() { return framesCaptured; }
        public long getFramesDropped() { return framesDropped; }
        public long getFramesEncoded() { return framesEncoded; }
        public long getAdaptiveDrops() { return adaptiveDrops; }
        public double getCaptureLatencyMs() { return captureLatencyMs; }
        public double getEncodeLatencyMs() { return encodeLatencyMs; }
        public double getCaptureFps() { return captureFps; }
        public double getEncoderFps() { return encoderFps; }
        public double getDropFps() { return dropFps; }
        public double getQueueHealthPercent() { return queueHealthPercent; }
        public long getQueueSize() { return queueSize; }
        public long getQueueCapacity() { return queueCapacity; }
        public RecordingManager.QueueHealth getQueueHealth() { return queueHealth; }
        public long getMemoryUsedMiB() { return memoryUsedMiB; }
        public long getFileSizeBytes() { return fileSizeBytes; }
        public long getGameFps() { return gameFps; }
    }

    private static final class RateSample {
        final long endedAtNanos;
        final long durationNanos;
        final long captured;
        final long dropped;

        RateSample(
                long endedAtNanos,
                long durationNanos,
                long captured,
                long dropped) {
            this.endedAtNanos = endedAtNanos;
            this.durationNanos = durationNanos;
            this.captured = captured;
            this.dropped = dropped;
        }
    }
}
