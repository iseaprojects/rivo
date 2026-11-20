# Architecture

Rivo is a native Android port that keeps yt-x's mental model and replaces its runtime.
Upstream is a bash script that shells out to `fzf`, `jq`, `yt-dlp` and `mpv`; here the
*shape* of the app (nested menus, one filterable list per level, declarative extensions,
plain-text config) is preserved, while rendering, extraction, playback and storage are
native.

## Layers

```
┌──────────────────────────────────────────────────────────────────┐
│ ui/            MainActivity, RivoRoot, MenuScreen<T>, media/       │  Compose only
├──────────────────────────────────────────────────────────────────┤
│ menu/          BuiltInMenus, BuiltInHandlers                     │  app wiring
├──────────────────────────────────────────────────────────────────┤
│ core/menu      MenuNode, catalog, engine, navigator, patcher     │  no Android UI
│ core/launch    LaunchSpec, parser, router, inbox                 │  no Android UI
│ core/config    ConfigKeys, parser, store, snapshot               │  file + Flow
│ core/i18n      Strings, repository                               │  assets
│ core/theme     ThemeSpec, ThemeRepository                        │  assets
│ core/model     MediaSummary, PlaylistSummary, ChannelSummary,    │  pure data
│                LibraryKeys                                       │
├──────────────────────────────────────────────────────────────────┤
│ data/library   Room entities, DAO, repository                    │  persistence
├──────────────────────────────────────────────────────────────────┤
│ di/AppGraph    manual service locator                            │
└──────────────────────────────────────────────────────────────────┘
```

Nothing under `core/` imports Compose, and only `ConfigStore`, `StringsRepository`,
`ThemeRepository` and the platform handlers touch `Context`. `core/launch`, `core/menu`
and `core/config` parsing are plain Kotlin, which is why the unit tests in
`app/src/test` can run on the JVM. `data/library` is Android-bound only through Room and
`Context`; `LibraryRepository` itself takes a DAO and a dispatcher, so the whole write
protocol is tested on the JVM against an in-memory fake.

Later phases add sibling packages with the same separation: `player/` (F5), `download/` and
`data/download` (F6), `core/update`, `core/menu/extension` and `ui/config` (F7). Extraction
lives in `data/extract` and `data/net` (F2); it is plain Kotlin apart from the yt-dlp runtime
in the `ytdlp` source set. `download/` holds only the foreground service; the format planner,
queue, archive, writers and muxer live in `data/download` so their rules are JVM-testable
(D40–D42).

## Menu model

### Nodes

`MenuNode` is one row. It is data, not code:

| Field | Purpose |
| --- | --- |
| `id` | stable identity; used by sort/filter patches and as the duplicate-key guard |
| `labelKey` / `label` | localized string key or literal |
| `iconKey` / `icon` | Nerd Font glyph from a `.lang` bundle, or a literal glyph |
| `detailKey` / `detail` | second line (config value, URL, page count…) |
| `kind` | `ACTION`, `SUBMENU`, `TOGGLE`, `INFO` |
| `handler` | leaf action id, e.g. `app.open_url` |
| `targetMenuId` | push a registered menu |
| `children` | push an inline submenu (extension injections) |
| `payload` | what the target frame is *about* |
| `value` | the answer a Prompt/Confirm re-dispatch carries (`"confirmed"` for destructive actions) |
| `toggleKey` / `checked` | config-backed switch |
| `tone` | `NORMAL`, `ACCENT`, `DESTRUCTIVE`, `MUTED` |
| `position` | insertion hint used by patches |
| `source` | `BUILTIN` or `EXTENSION` |

Exactly one of `targetMenuId`, `children` or `handler` drives a click.
`MenuResult.Unhandled` is a first-class outcome: the UI shows "Action not available yet"
and names the handler, so a typo in an extension or a menu owned by a later phase degrades
instead of crashing.

### Payloads

The same menu id serves many screens. `MenuPayload` carries the difference:

```kotlin
MenuPayload.Feed(FeedKind.TRENDING)      // -> menu "feed"
MenuPayload.Library(LibraryKind.SAVED)   // -> menu "library"
MenuPayload.Search("lofi", SearchKind.CHANNELS)
MenuPayload.Media(MediaSummary(...))     // -> menu "media_actions"
MenuPayload.Channel(ChannelSummary(...)) // -> menu "channel"
MenuPayload.Url("https://…")             // external or deeplinked media
MenuPayload.ConfigValue("CONFIG_PER_PAGE") // -> menu "config_choice"
MenuPayload.LocalPlaylist(7, "Road trip")  // -> menu "local_playlist"
MenuPayload.Text("extension-defined")      // built-in frame with a text body
```

