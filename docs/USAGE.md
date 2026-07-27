# Recording and replays

## Default controls

| Action | Default key |
| --- | --- |
| Start or stop recording | `-` |
| Pause or resume | `=` |
| Open settings | `F9` |
| Open video collection | `F12` |
| Microphone push-to-talk | `V` |

Replay save, bookmark, censor toggle, and censor editor keys are unbound by
default. Assign them under **Options > Controls > Recordish**.

If another mod already owns a default key, Recordish may choose a free
function key instead. The Minecraft Controls screen is always authoritative.

## Make a recording

1. Join a world or server.
2. Press the configured **Toggle Recording** key.
3. Press the pause key whenever footage should be omitted.
4. Press the recording key again and wait for the saved notification.

Auto-record is enabled by default and starts two seconds after joining a world.
Turn **Auto Record** off in settings if you want manual recording only.

Open the collection with `F12` or the title-screen clapperboard. From there
you can play a video, open its folder, copy its path, protect it, share it, or
delete it. Shared uploads become publicly accessible to anyone with the link.

## Save an instant replay

1. Open settings with `F9`.
2. Enable **Replay Buffer** and select its duration and quality.
3. Assign **Save Replay Buffer** in Minecraft's Controls screen.
4. Play long enough for the rolling buffer to fill, then press the assigned
   key to save the recent footage.

Only one replay save can encode at a time. Wait for its completion message
before requesting another.

## Automatic BedWars clips

In settings, enable:

- **Auto-Clipping**
- **On Player Kill**
- **Kill Montage**

The montage keeps a short window around an attributed kill. Direct melee hits,
recent aimed swings, and arrows fired by the local player are supported, but
server-side combat behavior can prevent perfect attribution.

## Files and folders

Paths are relative to the selected launcher's game folder unless you choose a
custom output directory.

| Item | Default location |
| --- | --- |
| Recordings and manual replays | `recordings` |
| Automatic clips | `recordings\recording_auto_clips\<trigger>` |
| Configuration | `config\recordish.json` |
| Managed FFmpeg | `recordish\ffmpeg\bin` |

The default recording name is `recordish-<date>-<time>`. Changing the output
directory also moves the automatic-clip tree beneath that directory.

Next: [Troubleshooting](TROUBLESHOOTING.md).
