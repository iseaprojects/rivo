# Deep links

Everything Android sends to Rivo — `rivo://` links, shared text, `VIEW` intents for YouTube URLs,
Tasker broadcasts and shortcuts — goes through `LaunchRouter`. The router turns it into a menu
frame, so a link does exactly what navigating there by hand does.

## Routes

| Link | Opens |
| --- | --- |
| `rivo://` | Main menu |
| `rivo://search?q=…&type=video` | Search; `type` is `video`, `playlist`, `channel`, `shorts` or `movie` |
| `rivo://feed?kind=subscriptions` | Feed; `kind` is `subscriptions`, `trending`, `watch-later`, `liked`, `history` or `clips` |
| `rivo://saved` · `rivo://recent` | Saved videos and recents |
| `rivo://downloads` | Download queue and files |
| `rivo://misc` · `rivo://config` | Miscellaneous and configuration screens |
| `rivo://channels` | Channel list |
| `rivo://channel?name=…` | One channel; accepts `name`, `handle`, `id` or `url` |
| `rivo://playlist?name=…&id=…` | A YouTube playlist |
| `rivo://custom-playlist?name=…` | A playlist stored on the device |
| `rivo://custom-cmd?name=…` | A saved custom command |
| `rivo://watch?v=…` | Media actions for a video id |
| `rivo://media?url=…` | Media actions for any supported URL |

Unrecognised hosts fall through to `null`, so the app opens normally instead of showing an empty
screen.

## Parameters

- `exit=true` closes the player or the command screen when the action finishes, which is what the
  terminal version's `--exit` did.
- `playlist-skip=true` starts a playlist at the second item.
- `url` on `media`, `channel` and `playlist` bypasses id handling and uses the URL verbatim.

## Intent extras

The same actions can be requested with extras instead of URL parameters. Boolean extras are truthy
when present with a non-false value.

| Extra | Effect |
| --- | --- |
| `RIVO_ACTION` | Action token: `play`, `watch`, `listen`, `download`, `download-audio`, `save`, `play-all`, `listen-all`, `download-all`, `save-playlist` |
| `RIVO_PLAY`, `RIVO_WATCH`, `RIVO_LISTEN` | Shorthand flags for the same actions |
| `RIVO_DOWNLOAD`, `RIVO_DOWNLOAD_AUDIO`, `RIVO_DOWNLOAD_ALL` | Download flags |
| `RIVO_SAVE`, `RIVO_SAVE_PLAYLIST`, `RIVO_PLAY_ALL`, `RIVO_LISTEN_ALL` | Library and queue flags |
| `RIVO_INPUT` | Text for a prompt stage |
| `RIVO_EXIT`, `RIVO_MEDIA_EXIT`, `RIVO_CMD_EXIT` | Exit when the action completes |
| `RIVO_PLAYLIST_SKIP` | Start the playlist at the second item |
| `RIVO_PER_PAGE` | Runtime page size, mapped to `CONFIG_PER_PAGE` |
| `RIVO_CONFIG` | Semicolon-separated `CONFIG_*` assignments, e.g. `CONFIG_THEME=catppuccin-mocha` |

Overrides are held in memory for the launch and never written to `files/config`; unknown names are
ignored rather than injected.

## Examples

```bash
# Start a search and go straight to the results
adb shell am start -a android.intent.action.VIEW -d "rivo://search?q=lofi+study" \
  -n io.github.iseaprojects.rivo/io.github.iseaprojects.rivo.ui.MainActivity

# Play a video with a bigger page size for this launch only
adb shell am start -n io.github.iseaprojects.rivo/io.github.iseaprojects.rivo.ui.MainActivity \
  -a android.intent.action.VIEW -d "rivo://watch?v=dQw4w9WgXcQ" \
  --ez RIVO_PLAY true --es RIVO_PER_PAGE 60

# Share sheet: any YouTube URL opens the media action menu
adb shell am start -a android.intent.action.VIEW -d "https://youtu.be/dQw4w9WgXcQ"
```

## Tasker

A Tasker task broadcasts `io.github.iseaprojects.rivo.action.TASKER_FIRE` with the link in
`rivo_uri` (or `RIVO_URI`, or as the intent data). The receiver forwards it to the same router, so
Tasker has exactly the deep-link surface and nothing more.

```bash
adb shell am broadcast -f 0x20 \
  -n io.github.iseaprojects.rivo/io.github.iseaprojects.rivo.platform.TaskerFireReceiver \
  -a io.github.iseaprojects.rivo.action.TASKER_FIRE --es rivo_uri "rivo://recent"
```

Android blocks background activity starts. The receiver opens the UI directly only on Android
8.0–9, or when Rivo holds "Display over other apps"; otherwise it posts a notification whose tap
opens the same link. If notifications are also off, the request is logged and dropped (D47).
