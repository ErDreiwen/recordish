package dev.recordable;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * JavaSound PCM capture used by the Forge port. It records an available
 * system-loopback line and microphone into independent WAV tracks so pause,
 * push-to-talk, mixing, and separate-track export remain deterministic.
 */
public final class AudioCaptureSession {
    public enum TrackKind {
        GAME,
        MICROPHONE,
        MUSIC
    }

    public static final class AudioDevice {
        private final String id;
        private final String displayName;
        private final boolean loopbackCandidate;

        AudioDevice(
                String id,
                String displayName,
                boolean loopbackCandidate) {
            this.id = id;
            this.displayName = displayName;
            this.loopbackCandidate = loopbackCandidate;
        }

        public String getId() {
            return id;
        }

        public String getDisplayName() {
            return displayName;
        }

        public boolean isLoopbackCandidate() {
            return loopbackCandidate;
        }
    }

    public static final class AudioTrack {
        private final Path path;
        private final TrackKind kind;
        private final String displayName;
        private final long videoStartNanos;
        private final long firstSampleNanos;
        private final long lastSampleNanos;
        private final long sampleFrameCount;
        private final AudioFormat format;

        AudioTrack(Path path, TrackKind kind, String displayName) {
            this(path, kind, displayName, 0L, 0L, 0L, 0L, null);
        }

        AudioTrack(
                Path path,
                TrackKind kind,
                String displayName,
                long videoStartNanos,
                long firstSampleNanos,
                long lastSampleNanos,
                long sampleFrameCount,
                AudioFormat format) {
            this.path = path;
            this.kind = kind;
            this.displayName = displayName;
            this.videoStartNanos = videoStartNanos;
            this.firstSampleNanos = firstSampleNanos;
            this.lastSampleNanos = lastSampleNanos;
            this.sampleFrameCount = Math.max(0L, sampleFrameCount);
            this.format = format;
        }

        public Path getPath() {
            return path;
        }

        public TrackKind getKind() {
            return kind;
        }

        public String getDisplayName() {
            return displayName;
        }

        /**
         * Monotonic origin used for video frame zero. Sample timestamps use
         * this same pause-adjusted timeline.
         */
        public long getVideoStartNanos() {
            return videoStartNanos;
        }

        public long getFirstSampleNanos() {
            return firstSampleNanos;
        }

        public long getLastSampleNanos() {
            return lastSampleNanos;
        }

        public long getSampleFrameCount() {
            return sampleFrameCount;
        }

        public AudioFormat getFormat() {
            return format;
        }

        public int getSampleRate() {
            return format == null ? 0 : Math.round(format.getSampleRate());
        }

        public int getChannelCount() {
            return format == null ? 0 : format.getChannels();
        }

        public int getSampleSizeBits() {
            return format == null ? 0 : format.getSampleSizeInBits();
        }

        public int getFrameSize() {
            return format == null ? 0 : format.getFrameSize();
        }

        public double getNominalDurationSeconds() {
            float frameRate = format == null ? 0.0F : format.getFrameRate();
            if (frameRate <= 0.0F || sampleFrameCount <= 0L) return 0.0D;
            return sampleFrameCount / (double) frameRate;
        }

        public double getObservedDurationSeconds() {
            if (firstSampleNanos <= 0L
                    || lastSampleNanos <= firstSampleNanos) {
                return 0.0D;
            }
            return (lastSampleNanos - firstSampleNanos)
                    / 1_000_000_000.0D;
        }
    }

    private final RecordableConfig config;
    private final List<CaptureWorker> workers;
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final AtomicBoolean microphoneGate = new AtomicBoolean(true);
    private final Object timelineLock = new Object();
    private final long requestedVideoStartNanos;
    private final Path openAlOutput;
    private volatile String statusDescription;
    private volatile long videoStartNanos;
    private volatile boolean openAlRecordingActive;
    private long pauseStartedNanos;
    private long totalPausedNanos;
    private volatile boolean preserveOutputs;

