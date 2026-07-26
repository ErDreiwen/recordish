# record-ish FAQ

record-ish is an unofficial Forge 1.8.9 port of the original
[Record-able](https://modrinth.com/mod/record-able) mod by Minewind's
Jo Eusebe. The original attribution and MIT license are retained.

## Original Record-able

### What is it?

Record-able is a client-side Fabric recording mod. It captures Minecraft video
and game audio, supports microphone audio, and includes overlays, a video
collection, replays, and automatic clips.

### Which versions does it support?

The original project is Fabric-only. Its Minecraft and Java requirements vary
between release families, so use the official Modrinth versions page to select
the matching build.

### Does it require FFmpeg?

Yes. FFmpeg converts captured frames and audio into playable video. Record-able
asks before downloading and verifying it when setup is required.

### Does it replace OBS?

It can handle Minecraft-only recording. It does not replace OBS scenes,
livestreaming, webcam layouts, alerts, or full desktop capture.

### Where should original-mod problems be reported?

Use the support links on the original Modrinth page, especially the creator's
Discord. Reports for this Forge port do not reach or represent the original
maintainer.

## record-ish

### Is this an official release?

No. It is an independently maintained Forge 1.8.9 port and is not an official
release or endorsement from the original author.

### What does it require?

- Minecraft `1.8.9`
- Forge `11.15.1.2318`
- Java `8`

### Does it work in Prism and Lunar Launcher?

Prism Launcher is supported. In Lunar, use the **Vanilla/Forge** option with a
Forge 1.8.9 profile. The branded Lunar/Ichor runtime filters arbitrary external
Forge JARs before Forge can load them.

### Why did recording start automatically?

Auto-record is enabled by default and starts two seconds after joining a world.
Disable **Auto Record** in settings if you prefer manual control.

### What are the default controls?

| Action | Key |
| --- | --- |
| Start or stop | `-` |
| Pause or resume | `=` |
| Settings | `F9` |
| Video collection | `F12` |
| Microphone push-to-talk | `V` |

### Where are files saved?

Regular recordings go to `recordings`. Automatic clips go to
`recordings\recording_auto_clips\<trigger>`, relative to the launcher instance
unless a custom output folder is configured.

### How should port, UI, or website problems be reported?

Use the website's guided Report Center. It prepares Markdown you can copy or
download. The builder does not upload what you type. Redact usernames, server
addresses, chat, file paths, tokens, and other private information before
sharing the result.
