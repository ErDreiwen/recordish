package dev.recordable.screen;

import dev.recordable.AudioCaptureSession;
import dev.recordable.FfmpegBundleManager;
import dev.recordable.NativeFolderPicker;
import dev.recordable.PlatformUtils;
import dev.recordable.RecordableConfig;
import dev.recordable.RecordableMod;
import dev.recordable.StorageManager;
import dev.recordable.theme.ThemeColors;
import dev.recordable.theme.ThemePreset;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.resources.I18n;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Java 8 / Forge adaptation of the V1-0.09 modern settings composition.
 *
 * <p>The screen deliberately preserves the modern release's dimensions,
 * section ordering, two-column 20px controls, 22px row rhythm, compact
 * search result layout, continuous scrolling, and fixed footer.</p>
 */
class RecordableSettingsScreenV109 extends GuiScreen {
    private static final int ROW = 22;
    private static final int WIDGET_HEIGHT = 20;
    private static final int FOOTER_OPEN = 9000;
    private static final int FOOTER_DONE = 9001;
    private static final Random EFFECT_RANDOM = new Random();
    private static long lastGlitchTick;
    private static int glitchY;
    private static int glitchHeight;
    private static boolean glitchActive;

    protected final GuiScreen parent;
    protected RecordableConfig config;

    private final List<LayoutItem> layout = new ArrayList<LayoutItem>();
    private final List<TextEntry> textEntries = new ArrayList<TextEntry>();
    private final List<ColorEntry> colorEntries =
            new ArrayList<ColorEntry>();
    private final List<Decoration> decorations =
            new ArrayList<Decoration>();
    private final Map<Integer, ActionButton> actionButtons =
            new HashMap<Integer, ActionButton>();

    private GuiTextField searchBox;
    private int nextId = 100;
    private int panelLeft;
    private int panelTop;
    private int panelWidth;
    private int panelBottom;
    private int panelBodyTop;
    private int panelBodyBottom;
    private int footerY;
    private int fullContentHeight;
    private int contentHeight;
    private int scrollOffset;
    private int searchMatchRows = -1;
    private boolean draggingScrollbar;
    private String searchQuery = "";
    private String statusMessage = "";
    private boolean statusError;
    private long statusUntil;
    private FfmpegBundleManager.FfmpegStatus ffmpegStatus;

    private volatile List<AudioCaptureSession.AudioDevice> audioDevices =
            Collections.emptyList();
    private volatile boolean audioScanRunning;
    private volatile String audioStatus = "Audio devices have not been scanned.";
    private TextEntry outputDirectoryEntry;

    RecordableSettingsScreenV109(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        buttonList.clear();
        layout.clear();
        textEntries.clear();
        colorEntries.clear();
        decorations.clear();
        actionButtons.clear();
        nextId = 100;

        config = RecordableConfig.get();
        config.sanitize();
        ffmpegStatus = FfmpegBundleManager.detectFfmpeg();

        panelWidth = Math.max(340, Math.min((int) (width * 0.78D), 760));
        panelLeft = (width - panelWidth) / 2;
        panelTop = Math.max(8, (int) (height * 0.04D));
        panelBottom = Math.min(
                height - 8,
                panelTop + Math.max(300, (int) (height * 0.90D)));
        panelBodyTop = panelTop + 46;
        footerY = panelBottom - 28;
        panelBodyBottom = footerY - 8;

        int widgetLeft = panelLeft + 14;
        int widgetWidth = Math.max(180, panelWidth - 28);
        searchBox = new GuiTextField(
                41,
                fontRendererObj,
                widgetLeft,
                panelTop + 24,
                widgetWidth,
                18);
        searchBox.setMaxStringLength(64);
        searchBox.setText(searchQuery);

        buildContent(widgetLeft, widgetWidth);

        int footerHalf = Math.max(84, (widgetWidth - 6) / 2);
        buttonList.add(new GuiButton(
                FOOTER_OPEN,
                widgetLeft,
                footerY,
                footerHalf,
                WIDGET_HEIGHT,
                tr("screen.recordable.settings.open_folder")));
        buttonList.add(new GuiButton(
                FOOTER_DONE,
                widgetLeft + footerHalf + 6,
                footerY,
                footerHalf,
                WIDGET_HEIGHT,
                tr("screen.recordable.settings.done")));

        updateWidgetLayout();
        if (audioDevices.isEmpty() && !audioScanRunning) {
            refreshAudioDevices();
        }
    }

