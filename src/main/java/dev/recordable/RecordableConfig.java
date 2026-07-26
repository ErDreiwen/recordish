package dev.recordable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.recordable.theme.ThemePreset;
import org.lwjgl.input.Keyboard;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Java 8-compatible configuration model matching Record-able V1-0.08 legacy.
 */
public final class RecordableConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Pattern HEX_COLOR = Pattern.compile("^#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?$");
    private static volatile RecordableConfig instance;
    private static Path gameDirectory;
    private static Path configPath;

    public static final String[] FORMATS = {"mp4", "mkv", "mov", "webm"};
    public static final int[] FPS_VALUES = {30, 60, 120};
    public static final int[] AUTO_CLIP_FPS_VALUES = {15, 20, 24, 30, 45, 60};
    public static final String[] RESOLUTIONS = {"native", "1080p", "720p", "480p"};
    public static final String[] QUALITIES = {"high", "balanced", "performance"};
    public static final String[] REPLAY_QUALITIES = {"source", "balanced", "performance", "high"};
    public static final String[] AUTO_RECORD_TRIGGERS = {"world_join", "game_start", "manual"};
    public static final String[] AUTO_STOP_TRIGGERS = {"world_leave", "game_quit", "never"};
    public static final String[] AUDIO_CHANNELS = {"auto", "mono", "stereo"};
    public static final int[] AUDIO_SAMPLE_RATES = {44100, 48000};
    public static final String[] TEMPLATES = {"custom", "cinematic", "balanced", "pvp_clip"};
    public static final String[] GALLERY_SORT_MODES = {
        "newest", "oldest", "name_az", "name_za", "largest", "smallest", "longest", "shortest"
    };
    public static final String[] DEVICE_PRESETS = {
        "android_phone", "low_end_pc", "mid_end_pc", "high_end_pc", "nasa"
    };
    public static final int MAX_WATERMARK_SLOTS = 4;
    public static final String DEFAULT_FILENAME_PATTERN =
            "recordable-{datetime}";

    public static final int DEFAULT_HOTKEY_TOGGLE_RECORDING = Keyboard.KEY_MINUS;
    public static final int DEFAULT_HOTKEY_PAUSE_RESUME = Keyboard.KEY_EQUALS;
    public static final int DEFAULT_HOTKEY_OPEN_SETTINGS = Keyboard.KEY_F9;
    public static final int DEFAULT_HOTKEY_OPEN_VIDEO_COLLECTION = Keyboard.KEY_F12;
    public static final int DEFAULT_HOTKEY_PUSH_TO_TALK = Keyboard.KEY_V;

    public enum VideoEncoder {
        SOFTWARE("Software (x264)", "libx264"),
        NVIDIA("NVIDIA NVENC", "h264_nvenc"),
        AMD("AMD AMF", "h264_amf"),
        INTEL("Intel QuickSync", "h264_qsv");

        public final String displayName;
        public final String ffmpegCodec;

        VideoEncoder(String displayName, String ffmpegCodec) {
            this.displayName = displayName;
            this.ffmpegCodec = ffmpegCodec;
        }
    }

    public enum AudioEncoder {
        AAC("AAC", "aac", new String[]{"mp4", "mkv", "mov"}, 192),
        OPUS("Opus", "libopus", new String[]{"webm", "mkv"}, 128),
        MP3("MP3", "libmp3lame", new String[]{"mp4", "mkv", "mov"}, 192),
        FLAC("FLAC (Lossless)", "flac", new String[]{"mkv", "mov", "mp4"}, 0);

        public final String displayName;
        public final String ffmpegCodec;
        public final String[] supportedContainers;
        public final int defaultBitrateKbps;

        AudioEncoder(String displayName, String ffmpegCodec, String[] supportedContainers, int defaultBitrateKbps) {
            this.displayName = displayName;
            this.ffmpegCodec = ffmpegCodec;
            this.supportedContainers = supportedContainers;
            this.defaultBitrateKbps = defaultBitrateKbps;
        }

        public boolean supportsContainer(String container) {
            if (isBlank(container)) {
                return false;
            }
            for (String supported : supportedContainers) {
                if (supported.equalsIgnoreCase(container.trim())) {
                    return true;
                }
            }
            return false;
        }
    }

    public enum AudioDelayPreset {
        AUTO("Auto", 0), NONE("None", 0), DESKTOP("Desktop", 46),
        ANDROID("Android", 60), CUSTOM("Custom", -1);

        public final String displayName;
        public final int defaultMs;

        AudioDelayPreset(String displayName, int defaultMs) {
            this.displayName = displayName;
            this.defaultMs = defaultMs;
        }

        public AudioDelayPreset next() {
            AudioDelayPreset[] all = values();
            return all[(ordinal() + 1) % all.length];
        }

        public AudioDelayPreset previous() {
            AudioDelayPreset[] all = values();
            return all[(ordinal() - 1 + all.length) % all.length];
        }
    }

    public enum OverlayPosition {
        TOP_LEFT("Top-Left"), TOP_RIGHT("Top-Right"), BOTTOM_LEFT("Bottom-Left"),
        BOTTOM_RIGHT("Bottom-Right"), CENTER_TOP("Center-Top");

        public final String displayName;

        OverlayPosition(String displayName) {
            this.displayName = displayName;
        }

        public OverlayPosition next() {
            OverlayPosition[] all = values();
            return all[(ordinal() + 1) % all.length];
        }

        public OverlayPosition previous() {
            OverlayPosition[] all = values();
            return all[(ordinal() - 1 + all.length) % all.length];
        }
    }

    public enum OverlayStyleHud {
        CLASSIC("Speed-Runner's Classic"), VHS("VHS"), SYNTHWAVE("Synthwave"), NONE("None");

        public final String displayName;

        OverlayStyleHud(String displayName) {
            this.displayName = displayName;
        }

        public OverlayStyleHud next() {
            OverlayStyleHud[] all = values();
            return all[(ordinal() + 1) % all.length];
        }

        public OverlayStyleHud previous() {
            OverlayStyleHud[] all = values();
            return all[(ordinal() - 1 + all.length) % all.length];
        }
    }

    // Video and audio
    public String format = "mp4";
    public int fps = 60;
    public String resolution = "1080p";
    public String quality = "balanced";
    public String bitrate = "auto";
    public VideoEncoder encoder = VideoEncoder.SOFTWARE;
    public boolean captureAudio = true;
    public String audioSource = "game";
    public String audioDevice = "auto";
    public AudioEncoder audioEncoder = AudioEncoder.AAC;
    public int audioBitrateKbps = 192;
    public int audioSampleRate = 48000;
    public int audioChannelCount = 2;
    public int audioVolume = 100;
    public int audioVolumeBoostDb = 0;
    public boolean captureMicrophone = false;
    public boolean includeVoiceChat = true;
    public String microphoneDevice = "auto";
    public int gameAudioVolume = 100;
    public int microphoneVolume = 80;
    public boolean microphonePushToTalk = false;
    public boolean noiseSuppression = false;
    public String audioBitrate = "192k";
    public String audioChannels = "stereo";
    public boolean useFFmpegIfAvailable = true;
    public boolean useBundledFfmpeg = true;
    public String bundledFfmpegPath = "";
    public String ffmpegPath = "";

    // General and recording behavior
    public boolean enabled = true;
    public String outputDir = "recordings";
    public String filenamePattern = DEFAULT_FILENAME_PATTERN;
    public boolean showOverlay = true;
    public boolean ffmpegFirstRunShown = false;
    public boolean bakeInOverlay = false;
    public boolean showHomeButton = true;
    public boolean stopOnDisconnect = true;
    public boolean showPerformanceStats = false;
    public boolean saveToGalleryOnAndroid = false;
    public boolean autoCompressOnAndroid = false;
    public int maxFileSizeMB = 0;
    public String overlayColor = "#FF0000";
    public String menuAccentColor = "#FF0000";
    public OverlayPosition overlayPosition = OverlayPosition.TOP_LEFT;
    public int overlayScale = 100;
    public AudioDelayPreset audioDelayPreset = AudioDelayPreset.AUTO;
    public int audioSyncOffsetMs = 46;
    public boolean autoRecord = true;
    public String autoRecordTrigger = "world_join";
    public String autoStopTrigger = "world_leave";
    public int autoRecordDelay = 2;

    // Keybinds and presets
    public int hotkeyToggleRecording = DEFAULT_HOTKEY_TOGGLE_RECORDING;
    public int hotkeyPauseResume = DEFAULT_HOTKEY_PAUSE_RESUME;
    public int hotkeyOpenSettings = DEFAULT_HOTKEY_OPEN_SETTINGS;
    public int hotkeyOpenVideoCollection = DEFAULT_HOTKEY_OPEN_VIDEO_COLLECTION;
    public int hotkeyPushToTalk = DEFAULT_HOTKEY_PUSH_TO_TALK;
    public int hotkeyToggleCensorOverlay = Keyboard.KEY_NONE;
    public int hotkeyOpenCensorEditor = Keyboard.KEY_NONE;
    public String activeTemplate = "custom";
    public String selectedDevicePreset = "mid_end_pc";

    // Storage and safety
    public int diskSpaceWarnPercent = 90;
    public int diskSpaceBlockPercent = 95;
    public int diskSpaceMinFreeMB = 500;
    public boolean autoCleanupEnabled = false;
    public int autoCleanupOlderThanDays = 30;
    public int autoCleanupMaxTotalMB = 0;
    public List<String> storageProtectedFiles = new ArrayList<String>();
    public int storageCompressionCrf = 28;

    // Replay, clips, markers and chapters
    public boolean replayBufferEnabled = false;
    public int replayBufferDurationSeconds = 30;
    public String replayBufferQuality = "balanced";
    public int hotkeySaveReplayBuffer = Keyboard.KEY_NONE;
    public boolean replayBufferNotify = true;
    public boolean bookmarksEnabled = true;
    public boolean markersEnabled = true;
    public int hotkeyAddBookmark = Keyboard.KEY_NONE;
    public boolean exportChapterFile = true;
    public boolean embedChaptersInVideo = false;
    public boolean autoMarkerOnStart = true;
    public boolean autoClipEnabled = false;
    public boolean autoClipOnAchievement = false;
    public boolean autoClipOnDeath = false;
    public boolean autoClipOnDimensionChange = false;
    public boolean autoClipOnBossKill = false;
    public boolean autoClipOnKill = false;
    public boolean autoClipOnPlayerKill = false;
    public int autoClipDuration = 30;
    public boolean autoClipKillMontage = true;
    public int autoClipKillPreSeconds = 1;
    public int autoClipKillPostSeconds = 1;
    public int autoClipFps = 30;
    public boolean autoClipAudio = true;

    // Notifications and compatibility
    public boolean notifyRecording = true;
    public boolean notifyClips = true;
    public boolean notifyReplayBuffer = true;
    public boolean notifyAutoRecord = true;
    public boolean notifyBookmarks = true;
    public boolean notifyWarnings = true;
    public boolean replayCompatBridge = true;
    public boolean replayAutoRecordPlayback = false;
    public boolean replayYieldAudioDevice = false;

    // Gallery and export
    public String gallerySortMode = "newest";
    public boolean galleryShowMetadata = true;
    public int galleryColumns = 3;
    public String exportFormat = "";
    public String exportVideoCodec = "";
    public int exportVideoBitrateMbps = 0;
    public String exportAudioCodec = "";
    public int exportAudioBitrateKbps = 0;
    public String exportResolution = "";
    public int exportFps = 0;

    // Overlay
    public boolean showRecordingTimer = true;
    public boolean showEstimatedFileSize = true;
    public boolean showPostRecordingToast = true;
    public OverlayStyleHud overlayStyleHud = OverlayStyleHud.CLASSIC;
    public boolean overlaySkinEnabled = false;
    public String vhsPlayColor = "#FFFFFF";
    public String vhsRecTextColor = "#FFFFFF";
    public String vhsRecDotColor = "#CC1E1E";
    public String vhsBracketColor = "#C8FFFFFF";
    public String vhsTimestampColor = "#FFFFFF";
    public String vhsDateColor = "#FFFFFF";
    public String vhsSpColor = "#FFFFFF";
    public boolean vhsShowBrackets = true;
    public boolean vhsShowPlay = true;
    public boolean vhsShowDate = true;
    public boolean vhsShowSp = true;
    public boolean vhsShowBattery = true;
    public boolean vhsShowAudioMeter = true;
    public boolean vhsShowTapeCounter = true;
    public int hudPlayRecX = 80;
    public int hudPlayRecY = 14;
    public int hudTimestampOffsetX = 14;
    public int hudTimestampY = 14;
    public int hudSpX = 80;
    public int hudSpOffsetY = 24;
    public int hudPerfOffsetX = 8;
    public int hudPerfOffsetY = 80;
    public int hudDetailsOffsetX = 14;
    public int hudDetailsOffsetY = 14;
    public int hudCornersX = 68;
    public int hudCornersY = 4;
    public int hudCornersWidth = 100;
    public int hudCornersHeight = 48;
    public int hudPlayRecW = 0;
    public int hudPlayRecH = 0;
    public int hudTimestampW = 0;
    public int hudTimestampH = 0;
    public int hudSpW = 0;
    public int hudSpH = 0;
    public int hudPerfW = 0;
    public int hudPerfH = 0;
    public int hudDetailsW = 0;
    public int hudDetailsH = 0;
    public int hudPlayRecOpacity = 100;
    public int hudTimestampOpacity = 100;
    public int hudCornersOpacity = 100;
    public int hudSpOpacity = 100;
    public int hudDetailsOpacity = 100;
    public int hudPerfOpacity = 100;
    public String hudLayerOrder = defaultLayerOrder();
    public boolean hudPlayRecVisible = true;
    public boolean hudTimestampVisible = true;
    public boolean hudCornersVisible = true;
    public boolean hudSpVisible = true;
    public boolean hudDetailsVisible = true;
    public boolean hudPerfVisible = true;
    public int hudMicX = -1;
    public int hudMicY = 4;
    public boolean hudMicVisible = true;
    public int hudMicOpacity = 100;
    public int hudClassicX = -1;
    public int hudClassicY = -1;
    public boolean hudClassicVisible = true;
    public int hudSynthX = -1;
    public int hudSynthY = -1;
    public boolean hudSynthVisible = true;

    // Filters, watermarks and censor/streamer mode
    public boolean showFiltersLive = true;
    public boolean filterVhsVisible = false;
    public boolean filterLcdMoireVisible = false;
    public boolean filterCrtVisible = false;
    public int filterVhsIntensity = 75;
    public int filterLcdMoireIntensity = 75;
    public int filterCrtIntensity = 75;
    public boolean watermarksEnabled = false;
    public boolean showWatermarksLive = true;
    public List<WatermarkSlot> watermarkSlots = new ArrayList<WatermarkSlot>();
    public boolean streamerModeEnabled = false;
    public boolean streamerShowCensorPreview = false;
    public boolean censorOverlayHidden = false;
    public String streamerDefaultCensorStyle = "SOLID";
    public List<CensorRegion> censorRegions = new ArrayList<CensorRegion>();

    // Separate tracks, performance, smooth motion and HUD hiding
    public boolean separateAudioTracks = false;
    public boolean trackGameAudio = true;
    public boolean trackMicAudio = true;
    public boolean trackMusicAudio = false;
    public boolean perfOptimizerEnabled = false;
    public boolean perfAutoAdjust = false;
    public int perfMinFps = 45;
    public boolean perfModeGamePriority = true;
    public boolean perfActionLowerRes = true;
    public boolean perfActionLowerFps = true;
    public boolean perfActionFasterPreset = true;
    public boolean perfWarnBeforeAdjust = false;
    public boolean perfShowStatsOverlay = false;
    public boolean frameBufferPoolingEnabled = true;
    public boolean smoothMotionEnabled = false;
    public String smoothMotionMode = "blend";
    public boolean hideChat = false;
    public boolean hideCrosshair = false;
    public boolean hideHotbar = false;
    public boolean hideBossBar = false;
    public boolean hideHand = false;
    public boolean hideScoreboard = false;
    public boolean hideVignette = false;

    // UI theme
    public ThemePreset uiTheme = ThemePreset.VHS;
    public boolean uiScanlines = true;
    public boolean uiFilmGrain = true;
    public boolean uiGlitchEffects = true;
    public boolean uiVignette = true;
    public boolean uiAnimations = true;
    public String uiCustomAccentColor = "";

    private RecordableConfig() {
    }

    public static synchronized void initialize(File minecraftDirectory, File forgeConfigDirectory) {
        gameDirectory = minecraftDirectory.toPath().toAbsolutePath().normalize();
        configPath = forgeConfigDirectory.toPath().resolve("recordable.json");
        instance = loadInternal();
    }

    public static RecordableConfig get() {
        if (instance == null) {
            File fallback = new File(".");
            initialize(fallback, new File(fallback, "config"));
        }
        return instance;
    }

    private static RecordableConfig loadInternal() {
        RecordableConfig loaded = null;
        if (Files.isRegularFile(configPath)) {
            try (Reader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
                loaded = GSON.fromJson(reader, RecordableConfig.class);
            } catch (Exception exception) {
                RecordableMod.LOGGER.warn("Unable to read Record-able config; defaults will be used.", exception);
            }
        }
        if (loaded == null) {
            loaded = new RecordableConfig();
        }
        loaded.sanitize();
        loaded.save();
        return loaded;
    }

    public synchronized void save() {
        sanitize();
        try {
            Files.createDirectories(configPath.getParent());
            try (Writer writer = Files.newBufferedWriter(
                    configPath,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException exception) {
            RecordableMod.LOGGER.error("Unable to save Record-able config.", exception);
        }
    }

    public static Path getConfigPath() {
        return configPath;
    }

    public Path getOutputDirectory() {
        String value = isBlank(outputDir) ? "recordings" : outputDir.trim();
        try {
            Path requested = Paths.get(value);
            return requested.isAbsolute() ? requested.normalize() : gameDirectory.resolve(requested).normalize();
        } catch (InvalidPathException exception) {
            return gameDirectory.resolve("recordings").normalize();
        }
    }

    public Path getClipDirectory() {
        return getOutputDirectory().resolve("Clips");
    }

    /**
     * Expands the same filename tokens supported by the V1-0.09 modern
     * client and strips characters that are illegal on common filesystems.
     */
    public static String resolveFilenamePattern(String pattern) {
        String value = isBlank(pattern)
                ? DEFAULT_FILENAME_PATTERN
                : pattern.trim();
        LocalDateTime now = LocalDateTime.now();
        String datetime = now.format(
                DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String date = now.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String time = now.format(DateTimeFormatter.ofPattern("HHmmss"));
        String resolved = value
                .replace("{datetime}", datetime)
                .replace("{date}", date)
                .replace("{time}", time)
                .replaceAll("[\\\\/:*?\"<>|\\x00-\\x1F]", "")
                .replaceAll("[ .]+$", "")
                .trim();
        if (resolved.isEmpty()) {
            resolved = DEFAULT_FILENAME_PATTERN
                    .replace("{datetime}", datetime)
                    .replace("{date}", date)
                    .replace("{time}", time);
        }
        return resolved;
    }

    public String getFormat() {
        return sanitizeString(format, FORMATS, "mp4");
    }

    public int getFps() {
        return fps;
    }

    public boolean isAutoBitrate() {
        return isBlank(bitrate) || "auto".equalsIgnoreCase(bitrate.trim());
    }

    public String resolveBitrate(int width, int height) {
        if (!isAutoBitrate()) {
            return bitrate.trim();
        }
        double factor = "high".equals(quality) ? 0.120D : ("performance".equals(quality) ? 0.050D : 0.080D);
        double mbps = width * (double) height * Math.max(1, fps) * factor / 1000000.0D;
        return Math.round(Math.max(2.0D, Math.min(80.0D, mbps))) + "M";
    }

    public int getX264Crf() {
        return "high".equals(quality) ? 18 : ("performance".equals(quality) ? 28 : 23);
    }

    public String getX264Preset() {
        return "high".equals(quality) ? "slow" : ("performance".equals(quality) ? "ultrafast" : "medium");
    }

    public int getVp9Crf() {
        return "high".equals(quality) ? 30 : ("performance".equals(quality) ? 40 : 35);
    }

    public int getEffectiveAudioDelay() {
        if (audioDelayPreset == null) {
            audioDelayPreset = AudioDelayPreset.AUTO;
        }
        return audioDelayPreset == AudioDelayPreset.CUSTOM
            ? clamp(audioSyncOffsetMs, 0, 500)
            : Math.max(0, audioDelayPreset.defaultMs);
    }

    public String getContainerFromFormat() {
        return getFormat();
    }

    public boolean isWebmFormat() {
        return "webm".equals(getFormat());
    }

    public int getOverlayColorRgb() {
        return parseHexColor(overlayColor, 0xFF0000);
    }

    public int getMenuAccentColorRgb() {
        return parseHexColor(menuAccentColor, 0xFF0000);
    }

    public CaptureDimensions resolveCaptureDimensions(int nativeWidth, int nativeHeight) {
        int safeWidth = Math.max(2, nativeWidth);
        int safeHeight = Math.max(2, nativeHeight);
        int maximumHeight = safeHeight;
        if ("1080p".equals(resolution)) {
            maximumHeight = 1080;
        } else if ("720p".equals(resolution)) {
            maximumHeight = 720;
        } else if ("480p".equals(resolution)) {
            maximumHeight = 480;
        }
        int width = safeWidth;
        int height = safeHeight;
        if (!"native".equals(resolution) && safeHeight > maximumHeight) {
            double scale = maximumHeight / (double) safeHeight;
            width = (int) Math.round(safeWidth * scale);
            height = maximumHeight;
        }
        return new CaptureDimensions(makeEven(width), makeEven(height));
    }

    public boolean isElementVisible(String elementId) {
        if ("Corners".equals(elementId)) return hudCornersVisible;
        if ("PLAY/REC".equals(elementId)) return hudPlayRecVisible;
        if ("Timestamp".equals(elementId)) return hudTimestampVisible;
        if ("SP".equals(elementId)) return hudSpVisible;
        if ("Details".equals(elementId)) return hudDetailsVisible;
        if ("Perf".equals(elementId)) return hudPerfVisible;
        if ("Mic".equals(elementId)) return hudMicVisible;
        if ("Classic".equals(elementId)) return hudClassicVisible;
        if ("Synthwave".equals(elementId)) return hudSynthVisible;
        return true;
    }

    public void setElementVisible(String elementId, boolean visible) {
        if ("Corners".equals(elementId)) hudCornersVisible = visible;
        else if ("PLAY/REC".equals(elementId)) hudPlayRecVisible = visible;
        else if ("Timestamp".equals(elementId)) hudTimestampVisible = visible;
        else if ("SP".equals(elementId)) hudSpVisible = visible;
        else if ("Details".equals(elementId)) hudDetailsVisible = visible;
        else if ("Perf".equals(elementId)) hudPerfVisible = visible;
        else if ("Mic".equals(elementId)) hudMicVisible = visible;
        else if ("Classic".equals(elementId)) hudClassicVisible = visible;
        else if ("Synthwave".equals(elementId)) hudSynthVisible = visible;
    }

    public static String defaultLayerOrder() {
        return "Filter:VHS,Filter:LCD_MOIRE,Filter:CRT,Corners,PLAY/REC,Timestamp,SP,Details,Perf,Mic,Classic,Synthwave";
    }

    public static int applyOpacity(int argb, int opacityPercent) {
        int opacity = clamp(opacityPercent, 0, 100);
        int alpha = (argb >>> 24) & 255;
        return ((alpha * opacity / 100) << 24) | (argb & 0xFFFFFF);
    }

    public static int parseArgbColor(String value, int fallback) {
        if (isBlank(value)) return fallback;
        String normalized = value.trim();
        if (normalized.startsWith("#")) normalized = normalized.substring(1);
        try {
            if (normalized.length() == 8) return (int) Long.parseLong(normalized, 16);
            if (normalized.length() == 6) return 0xFF000000 | Integer.parseInt(normalized, 16);
        } catch (NumberFormatException ignored) {
        }
        return fallback;
    }

    public static int[] resolveReplayPreset(String quality, int recordingHeight, int recordingFps) {
        String value = isBlank(quality) ? "source" : quality.trim().toLowerCase(Locale.ROOT);
        int fps = recordingFps > 0 ? recordingFps : 30;
        if ("balanced".equals(value)) return new int[]{Math.min(720, recordingHeight), Math.min(30, fps)};
        if ("performance".equals(value)) return new int[]{Math.min(480, recordingHeight), Math.min(30, fps)};
        if ("high".equals(value)) return new int[]{Math.min(1080, recordingHeight), Math.min(60, fps)};
        return new int[]{0, fps};
    }

    public void applyTemplate(String template) {
        if ("cinematic".equals(template)) {
            resolution = "1080p"; fps = 60; quality = "high"; bitrate = "auto";
            captureAudio = true; audioBitrateKbps = 256; maxFileSizeMB = 0;
        } else if ("balanced".equals(template)) {
            resolution = "1080p"; fps = 60; quality = "balanced"; bitrate = "auto";
            captureAudio = true; audioBitrateKbps = 192; maxFileSizeMB = 0;
        } else if ("pvp_clip".equals(template)) {
            resolution = "720p"; fps = 60; quality = "balanced"; bitrate = "auto";
            captureAudio = true; audioBitrateKbps = 128; maxFileSizeMB = 200;
        }
        activeTemplate = sanitizeString(template, TEMPLATES, "custom");
        sanitize();
    }

    public void applyDevicePreset(String preset) {
        selectedDevicePreset = sanitizeDevicePreset(preset);
        bitrate = "auto";
        captureAudio = true;
        audioEncoder = AudioEncoder.AAC;
        perfModeGamePriority = true;
        if ("android_phone".equals(selectedDevicePreset)) {
            resolution = "480p";
            fps = 30;
            quality = "performance";
            encoder = VideoEncoder.SOFTWARE;
            audioBitrateKbps = 96;
            audioChannels = "mono";
            audioChannelCount = 1;
            audioSampleRate = 44100;
            perfOptimizerEnabled = true;
            perfAutoAdjust = true;
            perfMinFps = 30;
            perfActionLowerRes = true;
            perfActionLowerFps = true;
            perfActionFasterPreset = true;
            perfWarnBeforeAdjust = false;
        } else if ("low_end_pc".equals(selectedDevicePreset)) {
            resolution = "720p";
            fps = 30;
            quality = "performance";
            encoder = VideoEncoder.SOFTWARE;
            audioBitrateKbps = 128;
            audioChannels = "stereo";
            audioChannelCount = 2;
            audioSampleRate = 44100;
            perfOptimizerEnabled = true;
            perfAutoAdjust = true;
            perfMinFps = 30;
            perfActionLowerRes = true;
            perfActionLowerFps = true;
            perfActionFasterPreset = true;
            perfWarnBeforeAdjust = false;
        } else if ("high_end_pc".equals(selectedDevicePreset)) {
            resolution = "1080p";
            fps = 60;
            quality = "high";
            encoder = VideoEncoder.NVIDIA;
            audioBitrateKbps = 256;
            audioChannels = "stereo";
            audioChannelCount = 2;
            audioSampleRate = 48000;
            perfOptimizerEnabled = false;
            perfAutoAdjust = false;
        } else if ("nasa".equals(selectedDevicePreset)) {
            resolution = "native";
            fps = 120;
            quality = "high";
            encoder = VideoEncoder.NVIDIA;
            audioBitrateKbps = 320;
            audioChannels = "stereo";
            audioChannelCount = 2;
            audioSampleRate = 48000;
            perfOptimizerEnabled = false;
            perfAutoAdjust = false;
        } else {
            resolution = "1080p";
            fps = 60;
            quality = "balanced";
            encoder = VideoEncoder.NVIDIA;
            audioBitrateKbps = 192;
            audioChannels = "stereo";
            audioChannelCount = 2;
            audioSampleRate = 48000;
            perfOptimizerEnabled = true;
            perfAutoAdjust = true;
            perfMinFps = 45;
            perfActionLowerRes = false;
            perfActionLowerFps = true;
            perfActionFasterPreset = true;
            perfWarnBeforeAdjust = true;
        }
        activeTemplate = "custom";
        sanitize();
    }

    public static String sanitizeDevicePreset(String preset) {
        return sanitizeString(preset, DEVICE_PRESETS, "mid_end_pc");
    }

    public void validateAudioEncoderCompatibility() {
        if (audioEncoder == null || !audioEncoder.supportsContainer(getFormat())) {
            audioEncoder = isWebmFormat() ? AudioEncoder.OPUS : AudioEncoder.AAC;
        }
    }

    public void sanitize() {
        format = sanitizeString(format, FORMATS, "mp4");
        resolution = sanitizeString(resolution, RESOLUTIONS, "native");
        quality = sanitizeString(quality, QUALITIES, "balanced");
        if (!contains(FPS_VALUES, fps)) fps = 60;
        if (encoder == null) encoder = VideoEncoder.SOFTWARE;
        if (isBlank(bitrate) || (!"auto".equalsIgnoreCase(bitrate.trim())
                && !bitrate.trim().matches("(?i)^[1-9][0-9]*(?:[km])?$"))) {
            bitrate = "auto";
        }
        if (isBlank(audioDevice)) {
            audioDevice = "auto";
        } else {
            audioDevice = audioDevice.trim();
            if ("openal".equalsIgnoreCase(audioDevice)) {
                RecordableMod.LOGGER.info(
                        "Migrating legacy audioDevice='openal' to 'auto'.");
                audioDevice = "auto";
            }
        }
        if (isBlank(microphoneDevice)) microphoneDevice = "auto";
        audioBitrateKbps = clamp(audioBitrateKbps, 32, 512);
        audioSampleRate = contains(AUDIO_SAMPLE_RATES, audioSampleRate) ? audioSampleRate : 48000;
        audioChannelCount = audioChannelCount == 1 ? 1 : 2;
        audioChannels = audioChannelCount == 1 ? "mono" : "stereo";
        audioBitrate = audioBitrateKbps + "k";
        audioVolume = clamp(audioVolume, 0, 200);
        audioVolumeBoostDb = clamp(audioVolumeBoostDb, 0, 24);
        gameAudioVolume = clamp(gameAudioVolume, 0, 200);
        microphoneVolume = clamp(microphoneVolume, 0, 200);
        validateAudioEncoderCompatibility();
        if (isBlank(outputDir)) outputDir = "recordings";
        filenamePattern = isBlank(filenamePattern)
                ? DEFAULT_FILENAME_PATTERN
                : filenamePattern.trim();
        overlayColor = sanitizeHexColor(overlayColor, "#FF0000");
        menuAccentColor = sanitizeHexColor(menuAccentColor, "#FF0000");
        overlayScale = clamp(overlayScale, 50, 200);
        if (overlayPosition == null) overlayPosition = OverlayPosition.TOP_LEFT;
        if (overlayStyleHud == null) overlayStyleHud = OverlayStyleHud.CLASSIC;
        if (audioDelayPreset == null) audioDelayPreset = AudioDelayPreset.AUTO;
        audioSyncOffsetMs = clamp(audioSyncOffsetMs, 0, 500);
        autoRecordTrigger = sanitizeString(autoRecordTrigger, AUTO_RECORD_TRIGGERS, "world_join");
        autoStopTrigger = sanitizeString(autoStopTrigger, AUTO_STOP_TRIGGERS, "world_leave");
        autoRecordDelay = clamp(autoRecordDelay, 0, 10);
        activeTemplate = sanitizeString(activeTemplate, TEMPLATES, "custom");
        selectedDevicePreset = sanitizeDevicePreset(selectedDevicePreset);
        diskSpaceWarnPercent = clamp(diskSpaceWarnPercent, 50, 99);
        diskSpaceBlockPercent = clamp(diskSpaceBlockPercent, diskSpaceWarnPercent + 1, 100);
        diskSpaceMinFreeMB = clamp(diskSpaceMinFreeMB, 100, 10000);
        maxFileSizeMB = Math.max(0, maxFileSizeMB);
        replayBufferDurationSeconds = clamp(replayBufferDurationSeconds, 10, 600);
        replayBufferQuality = sanitizeString(replayBufferQuality, REPLAY_QUALITIES, "balanced");
        autoClipDuration = clamp(autoClipDuration, 5, 300);
        autoClipKillPreSeconds = clamp(autoClipKillPreSeconds, 0, 10);
        autoClipKillPostSeconds = clamp(autoClipKillPostSeconds, 0, 10);
        if (!contains(AUTO_CLIP_FPS_VALUES, autoClipFps)) autoClipFps = 30;
        gallerySortMode = sanitizeString(gallerySortMode, GALLERY_SORT_MODES, "newest");
        galleryColumns = clamp(galleryColumns, 1, 6);
        filterVhsIntensity = clamp(filterVhsIntensity, 0, 100);
        filterLcdMoireIntensity = clamp(filterLcdMoireIntensity, 0, 100);
        filterCrtIntensity = clamp(filterCrtIntensity, 0, 100);
        if (watermarkSlots == null) watermarkSlots = new ArrayList<WatermarkSlot>();
        while (watermarkSlots.size() > MAX_WATERMARK_SLOTS) {
            watermarkSlots.remove(watermarkSlots.size() - 1);
        }
        for (WatermarkSlot slot : watermarkSlots) if (slot != null) slot.sanitize();
        if (censorRegions == null) censorRegions = new ArrayList<CensorRegion>();
        for (CensorRegion region : censorRegions) if (region != null) region.sanitize();
        if (storageProtectedFiles == null) storageProtectedFiles = new ArrayList<String>();
        autoCleanupOlderThanDays = clamp(autoCleanupOlderThanDays, 1, 3650);
        autoCleanupMaxTotalMB = Math.max(0, autoCleanupMaxTotalMB);
        storageCompressionCrf = clamp(storageCompressionCrf, 0, 51);
        perfMinFps = clamp(perfMinFps, 10, 240);
        smoothMotionMode = SmoothMotion.sanitizeMode(smoothMotionMode);
        if (!"SOLID".equals(streamerDefaultCensorStyle)
                && !"GRADIENT".equals(streamerDefaultCensorStyle)) {
            streamerDefaultCensorStyle = "SOLID";
        }
        if (uiTheme == null) uiTheme = ThemePreset.VHS;
        if (uiCustomAccentColor == null) uiCustomAccentColor = "";
        normalizeHudValues();
    }

    private void normalizeHudValues() {
        hudCornersWidth = clamp(hudCornersWidth, 20, 2000);
        hudCornersHeight = clamp(hudCornersHeight, 20, 2000);
        hudPlayRecOpacity = clamp(hudPlayRecOpacity, 0, 100);
        hudTimestampOpacity = clamp(hudTimestampOpacity, 0, 100);
        hudCornersOpacity = clamp(hudCornersOpacity, 0, 100);
        hudSpOpacity = clamp(hudSpOpacity, 0, 100);
        hudDetailsOpacity = clamp(hudDetailsOpacity, 0, 100);
        hudPerfOpacity = clamp(hudPerfOpacity, 0, 100);
        hudMicOpacity = clamp(hudMicOpacity, 0, 100);
        if (isBlank(hudLayerOrder)) hudLayerOrder = defaultLayerOrder();
        Set<String> valid = new HashSet<String>(Arrays.asList(defaultLayerOrder().split(",")));
        List<String> cleaned = new ArrayList<String>();
        for (String item : hudLayerOrder.split(",")) {
            String trimmed = item.trim();
            if (valid.contains(trimmed) && !cleaned.contains(trimmed)) cleaned.add(trimmed);
        }
        for (String item : defaultLayerOrder().split(",")) {
            if (!cleaned.contains(item)) cleaned.add(item);
        }
        hudLayerOrder = join(cleaned, ",");
    }

    private static String sanitizeString(String value, String[] allowed, String fallback) {
        if (!isBlank(value)) {
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            for (String candidate : allowed) {
                if (candidate.equals(normalized)) return normalized;
            }
        }
        return fallback;
    }

    private static String sanitizeHexColor(String value, String fallback) {
        if (isBlank(value)) return fallback;
        String normalized = value.trim();
        if (!normalized.startsWith("#")) normalized = "#" + normalized;
        return HEX_COLOR.matcher(normalized).matches() ? normalized.toUpperCase(Locale.ROOT) : fallback;
    }

    private static int parseHexColor(String value, int fallback) {
        String normalized = sanitizeHexColor(value, String.format(Locale.ROOT, "#%06X", fallback));
        try {
            String digits = normalized.substring(1);
            if (digits.length() == 8) digits = digits.substring(2);
            return Integer.parseInt(digits, 16);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static boolean contains(int[] values, int needle) {
        for (int value : values) if (value == needle) return true;
        return false;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static int makeEven(int value) {
        int safe = Math.max(2, value);
        return (safe & 1) == 0 ? safe : safe - 1;
    }

    private static String join(List<String> values, String separator) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (builder.length() > 0) builder.append(separator);
            builder.append(value);
        }
        return builder.toString();
    }

    public static final class CaptureDimensions {
        private final int width;
        private final int height;

        public CaptureDimensions(int width, int height) {
            this.width = width;
            this.height = height;
        }

        public int getWidth() {
            return width;
        }

        public int getHeight() {
            return height;
        }
    }
}
