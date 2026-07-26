package dev.recordable;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;

/**
 * End-to-end smoke harness for the raw RGB writer and audio/video finalizer.
 *
 * <p>This deliberately avoids Minecraft/OpenGL so it can run in CI or from a
 * developer shell with only Java 8 and an FFmpeg executable.</p>
 */
public final class RecordingPipelineSmoke {
    private static final int WIDTH = 320;
    private static final int HEIGHT = 180;
    private static final int FPS = 30;
    private static final int FRAME_COUNT = FPS * 3;

    private RecordingPipelineSmoke() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length < 2) {
            throw new IllegalArgumentException(
                    "Usage: RecordingPipelineSmoke <ffmpeg> <working-directory>");
        }

        Path ffmpeg = Paths.get(arguments[0]).toAbsolutePath().normalize();
        Path root = Paths.get(arguments[1]).toAbsolutePath().normalize()
                .resolve(Long.toString(System.currentTimeMillis()));
        if (!Files.isRegularFile(ffmpeg)) {
            throw new IllegalArgumentException("FFmpeg not found: " + ffmpeg);
        }
        Files.createDirectories(root);

        RecordableConfig.initialize(
                root.toFile(),
                root.resolve("config").toFile());
        RecordableConfig config = RecordableConfig.get();
        config.outputDir = root.resolve("output").toString();
        config.ffmpegPath = ffmpeg.toString();
        config.useBundledFfmpeg = false;
        config.useFFmpegIfAvailable = true;
        config.format = "mp4";
        config.fps = FPS;
        config.quality = "balanced";
        config.encoder = RecordableConfig.VideoEncoder.SOFTWARE;
        config.perfModeGamePriority = false;
        config.audioDelayPreset = RecordableConfig.AudioDelayPreset.NONE;
        config.audioSyncOffsetMs = 0;
        config.captureAudio = true;
        config.captureMicrophone = false;
        config.separateAudioTracks = false;
        config.autoClipAudio = false;
        config.notifyReplayBuffer = false;
        config.notifyWarnings = false;
        config.sanitize();
        verifyPcmPauseBoundaryClipping();
        verifySoftwareCodecCacheRefresh(
                config,
                ffmpeg,
                root);

        FFmpegEncoder encoder = new FFmpegEncoder(
                config,
                WIDTH,
                HEIGHT,
                WIDTH,
                HEIGHT,
                FPS,
                "on-player-kill");
        FrameBufferPool pool =
                new FrameBufferPool(WIDTH * HEIGHT * 3, 16, true);
        encoder.start();

        long origin = System.nanoTime();
        long frameInterval = 1_000_000_000L / FPS;
        for (int index = 0; index < FRAME_COUNT; index++) {
            boolean accepted = false;
            while (!accepted) {
                byte[] pixels = pool.acquire();
                fillFrame(pixels, index);
                accepted = encoder.submit(new CapturedFrame(
                        pixels,
                        WIDTH,
                        HEIGHT,
                        origin + index * frameInterval,
                        pool));
                if (!accepted) {
                    Throwable failure = encoder.getFailure();
                    if (failure != null) {
                        throw new IllegalStateException(
                                "Encoder failed while accepting frames.",
                                failure);
                    }
                    Thread.sleep(5L);
                }
            }
        }

        Path temporaryVideo = encoder.finishVideo();
        Path wave = root.resolve("game-audio.wav");
        writeSineWave(wave, FRAME_COUNT / (double) FPS);
        AudioCaptureSession.AudioTrack track =
                new AudioCaptureSession.AudioTrack(
                        wave,
                        AudioCaptureSession.TrackKind.GAME,
                        "Synthetic game audio");
        Path output = RecordingFinalizer.finalizeRecording(
                temporaryVideo,
                encoder.getRequestedOutput(),
                config,
                Collections.singletonList(track));

        if (!Files.isRegularFile(output) || Files.size(output) <= 0L) {
            throw new IllegalStateException(
                    "Final recording is missing or empty: " + output);
        }
        Path expectedAutoClipDirectory =
                StorageManager.getAutoClipTriggerDirectory(
                        config,
                        "on-player-kill")
                        .toAbsolutePath()
                        .normalize();
        Path actualParent = output.getParent() == null
                ? null
                : output.getParent()
                        .toAbsolutePath()
                        .normalize();
        if (!expectedAutoClipDirectory.equals(actualParent)
                || !output.getFileName().toString()
                        .startsWith("recordable-")) {
            throw new IllegalStateException(
                    "Auto-clip was not routed into its trigger folder: "
                            + output);
        }
        System.out.println("RECORDABLE_PIPELINE_SMOKE_OK=" + output);
        System.out.println("WRITTEN_FRAMES=" + encoder.getWrittenFrames());
        System.out.println("DROPPED_ATTEMPTS=" + encoder.getDroppedFrames());

        Path replayOutput = runReplaySmoke(config);
        System.out.println("RECORDABLE_REPLAY_SMOKE_OK=" + replayOutput);
    }

    private static void verifyPcmPauseBoundaryClipping() {
        assertPcmRange(
                "full interval",
                AudioCaptureSession.clipPcmFrameRange(
                        1_000L,
                        2_000L,
                        1_000L,
                        2_000L,
                        10),
                0,
                10);
        assertPcmRange(
                "exact interior interval",
                AudioCaptureSession.clipPcmFrameRange(
                        1_000L,
                        2_000L,
                        1_200L,
                        1_800L,
                        10),
                2,
                8);
        assertPcmRange(
                "fractional pause boundary",
                AudioCaptureSession.clipPcmFrameRange(
                        1_000L,
                        2_000L,
                        1_250L,
                        1_750L,
                        10),
                3,
                8);
        assertPcmRange(
                "exact exclusive end",
                AudioCaptureSession.clipPcmFrameRange(
                        1_000L,
                        2_000L,
                        1_000L,
                        1_700L,
                        10),
                0,
                7);
        assertPcmRange(
                "left-clipped interval",
                AudioCaptureSession.clipPcmFrameRange(
                        1_000L,
                        2_000L,
                        0L,
                        1_500L,
                        10),
                0,
                5);
        assertPcmRange(
                "touching interval",
                AudioCaptureSession.clipPcmFrameRange(
                        1_000L,
                        2_000L,
                        2_000L,
                        2_500L,
                        10),
                0,
                0);
        assertPcmRange(
                "reversed active interval",
                AudioCaptureSession.clipPcmFrameRange(
                        1_000L,
                        2_000L,
                        1_800L,
                        1_200L,
                        10),
                0,
                0);
        assertPcmRange(
                "invalid chunk",
                AudioCaptureSession.clipPcmFrameRange(
                        2_000L,
                        2_000L,
                        0L,
                        Long.MAX_VALUE,
                        10),
                0,
                0);
        assertPcmRange(
                "zero frames",
                AudioCaptureSession.clipPcmFrameRange(
                        1_000L,
                        2_000L,
                        1_000L,
                        2_000L,
                        0),
                0,
                0);
    }

    private static void assertPcmRange(
            String description,
            int[] actual,
            int expectedStart,
            int expectedEnd) {
        if (actual == null
                || actual.length != 2
                || actual[0] != expectedStart
                || actual[1] != expectedEnd) {
            throw new IllegalStateException(
                    "Unexpected PCM range for "
                            + description
                            + ": "
                            + (actual == null
                                    ? "null"
                                    : actual[0] + ".." + actual[1])
                            + " (expected "
                            + expectedStart
                            + ".."
                            + expectedEnd
                            + ")");
        }
    }

    /**
     * Reproduces the in-app installer transition in one JVM: settings first
     * probes while FFmpeg is absent, then the managed executable appears.
     * A cold recording start must use the official libx264/VP9 defaults
     * without poisoning the cache, then asynchronous discovery must retain
     * or replace those defaults with proven codecs in the same JVM.
     */
    private static void verifySoftwareCodecCacheRefresh(
            RecordableConfig config,
            Path ffmpeg,
            Path root) throws Exception {
        config.ffmpegPath =
                root.resolve("missing-ffmpeg.exe").toString();
        config.useBundledFfmpeg = false;
        config.bundledFfmpegPath = "";
        FfmpegBundleManager.invalidateCache();
        FFmpegEncoder.detectAvailableEncoders();
        if (!"mpeg4".equals(
                FFmpegEncoder.getCachedSoftwareCodec())) {
            throw new IllegalStateException(
                    "Missing-FFmpeg software fallback was not mpeg4.");
        }

        config.ffmpegPath = ffmpeg.toString();
        FfmpegBundleManager.invalidateCache();
        FfmpegBundleManager.FfmpegStatus status =
                FfmpegBundleManager.detectFfmpeg();
        long generation =
                FfmpegBundleManager.getStatusGeneration(status);
        String cacheKeyBeforeColdStart =
                FFmpegEncoder.getCachedSoftwareCodecKey();
        String coldSoftware =
                FFmpegEncoder.resolveSoftwareCodecForStartForTest(
                        status,
                        generation);
        if (!"libx264".equals(coldSoftware)) {
            throw new IllegalStateException(
                    "Cold software start did not use libx264.");
        }
        String cacheKeyAfterColdStart =
                FFmpegEncoder.getCachedSoftwareCodecKey();
        if (cacheKeyBeforeColdStart == null
                ? cacheKeyAfterColdStart != null
                : !cacheKeyBeforeColdStart.equals(
                        cacheKeyAfterColdStart)) {
            throw new IllegalStateException(
                    "Cold software fallback poisoned the codec cache.");
        }
        if (!"libvpx-vp9".equals(
                FFmpegEncoder.resolveWebmCodec(
                        generation,
                        status.getExecutable()))) {
            throw new IllegalStateException(
                    "Cold WebM start did not use the official VP9 default.");
        }

        FFmpegEncoder.detectAvailableEncoders();
        if (FfmpegBundleManager.supportsEncoder("libx264")
                && !"libx264".equals(
                        FFmpegEncoder.getCachedSoftwareCodec())) {
            throw new IllegalStateException(
                    "Software codec cache did not refresh to libx264 "
                            + "after FFmpeg became available.");
        }
    }

    private static void fillFrame(byte[] pixels, int frameIndex) {
        int offset = 0;
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                pixels[offset++] = (byte) ((x + frameIndex * 3) & 0xFF);
                pixels[offset++] = (byte) ((y * 2 + frameIndex * 5) & 0xFF);
                pixels[offset++] = (byte) (((x ^ y) + frameIndex * 7) & 0xFF);
            }
        }
    }

    private static void writeSineWave(Path destination, double seconds)
            throws Exception {
        int sampleRate = 48_000;
        int channels = 2;
        int sampleFrames = (int) Math.round(sampleRate * seconds);
        byte[] pcm = new byte[sampleFrames * channels * 2];
        int offset = 0;
        for (int frame = 0; frame < sampleFrames; frame++) {
            short sample = (short) Math.round(
                    Math.sin(2.0D * Math.PI * 440.0D * frame / sampleRate)
                            * 8_000.0D);
            for (int channel = 0; channel < channels; channel++) {
                pcm[offset++] = (byte) (sample & 0xFF);
                pcm[offset++] = (byte) ((sample >>> 8) & 0xFF);
            }
        }

        AudioFormat format =
                new AudioFormat(sampleRate, 16, channels, true, false);
        try (AudioInputStream stream = new AudioInputStream(
                new ByteArrayInputStream(pcm),
                format,
                sampleFrames)) {
            AudioSystem.write(
                    stream,
                    AudioFileFormat.Type.WAVE,
                    destination.toFile());
        }
    }

    private static Path runReplaySmoke(RecordableConfig config)
            throws Exception {
        ReplayBuffer replay = ReplayBuffer.getInstance();
        try {
            int[] capped =
                    ReplayBuffer.capSourceDimensions(3840, 2160);
            if (capped[0] != 1280 || capped[1] != 720) {
                throw new IllegalStateException(
                        "Replay source cap was not 1280x720: "
                                + capped[0] + "x" + capped[1]);
            }
            if (ReplayBuffer.resolveTargetFps(
                    "high",
                    2160,
                    120) != 30) {
                throw new IllegalStateException(
                        "Replay frame-rate cap was not 30 FPS.");
            }

            replay.start(WIDTH, HEIGHT, FPS, 3, "balanced");
            for (int index = 0; index < FPS * 2; index++) {
                byte[] pixels = new byte[WIDTH * HEIGHT * 3];
                fillFrame(pixels, index + FRAME_COUNT);
                replay.addFrame(pixels);
                Thread.sleep(34L);
            }

            long writerDeadline = System.currentTimeMillis() + 10_000L;
            while (replay.getWrittenFrameSequence() < FPS
                    && System.currentTimeMillis() < writerDeadline) {
                Thread.sleep(20L);
            }
            ReplayBuffer.SaveResult result =
                    replay.saveBuffer("replay-smoke", 2);
            if (result != ReplayBuffer.SaveResult.ACCEPTED) {
                throw new IllegalStateException(
                        "Replay save was not accepted: " + result);
            }

            /*
             * Saving a snapshot must not freeze the rolling history. Feed
             * timestamped frames immediately while the FFmpeg snapshot worker
             * owns the pinned source chunks and verify that at least one was
             * accepted into the continuing buffer.
             */
            long submittedBeforeSave =
                    replay.getSubmittedFrameSequence();
            long syntheticTimestamp =
                    System.nanoTime() + 1_000_000_000L;
            boolean observedActiveSave = false;
            for (int index = 0; index < 12; index++) {
                if (!replay.isSaving()) break;
                observedActiveSave = true;
                byte[] pixels = new byte[WIDTH * HEIGHT * 3];
                fillFrame(
                        pixels,
                        index + FRAME_COUNT + FPS * 2);
                replay.addFrame(
                        pixels,
                        WIDTH,
                        HEIGHT,
                        syntheticTimestamp
                                + index * 34_000_000L);
            }
            if (!observedActiveSave
                    || replay.getSubmittedFrameSequence()
                        <= submittedBeforeSave) {
                throw new IllegalStateException(
                        "Replay history did not continue accepting frames "
                                + "during snapshot save.");
            }

            long saveDeadline = System.currentTimeMillis() + 60_000L;
            while (replay.isSaving()
                    && System.currentTimeMillis() < saveDeadline) {
                Thread.sleep(50L);
            }
            if (replay.isSaving()) {
                throw new IllegalStateException(
                        "Replay save did not finish within 60 seconds.");
            }

            Path saved = newestMatching(
                    config.getOutputDirectory(),
                    "replay-smoke-",
                    "." + config.getFormat());
            if (saved == null || Files.size(saved) <= 0L) {
                throw new IllegalStateException(
                        "Replay output is missing or empty.");
            }
            return saved;
        } finally {
            replay.stop();
        }
    }

    private static Path newestMatching(
            Path directory,
            String prefix,
            String suffix) throws Exception {
        if (!Files.isDirectory(directory)) return null;
        Path newest = null;
        long newestModified = Long.MIN_VALUE;
        try (DirectoryStream<Path> entries =
                Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (!Files.isRegularFile(entry)
                        || !name.startsWith(prefix)
                        || !name.endsWith(suffix)) {
                    continue;
                }
                long modified = Files.getLastModifiedTime(entry).toMillis();
                if (newest == null || modified > newestModified) {
                    newest = entry;
                    newestModified = modified;
                }
            }
        }
        return newest;
    }
}
