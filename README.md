# Record-able for Forge 1.8.9

This is the Forge 1.8.9 port of
[Record-able](https://modrinth.com/mod/record-able), an in-game recorder that
writes standard video files with game audio, optional microphone audio,
instant replay, automatic clips, overlays, filters, watermarks, and privacy
censors.

The exact parity reference is the official `V1-0.09-modern` source archive
published with [Modrinth version `Tklmbazn`](https://modrinth.com/mod/record-able/version/Tklmbazn),
file `gHWYQl3T`, for Fabric on Minecraft 26.2. The implementation translates
that release's desktop behavior and visual compositions to Minecraft 1.8.9
rather than treating the older GitHub snapshot as the current UI reference.

The port targets:

- Minecraft 1.8.9
- Forge 11.15.1.2318
- Java 8 at runtime

The original MIT license and author attribution are retained.

## Documentation

- [Documentation hub](docs/README.md)
- [Installation and FFmpeg](docs/INSTALLATION.md)
- [Recording, replay, and BedWars clips](docs/USAGE.md)
- [Troubleshooting](docs/TROUBLESHOOTING.md)
- [Original and Community port FAQ](docs/FAQ.md)
- [Reporting a problem](docs/REPORTING.md)
- [Desktop parity and verification](docs/PARITY.md)
- [Release and deployment process](RELEASING.md)

## Install and record

1. Install Forge 1.8.9 (`11.15.1.2318`).
2. Put the downloaded `recordable-<version>-forge-1.8.9.jar` in the instance's
   `mods` folder.
3. Launch Minecraft. If FFmpeg is missing, Record-able opens a first-run setup
   prompt. Review the source and destination, then use **Download FFmpeg** on
   the dedicated progress screen. Nothing is downloaded without that explicit
   click. A custom FFmpeg executable can be selected instead.
4. Join a world or server and press `-` to start or stop recording.

Recordings go to the instance's `recordings` folder by default. The output
folder is configurable. Auto-record-on-world-join is enabled by default with a
two-second delay and can be disabled from the Recording settings.

The Record-able button on the title screen opens the V1-0.09-style video
collection. Its **Settings** button opens the scrollable, searchable settings
surface, with the modern theme, performance, streamer-mode, storage, FFmpeg,
and capture-diagnostics screens adapted to native 1.8.9 controls.

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

## Lunar Launcher

Record-able works when Lunar Launcher starts a normal **Vanilla/Forge** or
custom Forge 1.8.9 profile whose `mods` directory is passed to Forge.

Lunar's branded Forge/Ichor module currently filters arbitrary external Forge
JARs before Forge scans for mods. The launcher may list Record-able as enabled,
but that runtime does not put the JAR on its classpath. This happens before any
Record-able code or mixin runs, so it cannot be fixed as an ordinary mod
compatibility conflict. Stabilize and test the port with the Vanilla/Forge
profile first.

## Capture notes

Game audio is captured from Minecraft's OpenAL mix, so it does not require
Stereo Mix. Microphone capture uses Java Sound. If another replay mod needs
OpenAL ownership, the optional replay compatibility bridge can yield the
device and fall back gracefully.

The capture hook runs after Minecraft finishes the final HUD frame. Persistent
black frames automatically rotate among framebuffer, back-buffer, and texture
readback paths for OptiFine/shader compatibility. Hardware H.264 encoders are
preflighted and fall back to software x264 when unavailable.

## Porting boundary

The 26.2 release uses modern Fabric, Minecraft GUI, rendering, and input APIs.
This branch replaces them with Forge events, Java 8 `GuiScreen` controls, the
1.8.9 `FontRenderer`, and LWJGL 2 while retaining the reference layout,
interaction order, scrolling, theme presets, and desktop recording behavior.
Android/Pojav-only behavior is outside this desktop Forge target.

The V1-0.09 desktop parity build has completed a fresh Java 8 client,
pixel-level title/gallery/settings review, asynchronous FFmpeg interaction
check, and real in-world recording with game audio and pause/resume. The exact
verification record is kept in [docs/PARITY.md](docs/PARITY.md).

## Development

Gradle requires JDK 17 or newer to run this build; CI uses a JDK 17 host.
Compilation, verification, and compatible client launch use the explicit Java
8 toolchain, matching the Java version required by Minecraft 1.8.9.

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

The opt-in installer smoke test performs the real platform download into a
disposable `build` directory, verifies the provider checksum, stages and
publishes the binaries, and probes the published executable:

```powershell
.\gradlew.bat ffmpegInstallerSmokeTest
```

Stable Semantic Versions and every-main nightlies are built by GitHub Actions;
the JAR, checksum, and release manifest are published together. See
[RELEASING.md](RELEASING.md) before changing version or download metadata.

See [docs/PARITY.md](docs/PARITY.md) for the Forge adaptation and verification
matrix, and [UPSTREAM.md](UPSTREAM.md) for provenance.
