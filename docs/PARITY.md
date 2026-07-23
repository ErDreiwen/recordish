# V1-0.08 legacy parity

The authoritative baseline is Record-able `V1-0.08-legacy`
(`5vxgtdx7`). “Adapted” means the user-facing behavior is retained with a
Minecraft 1.8.9 implementation rather than the upstream Fabric API.

| Area | Status | Forge 1.8.9 adaptation |
| --- | --- | --- |
| Final-frame video capture | Adapted | LWJGL 2 PBO readback with synchronous fallback and framebuffer/back-buffer/texture recovery |
| FFmpeg video encoding | Ported | RGB24 input, x264, NVENC, AMF, QSV, VP9, bounded queues, encoder health checks, and hardware preflight |
| Game audio | Adapted | Direct OpenAL Soft loopback installed before Paulscode creates sources |
| Microphone and audio timing | Ported | Java Sound devices, push-to-talk, pause spans, volume/mix controls, and video-origin alignment |
| Recording controls and auto-record | Ported | Forge key, client-tick, connection, disconnection, and shutdown events |
| Replay buffer and manual clips | Adapted | Bounded disk-backed RGB frame chunks with monotonic timing and optional rolling OpenAL audio |
| Automatic clips | Adapted | Achievement, death, dimension, boss, melee, aimed-swing, PvP, and locally owned arrow attribution |
| BedWars kill montage | Adapted | Combat-armed pre/post replay window and independent auto-clip FPS |
| Recording overlay and HUD editor | Ported | Forge overlay events and native 1.8.9 position editor |
| Watermarks, censors, and filters | Ported | Final-frame rendering plus replay-safe software censor baking |
| Settings and themes | Adapted | Native paged `GuiScreen` controls and legacy-safe theme rendering |
| Video collection and metadata | Adapted | Native 1.8.9 gallery, thumbnails, metadata, open, protect, and delete actions |
| Storage management | Ported | Usage stats, protected recordings, cleanup rules, manual cleanup, and recompression |
| Diagnostics and performance controls | Ported | Framebuffer/GL/FFmpeg/audio/disk checks, metrics, and adaptive session FPS |
| Markers, bookmarks, and chapters | Ported | Marker sidecars, chapter text, and optional FFmpeg chapter embedding |
| Replay Mod coexistence | Adapted | Defensive mod/class detection, playback auto-record, and optional OpenAL ownership yield |
| Android/Pojav-specific behavior | Not applicable | This branch is a desktop Forge 1.8.9 target |

## Verification

- The Forge 1.8.9 client reaches the main menu with both mixins applied.
- The OpenAL loopback route initializes on initial sound startup and resource
  reload.
- The capture injection executes at Minecraft's final-frame point before the
  main framebuffer is unbound and blitted.
- `compileJava`, the clean remapped build, and the Java 8 pipeline smoke test
  are required before release.
- The pipeline smoke test generates RGB frames and PCM audio through the port's
  `FFmpegEncoder` and `RecordingFinalizer`, then saves a disk-backed instant
  replay through `ReplayBuffer`; both outputs are inspected with FFprobe.

Replay playback detection is necessarily heuristic because legacy Replay Mod
does not expose a stable cross-version playback API. Failures are closed and
the bridge only stops recordings that it started itself.
