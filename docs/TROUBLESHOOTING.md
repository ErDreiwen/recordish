# Troubleshooting

## The mod does not appear

- Confirm the instance is Minecraft `1.8.9` with Forge `11.15.1.2318`.
- Remove duplicate or older Record-able JARs from the profile.
- Confirm the JAR is in this instance's game-folder `mods` directory.
- Run the game with Java 8.
- In Lunar Launcher, use **Vanilla/Forge**. The branded Lunar/Ichor runtime
  filters arbitrary external Forge JARs before Record-able can start.

On a successful load, `logs\latest.log` contains:

```text
Record-able Forge 1.8.9 initialized.
```

## FFmpeg is missing or the download fails

Open settings with `F9`, search for `FFmpeg`, and retry from the FFmpeg setup
screen. Check that the instance folder is writable and that the firewall or
security software allows the displayed download host.

For a manual install, copy the full path to `ffmpeg.exe`, use
**Paste FFmpeg Path**, and then **Test FFmpeg**. The test report lists every
path Record-able tried.

## Recording will not start

- Join a world or server; recording cannot begin from a menu.
- Confirm the mod is enabled in its settings.
- Confirm FFmpeg passes **Test FFmpeg**.
- Check the in-game chat message and `logs\latest.log` for the exact failure.
- Free disk space. Recording is blocked when configured safety limits are
  reached; the default minimum is 500 MB free.
- Check **Options > Controls > Record-able** in case a key conflict changed
  the default binding.

Auto-record is on by default. If the recording overlay is already visible,
pressing the toggle key stops that recording instead of starting another.

## The video is black or cropped

Open settings and run **Capture Test**. If it reports black frames or a size
mismatch:

1. Temporarily disable shaders and OptiFine **Fast Render**.
2. Resize the game window or switch between windowed and fullscreen.
3. Restart the instance and run Capture Test again.
4. Update the graphics driver if every capture mode remains black.

Record-able automatically rotates through multiple framebuffer readback
methods, so let the test finish before changing settings.

## Game audio or microphone audio is missing

- Confirm **Capture Audio** is enabled.
- For a microphone, also enable **Capture Microphone**, select the device, and
  use **Test Mic**.
- If push-to-talk is enabled, hold its configured key while speaking.
- Open **How to Fix Audio** or **Test Audio** from the Audio settings.
- Restart Minecraft after changing sound-device or audio-mod configuration.

Game audio is captured directly from Minecraft's OpenAL mix and does not
require Windows Stereo Mix.

## Replay or automatic clips do not save

- Replay: enable **Replay Buffer**, bind **Save Replay Buffer**, and allow the
  buffer time to warm up.
- Automatic clips: enable **Auto-Clipping** plus at least one trigger.
- BedWars: enable **On Player Kill** and **Kill Montage**.
- Wait for the current replay encode to finish before saving another.
- Look under `recordings\recording_auto_clips`, not only the recording root.

## Performance is poor

Lower recording resolution or FPS first. Select a hardware encoder only when
it appears as available after FFmpeg detection; unavailable NVENC, AMF, or
Quick Sync selections fall back to software encoding.

## Include this in a bug report

- Launcher and exact profile type
- Java version
- Forge version
- Mod list
- `logs\latest.log`
- The **Test FFmpeg** report
- The **Capture Test** result for visual problems

Remove account names, server addresses, chat, and filesystem details you do
not want to share.
