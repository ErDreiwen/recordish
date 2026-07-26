package dev.recordable.screen;

import dev.recordable.PlatformUtils;
import dev.recordable.RecordableConfig;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * V1-0.09 audio-capture help, adapted to the Forge 1.8.9 GUI API.
 */
public final class AudioHelpScreen extends GuiScreen {
    private static final int PANEL_COLOR = 0xD0101010;
    private static final int PANEL_BORDER_COLOR = 0xFF424242;
    private static final int HEADER_COLOR = 0xFFFFFFFF;
    private static final int TEXT_COLOR = 0xFFD0D0D0;
    private static final int HIGHLIGHT_COLOR = 0xFF88CC88;
    private static final int WARNING_COLOR = 0xFFFFCC44;

    private final GuiScreen parent;
    private final List<HelpLine> helpLines = new ArrayList<HelpLine>();

    private int panelLeft;
    private int panelTop;
    private int panelWidth;
    private int panelBottom;
    private int scrollOffset;
    private int contentHeight;
    private int bodyTop;
    private int bodyBottom;

    public AudioHelpScreen(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        buttonList.clear();
        helpLines.clear();
        scrollOffset = 0;

        panelWidth = Math.max(340, Math.min((int) (width * 0.80D), 600));
        panelLeft = (width - panelWidth) / 2;
        panelTop = Math.max(8, (int) (height * 0.05D));
        panelBottom = Math.min(
                height - 8,
                panelTop + Math.max(300, (int) (height * 0.88D)));
        bodyTop = panelTop + 24;
        bodyBottom = panelBottom - 34;

        addHeader("Audio Capture - How It Works");
        addBlank();
        addHighlight("OpenAL Loopback Capture (Default)");
        addText("Record-able captures audio DIRECTLY from Minecraft's");
        addText("audio engine using OpenAL's ALC_SOFT_loopback extension.");
        addBlank();
        addText("What this means for you:");
        addText("  No Stereo Mix setup needed");
        addText("  No virtual cables or BlackHole required");
        addText("  No system-wide audio routing tricks");
        addText("  Works the same way on every platform");
        addBlank();
        addText("All game sounds, music, ambient effects, and mod");
        addText("sounds are captured at full digital quality:");
        addText("  48 kHz, Stereo, 16-bit PCM");
        addBlank();
        addHighlight("Why This Is Better");
        addText("Older recorders relied on Stereo Mix or BlackHole to");
        addText("pull audio from the speakers. That approach is fragile,");
        addText("noisy, and tied to OS volume sliders.");
        addBlank();
        addText("Loopback capture taps into the audio mixer BEFORE the");
        addText("sound leaves Minecraft. The result: clean audio at");
        addText("100% volume, regardless of how loud you set Windows or");
        addText("your phone.");
        addBlank();

        buildPlatformHelp();
        addBlank();

        addHeader("General Troubleshooting");
        addText("1. Verify 'Capture Audio' is ON in settings");
        addText("2. Make sure Minecraft sound is NOT muted in");
        addText("   Options > Music & Sounds");
        addText("3. Click 'Test Audio' to verify the recorder is");
        addText("   receiving samples");
        addText("4. Restart Minecraft once if you toggled audio mods");
        addText("5. If you hear no audio in the recording, check that");
        addText("   the master volume slider in-game is above 0%");
        addText("6. Check latest.log for any audio warnings");
        addBlank();

        addHeader("Volume Slider");
        addText("Use the Audio Volume slider (0 to 200%) to adjust the");
        addText("recorded audio level:");
        addText("  100% = normal volume (default)");
        addText("  150% = boost quiet audio by 1.5x");
        addText("  50%  = reduce loud audio by half");
        addText("  0%   = muted (no audio in recording)");
        addBlank();
        addText("This slider only changes the RECORDED audio. It does");
        addText("not affect what you hear while playing.");
        addBlank();

        addHeader("Mono vs Stereo");
        addText("Stereo (default) is recommended for music and most");
        addText("gameplay. Switch to Mono if you only need voice or");
        addText("if your output device is mono.");

        contentHeight = helpLines.size() * 12 + 10;
        buttonList.add(new GuiButton(
                1,
                (width - 120) / 2,
                panelBottom - 28,
                120,
                20,
                "Back"));
    }

    private void buildPlatformHelp() {
        if (isAndroidRuntime()) {
            buildAndroidHelp();
            return;
        }
        PlatformUtils.Platform platform = PlatformUtils.detectPlatform();
        if (platform == PlatformUtils.Platform.WINDOWS) {
            buildWindowsHelp();
        } else if (platform == PlatformUtils.Platform.LINUX) {
            buildLinuxHelp();
        } else if (platform == PlatformUtils.Platform.MACOS) {
            buildMacOSHelp();
        } else {
            addWarning("Unsupported platform for audio: "
                    + platform.getDisplayName());
        }
    }

    private void buildWindowsHelp() {
        addHeader("Windows");
        addBlank();
        addHighlight("Nothing to set up");
        addText("OpenAL loopback works out of the box on Windows.");
        addText("You do NOT need to enable Stereo Mix, install");
        addText("VB-Cable, or change any audio routing.");
        addBlank();
        addText("If audio still does not record:");
        addText("1. Make sure 'Capture Audio' is ON in settings");
        addText("2. Confirm Minecraft is producing sound");
        addText("   (check the Music & Sounds menu)");
        addText("3. Click 'Test Audio' in the settings screen");
    }

