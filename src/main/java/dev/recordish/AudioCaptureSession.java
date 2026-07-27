package dev.recordish;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
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
 * Audio capture used by the Forge port. OpenAL remains the preferred game
 * source; Windows can fall back to an FFmpeg/DirectShow loopback endpoint,
 * with JavaSound handling compatible loopback and microphone lines. Sources
 * are recorded into independent WAV tracks for deterministic finalization.
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

    private final RecordishConfig config;
    private final List<CaptureWorker> workers;
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final AtomicBoolean microphoneGate = new AtomicBoolean(true);
    private final Object timelineLock = new Object();
    private final long requestedVideoStartNanos;
    private final Path openAlOutput;
    private final CaptureWorker gameAudioFallback;
    private volatile String statusDescription;
    private volatile long videoStartNanos;
    private volatile boolean openAlRecordingActive;
    private volatile FfmpegCaptureWorker ffmpegGameWorker;
    private long pauseStartedNanos;
    private long totalPausedNanos;
    private volatile boolean preserveOutputs;

    private AudioCaptureSession(
            RecordishConfig config,
            List<CaptureWorker> workers,
            String statusDescription,
            long requestedVideoStartNanos,
            Path openAlOutput,
            FfmpegCaptureWorker ffmpegGameWorker,
            CaptureWorker gameAudioFallback) {
        this.config = config;
        this.workers = workers;
        this.statusDescription = statusDescription;
        this.requestedVideoStartNanos = requestedVideoStartNanos;
        this.openAlOutput = openAlOutput;
        this.ffmpegGameWorker = ffmpegGameWorker;
        this.gameAudioFallback = gameAudioFallback;
        microphoneGate.set(!config.microphonePushToTalk);
    }

    public static AudioCaptureSession start(
            RecordishConfig config,
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
            RecordishConfig config,
            Path outputDirectory,
            Path requestedVideo,
            long videoStartNanos) {
        if (config == null || outputDirectory == null) return null;
        List<CaptureWorker> workers = new ArrayList<CaptureWorker>();
        List<String> descriptions = new ArrayList<String>();
        Path openAlOutput = null;
        FfmpegCaptureWorker ffmpegGameWorker = null;
        CaptureWorker gameAudioFallback = null;
        String stem = requestedVideo == null
                ? "recordish-audio-" + System.currentTimeMillis()
                : stem(requestedVideo);

        try {
            Files.createDirectories(outputDirectory);
        } catch (IOException exception) {
            RecordishMod.LOGGER.warn(
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
                if (PlatformUtils.isWindows()) {
                    String executable =
                            StorageManager.resolveFfmpegExecutable(config);
                    String directShowDevice =
                            PlatformUtils.findWindowsSystemAudioDevice(
                                    executable,
                                    config.audioDevice);
                    if (directShowDevice != null) {
                        Path file = outputDirectory.resolve(
                                stem + ".game-audio.wav");
                        ffmpegGameWorker = new FfmpegCaptureWorker(
                                executable,
                                directShowDevice,
                                file,
                                preferred);
                        descriptions.add(
                                "game audio: DirectShow "
                                        + directShowDevice);
                        if (loopback != null) {
                            gameAudioFallback = new CaptureWorker(
                                    loopback,
                                    file,
                                    TrackKind.GAME,
                                    "Game audio",
                                    false);
                        }
                    }
                }
                if (ffmpegGameWorker == null && loopback != null) {
                    Path file = outputDirectory.resolve(
                            stem + ".game-audio.wav");
                    workers.add(new CaptureWorker(
                            loopback,
                            file,
                            TrackKind.GAME,
                            "Game audio",
                            false));
                    descriptions.add("game audio: " + loopback.name);
                } else if (ffmpegGameWorker == null) {
                    RecordishMod.LOGGER.warn(
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
                RecordishMod.LOGGER.warn(
                        "No compatible microphone JavaSound line is available.");
            }
        }

        if (workers.isEmpty()
                && openAlOutput == null
                && ffmpegGameWorker == null) {
            return null;
        }
        AudioCaptureSession session = new AudioCaptureSession(
                config,
                workers,
                join(descriptions, ", "),
                videoStartNanos,
                openAlOutput,
                ffmpegGameWorker,
                gameAudioFallback);
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
        FfmpegCaptureWorker systemAudio = ffmpegGameWorker;
        if (systemAudio != null && !systemAudio.start(this)) {
            RecordishMod.LOGGER.warn(
                    "Unable to start DirectShow system-audio capture for {}: {}",
                    systemAudio.deviceName,
                    systemAudio.failureDescription());
            systemAudio.deleteOutputQuietly();
            ffmpegGameWorker = null;
            systemAudio = null;
            if (gameAudioFallback != null) {
                workers.add(gameAudioFallback);
                RecordishMod.LOGGER.info(
                        "Falling back to JavaSound loopback device {}.",
                        gameAudioFallback.device.name);
            }
        }

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
                RecordishMod.LOGGER.warn(
                        "Timed out while opening audio devices.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            if (systemAudio != null) systemAudio.requestStop();
            for (CaptureWorker worker : workers) worker.requestStop();
            startGate.countDown();
            if (systemAudio != null) systemAudio.awaitStop();
            for (CaptureWorker worker : workers) worker.awaitStop();
            return false;
        }

        List<CaptureWorker> failedWorkers =
                new ArrayList<CaptureWorker>();
        for (CaptureWorker worker : workers) {
            if (!worker.prepared || worker.failure != null) {
                RecordishMod.LOGGER.warn(
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
                RecordishMod.LOGGER.warn(
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
        if (workers.isEmpty()
                && !openAlRecordingActive
                && (systemAudio == null || !systemAudio.isActive())) {
            return false;
        }

        statusDescription = describeWorkers(
            workers,
            openAlRecordingActive,
            systemAudio);
        return true;
    }

    public void pause() {
        synchronized (timelineLock) {
            if (!paused.get()) {
                FfmpegCaptureWorker systemAudio = ffmpegGameWorker;
                /*
                 * When DirectShow is active, establish the pause boundary
                 * while holding its PCM commit lock. Any chunk already being
                 * written therefore finishes before this timestamp, and all
                 * later chunks see the finite span end and are clipped.
                 */
                long pausedAt = systemAudio == null
                        ? System.nanoTime()
                        : systemAudio.publishPauseBoundary();
                pauseStartedNanos = pausedAt;
                paused.set(true);
                OpenALLoopbackCapture.getInstance()
                    .setRecordingPaused(true);
                if (systemAudio != null) {
                    systemAudio.signalPausedCapture();
                }
            }
        }
    }

    public void resume() {
        synchronized (timelineLock) {
            if (!paused.get()) return;
            long resumedAt = System.nanoTime();
            if (pauseStartedNanos > 0L
                    && resumedAt > pauseStartedNanos) {
                totalPausedNanos += resumedAt - pauseStartedNanos;
            }
            pauseStartedNanos = 0L;
            paused.set(false);
            OpenALLoopbackCapture.getInstance()
                .setRecordingPaused(false);
            FfmpegCaptureWorker systemAudio = ffmpegGameWorker;
            if (systemAudio != null) {
                systemAudio.resumeCapture(
                        resumedAt,
                        resumedAt - totalPausedNanos);
            }
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
        FfmpegCaptureWorker systemAudio = ffmpegGameWorker;
        if (systemAudio != null && systemAudio.isActive()) {
            return true;
        }
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
        FfmpegCaptureWorker systemAudio = ffmpegGameWorker;
        if (systemAudio != null) systemAudio.requestStop();
        for (CaptureWorker worker : workers) worker.requestStop();
        List<AudioTrack> tracks = new ArrayList<AudioTrack>();
        if (openAlRecordingActive) {
            AudioTrack gameTrack =
                OpenALLoopbackCapture.getInstance()
                    .finishRecording();
            openAlRecordingActive = false;
            if (gameTrack != null) tracks.add(gameTrack);
        }
        if (systemAudio != null) {
            systemAudio.awaitStop();
            AudioTrack gameTrack =
                    systemAudio.toAudioTrack(videoStartNanos);
            if (gameTrack != null) {
                tracks.add(gameTrack);
            } else if (systemAudio.failure != null) {
                RecordishMod.LOGGER.warn(
                        "DirectShow system-audio capture failed for {}.",
                        systemAudio.deviceName,
                        systemAudio.failure);
            }
            ffmpegGameWorker = null;
        }
        for (CaptureWorker worker : workers) {
            worker.awaitStop();
            if (worker.failure != null) {
                RecordishMod.LOGGER.warn(
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
        FfmpegCaptureWorker systemAudio = ffmpegGameWorker;
        if (systemAudio != null) {
            systemAudio.requestStop();
            systemAudio.awaitStop();
            if (!preserveOutputs) {
                systemAudio.deleteOutputQuietly();
            }
            ffmpegGameWorker = null;
        }
        for (CaptureWorker worker : workers) worker.requestStop();
        for (CaptureWorker worker : workers) {
            worker.awaitStop();
            if (!preserveOutputs) {
                worker.deleteOutputQuietly();
            }
        }
        if (preserveOutputs) {
            RecordishMod.LOGGER.warn(
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

    /**
     * Returns the half-open PCM frame range whose sample timestamps fall in
     * the requested active interval. Package-private for deterministic pause
     * boundary tests without opening an audio device.
     */
    static int[] clipPcmFrameRange(
            long chunkStartNanos,
            long chunkEndNanos,
            long activeStartNanos,
            long activeEndNanos,
            int frameCount) {
        if (frameCount <= 0
                || chunkEndNanos <= chunkStartNanos) {
            return new int[]{0, 0};
        }
        long allowedStart = Math.max(
                chunkStartNanos,
                activeStartNanos);
        long allowedEnd = Math.min(
                chunkEndNanos,
                activeEndNanos);
        if (allowedEnd <= allowedStart) {
            return new int[]{0, 0};
        }
        double framesPerNano = frameCount
                / (double) (chunkEndNanos - chunkStartNanos);
        int firstFrame = Math.max(
                0,
                Math.min(
                        frameCount,
                        (int) Math.ceil(
                                (allowedStart - chunkStartNanos)
                                        * framesPerNano)));
        int endFrame = Math.max(
                0,
                Math.min(
                        frameCount,
                        (int) Math.ceil(
                                (allowedEnd - chunkStartNanos)
                                        * framesPerNano)));
        return endFrame <= firstFrame
                ? new int[]{0, 0}
                : new int[]{firstFrame, endFrame};
    }

    private static String describeWorkers(
            List<CaptureWorker> activeWorkers,
            boolean directOpenAl,
            FfmpegCaptureWorker systemAudio) {
        List<String> descriptions = new ArrayList<String>();
        if (directOpenAl) {
            descriptions.add("game audio: direct OpenAL");
        }
        if (systemAudio != null && systemAudio.isActive()) {
            descriptions.add(
                    "game audio: DirectShow "
                            + systemAudio.deviceName);
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
                        RecordishMod.LOGGER.warn(
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
            RecordishMod.LOGGER.warn(
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

    /**
     * FFmpeg/DirectShow fallback used only for a named Windows loopback
     * endpoint. Each active recording span uses a fresh DirectShow process
     * that emits raw PCM. Java assembles those spans into one WAV, omitting
     * every paused interval and inserting silence only for real active-time
     * device startup gaps. Every FFmpeg process is still stopped with its
     * interactive {@code q} command.
     */
    private static final class FfmpegCaptureWorker {
        private static final long PROCESS_START_GRACE_MILLIS = 600L;
        private static final long PROCESS_STOP_TIMEOUT_SECONDS = 8L;
        private static final long PROCESS_KILL_TIMEOUT_SECONDS = 2L;
        private static final long UNBOUNDED_END_NANOS = Long.MAX_VALUE;

        final String executable;
        final String deviceName;
        final Path output;
        final AudioFormat format;
        final AtomicBoolean active = new AtomicBoolean(false);
        final Object processLock = new Object();
        final Object pcmLock = new Object();
        volatile AudioCaptureSession owner;
        volatile CaptureSpan currentSpan;
        volatile CaptureSegment currentSegment;
        volatile Thread restartThread;
        volatile Throwable failure;
        volatile WavWriter writer;
        volatile boolean writerClosed;
        volatile long firstSampleNanos;
        volatile long lastSampleNanos;
        volatile long sampleFrameCount;

        FfmpegCaptureWorker(
                String executable,
                String deviceName,
                Path output,
                AudioFormat format) {
            this.executable = executable;
            this.deviceName = deviceName;
            this.output = output;
            this.format = format;
        }

        boolean start(AudioCaptureSession owner) {
            this.owner = owner;
            try {
                Files.deleteIfExists(output);
                Path directory = output.toAbsolutePath().getParent();
                if (directory != null) {
                    Files.createDirectories(directory);
                }
                writer = new WavWriter(output, format);
                active.set(true);
                long activeStartNanos = System.nanoTime();
                CaptureSpan initialSpan = new CaptureSpan(
                        activeStartNanos,
                        owner.normalizeTimelineNanos(activeStartNanos));
                synchronized (pcmLock) {
                    currentSpan = initialSpan;
                }
                boolean started;
                synchronized (processLock) {
                    started = startSegmentLocked(initialSpan);
                }
                if (!started) {
                    active.set(false);
                    closeWriter();
                    return false;
                }
                RecordishMod.LOGGER.info(
                        "DirectShow system-audio capture started: {} -> {}",
                        deviceName,
                        output);
                return true;
            } catch (Throwable throwable) {
                failure = throwable;
                active.set(false);
                synchronized (processLock) {
                    finishCurrentSegmentLocked(0L);
                }
                closeWriter();
                return false;
            }
        }

        boolean isActive() {
            return active.get();
        }

        long publishPauseBoundary() {
            synchronized (pcmLock) {
                long pausedAtNanos = System.nanoTime();
                CaptureSpan span = currentSpan;
                closeSpanLocked(span, pausedAtNanos);
                return pausedAtNanos;
            }
        }

        void signalPausedCapture() {
            CaptureSpan span;
            CaptureSegment segment;
            synchronized (pcmLock) {
                span = currentSpan;
                segment = currentSegment;
            }
            if (segment != null && segment.span == span) {
                signalStop(segment);
            }
        }

        void resumeCapture(
                final long resumedAtNanos,
                final long timelineStartNanos) {
            final CaptureSpan resumedSpan;
            synchronized (pcmLock) {
                if (!active.get()) return;
                resumedSpan = new CaptureSpan(
                        resumedAtNanos,
                        timelineStartNanos);
                currentSpan = resumedSpan;
            }
            final Thread previousRestart = restartThread;
            Thread restart = new Thread(new Runnable() {
                @Override
                public void run() {
                    if (previousRestart != null
                            && previousRestart != Thread.currentThread()) {
                        joinThread(previousRestart, 12000L);
                    }
                    synchronized (processLock) {
                        AudioCaptureSession session = owner;
                        if (!active.get()
                                || session == null
                                || session.paused.get()
                                || currentSpan != resumedSpan
                                || resumedSpan.activeEndNanos
                                        != UNBOUNDED_END_NANOS) {
                            return;
                        }
                        /*
                         * Only the restart that still owns the current span
                         * may drain the previous process. A stale restart
                         * must never terminate a newer segment after rapid
                         * pause/resume cycles reorder lock acquisition.
                         */
                        finishCurrentSegmentLocked(0L);
                        session = owner;
                        if (!active.get()
                                || session == null
                                || session.paused.get()
                                || currentSpan != resumedSpan
                                || resumedSpan.activeEndNanos
                                        != UNBOUNDED_END_NANOS) {
                            return;
                        }
                        if (!startSegmentLocked(resumedSpan)) {
                            active.set(false);
                            RecordishMod.LOGGER.warn(
                                    "DirectShow system audio could not restart "
                                            + "after recording resumed: {}",
                                    failureDescription());
                        }
                    }
                }
            }, "Recordish-DirectShow-Restart");
            restart.setDaemon(true);
            restartThread = restart;
            restart.start();
        }

        void requestStop() {
            long stoppedAtNanos = System.nanoTime();
            active.set(false);
            CaptureSpan span;
            CaptureSegment segment;
            synchronized (pcmLock) {
                span = currentSpan;
                closeSpanLocked(span, stoppedAtNanos);
                segment = currentSegment;
            }
            if (segment != null && segment.span == span) {
                signalStop(segment);
            }
        }

        void awaitStop() {
            Thread restart = restartThread;
            if (restart != null && restart != Thread.currentThread()) {
                try {
                    restart.join(12000L);
                    if (restart.isAlive()) {
                        restart.interrupt();
                        restart.join(1000L);
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    if (failure == null) failure = exception;
                }
            }
            synchronized (processLock) {
                finishCurrentSegmentLocked(System.nanoTime());
            }
            closeWriter();
            restartThread = null;
        }

        AudioTrack toAudioTrack(long videoStartNanos) {
            try {
                if (sampleFrameCount <= 0L
                        || !Files.isRegularFile(output)
                        || Files.size(output) <= 44L) {
                    return null;
                }
                return new AudioTrack(
                        output,
                        TrackKind.GAME,
                        "Game audio (DirectShow: "
                                + deviceName + ")",
                        videoStartNanos,
                        firstSampleNanos,
                        lastSampleNanos,
                        sampleFrameCount,
                        format);
            } catch (IOException exception) {
                if (failure == null) failure = exception;
                return null;
            }
        }

        String failureDescription() {
            String message = failure == null
                    ? ""
                    : failure.getMessage();
            if (message == null || message.trim().isEmpty()) {
                message = failure == null
                        ? "unknown failure"
                        : failure.getClass().getSimpleName();
            }
            return message;
        }

        void deleteOutputQuietly() {
            try {
                Files.deleteIfExists(output);
            } catch (IOException ignored) {
            }
        }

        private boolean startSegmentLocked(CaptureSpan span) {
            if (isSpanInactive(span)) return true;
            List<String> arguments = buildCaptureArguments();
            CaptureSegment segment = null;
            try {
                ProcessBuilder builder =
                        StorageManager.createMediaProcess(
                                executable,
                                arguments);
                if (builder == null) {
                    throw new IOException(
                            "FFmpeg process creation was unavailable.");
                }
                Path directory = output.toAbsolutePath().getParent();
                if (directory != null) {
                    builder.directory(directory.toFile());
                }
                builder.redirectErrorStream(false);
                Process process = builder.start();
                segment = new CaptureSegment(
                        process,
                        span,
                        System.nanoTime());
                currentSegment = segment;
                startSegmentReaders(segment);
                AudioCaptureSession session = owner;
                if (!active.get()
                        || currentSpan != span
                        || span.activeEndNanos
                                != UNBOUNDED_END_NANOS
                        || (session != null
                                && session.paused.get())) {
                    signalStop(segment);
                }

                if (process.waitFor(
                        PROCESS_START_GRACE_MILLIS,
                        TimeUnit.MILLISECONDS)) {
                    awaitSegmentReaders(segment);
                    currentSegment = null;
                    if (span.activeEndNanos
                            != UNBOUNDED_END_NANOS
                            || !active.get()
                            || currentSpan != span
                            || (owner != null && owner.paused.get())) {
                        return true;
                    }
                    failure = new IOException(
                            "FFmpeg exited with code "
                                    + process.exitValue()
                                    + errorSuffix(segment));
                    return false;
                }
                return true;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                if (!isSpanInactive(span) && failure == null) {
                    failure = exception;
                }
            } catch (Throwable throwable) {
                if (!isSpanInactive(span) && failure == null) {
                    failure = throwable;
                }
            }
            if (segment != null) {
                destroySegmentProcess(segment);
                awaitSegmentReaders(segment);
            }
            currentSegment = null;
            return isSpanInactive(span);
        }

        private boolean isSpanInactive(CaptureSpan span) {
            return !active.get()
                    || span == null
                    || currentSpan != span
                    || span.activeEndNanos != UNBOUNDED_END_NANOS
                    || (owner != null && owner.paused.get());
        }

        private List<String> buildCaptureArguments() {
            List<String> arguments = new ArrayList<String>();
            Collections.addAll(
                    arguments,
                    "-hide_banner",
                    "-loglevel", "warning",
                    "-rtbufsize", "200M",
                    "-f", "dshow",
                    "-audio_buffer_size", "30",
                    "-use_wallclock_as_timestamps", "1",
                    "-i", "audio=" + deviceName,
                    "-af", "highpass=f=80,lowpass=f=18000,"
                            + "aresample=async=1:first_pts=0",
                    "-vn",
                    "-c:a", "pcm_s16le",
                    "-ar", Integer.toString(sampleRate()),
                    "-ac", Integer.toString(channelCount()),
                    "-f", "s16le",
                    "pipe:1");
            return arguments;
        }

        private void finishCurrentSegmentLocked(long activeEndNanos) {
            CaptureSegment segment = currentSegment;
            if (segment == null) return;
            if (activeEndNanos > 0L) {
                setSegmentEnd(segment, activeEndNanos);
            }
            signalStop(segment);
            Process process = segment.process;
            try {
                if (process.isAlive()
                        && !process.waitFor(
                                PROCESS_STOP_TIMEOUT_SECONDS,
                                TimeUnit.SECONDS)) {
                    process.destroy();
                    if (!process.waitFor(
                            PROCESS_KILL_TIMEOUT_SECONDS,
                            TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                        process.waitFor(
                                PROCESS_KILL_TIMEOUT_SECONDS,
                                TimeUnit.SECONDS);
                    }
                }
                if (!process.isAlive()) {
                    int exitCode = process.exitValue();
                    if (exitCode != 0 && exitCode != 255
                            && failure == null) {
                        failure = new IOException(
                                "FFmpeg exited with code " + exitCode
                                        + errorSuffix(segment));
                    }
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                if (failure == null) failure = exception;
            }
            awaitSegmentReaders(segment);
            currentSegment = null;
        }

        private void startSegmentReaders(final CaptureSegment segment) {
            segment.pcmThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    readSegmentPcm(segment);
                }
            }, "Recordish-DirectShow-PCM");
            segment.stderrThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    readSegmentErrors(segment);
                }
            }, "Recordish-DirectShow-Log");
            segment.pcmThread.setDaemon(true);
            segment.stderrThread.setDaemon(true);
            segment.pcmThread.start();
            segment.stderrThread.start();
        }

        private void readSegmentPcm(CaptureSegment segment) {
            InputStream input = segment.process.getInputStream();
            int frameSize = frameSize();
            int bufferFrames = Math.max(128, sampleRate() / 100);
            byte[] buffer = new byte[
                    Math.max(frameSize, bufferFrames * frameSize)];
            int pending = 0;
            try {
                while (true) {
                    int read = input.read(
                            buffer,
                            pending,
                            buffer.length - pending);
                    if (read < 0) break;
                    if (read == 0) continue;
                    int total = pending + read;
                    int aligned = total - total % frameSize;
                    if (aligned > 0) {
                        long completedNanos = System.nanoTime();
                        long frames = aligned / frameSize;
                        if (segment.firstPcmNanos <= 0L) {
                            segment.firstPcmNanos = Math.max(
                                    segment.processStartNanos,
                                    completedNanos
                                            - framesToNanos(frames));
                        }
                        long chunkStartNanos =
                                segment.firstPcmNanos
                                    + framesToNanos(
                                            segment.pcmFramesRead);
                        segment.pcmFramesRead += frames;
                        long chunkEndNanos =
                                segment.firstPcmNanos
                                    + framesToNanos(
                                            segment.pcmFramesRead);
                        writeAcceptedPcm(
                                segment,
                                buffer,
                                aligned,
                                chunkStartNanos,
                                chunkEndNanos);
                    }
                    pending = total - aligned;
                    if (pending > 0) {
                        System.arraycopy(
                                buffer,
                                aligned,
                                buffer,
                                0,
                                pending);
                    }
                }
            } catch (IOException exception) {
                if (segment.process.isAlive()
                        && segment.span.activeEndNanos
                                == UNBOUNDED_END_NANOS
                        && failure == null) {
                    failure = exception;
                }
            } finally {
                try {
                    input.close();
                } catch (IOException ignored) {
                }
            }
        }

        private void writeAcceptedPcm(
                CaptureSegment segment,
                byte[] pcm,
                int length,
                long chunkStartNanos,
                long chunkEndNanos) throws IOException {
            int frameSize = frameSize();
            int totalFrames = length / frameSize;
            if (totalFrames <= 0) return;

            synchronized (pcmLock) {
                CaptureSpan span = segment.span;
                long segmentEnd = span.activeEndNanos;
                AudioCaptureSession session = owner;
                if (session != null
                        && session.paused.get()
                        && segmentEnd == UNBOUNDED_END_NANOS) {
                    /*
                     * Defensive recovery for a future caller that publishes
                     * the pause flag without first closing this span. The
                     * atomic flag makes the earlier pause timestamp visible,
                     * so clip to it instead of discarding a valid pre-pause
                     * chunk.
                     */
                    long pauseBoundary = session.pauseStartedNanos;
                    if (pauseBoundary <= 0L) return;
                    closeSpanLocked(span, pauseBoundary);
                    segmentEnd = span.activeEndNanos;
                }
                int[] acceptedRange =
                        clipPcmFrameRange(
                                chunkStartNanos,
                                chunkEndNanos,
                                span.activeStartNanos,
                                segmentEnd,
                                totalFrames);
                int firstFrame = acceptedRange[0];
                int endFrame = acceptedRange[1];
                if (endFrame <= firstFrame) return;

                long acceptedRawStart =
                        chunkStartNanos
                                + framesToNanos(firstFrame);
                long timelineStart =
                        span.timelineStartNanos
                                + Math.max(
                                        0L,
                                        acceptedRawStart
                                                - span.activeStartNanos);
                if (firstSampleNanos <= 0L) {
                    firstSampleNanos = timelineStart;
                }

                long desiredFrame = Math.max(
                        0L,
                        Math.round(
                                (timelineStart - firstSampleNanos)
                                        * sampleRate()
                                        / 1_000_000_000.0D));
                if (desiredFrame > sampleFrameCount) {
                    writeSilenceFrames(
                            desiredFrame - sampleFrameCount);
                }

                int framesToWrite = endFrame - firstFrame;
                if (desiredFrame < sampleFrameCount) {
                    long overlap = sampleFrameCount - desiredFrame;
                    int skippedFrames = (int) Math.min(
                            overlap,
                            (long) framesToWrite);
                    firstFrame += skippedFrames;
                    framesToWrite -= skippedFrames;
                }
                if (framesToWrite <= 0) return;

                WavWriter activeWriter = writer;
                if (activeWriter == null || writerClosed) return;
                activeWriter.write(
                        pcm,
                        firstFrame * frameSize,
                        framesToWrite * frameSize);
                sampleFrameCount += framesToWrite;
                lastSampleNanos =
                        firstSampleNanos
                                + framesToNanos(sampleFrameCount);
            }
        }

        private void writeSilenceFrames(long frames)
                throws IOException {
            if (frames <= 0L) return;
            int frameSize = frameSize();
            byte[] silence = new byte[
                    Math.max(frameSize, frameSize * 2048)];
            WavWriter activeWriter = writer;
            while (frames > 0L
                    && activeWriter != null
                    && !writerClosed) {
                int batchFrames = (int) Math.min(
                        frames,
                        silence.length / frameSize);
                activeWriter.write(
                        silence,
                        0,
                        batchFrames * frameSize);
                sampleFrameCount += batchFrames;
                frames -= batchFrames;
            }
            if (firstSampleNanos > 0L) {
                lastSampleNanos =
                        firstSampleNanos
                                + framesToNanos(sampleFrameCount);
            }
        }

        private void readSegmentErrors(CaptureSegment segment) {
            InputStream input = segment.process.getErrorStream();
            byte[] buffer = new byte[2048];
            try {
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    synchronized (segment.stderrTail) {
                        if (segment.stderrTail.size() + read > 32768) {
                            segment.stderrTail.reset();
                        }
                        segment.stderrTail.write(buffer, 0, read);
                    }
                }
            } catch (IOException ignored) {
            } finally {
                try {
                    input.close();
                } catch (IOException ignored) {
                }
            }
        }

        private void setSegmentEnd(
                CaptureSegment segment,
                long activeEndNanos) {
            if (segment == null || activeEndNanos <= 0L) return;
            synchronized (pcmLock) {
                closeSpanLocked(segment.span, activeEndNanos);
            }
        }

        private void closeSpanLocked(
                CaptureSpan span,
                long activeEndNanos) {
            if (span == null || activeEndNanos <= 0L) return;
            span.activeEndNanos = Math.min(
                    span.activeEndNanos,
                    activeEndNanos);
        }

        private void signalStop(CaptureSegment segment) {
            if (segment == null
                    || !segment.stopSignalled.compareAndSet(
                            false,
                            true)) {
                return;
            }
            Process process = segment.process;
            if (!process.isAlive()) return;
            try {
                OutputStream command = process.getOutputStream();
                command.write('q');
                command.flush();
                command.close();
            } catch (IOException exception) {
                RecordishMod.LOGGER.debug(
                        "Unable to send the DirectShow FFmpeg stop command.",
                        exception);
            }
        }

        private void awaitSegmentReaders(CaptureSegment segment) {
            joinThread(segment.pcmThread, 2000L);
            joinThread(segment.stderrThread, 1000L);
        }

        private void destroySegmentProcess(CaptureSegment segment) {
            Process process = segment.process;
            if (!process.isAlive()) return;
            try {
                process.destroy();
                if (!process.waitFor(
                        PROCESS_KILL_TIMEOUT_SECONDS,
                        TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }

        private void closeWriter() {
            synchronized (pcmLock) {
                if (writerClosed) return;
                writerClosed = true;
                WavWriter activeWriter = writer;
                if (activeWriter != null) {
                    activeWriter.closeQuietly();
                }
                writer = null;
            }
        }

        private String errorSuffix(CaptureSegment segment) {
            String value;
            synchronized (segment.stderrTail) {
                value = new String(
                        segment.stderrTail.toByteArray(),
                        StandardCharsets.UTF_8);
            }
            value = value.replace('\r', ' ')
                    .replace('\n', ' ')
                    .trim();
            if (value.isEmpty()) return ".";
            if (value.length() > 500) {
                value = value.substring(value.length() - 500);
            }
            return ": " + value;
        }

        private int sampleRate() {
            return Math.max(
                    8000,
                    Math.round(format.getSampleRate()));
        }

        private int channelCount() {
            return Math.max(1, format.getChannels());
        }

        private int frameSize() {
            return Math.max(
                    1,
                    format.getFrameSize());
        }

        private long framesToNanos(long frames) {
            return Math.max(
                    0L,
                    Math.round(
                            frames * 1_000_000_000.0D
                                    / sampleRate()));
        }

        private static void joinThread(
                Thread thread,
                long timeoutMillis) {
            if (thread == null) return;
            try {
                thread.join(timeoutMillis);
                if (thread.isAlive()) {
                    thread.interrupt();
                    thread.join(500L);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }

        private static final class CaptureSpan {
            final long activeStartNanos;
            final long timelineStartNanos;
            volatile long activeEndNanos =
                    UNBOUNDED_END_NANOS;

            CaptureSpan(
                    long activeStartNanos,
                    long timelineStartNanos) {
                this.activeStartNanos = activeStartNanos;
                this.timelineStartNanos = timelineStartNanos;
            }
        }

        private static final class CaptureSegment {
            final Process process;
            final CaptureSpan span;
            final long processStartNanos;
            final AtomicBoolean stopSignalled =
                    new AtomicBoolean(false);
            final ByteArrayOutputStream stderrTail =
                    new ByteArrayOutputStream();
            volatile long firstPcmNanos;
            volatile long pcmFramesRead;
            volatile Thread pcmThread;
            volatile Thread stderrThread;

            CaptureSegment(
                    Process process,
                    CaptureSpan span,
                    long processStartNanos) {
                this.process = process;
                this.span = span;
                this.processStartNanos = processStartNanos;
            }
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
                    "Recordish-Audio-" + kind.name());
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
                    RecordishMod.LOGGER.warn(
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