Two payload fields exist only on `MenuPayload.Media`: `collectionId` and
`collectionTitle` name the list an item was opened *from*. They are what makes
"Remove from playlist" appear (and address the right row) when the same video is reached
through a custom playlist rather than through search.

`MenuFrame(menuId, title, payload, nodes)` is one entry of the back stack. Inline
submenus (`nodes != null`) never consult the catalog; everything else is rebuilt from the
catalog on each resolution, so a config change is reflected immediately. That is also why
the theme/language/per-page pickers are a real menu (`config_choice`) rather than inline
children: a pushed `children` list would freeze the selected-row checkmark at push time.

### Resolution pipeline

`MenuEngine.nodes()` mirrors the upstream `_menu_*` + `fzf` pipeline:

```
definition.build(ctx)          -- declarative list, may read config/payload
  -> MenuPatcher.applyAll()    -- extension patches, in file order: filter, sort, inject
  -> distinctBy(id)            -- duplicate ids are logged and the later one dropped
  -> localize()                -- labelKey -> text, iconKey -> glyph, toggleKey -> checked
  -> List<MenuNode>            -- rendered by the screen
```

Localization is last so an extension never needs to know about languages. Icons are
resolved to ASCII (`IconCatalog`) when `CONFIG_NERD_FONTS=false`.

### Patching

`rivo.ui/1` patches are applied per menu id:

```json
{
  "schema": "rivo.ui/1",
  "menus": {
    "main": {
      "sort": ["search", "feed.home", "3"],
      "filter": "^legacy\\.",
      "dropUnlisted": false,
      "inject": [
        { "id": "sites", "label": "Sites", "position": "after:channels",
          "children": [ { "id": "sites.dm", "label": "Dailymotion", "handler": "custom.dailymotion" } ] }
      ]
    }
  }
}
```

- `filter` is a regex tested against the node `id` and its `labelKey`. Matching nodes are
  removed (upstream: `grep --invert-match`).
- `sort` is an ordered list of ids or 1-based indexes (upstream `sort` strings are
  `"1,2,3,..."`). Unlisted nodes follow, unless `dropUnlisted` is set.
- `inject[].position` accepts `start`, `end` (default), `before:<id>`, `after:<id>` or a
  numeric index.
- Unknown fields are ignored; an invalid regex or a node without a label is reported in
  `UiExtension.issues` and skipped.

An extension node may reuse a built-in handler (`app.open_url`, `app.copy_url`,
`config.toggle`…) but cannot declare a new one: handler ids are resolved against the
registry the app ships with, so an unknown id degrades to a message instead of loading code
(D43). `targetMenuId` lets an extension open a built-in screen; `children` is enough for a
self-contained subtree.

## Library

Saved videos, recents, subscriptions, custom playlists, saved remote playlists and search
history are Room tables. Everything else (config, strings, themes, cookies) stays a file or
an encrypted preference; the split is D14.

### Schema

| Table | Key | Notes |
| --- | --- | --- |
| `saved_video` | `key` (`LibraryKeys`) | full `MediaSummary` snapshot, saved-at timestamp |
| `recent_video` | `key` | played-at timestamp, trimmed to `CONFIG_NO_OF_RECENT` on write |
| `subscription` | `key` | channel snapshot, subscribed-at timestamp |
| `custom_playlist` | auto id | title, created-at; `itemCount` via a relation query |
| `playlist_item` | auto id, `playlistId` FK | `ON DELETE CASCADE`, position, item snapshot |
| `search_history` | folded query | last-used timestamp, capped at `LibraryLimits.SEARCH_HISTORY` |
| `saved_playlist` | `key` | remote playlist snapshot |

Keys are provider-scoped (`youtube:dQw4w9WgXcQ`) rather than bare ids, because the same id
can exist on two sites and the yt-dlp flavor accepts both. `LibraryKeys` also owns the
`!1` recall syntax, so the search box and the table agree on what "entry 1" means.

### Snapshot

