package dev.recordable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts the crash-tolerant Matroska video stream into the container chosen
 * by the user. Audio inputs are added by {@link AudioCaptureSession} when one
 * is active.
 */
public final class RecordingFinalizer {
    private static final double MIN_DRIFT_CHECK_SECONDS = 10.0D;
    private static final double MAX_CLOCK_CORRECTION_RATIO = 0.01D;
    private static final Pattern HEADER_DURATION = Pattern.compile(
            "Duration:\\s*(\\d+):(\\d+):(\\d+(?:\\.\\d+)?)");

    private RecordingFinalizer() {
    }

    public static Path finalizeVideo(
            Path temporaryVideo,
            Path requestedOutput,
            RecordableConfig config) throws IOException {
        return finalizeRecording(
                temporaryVideo,
                requestedOutput,
                config,
                Collections.<AudioCaptureSession.AudioTrack>emptyList());
    }

    public static Path finalizeRecording(
            Path temporaryVideo,
            Path requestedOutput,
            RecordableConfig config,
            List<AudioCaptureSession.AudioTrack> audioTracks) throws IOException {
        if (temporaryVideo == null || !Files.isRegularFile(temporaryVideo)
                || Files.size(temporaryVideo) <= 0L) {
            throw new IOException("The temporary video stream is missing or empty.");
        }
        if (requestedOutput == null) {
            throw new IOException("No recording output path was selected.");
        }

        RecordableConfig effective =
                config == null ? RecordableConfig.get() : config;
        Files.createDirectories(requestedOutput.toAbsolutePath().getParent());
        List<AudioCaptureSession.AudioTrack> usableTracks =
                filterUsableTracks(audioTracks);
        String extension = extension(requestedOutput);

        if (usableTracks.isEmpty() && ".mkv".equals(extension)) {
            moveSafely(temporaryVideo, requestedOutput);
            return requestedOutput;
        }

        String executable =
                StorageManager.resolveFfmpegExecutable(effective);
        if (isBlank(executable)) {
            throw new IOException(
                    "FFmpeg disappeared before the recording could be finalized. "
                            + "The recoverable video and raw audio remain at "
                            + temporaryVideo + recoveryAudioSuffix(usableTracks));
        }

        Path partial = requestedOutput.resolveSibling(
                stem(requestedOutput) + ".part" + extension);
        Files.deleteIfExists(partial);

        List<String> arguments = new ArrayList<String>();
        Collections.addAll(
                arguments,
                "-nostdin",
                "-hide_banner",
                "-loglevel", "warning",
                "-y",
                "-i", temporaryVideo.toAbsolutePath().toString());

        for (AudioCaptureSession.AudioTrack track : usableTracks) {
            Collections.addAll(
                    arguments,
                    "-i", track.getPath().toAbsolutePath().toString());
        }

        double videoDurationSeconds = probeVideoDurationSeconds(
                temporaryVideo,
                effective,
                executable);
        Collections.addAll(arguments, "-map", "0:v:0", "-c:v", "copy");
        addAudioArguments(
                arguments,
                usableTracks,
                effective,
                extension,
                videoDurationSeconds);

        if (".mp4".equals(extension) || ".mov".equals(extension)) {
            Collections.addAll(arguments, "-movflags", "+faststart");
        }
        arguments.add(partial.toAbsolutePath().toString());

        StorageManager.ProcessResult result;
        try {
            result = StorageManager.runProcess(
                    StorageManager.createMediaProcess(executable, arguments),
                    2L,
                    TimeUnit.HOURS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while finalizing the recording.", exception);
        }

        if (!result.succeeded() || !Files.isRegularFile(partial)
                || Files.size(partial) <= 0L) {
            Files.deleteIfExists(partial);
            String reason = result.timedOut()
                    ? "FFmpeg timed out"
                    : "FFmpeg exited with code " + result.exitCode();
            String details = trimForMessage(result.output());
            throw new IOException(reason + " while finalizing the recording"
                    + (details.isEmpty() ? "." : ": " + details)
                    + " Recoverable video: " + temporaryVideo
                    + recoveryAudioSuffix(usableTracks));
        }

        moveSafely(partial, requestedOutput);
        Files.deleteIfExists(temporaryVideo);
        for (AudioCaptureSession.AudioTrack track : usableTracks) {
            try {
                Files.deleteIfExists(track.getPath());
            } catch (IOException cleanupFailure) {
                RecordableMod.LOGGER.debug(
                        "Unable to remove temporary audio track {}.",
                        track.getPath(),
                        cleanupFailure);
            }
        }
        return requestedOutput;
    }

    /**
     * Publishes a trailer-complete Matroska stream when the requested
     * container/audio mux cannot finish. This turns a hidden
     * {@code *.video.mkv} intermediate into a visible, playable recovery file
     * instead of making a shutdown failure look like total data loss.
     */
    public static Path publishRecoverableVideo(
            Path sealedVideo,
            Path requestedOutput) {
        try {
            if (requestedOutput != null
                    && Files.isRegularFile(requestedOutput)
                    && Files.size(requestedOutput) > 0L) {
                return requestedOutput;
            }
            if (sealedVideo == null
                    || !Files.isRegularFile(sealedVideo)
                    || Files.size(sealedVideo) <= 0L) {
                return null;
            }

            if (requestedOutput != null) {
                Path incomplete = requestedOutput.resolveSibling(
                        stem(requestedOutput)
                                + ".part"
                                + extension(requestedOutput));
                try {
                    Files.deleteIfExists(incomplete);
                } catch (IOException cleanupFailure) {
                    RecordableMod.LOGGER.debug(
                            "Unable to remove incomplete finalization output {}.",
                            incomplete,
                            cleanupFailure);
                }
            }

            Path directory = requestedOutput != null
                    && requestedOutput.toAbsolutePath().getParent() != null
                            ? requestedOutput.toAbsolutePath().getParent()
                            : sealedVideo.toAbsolutePath().getParent();
            if (directory == null) {
                return sealedVideo;
            }
            Files.createDirectories(directory);
            String baseName = requestedOutput == null
                    ? stem(sealedVideo)
                    : stem(requestedOutput);
            Path recovery = directory.resolve(
                    baseName + "-recovered.mkv");
            int suffix = 1;
            while (Files.exists(recovery)) {
                recovery = directory.resolve(
                        baseName + "-recovered-" + suffix++ + ".mkv");
            }
            moveSafely(sealedVideo, recovery);
            RecordableMod.LOGGER.warn(
                    "Published playable recovery video at {}.",
                    recovery);
            return recovery;
        } catch (IOException recoveryFailure) {
            RecordableMod.LOGGER.error(
                    "Unable to publish the sealed recovery video. It remains "
                            + "at {}.",
                    sealedVideo,
                    recoveryFailure);
            return sealedVideo;
        }
    }

    private static void addAudioArguments(
            List<String> arguments,
            List<AudioCaptureSession.AudioTrack> tracks,
            RecordableConfig config,
            String extension,
            double videoDurationSeconds) {
        if (tracks.isEmpty()) {
            arguments.add("-an");
            return;
        }

        RecordableConfig effective = config == null ? RecordableConfig.get() : config;
        StringBuilder filter = new StringBuilder();
        for (int index = 0; index < tracks.size(); index++) {
            AudioCaptureSession.AudioTrack track = tracks.get(index);
            TrackTiming timing = resolveTrackTiming(
                    track,
                    effective,
                    videoDurationSeconds);
            if (filter.length() > 0) filter.append(';');
            filter.append('[').append(index + 1).append(":a:0]");
            if (timing.trimSeconds > 0.0005D) {
                filter.append("atrim=start=")
                        .append(formatSeconds(timing.trimSeconds))
                        .append(',');
            }
            filter.append("asetpts=PTS-STARTPTS");
            if (Math.abs(timing.tempo - 1.0D) > 0.00001D) {
                filter.append(",atempo=")
                        .append(String.format(
                                Locale.ROOT,
                                "%.8f",
                                timing.tempo));
            }
            filter.append(",volume=")
                    .append(String.format(
                            Locale.ROOT,
                            "%.4f",
                            volumeFor(track, effective)));
            if (effective.noiseSuppression
                    && track.getKind()
                            == AudioCaptureSession.TrackKind.MICROPHONE) {
                filter.append(",highpass=f=80,lowpass=f=12000")
                        .append(",afftdn=nf=-25");
            }
            if (timing.delayMillis > 0L) {
                filter.append(",adelay=")
                        .append(buildDelayExpression(
                                timing.delayMillis,
                                track.getChannelCount(),
                                effective.audioChannelCount));
            }
            // An indefinitely padded audio output makes the video stream the
            // authoritative endpoint when -shortest is applied. A failed or
            // late audio device can no longer truncate a valid recording.
            filter.append(",apad");
            filter.append("[a").append(index).append(']');

            RecordableMod.LOGGER.info(
                    "Audio timing {}: measured start={}ms, user shift={}ms, "
                            + "trim={}ms, delay={}ms, tempo={}",
                    track.getDisplayName(),
                    Math.round(timing.measuredOffsetSeconds * 1000.0D),
                    Math.round(timing.userOffsetSeconds * 1000.0D),
                    Math.round(timing.trimSeconds * 1000.0D),
                    timing.delayMillis,
                    String.format(Locale.ROOT, "%.8f", timing.tempo));
        }

        if (effective.separateAudioTracks) {
            Collections.addAll(
                    arguments,
                    "-filter_complex", filter.toString());
            for (int index = 0; index < tracks.size(); index++) {
                Collections.addAll(arguments, "-map", "[a" + index + "]");
            }
        } else {
            filter.append(';');
            for (int index = 0; index < tracks.size(); index++) {
                filter.append("[a").append(index).append(']');
            }
            if (tracks.size() == 1) {
                filter.append("anull[aout]");
            } else {
                filter.append("amix=inputs=").append(tracks.size())
                        .append(":duration=longest")
                        .append(":dropout_transition=0")
                        .append(":normalize=0[aout]");
            }
            Collections.addAll(
                    arguments,
                    "-filter_complex", filter.toString(),
                    "-map", "[aout]");
        }

        RecordableConfig.AudioEncoder encoder = effective.audioEncoder;
        if (encoder == null || !encoder.supportsContainer(extension.substring(1))) {
            encoder = ".webm".equals(extension)
                    ? RecordableConfig.AudioEncoder.OPUS
                    : RecordableConfig.AudioEncoder.AAC;
        }
        Collections.addAll(arguments, "-c:a", encoder.ffmpegCodec);
        if (encoder.defaultBitrateKbps > 0) {
            Collections.addAll(
                    arguments,
                    "-b:a", Math.max(32, effective.audioBitrateKbps) + "k");
        }
        Collections.addAll(
                arguments,
                "-ar", Integer.toString(effective.audioSampleRate),
                "-ac", Integer.toString(effective.audioChannelCount));

        if (effective.separateAudioTracks) {
            for (int index = 0; index < tracks.size(); index++) {
                Collections.addAll(
                        arguments,
                        "-metadata:s:a:" + index,
                        "title=" + tracks.get(index).getDisplayName());
            }
        }
        arguments.add("-shortest");
    }

    private static TrackTiming resolveTrackTiming(
            AudioCaptureSession.AudioTrack track,
            RecordableConfig config,
            double videoDurationSeconds) {
        double measuredOffsetSeconds = 0.0D;
        if (track.getVideoStartNanos() > 0L
                && track.getFirstSampleNanos() > 0L) {
            measuredOffsetSeconds =
                    (track.getFirstSampleNanos()
                            - track.getVideoStartNanos())
                            / 1_000_000_000.0D;
        }
        if (!isFinite(measuredOffsetSeconds)
                || Math.abs(measuredOffsetSeconds) > 300.0D) {
            measuredOffsetSeconds = 0.0D;
        }

        double userOffsetSeconds =
                config.getEffectiveAudioDelay() / 1000.0D;
        double totalOffsetSeconds =
                measuredOffsetSeconds + userOffsetSeconds;
        double trimSeconds = Math.max(0.0D, -totalOffsetSeconds);
        long delayMillis = totalOffsetSeconds <= 0.0D
                ? 0L
                : Math.max(
                        0L,
                        Math.round(totalOffsetSeconds * 1000.0D));

        double tempo = resolveClockTempo(
                track,
                measuredOffsetSeconds,
                videoDurationSeconds);
        return new TrackTiming(
                measuredOffsetSeconds,
                userOffsetSeconds,
                trimSeconds,
                delayMillis,
                tempo);
    }

    /**
     * Corrects only small clock-rate discrepancies. Large duration
     * mismatches indicate a late, disconnected, or partial capture and are
     * padded instead of being audibly stretched across the whole video.
     */
    private static double resolveClockTempo(
            AudioCaptureSession.AudioTrack track,
            double measuredOffsetSeconds,
            double videoDurationSeconds) {
        double nominalDuration = track.getNominalDurationSeconds();
        double captureTrim = Math.max(0.0D, -measuredOffsetSeconds);
        double sourceDuration = nominalDuration - captureTrim;
        if (!isFinite(sourceDuration)
                || sourceDuration < MIN_DRIFT_CHECK_SECONDS) {
            return 1.0D;
        }

        double targetDuration;
        if (isFinite(videoDurationSeconds)
                && videoDurationSeconds > 0.0D) {
            targetDuration = videoDurationSeconds
                    - Math.max(0.0D, measuredOffsetSeconds);
        } else {
            targetDuration = track.getObservedDurationSeconds()
                    - captureTrim;
        }
        if (!isFinite(targetDuration)
                || targetDuration < MIN_DRIFT_CHECK_SECONDS) {
            return 1.0D;
        }

        double tempo = sourceDuration / targetDuration;
        if (!isFinite(tempo)
                || Math.abs(tempo - 1.0D)
                        > MAX_CLOCK_CORRECTION_RATIO
                || Math.abs(sourceDuration - targetDuration) < 0.005D) {
            return 1.0D;
        }
        return tempo;
    }

    private static String buildDelayExpression(
            long delayMillis,
            int trackChannels,
            int configuredChannels) {
        int channels = trackChannels > 0
                ? trackChannels
                : (configuredChannels > 0 ? configuredChannels : 2);
        channels = Math.max(1, Math.min(8, channels));
        StringBuilder expression = new StringBuilder();
        for (int channel = 0; channel < channels; channel++) {
            if (expression.length() > 0) expression.append('|');
            expression.append(delayMillis);
        }
        return expression.toString();
    }

    private static String formatSeconds(double seconds) {
        return String.format(Locale.ROOT, "%.6f", seconds);
    }

    private static double probeVideoDurationSeconds(
            Path video,
            RecordableConfig config,
            String ffmpegExecutable) {
        String ffprobeExecutable =
                StorageManager.resolveFfprobeExecutable(config);
        if (!isBlank(ffprobeExecutable)) {
            List<String> arguments = new ArrayList<String>();
            Collections.addAll(
                    arguments,
                    "-nostdin",
                    "-v", "error",
                    "-select_streams", "v:0",
                    "-show_entries", "format=duration:stream=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1",
                    video.toAbsolutePath().toString());
            try {
                StorageManager.ProcessResult result =
                        StorageManager.runProcess(
                                StorageManager.createMediaProcess(
                                        ffprobeExecutable,
                                        arguments),
                                10L,
                                TimeUnit.SECONDS);
                double duration = parseNumericDuration(result.output());
                if (!result.timedOut() && duration > 0.0D) {
                    return duration;
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return -1.0D;
            } catch (IOException exception) {
                RecordableMod.LOGGER.debug(
                        "Unable to probe the recording duration with FFprobe.",
                        exception);
            }
        }

        if (isBlank(ffmpegExecutable)) {
            return -1.0D;
        }
        List<String> arguments = new ArrayList<String>();
        Collections.addAll(
                arguments,
                "-nostdin",
                "-hide_banner",
                "-i", video.toAbsolutePath().toString());
        try {
            StorageManager.ProcessResult result =
                    StorageManager.runProcess(
                            StorageManager.createMediaProcess(
                                    ffmpegExecutable,
                                    arguments),
                            10L,
                            TimeUnit.SECONDS);
            Matcher matcher = HEADER_DURATION.matcher(result.output());
            if (matcher.find()) {
                double duration =
                        Double.parseDouble(matcher.group(1)) * 3600.0D
                                + Double.parseDouble(matcher.group(2)) * 60.0D
                                + Double.parseDouble(matcher.group(3));
                return isFinite(duration) && duration > 0.0D
                        ? duration
                        : -1.0D;
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (IOException exception) {
            RecordableMod.LOGGER.debug(
                    "Unable to read the recording duration from FFmpeg.",
                    exception);
        }
        return -1.0D;
    }

    private static double parseNumericDuration(String output) {
        if (output == null) {
            return -1.0D;
        }
        String[] candidates = output.split("\\r?\\n");
        double longest = -1.0D;
        for (String candidate : candidates) {
            try {
                double parsed = Double.parseDouble(candidate.trim());
                if (isFinite(parsed) && parsed > longest) {
                    longest = parsed;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return longest;
    }

    private static double volumeFor(
            AudioCaptureSession.AudioTrack track,
            RecordableConfig config) {
        double master = Math.max(0, config.audioVolume) / 100.0D;
        double source = track.getKind()
                == AudioCaptureSession.TrackKind.MICROPHONE
                ? Math.max(0, config.microphoneVolume) / 100.0D
                : Math.max(0, config.gameAudioVolume) / 100.0D;
        double boost = Math.pow(
                10.0D,
                Math.max(0, config.audioVolumeBoostDb) / 20.0D);
        return Math.max(0.0D, Math.min(16.0D, master * source * boost));
    }

    private static final class TrackTiming {
        final double measuredOffsetSeconds;
        final double userOffsetSeconds;
        final double trimSeconds;
        final long delayMillis;
        final double tempo;

        TrackTiming(
                double measuredOffsetSeconds,
                double userOffsetSeconds,
                double trimSeconds,
                long delayMillis,
                double tempo) {
            this.measuredOffsetSeconds = measuredOffsetSeconds;
            this.userOffsetSeconds = userOffsetSeconds;
            this.trimSeconds = trimSeconds;
            this.delayMillis = delayMillis;
            this.tempo = tempo;
        }
    }

    private static List<AudioCaptureSession.AudioTrack> filterUsableTracks(
            List<AudioCaptureSession.AudioTrack> tracks) {
        if (tracks == null || tracks.isEmpty()) {
            return Collections.emptyList();
        }
        List<AudioCaptureSession.AudioTrack> result =
                new ArrayList<AudioCaptureSession.AudioTrack>();
        for (AudioCaptureSession.AudioTrack track : tracks) {
            if (track == null || track.getPath() == null) continue;
            try {
                if (Files.isRegularFile(track.getPath())
                        && Files.size(track.getPath()) > 44L) {
                    result.add(track);
                }
            } catch (IOException ignored) {
            }
        }
        return result;
    }

    private static void moveSafely(Path source, Path destination)
            throws IOException {
        try {
            Files.move(
                    source,
                    destination,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicMoveFailure) {
            Files.move(
                    source,
                    destination,
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String extension(Path path) {
        String filename = path.getFileName().toString();
        int dot = filename.lastIndexOf('.');
        if (dot < 0) return ".mkv";
        return filename.substring(dot).toLowerCase(Locale.ROOT);
    }

    private static String stem(Path path) {
        String filename = path.getFileName().toString();
        int dot = filename.lastIndexOf('.');
        return dot <= 0 ? filename : filename.substring(0, dot);
    }

    private static String trimForMessage(String value) {
        if (value == null) return "";
        String normalized = value.replace('\r', ' ').replace('\n', ' ').trim();
        if (normalized.length() <= 500) return normalized;
        return normalized.substring(normalized.length() - 500);
    }

    private static String recoveryAudioSuffix(
            List<AudioCaptureSession.AudioTrack> tracks) {
        if (tracks == null || tracks.isEmpty()) {
            return ".";
        }
        StringBuilder message = new StringBuilder("; raw audio: ");
        for (AudioCaptureSession.AudioTrack track : tracks) {
            if (track == null || track.getPath() == null) continue;
            if (message.length() > "; raw audio: ".length()) {
                message.append(", ");
            }
            message.append(track.getPath());
        }
        if (message.length() == "; raw audio: ".length()) {
            return ".";
        }
        return message.append('.').toString();
    }

    private static boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
