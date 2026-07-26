# Installation

## Install the mod

1. Create or select a Minecraft `1.8.9` instance.
2. Install Forge `11.15.1.2318`.
3. Put `recordable-1.0.0-forge-1.8.9.jar` in that instance's `mods` folder.
4. Start the game with Java 8.

Keep only one Record-able JAR in the profile. The title screen should show a
small clapperboard button after a successful load.

### Prism Launcher

Open the instance's **Edit** window, confirm that Forge is listed on the
**Version** page, then use **Mods > Add file** to add the JAR.

The default instance game folder is beneath:

```text
%APPDATA%\PrismLauncher\instances\<instance>\minecraft
```

### Lunar Launcher

Select Lunar's **Vanilla** launcher option and a Forge 1.8.9 profile, then add
the JAR to the profile's mods directory. On Windows, the usual port profile is:

```text
%USERPROFILE%\.lunarclient\profiles\vanilla-1.8\mods
```

Do not select the branded **Lunar** runtime for this mod. That runtime can list
an external JAR as enabled while filtering it before Forge scans the profile.
This is a launcher classpath restriction, not a Record-able setting.

## Install FFmpeg

FFmpeg is required to create playable video files.

On the first launch without FFmpeg, Record-able opens a setup prompt:

1. Choose **Download FFmpeg**.
2. Review the displayed download source and destination.
3. Press **Download FFmpeg** on the detailed screen to give consent and begin.
4. Wait for the screen to report that FFmpeg is ready.

The Windows download is the x64 release-essentials bundle from gyan.dev. The
mod verifies and installs `ffmpeg.exe` and `ffprobe.exe` inside the current
instance:

```text
<game folder>\recordable\ffmpeg\bin
```

Nothing is downloaded merely by opening the setup screen.

### Use an existing FFmpeg

Open Record-able settings with `F9`, search for `FFmpeg`, and open the FFmpeg
setup screen. Copy the full path to `ffmpeg.exe`, choose
**Paste FFmpeg Path**, then choose **Test FFmpeg**. Keeping `ffprobe.exe` next
to it is recommended for gallery metadata and diagnostics.

Continue with [Recording and replays](USAGE.md).