`AppGraph` combines five DAO flows into one `StateFlow<MenuLibrary>` and passes it into
`MenuContext`. Menu builders never query Room: `MenuContext.library.saved` answers
"Save or UnSave?" synchronously, which keeps `BuiltInMenus`/`MediaMenus` pure and the
label consistent across every screen that shows the same item. Writes emit
`LibraryRepository.changes`, which local media screens collect to reload.

### Prompt and Confirm

`MenuResult.Prompt` and `MenuResult.Confirm` turn a handler into a two-step interaction
without holding state in the handler. The UI shows a dialog, then re-dispatches the same
handler with `MenuNode.value` set to the typed text or `LibraryHandlers.CONFIRMED`. The
handler re-reads live data (a playlist title, for instance) at both steps, so a prompt
opened before a rename still acts on the current row.

### Local media sources

`MediaSource.Library(SAVED|RECENT)` and `MediaSource.LocalPlaylist(id)` adapt Room to the
same `MediaLoader` contract as search and feeds. The loader returns every row in one
`MediaPage`; `PageWindow` applies `CONFIG_PER_PAGE`, so Previous/Next are instant and
offline. Opening a media row's action menu records it as recent (upstream records on media
activation), and rows opened from a custom playlist carry `collectionId` so the action menu
can offer removal.

Activation is uniform across sources: a video row opens its action menu (and records a
recent), a playlist row opens playlist actions, a channel row opens channel actions.
Playlist and channel menus put the drill-down first, so the contents stay one tap away,
and the playlist menu is where Save lives.

## Configuration

`filesDir/config` is the only store. It is a plain text file in the upstream syntax —
`KEY=value`, `export KEY=value`, quotes, comments — so an existing `config` from yt-x can
be dropped in unchanged.

```kotlin
ConfigStore(appContext)
  ├── state: StateFlow<ConfigSnapshot>   parsed map + typed accessors
  ├── set(key, value)                    reads, edits one line, writes atomically
  ├── setBoolean / remove
  ├── importText(text)                   replaces file, appends missing defaults
  ├── ensureDefaults()                   append-only migration
  └── reloadBlocking()                   after external edits (onResume)
```

- Writes go to `config.tmp`, `fd.sync()`, then rename — an interrupted save cannot
  truncate the file.
- `ConfigDocument` rewrites only the affected line, so comments and ordering survive a
  trip through the settings screen. `append()` is used for new keys.
- A single `Mutex` serializes writes. `setBlocking` exists for binder/receiver entry points
  (`DocumentsProvider`, Tasker) and uses `runBlocking` around the same mutex — never a
  second lock, which would deadlock on dispatcher hops.
- Missing keys are appended on startup and never overwritten: upgrades are additive.

`RuntimeConfig` layers `RIVO_*` intent extras on top of the file without persisting them.
`ConfigSnapshot.withOverrides` is a map union, so a runtime override shadows the file for
the lifetime of the process.

Keys are enumerated in `ConfigKeys` (upstream names, plus Android-only ones such as
`CONFIG_DOWNLOAD_WIFI_ONLY`). `ConfigKeys.keyForOverride` maps `RIVO_PER_PAGE` and
`YT_X_PER_PAGE` to `CONFIG_PER_PAGE`, handles the mismatched aliases
(`RIVO_ENUMERATE_DOWNLOADS` → `CONFIG_DOWNLOADS_ENUMERATE`), and rejects anything not in
`known` so intents cannot inject arbitrary properties.

## Internationalization

Strings are data, not resources: `assets/extensions/langs/<tag>.lang.json` with the
upstream `TXT_*` keys.

```json
{ "schema": "rivo.lang/1", "locale": "es", "name": "Español",
  "strings": { "TXT_MENU_MAIN_SEARCH": "Buscar" } }
```

`StringsRepository` merges `en` first, then the selected locale, and resolves
`CONFIG_LANG=auto` against `Locale.getDefault()` with a language-only fallback
(`pt-BR` → `pt` → `en`). Missing keys fall back to English, so a partial pack is useful.

Three packs ship in F1: English (294 keys), Spanish and Brazilian Portuguese (225 each —
the upstream translations plus the Android additions). A fourth can be added by dropping
a file into `assets/extensions/langs/`; F7 also loads them from SAF.

Android's own resources are limited to strings the system renders — notification channels,
the documents-provider label, shortcut labels, the widget. Everything the user reads
inside the app comes from the bundles, which is why a language pack can be installed
without rebuilding.

