package dev.recordable.screen;

import dev.recordable.AudioCaptureSession;
import dev.recordable.FfmpegBundleManager;
import dev.recordable.PlatformUtils;
import dev.recordable.RecordableConfig;
import dev.recordable.StorageManager;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;

import org.lwjgl.input.Keyboard;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Main Record-able configuration screen for Forge 1.8.9.
 *
 * <p>The original mod has a large collection of modern, scrollable screens.
 * This legacy implementation keeps the same effective settings in one compact
 * category-and-page UI that remains navigable on small game windows.</p>
 */
public final class RecordableSettingsScreen extends GuiScreen {
    private static final int CATEGORY_ID_BASE = 10;
    private static final int PREVIOUS_PAGE_ID = 30;
    private static final int NEXT_PAGE_ID = 31;
    private static final int DONE_ID = 32;
    private static final int CONTROL_ID_BASE = 1000;

    private static final int CONTENT_TOP = 44;
    private static final int ROW_HEIGHT = 24;
    private static final int FOOTER_HEIGHT = 48;

    private final GuiScreen parent;
    private final List<SettingControl> controls = new ArrayList<SettingControl>();
    private final List<SettingControl> visibleControls =
            new ArrayList<SettingControl>();
    private final List<TextControl> visibleTextControls =
            new ArrayList<TextControl>();

    private RecordableConfig config;
    private Category category = Category.RECORDING;
    private int page;
    private int rowsPerPage = 6;
    private int pageCount = 1;
    private int navigationWidth;
    private int contentLeft;
    private int contentRight;

    private volatile List<AudioCaptureSession.AudioDevice> audioDevices =
            Collections.emptyList();
    private volatile boolean audioScanRunning;
    private volatile boolean audioScanAttempted;
    private volatile boolean ffmpegTaskRunning;
    private volatile boolean cleanupTaskRunning;
    private volatile String taskStatus = "";
    private volatile long taskStatusUntil;
    private volatile String audioStatus = "Audio devices have not been scanned.";
    private long lastSavedAt;

    public RecordableSettingsScreen() {
        this(null);
    }

    public RecordableSettingsScreen(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        config = RecordableConfig.get();
        Keyboard.enableRepeatEvents(true);
        if (!audioScanAttempted) {
            refreshAudioDevices();
        }
        rebuildPage();
    }

    private void rebuildPage() {
        buttonList.clear();
        controls.clear();
        visibleControls.clear();
        visibleTextControls.clear();

        navigationWidth = width < 500 ? 88 : 116;
        contentLeft = navigationWidth + 8;
        contentRight = Math.max(contentLeft + 80, width - 8);
        rowsPerPage = Math.max(
                3,
                (height - CONTENT_TOP - FOOTER_HEIGHT) / ROW_HEIGHT);

        addCategoryButtons();
        buildControls(category, controls);

        pageCount = Math.max(
                1,
                (controls.size() + rowsPerPage - 1) / rowsPerPage);
        page = clamp(page, 0, pageCount - 1);

        int first = page * rowsPerPage;
        int last = Math.min(controls.size(), first + rowsPerPage);
        int labelWidth = Math.max(
                68,
                Math.min(150, (contentRight - contentLeft) * 44 / 100));
        int valueX = contentLeft + labelWidth;
        int valueWidth = Math.max(42, contentRight - valueX);

        for (int index = first; index < last; index++) {
            SettingControl control = controls.get(index);
            int row = index - first;
            int y = CONTENT_TOP + row * ROW_HEIGHT;
            int id = CONTROL_ID_BASE + index;
            control.attach(
                    this,
                    id,
                    contentLeft,
                    y,
                    labelWidth,
                    valueX,
                    valueWidth);
            visibleControls.add(control);
            if (control instanceof TextControl) {
                visibleTextControls.add((TextControl) control);
            }
        }

        int footerY = Math.max(CONTENT_TOP + 2, height - 25);
        int compactWidth = width < 500 ? 46 : 62;
        int center = (contentLeft + contentRight) / 2;
        GuiButton previous = new GuiButton(
                PREVIOUS_PAGE_ID,
                center - compactWidth - 3,
                footerY,
                compactWidth,
                20,
                "< Prev");
        previous.enabled = page > 0;
        buttonList.add(previous);

        GuiButton next = new GuiButton(
                NEXT_PAGE_ID,
                center + 3,
                footerY,
                compactWidth,
                20,
                "Next >");
        next.enabled = page + 1 < pageCount;
        buttonList.add(next);

        buttonList.add(new GuiButton(
                DONE_ID,
                6,
                footerY,
                Math.max(54, navigationWidth - 12),
                20,
                parent == null ? "Done" : "Back"));
    }