    private void buildLinuxHelp() {
        addHeader("Linux");
        addBlank();
        addHighlight("Nothing to set up");
        addText("OpenAL loopback works on PulseAudio, PipeWire, and");
        addText("ALSA without any extra configuration.");
        addBlank();
        addText("If audio still does not record:");
        addText("1. Make sure 'Capture Audio' is ON in settings");
        addText("2. Confirm Minecraft is producing sound");
        addText("3. Click 'Test Audio' in the settings screen");
        addText("4. If using PipeWire, make sure the OpenAL backend");
        addText("   is not forced to a specific device");
    }

    private void buildMacOSHelp() {
        addHeader("macOS");
        addBlank();
        addHighlight("Nothing to set up");
        addText("OpenAL loopback works out of the box on macOS.");
        addText("BlackHole and Multi-Output Devices are NOT required.");
        addBlank();
        addText("If audio still does not record:");
        addText("1. Make sure 'Capture Audio' is ON in settings");
        addText("2. Confirm Minecraft is producing sound");
        addText("3. Click 'Test Audio' in the settings screen");
    }

    private void buildAndroidHelp() {
        addHeader("Android (PojavLauncher / Zalith / FCL)");
        addBlank();
        addHighlight("Nothing to set up");
        addText("OpenAL loopback is automatic on Android launchers.");
        addText("You do NOT need root, special permissions, or any");
        addText("extra audio plugin. Just keep 'Capture Audio' ON.");
        addBlank();
        addText("If audio still does not record:");
        addText("1. Make sure 'Capture Audio' is ON in settings");
        addText("2. Confirm Minecraft is producing sound");
        addText("   (turn up the master volume slider)");
        addText("3. Click 'Test Audio' to verify the recorder");
        addText("   is receiving samples");
        addBlank();
        addWarning("Tip: Some launchers need OpenAL Soft enabled in");
        addWarning("their launcher settings. If audio is silent, look");
        addWarning("for an OpenAL or Sound option in your launcher.");
    }

    private static boolean isAndroidRuntime() {
        String runtime = (
                System.getProperty("java.runtime.name", "") + " "
                        + System.getProperty("java.vm.name", "") + " "
                        + System.getProperty("java.home", ""))
                .toLowerCase(Locale.ROOT);
        if (runtime.contains("android") || runtime.contains("dalvik")) {
            return true;
        }
        return System.getenv("POJAV_RENDERER") != null
                || System.getenv("POJAV_NATIVEDIR") != null;
    }

    private void addHeader(String text) {
        helpLines.add(new HelpLine(text, HEADER_COLOR, true));
    }

    private void addText(String text) {
        helpLines.add(new HelpLine(text, TEXT_COLOR, false));
    }

    private void addHighlight(String text) {
        helpLines.add(new HelpLine(text, HIGHLIGHT_COLOR, false));
    }

    private void addWarning(String text) {
        helpLines.add(new HelpLine(text, WARNING_COLOR, false));
    }

    private void addBlank() {
        helpLines.add(new HelpLine("", TEXT_COLOR, false));
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button != null && button.enabled && button.id == 1) {
            closeToParent();
        }
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel == 0) {
            return;
        }
        int maxScroll = Math.max(
                0,
                contentHeight - (bodyBottom - bodyTop));
        if (maxScroll <= 0) {
            return;
        }
        scrollOffset = clamp(
                scrollOffset + (wheel > 0 ? -20 : 20),
                0,
                maxScroll);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        dev.recordable.theme.ThemedPanel.drawMenuBackdrop(width, height);

        int accent = 0xFF000000
                | RecordableConfig.get().getMenuAccentColorRgb();
        int left = panelLeft - 6;
        int right = panelLeft + panelWidth + 6;
        Gui.drawRect(left, panelTop - 6, right, panelBottom, PANEL_COLOR);
        Gui.drawRect(left, panelTop - 6, right, panelTop - 5, accent);
        Gui.drawRect(
                left,
                panelBottom - 1,
                right,
                panelBottom,
                PANEL_BORDER_COLOR);
        Gui.drawRect(
                left,
                panelTop - 6,
                left + 1,
                panelBottom,
                PANEL_BORDER_COLOR);
        Gui.drawRect(
                right - 1,
                panelTop - 6,
                right,
                panelBottom,
                PANEL_BORDER_COLOR);

        drawCenteredString(
                fontRendererObj,
                "Audio Capture Help",
                width / 2,
                panelTop,
                0xFFFFFFFF);

        int textLeft = panelLeft + 14;
        for (int index = 0; index < helpLines.size(); index++) {
            int y = bodyTop + index * 12 - scrollOffset;
            if (y < bodyTop - 12 || y > bodyBottom + 2) {
                continue;
            }
            HelpLine line = helpLines.get(index);
            if (line.text.isEmpty()) {
                continue;
            }
            fontRendererObj.drawStringWithShadow(
                    line.bold ? "\u00A7l" + line.text : line.text,
                    textLeft,
                    y,
                    line.color);
        }

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode)
            throws IOException {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            closeToParent();
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    private void closeToParent() {
        mc.displayGuiScreen(parent);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static final class HelpLine {
        private final String text;
        private final int color;
        private final boolean bold;

        private HelpLine(String text, int color, boolean bold) {
            this.text = text;
            this.color = color;
            this.bold = bold;
        }
    }
}
