# Parity with yt-x 0.8.7

Feature-by-feature mapping from upstream to the port. `Done` means the mechanism is in the
repository and wired; `Partial` means the contract exists but the feature is completed in a
later phase; `Planned` names the phase that lands it.

Status reflects the repository after **F7**.

## Entry points and navigation

| Upstream | Android | Phase | Status |
| --- | --- | --- | --- |
| Run `yt-x` in a terminal | Launcher icon → `MainActivity` | F1 | Done |
| `yt-x --play <url>` and friends | `am start` extras / `LaunchSpecParser` | F1 parse, F5 execute | Done |
| `YT_X_*` environment overrides | `RIVO_*` intent extras → `RuntimeConfig` | F1 | Done |
| Interactive prompt per screen | Always-focused filter field on every list | F3 | Done |
| `fzf` filtering as you type | `Fuzzy` over label/detail/search text | F3 | Done |
| Pagination via `fzf` scroll | Explicit Next/Previous, `CONFIG_PER_PAGE=30`, `PageWindow` re-chunks the backend feed | F3 | Done |
| Back to previous menu | Breadcrumb bar + system back + `MenuFrame` stack | F1 | Done |
| Quit from a menu | `Exit` row and back-at-root | F1 | Done |
| Preview pane (`fzf --preview`) | Expandable bottom sheet (phone) / side panel (tablet, DeX); `space` binding plus a toolbar toggle on phones | F3 | Done |
| Preview images | Coil image in the preview panel, `CONFIG_ENABLE_PREVIEW_IMAGES` | F3 | Done |
| Nerd Font icons | Bundled glyphs from `.lang` keys, `CONFIG_NERD_FONTS` | F1 | Done |
| Icon fallback without a patched font | `IconCatalog` ASCII map | F1 | Done |
| Terminal keybindings (fzf) | `KeyBindings` parser (`CONFIG_KEYBINDINGS`), vim mode when the filter is not focused | F3 | Done |
| Rofi launcher | not applicable on Android | — | Dropped (see below) |

## Menus

| Upstream | Android | Phase | Status |
| --- | --- | --- | --- |
| Main menu (feed, search, subs, watch later, liked, history, clips, playlists, channels, misc) | `BuiltInMenus.main()`, port order | F1 | Done |
| Trending entry | `feed.trending` node present; YouTube retired the feed (HTTP 400), extractor reports `REMOVED` | F1/F2 | Partial |
| Your YouTube playlists | `Playlists` menu opens with `Your YouTube playlists` → `/feed/playlists`; needs cookies and a backend that reads account feeds | F4 | Partial (ytdlp) |
| Miscellaneous menu | `BuiltInMenus.miscellaneous()` | F1 | Done |
| Edit config (`mpv.conf`, `yt-dlp.conf`, `config`) | Config menu + full-text editor activity, plus the GUI settings screen writing the same file | F1/F7 | Done |
| Custom playlists menu | `BuiltInMenus.customPlaylists()` over Room: create, rename, delete, add/remove items | F4 | Done |
| Saved videos / Recent | `library.saved` / `library.recent` data screens over Room, `CONFIG_NO_OF_RECENT` | F4 | Done |
| Search history / edit search history | `SEARCH_HISTORY` rows re-run the query; `Edit histories` clears search history and recents, each behind a confirm | F4 | Done |
| Custom commands | `.cmds.json` extensions plus user commands: name, URL, optional yt-dlp options; Run resolves the link, Termux receives the options (D44) | F7 | Done |
| Sync subscriptions | `MISC → Sync`, confirmed replace from `/feed/channels`; needs cookies and `CONFIG_ACCOUNT_FEEDS`, so the fast flavor explains instead | F4 | Partial (ytdlp)|
| Downloads browser | `DOWNLOADS` menu: queue rows, file rows, pause/resume/retry/cancel/remove/clear, local playback, share, delete, open folder | F6 | Done |
| Update | GitHub releases of Rivo, six-hour floor, release page link, no installer (D45) | F7 | Done |
| `.ui` extension sort/filter/inject | `MenuPatcher` + `rivo.ui/1` loader | F1 | Done |
| Extension-provided handlers | handler ids resolve against a built-in registry; extensions cannot ship code (D43) | F1/F7 | Partial - declarative only |

## Search