    private AudioCaptureSession(
            RecordableConfig config,
            List<CaptureWorker> workers,
            String statusDescription,
            long requestedVideoStartNanos,
            Path openAlOutput) {
        this.config = config;
        this.workers = workers;
        this.statusDescription = statusDescription;
        this.requestedVideoStartNanos = requestedVideoStartNanos;
        this.openAlOutput = openAlOutput;
        microphoneGate.set(!config.microphonePushToTalk);
    }

    public static AudioCaptureSession start(
            RecordableConfig config,
            Path outputDirectory,
            Path requestedVideo) {
        return start(config, outputDirectory, requestedVideo, 0L);
    }

    /**
     * Starts capture with an optional authoritative video timeline origin.
     * Existing callers may pass zero and the shared audio start gate becomes
     * the best available approximation of video frame zero.
     */
    public static AudioCaptureSession start(
            RecordableConfig config,
            Path outputDirectory,
            Path requestedVideo,
            long videoStartNanos) {
        if (config == null || outputDirectory == null) return null;
        List<CaptureWorker> workers = new ArrayList<CaptureWorker>();
        List<String> descriptions = new ArrayList<String>();
        Path openAlOutput = null;
        String stem = requestedVideo == null
                ? "recordable-audio-" + System.currentTimeMillis()
                : stem(requestedVideo);

        try {
            Files.createDirectories(outputDirectory);
        } catch (IOException exception) {
            RecordableMod.LOGGER.warn(
                    "Unable to create the audio output directory.",
                    exception);
            return null;
        }

        AudioFormat preferred = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                config.audioSampleRate,
                16,
                config.audioChannelCount,
                config.audioChannelCount * 2,
                config.audioSampleRate,
                false);

        if (config.captureAudio && config.trackGameAudio) {
            if (OpenALLoopbackCapture.getInstance().isActive()) {
                openAlOutput = outputDirectory.resolve(
                    stem + ".game-audio.wav");
                descriptions.add("game audio: direct OpenAL");
            } else {
                DeviceMatch loopback = findDevice(
                        preferred,
                        config.audioDevice,
                        true);
                if (loopback != null) {
                    Path file = outputDirectory.resolve(
                            stem + ".game-audio.wav");
                    workers.add(new CaptureWorker(
                            loopback,
                            file,
                            TrackKind.GAME,
                            "Game audio",
                            false));
                    descriptions.add("game audio: " + loopback.name);
                } else {
                    RecordableMod.LOGGER.warn(
                            "No direct OpenAL or system-loopback audio source "
                                    + "is available. Video will still record; "
                                    + "enable Stereo Mix, a PulseAudio monitor, "
                                    + "BlackHole, or a similar loopback device.");
                }
            }
        }

        if (config.captureMicrophone && config.trackMicAudio) {
            DeviceMatch microphone = findDevice(
                    preferred,
                    config.microphoneDevice,
                    false);
            if (microphone != null) {
                Path file = outputDirectory.resolve(
                        stem + ".microphone.wav");
                workers.add(new CaptureWorker(
                        microphone,
                        file,
                        TrackKind.MICROPHONE,
                        "Microphone",
                        true));
                descriptions.add("microphone: " + microphone.name);
            } else {
                RecordableMod.LOGGER.warn(
                        "No compatible microphone JavaSound line is available.");
            }
        }