    private void addCategoryButtons() {
        int buttonX = 6;
        int buttonWidth = Math.max(54, navigationWidth - 12);
        int buttonY = 42;
        int buttonHeight = height < 250 ? 18 : 20;
        int spacing = buttonHeight + 3;
        Category[] categories = Category.values();
        for (int index = 0; index < categories.length; index++) {
            Category candidate = categories[index];
            String label = candidate == category
                    ? "> " + candidate.shortName
                    : candidate.shortName;
            buttonList.add(new GuiButton(
                    CATEGORY_ID_BASE + index,
                    buttonX,
                    buttonY + index * spacing,
                    buttonWidth,
                    buttonHeight,
                    label));
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button == null || !button.enabled) {
            return;
        }

        if (button.id >= CATEGORY_ID_BASE
                && button.id < CATEGORY_ID_BASE + Category.values().length) {
            commitTextFields();
            category = Category.values()[button.id - CATEGORY_ID_BASE];
            page = 0;
            rebuildPage();
            return;
        }

        if (button.id == PREVIOUS_PAGE_ID) {
            commitTextFields();
            page = Math.max(0, page - 1);
            rebuildPage();
            return;
        }
        if (button.id == NEXT_PAGE_ID) {
            commitTextFields();
            page = Math.min(pageCount - 1, page + 1);
            rebuildPage();
            return;
        }
        if (button.id == DONE_ID) {
            closeToParent();
            return;
        }

        for (SettingControl control : visibleControls) {
            if (control.id == button.id) {
                control.activate();
                control.refresh();
                return;
            }
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();

        int accent = 0xFF000000 | config.getMenuAccentColorRgb();
        Gui.drawRect(3, 30, navigationWidth + 2, height - 31, 0x80000000);
        Gui.drawRect(contentLeft - 4, 30, contentRight + 4, height - 31,
                0x65000000);
        Gui.drawRect(contentLeft - 4, 30, contentRight + 4, 32, accent);

        drawCenteredString(
                fontRendererObj,
                "Record-able 1.8.9 Settings",
                width / 2,
                10,
                0xFFFFFFFF);
        String pageLabel = category.displayName + "  -  Page "
                + (page + 1) + "/" + pageCount;
        fontRendererObj.drawStringWithShadow(
                pageLabel,
                contentLeft,
                34,
                0xFFE0E0E0);

        String hoveredDescription = null;
        for (SettingControl control : visibleControls) {
            control.refresh();
            control.drawLabel(fontRendererObj, mouseX, mouseY);
            if (control.isHovered(mouseX, mouseY)) {
                hoveredDescription = control.description;
            }
        }

        super.drawScreen(mouseX, mouseY, partialTicks);
        for (TextControl control : visibleTextControls) {
            control.drawTextBox();
        }

        String footer = hoveredDescription;
        if (!isBlank(taskStatus)
                && System.currentTimeMillis() < taskStatusUntil) {
            footer = taskStatus;
        } else if (System.currentTimeMillis() - lastSavedAt < 1300L) {
            footer = "Settings saved.";
        }
        if (!isBlank(footer)) {
            int maximum = Math.max(30, width - navigationWidth - 20);
            String clipped = fontRendererObj.trimStringToWidth(footer, maximum);
            fontRendererObj.drawStringWithShadow(
                    clipped,
                    contentLeft,
                    height - 38,
                    0xFFB8C7D9);
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton)
            throws IOException {
        for (TextControl textControl : visibleTextControls) {
            boolean wasFocused = textControl.isFocused();
            textControl.mouseClicked(mouseX, mouseY, mouseButton);
            if (wasFocused && !textControl.isFocused()) {
                textControl.commit();
            }
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        for (TextControl textControl : visibleTextControls) {
            if (!textControl.isFocused()) {
                continue;
            }
            if (keyCode == Keyboard.KEY_RETURN
                    || keyCode == Keyboard.KEY_NUMPADENTER) {
                textControl.commit();
                textControl.setFocused(false);
            } else if (keyCode == Keyboard.KEY_ESCAPE) {
                textControl.reset();
                textControl.setFocused(false);
            } else {
                textControl.textboxKeyTyped(typedChar, keyCode);
            }
            return;
        }

        if (keyCode == Keyboard.KEY_ESCAPE) {
            closeToParent();
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        for (TextControl textControl : visibleTextControls) {
            textControl.updateCursorCounter();
        }
    }

    @Override
    public void onGuiClosed() {
        commitTextFields();
        safeSave();
        Keyboard.enableRepeatEvents(false);
        super.onGuiClosed();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private void closeToParent() {
        commitTextFields();
        safeSave();
        mc.displayGuiScreen(parent);
    }

    private void commitTextFields() {
        for (TextControl control : visibleTextControls) {
            control.commit();
        }
    }

    private void safeSave() {
        if (config == null) {
            return;
        }
        try {
            config.save();
            lastSavedAt = System.currentTimeMillis();
        } catch (RuntimeException exception) {
            setStatus("Could not save settings: " + safeMessage(exception));
        }
    }

    private void setStatus(String message) {
        taskStatus = message == null ? "" : message;
        taskStatusUntil = isBlank(taskStatus)
                ? 0L
                : System.currentTimeMillis() + 6000L;
    }

    private void buildControls(Category selected, List<SettingControl> target) {
        switch (selected) {
            case RECORDING:
                buildRecordingControls(target);
                break;
            case AUDIO:
                buildAudioControls(target);
                break;
            case OVERLAY:
                buildOverlayControls(target);
                break;
            case AUTOMATION:
                buildAutomationControls(target);
                break;
            case STORAGE:
                buildStorageControls(target);
                break;
            case PERFORMANCE:
                buildPerformanceControls(target);
                break;
            default:
                break;
        }
    }

    private void buildRecordingControls(List<SettingControl> target) {
        target.add(toggle(
                "Mod enabled",
                "Master switch for recording and automatic triggers.",
                () -> config.enabled,
                value -> config.enabled = value));
        target.add(cycle(
                "Device preset",
                "Applies the selected hardware preset immediately.",
                () -> pretty(config.selectedDevicePreset),
                () -> config.applyDevicePreset(next(
                        config.selectedDevicePreset,
                        RecordableConfig.DEVICE_PRESETS))));
        target.add(cycle(
                "Recording template",
                "Applies Cinematic, Balanced, PvP Clip, or Custom defaults.",
                () -> pretty(config.activeTemplate),
                () -> config.applyTemplate(next(
                        config.activeTemplate,
                        RecordableConfig.TEMPLATES))));
        target.add(cycle(
                "Container",
                "Video container written after recording finalization.",
                () -> config.format.toUpperCase(Locale.ROOT),
                () -> {
                    config.format = next(config.format, RecordableConfig.FORMATS);
                    config.validateAudioEncoderCompatibility();
                }));
        target.add(cycle(
                "Resolution",
                "Maximum output resolution; Native keeps the game framebuffer size.",
                () -> pretty(config.resolution),
                () -> config.resolution =
                        next(config.resolution, RecordableConfig.RESOLUTIONS)));
        target.add(cycle(
                "Frame rate",
                "Target recording frame rate.",
                () -> config.fps + " FPS",
                () -> config.fps = next(config.fps, RecordableConfig.FPS_VALUES)));
        target.add(cycle(
                "Quality",
                "Controls CRF and encoder preset defaults.",
                () -> pretty(config.quality),
                () -> config.quality =
                        next(config.quality, RecordableConfig.QUALITIES)));
        target.add(text(
                "Bitrate",
                "Use auto, a raw number, or values such as 8m and 12000k.",
                () -> config.bitrate,
                value -> config.bitrate = value,
                24));
        target.add(cycle(
                "Video encoder",
                "Hardware options require a matching FFmpeg encoder.",
                () -> config.encoder == null
                        ? "Software (x264)"
                        : config.encoder.displayName,
                () -> config.encoder = nextEnum(
                        config.encoder,
                        RecordableConfig.VideoEncoder.values())));
        target.add(slider(
                "Max file size",
                "Zero means unlimited; recording stops at the selected size.",
                () -> config.maxFileSizeMB,
                value -> config.maxFileSizeMB = value,
                0,
                4096,
                64,
                " MB"));
        target.add(toggle(
                "Stop on disconnect",
                "Stops and finalizes the recording when leaving a server.",
                () -> config.stopOnDisconnect,
                value -> config.stopOnDisconnect = value));
        target.add(toggle(
                "Bookmarks",
                "Allows timestamped bookmarks during a recording.",
                () -> config.bookmarksEnabled,
                value -> config.bookmarksEnabled = value));
        target.add(action(
                "Hotkeys",
                "Key capture UI is not part of this screen.",
                "Show note",
                () -> setStatus(
                        "Hotkey capture is not available here yet; edit recordable.json or use defaults.")));
    }

    private void buildAudioControls(List<SettingControl> target) {
        target.add(info(
                "Audio devices",
                "Background Java Sound device discovery status.",
                () -> audioStatus));
        target.add(action(
                "Refresh devices",
                "Rescans Java Sound mixers without freezing the UI.",
                "Scan now",
                this::refreshAudioDevices));
        target.add(toggle(
                "Capture game audio",
                "Records the selected loopback/game input when supported.",
                () -> config.captureAudio,
                value -> config.captureAudio = value));
        target.add(device(
                "Game audio device",
                "Auto prefers a loopback-capable capture mixer.",
                false));
        target.add(slider(
                "Game volume",
                "Gain applied to the game-audio track.",
                () -> config.gameAudioVolume,
                value -> config.gameAudioVolume = value,
                0,
                200,
                5,
                "%"));
        target.add(cycle(
                "Audio encoder",
                "Only encoders compatible with the selected container are retained.",
                () -> config.audioEncoder == null
                        ? "AAC"
                        : config.audioEncoder.displayName,
                () -> {
                    config.audioEncoder = nextEnum(
                            config.audioEncoder,
                            RecordableConfig.AudioEncoder.values());
                    config.validateAudioEncoderCompatibility();
                }));
        target.add(slider(
                "Audio bitrate",
                "Compressed audio bitrate; lossless FLAC ignores this value.",
                () -> config.audioBitrateKbps,
                value -> config.audioBitrateKbps = value,
                32,
                512,
                32,
                " kbps"));
        target.add(cycle(
                "Sample rate",
                "Audio capture and final output sample rate.",
                () -> config.audioSampleRate + " Hz",
                () -> config.audioSampleRate = next(
                        config.audioSampleRate,
                        RecordableConfig.AUDIO_SAMPLE_RATES)));
        target.add(cycle(
                "Channels",
                "Mono can reduce file size; stereo preserves spatial separation.",
                () -> config.audioChannelCount == 1 ? "Mono" : "Stereo",
                () -> config.audioChannelCount =
                        config.audioChannelCount == 1 ? 2 : 1));
        target.add(toggle(
                "Capture microphone",
                "Adds a microphone track to the recording.",
                () -> config.captureMicrophone,
                value -> config.captureMicrophone = value));
        target.add(device(
                "Microphone device",
                "Selects a Java Sound capture mixer for microphone input.",
                true));
        target.add(slider(
                "Microphone volume",
                "Gain applied to the microphone track.",
                () -> config.microphoneVolume,
                value -> config.microphoneVolume = value,
                0,
                200,
                5,
                "%"));
        target.add(toggle(
                "Push to talk",
                "Records microphone audio only while the PTT key is held.",
                () -> config.microphonePushToTalk,
                value -> config.microphonePushToTalk = value));
        target.add(toggle(
                "Noise suppression",
                "Enables the available microphone noise-reduction path.",
                () -> config.noiseSuppression,
                value -> config.noiseSuppression = value));
        target.add(cycle(
                "Audio delay",
                "Preset synchronization delay between video and audio.",
                () -> config.audioDelayPreset == null
                        ? "Auto"
                        : config.audioDelayPreset.displayName,
                () -> config.audioDelayPreset =
                        (config.audioDelayPreset == null
                                ? RecordableConfig.AudioDelayPreset.AUTO
                                : config.audioDelayPreset).next()));
        target.add(slider(
                "Custom delay",
                "Used when the Custom audio-delay preset is selected.",
                () -> config.audioSyncOffsetMs,
                value -> config.audioSyncOffsetMs = value,
                0,
                500,
                5,
                " ms"));
        target.add(toggle(
                "Separate tracks",
                "Keeps enabled audio sources as separate tracks when possible.",
                () -> config.separateAudioTracks,
                value -> config.separateAudioTracks = value));
        target.add(toggle(
                "Game track",
                "Includes game audio in separate-track recordings.",
                () -> config.trackGameAudio,
                value -> config.trackGameAudio = value));
        target.add(toggle(
                "Microphone track",
                "Includes microphone audio in separate-track recordings.",
                () -> config.trackMicAudio,
                value -> config.trackMicAudio = value));
        target.add(toggle(
                "Music track",
                "Includes the optional music source in separate-track recordings.",
                () -> config.trackMusicAudio,
                value -> config.trackMusicAudio = value));
    }

    private void buildOverlayControls(List<SettingControl> target) {
        target.add(toggle(
                "Show overlay",
                "Master visibility switch for the recording HUD.",
                () -> config.showOverlay,
                value -> config.showOverlay = value));
        target.add(cycle(
                "HUD style",
                "Classic, VHS, Synthwave, or None.",
                () -> config.overlayStyleHud == null
                        ? "Classic"
                        : config.overlayStyleHud.displayName,
                () -> config.overlayStyleHud =
                        (config.overlayStyleHud == null
                                ? RecordableConfig.OverlayStyleHud.CLASSIC
                                : config.overlayStyleHud).next()));
        target.add(cycle(
                "HUD position",
                "Default anchor used by Classic and Synthwave panels.",
                () -> config.overlayPosition == null
                        ? "Top-Left"
                        : config.overlayPosition.displayName,
                () -> config.overlayPosition =
                        (config.overlayPosition == null
                                ? RecordableConfig.OverlayPosition.TOP_LEFT
                                : config.overlayPosition).next()));
        target.add(slider(
                "HUD scale",
                "Scales recording overlay elements from 50% to 200%.",
                () -> config.overlayScale,
                value -> config.overlayScale = value,
                50,
                200,
                5,
                "%"));
        target.add(text(
                "Overlay color",
                "Hex accent color such as #FF3030.",
                () -> config.overlayColor,
                value -> config.overlayColor = value,
                9));
        target.add(toggle(
                "Recording timer",
                "Shows elapsed recording time.",
                () -> config.showRecordingTimer,
                value -> config.showRecordingTimer = value));
        target.add(toggle(
                "Estimated size",
                "Shows the current estimated output size.",
                () -> config.showEstimatedFileSize,
                value -> config.showEstimatedFileSize = value));
        target.add(toggle(
                "Completion toast",
                "Shows the post-recording saved-file notification.",
                () -> config.showPostRecordingToast,
                value -> config.showPostRecordingToast = value));
        target.add(toggle(
                "Performance stats",
                "Shows detailed encoder, queue, FPS, and memory statistics.",
                () -> config.showPerformanceStats,
                value -> config.showPerformanceStats = value));
        target.add(toggle(
                "Classic visible",
                "Per-element visibility for the Classic panel.",
                () -> config.hudClassicVisible,
                value -> config.hudClassicVisible = value));
        target.add(toggle(
                "Synthwave visible",
                "Per-element visibility for the Synthwave panel.",
                () -> config.hudSynthVisible,
                value -> config.hudSynthVisible = value));
        target.add(toggle(
                "Mic indicator",
                "Shows microphone/PTT state independently of HUD style.",
                () -> config.hudMicVisible,
                value -> config.hudMicVisible = value));
        target.add(slider(
                "Mic opacity",
                "Opacity of the microphone/PTT indicator.",
                () -> config.hudMicOpacity,
                value -> config.hudMicOpacity = value,
                0,
                100,
                5,
                "%"));
        target.add(toggle(
                "VHS PLAY/REC",
                "Shows the VHS transport and recording indicators.",
                () -> config.hudPlayRecVisible,
                value -> config.hudPlayRecVisible = value));
        target.add(slider(
                "PLAY/REC opacity",
                "Opacity of VHS PLAY and REC indicators.",
                () -> config.hudPlayRecOpacity,
                value -> config.hudPlayRecOpacity = value,
                0,
                100,
                5,
                "%"));
        target.add(toggle(
                "VHS timestamp",
                "Shows the VHS elapsed-time counter.",
                () -> config.hudTimestampVisible,
                value -> config.hudTimestampVisible = value));
        target.add(slider(
                "Timestamp opacity",
                "Opacity of the VHS timestamp.",
                () -> config.hudTimestampOpacity,
                value -> config.hudTimestampOpacity = value,
                0,
                100,
                5,
                "%"));
        target.add(toggle(
                "VHS brackets",
                "Shows the VHS viewfinder corner brackets.",
                () -> config.hudCornersVisible && config.vhsShowBrackets,
                value -> {
                    config.hudCornersVisible = value;
                    config.vhsShowBrackets = value;
                }));
        target.add(slider(
                "Bracket opacity",
                "Opacity of VHS viewfinder brackets.",
                () -> config.hudCornersOpacity,
                value -> config.hudCornersOpacity = value,
                0,
                100,
                5,
                "%"));
        target.add(toggle(
                "VHS SP",
                "Shows the retro tape-speed indicator.",
                () -> config.hudSpVisible && config.vhsShowSp,
                value -> {
                    config.hudSpVisible = value;
                    config.vhsShowSp = value;
                }));
        target.add(slider(
                "SP opacity",
                "Opacity of the VHS SP indicator.",
                () -> config.hudSpOpacity,
                value -> config.hudSpOpacity = value,
                0,
                100,
                5,
                "%"));
        target.add(toggle(
                "VHS details",
                "Master visibility for date, battery, meter, and tape counter.",
                () -> config.hudDetailsVisible,
                value -> config.hudDetailsVisible = value));
        target.add(slider(
                "Details opacity",
                "Opacity of the VHS details cluster.",
                () -> config.hudDetailsOpacity,
                value -> config.hudDetailsOpacity = value,
                0,
                100,
                5,
                "%"));
        target.add(toggle(
                "VHS date",
                "Shows the VHS date and clock.",
                () -> config.vhsShowDate,
                value -> config.vhsShowDate = value));
        target.add(toggle(
                "VHS battery",
                "Shows the simulated tape battery.",
                () -> config.vhsShowBattery,
                value -> config.vhsShowBattery = value));
        target.add(toggle(
                "VHS audio meter",
                "Shows the animated stereo level meter.",
                () -> config.vhsShowAudioMeter,
                value -> config.vhsShowAudioMeter = value));
        target.add(toggle(
                "VHS tape counter",
                "Shows the tape-style second counter.",
                () -> config.vhsShowTapeCounter,
                value -> config.vhsShowTapeCounter = value));
        target.add(toggle(
                "VHS performance",
                "Shows VHS queue and encoding statistics.",
                () -> config.hudPerfVisible,
                value -> config.hudPerfVisible = value));
        target.add(slider(
                "Performance opacity",
                "Opacity of VHS performance information.",
                () -> config.hudPerfOpacity,
                value -> config.hudPerfOpacity = value,
                0,
                100,
                5,
                "%"));
        target.add(action(
                "Advanced layout",
                "Drag, resize, reorder, and preview recording HUD layers.",
                "Open editor",
                () -> {
                    commitTextFields();
                    safeSave();
                    mc.displayGuiScreen(new OverlayPositionScreen(this));
                }));
        target.add(action(
                "Watermarks",
                "Edit up to four text or image watermark layers.",
                "Open editor",
                () -> {
                    commitTextFields();
                    safeSave();
                    mc.displayGuiScreen(new WatermarkScreen(this));
                }));
        target.add(toggle(
                "Streamer mode",
                "Master switch for privacy censor regions.",
                () -> config.streamerModeEnabled,
                value -> config.streamerModeEnabled = value));
        target.add(toggle(
                "Censor preview",
                "Shows censor regions while playing so their placement can be checked.",
                () -> config.streamerShowCensorPreview,
                value -> config.streamerShowCensorPreview = value));
        target.add(toggle(
                "Bake censors into video",
                "Permanently applies censor regions to recordings and replay clips.",
                () -> config.bakeInOverlay,
                value -> config.bakeInOverlay = value));
        target.add(action(
                "Censor regions",
                "Draw privacy regions; enable Streamer mode above to activate them.",
                "Open editor",
                () -> {
                    commitTextFields();
                    safeSave();
                    mc.displayGuiScreen(new CensorOverlayEditorScreen(this));
                }));
    }

    private void buildAutomationControls(List<SettingControl> target) {
        target.add(toggle(
                "Auto record",
                "Starts recording automatically for the selected trigger.",
                () -> config.autoRecord,
                value -> config.autoRecord = value));
        target.add(cycle(
                "Start trigger",
                "World join, game start, or manual-only.",
                () -> pretty(config.autoRecordTrigger),
                () -> config.autoRecordTrigger = next(
                        config.autoRecordTrigger,
                        RecordableConfig.AUTO_RECORD_TRIGGERS)));
        target.add(cycle(
                "Stop trigger",
                "World leave, game quit, or never.",
                () -> pretty(config.autoStopTrigger),
                () -> config.autoStopTrigger = next(
                        config.autoStopTrigger,
                        RecordableConfig.AUTO_STOP_TRIGGERS)));
        target.add(slider(
                "Start delay",
                "Delay after the automatic start trigger.",
                () -> config.autoRecordDelay,
                value -> config.autoRecordDelay = value,
                0,
                10,
                1,
                " s"));
        target.add(toggle(
                "Auto notifications",
                "Shows automatic recording start/stop notifications.",
                () -> config.notifyAutoRecord,
                value -> config.notifyAutoRecord = value));
        target.add(toggle(
                "Replay-mod bridge",
                "Enables defensive Replay Mod and Flashback coexistence behavior.",
                () -> config.replayCompatBridge,
                value -> config.replayCompatBridge = value));
        target.add(toggle(
                "Record replay playback",
                "Starts and stops Record-able with detected replay playback.",
                () -> config.replayAutoRecordPlayback,
                value -> config.replayAutoRecordPlayback = value));
        target.add(toggle(
                "Yield replay audio",
                "Lets a detected replay mod own OpenAL; Record-able falls back to another audio source.",
                () -> config.replayYieldAudioDevice,
                value -> config.replayYieldAudioDevice = value));
        target.add(toggle(
                "Replay buffer",
                "Continuously retains recent frames for instant replay clips.",
                () -> config.replayBufferEnabled,
                value -> config.replayBufferEnabled = value));
        target.add(slider(
                "Replay duration",
                "Seconds retained by the replay buffer.",
                () -> config.replayBufferDurationSeconds,
                value -> config.replayBufferDurationSeconds = value,
                10,
                600,
                10,
                " s"));
        target.add(cycle(
                "Replay quality",
                "Replay-buffer resolution and FPS preset.",
                () -> pretty(config.replayBufferQuality),
                () -> config.replayBufferQuality = next(
                        config.replayBufferQuality,
                        RecordableConfig.REPLAY_QUALITIES)));
        target.add(toggle(
                "Replay notifications",
                "Shows a notification after saving a replay.",
                () -> config.replayBufferNotify,
                value -> config.replayBufferNotify = value));
        target.add(toggle(
                "Markers",
                "Writes recording markers and bookmark sidecars.",
                () -> config.markersEnabled,
                value -> config.markersEnabled = value));
        target.add(toggle(
                "Marker on start",
                "Creates an initial chapter marker automatically.",
                () -> config.autoMarkerOnStart,
                value -> config.autoMarkerOnStart = value));
        target.add(toggle(
                "Chapter file",
                "Exports YouTube-style chapter text after recording.",
                () -> config.exportChapterFile,
                value -> config.exportChapterFile = value));
        target.add(toggle(
                "Embed chapters",
                "Embeds bookmark chapters through FFmpeg when finalizing.",
                () -> config.embedChaptersInVideo,
                value -> config.embedChaptersInVideo = value));
        target.add(toggle(
                "Auto clips",
                "Enables event-triggered clip generation.",
                () -> config.autoClipEnabled,
                value -> config.autoClipEnabled = value));
        target.add(toggle(
                "Achievement clips",
                "Creates clips around achievement events.",
                () -> config.autoClipOnAchievement,
                value -> config.autoClipOnAchievement = value));
        target.add(toggle(
                "Death clips",
                "Creates clips around player death.",
                () -> config.autoClipOnDeath,
                value -> config.autoClipOnDeath = value));
        target.add(toggle(
                "Dimension clips",
                "Creates clips on dimension changes.",
                () -> config.autoClipOnDimensionChange,
                value -> config.autoClipOnDimensionChange = value));
        target.add(toggle(
                "Boss-kill clips",
                "Creates clips when a boss is defeated.",
                () -> config.autoClipOnBossKill,
                value -> config.autoClipOnBossKill = value));
        target.add(toggle(
                "Kill clips",
                "Creates clips for qualifying kills.",
                () -> config.autoClipOnKill,
                value -> config.autoClipOnKill = value));
        target.add(toggle(
                "Player-kill clips",
                "Creates clips specifically for PvP eliminations.",
                () -> config.autoClipOnPlayerKill,
                value -> config.autoClipOnPlayerKill = value));
        target.add(slider(
                "Clip duration",
                "Duration of general automatic clips.",
                () -> config.autoClipDuration,
                value -> config.autoClipDuration = value,
                5,
                300,
                5,
                " s"));
        target.add(cycle(
                "Clip frame rate",
                "Frame rate used by automatic clips.",
                () -> config.autoClipFps + " FPS",
                () -> config.autoClipFps = next(
                        config.autoClipFps,
                        RecordableConfig.AUTO_CLIP_FPS_VALUES)));
        target.add(toggle(
                "Clip audio",
                "Includes audio in automatically generated clips.",
                () -> config.autoClipAudio,
                value -> config.autoClipAudio = value));
        target.add(toggle(
                "Kill montage",
                "Uses short pre/post windows for kill montage clips.",
                () -> config.autoClipKillMontage,
                value -> config.autoClipKillMontage = value));
        target.add(slider(
                "Kill pre-roll",
                "Seconds retained before a kill event.",
                () -> config.autoClipKillPreSeconds,
                value -> config.autoClipKillPreSeconds = value,
                0,
                10,
                1,
                " s"));
        target.add(slider(
                "Kill post-roll",
                "Seconds retained after a kill event.",
                () -> config.autoClipKillPostSeconds,
                value -> config.autoClipKillPostSeconds = value,
                0,
                10,
                1,
                " s"));
    }

    private void buildStorageControls(List<SettingControl> target) {
        target.add(text(
                "Output folder",
                "Absolute path or a path relative to the Minecraft directory.",
                () -> config.outputDir,
                value -> config.outputDir = value,
                256));
        target.add(action(
                "Open recordings",
                "Creates and opens the configured recordings folder.",
                "Open folder",
                this::openRecordings));
        target.add(cycle(
                "Gallery sort",
                "Default ordering for recordings.",
                () -> pretty(config.gallerySortMode),
                () -> config.gallerySortMode = next(
                        config.gallerySortMode,
                        RecordableConfig.GALLERY_SORT_MODES)));
        target.add(toggle(
                "Gallery metadata",
                "Shows duration, resolution, codec, and size when available.",
                () -> config.galleryShowMetadata,
                value -> config.galleryShowMetadata = value));
        target.add(slider(
                "Gallery columns",
                "Number of thumbnails per gallery row.",
                () -> config.galleryColumns,
                value -> config.galleryColumns = value,
                1,
                6,
                1,
                ""));
        target.add(slider(
                "Disk warning",
                "Warns when used disk space reaches this percentage.",
                () -> config.diskSpaceWarnPercent,
                value -> config.diskSpaceWarnPercent = value,
                50,
                99,
                1,
                "%"));
        target.add(slider(
                "Disk block",
                "Blocks new recording when disk usage reaches this percentage.",
                () -> config.diskSpaceBlockPercent,
                value -> config.diskSpaceBlockPercent = value,
                51,
                100,
                1,
                "%"));
        target.add(slider(
                "Minimum free",
                "Also warns when free space falls below this amount.",
                () -> config.diskSpaceMinFreeMB,
                value -> config.diskSpaceMinFreeMB = value,
                100,
                10000,
                100,
                " MB"));
        target.add(toggle(
                "Automatic cleanup",
                "Deletes old, unprotected recordings according to the rules below.",
                () -> config.autoCleanupEnabled,
                value -> config.autoCleanupEnabled = value));
        target.add(action(
                "Clean now",
                "Runs the configured age and storage-cap rules now. Protected recordings are kept.",
                "Run cleanup",
                this::runStorageCleanup));
        target.add(slider(
                "Cleanup age",
                "Unprotected recordings older than this can be deleted.",
                () -> config.autoCleanupOlderThanDays,
                value -> config.autoCleanupOlderThanDays = value,
                1,
                3650,
                1,
                " days"));
        target.add(slider(
                "Storage cap",
                "Zero disables size-based cleanup.",
                () -> config.autoCleanupMaxTotalMB,
                value -> config.autoCleanupMaxTotalMB = value,
                0,
                20480,
                256,
                " MB"));
        target.add(slider(
                "Compression CRF",
                "Lower values preserve more quality and create larger files.",
                () -> config.storageCompressionCrf,
                value -> config.storageCompressionCrf = value,
                0,
                51,
                1,
                ""));
        target.add(toggle(
                "Use FFmpeg",
                "Enables FFmpeg encoding and finalization when available.",
                () -> config.useFFmpegIfAvailable,
                value -> config.useFFmpegIfAvailable = value));
        target.add(toggle(
                "Bundled FFmpeg",
                "Allows Record-able to use its managed FFmpeg installation.",
                () -> config.useBundledFfmpeg,
                value -> config.useBundledFfmpeg = value));
        target.add(text(
                "Custom FFmpeg",
                "Executable path; leave blank for bundled FFmpeg or PATH detection.",
                () -> config.ffmpegPath,
                value -> {
                    config.ffmpegPath = value;
                    FfmpegBundleManager.invalidateCache();
                },
                256));
        target.add(info(
                "FFmpeg status",
                "Current detection or download state.",
                this::ffmpegStatusText));
        target.add(action(
                "Detect FFmpeg",
                "Checks custom, bundled, and PATH executables in the background.",
                "Detect",
                this::detectFfmpeg));
        target.add(action(
                "Download FFmpeg",
                "Downloads the platform bundle described by the status footer.",
                "Download",
                this::downloadFfmpeg));
        target.add(info(
                "Download source",
                "Provider selected for the current operating system.",
                FfmpegBundleManager::getDownloadSourceDescription));
        target.add(action(
                "Open FFmpeg folder",
                "Opens the managed FFmpeg bin folder.",
                "Open folder",
                this::openFfmpegFolder));
    }

    private void buildPerformanceControls(List<SettingControl> target) {
        target.add(action(
                "Capture diagnostics",
                "Run framebuffer, OpenGL, FFmpeg, disk, and environment checks.",
                "Open diagnostics",
                () -> {
                    commitTextFields();
                    safeSave();
                    mc.displayGuiScreen(new CaptureDiagnosticsScreen(this));
                }));
        target.add(cycle(
                "Device preset",
                "Applies a complete low-, mid-, high-end, or extreme preset.",
                () -> pretty(config.selectedDevicePreset),
                () -> config.applyDevicePreset(next(
                        config.selectedDevicePreset,
                        RecordableConfig.DEVICE_PRESETS))));
        target.add(toggle(
                "Optimizer",
                "Enables the recording performance optimizer.",
                () -> config.perfOptimizerEnabled,
                value -> config.perfOptimizerEnabled = value));
        target.add(toggle(
                "Automatic adjust",
                "Allows automatic quality reductions below the minimum game FPS.",
                () -> config.perfAutoAdjust,
                value -> config.perfAutoAdjust = value));
        target.add(slider(
                "Minimum game FPS",
                "Performance actions can trigger below this threshold.",
                () -> config.perfMinFps,
                value -> config.perfMinFps = value,
                10,
                240,
                5,
                " FPS"));
        target.add(toggle(
                "Prioritize game",
                "Favors game responsiveness over encoder throughput.",
                () -> config.perfModeGamePriority,
                value -> config.perfModeGamePriority = value));
        target.add(toggle(
                "Allow lower resolution",
                "Optimizer may lower recording resolution.",
                () -> config.perfActionLowerRes,
                value -> config.perfActionLowerRes = value));
        target.add(toggle(
                "Allow lower FPS",
                "Optimizer may lower recording frame rate.",
                () -> config.perfActionLowerFps,
                value -> config.perfActionLowerFps = value));
        target.add(toggle(
                "Allow faster preset",
                "Optimizer may choose a faster FFmpeg preset.",
                () -> config.perfActionFasterPreset,
                value -> config.perfActionFasterPreset = value));
        target.add(toggle(
                "Warn before adjust",
                "Shows a warning before automatic performance changes.",
                () -> config.perfWarnBeforeAdjust,
                value -> config.perfWarnBeforeAdjust = value));
        target.add(toggle(
                "Stats overlay",
                "Displays live optimizer and queue diagnostics.",
                () -> config.perfShowStatsOverlay,
                value -> config.perfShowStatsOverlay = value));
        target.add(toggle(
                "Frame buffer pooling",
                "Reuses capture buffers to reduce allocation and GC pressure.",
                () -> config.frameBufferPoolingEnabled,
                value -> config.frameBufferPoolingEnabled = value));
        target.add(toggle(
                "Smooth motion",
                "Adds the configured FFmpeg interpolation filter.",
                () -> config.smoothMotionEnabled,
                value -> config.smoothMotionEnabled = value));
        target.add(cycle(
                "Motion mode",
                "Blend is lighter; Duplicate avoids interpolation artifacts.",
                () -> pretty(config.smoothMotionMode),
                () -> config.smoothMotionMode = "blend".equals(config.smoothMotionMode)
                        ? "duplicate"
                        : "blend"));
        target.add(toggle(
                "Hide chat",
                "Hides chat from captured frames while recording.",
                () -> config.hideChat,
                value -> config.hideChat = value));
        target.add(toggle(
                "Hide crosshair",
                "Hides the crosshair from captured frames.",
                () -> config.hideCrosshair,
                value -> config.hideCrosshair = value));
        target.add(toggle(
                "Hide hotbar",
                "Hides the hotbar from captured frames.",
                () -> config.hideHotbar,
                value -> config.hideHotbar = value));
        target.add(toggle(
                "Hide boss bar",
                "Hides boss-health overlays from captured frames.",
                () -> config.hideBossBar,
                value -> config.hideBossBar = value));
        target.add(toggle(
                "Hide hand",
                "Hides the first-person hand from captured frames.",
                () -> config.hideHand,
                value -> config.hideHand = value));
        target.add(toggle(
                "Hide scoreboard",
                "Hides the sidebar scoreboard from captured frames.",
                () -> config.hideScoreboard,
                value -> config.hideScoreboard = value));
        target.add(toggle(
                "Hide vignette",
                "Hides the vanilla vignette from captured frames.",
                () -> config.hideVignette,
                value -> config.hideVignette = value));
    }

    private SettingControl toggle(
            String label,
            String description,
            BooleanSupplier getter,
            Consumer<Boolean> setter) {
        return new ToggleControl(label, description, getter, setter);
    }

    private SettingControl cycle(
            String label,
            String description,
            Supplier<String> display,
            Runnable cycler) {
        return new CycleControl(label, description, display, cycler);
    }

    private SettingControl slider(
            String label,
            String description,
            IntSupplier getter,
            IntConsumer setter,
            int minimum,
            int maximum,
            int step,
            String suffix) {
        return new SliderControl(
                label,
                description,
                getter,
                setter,
                minimum,
                maximum,
                step,
                suffix);
    }

    private SettingControl text(
            String label,
            String description,
            Supplier<String> getter,
            Consumer<String> setter,
            int maximumLength) {
        return new TextControl(
                label,
                description,
                getter,
                setter,
                maximumLength);
    }

    private SettingControl action(
            String label,
            String description,
            String buttonLabel,
            Runnable action) {
        return new ActionControl(
                label,
                description,
                buttonLabel,
                action);
    }

    private SettingControl info(
            String label,
            String description,
            Supplier<String> value) {
        return new InfoControl(label, description, value);
    }

    private SettingControl device(
            String label,
            String description,
            boolean microphone) {
        return new DeviceControl(label, description, microphone);
    }

    private void refreshAudioDevices() {
        if (audioScanRunning) {
            setStatus("Audio device scan is already running.");
            return;
        }
        audioScanAttempted = true;
        audioScanRunning = true;
        audioStatus = "Scanning Java Sound mixers...";
        setStatus(audioStatus);

        CompletableFuture
                .supplyAsync(AudioCaptureSession::listDevices)
                .whenComplete((devices, failure) -> runOnClientThread(() -> {
                    audioScanRunning = false;
                    if (failure != null) {
                        audioDevices = Collections.emptyList();
                        audioStatus = "Device scan failed: " + safeMessage(failure);
                    } else {
                        List<AudioCaptureSession.AudioDevice> safe =
                                devices == null
                                        ? Collections.emptyList()
                                        : new ArrayList<AudioCaptureSession.AudioDevice>(devices);
                        audioDevices = Collections.unmodifiableList(safe);
                        audioStatus = safe.isEmpty()
                                ? "No compatible Java Sound capture devices found."
                                : safe.size() + " capture device"
                                        + (safe.size() == 1 ? "" : "s") + " found.";
                    }
                    setStatus(audioStatus);
                }));
    }

    private void detectFfmpeg() {
        if (ffmpegTaskRunning || FfmpegBundleManager.isDownloading()) {
            setStatus("An FFmpeg task is already running.");
            return;
        }
        ffmpegTaskRunning = true;
        setStatus("Detecting FFmpeg...");
        CompletableFuture
                .supplyAsync(FfmpegBundleManager::detectFfmpeg)
                .whenComplete((result, failure) -> runOnClientThread(() -> {
                    ffmpegTaskRunning = false;
                    if (failure != null) {
                        setStatus("FFmpeg detection failed: " + safeMessage(failure));
                    } else if (result != null && result.isFound()) {
                        config.bundledFfmpegPath = result.getExecutable();
                        safeSave();
                        setStatus("FFmpeg found: " + result.getVersion());
                    } else {
                        String error = result == null
                                ? FfmpegBundleManager.getLastError()
                                : result.getError();
                        setStatus("FFmpeg not found"
                                + (isBlank(error) ? "." : ": " + error));
                    }
                }));
    }

    private void downloadFfmpeg() {
        if (ffmpegTaskRunning || FfmpegBundleManager.isDownloading()) {
            setStatus("An FFmpeg task is already running.");
            return;
        }
        if (!FfmpegBundleManager.isAutoDownloadSupported()) {
            setStatus("Automatic FFmpeg download is unsupported on "
                    + PlatformUtils.detectPlatform().getDisplayName() + ".");
            return;
        }

        ffmpegTaskRunning = true;
        setStatus("Starting FFmpeg download from "
                + FfmpegBundleManager.getDownloadSourceDescription() + "...");
        FfmpegBundleManager.downloadAsync()
                .whenComplete((success, failure) -> runOnClientThread(() -> {
                    ffmpegTaskRunning = false;
                    if (failure != null) {
                        setStatus("FFmpeg download failed: " + safeMessage(failure));
                    } else if (Boolean.TRUE.equals(success)) {
                        FfmpegBundleManager.FfmpegStatus detected =
                                FfmpegBundleManager.detectFfmpeg();
                        if (detected.isFound()) {
                            config.bundledFfmpegPath = detected.getExecutable();
                            config.useBundledFfmpeg = true;
                            safeSave();
                        }
                        setStatus("FFmpeg download complete.");
                    } else {
                        setStatus("FFmpeg download did not complete"
                                + (isBlank(FfmpegBundleManager.getLastError())
                                        ? "."
                                        : ": " + FfmpegBundleManager.getLastError()));
                    }
                }));
    }

    private String ffmpegStatusText() {
        FfmpegBundleManager.Status status = FfmpegBundleManager.getStatus();
        if (status == FfmpegBundleManager.Status.DOWNLOADING) {
            FfmpegBundleManager.DownloadProgress progress =
                    FfmpegBundleManager.getProgress();
            return "Downloading " + progress.getPhase() + " "
                    + progress.displayPercent();
        }
        if (status == FfmpegBundleManager.Status.AVAILABLE) {
            return isBlank(config.bundledFfmpegPath)
                    ? "Available"
                    : "Available: " + config.bundledFfmpegPath;
        }
        if (status == FfmpegBundleManager.Status.CHECKING) {
            return "Checking...";
        }
        if (status == FfmpegBundleManager.Status.ERROR) {
            return "Error: " + nullToEmpty(FfmpegBundleManager.getLastError());
        }
        return "Not detected yet";
    }

    private void openRecordings() {
        try {
            Files.createDirectories(config.getOutputDirectory());
            if (PlatformUtils.open(config.getOutputDirectory())) {
                setStatus("Opened " + config.getOutputDirectory());
            } else {
                setStatus("Could not open the recordings folder.");
            }
        } catch (Exception exception) {
            setStatus("Could not open recordings: " + safeMessage(exception));
        }
    }

    private void runStorageCleanup() {
        if (cleanupTaskRunning) {
            setStatus("Storage cleanup is already running.");
            return;
        }
        cleanupTaskRunning = true;
        setStatus("Cleaning unprotected recordings...");
        CompletableFuture
                .supplyAsync(() -> StorageManager.runCleanup(config, true))
                .whenComplete((result, failure) -> runOnClientThread(() -> {
                    cleanupTaskRunning = false;
                    if (failure != null) {
                        setStatus("Storage cleanup failed: " + safeMessage(failure));
                    } else if (result == null || result.getFilesDeleted() == 0) {
                        setStatus("Storage cleanup complete; no recordings matched.");
                    } else {
                        setStatus("Storage cleanup removed "
                                + result.getFilesDeleted() + " recording"
                                + (result.getFilesDeleted() == 1 ? "" : "s")
                                + " and freed " + result.bytesFreedDisplay() + ".");
                    }
                }));
    }

    private void openFfmpegFolder() {
        try {
            Files.createDirectories(FfmpegBundleManager.getBundleDirectory());
            if (PlatformUtils.open(FfmpegBundleManager.getBundleDirectory())) {
                setStatus("Opened the managed FFmpeg folder.");
            } else {
                setStatus("Could not open the managed FFmpeg folder.");
            }
        } catch (Exception exception) {
            setStatus("Could not open FFmpeg folder: " + safeMessage(exception));
        }
    }

    private void runOnClientThread(Runnable runnable) {
        Minecraft client = mc == null ? Minecraft.getMinecraft() : mc;
        if (client == null) {
            runnable.run();
            return;
        }
        client.addScheduledTask(runnable);
    }

    private List<AudioCaptureSession.AudioDevice> deviceChoices(
            boolean microphone) {
        List<AudioCaptureSession.AudioDevice> preferred =
                new ArrayList<AudioCaptureSession.AudioDevice>();
        List<AudioCaptureSession.AudioDevice> fallback =
                new ArrayList<AudioCaptureSession.AudioDevice>();
        for (AudioCaptureSession.AudioDevice device : audioDevices) {
            if (device == null) {
                continue;
            }
            fallback.add(device);
            if (microphone != device.isLoopbackCandidate()) {
                preferred.add(device);
            }
        }
        return preferred.isEmpty() ? fallback : preferred;
    }

    private String deviceDisplay(boolean microphone) {
        String selected = microphone
                ? config.microphoneDevice
                : config.audioDevice;
        if (isBlank(selected) || "auto".equalsIgnoreCase(selected)) {
            return "Auto";
        }
        for (AudioCaptureSession.AudioDevice device : audioDevices) {
            if (selected.equals(device.getId())) {
                return device.getDisplayName();
            }
        }
        return selected;
    }

    private void cycleDevice(boolean microphone) {
        List<AudioCaptureSession.AudioDevice> available =
                deviceChoices(microphone);
        String current = microphone
                ? config.microphoneDevice
                : config.audioDevice;
        List<String> ids = new ArrayList<String>();
        ids.add("auto");
        for (AudioCaptureSession.AudioDevice device : available) {
            ids.add(device.getId());
        }
        int index = ids.indexOf(current);
        String selected = ids.get((index + 1 + ids.size()) % ids.size());
        if (microphone) {
            config.microphoneDevice = selected;
        } else {
            config.audioDevice = selected;
        }
    }

    private static String next(String current, String[] values) {
        if (values == null || values.length == 0) {
            return current;
        }
        int index = -1;
        for (int candidate = 0; candidate < values.length; candidate++) {
            if (values[candidate].equalsIgnoreCase(nullToEmpty(current))) {
                index = candidate;
                break;
            }
        }
        return values[(index + 1) % values.length];
    }

    private static int next(int current, int[] values) {
        if (values == null || values.length == 0) {
            return current;
        }
        int index = -1;
        for (int candidate = 0; candidate < values.length; candidate++) {
            if (values[candidate] == current) {
                index = candidate;
                break;
            }
        }
        return values[(index + 1) % values.length];
    }

    private static <T> T nextEnum(T current, T[] values) {
        if (values == null || values.length == 0) {
            return current;
        }
        int index = Arrays.asList(values).indexOf(current);
        return values[(index + 1) % values.length];
    }

    private static String pretty(String value) {
        if (isBlank(value)) {
            return "Auto";
        }
        String normalized = value.trim().replace('_', ' ');
        StringBuilder result = new StringBuilder(normalized.length());
        boolean capitalize = true;
        for (int index = 0; index < normalized.length(); index++) {
            char character = normalized.charAt(index);
            if (capitalize && Character.isLetter(character)) {
                result.append(Character.toUpperCase(character));
                capitalize = false;
            } else {
                result.append(character);
            }
            if (character == ' ') {
                capitalize = true;
            }
        }
        return result.toString();
    }

    private static String safeMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return isBlank(message)
                ? current.getClass().getSimpleName()
                : message;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private enum Category {
        RECORDING("Recording", "Recording"),
        AUDIO("Audio", "Audio"),
        OVERLAY("Overlay", "Overlay"),
        AUTOMATION("Automation / Clips", "Auto / Clips"),
        STORAGE("Storage", "Storage"),
        PERFORMANCE("Performance", "Performance");

        final String displayName;
        final String shortName;

        Category(String displayName, String shortName) {
            this.displayName = displayName;
            this.shortName = shortName;
        }
    }

    private abstract static class SettingControl {
        final String label;
        final String description;
        RecordableSettingsScreen owner;
        int id = -1;
        int labelX;
        int y;
        int labelWidth;
        int rowRight;

        SettingControl(String label, String description) {
            this.label = label;
            this.description = description;
        }

        final void attach(
                RecordableSettingsScreen screen,
                int buttonId,
                int left,
                int top,
                int availableLabelWidth,
                int valueX,
                int valueWidth) {
            owner = screen;
            id = buttonId;
            labelX = left;
            y = top;
            labelWidth = availableLabelWidth;
            rowRight = valueX + valueWidth;
            onAttach(screen, buttonId, valueX, top, valueWidth);
        }

        abstract void onAttach(
                RecordableSettingsScreen screen,
                int buttonId,
                int valueX,
                int top,
                int valueWidth);

        abstract void activate();

        void refresh() {
        }

        void drawLabel(FontRenderer font, int mouseX, int mouseY) {
            String visible = font.trimStringToWidth(
                    label,
                    Math.max(8, labelWidth - 4));
            font.drawStringWithShadow(visible, labelX, y + 6, 0xFFD8D8D8);
        }

        boolean isHovered(int mouseX, int mouseY) {
            return mouseX >= labelX
                    && mouseX < rowRight
                    && mouseY >= y
                    && mouseY < y + 20;
        }
    }

    private static final class ToggleControl extends SettingControl {
        private final BooleanSupplier getter;
        private final Consumer<Boolean> setter;
        private GuiButton button;

        ToggleControl(
                String label,
                String description,
                BooleanSupplier getter,
                Consumer<Boolean> setter) {
            super(label, description);
            this.getter = getter;
            this.setter = setter;
        }

        @Override
        void onAttach(
                RecordableSettingsScreen screen,
                int buttonId,
                int valueX,
                int top,
                int valueWidth) {
            button = new GuiButton(
                    buttonId,
                    valueX,
                    top,
                    valueWidth,
                    20,
                    display());
            screen.buttonList.add(button);
        }

        @Override
        void activate() {
            setter.accept(Boolean.valueOf(!getter.getAsBoolean()));
            owner.safeSave();
        }

        @Override
        void refresh() {
            if (button != null) {
                button.displayString = display();
            }
        }

        private String display() {
            return getter.getAsBoolean() ? "ON" : "OFF";
        }
    }

    private static final class CycleControl extends SettingControl {
        private final Supplier<String> display;
        private final Runnable cycler;
        private GuiButton button;

        CycleControl(
                String label,
                String description,
                Supplier<String> display,
                Runnable cycler) {
            super(label, description);
            this.display = display;
            this.cycler = cycler;
        }

        @Override
        void onAttach(
                RecordableSettingsScreen screen,
                int buttonId,
                int valueX,
                int top,
                int valueWidth) {
            button = new GuiButton(
                    buttonId,
                    valueX,
                    top,
                    valueWidth,
                    20,
                    clippedDisplay(valueWidth));
            screen.buttonList.add(button);
        }

        @Override
        void activate() {
            cycler.run();
            owner.safeSave();
        }

        @Override
        void refresh() {
            if (button != null) {
                button.displayString = clippedDisplay(button.width);
            }
        }

        private String clippedDisplay(int width) {
            String value = nullToEmpty(display.get());
            return owner == null
                    ? value
                    : owner.fontRendererObj.trimStringToWidth(
                            value,
                            Math.max(8, width - 8));
        }
    }

    private static final class ActionControl extends SettingControl {
        private final String buttonLabel;
        private final Runnable action;
        private GuiButton button;

        ActionControl(
                String label,
                String description,
                String buttonLabel,
                Runnable action) {
            super(label, description);
            this.buttonLabel = buttonLabel;
            this.action = action;
        }

        @Override
        void onAttach(
                RecordableSettingsScreen screen,
                int buttonId,
                int valueX,
                int top,
                int valueWidth) {
            button = new GuiButton(
                    buttonId,
                    valueX,
                    top,
                    valueWidth,
                    20,
                    screen.fontRendererObj.trimStringToWidth(
                            buttonLabel,
                            Math.max(8, valueWidth - 8)));
            screen.buttonList.add(button);
        }

        @Override
        void activate() {
            action.run();
        }
    }

    private static final class InfoControl extends SettingControl {
        private final Supplier<String> value;
        private int valueX;
        private int valueWidth;

        InfoControl(
                String label,
                String description,
                Supplier<String> value) {
            super(label, description);
            this.value = value;
        }

        @Override
        void onAttach(
                RecordableSettingsScreen screen,
                int buttonId,
                int valueX,
                int top,
                int valueWidth) {
            this.valueX = valueX;
            this.valueWidth = valueWidth;
        }

        @Override
        void activate() {
        }

        @Override
        void drawLabel(FontRenderer font, int mouseX, int mouseY) {
            super.drawLabel(font, mouseX, mouseY);
            String text;
            try {
                text = nullToEmpty(value.get());
            } catch (RuntimeException exception) {
                text = "Unavailable";
            }
            font.drawStringWithShadow(
                    font.trimStringToWidth(text, Math.max(8, valueWidth - 4)),
                    valueX + 2,
                    y + 6,
                    0xFFAFC4D8);
        }
    }

    private static final class TextControl extends SettingControl {
        private final Supplier<String> getter;
        private final Consumer<String> setter;
        private final int maximumLength;
        private GuiTextField textField;
        private String lastCommitted = "";

        TextControl(
                String label,
                String description,
                Supplier<String> getter,
                Consumer<String> setter,
                int maximumLength) {
            super(label, description);
            this.getter = getter;
            this.setter = setter;
            this.maximumLength = maximumLength;
        }

        @Override
        void onAttach(
                RecordableSettingsScreen screen,
                int buttonId,
                int valueX,
                int top,
                int valueWidth) {
            textField = new GuiTextField(
                    buttonId,
                    screen.fontRendererObj,
                    valueX,
                    top,
                    valueWidth,
                    20);
            textField.setMaxStringLength(maximumLength);
            reset();
        }

        @Override
        void activate() {
        }

        void drawTextBox() {
            if (textField != null) {
                textField.drawTextBox();
            }
        }

        void mouseClicked(int mouseX, int mouseY, int mouseButton) {
            if (textField != null) {
                textField.mouseClicked(mouseX, mouseY, mouseButton);
            }
        }

        void textboxKeyTyped(char typedChar, int keyCode) {
            if (textField != null) {
                textField.textboxKeyTyped(typedChar, keyCode);
            }
        }

        void updateCursorCounter() {
            if (textField != null) {
                textField.updateCursorCounter();
            }
        }

        boolean isFocused() {
            return textField != null && textField.isFocused();
        }

        void setFocused(boolean focused) {
            if (textField != null) {
                textField.setFocused(focused);
            }
        }

        void commit() {
            if (textField == null) {
                return;
            }
            String value = textField.getText();
            if (value.equals(lastCommitted)) {
                return;
            }
            setter.accept(value.trim());
            owner.safeSave();
            reset();
        }

        void reset() {
            if (textField == null) {
                return;
            }
            lastCommitted = nullToEmpty(getter.get());
            textField.setText(lastCommitted);
        }
    }

    private static final class SliderControl extends SettingControl {
        private final IntSupplier getter;
        private final IntConsumer setter;
        private final int minimum;
        private final int maximum;
        private final int step;
        private final String suffix;
        private SliderButton button;

        SliderControl(
                String label,
                String description,
                IntSupplier getter,
                IntConsumer setter,
                int minimum,
                int maximum,
                int step,
                String suffix) {
            super(label, description);
            this.getter = getter;
            this.setter = setter;
            this.minimum = minimum;
            this.maximum = maximum;
            this.step = Math.max(1, step);
            this.suffix = suffix;
        }

        @Override
        void onAttach(
                RecordableSettingsScreen screen,
                int buttonId,
                int valueX,
                int top,
                int valueWidth) {
            button = new SliderButton(
                    buttonId,
                    valueX,
                    top,
                    valueWidth,
                    20,
                    this);
            screen.buttonList.add(button);
        }

        @Override
        void activate() {
            // Slider values are updated by mousePressed/mouseDragged and saved
            // once on release to avoid rewriting the JSON file every frame.
        }

        @Override
        void refresh() {
            if (button != null) {
                button.refreshLabel();
            }
        }

        int value() {
            return clamp(getter.getAsInt(), minimum, maximum);
        }

        void setFromMouse(int mouseX) {
            if (button == null || maximum <= minimum) {
                return;
            }
            double fraction = (mouseX - button.xPosition)
                    / (double) Math.max(1, button.width);
            fraction = Math.max(0.0D, Math.min(1.0D, fraction));
            double raw = minimum + fraction * (maximum - minimum);
            int snapped = minimum
                    + (int) Math.round((raw - minimum) / step) * step;
            setter.accept(clamp(snapped, minimum, maximum));
            button.refreshLabel();
        }

        void save() {
            owner.safeSave();
        }

        String display() {
            return value() + suffix;
        }

        double fraction() {
            return maximum <= minimum
                    ? 0.0D
                    : (value() - minimum) / (double) (maximum - minimum);
        }
    }

    private static final class SliderButton extends GuiButton {
        private final SliderControl control;
        private boolean dragging;

        SliderButton(
                int buttonId,
                int x,
                int y,
                int width,
                int height,
                SliderControl control) {
            super(buttonId, x, y, width, height, "");
            this.control = control;
            refreshLabel();
        }

        @Override
        public boolean mousePressed(
                Minecraft minecraft,
                int mouseX,
                int mouseY) {
            boolean pressed = super.mousePressed(minecraft, mouseX, mouseY);
            if (pressed) {
                dragging = true;
                control.setFromMouse(mouseX);
            }
            return pressed;
        }

        @Override
        protected void mouseDragged(
                Minecraft minecraft,
                int mouseX,
                int mouseY) {
            if (dragging) {
                control.setFromMouse(mouseX);
            }
        }

        @Override
        public void mouseReleased(int mouseX, int mouseY) {
            if (dragging) {
                control.setFromMouse(mouseX);
                dragging = false;
                control.save();
            }
            super.mouseReleased(mouseX, mouseY);
        }

        @Override
        public void drawButton(
                Minecraft minecraft,
                int mouseX,
                int mouseY) {
            refreshLabel();
            super.drawButton(minecraft, mouseX, mouseY);
            if (!visible) {
                return;
            }
            int trackLeft = xPosition + 4;
            int trackRight = xPosition + width - 4;
            int trackY = yPosition + height - 4;
            Gui.drawRect(trackLeft, trackY, trackRight, trackY + 1, 0xFF555555);
            int knob = trackLeft
                    + (int) Math.round(
                            control.fraction() * (trackRight - trackLeft));
            Gui.drawRect(knob - 1, trackY - 2, knob + 2, trackY + 3,
                    0xFFFFFFFF);
        }

        void refreshLabel() {
            displayString = control.display();
        }
    }

    private final class DeviceControl extends SettingControl {
        private final boolean microphone;
        private GuiButton button;

        DeviceControl(
                String label,
                String description,
                boolean microphone) {
            super(label, description);
            this.microphone = microphone;
        }

        @Override
        void onAttach(
                RecordableSettingsScreen screen,
                int buttonId,
                int valueX,
                int top,
                int valueWidth) {
            button = new GuiButton(
                    buttonId,
                    valueX,
                    top,
                    valueWidth,
                    20,
                    display(valueWidth));
            screen.buttonList.add(button);
        }

        @Override
        void activate() {
            cycleDevice(microphone);
            safeSave();
        }

        @Override
        void refresh() {
            if (button != null) {
                button.displayString = display(button.width);
            }
        }

        private String display(int width) {
            return fontRendererObj.trimStringToWidth(
                    deviceDisplay(microphone),
                    Math.max(8, width - 8));
        }
    }
}