| Upstream | Android | Phase | Status |
| --- | --- | --- | --- |
| Search with YouTube `sp` filters | `SearchFilters` tokens generate the same `sp` codes | F3 | Done |
| Date filters `:hour :today :week :month :year` | chips, one active (switching replaces the previous one) | F3 | Done |
| Type filters `:video :movie :live :short :long` | chips, one active | F3 | Done |
| Quality filters `:4k :hd :hdr :subtitles :360 :vr :3d :local` | chips, one active | F3 | Done |
| Sort filters `:newest :views :rating` | chips, one active | F3 | Done |
| Search history recall `!1`, `!2` | `LibraryKeys.recallIndex` → Room history, recorded on every submit | F4 | Done |
| Autocomplete | local history matches first, then live extractor suggestions (250 ms debounce), capped at 8 | F3/F4 | Done |
| Search channels / playlists / shorts / movies | `MenuPayload.Search.kind` → `SearchKind` | F3 | Done |

## Channels

| Upstream | Android | Phase | Status |
| --- | --- | --- | --- |
| Channel action menu | `MediaMenus.channelActions()` (`channel_actions`) | F3 | Done |
| Tabs: videos, featured, playlists, shorts, live, podcasts | `ChannelTabRow` over the extractor's tabs; `MenuPayload.Channel.tab` deep-links into one | F3 | Done |
| In-channel search | `Search` row and toolbar icon open the search frame scoped to the channel URL (`<channel>/search?query=`); yt-dlp extracts it, the fast flavor reports it as unsupported | F3 | Done |
| Local subscribe / unsubscribe | Room subscriptions; `CHANNELS` lists them and opens `channel_actions` | F4 | Done |
| Open channel in browser | `app.open_url` handler | F1 handler, F3 entry | Done |

## Media actions

| Upstream | Android | Phase | Status |
| --- | --- | --- | --- |
| Watch | in-app Media3 player | F5 | Done |
| Listen | audio-only background playback | F5 | Done |
| Download / Download audio | queue + `DownloadService`, `CONFIG_AUDIO_FORMAT`, MediaStore/SAF/directory target | F6 | Done |
| Save / Unsave | Room `saved_video`, row relabels from the library snapshot | F4 | Done |
| Add to playlist / remove from playlist | picker menu (`ADD_TO_PLAYLIST`), removal row on items opened from a custom playlist | F4 | Done |
| Play all / Listen all / Download all | queue over a playlist payload; download pages the whole playlist | F5 play/listen, F6 download | Done |
| Save playlist | Room `saved_playlist`; every playlist row opens the actions menu, where Save/UnSave toggles and `PLAYLISTS` lists the saved ones | F4 | Done |
| Mix | generated mix URL, played as a playlist queue | F5 | Done |
| Open in browser | `app.open_url` | F1 | Done |
| Copy URL | `app.copy_url` (ClipboardManager) | F1 | Done |
| Share | `app.share_url` (`ACTION_SEND`) | F1 | Done |
| External player | resolved stream URL to the configured package or the chooser; `CONFIG_EXTERNAL_PLAYER`, `CONFIG_EXTERNAL_FORMAT` | F5 | Done |
| Shell (Termux `RUN_COMMAND`) | `com.termux.RUN_COMMAND` with `YT_X_URL`, `YT_X_TITLE`, `YT_X_ID`, `YT_X_STREAM` | F5 | Done |
| Subscribe to channel | Room subscriptions, from the video and channel action menus | F4 | Done |
| Toggle enumerate downloads | writes `CONFIG_DOWNLOADS_ENUMERATE`, read by `DownloadNaming.fileName` | F1 (config), F6 (effect) | Done |

## Playback

| Upstream | Android | Phase | Status |
| --- | --- | --- | --- |
| `mpv` playback | ExoPlayer, `PlayerActivity` + `PlaybackService` | F5 | Done |
| DASH video+audio (broken workaround upstream) | `MergingMediaSource(video, audio)`; manifests left to Media3 | F5 | Done |
| Live streams | DASH/HLS manifest, never the muxed DVR snapshot | F5 | Done |
| Background audio | `MediaSessionService`, notification, Bluetooth | F5 | Done |
| Android Auto | media browser service filter and `MediaLibrary`-less session | F5 | Partial — declared and reachable; no Auto host in the emulator |
| Picture-in-Picture | `PlayerActivity`, `TextureView` surface | F5 | Done |
| Keep browsing while playing | separate activity + session, `CONFIG_DISOWN_PLAYER` | F5 | Done |
| Subtitles | `MediaItem.SubtitleConfiguration`, `CONFIG_SUBTITLES_LANGS` pre-select | F5 | Done |
| Chapters | `ChapterSheet`, chapter ticks on the timeline; description chapters from the extractor (D37) | F5 | Done |
| SponsorBlock | category segments, `CONFIG_SPONSORBLOCK_CATEGORIES`, auto-skip + timeline marks | F5 | Done |
| Storyboards / heatmap | "most replayed" heatmap drawn into the timeline | F5 | Partial — heatmap yes, hover thumbnails no (touch UI) |
| Speed 0.25×–4× | speed sheet | F5 | Done |
| A–B loop | two-tap loop marker, 250 ms enforcement | F5 | Done |
| Audio/subtitle delay | not exposed | F5 | Deliberate omission |
| Track selection | audio track sheet (grouped by track, D33), subtitle sheet | F5 | Done |
| External player | `CONFIG_PLAYER=external`, `CONFIG_EXTERNAL_PLAYER`, `CONFIG_EXTERNAL_FORMAT` | F5 | Done |
| Termux shell | `com.termux.RUN_COMMAND` with `YT_X_*` extras | F5 | Done |
| Queue | request length carried in extras, lazy prefetch window | F5 | Done |