Format strings use `%1$s` / `%1$d` and are applied with `String.format(Locale.ROOT)`;
a malformed template returns the raw template instead of throwing.

## Themes

`*.theme.json` maps Material 3 color roles plus the terminal accents yt-x used for fzf:

```json
{ "id": "tokyo-night", "name": "Tokyo Night", "dark": true,
  "colors": { "primary": "#7aa2f7", "background": "#16161e", ... },
  "terminal": { "iconPrimary": "#2ac3de", "iconError": "#f7768e", "previewKey": "#7aa2f7" } }
```

`ThemeSpec.toColorScheme()` fills all 36 Material 3 roles, using the spec's values and
sensible fallbacks from the same palette, so a minimal theme still yields a coherent
scheme. Dynamic color is not implemented on purpose: the identity of the app is the
imported palette, and wallpaper-derived colors would make previews and icon colors
unpredictable.

`terminal` keeps the `THEME_FZF_*` semantics: icon primary, icon secondary, icon accent,
icon error, preview key/value/divider and pointer. `IconCatalog` and the list screen read
from it, so a theme repaints both Material surfaces and the terminal-style accents.

## Launching

Every entry point is normalized before any UI exists:

```
Intent ──▶ LaunchSpecParser.parse() ──▶ LaunchSpec ──▶ LaunchInbox ──▶ LaunchRouter
                                                                          │
                                                                 LaunchRoute(menuId, payload)
```

`LaunchSpec` fields: `deepLink`, `externalUrl`, `sharedText`, `action`, `input`, `exit`,
`playlistSkip`, `mediaExit`, `cmdExit`, `overrides`.

### Supported intents

| Entry | Example |
| --- | --- |
| Launcher / shortcut | no data |
| `rivo://` deep link | `rivo://search?q=:4k+eono&action=play` |
| YouTube URL | `https://youtu.be/<id>`, `/watch?v=`, `/shorts/`, `/playlist?list=` |
| Share sheet (`text/plain`) | a URL becomes media actions, free text becomes search |
| `am start` extras | `--es RIVO_ACTION download --es RIVO_PER_PAGE 60` |
| Tasker / QS tile / widget | same intent shape (F7) |

### Extras

| Extra | Meaning |
| --- | --- |
| `RIVO_ACTION` | `play`, `listen`, `download`, `download-audio`, `save`, `play-all`, `listen-all`, `download-all`, `save-playlist` |
| `RIVO_PLAY` … `RIVO_SAVE_PLAYLIST` | boolean form of the same flags (yt-x CLI parity) |
| `RIVO_INPUT` | query, URL or search filter string |
| `RIVO_EXIT` | finish after the action |
| `RIVO_PLAYLIST_SKIP` | do not prompt for the playlist |
| `RIVO_MEDIA_EXIT`, `RIVO_CMD_EXIT` | exit the media/command menu after the action |
| `RIVO_<SETTING>` | runtime config override (`RIVO_PER_PAGE=60`) |
| `RIVO_CONFIG` | `KEY=value;KEY=value` multi-override, unknown keys rejected |

### Deep link grammar

| URI | Route |
| --- | --- |
| `rivo://search?q=…&type=channels` | search, with kind |
| `rivo://feed?kind=trending` | feed (`home`, `subscriptions`, `trending`, `watch-later`, `liked`, `history`, `clips`) |
| `rivo://saved`, `rivo://recent`, `rivo://downloads` | library screens |
| `rivo://channel?name=…`, `?id=UC…`, `?handle=@…` | channel tabs |
| `rivo://playlist?name=…&id=PL…` | playlist |
| `rivo://custom-playlist?name=…` | local playlist |
| `rivo://custom-cmd?name=…` | saved command |
| `rivo://watch?v=…` | media actions for a video |
| `rivo://media?url=…` | media actions for an arbitrary URL |
| `rivo://misc`, `rivo://config`, `rivo://channels` | built-in screens |

`LaunchInbox` stamps each event with a monotonic id; `RivoRoot` remembers the last handled
id in `rememberSaveable`, so a configuration change does not replay the intent and push a
duplicate frame — the reason a `SharedFlow(replay = 1)` is not used here. The inbox and the
menu stack live in `RivoViewModel`, so rotation keeps the current screen *and* the handled
state; ids are seeded from the clock so that after process death (fresh inbox, restored
"handled" id) the launch intent is applied again instead of being silently skipped.

