# Changelog

All notable changes to Rivo are recorded here. The version keeps the upstream base it was
ported from (`0.8.7`) plus a Rivo revision.

## 0.8.7-rivo.4 - 2026-11-20

### Fixed

- SponsorBlock works. The lookup decoded `/api/skipSegments?videoID=` as if it answered
  `[{videoID, segments: [...]}]`, but that endpoint returns a flat array of segments; the
  unknown wrapper decoded into an empty list without an error, so no video ever had a skip or
  a timeline band. Segments now load before the item is built, so the bands are drawn on the
  first pass instead of after a quality change.
- Subtitles render. Every resolved stream is built by `StreamMediaSourceFactory`, which never
  consulted the item's `SubtitleConfiguration` list — the captions sheet listed languages and
  the chip flipped, but no caption appeared. The sidecars are merged in the same way
  `DefaultMediaSourceFactory` does it.
- Next/Previous work during the first seconds of a playlist. Both were gated on
  `hasNextMediaItem()`, which only covers the prefetch window, so a queue of 26 items showed
  "1 of 26" with a dead Next button until item 2 finished resolving. Next now asks the service
  to resolve the next item when the window is empty, and the end-of-item prefetch waits for an
  in-flight pass instead of returning early.
- One flaky retry no longer disables recovery for the rest of an item, and a retry that fails
  to resolve a fresh URL advances the queue instead of leaving the player parked on an error.
- Seeking with an unknown duration no longer jumps to the start: a chapter tap used to clamp
  against a zero duration, so the position moved to 0 and then disagreed with the player.
- "Resume at" and the title survive handoff to VLC, which gets its documented `position`,
  `from_start` and `title` extras.
- The update checker honours its six-hour window across cold starts. The window lived only in
  the state object, so every launch hit the GitHub API; the last successful check is now stored
  in preferences, and a pending release is deliberately not stored so it is re-checked.
- A corrupt or unreadable download queue file is moved aside as `queue.json.corrupt` instead of
  being treated as an empty queue and overwritten by the next save.
- The download archive serializes its cache and its lazy load, so the service and the library
  screens cannot race on the same set.
- Empty cache directories are removed. Deleting their contents bumped the directory's mtime, so
  the age check never matched and they stayed forever.
- Removing an item that is not in a playlist no longer bumps the playlist's `updatedAt`.
- `DownloadNaming` caps generated file names by bytes (180, extension included) and resolves
  collisions in one place; a long title no longer produces a name the filesystem rejects.
- A `416` on resume drops the stale `Range` header and restarts the transfer instead of failing
  the task; `CountFormat.bytes` picks the unit after rounding (`1023.6 KB` became `1024.0 KB`
  instead of `1.0 MB`); the filter's position penalty is bounded so a late match cannot outrank
  a prefix match.
- `MediaListState` ignores a stale page result that lands after the request changed, and
  `PlaybackCoordinator` trims recents with the same ceiling the browse screen reads.
- `RIVO_NO_PREVIEW*` actually disables previews (the flag was inverted), `readConfig()`
  recreates a deleted config file, the parser strips a UTF-8 BOM, and `decode()` applies escape
  sequences in one pass instead of re-escaping.
- Downloads: `cancelNow` only relabels a `RUNNING` row, and `pause` writes `PAUSED` before it
  stops the worker, so a crash between the two cannot resume a paused task on next start.

### Changed

- Long transfers use a dedicated HTTP client without a whole-call deadline; the extractor
  client keeps its 45-second ceiling.
- Subscription sync inserts first and deletes stale rows afterwards, so an interrupted sync
  cannot leave the library empty.
- `docs/PARITY.md` documents `CONFIG_VLC_ARGS` as inert and explains that a VLC/MX handoff
  cannot carry the remote subtitle sidecars; `docs/DECISIONS.md` D45 now describes the check
  that exists (this repository's releases, a persisted interval).

## 0.8.7-rivo.3 - 2026-11-20

### Fixed

- The filter folds diacritics, so `cancion` finds "Canción" and `videoaula` finds "Videoaula";
  the search-screen suggestions use the same folding. Case folding is locale-independent now.
- `CONFIG_DOWNLOAD_RETRIES` is read from the config instead of being documented as honored
  while every transfer tried three times. It is clamped to 1..10, shared by both tracks of a
  merged download, and editable from the config screen.

### Changed

- Retry backoff stops growing at 8 seconds, so a large budget cannot stall a queued item.
- `docs/PARITY.md` names the upstream config keys that are parsed but inert, and why.

## 0.8.7-rivo.2 - 2026-11-19

First release under the Rivo name. Functionally it is the completed F1-F8 Android port of
[yt-x](https://github.com/Benexl/yt-x) 0.8.7.

### Added

- Filterable nested-menu explorer with previews, explicit paging, breadcrumbs and the
  upstream keybindings.
- NewPipeExtractor and yt-dlp flavors behind one extractor interface, with typed failures,
  signed-URL refresh, cookies, PO tokens and URL canonicalisation.
- Media3 player: progressive/DASH/HLS playback, background audio, MediaSessionService,
  picture-in-picture, subtitles, chapters, SponsorBlock, queues and external-player handoff.
- Downloads: MediaStore, SAF and plain-directory targets, persisted queue, foreground
  service, ranged resume, MP4 muxing, archive and local playback.
- Settings menus over the plain-text `config`, cookies import and WebView login, extractor
  choice, cache retention, update checks and declarative extensions from a SAF folder.
- Custom commands as links, mirrored as dynamic shortcuts.
- Quick Settings tile, recents widget, static shortcuts and a Tasker broadcast, all entering
  through the `rivo://` deep-link router.
- English, Spanish and Brazilian Portuguese from the first build.

### Changed

- Project renamed from the working title "yt-x for Android" to **Rivo**.
- Application id is now `io.github.iseaprojects.rivo`; the deep-link scheme is `rivo://`.
- Default download roots are `Movies/Rivo`, `Music/Rivo` and `Download/Rivo`.
- Update checks read this repository's releases instead of the upstream ones; the upstream
  repository stays credited in the about screen. A tag like `0.8.7-rivo.10` now sorts above
  `0.8.7-rivo.9`, and a repository with no releases reports "Up to date" instead of a failure.
  The feed is read unauthenticated, so the repository has to be public for the check to work.
- `tools/publish.sh` builds both flavors, packs them as `rivo-<version>-fast.apk` and
  `rivo-<version>-ytdlp.apk`, tags the commit and publishes the release from
  `docs/releases/<version>.md`.

### Fixed during the F7/F8 verification

- Cache worker no longer crashes the process on boot by re-entering `AppGraph`.
- Optional prompt stages accept an empty value.
- All string keys exist in the three language packs; the Spanish pack parses.
- Config editor content clears the status bar.
- Tasker uses the overlay permission for a direct launch and a notification otherwise.
- `REQUEST_INSTALL_PACKAGES` removed: the update checker only opens the release page.
- Backup and device-transfer rules now name the real library database and cookie prefs file.
