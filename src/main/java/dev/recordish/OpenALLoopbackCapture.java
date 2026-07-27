package dev.recordish;

import com.sun.jna.Function;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import org.lwjgl.BufferUtils;
import org.lwjgl.LWJGLUtil;
import org.lwjgl.Sys;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.ALCcontext;
import org.lwjgl.openal.ALCdevice;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * LWJGL 2 bridge for OpenAL Soft's {@code ALC_SOFT_loopback} extension.
 *
 * <p>Minecraft 1.8.9's LWJGL binding predates this extension, so the three
 * extension functions are resolved from the already-loaded OpenAL library
 * through the JNA version shipped with Minecraft. Minecraft then mixes into a
 * software loopback device; this class sends the PCM to both the speakers and
 * Recordish without requiring Stereo Mix.</p>
 */
public final class OpenALLoopbackCapture {
    public static final int SAMPLE_RATE = 48000;
    public static final int CHANNELS = 2;
    public static final int BITS_PER_SAMPLE = 16;

    private static final int ALC_FORMAT_CHANNELS_SOFT = 0x1990;
    private static final int ALC_FORMAT_TYPE_SOFT = 0x1991;
    private static final int ALC_STEREO_SOFT = 0x1501;
    private static final int ALC_SHORT_SOFT = 0x1402;
    private static final int RENDER_INTERVAL_MS = 10;
    private static final int SAMPLES_PER_RENDER =
        SAMPLE_RATE * RENDER_INTERVAL_MS / 1000;
    private static final int PCM_FRAME_BYTES =
        CHANNELS * (BITS_PER_SAMPLE / 8);
    private static final int BYTES_PER_RENDER =
        SAMPLES_PER_RENDER * PCM_FRAME_BYTES;
    private static final double BYTES_PER_MILLISECOND =
        SAMPLE_RATE * PCM_FRAME_BYTES / 1000.0D;
    private static final long RENDER_STOP_TIMEOUT_MILLIS = 3000L;
    private static final long AUDIO_GAP_THRESHOLD_MILLIS = 25L;
    private static final long MAX_RECORDING_GAP_SILENCE_MILLIS = 30000L;
    private static final long MAX_ROLLING_RETENTION_MILLIS = 600000L;
    private static final byte[] SILENCE_BLOCK = new byte[8192];

    private static final OpenALLoopbackCapture INSTANCE =
        new OpenALLoopbackCapture();

    private final AtomicReference<RecordingSink> recording =
        new AtomicReference<RecordingSink>();
    private final Deque<TimedAudio> rollingAudio =
        new ArrayDeque<TimedAudio>();
    private final Object speakerLock = new Object();

    private volatile ALCdevice loopbackDevice;
    private volatile long loopbackDeviceAddress;
    private volatile Function renderSamplesFunction;
    private volatile boolean running;
    private volatile boolean rollingEnabled;
    private volatile long rollingRetentionMillis;
    private volatile Thread renderThread;
    private volatile SourceDataLine speakerLine;
    private long rollingLastEndMillis;

    private OpenALLoopbackCapture() {
    }

    public static OpenALLoopbackCapture getInstance() {
        return INSTANCE;
    }