## Playback

Playback is split between a service that owns the player and an activity that only draws it.

```
menu action ──▶ PlaybackActions ──▶ PlaybackRequest ──▶ startForegroundService(SET_QUEUE)
                                                             │
                                                    PlaybackService
                                                             │
                                    PlaybackCoordinator (queue, prefetch, rebuild)
                                                             │
                        StreamPlanner ──▶ StreamMediaSourceFactory ──▶ ExoPlayer
                                                             │
                                       MediaItem.mediaMetadata.extras (JSON)
                                                             │
    PlayerActivity ── MediaController ──▶ PlayerController ──▶ Compose
```

`PlaybackService` is a `MediaSessionService` **and** a `MediaBrowserService` (the second
filter is what lets a media browser, and `SystemUI`'s own resume flow, enumerate the session).
It hosts one `ExoPlayer`, one `MediaSession`, and the notification. `PlayerActivity` carries no
request: it connects a `MediaController`, reads `PlayerUiState` every 250 ms, and sends
commands. That is what makes closing the activity (or entering PiP) leave playback alone.

### Sessions and custom commands

Everything the system can drive — play, pause, seek, next, previous, speed, subtitle track —
is a normal Media3 command, so the notification, Bluetooth headsets and Android Auto all work
without extra code. Three things cannot be expressed that way because they need the service's
extraction results:

| Command | Payload | Effect |
| --- | --- | --- |
| `SET_QUEUE` | serialized `PlaybackRequest` | replace the timeline, resolve the first item, start prefetching |
| `SET_STREAM` | `height` and/or `audio_track` | rebuild the current item with another quality cap or audio rendition, keep position |
| `STOP` | — | clear the queue and stop the service |

`PlayerExtras` travels inside each `MediaItem`'s `MediaMetadata`: chapters, SponsorBlock
segments, heatmap buckets, playable heights, audio tracks, the active track, the quality
label, whether the item has a picture, and the request's queue length. The activity builds its
sheets from those extras instead of re-extracting anything.

### Stream selection

`StreamPlanner` is pure: it takes `VideoDetails` and returns a `PlannedStream`. In order:

1. **Audio mode** → best audio-only rendition, played without a surface.
2. **Live** → the DASH/HLS manifest. A muxed URL for a live stream is a snapshot of the DVR
   window, not the live edge.
3. **Muxed at the same height** → one `ProgressiveMediaSource`, no merging.
4. **Manifest delivery** → the manifest, so Media3 adapts.
5. **Video-only + audio-only** → `MergingMediaSource`, which is the case yt-x's mpv path lost
   audio on.
6. **Video only / audio only / manifest fallback**, in that order.

A quality cap rebuilds the item through the same planner, so "1080p" means the same thing
while a playlist plays as it does for the first item. `replaceMediaItem` is used instead of
remove+add: the timeline index stays stable and the seek target survives.

### Queue and prefetch

The request is the queue; the ExoPlayer timeline is a window over it. The coordinator resolves
the first item, then appends while the resolved count is within `PREFETCH_AHEAD` of the
current index. A single append pass runs at a time (two triggers can fire together), and an
item that cannot be resolved is dropped from the queue rather than left to stall `next`.
`PlayerExtras.queueSize` keeps "3 of 26" honest.

Expired URLs are handled inside the session: a 403 on a resolved item re-runs the extractor
once per item (`retried`), then rebuilds that timeline entry at the same position.

### SponsorBlock and A–B

`SponsorBlockClient` fetches segments from `sponsor.ajay.app` for the configured categories.
The coordinator polls the position every 500 ms and seeks past a segment that starts before
the playhead and ends after it; the skipped segment is remembered so a manual seek back does
not fight the skipper. Segments are also drawn on the timeline. A–B loop is a UI-level
construct (Media3 has no loop-region primitive): the player screen remembers two positions and
seeks back whenever the playhead crosses the end.

Chapters are the extractor's, not the player's: `VideoDetails.chapters` comes from NewPipe's
description chapter panel (ends derived from the next start) or from yt-dlp's `chapters`
array, and travels in `PlayerExtras`, so the sheet and the timeline ticks need no second
extraction.

### Leaving the app

- **External player** (`CONFIG_PLAYER=external`): `ExternalPlayer` resolves the stream URL and
  hands it to mpv/VLC/MX by package, or to the system chooser, in muxed or adaptive form
  (`CONFIG_EXTERNAL_FORMAT`).
- **Termux shell**: `TermuxShell` sends the same URL and title through
  `com.termux.RUN_COMMAND` with `YT_X_URL`/`YT_X_TITLE`/`YT_X_ID`/`YT_X_STREAM`.
- **Close**: the X finishes the activity; `CONFIG_DISOWN_PLAYER` (default on) keeps the
  service playing, off stops it.
- **PiP**: the activity declares `supportsPictureInPicture`; the surface is a `TextureView`
  so the picture survives the window change (D32).


## Downloads

`DownloadRepository` owns the state; `DownloadService` owns the clock. The repository keeps
`queue.json` (`version: 1`, a list of tasks with kind, id, URL, metadata, state, progress and
output path) and `archive.txt` (`kind<TAB>videoId`, D40) in `files/downloads`, plus a
`StateFlow` snapshot the menus read. Every transition takes a mutex and rewrites the whole
file, so the queue on disk is always one consistent list; progress counters stay in memory
and only the final byte count reaches disk with `DONE`. A `RUNNING` row loaded at startup is
demoted to `QUEUED`, which is what makes a killed process resume rather than wedge.

A task moves `QUEUED → RUNNING → DONE | FAILED | CANCELLED`, with `PAUSED` reachable from
either live state. `DownloadService` is started with `startForegroundService`, claims one
`QUEUED` task at a time, re-reads the queue after each transition, and stops itself when
nothing is claimed or queued. The notification is aggregate (`n downloading · m queued`) and
its tap opens `rivo://downloads`; it is posted through the `downloads` channel. Pause signals
the engine's coroutine, which returns without deleting its work directory; the part file is
what a later `Range` request continues. Cancel deletes the work directory. The repository
sweeps stale work directories once at construction, skipping every paused task's directory.

`DownloadEngine` asks `DownloadPlanner` for one of three plans:

| Plan | Meaning |
| --- | --- |
| `Direct(format)` | one progressive file: a muxed rendition, or an audio-only one |
| `Merge(video, audio)` | two progressive MP4 renditions joined by `MediaMuxer` |
| `Manifest(url, mime)` | HLS/DASH only; handed to the yt-dlp binary when the build has one |

Merging is MP4-only by design: `MediaMuxer` writes MP4, and an Opus track inside it is not
portable (D41). An audio download honours `CONFIG_AUDIO_FORMAT` (`m4a` default). Progressive
transfers go through `RangedDownloader`, which resumes from the part's length, retries
`CONFIG_DOWNLOAD_RETRIES` times with a 1–8 s backoff, and treats `416` as "already complete".
The budget is clamped to 1..10 and resolved once per task, so both renditions of a merge share
it instead of multiplying the wait; it is editable in the config screen.

`DownloadFolder.resolve` turns `CONFIG_DOWNLOAD_DIR` into a `DownloadTarget`:

- a `content://` tree → `SafTarget` (`DocumentFile`, the only writer for SD cards/cloud);
- `file://` or API 26–28 → `FileTarget` under shared storage;
- anything else, API 29+ → `MediaStoreTarget`, whose configured root is validated per
  collection (`Movies`, `DCIM`, `Pictures` for video; `Music` and friends for audio;
  `Download` for the rest) and whose audio insert retries in `MediaStore.Downloads` (D42).

Every writer reports `DownloadedFile` with a content URI, so "open", "share" and "delete" work
the same for all three. Deletion goes through `DownloadRepository.deleteFile`, which finds the
owning task by path, then by unique file name, removes the archive entry and clears the output
path while keeping the queue row.

Local playback is a URI, not a download: `PlaybackActions.playLocal` hands the file's content
URI to the same `PlaybackService`, and `PlaybackCoordinator` skips resolution for
`content://`/`file://` sources.

## Storage and the documents provider

`RivoDocumentsProvider` exposes `filesDir` to the Storage Access Framework with a
`Base64`-encoded document id per entry and a canonical-path check that rejects traversal
(`resolve()` compares against `filesDir.canonicalPath`). This is what makes "the config is
a text file" true on a phone: DocumentsUI, a desktop MTP browser or `adb` can open
`config`, and the GUI writes the same file.

Cookies are not files in `filesDir`: `CookieRepository` keeps them in
`EncryptedSharedPreferences` (KeyStore-backed), which the provider never exposes. Names
starting with `.` plus `cookies`/`no_backup` are filtered from listings anyway, so a future
plain-text fallback cannot leak through the DocumentsProvider.

The library lives in `databases/rivo-library.db`, outside the provider's root, with exported
schema JSON in `app/schemas` and no destructive migration (D30). A corrupt or
schema-mismatched database fails loudly on open instead of silently dropping user data.

## Settings, extensions and Android surfaces

F7 makes the app configurable without a text editor and reachable without the launcher.

### The SAF extension folder

`ExtensionFiles` owns the one persisted tree URI and the document I/O: it lists the folder,
enforces per-file and per-run size caps, and dispatches by extension to the `.ui`, `.lang`,
`.theme`, `.sites` and `.cmds` parsers. `ExtensionRepository` merges the results into the
menu snapshot: loadable theme and language overlays, site actions, command definitions and
menu patches, plus a per-file report (`name`, `kind`, `count`) and accumulated issues for the
settings screen. Loading happens off the main thread at startup and after choosing, reloading
or forgetting the folder; `CONFIG_EXTENSIONS_ENABLED` short-circuits it and
`CONFIG_AUTOLOADED_EXTENSIONS` filters by base name. Nothing is executed: a document can only
name existing handler ids (D43).

### Custom commands and sites

Local commands live in `files/my/cmds.json` behind `CustomCommandStore`; extension commands are
merged after them and marked read-only. A draft command is a three-stage `Prompt` sequence
carried in `MenuPayload.CommandDraft` (name, URL, optional yt-dlp options), so the menu stays
stateless between answers. Run resolves the URL through `MediaLinkResolver` and pushes the
ordinary media actions frame; "no extractor for this site" is a message, not an exception.
Extension sites become ordinary action rows: a prompt expands `{query}` into the URL and opens
it through the same `open_url` handler every other link uses (D44).

### Settings without a second menu system

Config rows are regular `MenuNode`s: `BuiltInMenus.config()` builds them from
`ConfigSnapshot`, and every write goes through `ConfigStore.edit`, which preserves comments and
unknown keys and refreshes the snapshot the menus read. Value pickers are child menus with a
check on the current value, so filtering and previewing work there too.

`ConfigEditorActivity` is a deliberate exception: a full-screen text editor does not fit the
list model. It reads `files/config` through the same store and writes it back on Save,
leaving comments intact. The cookies screen is also a menu; the WebView sign-in stays a
separate activity because it hosts a browser.

### Update checks and cache retention

`UpdateRepository` (with `ReleaseFeed`, `VersionNumbers` and `ReleaseSelection`) turns GitHub
releases from `iseaprojects/rivo` into a `StateFlow<UpdateState>`; `AppGraph` flattens the
current value into `MenuContext.updates` so the update screen stays synchronous and testable.
`VersionNumbers` compares the `0.8.7-rivo.N` form, including dotted pre-release identifiers,
so a revision bump is a real update. It never downloads an APK (D45).

`CacheCleanupWorker` runs as unique periodic work and calls `CacheCleaner.clean`, which walks
`cacheDir` and deletes entries older than the configured days, skipping `cache/downloads` so a
paused transfer keeps its part. Choosing a new retention writes the config and reschedules the
worker; "Clear cache now" sweeps everything else immediately (D46).

### Android surfaces are adapters

`RivoTileService`, `RecentWidgetProvider`, `ShortcutPublisher`, the static shortcut XML and
`TaskerFireReceiver` all build a `rivo://` link and start `MainActivity`; none of them inspects
menus or calls handlers (D48). The widget queries `library.recentsOnce(4)` through `goAsync`
because a widget update has a short broadcast window, and every row gets a per-item pending
intent to `rivo://media?url=...`. The Tasker receiver forwards `RIVO_*` extras only, and falls
back to a notification when the platform refuses a background activity start (D47).

## Components declared per phase

`AndroidManifest.xml` only declares a component once its phase implements it, because the
system resolves every declared component during package scan:

| Component | Lands with |
| --- | --- |
| `MainActivity`, `RivoDocumentsProvider` | F1 |
| `WebLoginActivity` | F2 |
| `PlayerActivity`, `PlaybackService` | F5 |
| `DownloadService` | F6 |
| `RivoTileService`, `TaskerFireReceiver`, `RecentWidgetProvider`, `ConfigEditorActivity` | F7 |

F1 originally declared the whole list up front. That broke the app: the package scan probed
`PlaybackService` as a media browser service, found no class, and killed the process before
it drew its first frame (see `docs/VERIFICATION.md`, F3 defects). Declaring a component in
the same change that adds its class keeps the manifest honest and lets lint's
`MissingClass` check stay enabled.

## Error handling

- Config and asset reads never throw into the UI; a missing or corrupt file degrades to
  defaults (`ConfigStore.FALLBACK_DEFAULTS`, `ThemeSpec.FALLBACK`, English strings).
- Extension parsing collects issues (`UiExtension.issues`) instead of aborting; they are
  logged at startup with the `rivo` tag and surfaced in the settings screen later.
- Menu handlers return `MenuResult.Message(isError = true)` for user-visible failures and
  `Unhandled` for missing wiring.
- Network and extractor failures are typed (`ExtractorException.kind`) and map to
  `Availability` values, so the same video can explain *why* it cannot be played instead of
  failing generically. Signed-URL expiry is `FORBIDDEN` with `looksLikeExpiredUrl`, which
  tells the player to re-resolve rather than show an error.

## Testing

`app/src/test` covers the pure layers:

| Test | Covers |
| --- | --- |
| `ConfigParserTest` | shell-compatible parsing, comment-preserving writes, quoting, override name mapping, snapshot defaults |
| `MenuPatcherTest` | sort by id/index, `dropUnlisted`, regex filter, all injection positions, `rivo.ui/1` parsing, broken-extension and invalid-regex handling |
| `LaunchSpecParserTest` | extras → `LaunchSpec`, `RIVO_CONFIG` key filtering, deep-link grammar, share-sheet routing, watch URL normalization |
| `ColorHexTest` | `#RGB`/`#RRGGBB`/`#AARRGGBB`, invalid input, terminal color fallback |
| `UrlCanonTest` | every accepted URL shape, cache-key stability, channel/playlist normalization |
| `CookieTxtTest` | Netscape/JSON parsing, RFC 6265 origin scoping, export round-trip |
| `PoTokenTest` | both `CONFIG_PO_TOKEN` syntaxes and the yt-dlp extractor-arg form |
| `YtdlpJsonMapperTest` | recorded `-J` JSON: metadata, formats, subtitles, chapters, flat lists |
| `CommandLineTest` | POSIX quoting for `CONFIG_YT_DLP_OPTS` |
| `ExtractorFailureTest` | status/exception/stderr → `FailureKind`, expired-URL detection |
| `ExtractorRouterTest` | capability merging, forced-backend behavior, fast flavor without yt-dlp |
| `LibraryRepositoryTest` | save/unsave idempotence, playlist create/rename/delete, item order, recents trimming, search-history cap and recall, subscription replace, change emissions |
| `LibraryKeysTest` | provider-scoped keys, URL fallback, `!N` recall parsing (including `!0`, `!-1`, `!abc`) |
| `LibraryMenusTest` | Save/UnSave and Subscribe/Unsubscribe row selection from the snapshot, custom playlist counts, add-to-playlist picker, remove-from-playlist presence |
| `LibraryHandlersTest` | the two-step Prompt/Confirm protocol: prompt payloads, trimmed values, confirmation sentinel, live title re-read, idempotent add, subscribe round trip |
| `MediaMenusTest` | per-item action rows for videos, channels, playlists and local collections |
| `ExtensionFileParserTest` | site and command documents, prompt templates, duplicate ids, broken JSON |
| `VersionNumbersTest` | tag ordering, prerelease rules, release selection, feed parsing |
| `CacheRetentionTest` | retention clamping, day cutoff, files exactly at the cutoff |
| `SettingsMenusTest` | commands, sites, extensions, cookies and update screens as data |

`NewPipeLiveTest` is opt-in (`RIVO_LIVE=1`) and talks to the real site; it is skipped in the
default run. Everything above the pure layers needs an emulator or device; the per-phase
walkthroughs in `docs/VERIFICATION.md` are that layer's checklist (navigation, filter,
playback, download resume, SAF round-trip, extensions, tile, widget, automation).
