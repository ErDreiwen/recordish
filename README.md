## Record-able
---
---
---
A lightweight screen recording mod for Fabric 1.21.11+ that lets you capture gameplay.

what does it do?

Record-able captures video and audio directly from your Minecraft session. No external software needed, no performance tanks. Uses on-demand FFmpeg downloads instead of bundling binaries.

## Key features!
---
- Hardware acceleration support (NVENC, AMF, QuickSync)

- Two container formats(For now) (MP4, MKV)

- Customizable video quality and bitrate settings

- Audio capture from game

- Max file size limits with auto-stop

- Customizable recording overlay (5 positions, scalable from 50% to 200%)

- Built-in video collection browser


## How does FFmpeg work?
---
First launch will prompt you to download FFmpeg. The mod handles this automatically from trusted mirrors (gyan.dev, johnvansickle.com, evermeet.cx) with SHA-256/MD5 integrity verification. One-time setup, then you're good to go.

## System requirements(because ofc🤷)
---
Fabric 1.21.11 or above

Java 21

FFmpeg (downloaded on first use when prompted)

## Controls🎮
---
Start/stop recording with a keybind (configurable)

Access settings and video collection from mod menu

Overlay shows recording status and duration in real-time

## Technical details for the power-users!
---
Uses OpenAL loopback for audio capture with microsecond-precision timestamp tracking to prevent A/V drift. Video encoder runs async to avoid frame drops. Supports custom FFmpeg parameters if you want full control.

## Known Incompatibilities

- Flashback
- Replay Mod
- and (probably) any other recording mod

why? because Flashback usually causes visual corruption in the record-able mod's recordings and Replay is kinda similar (Shout-out to each respective mods authors)

**Also High quality, Fps, Resolution, The Replay buffer feature and long recording times**

**Eat-up a lot of Memory, Cpu and Ram(So better be careful!)**

# Disclaimer⚠️:
---
For complete clarity, this project was and is AI assisted.

<iframe width="560" height="315" src="https://www.youtube-nocookie.com/embed/6Mz6RwHReW4" title="YouTube video player" frameborder="0" allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share" allowfullscreen></iframe>