    /**
     * Called by the AL mixin in place of LWJGL's normal device opener.
     * Returns {@code null} when loopback cannot be used so sound initialization
     * can continue on the ordinary hardware device.
     */
    public synchronized ALCdevice openLoopbackDevice() {
        if (loopbackDevice != null) return loopbackDevice;
        try {
            if (!ALC10.alcIsExtensionPresent(
                    null,
                    "ALC_SOFT_loopback")) {
                RecordishMod.LOGGER.info(
                    "OpenAL Soft loopback is unavailable; using the normal audio device.");
                return null;
            }

            NativeLibrary library = loadOpenAlLibrary();
            Function getProcAddress =
                library.getFunction("alcGetProcAddress");
            Function openDevice = extension(
                getProcAddress,
                "alcLoopbackOpenDeviceSOFT");
            Function formatSupported = extension(
                getProcAddress,
                "alcIsRenderFormatSupportedSOFT");
            Function renderSamples = extension(
                getProcAddress,
                "alcRenderSamplesSOFT");
            if (openDevice == null
                    || formatSupported == null
                    || renderSamples == null) {
                RecordishMod.LOGGER.warn(
                    "OpenAL reports ALC_SOFT_loopback but its functions could not be resolved.");
                return null;
            }

            Pointer rawDevice = openDevice.invokePointer(
                new Object[]{null});
            long address = Pointer.nativeValue(rawDevice);
            if (address == 0L) {
                RecordishMod.LOGGER.warn(
                    "alcLoopbackOpenDeviceSOFT returned no device.");
                return null;
            }

            int supported = formatSupported.invokeInt(new Object[]{
                rawDevice,
                Integer.valueOf(SAMPLE_RATE),
                Integer.valueOf(ALC_STEREO_SOFT),
                Integer.valueOf(ALC_SHORT_SOFT)
            });
            if (supported == ALC10.ALC_FALSE) {
                closeRawDevice(address);
                RecordishMod.LOGGER.warn(
                    "OpenAL loopback does not support 48 kHz stereo 16-bit PCM.");
                return null;
            }

            ALCdevice wrapped = wrapDevice(address);
            loopbackDevice = wrapped;
            loopbackDeviceAddress = address;
            renderSamplesFunction = renderSamples;
            RecordishMod.LOGGER.info(
                "OpenAL loopback device opened for direct Minecraft audio capture.");
            return wrapped;
        } catch (Throwable throwable) {
            RecordishMod.LOGGER.warn(
                "Unable to initialize OpenAL loopback; using the normal audio device.",
                throwable);
            loopbackDevice = null;
            loopbackDeviceAddress = 0L;
            renderSamplesFunction = null;
            return null;
        }
    }

    public boolean ownsDevice(ALCdevice device) {
        return device != null && device == loopbackDevice;
    }

    /**
     * LWJGL's own classes are excluded from LaunchWrapper transformation, so
     * the Paulscode mixin calls this immediately after ordinary AL creation.
     * Native stubs are then ready and the normal device can be atomically
     * replaced before Paulscode creates any sources.
     */
    public synchronized boolean replaceCurrentDevice() {
        if (ownsDevice(AL.getDevice())) {
            startRenderThread();
            return true;
        }

        ALCcontext oldContext = AL.getContext();
        ALCdevice oldDevice = AL.getDevice();
        try {
            ALC10.alcMakeContextCurrent(null);
            if (oldContext != null) {
                ALC10.alcDestroyContext(oldContext);
            }
            if (oldDevice != null) {
                ALC10.alcCloseDevice(oldDevice);
            }

            ALCdevice replacement = openLoopbackDevice();
            if (replacement == null) {
                restoreNormalDevice();
                return false;
            }
            ALCcontext context = ALC10.alcCreateContext(
                replacement,
                createContextAttributes());
            if (context == null
                    || ALC10.alcMakeContextCurrent(context)
                        == ALC10.ALC_FALSE) {
                throw new IOException(
                    "OpenAL rejected the loopback context.");
            }
            setAlState(replacement, context);
            startRenderThread();
            RecordishMod.LOGGER.info(
                "Minecraft audio is now routed through direct OpenAL loopback capture.");
            return true;
        } catch (Throwable throwable) {
            RecordishMod.LOGGER.warn(
                "Unable to replace Minecraft's OpenAL output with loopback; restoring normal sound.",
                throwable);
            try {
                ALCdevice replacement = loopbackDevice;
                beforeDeviceClose();
                if (replacement != null && replacement.isValid()) {
                    ALC10.alcCloseDevice(replacement);
                }
            } catch (Throwable ignored) {
            }
            try {
                restoreNormalDevice();
            } catch (Throwable restoreFailure) {
                RecordishMod.LOGGER.error(
                    "Unable to restore Minecraft's normal OpenAL device.",
                    restoreFailure);
            }
            return false;
        }
    }