## Downloads

| Upstream | Android | Phase | Status |
| --- | --- | --- | --- |
| `video/individual/<Channel>/<title>.<ext>` | same relative layout under the configured target (MediaStore by default, SAF tree or plain directory) | F6 | Done |
| `video/<Playlist>/<Channel>/…`, `audio/<Playlist>/<Channel>/…` | same, playlist title carried on the queued item (`MediaSummary.playlistTitle`) | F6 | Done |
| `$enumerate%(title)` | `CONFIG_DOWNLOADS_ENUMERATE` (`01 - `, `02 - …` inside one folder) | F6 | Done |
| Duplicate names get ` [<video id>]` before the extension | same, when the folder already holds that name and the archive is off | F6 | Done |
| Download archive | `kind<TAB>videoId` in `files/downloads/archive.txt`, no playlist URL in the key (D40) | F6 | Done |
| `CONFIG_DOWNLOAD_DIR=$HOME/Videos/yt-x` | storage-relative root, default `Movies/Rivo`; MediaStore, SAF tree or plain directory by shape (D42) | F6 | Done |
| `yt-dlp` resumable transfer | ranged requests + `CONFIG_DOWNLOAD_RETRIES`; a paused task keeps its part | F6 | Done |
| Queue / pause / progress | persisted `queue.json` + `dataSync` foreground service, aggregate notification | F6 | Done |
| Wi-Fi only | `CONFIG_DOWNLOAD_WIFI_ONLY`, task held at `QUEUED`, "Waiting for Wi-Fi" notification | F6 | Done |
| Muxing | `MediaMuxer` joins MP4 video + MP4 audio, no re-encode (D41) | F6 | Done |
| Audio-only downloads | `CONFIG_AUDIO_FORMAT` (`m4a` default, Opus selectable) (D41) | F6 | Done |
| HLS/DASH manifests | handed to the yt-dlp binary when the flavor ships it, reported otherwise | F6 | Done |
| Downloaded files menu | in-app list, local playback, share, open with, delete, open folder | F6 | Done |
| Cache retention | `CONFIG_CACHE_RETENTION_DAYS` periodic WorkManager sweep, manual clear (D46) | F7 | Done |

## Data and accounts

| Upstream | Android | Phase | Status |
| --- | --- | --- | --- |
| `my-recent.json` (10 entries) | Room `recent_video`, `CONFIG_NO_OF_RECENT` trims on write | F4 | Done |
| `my-videos.json` (saved) | Room `saved_video` | F4 | Done |
| `my-playlists.json` | Room `custom_playlist` + `playlist_item` (FK CASCADE) | F4 | Done |
| `search-history.json` | Room `search_history`, case-folded key, 50-entry ceiling | F4 | Done |
| `my-subs.json` | Room `subscription` | F4 | Done |
| Browser cookie extraction | local cookies.txt import, WebView login, encrypted storage, per-origin scope | F2/F7 | Done |
| PO token | `CONFIG_PO_TOKEN`, both config syntaxes, NewPipe provider + yt-dlp extractor args | F2 | Done |
| yt-dlp on non-YouTube sites | `ytdlp` flavor, `CONFIG_EXTRACTOR` routing | F2 | Done |
| Typed extraction failures | `FailureKind` mapped to `Availability` for every screen | F2 | Done |
| Expired stream URLs | router TTL (`CONFIG_RESOLVE_MAX_AGE_HOURS`) + `fresh()` re-resolve on 403 | F2 | Done |
| yt-dlp binary updates | `CONFIG_YTDLP_CHANNEL` self-update endpoint, UI in the extractor screen; only the ytdlp flavor ships a binary | F2/F7 | Partial - shipped, not device-exercised |
| Account feeds (subscriptions, history, watch later, liked, playlists) | `AccountFeed` → `ExtractorRouter.accountFeed()`, gated on `CONFIG_ACCOUNT_FEEDS` + stored cookies; yt-dlp only, NewPipe reports `UNSUPPORTED` | F4 | Partial |