    private void buildContent(int left, int widthAvailable) {
        int half = Math.max(88, (widthAvailable - 6) / 2);
        int right = left + half + 6;
        int y = 0;

        addAction(
                left,
                widthAvailable,
                y,
                tr("screen.recordable.settings.open_video_collection"),
                () -> mc.displayGuiScreen(new VideoCollectionScreen(this)),
                "video collection gallery recordings clips");

        addLabel("Rename File Name", y += ROW, ThemeRole.SECONDARY);
        y += 12;
        addText(
                left,
                widthAvailable,
                y,
                "Rename File Name",
                () -> config.filenamePattern,
                value -> config.filenamePattern = isBlank(value)
                        ? RecordableConfig.DEFAULT_FILENAME_PATTERN
                        : value.trim(),
                128,
                "rename filename pattern datetime date time");

        addHeader(tr("screen.recordable.settings.video"), y += ROW);
        y += 12;
        addCycle(
                left,
                half,
                y,
                () -> "Output: ." + config.getFormat(),
                () -> {
                    config.format = next(config.format, RecordableConfig.FORMATS);
                    config.validateAudioEncoderCompatibility();
                },
                () -> {
                    config.format = previous(
                            config.format,
                            RecordableConfig.FORMATS);
                    config.validateAudioEncoderCompatibility();
                },
                "format output mp4 mkv mov webm");
        addCycle(
                right,
                half,
                y,
                () -> "Resolution: " + display(config.resolution),
                () -> config.resolution =
                        next(config.resolution, RecordableConfig.RESOLUTIONS),
                () -> config.resolution = previous(
                        config.resolution,
                        RecordableConfig.RESOLUTIONS),
                "resolution native 1080p 720p 480p");

        addCycle(
                left,
                half,
                y += ROW,
                () -> "Quality: " + display(config.quality),
                () -> config.quality =
                        next(config.quality, RecordableConfig.QUALITIES),
                () -> config.quality = previous(
                        config.quality,
                        RecordableConfig.QUALITIES),
                "quality high balanced performance");
        addSlider(
                right,
                half,
                y,
                () -> config.fps,
                value -> config.fps = value,
                30,
                120,
                30,
                value -> "FPS: " + value,
                "fps frame rate 30 60 120");

        addCycle(
                left,
                widthAvailable,
                y += ROW,
                () -> "Video Encoder: "
                        + (config.encoder == null
                            ? RecordableConfig.VideoEncoder.SOFTWARE.displayName
                            : config.encoder.displayName),
                () -> config.encoder = nextEnum(
                        config.encoder,
                        RecordableConfig.VideoEncoder.values()),
                () -> config.encoder = previousEnum(
                        config.encoder,
                        RecordableConfig.VideoEncoder.values()),
                "encoder software x264 nvidia nvenc amd intel quicksync");

        if (ffmpegStatus != null && ffmpegStatus.isFound()) {
            addAction(
                    left,
                    widthAvailable,
                    y += ROW,
                    "Encoder: FFmpeg \u2713",
                    this::detectFfmpeg,
                    "ffmpeg encoder detect");
        } else {
            addAction(
                    left,
                    half,
                    y += ROW,
                    "Encoder: FFmpeg (NOT FOUND)",
                    this::detectFfmpeg,
                    "ffmpeg encoder detect missing");
            addAction(
                    right,
                    half,
                    y,
                    FfmpegBundleManager.isDownloading()
                            ? "Downloading... "
                                + FfmpegBundleManager.getProgress()
                                        .displayPercent()
                            : "Download FFmpeg ("
                                + FfmpegBundleManager
                                        .getEstimatedDownloadSize()
                                + ")",
                    () -> mc.displayGuiScreen(
                            new FfmpegDownloadScreen(this)),
                    "download ffmpeg setup install");
        }

        addText(
                left,
                widthAvailable,
                y += ROW,
                tr("screen.recordable.settings.bitrate"),
                () -> config.bitrate,
                value -> config.bitrate = isBlank(value)
                        ? "auto"
                        : value.trim(),
                16,
                "bitrate auto 8m 4500k");
        addLabel(
                tr("screen.recordable.settings.bitrate_hint"),
                y += ROW,
                ThemeRole.MUTED);
        addWrappedLabel(
                "Performance: " + performanceHint(),
                y += 12,
                ThemeRole.MUTED);

        addHeader(tr("screen.recordable.settings.audio"), y += ROW);
        y += 12;
        addToggle(
                left,
                half,
                y,
                tr("screen.recordable.settings.capture_audio"),
                () -> config.captureAudio,
                value -> config.captureAudio = value,
                "capture audio game sound");
        addAction(
                right,
                half,
                y,
                () -> audioScanRunning
                        ? "Scanning Audio..."
                        : trim(audioStatus, 34),
                this::refreshAudioDevices,
                "audio devices refresh scan");

        addCycle(
                left,
                widthAvailable,
                y += ROW,
                () -> "Audio Device: " + deviceDisplay(false),
                () -> cycleDevice(false, true),
                () -> cycleDevice(false, false),
                "audio device stereo mix loopback");

        List<RecordableConfig.AudioEncoder> compatibleAudioEncoders =
                compatibleAudioEncoders();
        if (!compatibleAudioEncoders.contains(config.audioEncoder)) {
            config.audioEncoder = compatibleAudioEncoders.get(0);
            safeSave();
        }
        addAction(
                left,
                widthAvailable,
                y += ROW,
                () -> audioEncoderLabel(config.audioEncoder),
                () -> {
                    List<RecordableConfig.AudioEncoder> available =
                            compatibleAudioEncoders();
                    config.audioEncoder = nextAudioEncoder(
                            available,
                            config.audioEncoder);
                },
                "audio encoder codec aac opus mp3 flac lossless");

        addSlider(
                left,
                widthAvailable,
                y += ROW,
                () -> config.audioVolume,
                value -> config.audioVolume = value,
                0,
                200,
                5,
                value -> "Audio Volume: " + value + "%",
                "audio volume gain");

        addToggle(
                left,
                half,
                y += ROW,
                tr("screen.recordable.settings.capture_microphone"),
                () -> config.captureMicrophone,
                value -> config.captureMicrophone = value,
                "capture microphone mic");
        addCycle(
                right,
                half,
                y,
                () -> "Mic: " + deviceDisplay(true),
                () -> cycleDevice(true, true),
                () -> cycleDevice(true, false),
                "microphone device mic");

        addSlider(
                left,
                half,
                y += ROW,
                () -> config.gameAudioVolume,
                value -> config.gameAudioVolume = value,
                0,
                200,
                5,
                value -> "Game Volume: " + value + "%",
                "game volume");
        addSlider(
                right,
                half,
                y,
                () -> config.microphoneVolume,
                value -> config.microphoneVolume = value,
                0,
                200,
                5,
                value -> "Mic Volume: " + value + "%",
                "microphone volume");

        addToggle(
                left,
                half,
                y += ROW,
                tr("screen.recordable.settings.push_to_talk"),
                () -> config.microphonePushToTalk,
                value -> config.microphonePushToTalk = value,
                "push to talk ptt microphone");
        addToggle(
                right,
                half,
                y,
                tr("screen.recordable.settings.noise_suppression"),
                () -> config.noiseSuppression,
                value -> config.noiseSuppression = value,
                "noise suppression denoise microphone");

        addAction(
                left,
                widthAvailable,
                y += ROW,
                tr("screen.recordable.settings.test_mic"),
                this::runMicrophoneTest,
                "test microphone mic level");

        addCycle(
                left,
                widthAvailable,
                y += ROW,
                () -> "Audio Delay: "
                        + config.audioDelayPreset.displayName
                        + " (" + config.getEffectiveAudioDelay() + "ms)",
                () -> config.audioDelayPreset =
                        config.audioDelayPreset.next(),
                () -> config.audioDelayPreset =
                        config.audioDelayPreset.previous(),
                "audio delay sync");
        addSlider(
                left,
                widthAvailable,
                y += ROW,
                () -> config.audioSyncOffsetMs,
                value -> config.audioSyncOffsetMs = value,
                0,
                500,
                5,
                value -> "Custom Delay: " + value + " ms",
                "custom audio delay sync");

        y += 26;
        addWrappedLabel(platformAudioWarning(), y, ThemeRole.WARNING);
        y += wrappedHeight(platformAudioWarning(), panelWidth - 28) + 6;

        addHeader(tr("screen.recordable.settings.general"), y);
        y += 12;
        addToggle(
                left,
                half,
                y,
                tr("screen.recordable.settings.enabled"),
                () -> config.enabled,
                value -> config.enabled = value,
                "enabled master recording");
        addToggle(
                right,
                half,
                y,
                tr("screen.recordable.settings.overlay"),
                () -> config.showOverlay,
                value -> config.showOverlay = value,
                "show overlay hud");
        addToggle(
                left,
                half,
                y += ROW,
                tr("screen.recordable.settings.stop_on_disconnect"),
                () -> config.stopOnDisconnect,
                value -> config.stopOnDisconnect = value,
                "stop disconnect world leave");
        addToggle(
                right,
                half,
                y,
                tr("screen.recordable.settings.show_home_button"),
                () -> config.showHomeButton,
                value -> config.showHomeButton = value,
                "home button title menu");
        addCycle(
                left,
                half,
                y += ROW,
                () -> "Overlay Position: "
                        + config.overlayPosition.displayName,
                () -> config.overlayPosition =
                        config.overlayPosition.next(),
                () -> config.overlayPosition =
                        config.overlayPosition.previous(),
                "overlay position top left right bottom center");
        addSlider(
                right,
                half,
                y,
                () -> config.overlayScale,
                value -> config.overlayScale = value,
                50,
                200,
                5,
                value -> "Overlay Scale: " + value + "%",
                "overlay scale");

        int browseWidth = NativeFolderPicker.isSupported() ? 62 : 0;
        int outputFieldWidth = browseWidth > 0
                ? widthAvailable - browseWidth - 6
                : widthAvailable;
        outputDirectoryEntry = addText(
                left,
                outputFieldWidth,
                y += ROW,
                tr("screen.recordable.settings.output_dir"),
                () -> config.outputDir,
                value -> config.outputDir = isBlank(value)
                        ? "recordings"
                        : value.trim(),
                256,
                "output directory folder path");
        if (browseWidth > 0) {
            addAction(
                    left + outputFieldWidth + 6,
                    browseWidth,
                    y,
                    tr("screen.recordable.settings.browse"),
                    this::chooseOutputFolder,
                    "browse choose recordings output directory folder");
        }
        addLabel(
                tr("screen.recordable.settings.output_dir"),
                y += ROW,
                ThemeRole.MUTED);
        addWrappedLabel(
                displayOutputPath(),
                y += 10,
                ThemeRole.MUTED);
        addSlider(
                left,
                widthAvailable,
                y += 12,
                () -> config.maxFileSizeMB,
                value -> config.maxFileSizeMB = value,
                0,
                10240,
                128,
                value -> value <= 0
                        ? tr("screen.recordable.settings.max_file_size.unlimited")
                        : "Max File Size: " + value + " MB",
                "maximum file size unlimited");

        addHeader(
                tr("screen.recordable.settings.auto_record.section"),
                y += 24);
        y += 12;
        addToggle(
                left,
                half,
                y,
                tr("screen.recordable.settings.auto_record.enabled"),
                () -> config.autoRecord,
                value -> config.autoRecord = value,
                "auto record automatic");
        addCycle(
                right,
                half,
                y,
                () -> "Start Trigger: " + display(config.autoRecordTrigger),
                () -> config.autoRecordTrigger = next(
                        config.autoRecordTrigger,
                        RecordableConfig.AUTO_RECORD_TRIGGERS),
                () -> config.autoRecordTrigger = previous(
                        config.autoRecordTrigger,
                        RecordableConfig.AUTO_RECORD_TRIGGERS),
                "auto record start trigger world join game start manual");
        addCycle(
                left,
                widthAvailable,
                y += ROW,
                () -> "Stop Trigger: " + display(config.autoStopTrigger),
                () -> config.autoStopTrigger = next(
                        config.autoStopTrigger,
                        RecordableConfig.AUTO_STOP_TRIGGERS),
                () -> config.autoStopTrigger = previous(
                        config.autoStopTrigger,
                        RecordableConfig.AUTO_STOP_TRIGGERS),
                "auto stop trigger world leave quit never");

        y = buildAppearance(left, right, half, widthAvailable, y + 24);
        y = buildAdvanced(left, right, half, widthAvailable, y);

        addLabel(
                diskSpaceLine(),
                y += ROW,
                ThemeRole.MUTED);
        addWrappedLabel(
                ffmpegStatusLine(),
                y += 14,
                ffmpegStatus != null && ffmpegStatus.isFound()
                        ? ThemeRole.MUTED
                        : ThemeRole.ERROR);
        fullContentHeight = y + 18;
        contentHeight = fullContentHeight;
    }

    private int buildAppearance(
            int left,
            int right,
            int half,
            int full,
            int y) {
        addHeader(tr("screen.recordable.settings.appearance"), y);
        addColor(
                left,
                full,
                y += 20,
                tr("screen.recordable.settings.overlay_color"),
                () -> config.overlayColor,
                value -> config.overlayColor = value,
                "classic overlay color hex");
        addColor(
                left,
                full,
                y += 24,
                tr("screen.recordable.settings.menu_accent_color"),
                () -> config.menuAccentColor,
                value -> config.menuAccentColor = value,
                "menu accent color hex");

        addCycle(
                left,
                full,
                y += 30,
                () -> "Overlay Style: "
                        + config.overlayStyleHud.displayName,
                () -> config.overlayStyleHud =
                        config.overlayStyleHud.next(),
                () -> config.overlayStyleHud =
                        config.overlayStyleHud.previous(),
                "overlay style classic vhs synthwave none");
        addToggle(
                left,
                full,
                y += ROW,
                "Overlay Skin: " + config.uiTheme.displayName,
                () -> config.overlaySkinEnabled,
                value -> config.overlaySkinEnabled = value,
                "overlay skin theme colors");

        if (config.overlayStyleHud
                == RecordableConfig.OverlayStyleHud.VHS) {
            addToggle(
                    left,
                    half,
                    y += ROW,
                    tr("screen.recordable.settings.vhs_brackets"),
                    () -> config.vhsShowBrackets,
                    value -> config.vhsShowBrackets = value,
                    "vhs brackets");
            addToggle(
                    right,
                    half,
                    y,
                    tr("screen.recordable.settings.vhs_play"),
                    () -> config.vhsShowPlay,
                    value -> config.vhsShowPlay = value,
                    "vhs play label");
            addToggle(
                    left,
                    half,
                    y += ROW,
                    tr("screen.recordable.settings.vhs_date"),
                    () -> config.vhsShowDate,
                    value -> config.vhsShowDate = value,
                    "vhs date stamp");
            addToggle(
                    right,
                    half,
                    y,
                    tr("screen.recordable.settings.vhs_sp"),
                    () -> config.vhsShowSp,
                    value -> config.vhsShowSp = value,
                    "vhs sp indicator");
            addToggle(
                    left,
                    half,
                    y += ROW,
                    tr("screen.recordable.settings.vhs_battery"),
                    () -> config.vhsShowBattery,
                    value -> config.vhsShowBattery = value,
                    "vhs battery");
            addToggle(
                    right,
                    half,
                    y,
                    tr("screen.recordable.settings.vhs_audio_meter"),
                    () -> config.vhsShowAudioMeter,
                    value -> config.vhsShowAudioMeter = value,
                    "vhs audio meter");
            addToggle(
                    left,
                    half,
                    y += ROW,
                    tr("screen.recordable.settings.vhs_tape_counter"),
                    () -> config.vhsShowTapeCounter,
                    value -> config.vhsShowTapeCounter = value,
                    "vhs tape counter");
        }

        addAction(
                left,
                full,
                y += 26,
                "\u00a7fUI Theme: " + config.uiTheme.displayName,
                () -> openOptionalScreen("ThemeSettingsScreen"),
                "ui theme vhs cinema neon minimal scanlines grain glitch");

        addHeader(tr("screen.recordable.settings.positions"), y += 26);
        addAction(
                left,
                full,
                y += 14,
                "Position & Colors Editor",
                () -> mc.displayGuiScreen(
                        new OverlayPositionScreen(this)),
                "position colors editor overlay drag resize");
        addAction(
                left,
                full,
                y += ROW,
                tr("screen.recordable.settings.open_watermarks"),
                () -> mc.displayGuiScreen(new WatermarkScreen(this)),
                "watermark branding editor");
        addAction(
                left,
                full,
                y += ROW,
                "Streamer Mode",
                () -> openOptionalScreen("StreamerModeScreen"),
                "streamer mode censor privacy");

        addHeader(
                tr("screen.recordable.settings.performance_section"),
                y += ROW);
        addAction(
                left,
                full,
                y += 14,
                "Performance",
                () -> openOptionalScreen("PerformanceScreen"),
                "performance optimizer device preset smooth motion fps");
        addAction(
                left,
                full,
                y += ROW,
                "Capture Test",
                () -> mc.displayGuiScreen(
                        new CaptureDiagnosticsScreen(this)),
                "capture test diagnostics black blank frame");
        return y + 26;
    }

