# Record-able for Forge 1.8.9

This is the Forge 1.8.9 port of
[Record-able](https://github.com/JoEusebe/record-able), an in-game recorder
that writes standard video files with game audio, optional microphone audio,
instant replay, automatic clips, overlays, filters, watermarks, and privacy
censors.

The port targets:

- Minecraft 1.8.9
- Forge 11.15.1.2318
- Java 8 at runtime

The original MIT license and author attribution are retained.

## Install and record

1. Install Forge 1.8.9 (`11.15.1.2318`).
2. Put the finished `recordable-1.0.0-forge-1.8.9.jar` in the instance's
   `mods` folder.
3. Launch Minecraft, click **Record-able** on the main menu, open **Storage**,
   and use **Download FFmpeg** once. A custom FFmpeg executable can be
   selected instead.
4. Join a world or server and press `-` to start or stop recording.

Recordings go to the instance's `recordings` folder by default. The output
folder is configurable. Auto-record-on-world-join is enabled by default with a
two-second delay and can be disabled from the Recording settings.

Default controls:

| Action | Key |
| --- | --- |
| Start/stop recording | `-` |
| Pause/resume | `=` |
| Settings | `F9` |
| Video collection | `F12` |
| Microphone push-to-talk | `V` |

Replay saving, bookmarks, and censor controls are available as normal
Minecraft key bindings but are unbound by default.

For BedWars kill clips, enable **Auto clips**, **Player-kill clips**, and
**Kill montage**. The Forge port attributes direct melee hits, recent aimed
swings, and locally fired arrows while keeping the montage buffer combat-armed
instead of continuously writing full-resolution raw frames.

## Capture notes

Game audio is captured from Minecraft's OpenAL mix, so it does not require
Stereo Mix. Microphone capture uses Java Sound. If another replay mod needs
OpenAL ownership, the optional replay compatibility bridge can yield the
device and fall back gracefully.

The capture hook runs after Minecraft finishes the final HUD frame. Persistent
black frames automatically rotate among framebuffer, back-buffer, and texture
readback paths for OptiFine/shader compatibility. Hardware H.264 encoders are
preflighted and fall back to software x264 when unavailable.

## Development

Gradle itself needs a modern host JDK; compilation and game launch use the
Java 8 toolchain.

```powershell
.\gradlew.bat clean build
.\gradlew.bat runClientCompat
```

The remapped distributable JAR is written to `build/libs`.

An FFmpeg-backed pipeline smoke test is also included:

```powershell
.\gradlew.bat pipelineSmokeTest `
  -PrecordableSmokeFfmpeg="C:\path\to\ffmpeg.exe"
```

See [docs/PARITY.md](docs/PARITY.md) for the Forge adaptation and verification
matrix, and [UPSTREAM.md](UPSTREAM.md) for provenance.