## Extensions and appearance

| Upstream | Android | Phase | Status |
| --- | --- | --- | --- |
| `.lang` files | `rivo.lang/1` JSON, `es`/`pt-BR` shipped, `CONFIG_LANG` | F1 | Done |
| `.theme` / `THEME_FZF_*` | `*.theme.json`, Tokyo Night default, Catppuccin included | F1 | Done |
| `.ui` menu patches | `rivo.ui/1`, asset + SAF sources, autoload list, reload/forget | F1/F7 | Done |
| `.sites` extensions | declarative site descriptors with prompts and URLs | F7 | Done |
| `.cmds` extensions | declarative command definitions, read-only in the UI | F7 | Done |
| Sandboxed custom handler code | not shipped: extensions are data, never code (D43) | - | Dropped |
| `CONFIG_EXTENSIONS_ENABLED`, `CONFIG_AUTOLOADED_EXTENSIONS` | honored for asset and SAF extensions; toggling unloads overlays, forgetting the folder clears it | F1/F7 | Done |

## Integration beyond the CLI

| Upstream | Android | Phase | Status |
| --- | --- | --- | --- |
| — (no equivalent) | `rivo://` deep links | F1 | Done |
| — | `ACTION_VIEW` YouTube links | F1 | Done |
| — | `ACTION_SEND` text/URL into search or media actions | F1 | Done |
| — | Unreadable links report the extractor's verdict instead of a generic message (D39) | F5 | Done |
| — | Static launcher shortcuts | F1 resources, actions open the same frames | F7 | Done |
| — | Dynamic shortcuts, QS tile | shortcuts for the first four custom commands; tile opens `rivo://search` (D48) | F7 | Done |
| — | Tasker/automation broadcast | `TASKER_FIRE` carries a `rivo://` link; direct launch needs the overlay permission, otherwise a tappable notification (D47) | F7 | Done |
| — | Recent-videos widget | four rows from Room, tap opens actions, title opens Recent (D48) | F7 | Done |
| — | GitHub release update check | release tag, name, date and notes; opens the release page (D45) | F7 | Done |
| — | Plain-text config exposed over SAF | F1 | Done |

## Deliberate differences

- **Playlists are actions-first.** Upstream prompts for playlist actions and one of them is
  Save; a playlist row here opens that actions menu too, with "Open playlist" as the first
  row so the contents are one tap away. "Play All" becomes the primary row in F5, when it
  can do something.
- **`Your Feed` has no source.** YouTube's recommendation feed is an InnerTube construct that
  neither extractor exposes to third-party clients. The row stays (parity), the frame names
  the reason, and Trending / Subscriptions / Search cover the intent.
- **No launcher or terminal integration.** Rofi, `fzf` bindings and terminal image
  renderers (`icat`, `chafa`, `viu`) have no Android equivalent; their role is taken by
  Compose, the preview panel and Coil.
- **No infinite scroll.** Upstream pages; so does the port, explicitly.
- **Progressive-only playback upstream workaround is gone.** DASH video and audio are
  merged instead of forcing a muxed 720p stream.
- **`CONFIG_LAUNCHER`, `CONFIG_PLAYER`, `CONFIG_EDITOR`** are accepted and preserved in the
  config file so an imported file round-trips, but they do not select anything on Android.
- **Play Store.** Not distributed there; see the distribution note in the README.
- **The archive key is `kind<TAB>videoId`, not "kind plus playlist URL".** The playlist a
  video was listed in does not change the file, and keying by it would re-download the same
  video from the next playlist it appears in (D40).
- **A configured download folder is a root per media collection.** `Movies/Rivo` files video
  under `Movies/Rivo`, audio under `Music/Rivo` and anything else under `Download/Rivo`,
  because `MediaStore` validates the primary directory (and the MIME type) per collection
  (D42). The config row shows both resolved roots.
- **Downloads live in the app, not only in a folder.** The queue, pause/resume, progress,
  the file list, local playback, share and delete are screens; yt-x hands the download to
  `yt-dlp` and prints its progress.