    private int buildAdvanced(
            int left,
            int right,
            int half,
            int full,
            int y) {
        addHeader(tr("screen.recordable.settings.advanced"), y);
        addToggle(
                left,
                half,
                y += 12,
                tr("screen.recordable.settings.show_toast"),
                () -> config.showPostRecordingToast,
                value -> config.showPostRecordingToast = value,
                "save completion toast");
        addToggle(
                right,
                half,
                y,
                tr("screen.recordable.settings.show_timer"),
                () -> config.showRecordingTimer,
                value -> config.showRecordingTimer = value,
                "recording timer");
        addToggle(
                left,
                half,
                y += ROW,
                tr("screen.recordable.settings.show_est_size"),
                () -> config.showEstimatedFileSize,
                value -> config.showEstimatedFileSize = value,
                "estimated file size");
        addToggle(
                right,
                half,
                y,
                tr("screen.recordable.settings.bookmarks"),
                () -> config.bookmarksEnabled,
                value -> config.bookmarksEnabled = value,
                "bookmarks timestamp");

        addHeader(
                tr("screen.recordable.settings.autoclip.section"),
                y += ROW);
        addToggle(
                left,
                full,
                y += 12,
                tr("screen.recordable.settings.autoclip_enabled"),
                () -> config.autoClipEnabled,
                value -> {
                    config.autoClipEnabled = value;
                    config.autoClipOnAchievement = value;
                    config.autoClipOnDeath = value;
                    config.autoClipOnDimensionChange = value;
                    config.autoClipOnBossKill = value;
                    config.autoClipOnKill = value;
                    config.autoClipOnPlayerKill = value;
                },
                "auto clip automatic clipping");
        BooleanSupplier clipEnabled = () -> config.autoClipEnabled;
        addToggle(
                left,
                half,
                y += ROW,
                tr("screen.recordable.settings.autoclip_on_achievement"),
                () -> config.autoClipOnAchievement,
                value -> config.autoClipOnAchievement = value,
                "auto clip achievement",
                clipEnabled);
        addToggle(
                right,
                half,
                y,
                tr("screen.recordable.settings.autoclip_on_death"),
                () -> config.autoClipOnDeath,
                value -> config.autoClipOnDeath = value,
                "auto clip death",
                clipEnabled);
        addToggle(
                left,
                half,
                y += ROW,
                tr("screen.recordable.settings.autoclip_on_dimension"),
                () -> config.autoClipOnDimensionChange,
                value -> config.autoClipOnDimensionChange = value,
                "auto clip dimension",
                clipEnabled);
        addToggle(
                right,
                half,
                y,
                tr("screen.recordable.settings.autoclip_on_boss"),
                () -> config.autoClipOnBossKill,
                value -> config.autoClipOnBossKill = value,
                "auto clip boss kill",
                clipEnabled);
        addToggle(
                left,
                half,
                y += ROW,
                tr("screen.recordable.settings.autoclip_on_kill"),
                () -> config.autoClipOnKill,
                value -> config.autoClipOnKill = value,
                "auto clip kill montage",
                clipEnabled);
        addToggle(
                right,
                half,
                y,
                tr("screen.recordable.settings.autoclip_on_player_kill"),
                () -> config.autoClipOnPlayerKill,
                value -> config.autoClipOnPlayerKill = value,
                "auto clip player kill bedwars pvp",
                clipEnabled);
        addSlider(
                left,
                half,
                y += ROW,
                () -> config.autoClipKillPreSeconds,
                value -> config.autoClipKillPreSeconds = value,
                0,
                10,
                1,
                value -> "Montage Seconds Before: " + value,
                "kill montage seconds before",
                clipEnabled);
        addSlider(
                right,
                half,
                y,
                () -> config.autoClipKillPostSeconds,
                value -> config.autoClipKillPostSeconds = value,
                0,
                10,
                1,
                value -> "Montage Seconds After: " + value,
                "kill montage seconds after",
                clipEnabled);
        addSlider(
                left,
                full,
                y += ROW,
                () -> config.autoClipDuration,
                value -> config.autoClipDuration = value,
                5,
                300,
                5,
                value -> "Auto-Clip Duration: " + value + " s",
                "auto clip duration",
                clipEnabled);
        addToggle(
                left,
                half,
                y += ROW,
                tr("screen.recordable.settings.autoclip_audio"),
                () -> config.autoClipAudio,
                value -> config.autoClipAudio = value,
                "auto clip audio",
                clipEnabled);
        addSlider(
                left,
                full,
                y += ROW,
                () -> config.autoClipFps,
                value -> config.autoClipFps = nearest(
                        value,
                        RecordableConfig.AUTO_CLIP_FPS_VALUES),
                15,
                60,
                1,
                value -> "Auto-Clip FPS: " + nearest(
                        value,
                        RecordableConfig.AUTO_CLIP_FPS_VALUES),
                "auto clip fps",
                clipEnabled);

        addHeader(
                tr("screen.recordable.settings.v07_section"),
                y += ROW);
        addToggle(
                left,
                half,
                y += 12,
                tr("screen.recordable.settings.markers_enabled"),
                () -> config.markersEnabled,
                value -> config.markersEnabled = value,
                "markers chapters");
        addToggle(
                right,
                half,
                y,
                tr("screen.recordable.settings.export_chapters"),
                () -> config.exportChapterFile,
                value -> config.exportChapterFile = value,
                "export chapter file");
        addToggle(
                left,
                half,
                y += ROW,
                tr("screen.recordable.settings.embed_chapters"),
                () -> config.embedChaptersInVideo,
                value -> config.embedChaptersInVideo = value,
                "embed chapters video");
        addToggle(
                right,
                half,
                y,
                tr("screen.recordable.settings.auto_marker_start"),
                () -> config.autoMarkerOnStart,
                value -> config.autoMarkerOnStart = value,
                "auto marker start");
        addToggle(
                left,
                half,
                y += ROW,
                tr("screen.recordable.settings.replay_buffer_enabled"),
                () -> config.replayBufferEnabled,
                value -> config.replayBufferEnabled = value,
                "replay buffer instant replay");
        addToggle(
                right,
                half,
                y,
                tr("screen.recordable.settings.replay_notify"),
                () -> config.replayBufferNotify,
                value -> config.replayBufferNotify = value,
                "replay notify");
        addSlider(
                left,
                half,
                y += ROW,
                () -> config.replayBufferDurationSeconds,
                value -> config.replayBufferDurationSeconds = value,
                10,
                300,
                10,
                value -> "Replay Buffer: " + value + " s",
                "replay duration");
        addCycle(
                right,
                half,
                y,
                () -> "Replay Quality: "
                        + display(config.replayBufferQuality),
                () -> config.replayBufferQuality = next(
                        config.replayBufferQuality,
                        RecordableConfig.REPLAY_QUALITIES),
                () -> config.replayBufferQuality = previous(
                        config.replayBufferQuality,
                        RecordableConfig.REPLAY_QUALITIES),
                "replay quality source balanced performance high");
        addToggle(
                left,
                half,
                y += ROW,
                tr("screen.recordable.settings.separate_audio"),
                () -> config.separateAudioTracks,
                value -> config.separateAudioTracks = value,
                "separate audio tracks");
        addToggle(
                right,
                half,
                y,
                tr("screen.recordable.settings.watermarks_enabled"),
                () -> config.watermarksEnabled,
                value -> config.watermarksEnabled = value,
                "enable watermarks");
        addAction(
                left,
                half,
                y += ROW,
                tr("screen.recordable.settings.open_storage"),
                () -> openOptionalScreen("StorageManagerScreen"),
                "storage manager cleanup recordings");
        addToggle(
                right,
                half,
                y,
                tr("screen.recordable.settings.auto_cleanup"),
                () -> config.autoCleanupEnabled,
                value -> config.autoCleanupEnabled = value,
                "auto cleanup storage");

        addHeader(
                tr("screen.recordable.settings.notify_section"),
                y += ROW);
        addToggle(
                left,
                half,
                y += 12,
                tr("screen.recordable.settings.notify_recording"),
                () -> config.notifyRecording,
                value -> config.notifyRecording = value,
                "recording notifications messages");
        addToggle(
                right,
                half,
                y,
                tr("screen.recordable.settings.notify_clips"),
                () -> config.notifyClips,
                value -> config.notifyClips = value,
                "clip montage notifications");
        addToggle(
                left,
                half,
                y += ROW,
                tr("screen.recordable.settings.notify_replay"),
                () -> config.notifyReplayBuffer,
                value -> config.notifyReplayBuffer = value,
                "replay notifications");
        addToggle(
                right,
                half,
                y,
                tr("screen.recordable.settings.notify_autorecord"),
                () -> config.notifyAutoRecord,
                value -> config.notifyAutoRecord = value,
                "auto record notifications");
        addToggle(
                left,
                half,
                y += ROW,
                tr("screen.recordable.settings.notify_bookmarks"),
                () -> config.notifyBookmarks,
                value -> config.notifyBookmarks = value,
                "bookmark notifications");
        addToggle(
                right,
                half,
                y,
                tr("screen.recordable.settings.notify_warnings"),
                () -> config.notifyWarnings,
                value -> config.notifyWarnings = value,
                "warning notifications");

        addHeader(
                tr("screen.recordable.settings.compat_section"),
                y += ROW);
        addToggle(
                left,
                half,
                y += 12,
                tr("screen.recordable.settings.compat_bridge"),
                () -> config.replayCompatBridge,
                value -> config.replayCompatBridge = value,
                "replay flashback compatibility bridge");
        addToggle(
                right,
                half,
                y,
                tr("screen.recordable.settings.compat_autorecord"),
                () -> config.replayAutoRecordPlayback,
                value -> config.replayAutoRecordPlayback = value,
                "replay auto record playback");
        addToggle(
                left,
                half,
                y += ROW,
                tr("screen.recordable.settings.compat_yield_audio"),
                () -> config.replayYieldAudioDevice,
                value -> config.replayYieldAudioDevice = value,
                "yield audio replay mod");
        return y;
    }