    public IntBuffer createContextAttributes() {
        IntBuffer attributes = BufferUtils.createIntBuffer(7);
        attributes.put(ALC_FORMAT_CHANNELS_SOFT)
            .put(ALC_STEREO_SOFT)
            .put(ALC_FORMAT_TYPE_SOFT)
            .put(ALC_SHORT_SOFT)
            .put(ALC10.ALC_FREQUENCY)
            .put(SAMPLE_RATE)
            .put(0);
        attributes.flip();
        return attributes;
    }

    public synchronized void startRenderThread() {
        Thread current = renderThread;
        if ((running && current != null && current.isAlive())
                || loopbackDeviceAddress == 0L
                || renderSamplesFunction == null) {
            return;
        }
        Thread next = new Thread(new Runnable() {
            @Override
            public void run() {
                renderLoop();
            }
        }, "Recordish-OpenAL-Loopback");
        next.setDaemon(true);
        next.setPriority(Thread.NORM_PRIORITY + 2);
        running = true;
        renderThread = next;
        next.start();
    }

    public synchronized void stopRenderThread() {
        running = false;
        Thread thread = renderThread;
        if (thread != null && thread != Thread.currentThread()) {
            thread.interrupt();
        }
        // Closing the monitor line first releases a render thread blocked in
        // SourceDataLine.write instead of waiting forever for its queue.
        closeSpeakerLine();
        if (thread != null && thread != Thread.currentThread()) {
            boolean interrupted = false;
            long deadline = System.nanoTime()
                + RENDER_STOP_TIMEOUT_MILLIS * 1_000_000L;
            while (thread.isAlive()) {
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0L) {
                    break;
                }
                try {
                    thread.join(Math.max(
                        1L,
                        remainingNanos / 1_000_000L));
                } catch (InterruptedException ignored) {
                    interrupted = true;
                    break;
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
        // The render thread may have been opening the line when shutdown
        // began, so close once more after the bounded join.
        closeSpeakerLine();
        if (renderThread == thread) {
            renderThread = null;
        }
        if (thread != null
                && thread != Thread.currentThread()
                && thread.isAlive()) {
            RecordishMod.LOGGER.error(
                "OpenAL loopback render thread did not stop within {} ms; "
                    + "continuing device cleanup without waiting indefinitely.",
                Long.valueOf(RENDER_STOP_TIMEOUT_MILLIS));
        }
        /*
         * Paulscode uses cleanup()/init() for sound reloads as well as final
         * shutdown. Keep the recording sink and rolling PCM intact so a new
         * loopback device can continue the same capture after reload. Their
         * explicit owners finalize or discard them through finishRecording(),
         * abortRecording(), and disableRollingBuffer().
         */
    }

    /**
     * Called before LWJGL closes the ALC device.
     */
    public synchronized void beforeDeviceClose() {
        stopRenderThread();
        loopbackDevice = null;
        loopbackDeviceAddress = 0L;
        renderSamplesFunction = null;
    }

    public boolean isActive() {
        return running
            && loopbackDeviceAddress != 0L
            && renderSamplesFunction != null;
    }

    public boolean beginRecording(Path output, long videoStartNanos) {
        if (!isActive() || output == null) return false;
        RecordingSink next = null;
        try {
            next = new RecordingSink(output, videoStartNanos);
            if (!recording.compareAndSet(null, next)) {
                next.abort();
                return false;
            }
            return true;
        } catch (IOException exception) {
            if (next != null) next.abort();
            RecordishMod.LOGGER.warn(
                "Unable to open the OpenAL game-audio track.",
                exception);
            return false;
        }
    }

    public void setRecordingPaused(boolean paused) {
        RecordingSink sink = recording.get();
        if (sink != null) sink.setPaused(paused);
    }

    public void alignRecordingVideoStart(long videoStartNanos) {
        RecordingSink sink = recording.get();
        if (sink != null) {
            sink.setVideoStartNanos(videoStartNanos);
        }
    }

    public AudioCaptureSession.AudioTrack finishRecording() {
        RecordingSink sink = recording.getAndSet(null);
        return sink == null ? null : sink.finish();
    }

    public void abortRecording() {
        RecordingSink sink = recording.getAndSet(null);
        if (sink != null) sink.abort();
    }

    public void enableRollingBuffer(long retentionMillis) {
        synchronized (rollingAudio) {
            rollingRetentionMillis = Math.max(
                1000L,
                Math.min(
                    MAX_ROLLING_RETENTION_MILLIS,
                    retentionMillis));
            rollingEnabled = true;
            trimRollingAudio(monotonicMillis());
        }
    }

    public void disableRollingBuffer() {
        synchronized (rollingAudio) {
            rollingEnabled = false;
            rollingAudio.clear();
            rollingLastEndMillis = 0L;
        }
    }

    public byte[] extractAudio(long startMillis, long endMillis) {
        if (endMillis <= startMillis) return null;
        synchronized (rollingAudio) {
            long durationMillis = Math.min(
                MAX_ROLLING_RETENTION_MILLIS,
                endMillis - startMillis);
            if (durationMillis < endMillis - startMillis) {
                startMillis = endMillis - durationMillis;
            }
            long targetFrames = Math.round(
                durationMillis * (double) SAMPLE_RATE / 1000.0D);
            if (targetFrames <= 0L) return null;
            int targetBytes = (int) Math.min(
                Integer.MAX_VALUE - (Integer.MAX_VALUE % PCM_FRAME_BYTES),
                targetFrames * PCM_FRAME_BYTES);
            targetFrames = targetBytes / PCM_FRAME_BYTES;
            byte[] result = new byte[targetBytes];
            boolean copiedAudio = false;
            for (TimedAudio chunk : rollingAudio) {
                long chunkFrames =
                    chunk.pcm.length / PCM_FRAME_BYTES;
                if (chunkFrames <= 0L) continue;

                long chunkEndFrame = Math.round(
                    (chunk.timestampMillis - startMillis)
                        * (double) SAMPLE_RATE / 1000.0D);
                long chunkStartFrame = chunkEndFrame - chunkFrames;
                long copyStartFrame = Math.max(0L, chunkStartFrame);
                long copyEndFrame = Math.min(
                    targetFrames,
                    chunkEndFrame);
                if (copyEndFrame <= copyStartFrame) continue;

                int sourceOffset = (int) (
                    (copyStartFrame - chunkStartFrame)
                        * PCM_FRAME_BYTES);
                int destinationOffset = (int) (
                    copyStartFrame * PCM_FRAME_BYTES);
                int copyBytes = (int) (
                    (copyEndFrame - copyStartFrame)
                        * PCM_FRAME_BYTES);
                System.arraycopy(
                    chunk.pcm,
                    sourceOffset,
                    result,
                    destinationOffset,
                    copyBytes);
                copiedAudio = true;
            }
            return copiedAudio ? result : null;
        }
    }

    private void renderLoop() {
        Thread owner = Thread.currentThread();
        ByteBuffer buffer =
            ByteBuffer.allocateDirect(BYTES_PER_RENDER)
                .order(ByteOrder.nativeOrder());
        byte[] pcm = new byte[BYTES_PER_RENDER];
        openSpeakerLine(owner);

        long clockStart = System.nanoTime();
        long renderedSamples = 0L;
        while (ownsRenderGeneration(owner)) {
            try {
                long expected = clockStart
                    + renderedSamples * 1_000_000_000L / SAMPLE_RATE;
                long wait = expected - System.nanoTime();
                if (wait > 1_000_000L) {
                    Thread.sleep(
                        wait / 1_000_000L,
                        (int) (wait % 1_000_000L));
                } else if (wait < -500_000_000L) {
                    clockStart = System.nanoTime();
                    renderedSamples = 0L;
                }
                if (!ownsRenderGeneration(owner)) break;

                long address = loopbackDeviceAddress;
                Function render = renderSamplesFunction;
                if (address == 0L || render == null) {
                    Thread.sleep(20L);
                    continue;
                }

                buffer.clear();
                render.invokeVoid(new Object[]{
                    new Pointer(address),
                    buffer,
                    Integer.valueOf(SAMPLES_PER_RENDER)
                });
                if (!ownsRenderGeneration(owner)) break;
                renderedSamples += SAMPLES_PER_RENDER;
                buffer.position(0);
                buffer.get(pcm, 0, pcm.length);

                RecordingSink sink = recording.get();
                if (sink != null) sink.write(pcm);
                if (!ownsRenderGeneration(owner)) break;
                appendRollingAudio(pcm);
                if (!ownsRenderGeneration(owner)) break;

                SourceDataLine speaker = speakerLine;
                if (speaker != null && speaker.isOpen()) {
                    speaker.write(pcm, 0, pcm.length);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                break;
            } catch (Throwable throwable) {
                if (!ownsRenderGeneration(owner)) break;
                RecordishMod.LOGGER.warn(
                    "OpenAL loopback render failed.",
                    throwable);
                try {
                    Thread.sleep(50L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private boolean ownsRenderGeneration(Thread owner) {
        return running && renderThread == owner;
    }

    private void openSpeakerLine(Thread owner) {
        SourceDataLine line = null;
        try {
            AudioFormat format = audioFormat();
            DataLine.Info info =
                new DataLine.Info(SourceDataLine.class, format);
            if (!AudioSystem.isLineSupported(info)) {
                RecordishMod.LOGGER.warn(
                    "JavaSound cannot monitor the OpenAL loopback format; game audio may be inaudible while loopback is active.");
                return;
            }
            line =
                (SourceDataLine) AudioSystem.getLine(info);
            line.open(format, BYTES_PER_RENDER * 8);
            line.start();
            boolean accepted;
            synchronized (speakerLock) {
                accepted = ownsRenderGeneration(owner);
                if (accepted) {
                    speakerLine = line;
                }
            }
            if (!accepted) {
                closeSpeakerLine(line);
                return;
            }
        } catch (Throwable throwable) {
            closeSpeakerLine(line);
            if (!ownsRenderGeneration(owner)) return;
            RecordishMod.LOGGER.warn(
                "Unable to open the speaker monitor for OpenAL loopback.",
                throwable);
        }
    }

    private void closeSpeakerLine() {
        SourceDataLine line;
        synchronized (speakerLock) {
            line = speakerLine;
            speakerLine = null;
        }
        closeSpeakerLine(line);
    }

    private static void closeSpeakerLine(SourceDataLine line) {
        if (line == null) return;
        try {
            line.stop();
        } catch (Throwable ignored) {
        }
        try {
            line.flush();
        } catch (Throwable ignored) {
        }
        try {
            line.close();
        } catch (Throwable ignored) {
        }
    }

    private void appendRollingAudio(byte[] pcm) {
        if (!rollingEnabled
                || pcm == null
                || pcm.length < PCM_FRAME_BYTES) {
            return;
        }
        long now = monotonicMillis();
        synchronized (rollingAudio) {
            if (!rollingEnabled) return;
            int usableBytes =
                pcm.length - (pcm.length % PCM_FRAME_BYTES);
            long durationMillis = Math.max(
                1L,
                Math.round(usableBytes / BYTES_PER_MILLISECOND));
            long chunkEndMillis = now;
            if (rollingLastEndMillis > 0L
                    && now - durationMillis - rollingLastEndMillis
                        <= AUDIO_GAP_THRESHOLD_MILLIS) {
                chunkEndMillis =
                    rollingLastEndMillis + durationMillis;
            }
            byte[] copy = new byte[usableBytes];
            System.arraycopy(pcm, 0, copy, 0, usableBytes);
            rollingAudio.addLast(
                new TimedAudio(copy, chunkEndMillis));
            rollingLastEndMillis = chunkEndMillis;
            trimRollingAudio(chunkEndMillis);
        }
    }

    private void trimRollingAudio(long now) {
        long cutoff = now - rollingRetentionMillis;
        while (!rollingAudio.isEmpty()
                && rollingAudio.peekFirst().timestampMillis < cutoff) {
            rollingAudio.removeFirst();
        }
    }

    private static long monotonicMillis() {
        return System.nanoTime() / 1_000_000L;
    }

    private static NativeLibrary loadOpenAlLibrary() {
        String directory = System.getProperty("org.lwjgl.librarypath");
        String filename;
        String fallback;
        switch (LWJGLUtil.getPlatform()) {
            case LWJGLUtil.PLATFORM_WINDOWS:
                filename = Sys.is64Bit()
                    ? "OpenAL64.dll"
                    : "OpenAL32.dll";
                fallback = Sys.is64Bit() ? "OpenAL64" : "OpenAL32";
                break;
            case LWJGLUtil.PLATFORM_MACOSX:
                filename = "openal.dylib";
                fallback = "openal";
                break;
            case LWJGLUtil.PLATFORM_LINUX:
            default:
                filename = Sys.is64Bit()
                    ? "libopenal64.so"
                    : "libopenal.so";
                fallback = "openal";
                break;
        }
        if (directory != null && !directory.trim().isEmpty()) {
            java.io.File candidate =
                new java.io.File(directory, filename);
            if (candidate.isFile()) {
                return NativeLibrary.getInstance(
                    candidate.getAbsolutePath());
            }
        }
        return NativeLibrary.getInstance(fallback);
    }

    private static Function extension(
            Function getProcAddress,
            String name) {
        Pointer address = getProcAddress.invokePointer(
            new Object[]{Pointer.NULL, name});
        return address == null || Pointer.nativeValue(address) == 0L
            ? null
            : Function.getFunction(address);
    }

    private static void restoreNormalDevice() throws Exception {
        ALCdevice device = ALC10.alcOpenDevice(null);
        if (device == null) {
            throw new IOException(
                "OpenAL could not reopen the normal output device.");
        }
        ALCcontext context = ALC10.alcCreateContext(device, null);
        if (context == null
                || ALC10.alcMakeContextCurrent(context)
                    == ALC10.ALC_FALSE) {
            ALC10.alcCloseDevice(device);
            throw new IOException(
                "OpenAL could not recreate the normal output context.");
        }
        setAlState(device, context);
    }

    private static void setAlState(
            ALCdevice device,
            ALCcontext context) throws Exception {
        Field deviceField = AL.class.getDeclaredField("device");
        Field contextField = AL.class.getDeclaredField("context");
        deviceField.setAccessible(true);
        contextField.setAccessible(true);
        deviceField.set(null, device);
        contextField.set(null, context);
    }

    @SuppressWarnings("unchecked")
    private static ALCdevice wrapDevice(long address) throws Exception {
        Constructor<ALCdevice> constructor =
            ALCdevice.class.getDeclaredConstructor(Long.TYPE);
        constructor.setAccessible(true);
        ALCdevice device = constructor.newInstance(
            Long.valueOf(address));

        Field devicesField =
            ALC10.class.getDeclaredField("devices");
        devicesField.setAccessible(true);
        Map<Long, ALCdevice> devices =
            (Map<Long, ALCdevice>) devicesField.get(null);
        synchronized (devices) {
            devices.put(Long.valueOf(address), device);
        }
        return device;
    }

    private static void closeRawDevice(long address) {
        if (address == 0L) return;
        try {
            ALC10.alcCloseDevice(wrapDevice(address));
        } catch (Throwable ignored) {
        }
    }

    private static AudioFormat audioFormat() {
        return new AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED,
            SAMPLE_RATE,
            BITS_PER_SAMPLE,
            CHANNELS,
            CHANNELS * (BITS_PER_SAMPLE / 8),
            SAMPLE_RATE,
            false);
    }

    private static final class TimedAudio {
        final byte[] pcm;
        final long timestampMillis;

        TimedAudio(byte[] pcm, long timestampMillis) {
            this.pcm = pcm;
            this.timestampMillis = timestampMillis;
        }
    }

    private static final class RecordingSink {
        private final Path output;
        private volatile long videoStartNanos;
        private final AudioFormat format = audioFormat();
        private final OutputStream stream;

        private boolean closed;
        private boolean paused;
        private long pauseStartedNanos;
        private long totalPausedNanos;
        private long firstSampleNanos;
        private long lastSampleNanos;
        private long sampleFrames;
        private long pcmBytes;

        RecordingSink(Path output, long videoStartNanos)
                throws IOException {
            this.output = output;
            this.videoStartNanos = videoStartNanos;
            Path parent = output.getParent();
            if (parent != null) Files.createDirectories(parent);
            this.stream = new BufferedOutputStream(
                Files.newOutputStream(output));
            writeWavHeader(stream, 0L);
        }

        synchronized void write(byte[] pcm) {
            if (closed
                    || paused
                    || pcm == null
                    || pcm.length < format.getFrameSize()) {
                return;
            }
            try {
                int frameSize = format.getFrameSize();
                int usableBytes =
                    pcm.length - (pcm.length % frameSize);
                long chunkFrames = usableBytes / frameSize;
                long chunkDurationNanos =
                    chunkFrames * 1_000_000_000L / SAMPLE_RATE;
                long chunkEndNanos = normalizedNow();
                long chunkStartNanos =
                    chunkEndNanos - chunkDurationNanos;

                if (lastSampleNanos > 0L) {
                    long gapNanos =
                        chunkStartNanos - lastSampleNanos;
                    if (gapNanos
                            > AUDIO_GAP_THRESHOLD_MILLIS
                                * 1_000_000L) {
                        long boundedGapNanos = Math.min(
                            gapNanos,
                            MAX_RECORDING_GAP_SILENCE_MILLIS
                                * 1_000_000L);
                        long silenceFrames =
                            boundedGapNanos * SAMPLE_RATE
                                / 1_000_000_000L;
                        writeSilence(silenceFrames, frameSize);
                    }
                    /*
                     * Small scheduler jitter is not a real hole. Snap every
                     * following chunk to the written PCM timeline; a large
                     * outage has already advanced it by bounded silence.
                     */
                    chunkStartNanos = lastSampleNanos;
                    chunkEndNanos =
                        chunkStartNanos + chunkDurationNanos;
                } else {
                    firstSampleNanos = chunkStartNanos;
                }

                stream.write(pcm, 0, usableBytes);
                lastSampleNanos = chunkEndNanos;
                pcmBytes += usableBytes;
                sampleFrames += chunkFrames;
            } catch (IOException exception) {
                closed = true;
                try {
                    stream.close();
                } catch (IOException ignored) {
                }
                RecordishMod.LOGGER.warn(
                    "OpenAL game-audio track stopped after a write failure.",
                    exception);
            }
        }

        private void writeSilence(long frames, int frameSize)
                throws IOException {
            long bytesRemaining = frames * frameSize;
            while (bytesRemaining > 0L) {
                int blockBytes = (int) Math.min(
                    SILENCE_BLOCK.length,
                    bytesRemaining);
                blockBytes -= blockBytes % frameSize;
                if (blockBytes <= 0) break;
                stream.write(SILENCE_BLOCK, 0, blockBytes);
                bytesRemaining -= blockBytes;
                pcmBytes += blockBytes;
                sampleFrames += blockBytes / frameSize;
                lastSampleNanos +=
                    (blockBytes / frameSize)
                        * 1_000_000_000L / SAMPLE_RATE;
            }
        }

        synchronized void setPaused(boolean value) {
            if (closed || value == paused) return;
            long now = System.nanoTime();
            if (value) {
                pauseStartedNanos = now;
            } else if (pauseStartedNanos > 0L) {
                totalPausedNanos += now - pauseStartedNanos;
                pauseStartedNanos = 0L;
            }
            paused = value;
        }

        void setVideoStartNanos(long value) {
            if (value > 0L) videoStartNanos = value;
        }

        synchronized AudioCaptureSession.AudioTrack finish() {
            if (!closed) {
                try {
                    stream.flush();
                    stream.close();
                } catch (IOException exception) {
                    RecordishMod.LOGGER.warn(
                        "Unable to close the OpenAL game-audio track.",
                        exception);
                }
                closed = true;
            }
            try {
                patchWavHeader(output, pcmBytes);
                if (Files.isRegularFile(output)
                        && Files.size(output) > 44L) {
                    return new AudioCaptureSession.AudioTrack(
                        output,
                        AudioCaptureSession.TrackKind.GAME,
                        "Game audio",
                        videoStartNanos,
                        firstSampleNanos,
                        lastSampleNanos,
                        sampleFrames,
                        format);
                }
            } catch (IOException exception) {
                RecordishMod.LOGGER.warn(
                    "Unable to finalize the OpenAL game-audio WAV.",
                    exception);
            }
            return null;
        }

        synchronized void abort() {
            if (!closed) {
                try {
                    stream.close();
                } catch (IOException ignored) {
                }
                closed = true;
            }
            try {
                Files.deleteIfExists(output);
            } catch (IOException ignored) {
            }
        }

        private long normalizedNow() {
            long now = System.nanoTime();
            return now - totalPausedNanos;
        }
    }

    private static void writeWavHeader(
            OutputStream output,
            long dataBytes) throws IOException {
        int byteRate =
            SAMPLE_RATE * CHANNELS * (BITS_PER_SAMPLE / 8);
        int blockAlign =
            CHANNELS * (BITS_PER_SAMPLE / 8);
        output.write(new byte[]{'R', 'I', 'F', 'F'});
        writeLittleEndian(output, 36L + dataBytes, 4);
        output.write(new byte[]{'W', 'A', 'V', 'E'});
        output.write(new byte[]{'f', 'm', 't', ' '});
        writeLittleEndian(output, 16L, 4);
        writeLittleEndian(output, 1L, 2);
        writeLittleEndian(output, CHANNELS, 2);
        writeLittleEndian(output, SAMPLE_RATE, 4);
        writeLittleEndian(output, byteRate, 4);
        writeLittleEndian(output, blockAlign, 2);
        writeLittleEndian(output, BITS_PER_SAMPLE, 2);
        output.write(new byte[]{'d', 'a', 't', 'a'});
        writeLittleEndian(output, dataBytes, 4);
    }

    private static void patchWavHeader(Path file, long dataBytes)
            throws IOException {
        RandomAccessFile random =
            new RandomAccessFile(file.toFile(), "rw");
        try {
            random.seek(4L);
            writeLittleEndian(random, 36L + dataBytes, 4);
            random.seek(40L);
            writeLittleEndian(random, dataBytes, 4);
        } finally {
            random.close();
        }
    }

    private static void writeLittleEndian(
            OutputStream output,
            long value,
            int bytes) throws IOException {
        for (int index = 0; index < bytes; index++) {
            output.write((int) (value >>> (index * 8)) & 0xFF);
        }
    }

    private static void writeLittleEndian(
            RandomAccessFile output,
            long value,
            int bytes) throws IOException {
        for (int index = 0; index < bytes; index++) {
            output.write((int) (value >>> (index * 8)) & 0xFF);
        }
    }
}