        if (workers.isEmpty() && openAlOutput == null) return null;
        AudioCaptureSession session = new AudioCaptureSession(
                config,
                workers,
                join(descriptions, ", "),
                videoStartNanos,
                openAlOutput);
        if (!session.startWorkers()) {
            session.abort();
            return null;
        }
        return session;
    }

    public static List<AudioDevice> listDevices() {
        List<AudioDevice> result = new ArrayList<AudioDevice>();
        Mixer.Info[] infos = AudioSystem.getMixerInfo();
        for (int index = 0; index < infos.length; index++) {
            Mixer.Info info = infos[index];
            Mixer mixer = AudioSystem.getMixer(info);
            if (!supportsAnyCaptureFormat(mixer)) continue;
            String name = deviceName(info);
            result.add(new AudioDevice(
                    Integer.toString(index),
                    name,
                    isLoopbackName(name)));
        }
        Collections.sort(result, new Comparator<AudioDevice>() {
            @Override
            public int compare(AudioDevice left, AudioDevice right) {
                if (left.loopbackCandidate
                        != right.loopbackCandidate) {
                    return left.loopbackCandidate ? -1 : 1;
                }
                return left.displayName.compareToIgnoreCase(
                        right.displayName);
            }
        });
        return result;
    }

    private boolean startWorkers() {
        final CountDownLatch ready =
                new CountDownLatch(workers.size());
        final CountDownLatch startGate = new CountDownLatch(1);
        for (CaptureWorker worker : workers) {
            worker.owner = this;
            worker.ready = ready;
            worker.startGate = startGate;
            worker.start();
        }
        boolean openedInTime;
        try {
            openedInTime = ready.await(5L, TimeUnit.SECONDS);
            if (!openedInTime) {
                RecordableMod.LOGGER.warn(
                        "Timed out while opening audio devices.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            for (CaptureWorker worker : workers) worker.requestStop();
            startGate.countDown();
            for (CaptureWorker worker : workers) worker.awaitStop();
            return false;
        }

        List<CaptureWorker> failedWorkers =
                new ArrayList<CaptureWorker>();
        for (CaptureWorker worker : workers) {
            if (!worker.prepared || worker.failure != null) {
                RecordableMod.LOGGER.warn(
                        "Unable to open audio device {}.",
                        worker.device.name,
                        worker.failure == null
                                ? new IOException("Audio device open timed out.")
                                : worker.failure);
                worker.requestStop();
                failedWorkers.add(worker);
            }
        }

        videoStartNanos = requestedVideoStartNanos > 0L
                ? requestedVideoStartNanos
                : System.nanoTime();
        if (openAlOutput != null) {
            openAlRecordingActive =
                OpenALLoopbackCapture.getInstance().beginRecording(
                    openAlOutput,
                    videoStartNanos);
            if (!openAlRecordingActive) {
                RecordableMod.LOGGER.warn(
                    "Direct OpenAL game-audio capture was available during "
                        + "setup but could not start its recording track.");
            }
        }
        startGate.countDown();

        for (CaptureWorker worker : failedWorkers) {
            worker.awaitStop();
            worker.deleteOutputQuietly();
        }
        workers.removeAll(failedWorkers);
        if (workers.isEmpty() && !openAlRecordingActive) return false;

        statusDescription = describeWorkers(
            workers,
            openAlRecordingActive);
        return true;
    }

    public void pause() {
        synchronized (timelineLock) {
            if (paused.compareAndSet(false, true)) {
                pauseStartedNanos = System.nanoTime();
                OpenALLoopbackCapture.getInstance()
                    .setRecordingPaused(true);
            }
        }
    }

    public void resume() {
        synchronized (timelineLock) {
            if (!paused.get()) return;
            long now = System.nanoTime();
            if (pauseStartedNanos > 0L && now > pauseStartedNanos) {
                totalPausedNanos += now - pauseStartedNanos;
            }
            pauseStartedNanos = 0L;
            paused.set(false);
            OpenALLoopbackCapture.getInstance()
                .setRecordingPaused(false);
        }
    }

    public void setMicrophoneGate(boolean active) {
        microphoneGate.set(active);
    }

    public String getStatusDescription() {
        return statusDescription;
    }

    public long getVideoStartNanos() {
        return videoStartNanos;
    }

    public void alignVideoStartNanos(long firstFrameNanos) {
        if (firstFrameNanos <= 0L) return;
        videoStartNanos = firstFrameNanos;
        if (openAlRecordingActive) {
            OpenALLoopbackCapture.getInstance()
                .alignRecordingVideoStart(firstFrameNanos);
        }
    }

    public boolean hasGameAudio() {
        if (openAlRecordingActive) return true;
        for (CaptureWorker worker : workers) {
            if (worker.kind == TrackKind.GAME
                    && worker.prepared
                    && worker.failure == null) {
                return true;
            }
        }
        return false;
    }

    public boolean hasMicrophoneAudio() {
        for (CaptureWorker worker : workers) {
            if (worker.kind == TrackKind.MICROPHONE
                    && worker.prepared
                    && worker.failure == null) {
                return true;
            }
        }
        return false;
    }

    public List<AudioTrack> finish() {
        for (CaptureWorker worker : workers) worker.requestStop();
        List<AudioTrack> tracks = new ArrayList<AudioTrack>();
        if (openAlRecordingActive) {
            AudioTrack gameTrack =
                OpenALLoopbackCapture.getInstance()
                    .finishRecording();
            openAlRecordingActive = false;
            if (gameTrack != null) tracks.add(gameTrack);
        }
        for (CaptureWorker worker : workers) {
            worker.awaitStop();
            if (worker.failure != null) {
                RecordableMod.LOGGER.warn(
                        "Audio capture failed for {}.",
                        worker.device.name,
                        worker.failure);
            }
            try {
                if (Files.isRegularFile(worker.output)
                        && Files.size(worker.output) > 44L) {
                    tracks.add(new AudioTrack(
                            worker.output,
                            worker.kind,
                            worker.displayName,
                            videoStartNanos,
                            worker.firstSampleNanos,
                            worker.lastSampleNanos,
                            worker.sampleFrameCount,
                            worker.device.format));
                }
            } catch (IOException ignored) {
            }
        }
        preserveOutputs = true;
        return tracks;
    }

    public void preserveRawOutputs() {
        preserveOutputs = true;
    }

    public void abort() {
        if (openAlRecordingActive) {
            if (preserveOutputs) {
                OpenALLoopbackCapture.getInstance().finishRecording();
            } else {
                OpenALLoopbackCapture.getInstance().abortRecording();
            }
            openAlRecordingActive = false;
        }
        for (CaptureWorker worker : workers) worker.requestStop();
        for (CaptureWorker worker : workers) {
            worker.awaitStop();
            if (!preserveOutputs) {
                worker.deleteOutputQuietly();
            }
        }
        if (preserveOutputs) {
            RecordableMod.LOGGER.warn(
                    "Preserving raw audio WAV files because recording "
                            + "finalization did not complete.");
        }
    }

    private long normalizeTimelineNanos(long timestampNanos) {
        synchronized (timelineLock) {
            long pausedNanos = totalPausedNanos;
            if (paused.get() && pauseStartedNanos > 0L
                    && timestampNanos > pauseStartedNanos) {
                pausedNanos += timestampNanos - pauseStartedNanos;
            }
            return timestampNanos - pausedNanos;
        }
    }

    private static String describeWorkers(
            List<CaptureWorker> activeWorkers,
            boolean directOpenAl) {
        List<String> descriptions = new ArrayList<String>();
        if (directOpenAl) {
            descriptions.add("game audio: direct OpenAL");
        }
        for (CaptureWorker worker : activeWorkers) {
            String label = worker.kind == TrackKind.MICROPHONE
                    ? "microphone"
                    : "game audio";
            descriptions.add(label + ": " + worker.device.name);
        }
        return join(descriptions, ", ");
    }

    private static DeviceMatch findDevice(
            AudioFormat preferred,
            String configured,
            boolean requireLoopback) {
        Mixer.Info[] infos = AudioSystem.getMixerInfo();
        List<DeviceMatch> candidates = new ArrayList<DeviceMatch>();
        for (int index = 0; index < infos.length; index++) {
            Mixer.Info info = infos[index];
            String name = deviceName(info);
            Mixer mixer = AudioSystem.getMixer(info);
            AudioFormat supported = supportedFormat(mixer, preferred);
            if (supported == null) continue;
            candidates.add(new DeviceMatch(
                    mixer,
                    supported,
                    name,
                    index,
                    isLoopbackName(name)));
        }

        if (!isAuto(configured)) {
            String needle = configured.trim().toLowerCase(Locale.ROOT);
            DeviceMatch partialMatch = null;
            for (DeviceMatch candidate : candidates) {
                String candidateName =
                        candidate.name.toLowerCase(Locale.ROOT);
                if (Integer.toString(candidate.index).equals(needle)
                        || candidateName.equals(needle)) {
                    if (!isAllowedDevice(candidate, requireLoopback)) {
                        RecordableMod.LOGGER.warn(
                            "Using explicitly selected {} device '{}' even "
                                + "though its driver name does not match the "
                                + "expected capture type.",
                            requireLoopback
                                ? "game-audio"
                                : "microphone",
                            candidate.name);
                    }
                    return candidate;
                }
                if (isAllowedDevice(candidate, requireLoopback)
                        && candidateName.contains(needle)
                        && partialMatch == null) {
                    partialMatch = candidate;
                }
            }
            if (partialMatch != null) {
                return partialMatch;
            }
            RecordableMod.LOGGER.warn(
                    "Configured {} device '{}' was unavailable or had the "
                            + "wrong capture type; trying automatic selection.",
                    requireLoopback ? "game-audio loopback" : "microphone",
                    configured);
        }

        if (requireLoopback) {
            for (DeviceMatch candidate : candidates) {
                if (candidate.loopback) return candidate;
            }
            return null;
        }

        for (DeviceMatch candidate : candidates) {
            if (!candidate.loopback) return candidate;
        }
        return null;
    }

    private static boolean isAllowedDevice(
            DeviceMatch candidate,
            boolean requireLoopback) {
        return candidate != null
                && candidate.loopback == requireLoopback;
    }

    private static boolean supportsAnyCaptureFormat(Mixer mixer) {
        AudioFormat[] formats = {
            pcmFormat(48000, 2),
            pcmFormat(48000, 1),
            pcmFormat(44100, 2),
            pcmFormat(44100, 1)
        };
        for (AudioFormat format : formats) {
            if (mixer.isLineSupported(
                    new DataLine.Info(TargetDataLine.class, format))) {
                return true;
            }
        }
        return false;
    }

    private static AudioFormat supportedFormat(
            Mixer mixer,
            AudioFormat preferred) {
        AudioFormat[] formats = {
            preferred,
            pcmFormat((int) preferred.getSampleRate(), 2),
            pcmFormat((int) preferred.getSampleRate(), 1),
            pcmFormat(48000, 2),
            pcmFormat(48000, 1),
            pcmFormat(44100, 2),
            pcmFormat(44100, 1)
        };
        for (AudioFormat format : formats) {
            if (mixer.isLineSupported(
                    new DataLine.Info(TargetDataLine.class, format))) {
                return format;
            }
        }
        return null;
    }

    private static AudioFormat pcmFormat(int sampleRate, int channels) {
        return new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                sampleRate,
                16,
                channels,
                channels * 2,
                sampleRate,
                false);
    }

    private static String deviceName(Mixer.Info info) {
        String description = info.getDescription();
        if (isBlank(description)
                || description.equalsIgnoreCase(info.getName())) {
            return info.getName();
        }
        return info.getName() + " - " + description;
    }

    private static boolean isLoopbackName(String value) {
        String name = value == null
                ? ""
                : value.toLowerCase(Locale.ROOT);
        return name.contains("stereo mix")
                || name.contains("stereomix")
                || name.contains("what u hear")
                || name.contains("what you hear")
                || name.contains("wave out mix")
                || name.contains("waveout mix")
                || name.contains("rec. playback")
                || name.contains("loopback")
                || name.contains("monitor of")
                || name.contains("monitor source")
                || name.contains(".monitor")
                || name.contains("blackhole")
                || name.contains("soundflower")
                || name.contains("cable output")
                || name.contains("virtual cable output")
                || name.contains("voicemeeter output")
                || name.contains("voicemeeter aux output");
    }

    private static boolean isAuto(String value) {
        return isBlank(value)
                || "auto".equalsIgnoreCase(value.trim())
                || "default".equalsIgnoreCase(value.trim());
    }

    private static String stem(Path path) {
        String filename = path.getFileName().toString();
        int dot = filename.lastIndexOf('.');
        return dot <= 0 ? filename : filename.substring(0, dot);
    }

    private static String join(List<String> values, String separator) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (builder.length() > 0) builder.append(separator);
            builder.append(value);
        }
        return builder.toString();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static final class DeviceMatch {
        final Mixer mixer;
        final AudioFormat format;
        final String name;
        final int index;
        final boolean loopback;

        DeviceMatch(
                Mixer mixer,
                AudioFormat format,
                String name,
                int index,
                boolean loopback) {
            this.mixer = mixer;
            this.format = format;
            this.name = name;
            this.index = index;
            this.loopback = loopback;
        }
    }

    private static final class CaptureWorker implements Runnable {
        final DeviceMatch device;
        final Path output;
        final TrackKind kind;
        final String displayName;
        final boolean microphone;
        final AtomicBoolean running = new AtomicBoolean(true);
        volatile AudioCaptureSession owner;
        volatile CountDownLatch ready;
        volatile CountDownLatch startGate;
        volatile Throwable failure;
        volatile TargetDataLine line;
        volatile Thread thread;
        volatile boolean prepared;
        volatile long firstSampleNanos;
        volatile long lastSampleNanos;
        volatile long sampleFrameCount;

        CaptureWorker(
                DeviceMatch device,
                Path output,
                TrackKind kind,
                String displayName,
                boolean microphone) {
            this.device = device;
            this.output = output;
            this.kind = kind;
            this.displayName = displayName;
            this.microphone = microphone;
        }

        void start() {
            thread = new Thread(
                    this,
                    "Recordable-Audio-" + kind.name());
            thread.setDaemon(true);
            thread.start();
        }

        @Override
        public void run() {
            WavWriter writer = null;
            try {
                DataLine.Info lineInfo = new DataLine.Info(
                        TargetDataLine.class,
                        device.format);
                line = (TargetDataLine) device.mixer.getLine(lineInfo);
                line.open(
                        device.format,
                        Math.max(
                                4096,
                                (int) device.format.getFrameRate()
                                        * device.format.getFrameSize()
                                        / 5));
                writer = new WavWriter(output, device.format);
                prepared = true;
            } catch (Throwable throwable) {
                failure = throwable;
            } finally {
                ready.countDown();
            }

            if (failure != null || writer == null || line == null) {
                closeLine();
                if (writer != null) writer.closeQuietly();
                return;
            }

            try {
                startGate.await();
                if (!running.get()) return;
                TargetDataLine captureLine = line;
                if (captureLine == null) return;
                long lineStartedNanos = System.nanoTime();
                captureLine.start();
                int frameSize = Math.max(1, device.format.getFrameSize());
                int bufferLength = Math.max(
                        frameSize * 256,
                        (int) device.format.getFrameRate()
                                * frameSize
                                / 50);
                bufferLength -= bufferLength % frameSize;
                byte[] buffer = new byte[bufferLength];
                while (running.get()) {
                    int read = captureLine.read(
                            buffer,
                            0,
                            buffer.length);
                    long readCompletedNanos = System.nanoTime();
                    if (read <= 0) continue;
                    read -= read % frameSize;
                    if (read <= 0 || owner.paused.get()) continue;
                    if (microphone
                            && !owner.microphoneGate.get()) {
                        for (int index = 0; index < read; index++) {
                            buffer[index] = 0;
                        }
                    }
                    writer.write(buffer, 0, read);
                    long frames = read / frameSize;
                    double frameRate = Math.max(
                            1.0D,
                            device.format.getFrameRate());
                    long chunkDurationNanos = Math.max(
                            1L,
                            Math.round(frames
                                    * 1_000_000_000.0D
                                    / frameRate));
                    long chunkStartNanos = Math.max(
                            lineStartedNanos,
                            readCompletedNanos - chunkDurationNanos);
                    long timelineChunkStart =
                            owner.normalizeTimelineNanos(chunkStartNanos);
                    long timelineChunkEnd =
                            owner.normalizeTimelineNanos(
                                    readCompletedNanos);
                    if (timelineChunkEnd <= timelineChunkStart) {
                        timelineChunkEnd =
                                timelineChunkStart + chunkDurationNanos;
                    }
                    if (firstSampleNanos <= 0L) {
                        firstSampleNanos = timelineChunkStart;
                    }
                    lastSampleNanos = Math.max(
                            lastSampleNanos,
                            timelineChunkEnd);
                    sampleFrameCount += frames;
                }
            } catch (Throwable throwable) {
                if (running.get()) failure = throwable;
            } finally {
                closeLine();
                writer.closeQuietly();
            }
        }

        void requestStop() {
            running.set(false);
            closeLine();
            CountDownLatch gate = startGate;
            if (gate != null) gate.countDown();
        }

        void awaitStop() {
            Thread active = thread;
            if (active == null) return;
            try {
                active.join(5000L);
                if (active.isAlive()) {
                    active.interrupt();
                    active.join(1000L);
                }
                if (active.isAlive()) {
                    RecordableMod.LOGGER.warn(
                            "Audio capture worker {} did not stop cleanly.",
                            device.name);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }

        void deleteOutputQuietly() {
            try {
                Files.deleteIfExists(output);
            } catch (IOException ignored) {
            }
        }

        private void closeLine() {
            TargetDataLine active = line;
            if (active == null) return;
            try {
                active.stop();
            } catch (Throwable ignored) {
            }
            try {
                active.close();
            } catch (Throwable ignored) {
            }
            line = null;
        }
    }

    private static final class WavWriter {
        private final RandomAccessFile file;
        private final AudioFormat format;
        private long dataLength;

        WavWriter(Path output, AudioFormat format) throws IOException {
            this.format = format;
            this.file = new RandomAccessFile(output.toFile(), "rw");
            file.setLength(0L);
            writeHeader(0L);
        }

        synchronized void write(byte[] data, int offset, int length)
                throws IOException {
            file.write(data, offset, length);
            dataLength += length;
        }

        synchronized void closeQuietly() {
            try {
                file.seek(0L);
                writeHeader(dataLength);
                file.close();
            } catch (IOException ignored) {
            }
        }

        private void writeHeader(long pcmBytes) throws IOException {
            long boundedLength = Math.min(0xFFFFFFFFL, pcmBytes);
            int channels = format.getChannels();
            int sampleRate = (int) format.getSampleRate();
            int bits = format.getSampleSizeInBits();
            int blockAlign = channels * bits / 8;
            long byteRate = sampleRate * (long) blockAlign;

            file.writeBytes("RIFF");
            writeLeInt(file, Math.min(0xFFFFFFFFL, 36L + boundedLength));
            file.writeBytes("WAVE");
            file.writeBytes("fmt ");
            writeLeInt(file, 16L);
            writeLeShort(file, 1);
            writeLeShort(file, channels);
            writeLeInt(file, sampleRate);
            writeLeInt(file, byteRate);
            writeLeShort(file, blockAlign);
            writeLeShort(file, bits);
            file.writeBytes("data");
            writeLeInt(file, boundedLength);
        }

        private static void writeLeShort(
                RandomAccessFile file,
                int value) throws IOException {
            file.write(value & 255);
            file.write(value >>> 8 & 255);
        }

        private static void writeLeInt(
                RandomAccessFile file,
                long value) throws IOException {
            file.write((int) value & 255);
            file.write((int) (value >>> 8) & 255);
            file.write((int) (value >>> 16) & 255);
            file.write((int) (value >>> 24) & 255);
        }
    }
}