    private void addHeader(String text, int baseY) {
        decorations.add(new Decoration(
                DecorationType.HEADER,
                text,
                baseY,
                ThemeRole.PRIMARY));
    }

    private void addLabel(String text, int baseY, ThemeRole role) {
        decorations.add(new Decoration(
                DecorationType.LABEL,
                text,
                baseY,
                role));
    }

    private void addWrappedLabel(
            String text,
            int baseY,
            ThemeRole role) {
        decorations.add(new Decoration(
                DecorationType.WRAPPED,
                text,
                baseY,
                role));
    }

    private void addAction(
            int x,
            int widthValue,
            int baseY,
            String label,
            Runnable action,
            String keywords) {
        addAction(
                x,
                widthValue,
                baseY,
                () -> label,
                action,
                keywords);
    }

    private void addAction(
            int x,
            int widthValue,
            int baseY,
            Supplier<String> label,
            Runnable action,
            String keywords) {
        ActionButton button = new ActionButton(
                nextId++,
                x,
                0,
                widthValue,
                WIDGET_HEIGHT,
                label,
                action,
                null,
                () -> true);
        register(button, baseY, keywords);
    }

    private void addToggle(
            int x,
            int widthValue,
            int baseY,
            String label,
            BooleanSupplier getter,
            Consumer<Boolean> setter,
            String keywords) {
        addToggle(
                x,
                widthValue,
                baseY,
                label,
                getter,
                setter,
                keywords,
                () -> true);
    }

    private void addToggle(
            int x,
            int widthValue,
            int baseY,
            String label,
            BooleanSupplier getter,
            Consumer<Boolean> setter,
            String keywords,
            BooleanSupplier enabled) {
        ActionButton button = new ActionButton(
                nextId++,
                x,
                0,
                widthValue,
                WIDGET_HEIGHT,
                () -> label + ": " + (getter.getAsBoolean()
                        ? "ON"
                        : "OFF"),
                () -> setter.accept(Boolean.valueOf(
                        !getter.getAsBoolean())),
                null,
                enabled);
        register(button, baseY, keywords + " " + label);
    }

    private void addCycle(
            int x,
            int widthValue,
            int baseY,
            Supplier<String> label,
            Runnable forward,
            Runnable backward,
            String keywords) {
        ActionButton button = new ActionButton(
                nextId++,
                x,
                0,
                widthValue,
                WIDGET_HEIGHT,
                label,
                forward,
                backward,
                () -> true);
        register(button, baseY, keywords);
    }

    private void addSlider(
            int x,
            int widthValue,
            int baseY,
            IntSupplier getter,
            IntConsumer setter,
            int minimum,
            int maximum,
            int step,
            IntLabel label,
            String keywords) {
        addSlider(
                x,
                widthValue,
                baseY,
                getter,
                setter,
                minimum,
                maximum,
                step,
                label,
                keywords,
                () -> true);
    }

    private void addSlider(
            int x,
            int widthValue,
            int baseY,
            IntSupplier getter,
            IntConsumer setter,
            int minimum,
            int maximum,
            int step,
            IntLabel label,
            String keywords,
            BooleanSupplier enabled) {
        ValueSlider slider = new ValueSlider(
                nextId++,
                x,
                0,
                widthValue,
                WIDGET_HEIGHT,
                getter,
                setter,
                minimum,
                maximum,
                step,
                label,
                enabled);
        register(slider, baseY, keywords);
    }

    private TextEntry addText(
            int x,
            int widthValue,
            int baseY,
            String label,
            Supplier<String> getter,
            Consumer<String> setter,
            int maximumLength,
            String keywords) {
        TextEntry entry = new TextEntry(
                nextId++,
                x,
                widthValue,
                label,
                getter,
                setter,
                maximumLength);
        textEntries.add(entry);
        layout.add(new LayoutItem(
                null,
                entry,
                baseY,
                (keywords + " " + label).toLowerCase(Locale.ROOT)));
        return entry;
    }

    private void chooseOutputFolder() {
        final TextEntry entry = outputDirectoryEntry;
        if (entry == null) {
            return;
        }
        String defaultDirectory;
        try {
            defaultDirectory = config.getOutputDirectory()
                    .toAbsolutePath()
                    .normalize()
                    .toString();
        } catch (RuntimeException exception) {
            defaultDirectory = entry.field.getText();
        }
        NativeFolderPicker.pickFolder(
                "Choose recordings folder",
                defaultDirectory,
                chosen -> {
                    if (isBlank(chosen)) {
                        return;
                    }
                    Minecraft client = Minecraft.getMinecraft();
                    client.addScheduledTask(() -> {
                        entry.field.setText(chosen.trim());
                        entry.commit();
                        setStatus(
                                "Recordings folder updated.",
                                false);
                    });
                });
    }

    private void addColor(
            int x,
            int widthValue,
            int baseY,
            String label,
            Supplier<String> getter,
            Consumer<String> setter,
            String keywords) {
        ColorEntry entry = new ColorEntry(
                nextId++,
                x,
                widthValue,
                label,
                getter,
                setter);
        colorEntries.add(entry);
        layout.add(new LayoutItem(
                null,
                null,
                entry,
                baseY,
                (keywords + " " + label).toLowerCase(Locale.ROOT)));
    }

