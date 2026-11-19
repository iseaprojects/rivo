# Changelog

All notable changes to Rivo are recorded here. The version keeps the upstream base it was
ported from (`0.8.7`) plus a Rivo revision.

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
