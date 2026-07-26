# V1-0.09 modern parity for Forge 1.8.9

The authoritative baseline is the official Record-able
`V1-0.09-modern` source archive published with Modrinth version `Tklmbazn`,
file `gHWYQl3T`, for Fabric on Minecraft 26.2. The source archive, rather than
the older GitHub tree or the earlier `V1-0.08-legacy` release, defines the
visual and behavioral target.

"Ported" means the feature is implemented directly on the legacy runtime.
"Adapted" means the user-facing behavior or composition is retained with
Forge 1.8.9, Java 8, `GuiScreen`, `FontRenderer`, and LWJGL 2 replacements for
the modern Fabric and Minecraft APIs.

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
| Settings and themes | Adapted | V1-0.09 continuous-scroll, searchable settings composition plus Classic, VHS Retro, Cinema, Neon Synthwave, and Minimal themes; panels, controls, scanlines, grain, glitches, and vignette use legacy-safe rendering |
| Video collection and metadata | Adapted | V1-0.09 responsive toolbar and 62-pixel entries with thumbnails, sorting, recordings/clips views, metadata, in-game player handoff, sharing, open, protect, and confirmed delete actions |
| Title-screen entry point | Adapted | The Record-able home control opens the video collection and preserves settings navigation |
| First-run FFmpeg setup | Adapted | V1-0.09 welcome and scrollable download compositions with explicit consent, source/destination disclosure, progress and verification states, retry, diagnostics, custom-path fallback, and platform guidance |
| Auxiliary settings screens | Adapted | Theme, performance, streamer-mode, storage, export, audio-help, and capture-diagnostics compositions use native 1.8.9 screens and input dispatch |
| Storage management | Ported | Usage stats, protected recordings, cleanup rules, manual cleanup, and recompression behind the V1-0.09-style screen |
| Diagnostics and performance controls | Adapted | A focused capture self-test report, FFmpeg checks in setup, and separate performance/adaptive-FPS controls replace modern runtime-specific diagnostics |
| Markers, bookmarks, and chapters | Ported | Marker sidecars, chapter text, and optional FFmpeg chapter embedding |
| Replay Mod coexistence | Adapted | Defensive mod/class detection, playback auto-record, and optional OpenAL ownership yield |
| Android/Pojav-specific behavior | Not applicable | This branch is a desktop Forge 1.8.9 target |

## Verification state

The recording backend was exercised before the current V1-0.09 UI parity
pass:

- The Forge 1.8.9 client reached the main menu with both mixins applied.
- The OpenAL loopback route initialized on initial sound startup and resource
  reload.
- The capture injection executed at Minecraft's final-frame point before the
  main framebuffer was unbound and blitted.
- The Java 8 pipeline smoke test generated RGB frames and PCM audio through
  `FFmpegEncoder` and `RecordingFinalizer`, saved a disk-backed instant replay
  through `ReplayBuffer`, and inspected both outputs with FFprobe.
- The opt-in FFmpeg installer smoke test exercised the real staged download,
  checksum verification, publication, and executable probe.

The complete V1-0.09 parity tree now passes a fresh Java 8 compile, test,
shadow, and remapped Forge build. The real Windows installer smoke also
downloaded the current gyan.dev archive, verified its published SHA-256,
staged FFmpeg and FFprobe, and passed a live executable probe. The resulting
binary then passed the recording/audio-finalization and disk-backed replay
pipeline smoke tests with FFprobe inspection.

Before final visual sign-off, launch the installed Java 8 client, visually and
interactively inspect every V1-0.09 screen at compact and normal window sizes,
and record and play back a representative BedWars session. Prism and Lunar
Vanilla/Forge now contain the same verified JAR but still need that fresh
launch check; Lunar's branded Forge/Ichor runtime remains a separate
unsupported classpath boundary.

Replay playback detection is necessarily heuristic because legacy Replay Mod
does not expose a stable cross-version playback API. Failures are closed and
the bridge only stops recordings that it started itself.