    private void register(
            ActionButton button,
            int baseY,
            String keywords) {
        buttonList.add(button);
        actionButtons.put(Integer.valueOf(button.id), button);
        layout.add(new LayoutItem(
                button,
                null,
                baseY,
                keywords == null
                        ? ""
                        : keywords.toLowerCase(Locale.ROOT)));
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button == null || !button.enabled) {
            return;
        }
        if (button.id == FOOTER_OPEN) {
            if (!PlatformUtils.open(config.getOutputDirectory())) {
                setStatus(
                        tr("screen.recordable.settings.open_folder_failed"),
                        true);
            } else {
                setStatus(
                        tr("screen.recordable.settings.opened_folder"),
                        false);
            }
            return;
        }
        if (button.id == FOOTER_DONE) {
            saveAndClose();
            return;
        }
        ActionButton action =
                actionButtons.get(Integer.valueOf(button.id));
        if (action != null) {
            action.pressPrimary();
            safeSave();
        }
    }

    @Override
    protected void mouseClicked(
            int mouseX,
            int mouseY,
            int mouseButton) throws IOException {
        boolean searchWasFocused = searchBox.isFocused();
        searchBox.mouseClicked(mouseX, mouseY, mouseButton);
        if (searchWasFocused && !searchBox.isFocused()) {
            searchQuery = searchBox.getText()
                    .trim()
                    .toLowerCase(Locale.ROOT);
            updateWidgetLayout();
        }

        for (TextEntry entry : textEntries) {
            boolean focused = entry.field.isFocused();
            entry.field.mouseClicked(mouseX, mouseY, mouseButton);
            if (focused && !entry.field.isFocused()) {
                entry.commit();
            }
        }
        for (ColorEntry entry : colorEntries) {
            entry.widget.mouseClicked(mouseX, mouseY, mouseButton);
        }

        if (mouseButton == 0 && isOverScrollbar(mouseX, mouseY)) {
            draggingScrollbar = true;
            scrollToMouse(mouseY);
            return;
        }
        if (mouseButton == 1) {
            for (ActionButton button : actionButtons.values()) {
                if (button.visible
                        && button.enabled
                        && button.hasSecondary()
                        && contains(button, mouseX, mouseY)) {
                    button.playPressSound(mc.getSoundHandler());
                    button.pressSecondary();
                    safeSave();
                    return;
                }
            }
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void mouseClickMove(
            int mouseX,
            int mouseY,
            int clickedMouseButton,
            long timeSinceLastClick) {
        if (draggingScrollbar && clickedMouseButton == 0) {
            scrollToMouse(mouseY);
            return;
        }
        super.mouseClickMove(
                mouseX,
                mouseY,
                clickedMouseButton,
                timeSinceLastClick);
    }

    @Override
    protected void mouseReleased(
            int mouseX,
            int mouseY,
            int state) {
        draggingScrollbar = false;
        super.mouseReleased(mouseX, mouseY, state);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode)
            throws IOException {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            saveAndClose();
            return;
        }
        if (searchBox.isFocused()) {
            searchBox.textboxKeyTyped(typedChar, keyCode);
            searchQuery = searchBox.getText()
                    .trim()
                    .toLowerCase(Locale.ROOT);
            scrollOffset = 0;
            updateWidgetLayout();
            return;
        }
        for (TextEntry entry : textEntries) {
            if (!entry.field.isFocused()) {
                continue;
            }
            if (keyCode == Keyboard.KEY_RETURN
                    || keyCode == Keyboard.KEY_NUMPADENTER) {
                entry.commit();
                entry.field.setFocused(false);
            } else {
                entry.field.textboxKeyTyped(typedChar, keyCode);
            }
            return;
        }
        for (ColorEntry entry : colorEntries) {
            if (entry.widget.isFocused()
                    && entry.widget.keyTyped(typedChar, keyCode)) {
                safeSave();
                return;
            }
        }
        if (keyCode == Keyboard.KEY_DOWN) {
            scrollBy(ROW);
            return;
        }
        if (keyCode == Keyboard.KEY_UP) {
            scrollBy(-ROW);
            return;
        }
        if (keyCode == Keyboard.KEY_NEXT) {
            scrollBy(panelBodyBottom - panelBodyTop);
            return;
        }
        if (keyCode == Keyboard.KEY_PRIOR) {
            scrollBy(-(panelBodyBottom - panelBodyTop));
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            scrollBy(wheel > 0 ? -20 : 20);
        }
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        searchBox.updateCursorCounter();
        for (TextEntry entry : textEntries) {
            entry.field.updateCursorCounter();
        }
        for (ColorEntry entry : colorEntries) {
            entry.widget.updateCursorCounter();
        }
    }

    @Override
    public void onGuiClosed() {
        for (TextEntry entry : textEntries) {
            entry.commit();
        }
        safeSave();
        Keyboard.enableRepeatEvents(false);
        super.onGuiClosed();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private void updateWidgetLayout() {
        if (searchQuery.isEmpty()) {
            searchMatchRows = -1;
            contentHeight = fullContentHeight;
            int max = maximumScroll();
            scrollOffset = clamp(scrollOffset, 0, max);
            for (LayoutItem item : layout) {
                positionItem(
                        item,
                        panelBodyTop + item.baseY - scrollOffset);
            }
            return;
        }

        Set<Integer> rows = new LinkedHashSet<Integer>();
        List<Integer> sortedRows = new ArrayList<Integer>();
        for (LayoutItem item : layout) {
            if (matchesSearch(item)) {
                rows.add(Integer.valueOf(item.baseY));
            }
        }
        sortedRows.addAll(rows);
        Collections.sort(sortedRows);
        searchMatchRows = sortedRows.size();
        contentHeight = sortedRows.size() * ROW;
        scrollOffset = clamp(scrollOffset, 0, maximumScroll());
        for (LayoutItem item : layout) {
            if (!matchesSearch(item)) {
                hideItem(item);
                continue;
            }
            int rowIndex = sortedRows.indexOf(Integer.valueOf(item.baseY));
            positionItem(
                    item,
                    panelBodyTop + rowIndex * ROW - scrollOffset);
        }
    }

    private void positionItem(LayoutItem item, int y) {
        boolean visible = y >= panelBodyTop
                && y + WIDGET_HEIGHT <= panelBodyBottom;
        if (item.button != null) {
            item.button.yPosition = y;
            item.button.visible = visible;
            item.button.enabled = visible
                    && item.button.dynamicEnabled();
        }
        if (item.text != null) {
            item.text.field.yPosition = y;
            item.text.field.setVisible(visible);
            item.text.field.setEnabled(visible);
        }
        if (item.color != null) {
            item.color.visible = visible;
            item.color.widget.setPosition(
                    item.color.x,
                    visible ? y : -10000);
        }
    }

    private void hideItem(LayoutItem item) {
        if (item.button != null) {
            item.button.visible = false;
            item.button.enabled = false;
        }
        if (item.text != null) {
            item.text.field.setVisible(false);
            item.text.field.setEnabled(false);
        }
        if (item.color != null) {
            item.color.visible = false;
            item.color.widget.setPosition(item.color.x, -10000);
        }
    }

    private boolean matchesSearch(LayoutItem item) {
        if (searchQuery.isEmpty()) {
            return true;
        }
        StringBuilder text = new StringBuilder(item.keywords);
        if (item.button != null) {
            item.button.refreshLabel();
            text.append(' ').append(
                    item.button.displayString.toLowerCase(Locale.ROOT));
        }
        if (item.text != null) {
            text.append(' ').append(
                    item.text.label.toLowerCase(Locale.ROOT));
        }
        if (item.color != null) {
            text.append(' ').append(
                    item.color.label.toLowerCase(Locale.ROOT));
        }
        return text.toString().contains(searchQuery);
    }

    private void scrollBy(int amount) {
        scrollOffset = clamp(
                scrollOffset + amount,
                0,
                maximumScroll());
        updateWidgetLayout();
    }

    private int maximumScroll() {
        return Math.max(
                0,
                contentHeight - (panelBodyBottom - panelBodyTop));
    }

    private boolean isOverScrollbar(double mouseX, double mouseY) {
        if (maximumScroll() <= 0) {
            return false;
        }
        int barLeft = panelLeft + panelWidth - 4;
        return mouseX >= barLeft - 2
                && mouseX <= barLeft + 5
                && mouseY >= panelBodyTop
                && mouseY <= panelBodyBottom;
    }

    private void scrollToMouse(double mouseY) {
        int viewport = panelBodyBottom - panelBodyTop;
        int maximum = maximumScroll();
        if (maximum <= 0) {
            return;
        }
        int thumb = Math.max(
                22,
                (int) (viewport * (viewport / (double) contentHeight)));
        int available = viewport - thumb;
        double relative = available <= 0
                ? 0.0D
                : (mouseY - panelBodyTop - thumb / 2.0D) / available;
        relative = Math.max(0.0D, Math.min(1.0D, relative));
        scrollOffset = (int) Math.round(relative * maximum);
        updateWidgetLayout();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        ThemeColors colors = colors();
        ThemePreset preset = config.uiTheme == null
                ? ThemePreset.VHS
                : config.uiTheme;
        int left = panelLeft - 6;
        int right = panelLeft + panelWidth + 6;
        int top = panelTop - 6;
        if (preset == ThemePreset.CINEMA) {
            drawFilmPanel(colors, left, top, right, panelBottom);
        } else {
            drawPanel(colors, left, top, right, panelBottom);
        }

        String title = tr("screen.recordable.settings.title");
        if (preset == ThemePreset.VHS || preset == ThemePreset.NEON) {
            title = "[ " + title + " ]";
            int alpha = (int) (200.0D + 55.0D * pulse(3000));
            drawCenteredString(
                    fontRendererObj,
                    title,
                    width / 2,
                    panelTop,
                    alpha << 24 | colors.headerText & 0xFFFFFF);
        } else if (preset == ThemePreset.CINEMA) {
            drawCenteredString(
                    fontRendererObj,
                    "\u2605 " + title + " \u2605",
                    width / 2,
                    panelTop,
                    colors.headerText);
        } else {
            drawCenteredString(
                    fontRendererObj,
                    title,
                    width / 2,
                    panelTop,
                    colors.headerText);
        }

        super.drawScreen(mouseX, mouseY, partialTicks);
        searchBox.drawTextBox();
        if (searchBox.getText().isEmpty()
                && !searchBox.isFocused()) {
            fontRendererObj.drawStringWithShadow(
                    tr("screen.recordable.settings.search"),
                    searchBox.xPosition + 4,
                    searchBox.yPosition + 5,
                    colors.textMuted);
        }
        for (TextEntry entry : textEntries) {
            if (entry.field.getVisible()) {
                entry.field.drawTextBox();
            }
        }
        for (ColorEntry entry : colorEntries) {
            if (entry.visible) {
                entry.widget.drawWidget();
            }
        }

        if (searchQuery.isEmpty()) {
            for (Decoration decoration : decorations) {
                drawDecoration(decoration, colors);
            }
        } else if (searchMatchRows == 0) {
            drawCenteredString(
                    fontRendererObj,
                    tr("screen.recordable.settings.no_search_results"),
                    width / 2,
                    panelBodyTop + 12,
                    colors.textMuted);
        }

        if (!isBlank(statusMessage)
                && System.currentTimeMillis() < statusUntil) {
            drawCenteredString(
                    fontRendererObj,
                    trim(statusMessage, Math.max(24, panelWidth / 6)),
                    width / 2,
                    panelBottom - 12,
                    statusError ? colors.textError : colors.textMuted);
        }
        drawScrollbar(colors);
    }

    private void drawDecoration(
            Decoration decoration,
            ThemeColors colors) {
        int y = panelBodyTop + decoration.baseY - scrollOffset;
        if (y < panelBodyTop - 18 || y > panelBodyBottom + 2) {
            return;
        }
        int x = panelLeft + 14;
        int available = panelWidth - 28;
        if (decoration.type == DecorationType.HEADER) {
            drawSectionHeader(
                    colors,
                    decoration.text,
                    x,
                    y,
                    available);
            return;
        }
        int color = roleColor(colors, decoration.role);
        if (decoration.type == DecorationType.WRAPPED) {
            drawWrapped(
                    decoration.text,
                    x,
                    y,
                    available,
                    color);
        } else {
            fontRendererObj.drawStringWithShadow(
                    decoration.text,
                    x,
                    y,
                    color);
        }
    }

    private void drawPanel(
            ThemeColors colors,
            int left,
            int top,
            int right,
            int bottom) {
        Gui.drawRect(left, top, right, bottom, colors.panelBackground);
        Gui.drawRect(left, top, right, top + 2, colors.accent);
        Gui.drawRect(
                left,
                bottom - 1,
                right,
                bottom,
                colors.panelBorder);
        Gui.drawRect(left, top, left + 1, bottom, colors.panelBorder);
        Gui.drawRect(
                right - 1,
                top,
                right,
                bottom,
                colors.panelBorder);
        renderPanelEffects(
                colors,
                left + 1,
                top + 2,
                right - 1,
                bottom - 1);
    }

    private void drawFilmPanel(
            ThemeColors colors,
            int left,
            int top,
            int right,
            int bottom) {
        int strip = 12;
        Gui.drawRect(
                left + strip,
                top,
                right - strip,
                bottom,
                colors.panelBackground);
        Gui.drawRect(
                left,
                top,
                left + strip,
                bottom,
                colors.panelBorder);
        Gui.drawRect(
                right - strip,
                top,
                right,
                bottom,
                colors.panelBorder);
        for (int y = top + 6; y < bottom - 6; y += 18) {
            drawSprocket(left + 2, y, colors.accent);
            drawSprocket(right - strip + 2, y, colors.accent);
        }
        renderPanelEffects(
                colors,
                left + strip,
                top,
                right - strip,
                bottom);
    }

    private static void drawSprocket(int x, int y, int color) {
        Gui.drawRect(x, y, x + 6, y + 4, color);
        Gui.drawRect(x + 1, y + 1, x + 5, y + 3, 0xFF000000);
    }

    private void renderPanelEffects(
            ThemeColors colors,
            int left,
            int top,
            int right,
            int bottom) {
        if (config.uiScanlines
                && (colors.scanlineColor & 0xFF000000) != 0) {
            for (int y = top; y < bottom; y += 2) {
                Gui.drawRect(
                        left,
                        y,
                        right,
                        y + 1,
                        colors.scanlineColor);
            }
        }
        if (config.uiFilmGrain
                && (colors.grainColor & 0xFF000000) != 0) {
            int panelW = Math.max(1, right - left);
            int panelH = Math.max(1, bottom - top);
            int count = Math.max(20, panelW * panelH / 400);
            int baseAlpha = colors.grainColor >>> 24;
            for (int index = 0; index < count; index++) {
                int x = left + EFFECT_RANDOM.nextInt(panelW);
                int y = top + EFFECT_RANDOM.nextInt(panelH);
                int alpha = Math.max(
                        1,
                        baseAlpha / 2
                                + EFFECT_RANDOM.nextInt(
                                        Math.max(1, baseAlpha / 2)));
                Gui.drawRect(
                        x,
                        y,
                        x + 1,
                        y + 1,
                        alpha << 24
                                | colors.grainColor & 0xFFFFFF);
            }
        }
        if (config.uiGlitchEffects
                && (colors.glitchColor & 0xFF000000) != 0) {
            renderGlitch(colors, left, top, right, bottom);
        }
        if (config.uiVignette
                && (colors.vignetteColor & 0xFF000000) != 0) {
            renderVignette(colors, left, top, right, bottom);
        }
    }

    private static void renderGlitch(
            ThemeColors colors,
            int left,
            int top,
            int right,
            int bottom) {
        long now = System.currentTimeMillis();
        if (!glitchActive
                && now - lastGlitchTick
                    > 3000L + EFFECT_RANDOM.nextInt(5000)) {
            glitchActive = true;
            lastGlitchTick = now;
            glitchY = top + EFFECT_RANDOM.nextInt(
                    Math.max(1, bottom - top - 10));
            glitchHeight = 2 + EFFECT_RANDOM.nextInt(6);
        }
        if (!glitchActive) {
            return;
        }
        if (now - lastGlitchTick
                > 100L + EFFECT_RANDOM.nextInt(200)) {
            glitchActive = false;
            return;
        }
        int shift = -3 + EFFECT_RANDOM.nextInt(7);
        Gui.drawRect(
                left + shift,
                glitchY,
                right + shift,
                Math.min(bottom, glitchY + glitchHeight),
                colors.glitchColor);
        int y2 = glitchY + 8 + EFFECT_RANDOM.nextInt(20);
        if (y2 < bottom) {
            Gui.drawRect(
                    left - shift,
                    y2,
                    right - shift,
                    Math.min(bottom, y2 + 2),
                    colors.glitchColor & 0x80FFFFFF);
        }
    }

    private static void renderVignette(
            ThemeColors colors,
            int left,
            int top,
            int right,
            int bottom) {
        int panelW = right - left;
        int panelH = bottom - top;
        int borderW = Math.max(6, panelW / 12);
        int borderH = Math.max(6, panelH / 12);
        int alpha = colors.vignetteColor >>> 24;
        for (int layer = 0; layer < 4; layer++) {
            int layerAlpha = alpha * (4 - layer) / 6;
            int color = layerAlpha << 24;
            int insetX = layer * Math.max(1, borderW / 4);
            int insetY = layer * Math.max(1, borderH / 4);
            Gui.drawRect(
                    left + insetX,
                    top + insetY,
                    right - insetX,
                    top + borderH - insetY,
                    color);
            Gui.drawRect(
                    left + insetX,
                    bottom - borderH + insetY,
                    right - insetX,
                    bottom - insetY,
                    color);
            Gui.drawRect(
                    left + insetX,
                    top + borderH - insetY,
                    left + borderW - insetX,
                    bottom - borderH + insetY,
                    color);
            Gui.drawRect(
                    right - borderW + insetX,
                    top + borderH - insetY,
                    right - insetX,
                    bottom - borderH + insetY,
                    color);
        }
    }

    private void drawSectionHeader(
            ThemeColors colors,
            String text,
            int x,
            int y,
            int maximumWidth) {
        fontRendererObj.drawStringWithShadow(
                text,
                x,
                y,
                colors.headerText);
        int textWidth = fontRendererObj.getStringWidth(text);
        int lineY = y + 10;
        Gui.drawRect(
                x,
                lineY,
                x + textWidth,
                lineY + 1,
                colors.headerUnderline);
        int extension = Math.min(
                x + maximumWidth,
                x + textWidth + 40);
        if (extension > x + textWidth + 4) {
            Gui.drawRect(
                    x + textWidth + 2,
                    lineY,
                    extension,
                    lineY + 1,
                    colors.headerUnderline & 0x40FFFFFF);
        }
        ThemePreset preset = config.uiTheme;
        if (preset == ThemePreset.VHS
                || preset == ThemePreset.NEON) {
            fontRendererObj.drawStringWithShadow(
                    "\u258c",
                    x - 8,
                    y,
                    colors.accent);
        } else if (preset == ThemePreset.CINEMA) {
            fontRendererObj.drawStringWithShadow(
                    "\u2605",
                    x - 10,
                    y,
                    colors.accent);
        }
    }

    private void drawScrollbar(ThemeColors colors) {
        int maximum = maximumScroll();
        if (maximum <= 0) {
            return;
        }
        int viewport = panelBodyBottom - panelBodyTop;
        int thumb = Math.max(
                22,
                (int) (viewport * (viewport / (double) contentHeight)));
        int available = viewport - thumb;
        int thumbTop = panelBodyTop
                + (int) (scrollOffset
                    / (double) maximum
                    * available);
        int left = panelLeft + panelWidth - 4;
        Gui.drawRect(
                left,
                panelBodyTop,
                left + 3,
                panelBodyBottom,
                colors.scrollTrack);
        Gui.drawRect(
                left,
                thumbTop,
                left + 3,
                thumbTop + thumb,
                colors.scrollThumb);
    }

    private int drawWrapped(
            String text,
            int x,
            int y,
            int maximumWidth,
            int color) {
        List<String> lines =
                fontRendererObj.listFormattedStringToWidth(
                        text == null ? "" : text,
                        Math.max(20, maximumWidth));
        int drawY = y;
        for (String line : lines) {
            if (drawY >= panelBodyTop - 10
                    && drawY <= panelBodyBottom) {
                fontRendererObj.drawStringWithShadow(
                        line,
                        x,
                        drawY,
                        color);
            }
            drawY += fontRendererObj.FONT_HEIGHT + 1;
        }
        return drawY - y;
    }

    private ThemeColors colors() {
        return ThemeColors.forPreset(
                config == null ? ThemePreset.VHS : config.uiTheme);
    }

    private static int roleColor(
            ThemeColors colors,
            ThemeRole role) {
        if (role == ThemeRole.ERROR) return colors.textError;
        if (role == ThemeRole.WARNING) return 0xFFFFCC44;
        if (role == ThemeRole.SECONDARY) return colors.textSecondary;
        if (role == ThemeRole.MUTED) return colors.textMuted;
        return colors.textPrimary;
    }

    private int wrappedHeight(String text, int maximumWidth) {
        return fontRendererObj.listFormattedStringToWidth(
                text == null ? "" : text,
                Math.max(20, maximumWidth)).size()
                * (fontRendererObj.FONT_HEIGHT + 1);
    }

    private String platformAudioWarning() {
        if (PlatformUtils.isWindows()) {
            return "\u2139 Audio capture uses system loopback and direct "
                    + "OpenAL capture. If a driver source is needed, enable "
                    + "Stereo Mix in Windows Sound settings.";
        }
        if (PlatformUtils.isLinux()) {
            return "\u2139 Audio capture uses the available PulseAudio/"
                    + "Java Sound monitor source.";
        }
        if (PlatformUtils.isMacOS()) {
            return "\u2139 Install a loopback driver such as BlackHole "
                    + "when direct game-audio capture is unavailable.";
        }
        return "\u2139 Game audio is captured from the best available "
                + "client-side source.";
    }

    private String performanceHint() {
        if (config.fps >= 120) {
            return "120 FPS is expensive and may drop frames on Bedwars.";
        }
        if ("native".equals(config.resolution)
                && config.fps >= 60) {
            return "Native resolution at 60 FPS prioritizes clarity.";
        }
        if ("performance".equals(config.quality)) {
            return "Performance quality reduces recording overhead.";
        }
        return "Balanced settings are suitable for most systems.";
    }

    private String displayOutputPath() {
        try {
            return config.getOutputDirectory()
                    .toAbsolutePath()
                    .normalize()
                    .toString();
        } catch (RuntimeException exception) {
            return config.outputDir;
        }
    }

    private String diskSpaceLine() {
        try {
            StorageManager.StorageStats stats =
                    StorageManager.computeStats(config);
            return "Disk: " + stats.diskFreeDisplay() + " free";
        } catch (RuntimeException exception) {
            return "Disk space unavailable";
        }
    }

    private String ffmpegStatusLine() {
        if (ffmpegStatus != null && ffmpegStatus.isFound()) {
            return "Platform: " + PlatformUtils.detectPlatform().name()
                    + " | FFmpeg: " + ffmpegStatus.getVersion();
        }
        String error = ffmpegStatus == null
                ? FfmpegBundleManager.getLastError()
                : ffmpegStatus.getError();
        return "Platform: " + PlatformUtils.detectPlatform().name()
                + " | FFmpeg: "
                + (isBlank(error) ? "Not found" : trim(error, 80));
    }

    private void detectFfmpeg() {
        setStatus("Detecting FFmpeg...", false);
        Thread worker = new Thread(() -> {
            FfmpegBundleManager.invalidateCache();
            final FfmpegBundleManager.FfmpegStatus detected =
                    FfmpegBundleManager.detectFfmpeg();
            mc.addScheduledTask(() -> {
                ffmpegStatus = detected;
                setStatus(
                        detected.isFound()
                                ? "\u2713 FFmpeg detected: "
                                    + detected.getVersion()
                                : "FFmpeg was not found.",
                        !detected.isFound());
                int saved = scrollOffset;
                initGui();
                scrollOffset = saved;
                updateWidgetLayout();
            });
        }, "Recordable-Settings-FfmpegProbe");
        worker.setDaemon(true);
        worker.start();
    }

    private void refreshAudioDevices() {
        if (audioScanRunning) {
            return;
        }
        audioScanRunning = true;
        audioStatus = "Scanning audio devices...";
        Thread worker = new Thread(() -> {
            List<AudioCaptureSession.AudioDevice> found;
            String result;
            try {
                found = AudioCaptureSession.listDevices();
                result = found.isEmpty()
                        ? "No capture devices detected"
                        : found.size() + " audio device"
                            + (found.size() == 1 ? "" : "s");
            } catch (RuntimeException exception) {
                found = Collections.emptyList();
                result = "Audio scan failed: " + safeMessage(exception);
            }
            final List<AudioCaptureSession.AudioDevice> finalFound = found;
            final String finalResult = result;
            mc.addScheduledTask(() -> {
                audioDevices = finalFound;
                audioStatus = finalResult;
                audioScanRunning = false;
                for (ActionButton button : actionButtons.values()) {
                    button.refreshLabel();
                }
            });
        }, "Recordable-Settings-AudioScan");
        worker.setDaemon(true);
        worker.start();
    }

    private String deviceDisplay(boolean microphone) {
        String configured = microphone
                ? config.microphoneDevice
                : config.audioDevice;
        if (isBlank(configured)
                || "auto".equalsIgnoreCase(configured.trim())) {
            return "Auto";
        }
        for (AudioCaptureSession.AudioDevice device : audioDevices) {
            if (configured.equalsIgnoreCase(device.getId())
                    || configured.equalsIgnoreCase(
                            device.getDisplayName())) {
                return trim(device.getDisplayName(), 26);
            }
        }
        return trim(configured, 26);
    }

    private void cycleDevice(boolean microphone, boolean forward) {
        List<String> choices = new ArrayList<String>();
        choices.add("auto");
        for (AudioCaptureSession.AudioDevice device : audioDevices) {
            if (!microphone && !device.isLoopbackCandidate()) {
                continue;
            }
            if (microphone && device.isLoopbackCandidate()) {
                continue;
            }
            choices.add(device.getId());
        }
        if (choices.size() == 1) {
            for (AudioCaptureSession.AudioDevice device : audioDevices) {
                choices.add(device.getId());
            }
        }
        String configured = microphone
                ? config.microphoneDevice
                : config.audioDevice;
        int index = choices.indexOf(
                isBlank(configured) ? "auto" : configured);
        if (index < 0) index = 0;
        index = forward
                ? (index + 1) % choices.size()
                : (index - 1 + choices.size()) % choices.size();
        if (microphone) {
            config.microphoneDevice = choices.get(index);
        } else {
            config.audioDevice = choices.get(index);
        }
    }

    private void runMicrophoneTest() {
        setStatus(tr("screen.recordable.settings.test_mic_running"), false);
        Thread worker = new Thread(() -> {
            TargetDataLine line = null;
            try {
                AudioFormat format = new AudioFormat(
                        Math.max(8000, config.audioSampleRate),
                        16,
                        1,
                        true,
                        false);
                Mixer.Info info = selectMicrophoneMixer();
                DataLine.Info lineInfo = new DataLine.Info(
                        TargetDataLine.class,
                        format);
                if (info != null) {
                    Mixer mixer = AudioSystem.getMixer(info);
                    line = (TargetDataLine) mixer.getLine(lineInfo);
                } else {
                    line = (TargetDataLine) AudioSystem.getLine(lineInfo);
                }
                line.open(format);
                line.start();
                byte[] buffer = new byte[4096];
                long deadline = System.nanoTime()
                        + 3_000_000_000L;
                double sum = 0.0D;
                long samples = 0L;
                int peak = 0;
                while (System.nanoTime() < deadline) {
                    int read = line.read(buffer, 0, buffer.length);
                    for (int index = 0; index + 1 < read; index += 2) {
                        int sample = (short) ((buffer[index] & 255)
                                | buffer[index + 1] << 8);
                        int absolute = Math.abs(sample);
                        peak = Math.max(peak, absolute);
                        double normalized = sample / 32768.0D;
                        sum += normalized * normalized;
                        samples++;
                    }
                }
                final int rmsPercent = samples <= 0L
                        ? 0
                        : (int) Math.round(
                                Math.sqrt(sum / samples) * 100.0D);
                final int peakPercent = (int) Math.round(
                        peak / 32768.0D * 100.0D);
                final boolean noSamples = samples <= 0L;
                mc.addScheduledTask(() -> setStatus(
                        "Mic test: " + rmsPercent
                                + "% average, " + peakPercent
                                + "% peak",
                        noSamples));
            } catch (Exception exception) {
                final String failure = safeMessage(exception);
                mc.addScheduledTask(() -> setStatus(
                        "Microphone test failed: " + failure,
                        true));
            } finally {
                if (line != null) {
                    try {
                        line.stop();
                    } catch (RuntimeException ignored) {
                    }
                    line.close();
                }
            }
        }, "Recordable-MicrophoneTest");
        worker.setDaemon(true);
        worker.start();
    }

    private Mixer.Info selectMicrophoneMixer() {
        Mixer.Info[] infos = AudioSystem.getMixerInfo();
        String selected = config.microphoneDevice == null
                ? "auto"
                : config.microphoneDevice.trim();
        if (!"auto".equalsIgnoreCase(selected)) {
            for (int index = 0; index < infos.length; index++) {
                String name = infos[index].getName()
                        + " " + infos[index].getDescription();
                if (Integer.toString(index).equals(selected)
                        || name.toLowerCase(Locale.ROOT).contains(
                                selected.toLowerCase(Locale.ROOT))) {
                    return infos[index];
                }
            }
        }
        for (AudioCaptureSession.AudioDevice device : audioDevices) {
            if (device.isLoopbackCandidate()) {
                continue;
            }
            try {
                int index = Integer.parseInt(device.getId());
                if (index >= 0 && index < infos.length) {
                    return infos[index];
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private void openOptionalScreen(String simpleName) {
        try {
            Class<?> type = Class.forName(
                    "dev.recordable.screen." + simpleName);
            Object screen = type
                    .getConstructor(GuiScreen.class)
                    .newInstance(this);
            mc.displayGuiScreen((GuiScreen) screen);
        } catch (Exception exception) {
            RecordableMod.LOGGER.warn(
                    "Unable to open {}.",
                    simpleName,
                    exception);
            setStatus(
                    "Could not open "
                            + simpleName.replace("Screen", "")
                            + ".",
                    true);
        }
    }

    protected void saveAndClose() {
        for (TextEntry entry : textEntries) {
            entry.commit();
        }
        safeSave();
        mc.displayGuiScreen(parent);
    }

    protected void safeSave() {
        try {
            config.save();
        } catch (RuntimeException exception) {
            setStatus(
                    "Could not save settings: "
                            + safeMessage(exception),
                    true);
        }
    }

    private void setStatus(String message, boolean error) {
        statusMessage = message == null ? "" : message;
        statusError = error;
        statusUntil = System.currentTimeMillis() + 6000L;
    }

    private static boolean contains(
            GuiButton button,
            int mouseX,
            int mouseY) {
        return mouseX >= button.xPosition
                && mouseX < button.xPosition + button.width
                && mouseY >= button.yPosition
                && mouseY < button.yPosition + button.height;
    }

    private static String tr(String key, Object... arguments) {
        try {
            return I18n.format(key, arguments);
        } catch (RuntimeException ignored) {
            return key;
        }
    }

    private static String display(String value) {
        if (isBlank(value)) return "Auto";
        String normalized = value.replace('_', ' ').trim();
        String[] words = normalized.split(" ");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (result.length() > 0) result.append(' ');
            result.append(Character.toUpperCase(word.charAt(0)));
            if (word.length() > 1) result.append(word.substring(1));
        }
        return result.toString();
    }

    private static String next(String current, String[] values) {
        if (values == null || values.length == 0) return current;
        for (int index = 0; index < values.length; index++) {
            if (values[index].equalsIgnoreCase(
                    current == null ? "" : current)) {
                return values[(index + 1) % values.length];
            }
        }
        return values[0];
    }

    private static String previous(String current, String[] values) {
        if (values == null || values.length == 0) return current;
        for (int index = 0; index < values.length; index++) {
            if (values[index].equalsIgnoreCase(
                    current == null ? "" : current)) {
                return values[
                        (index - 1 + values.length) % values.length];
            }
        }
        return values[0];
    }

    private static <T> T nextEnum(T current, T[] values) {
        if (values == null || values.length == 0) return current;
        int index = 0;
        for (int candidate = 0; candidate < values.length; candidate++) {
            if (values[candidate] == current) {
                index = candidate;
                break;
            }
        }
        return values[(index + 1) % values.length];
    }

    private static <T> T previousEnum(T current, T[] values) {
        if (values == null || values.length == 0) return current;
        int index = 0;
        for (int candidate = 0; candidate < values.length; candidate++) {
            if (values[candidate] == current) {
                index = candidate;
                break;
            }
        }
        return values[
                (index - 1 + values.length) % values.length];
    }

    private List<RecordableConfig.AudioEncoder> compatibleAudioEncoders() {
        List<RecordableConfig.AudioEncoder> compatible =
                new ArrayList<RecordableConfig.AudioEncoder>();
        String container = config.getContainerFromFormat();
        for (RecordableConfig.AudioEncoder encoder
                : RecordableConfig.AudioEncoder.values()) {
            if (encoder.supportsContainer(container)) {
                compatible.add(encoder);
            }
        }
        if (compatible.isEmpty()) {
            compatible.add(RecordableConfig.AudioEncoder.AAC);
        }
        return compatible;
    }

    private static RecordableConfig.AudioEncoder nextAudioEncoder(
            List<RecordableConfig.AudioEncoder> values,
            RecordableConfig.AudioEncoder current) {
        if (values == null || values.isEmpty()) {
            return RecordableConfig.AudioEncoder.AAC;
        }
        int index = values.indexOf(current);
        if (index < 0) {
            return values.get(0);
        }
        return values.get((index + 1) % values.size());
    }

    private static String audioEncoderLabel(
            RecordableConfig.AudioEncoder encoder) {
        RecordableConfig.AudioEncoder value = encoder == null
                ? RecordableConfig.AudioEncoder.AAC
                : encoder;
        return "Audio Encoder: " + value.displayName;
    }

    private static int nearest(int value, int[] values) {
        if (values == null || values.length == 0) return value;
        int nearest = values[0];
        int distance = Math.abs(value - nearest);
        for (int candidate : values) {
            int nextDistance = Math.abs(value - candidate);
            if (nextDistance < distance) {
                nearest = candidate;
                distance = nextDistance;
            }
        }
        return nearest;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static String trim(String value, int maximumCharacters) {
        if (value == null) return "";
        String normalized = value.replace('\r', ' ')
                .replace('\n', ' ')
                .trim();
        if (normalized.length() <= maximumCharacters) {
            return normalized;
        }
        return normalized.substring(
                0,
                Math.max(0, maximumCharacters - 1)) + "\u2026";
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String safeMessage(Throwable throwable) {
        if (throwable == null) return "Unknown error";
        String message = throwable.getMessage();
        return isBlank(message)
                ? throwable.getClass().getSimpleName()
                : trim(message, 120);
    }

    private static double pulse(int periodMilliseconds) {
        float phase = (System.currentTimeMillis()
                % periodMilliseconds)
                / (float) periodMilliseconds;
        return 0.5D + 0.5D
                * Math.sin(phase * Math.PI * 2.0D);
    }

    private static class ActionButton extends GuiButton {
        private final Supplier<String> labelSupplier;
        private final Runnable primary;
        private final Runnable secondary;
        private final BooleanSupplier enabledSupplier;

        ActionButton(
                int id,
                int x,
                int y,
                int width,
                int height,
                Supplier<String> labelSupplier,
                Runnable primary,
                Runnable secondary,
                BooleanSupplier enabledSupplier) {
            super(id, x, y, width, height, "");
            this.labelSupplier = labelSupplier;
            this.primary = primary;
            this.secondary = secondary;
            this.enabledSupplier = enabledSupplier;
            refreshLabel();
        }

        void refreshLabel() {
            try {
                displayString = labelSupplier == null
                        ? ""
                        : labelSupplier.get();
            } catch (RuntimeException exception) {
                displayString = "Unavailable";
            }
        }

        boolean dynamicEnabled() {
            try {
                return enabledSupplier == null
                        || enabledSupplier.getAsBoolean();
            } catch (RuntimeException ignored) {
                return false;
            }
        }

        void pressPrimary() {
            if (primary != null) primary.run();
            refreshLabel();
        }

        void pressSecondary() {
            if (secondary != null) secondary.run();
            refreshLabel();
        }

        boolean hasSecondary() {
            return secondary != null;
        }

        @Override
        public void drawButton(
                Minecraft minecraft,
                int mouseX,
                int mouseY) {
            refreshLabel();
            super.drawButton(minecraft, mouseX, mouseY);
        }
    }

    private final class ValueSlider extends ActionButton {
        private final IntSupplier getter;
        private final IntConsumer setter;
        private final int minimum;
        private final int maximum;
        private final int step;
        private boolean dragging;

        ValueSlider(
                int id,
                int x,
                int y,
                int width,
                int height,
                IntSupplier getter,
                IntConsumer setter,
                int minimum,
                int maximum,
                int step,
                IntLabel label,
                BooleanSupplier enabled) {
            super(
                    id,
                    x,
                    y,
                    width,
                    height,
                    () -> label.label(clamp(
                            getter.getAsInt(),
                            minimum,
                            maximum)),
                    null,
                    null,
                    enabled);
            this.getter = getter;
            this.setter = setter;
            this.minimum = minimum;
            this.maximum = maximum;
            this.step = Math.max(1, step);
        }

        @Override
        public boolean mousePressed(
                Minecraft minecraft,
                int mouseX,
                int mouseY) {
            boolean pressed = super.mousePressed(
                    minecraft,
                    mouseX,
                    mouseY);
            if (pressed) {
                dragging = true;
                setFromMouse(mouseX);
            }
            return pressed;
        }

        @Override
        protected void mouseDragged(
                Minecraft minecraft,
                int mouseX,
                int mouseY) {
            if (dragging) {
                setFromMouse(mouseX);
            }
        }

        @Override
        public void mouseReleased(int mouseX, int mouseY) {
            if (dragging) {
                setFromMouse(mouseX);
                dragging = false;
                safeSave();
            }
            super.mouseReleased(mouseX, mouseY);
        }

        @Override
        public void drawButton(
                Minecraft minecraft,
                int mouseX,
                int mouseY) {
            super.drawButton(minecraft, mouseX, mouseY);
            if (!visible) return;
            int trackLeft = xPosition + 4;
            int trackRight = xPosition + width - 4;
            int trackY = yPosition + height - 4;
            Gui.drawRect(
                    trackLeft,
                    trackY,
                    trackRight,
                    trackY + 1,
                    0xFF555555);
            int value = clamp(getter.getAsInt(), minimum, maximum);
            double fraction = maximum <= minimum
                    ? 0.0D
                    : (value - minimum)
                        / (double) (maximum - minimum);
            int thumb = trackLeft
                    + (int) Math.round(
                            fraction * (trackRight - trackLeft));
            Gui.drawRect(
                    thumb - 1,
                    trackY - 2,
                    thumb + 2,
                    trackY + 3,
                    0xFFFFFFFF);
        }

        private void setFromMouse(int mouseX) {
            if (maximum <= minimum) return;
            double fraction = (mouseX - xPosition)
                    / (double) Math.max(1, width);
            fraction = Math.max(0.0D, Math.min(1.0D, fraction));
            double raw = minimum
                    + fraction * (maximum - minimum);
            int snapped = minimum
                    + (int) Math.round(
                            (raw - minimum) / step) * step;
            setter.accept(clamp(snapped, minimum, maximum));
            refreshLabel();
        }
    }

    private final class TextEntry {
        final String label;
        final Supplier<String> getter;
        final Consumer<String> setter;
        final GuiTextField field;
        String lastValue;

        TextEntry(
                int id,
                int x,
                int width,
                String label,
                Supplier<String> getter,
                Consumer<String> setter,
                int maximumLength) {
            this.label = label;
            this.getter = getter;
            this.setter = setter;
            this.field = new GuiTextField(
                    id,
                    fontRendererObj,
                    x,
                    0,
                    width,
                    WIDGET_HEIGHT);
            this.field.setMaxStringLength(maximumLength);
            this.lastValue = getter.get() == null ? "" : getter.get();
            this.field.setText(lastValue);
        }

        void commit() {
            String current = field.getText() == null
                    ? ""
                    : field.getText();
            if (current.equals(lastValue)) {
                return;
            }
            setter.accept(current);
            safeSave();
            lastValue = getter.get() == null ? "" : getter.get();
            if (!lastValue.equals(field.getText())) {
                field.setText(lastValue);
            }
        }
    }

    private final class ColorEntry {
        final int x;
        final String label;
        final ColorPickerWidget widget;
        boolean visible;

        ColorEntry(
                int id,
                int x,
                int width,
                String label,
                Supplier<String> getter,
                Consumer<String> setter) {
            this.x = x;
            this.label = label;
            this.widget = new ColorPickerWidget(
                    fontRendererObj,
                    id,
                    x,
                    0,
                    width,
                    WIDGET_HEIGHT,
                    label,
                    getter.get(),
                    value -> {
                        setter.accept(value);
                        safeSave();
                    });
        }
    }

    private static final class LayoutItem {
        final ActionButton button;
        final TextEntry text;
        final ColorEntry color;
        final int baseY;
        final String keywords;

        LayoutItem(
                ActionButton button,
                TextEntry text,
                int baseY,
                String keywords) {
            this(button, text, null, baseY, keywords);
        }

        LayoutItem(
                ActionButton button,
                TextEntry text,
                ColorEntry color,
                int baseY,
                String keywords) {
            this.button = button;
            this.text = text;
            this.color = color;
            this.baseY = baseY;
            this.keywords = keywords == null ? "" : keywords;
        }
    }

    private static final class Decoration {
        final DecorationType type;
        final String text;
        final int baseY;
        final ThemeRole role;

        Decoration(
                DecorationType type,
                String text,
                int baseY,
                ThemeRole role) {
            this.type = type;
            this.text = text == null ? "" : text;
            this.baseY = baseY;
            this.role = role;
        }
    }

    private enum DecorationType {
        HEADER,
        LABEL,
        WRAPPED
    }

    private enum ThemeRole {
        PRIMARY,
        SECONDARY,
        MUTED,
        WARNING,
        ERROR
    }

    private interface IntLabel {
        String label(int value);
    }
}
