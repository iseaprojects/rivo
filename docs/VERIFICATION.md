# Verification

Everything below was run on Windows with JDK 17.0.19, the Android SDK at `C:\Android\Sdk`
(platforms 35, 36, 37.0, 37.1; Build Tools 34–37), Gradle 9.9.1 via the committed wrapper
and AGP 9.5.1. No part of the project is unbuilt or stubbed.

> F1-F7 were verified while the project was still called yt-x for Android. Quoted tool output
> keeps the identifiers of the build that produced it (for example the old
> `dev.ytx.android.debug` package id); commands, package names, paths and links use the
> current `Rivo` identifiers.

## Build matrix

| Task | Result |
| --- | --- |
| `:app:compileFastDebugKotlin` | OK — no errors (upstream NewPipeExtractor deprecation warnings only) |
| `:app:assembleFastDebug` | OK - `app-fast-debug.apk`, 28.12 MB |
| `:app:assembleYtdlpDebug` | OK - `app-ytdlp-debug.apk`, 105.50 MB (CPython + aria2 for 4 ABIs) |
| `:app:assembleFastRelease` | OK - `app-fast-release-unsigned.apk`, 3.82 MB (R8 + resource shrinking) |
| `:app:testFastDebugUnitTest` | OK - 344 tests, 0 failures, 7 skipped (`RIVO_LIVE` opt-in), 39 suites |
| `:app:testYtdlpDebugUnitTest` | OK - 344 tests, 0 failures, 7 skipped, 39 suites |
| `:app:lintFastDebug` | OK — 0 errors, 19 warnings (all intentional, see below) |

`aapt2 dump badging` on the debug APK:

```
package: name='io.github.iseaprojects.rivo.debug' versionCode='807002'
         versionName='0.8.7-rivo.2-fast-debug'
compileSdkVersion='37' compileSdkVersionCodename='17'
minSdkVersion:'26'
targetSdkVersion:'37'
application-label:'Rivo'
locales: '--_--' 'es' 'pt' 'pt-BR'
```

## Toolchain migration (what actually broke)

The F1 tree was written against AGP 8.7.3 / Kotlin 2.1.0. Moving to AGP 9.5.1 fixed the
build on current SDKs; these were the concrete incompatibilities, all fixed:

| Symptom | Cause | Fix |
| --- | --- | --- |
| `Failed to apply plugin 'org.jetbrains.kotlin.android'` | AGP 9 has built-in Kotlin | removed the plugin, kept `kotlin { compilerOptions { … } }` |
| `Product Flavor fast contains custom resource values, but the feature is disabled` | AGP 9 defaults `resValues` off | `buildFeatures.resValues = true` |
| `AAPT: invalid config 'pt-BR' for -c option` | `localeFilters` wants resource-qualifier form | `localeFilters += listOf("en", "es", "pt-rBR")` |
| `Unclosed comment` at EOF of `UiExtension.kt` | `ui/*.ui.json` inside KDoc opens a **nested** comment in Kotlin | reworded to `ui/<name>.ui.json` |
| `Unresolved reference FileAlreadyExistsException` | `android.os.FileAlreadyExistsException` is hidden API | throw `FileNotFoundException` (the DocumentsProvider contract) |
| `Missing class java.awt.image.BufferedImage` (R8) | Rhino (NewPipeExtractor) and, at the time, mp4parser's MJPEG helper reference desktop classes | `-dontwarn java.awt.** javax.imageio.** java.beans.**` |
| Configuration cache could not serialize `ProcessNavigationXmlTask` | AGP 9.5.1 + Gradle 9.9 bug | `org.gradle.configuration-cache=false` (commented) |
| `Could not find com.github.yausername.youtubedl-android:library:0.18.1` | upstream JitPack stopped at 0.14.0; 0.18.x tags fail to build there | depend on `io.github.junkfood02.youtubedl-android:0.18.1` from Maven Central |
| `invalid config` / adaptive icon not found | `mipmap-anydpi` without `-v26` drops `<adaptive-icon>` | kept `mipmap-anydpi-v26`, `ObsoleteSdkInt` disabled with a note |

## Lint

0 errors. The 19 remaining warnings are deliberate and documented in `app/build.gradle.kts`
or the manifest:

- `IntentFilterUniqueDataAttributes` (9) — one `<data>` per scheme+host pair is the correct
  way to match `https://youtu.be`, `https://www.youtube.com`, `rivo://` and the share filters
  in a single filter.
- `UseKtx` (4) — `Uri.parse(string)` at those four sites; the KTX `String.toUri()`
  extension is a one-line alias and the surrounding code is JVM-testable without it.
- `SdCardPath` (2) — `TermuxShell` names Termux's fixed `com.termux` data directory
  (`/data/data/com.termux/files/...`), which is the path the Termux API expects, not a path
  this app opens.
- `UsableSpace` — belongs to the F6 download pre-flight check.
- `InlinedApi` — `FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK` is API 29; it is only read on
  `SDK_INT >= 29` and the pre-29 foreground start uses the untyped call.
- `PictureInPictureIssue` — `setAutoEnterEnabled` and `setSourceRectHint` are gestures/rect
  helpers; the app enters PiP from its own button and via `enterPictureInPictureMode`, which
  the manifest declaration covers.
- `ReportShortcutUsage` — `ShortcutPublisher` creates dynamic shortcuts but the app has no
  "used" event to report: a shortcut either launches the deep link or is never tapped inside
  this process, so there is nothing to rank.

Components are declared in the manifest by the phase that implements them (player and
playback service in F5, download service in F6, tile/receiver/widget in F7), so the
F5–F7 contract no longer depends on a disabled `MissingClass` check.

## F1 emulator walkthrough

`Pixel_8_API_36` (Android 16, x86_64, 1080x2400) booted headless with
`-gpu swiftshader_indirect`. The emulator is unstable in this environment (it has
dropped offline mid-session more than once), so the walkthrough was run as short
install → launch → `uiautomator dump` cycles instead of one long session.

```
adb install -r app-fast-debug.apk            OK (29 049 512 B)
am start -W …MainActivity                    Status: ok, COLD, 5204 ms
                                             later launches 2063-2253 ms
logcat                                       no FATAL EXCEPTION
```

Screens actually opened and dumped (not just launched):

| Screen | Evidence |
|---|---|
| Main Menu | 93 nodes: the 15 documented rows + **Exit**, breadcrumb `Rivo` |
| Miscellaneous | Explore Channels / Playlists / Shorts / Movies, Search History, Custom Commands, New Custom Command, Sync Subscriptions, Edit Config, Edit histories, Downloads, Update, About, Back, Exit |
| Edit Config | 14 rows: 6 toggles, Theme, Language, Items per page, Download folder, Extensions folder, Open config editor |
| Theme picker | Catppuccin Mocha, Tokyo Night (checkmark on the active one) |
| Language picker | Automatic (en-US), English (en), Español (es), Português (Brasil) (pt-BR) |

Selecting **Español** re-rendered every open screen without a restart
(`Configuración`, `Recientes`, `Tema`, `Idioma`, `Elementos por página`,
`Carpeta de descargas`…) and the picker wrote `CONFIG_LANG=es` to `files/config`.
Deleting `files/config` and relaunching regenerated the defaults
(`CONFIG_THEME=tokyo-night`, `CONFIG_LANG=auto`, `CONFIG_NERD_FONTS=true`), which
exercises the "config absent" path as well.

### Defects the walkthrough found (all fixed)

1. **Nerd Font toggle lied.** `CONFIG_NERD_FONTS=true` and the switch showed ON while
   the APK ships no patched font, so every icon silently fell back to ASCII.
   `FontRepository.hasNerdFont` is now plumbed through `MenuContext.nerdFontAvailable`;
   without a bundled TTF the row is rendered disabled with the detail
   *No Nerd Font bundled* (es: *Sin fuente Nerd Font incluida*, pt-BR: *Sem fonte
   Nerd Font incluída*). Dump after the fix: `checked=true enabled=false` plus the text.
2. **Breadcrumb kept the old language.** `MenuFrame.title` froze the label at click
   time, so `Edit Config → Language → Español` left `Rivo / Edit Config` on screen.
   Catalog menus now resolve their title per render; only extension-provided menus
   (no catalog definition) keep the frozen label. After the fix the crumb reads
   `Rivo / Configuración`.
3. **Missing Spanish/Portuguese strings.** `TXT_MENU_MAIN_MY_RECENTS` and 15 other
   keys existed only in `en.lang.json`, so a Spanish UI showed *Recent* and the
   file-not-found/error texts in English. Added translations; all three packs parse
   and `es`/`pt-BR` have no missing non-icon key (`en` 295 keys, `es` 242,
   `pt-BR` 242). The 62 `TXT_ICON_*` glyphs intentionally live only in `en` and are
   served by the `en + locale` fallback in `StringsRepository.bundle()`.

`pt-BR` was verified at the asset level (JSON parse + key parity + Spanish screen
pass) but not rendered on the device; there is no Portuguese system locale on this
AVD.

## F2 extraction layer

F2 has no screens of its own, so its verification is the test suite plus an opt-in run
against the live site. Commands:

```bash
./gradlew :app:testFastDebugUnitTest :app:testYtdlpDebugUnitTest
RIVO_LIVE=1 ./gradlew :app:testFastDebugUnitTest --tests '*NewPipeLiveTest'
```

Offline suite (both flavors): **88 test cases, 0 failures, 5 skipped** — the five skips are
`NewPipeLiveTest`, which the `RIVO_LIVE` assumption disables by default. New in F2:
`UrlCanonTest`, `CookieTxtTest`, `PoTokenTest`, `YtdlpJsonMapperTest`, `CommandLineTest`,
`ExtractorFailureTest`, `ExtractorRouterTest`.

Live run (`RIVO_LIVE=1`, 18 Nov 2026):

| Check | Result |
| --- | --- |
| Public video (`dQw4w9WgXcQ`) | 21 formats, 6 subtitle tracks, HLS manifest, playable audio + video |
| Search page 1 → page 2 | 19 then 20 items, continuation token accepted, ids are real video ids |
| Channel `@MrBeast` | name, 2 tabs, 30 items with channel ids |
| Music kiosk | 30 items |
| Trending | capability absent; request throws `REMOVED` naming the retirement |

### Defects the live run found (all fixed)

1. **Every list item was keyed `"newpipe"`.** `StreamInfoItem` (and `PlaylistInfoItem`)
   expose no `id` member, so an unqualified `id` inside the mapping extension resolved to
   `NewPipeBackend.id` — the backend's literal id. Search results still rendered, but every
   row shared one key, and playback would have resolved the wrong video. Identity now comes
   from the item URL via `UrlCanon`.
2. **List and channel extractors were read without fetching.** NewPipe parses lazily;
   `getInitialPage()`/`ChannelInfo.getInfo(extractor)` throw `NullPointerException` or
   `Page is not fetched` until `fetchPage()` runs. Search, channel tabs, kiosks, channel
   details and playlists now fetch first.
3. **HTML pages were requested without a browser User-Agent.** `NewPipeTransport` attached
   cookies but no UA, and YouTube served a page with no `ytInitialData` to the default Java
   client. The transport now sends a desktop Chrome UA unless the request supplies one.
4. **Trending is not fetchable.** YouTube answers the trending browse call with HTTP 400.
   `Kiosk.TRENDING` is no longer advertised; requesting it reports `REMOVED` instead of a
   parse failure. The menu node remains.

## F3 menu shell and data screens

F3 replaces the terminal loop with one list shell (`MenuScreen<T>`) and four
extractor-backed screens (feed, search, channel, playlist) rendered through it. Filter,
paging, preview, breadcrumbs and keybindings are shared; a screen only supplies its source
and header.

Offline suite: **150 test cases, 0 failures, 6 skipped** in both flavors. New in F3:
`FuzzyTest`, `KeyBindingsTest`, `CountFormatTest`, `SearchFiltersTest`, `PageWindowTest`,
`MediaSourceTest`, `MediaMenusTest`.

Live checks (`RIVO_LIVE=1`):

| Check | Result |
| --- | --- |
| Unfiltered search | 19 items, continuation accepted, page 2 = 20 items |
| Search with `sp=VIDEOS` | 20 videos (scope applied, not dropped) |
| Search with `sp=CHANNELS` / `sp=PLAYLISTS` | 20 channels / 19 playlists |
| Date/quality/sort chip on NewPipe | `UNSUPPORTED`, message names the yt-dlp extractor |
| Channel `@MrBeast` | name, 2 tabs, 30 items |
| Channel search on the ytdlp flavor | 30 items for `jazz`, page 2 = 27 items (the short last page: 57 rows total, no further page) |
| Channel search on the fast flavor | localized "unsupported" + extractor reason |
| Music kiosk | 30 items |

Emulator (`Pixel_8_API_36`, fast and ytdlp debug APKs installed in turn):

| Screen | Evidence |
|---|---|
| Main menu | filter field focused, breadcrumb `Rivo`, ASCII icon fallback |
| Search | suggestions after 250 ms, 24 `sp` chips in four labelled groups, `VIDEOS` scope by default |
| Results | 20 rows on page 1, `Page 1 · 20 items`; `Next` → `Page 2 · 19 items` with new rows |
| Suggestion tap | picks the term, clears the box, runs the search (title `chicken banana` on ytdlp) |
| `:token` grammar | `:4k` on a `lofi` result list keeps the query, selects the `4K` chip: fast flavor → `UNSUPPORTED`, ytdlp → 30 filtered rows |
| Preview sheet (phone) | `Preview` in the toolbar opens the sheet: `Channel`/`Views`/`Live Status` for a live row; system back closes it |
| Preview panel (tablet) | at `2400x1600` the two-pane layout drops the toggle and docks the panel: same fields, right-hand column |
| Item actions | Watch…Share rows, `Go To Channel` opens `channel_actions` |
| Channel | `Lofi Girl`, tab chips, 30 rows, toolbar search icon |
| Channel search | title `Search`, prompt `Search for` in the empty list, 30 rows, `Page 2 · 27 items` |
| Fast flavor channel search | `This source cannot be opened by the current extractor.` + `NewPipeExtractor cannot search inside a channel; select the yt-dlp extractor for that` |

### Defects F3 found (all fixed)

1. **Every `sp`-filtered search returned an empty page.** `NewPipeBackend` passed the raw
   `sp` protobuf as a *content filter name*. `SearchQueryHandlerFactory` expects
   `videos` / `channels` / `playlists` and builds the params itself, so an unknown name
   left every scope flag false and the collector dropped the whole page — the UI showed an
   empty result set with no error. Search now maps the three scope codes and reports the
   other chips as `UNSUPPORTED`, which `ExtractorRouter` retries on yt-dlp when that
   backend is compiled in.
2. **Paged results were filtered by the query that produced them.** The search box kept
   the submitted text, which doubled as the local fuzzy filter, so page 2 showed
   `No matches` whenever its rows did not repeat the query. The box is now a query editor:
   submitting (Enter, the toolbar icon, a suggestion, a chip tap with text in the box) runs
   the search and clears it, the submitted text becomes the title, and Enter activates the
   selected row when the box is empty. The same path implements the upstream `:token`
   grammar, so `:4k` or `:today` replaces the active chip instead of being searched for.
3. **The app crashed at boot.** The F1 manifest declared `PlaybackService`,
   `PlayerActivity`, `DownloadService`, the tile, the Tasker receiver and the widget before
   F5–F7 implement them; the system probed the missing media browser service during package
   scan and the process died before drawing. Components are now declared by the phase that
   implements them and the blanket `MissingClass` lint exemption is gone.
4. **yt-dlp could not start in the ytdlp flavor.** `YoutubeDL.init` copies `R.raw.ytdlp`
   and unpacks `libpython.zip.so` from the *native library directory*; AGP's default
   page-aligned packaging only maps that `.so` from inside the APK, so `init` threw
   `failed to initialize`. `packaging.jniLibs.useLegacyPackaging = true` restores the
   extracted library directory (and shrinks the APK to 105 MB from 113 MB).

## F4 — Library (Room)

Room v1 became the store for saved videos, recents, custom playlists, subscriptions,
saved remote playlists and search history; every menu that used to say "not stored yet"
now reads and writes it. The full matrix after F4:

| Task | Result |
| --- | --- |
| `:app:testFastDebugUnitTest` | OK — 195 tests, 0 failures, 6 skipped |
| `:app:testYtdlpDebugUnitTest` | OK — 195 tests, 0 failures, 6 skipped |
| `:app:lintFastDebug` | OK — 0 errors, 13 warnings |
| `:app:assembleFastDebug` | OK — 28.18 MB |
| `:app:assembleYtdlpDebug` | OK — 105.49 MB |
| `:app:assembleFastRelease` | OK — 2.78 MB |

The 45 new JVM cases are `LibraryRepositoryTest` (15), `LibraryKeysTest` (8),
`LibraryHandlersTest` (8), `LibraryMenusTest` (10) and four updated `MediaMenusTest`
assertions. `LibraryRepositoryTest` runs the real repository against `FakeLibraryDao`
(`MutableStateFlow`-backed), including recents trimming, the 50-entry search-history cap and
the `!N` recall path; no Robolectric.

### Emulator walkthrough (fast flavor, `Pixel_8_API_36`, 1080×2400)

Each line was performed through the running app with `adb` taps and the accessibility tree.

| Check | Result |
| --- | --- |
| Main menu lists Custom Playlists / Saved Videos / Recent / Channels | present, in the documented order |
| Search records history, results render | `lofi` → 20 rows, `Page 1 · 20 items` |
| Save from item actions flips the row live | `Save` → `UnSave` without leaving the frame |
| Add to playlist → New playlist | prompt → `Road trip`; item auto-added, detail `1 item` |
| Saved Videos | saved row listed, `Page 1 of 1 · 1 item` |
| UnSave from Saved Videos | list reloads to `No saved videos yet` |
| Custom Playlists | `Road trip` row; opens actions frame |
| Rename playlist | prompt prefilled with the live title; `Road trip 2` applied, snackbar confirms |
| Delete playlist | confirm dialog names the playlist; list returns to the empty state |
| Open playlist | playlist row opens the actions menu (`Open playlist` first); contents frame titled with the playlist name, breadcrumb `… / Trip` (no duplicate crumb), `Page 1 of 1 · 1 item`, actions include `Remove from playlist` |
| Remove from playlist | item gone; frame shows `This playlist is empty` |
| Save / UnSave playlist | `Misc → Explore Playlists → lofi` → row opens playlist actions → `Save Playlist` → row appears under `Playlists` with `UnSave Playlist`; removing it empties the list |
| Account playlists entry | `Playlists` starts with `Your YouTube playlists`; the fast flavor answers `the playlists feed needs cookies; import cookies.txt or sign in` |
| Recent screen | played row listed; `!1` in the search box recalls the last query and runs it |
| Search History | `lofi` row re-runs the query; `Clear Search History` confirms and empties the frame |
| Edit histories | offers `Clear Search History` and `Clear recent videos`; confirming recents empties the screen to `No recent videos yet` |
| Channels | `Lofi Girl` → channel actions → `Unsubscribe` relabels to `Subscribe` live |
| Sync subscriptions | confirm dialog shown; fast flavor answers with the account-feeds message instead of pretending |
| Account feeds toggle | `Config → Account feeds` flips and persists |
| Downloads entry | present, explains it belongs to the downloader (F6) |

### Defects F4 found (all fixed)

1. **Singular counts rendered as `1 items`.** Playlist details and the pager status used one
   pluralized format (`1/1 items`). `TXT_ANDROID_ITEM_COUNT_ONE` now covers the singular in
   en/es/pt-BR, and a one-row page reads `Page 1 of 1 · 1 item`.
2. **Playlist frames were titled after the row that opened them.** The contents frame showed
   `Open playlist` and the actions frame its catalog title. `MenuEngine.title` derives the
   `local_playlist` title from its payload, so the contents frame, breadcrumb and header name
   the playlist.
3. **Rename showed a stale title.** The prompt, confirm message and snackbar used the payload
   snapshot, which is the pre-rename name after one rename in the same session. The handler
   now re-reads the live title from the repository at both steps.
4. **Autocomplete ignored the search history.** Suggestions came only from the extractor;
   local history matches now lead the list, which is what makes typing three characters of
   a previous query useful offline.
5. **The breadcrumb repeated the playlist name.** Opening a playlist from its action menu
   produced `… / Trip / Trip`, because both frames are named after the playlist. Adjacent
   crumbs with the same label now collapse, while the remaining crumb keeps the frame index
   it pops to.
6. **A playlist could not be saved.** Playlist rows opened their contents directly, and the
   playlist action menu (the only place `Save Playlist` exists) was unreachable. Rows now
   open the actions menu with `Open playlist` first, which is also what upstream prompts for.

## F5 — Player (Media3)

F5 added `PlaybackService` (`MediaSessionService` + `MediaBrowserService`),
`PlayerActivity`, the stream planner, the queue coordinator and the whole overlay: speed,
quality, audio track, subtitles, chapters, A–B loop, SponsorBlock, external-player handoff,
PiP and the notification.

| Task | Result |
| --- | --- |
| `:app:testFastDebugUnitTest` | OK — 227 tests, 0 failures, 7 skipped |
| `:app:testYtdlpDebugUnitTest` | OK — 227 tests, 0 failures, 7 skipped |
| `:app:lintFastDebug` | OK — 0 errors, 19 warnings (listed under [Lint](#lint)) |
| `:app:assembleFastDebug` | OK — 28.57 MB |
| `:app:assembleYtdlpDebug` | OK — 105.89 MB |
| `:app:assembleFastRelease` | OK — 3.87 MB |

The 32 new JVM cases are `StreamPlannerTest` (11: muxed preference, merge fallback, live
manifests, audio mode, quality caps, subtitles, track grouping), `PlaybackRequestCodecTest`
(5: request and extras round-trip through `MediaMetadata`), `SponsorBlockClientTest` (7),
`NewPipeChaptersTest` (5: chapter ends, ordering, dedupe, unknown duration),
`YtdlpJsonMapperTest` (2: a direct video file and a direct audio file without codecs),
`ExtractorRouterTest` (1: the fast build names yt-dlp for a non-YouTube link) and updated
`MediaMenusTest` assertions for the new action rows and their order. `NewPipeLiveTest` gained
a description-chapters check, which is skipped unless `RIVO_LIVE=1`.

### Emulator walkthrough (fast flavor, `Pixel_8_API_36`, 1080×2400)

| Check | Result |
| --- | --- |
| `rivo://watch?v=…&action=play` | player opens, video and audio play, letterboxed 16:9 |
| Watch from item actions | `MEDIA_ACTIONS → Watch` opens the same player |
| Listen | audio-only backdrop, no surface, system chrome stays |
| Overlay auto-hide | chrome fades after 4 s of playback; a tap brings it back |
| Paused chrome | pausing keeps the controls up (D35) |
| Quality sheet | `Auto`, `2160p` … `144p`; picking `1080p` rebuilds the stream at the same position (`stream rebuilt: index=0 position=2424`) |
| Speed sheet | `0.25×` … `4×`; `1.5×` shows on the chip and survives sheet dismissal |
| Subtitles | `Off`, `English`, `en`, `es-419`; picking `en` produces the `CC en` chip |
| Audio track | a single-track video offers only `Original` (no codec rows) |
| A–B loop | first tap sets A, second sets B, chip turns active, playhead wraps to A; third tap clears |
| Playlist queue | `Play → Play All` on a 19-item playlist: `1 of 19`, `next` advances (`2 of 19`), `previous` restarts the item then steps back |
| Notification | `channel=playback`, `FOREGROUND_SERVICE`, title/artist/artwork, seek bar, `actions=2–3`, channel name `Rivo playback` |
| Notification tap | opens `PlayerActivity` on the current item; the picture comes back after the surface was detached (D36) |
| Media session | `state=PLAYING`, queue size 2–19 while the timeline holds the prefetch window |
| External transport | `cmd media_session dispatch pause` → `PAUSED(2)`, `play` → `PLAYING(3)`: the commands the notification and a headset send |
| Chapters | a description-chaptered video (3Blue1Brown, 12 chapters) shows the `Chapters` chip; the sheet lists all 12; picking `Why layers?` seeks to `5:31` (`position=331000`) |
| PiP | `PlayerActivity` reports PiP and the picture renders in the window (TextureView, D32) |
| PiP dismissed | swipe the window away → playback keeps going (`PLAYING`, position advancing) |
| Close (X) | activity finishes, notification and session stay `PLAYING` (`CONFIG_DISOWN_PLAYER` default on, D36) |
| Close (X), disown off | `RIVO_CONFIG=CONFIG_DISOWN_PLAYER=false` → no session and no notification after the close |
| External player | no mpv/VLC/MX installed → `No suitable media player found. … Currently set to: mpv-android` (single colon) |
| Termux shell | `Shell (Termux)` with no Termux installed → `Termux is not installed or RUN_COMMAND is not allowed` |
| Live stream | HLS manifest plays with the `HLS` quality chip |
| Background start | `Background started FGS: Allowed`, Media3 replaces the provisional notification |

### Emulator walkthrough (yt-dlp flavor)

The `ytdlp` build is the same app plus CPython and the extractor, so the whole fast-flavor
table applies; these are the paths only this flavor has.

| Check | Result |
| --- | --- |
| Install | `app-ytdlp-debug.apk` replaces the fast build in place (`versionName 0.8.7-rivo.2-ytdlp-debug`) |
| First run | CPython 3.12 and yt-dlp 2025.11.12 are unpacked from `lib/*/libpython.zip.so` and `res/raw/ytdlp` into `no_backup/youtubedl-android`; `yt-dlp` is 3.1 MB and executable |
| Non-YouTube direct file | `rivo://media?url=…/mov_bbb.mp4&action=play` opens the player: `mov_bbb`, `0:10`, `mp4` quality chip, `1 of 1`, `PLAYING`, plays to the end (`position=10035`, `STOPPED`) |
| Fast build, non-YouTube link | same Vimeo URL on `app-fast-debug.apk` → `This source cannot be opened by the current extractor: the fast build opens YouTube links only; the yt-dlp build adds non-YouTube sites` |
| Vimeo | `Extraction failed: [vimeo] 76979871: The web client only works when logged-in. Use --cookies …` — honest about the login wall (D39) |
| Missing page | a 404 URL reports `This content is unavailable or was removed: [generic] Unable to download webpage: HTTP Error 404` |
| YouTube with `CONFIG_EXTRACTOR=ytdlp` | extraction succeeds; the CDN answers the media request with `403`, so playback ends in `Source error`. The bundled 2025.11.12 client needs a PO token (or a newer extractor) for YouTube in Nov 2026; with the default `auto` the same video plays through NewPipe |

### Defects F5 found (all fixed)

1. **The prefetch window leaked into the UI.** The overlay said `1 of 5` for a 19-item
   playlist because it read `mediaItemCount`, which is only the resolved window.
   `PlayerExtras.queueSize` now carries the request length (D34).
2. **The audio sheet listed codecs as tracks.** A normal video offered `Track 139`,
   `Track 140`, `Track 249`… — the same audio in `m4a` and `opus`. The picker now groups by
   track identity and offers nothing when there is nothing to choose (D33).
3. **PiP rendered a black window.** A `SurfaceView` is not composited when the system
   re-parents the task, and the same thing made the video area black while a sheet was open.
   The player surface is a `TextureView` (D32).
4. **"Next and quality chips do nothing".** The taps landed after the four-second auto-hide,
   so they only re-showed the chrome. The real defect was that a *paused* player hid its
   controls at all; pausing now keeps them (D35).
5. **The chip row clipped `External`.** Six chips with `TextButton` defaults do not fit
   1080 px. Compact padding, `labelSmall` and a horizontally scrollable row fix it.
6. **Failure messages repeated the colon.** `… Currently set to:: mpv-android` came from a
   language-pack string that ends in `:` plus the detail joiner. `LaunchOutcome.Failed.describe`
   normalizes the trailing punctuation in one place.
7. **`values-es` and `values-pt-rBR` were double-encoded** (UTF-8 read as Latin-1, so
   `Acción` shipped as `AcciÃ³n`), and the three `playback_*` strings were missing entirely —
   which lint reported as `MissingTranslation`. Both files are repaired and translated.
8. **Closing the player killed playback.** With disown on, the X finished the activity and the
   session went to `ERROR (MediaCodecVideoRenderer error … format_supported=YES)`: the codec
   was still rendering into a `TextureView` that died with the window. The activity now clears
   the video surface in `onStop`, before the controller is released (D36).
9. **Chapters never reached the player.** `NewPipeBackend` reported `chapters = false` and
   passed `streamSegments` off as sponsor segments, so the `Chapters` chip and the chapter
   sheet were dead on the default flavor. Description chapters are now mapped to
   `VideoDetails.chapters`, with each chapter ending where the next one starts (D37).
10. **Every non-YouTube file was unplayable.** `YtdlpJsonMapper` classified a format by its
    `vcodec`/`acodec`, and the generic extractor reports neither for a direct media URL
    (`"vcodec": null`, `"format": "mp4 - unknown"`). The only format was dropped, so the
    player opened on an empty queue. The kind is now inferred from the container when the
    codecs are unknown (D38), with `YtdlpJsonMapperTest` covering the recorded JSON for a
    direct video and a direct audio file.
11. **A broken link blamed the external player.** `MediaLinkResolver` swallowed the extractor
    error and returned `null`, and the only `null` branch was `TXT_PLAYER_NOT_FOUND`: opening
    a Vimeo link said "No suitable media player found. Please set CONFIG_PLAYER…". Failing
    links now carry their error and show the category plus the extractor's first line
    (D39); `action=play` on such a link no longer fails silently. The `fast` build also
    answers a non-YouTube link itself ("the fast build opens YouTube links only…") instead
    of forwarding NewPipe's misleading "not a video URL".

### Not verified in F5

- **External player, success path.** mpv/VLC/MX are not installed on the emulator; only the
  "no player" outcome was exercised. Package mapping is covered by `ExternalPlayerTest`.
- **Termux, success path.** Termux is not installed; `TermuxShell` builds the
  `com.termux.RUN_COMMAND` intent and reports the missing package the same way the external
  player does.
- **SponsorBlock skipping.** The test videos carry no segments; the client and the polling
  logic are covered by `SponsorBlockClientTest`, the timeline marks by inspection.
- **Audio track switching.** No multi-dub video was found; the rebuild path itself is the
  same one exercised by the quality picker.
- **YouTube through yt-dlp.** The pinned 2025.11.12 client extracts, but the CDN rejects its
  URLs with `403`; the extractor update that fixes it is F7 (D20).
- **Cookies and PO tokens through yt-dlp.** No account was signed in on the emulator, so only
  the unauthenticated paths ran.
- **Android Auto.** The service is declared as a media browser service; there is no Auto
  host in the emulator.

Known noise: declaring `MediaBrowserService` makes `SystemUI`'s `ResumeMediaBrowser` log
`Subscribe error … MediaLibraryService` / `Cannot resume`. Dropping the filter would silence
it but reintroduce lint's `MissingMediaBrowserServiceIntentFilter` and break media-browser
clients, so it stays.


## F6 — Downloads

F6 added the download stack: the format planner, ranged transfer with resume, `MediaMuxer`
joining, the three writers (`MediaStore`, SAF tree, plain directory), the persisted queue,
`DownloadService`, the Downloads menus, local playback of downloaded files, share/open-with,
delete and the archive.

| Task | Result |
| --- | --- |
| `:app:testFastDebugUnitTest` | OK — 315 tests, 0 failures, 7 skipped |
| `:app:testYtdlpDebugUnitTest` | OK — 315 tests, 0 failures, 7 skipped |
| `:app:lintFastDebug` | OK — 0 errors, 20 warnings (one new: `InlinedApi` on `FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK`, harmless by definition) |
| `:app:assembleFastDebug` | OK — 27.96 MB |
| `:app:assembleYtdlpDebug` | OK — 105.37 MB |
| `:app:assembleFastRelease` | OK — 3.74 MB (unsigned) |

The 88 new JVM cases are `DownloadPlannerTest` (20: muxed preference, MP4-only merge, audio
format preference, height caps, manifest fallback), `DownloadNamingTest` (18: layout,
sanitising, enumeration, duplicate suffixes, extension and MIME mapping), `DownloadStoreTest`
(15: queue persistence round-trip, state transitions, archive interaction), `DownloadMenusTest`
(14: row order, task state labels, file rows, remove/delete confirmations),
`RangedDownloaderTest` (9: `Range` continuation, `416`, retries, truncation) and
`DownloadArchiveTest` (8: `kind<TAB>videoId` identity and the playlist-independence rule),
`DownloadFolderTest` (4: root normalisation and defaults). F6 also removed the unused
mp4parser dependency (D8 superseded by D41): the fast debug APK shrank from 28.63 MB to
27.96 MB and the release from 3.95 MB to 3.74 MB.

### Emulator walkthrough (fast flavor, `Pixel_8_API_36`, 1080×2400)

| Check | Result |
| --- | --- |
| Video download, muxed rendition | `Download` on a 720p-max video writes `Movies/Rivo/video/individual/<Channel>/<title>.mp4`; MediaStore row becomes visible when `IS_PENDING` clears |
| Video download, merged rendition | `CONFIG_VIDEO_FORMAT=480` → video-only + MP4 audio merged by `MediaMuxer`; the file plays with sound in the player and in Photos (D41) |
| Audio download | `Download (Audio Only)` writes `Music/Rivo/audio/….m4a`; the music collection accepts it (D42) |
| Picked SAF folder | `Choose folder` opens `ACTION_OPEN_DOCUMENT_TREE`; picking `Download/rivo-saf` persists the grant and writes `video/…` and `audio/…` there |
| SAF video and audio | both write and play from the tree URI |
| Folder row | config row and Downloads header show `Movies/Rivo · Music/Rivo`, or `Download/rivo-saf` for a tree, never the raw `content://` string |
| Archive | after a download `archive.txt` holds `video<TAB>yebNIHKAC4A`; a second request reports `Already downloaded (1)` and queues nothing |
| Archive off | `CONFIG_DOWNLOAD_ARCHIVE=false` → the same video downloads again as `… [yebNIHKAC4A].m4a` |
| Progress | task row shows `downloading · 42% · <Channel>`; notification shows the aggregate `n downloading · m queued` |
| Notification tap | opens `rivo://downloads` (the Downloads menu) |
| Queue menu | `Folder`, `Queue` (`1 downloading · 25 queued`), `Downloaded files` (`1 file` / `2 files`), `Refresh`, `Clear finished` |
| Playlist download | `Download All` on a 27-item playlist queues the paginated whole playlist, metadata says `playlist: Cute Lofi ꒰⋆•͈⌔•͈⋆꒱`, files land under `video/<Playlist>/<Channel>/` |
| Cancel while running | `Cancel download` marks the task `cancelled`, removes its work directory (`cache/downloads/<kind>_<id>` gone), and the next queued task starts |
| Retry | `Retry download` on a cancelled task returns it to `queued` |
| Remove from queue | confirms, then drops the row from `queue.json` and the list (`24 queued` after 25) |
| Pause and resume | pause keeps `cache/downloads/<kind>_<id>/….part`; resume continues with a ranged request and finishes |
| Wi-Fi gate | `CONFIG_DOWNLOAD_WIFI_ONLY=true` with Wi-Fi and data off holds the task at `queued`, notification says `Waiting for Wi-Fi`; enabling Wi-Fi moves it to `running`, then `done` |
| Local playback | `Play` on a downloaded file opens the internal player with a local URI (no resolver round-trip) |
| Share | `Share` opens the system chooser with the file attached (the chooser itself now carries `NEW_TASK`) |
| Open with another app | chooser → Google Photos opens the MP4 |
| Delete file | confirms, deletes the row (MediaStore) or the document (SAF), nulls `outputPath`, drops the archive entry, keeps the queue row |
| Delete dialog text | two lines (`Delete this file?` / title) thanks to `&#10;` in all three languages |
| Delete/remove feedback | the screen pops back to the list before the snackbar shows |
| Missing file | a task whose file was deleted elsewhere shows the folder label, not a payload dump |
| Open folder | MediaStore root opens DocumentsUI at `Movies/Rivo`; a SAF tree opens at `Download/rivo-saf` |
| Empty list | `No local downloads yet` |
| Refresh | re-scans the target and reports `0 files in the folder` |
| Clear finished | empties finished rows from `queue.json` and the list |

### Emulator walkthrough (yt-dlp flavor)

| Check | Result |
| --- | --- |
| Install over fast | `app-ytdlp-debug.apk` replaces the fast build (`versionName 0.8.7-rivo.2-ytdlp-debug`) |
| Audio download | `Download (Audio Only)` finishes `audio/individual/<Channel>/<title>.m4a`, `mimeType audio/mp4`, `state done` |

### Defects F6 found (all fixed)

1. **Share and "open with" did nothing.** The chooser intent was built from the application
   context without `FLAG_ACTIVITY_NEW_TASK`; the platform refused with "Calling
   startActivity() from outside of an Activity context…". Both the wrapped and the chooser
   intent now carry the flag.
2. **Audio-only downloads failed into `Movies/`.** One configured root was applied literally
   to every MediaStore collection, and `Audio` rejects `Movies` as a primary directory (and
   `audio/webm` as a type). Roots are resolved per collection, a refused audio insert retries
   in `MediaStore.Downloads`, and the config row shows both roots (D42).
3. **The folder row was unreadable.** It printed `CONFIG_DOWNLOAD_DIR` raw, so a tree showed
   as a percent-encoded `content://…` string. `DownloadRepository.folderLabel()` now supplies
   `Movies/Rivo · Music/Rivo` or the tree's `Download/rivo-saf`, and `SafTarget.description`
   strips the `primary:` prefix.
4. **Deleting a file left the archive entry.** The task was found by a strict path compare
   that failed for SAF documents and trailing slashes; `ownerOf` matches the trailing-slash
   -insensitive path, then a unique file name. Deleting now clears the archive entry and the
   output path while keeping the queue row.
5. **"Open folder" opened the wrong place or nothing.** MediaStore rows have no folder URI;
   the action now builds a `com.android.externalstorage.documents` document URI for the
   shared folder and uses the tree URI for SAF, with `choose = false`.
6. **The delete snackbar arrived on a dead screen.** `showSnackbar` suspends, so popping
   afterwards raced the scaffold. `MenuResult.Message` gained `pop` and the root pops before
   showing the message.
7. **`1 files`.** The header had only a plural format; `TXT_ANDROID_DOWNLOADS_FILES_ONE`
   was added in all three languages.
8. **A missing file explained itself with `DownloadPayload(...)`.** The detail row now says
   which folder the app looked in.
9. **Lint error on `MediaStore.Downloads`.** The collection is API 29+; `MediaStoreTarget` is
   annotated `@RequiresApi(Q)` because it is only constructed behind that check.

### Not verified in F6

- **API 26–28 `FileTarget`.** The emulator is API 36; the plain-directory writer and its
  `WRITE_EXTERNAL_STORAGE` path are exercised only by unit tests.
- **Manifest fallback through yt-dlp.** The HLS/DASH branch of `DownloadPlanner` and the
  `DownloadFallback` handoff to the binary were not triggered by a test video.
- **Notification actions.** The aggregate notification's pause/cancel actions were not tapped
  from the shade; the same commands run from the menu were exercised.
- **Concurrency above one.** `CONFIG_DOWNLOAD_CONCURRENCY` stayed at 1 for the whole run.
- **Removable storage.** No SD card was present, so the SAF tree was on primary storage.
- **Process death and reboot.** The queue file survives restarts (it is read on construction),
  but no reboot with a live task was performed.
- **Out of space and muxer failure.** Neither was induced.
- **Notification permission denial.** POST_NOTIFICATIONS was granted on first request; the
  denied path (downloads without a notification) was not walked.



## F7 - Settings, extensions and Android surfaces

`Pixel_8_API_36` (Android 16, x86_64, 1080x2400), fast debug build, the F6 `files/config`
plus the demo extension folder `/sdcard/Download/ext/`. Screens were reached through
`rivo://` links and the always-focused filter; the network was up for the update check.

### Emulator walkthrough

**Custom commands.**

- `rivo://misc` lists `Custom Commands`, `New Custom Command` and `Sites` between the search
  history and sync rows. With no extension folder, `Sites` says "Add a .sites.json file to the
  extensions folder".
- New command asks name → URL → options. The options stage is optional and accepts a blank
  field (`Prompt.allowEmpty`); confirming writes `files/my/cmds.json` and shows "Added Demo".
- The command appears in `Custom Commands` and as a dynamic shortcut `cmd-<id>`
  (`adb shell cmd shortcut get-shortcuts --user 0 io.github.iseaprojects.rivo.debug`); deleting it removes
  both the row and the shortcut.
- `rivo://custom-cmd?name=Demo` opens the actions menu. Run on a Dailymotion link reports "This
  source cannot be opened by the current extractor" in the fast flavor; Edit renames and clears
  the options; Delete asks for confirmation and leaves `cmds.json` with an empty list.

**Extensions from SAF.**

- Choosing `Download/ext` through the system picker loads the four demo files
  (`demo.cmds.json`, `demo.lang.json`, `demo.sites.json`, `demo.theme.json`): "4 files loaded".
- The theme overlay appears in Config → Theme as "Demo Extension" and applies
  (`CONFIG_THEME=demo-ext`); the language overlay adds `Sitios DEMO`, `Tema DEMO` and
  `Carpeta DEMO` under `es` once selected.
- `Sites` lists the extension action; its prompt asks for the query and opens
  `https://www.dailymotion.com/search/<query>`. The extension command `PeerTube` is listed
  read-only with Run, Open in Termux and URL; Termux reports "Termux is not installed or
  RUN_COMMAND is not allowed".
- Turning `Use extension folder` off persists `CONFIG_EXTENSIONS_ENABLED=false` and removes
  the overlays: `Sites` reverts to the empty hint and the demo theme/language disappear.
  Turning it back on reloads "4 files loaded".
- `Forget folder` shows "Extension folder forgotten", `0 files loaded` and "No folder
  selected", and the file list is gone.

**Cookies and accounts.**

- The cookies screen shows Use cookies, Import cookies.txt, WebView login, Clear cookies,
  Storage and Scope. Importing `/sdcard/Download/cookies.txt` reports "Imported 2 cookies
  (0 skipped)"; Clear removes the entries from `shared_prefs/rivo_secure_cookies.xml`.

**Extractor and update.**

- Extractor shows Automatic ("Use yt-dlp when the build ships it, otherwise NewPipeExtractor"),
  NewPipeExtractor ("Bundled in every build") and yt-dlp ("Not included in this build" in the
  fast flavor; the ytdlp flavor enables the same row). The build row reads
  "NewPipeExtractor only (fast build)".
- Update opens on "Up to date / 0.8.7" with "Check again"; the release-page row is absent while
  no newer upstream tag exists. The available branch was not exercised.

**Cache and configuration.**

- Keep cache for offers Off, 1 day, 3 days, 7, 14 and 30. Choosing 1 writes
  `CONFIG_CACHE_RETENTION_DAYS=1`, the row reads "1 day", and the periodic
  `CacheCleanupWorker` job stays registered (`dumpsys jobscheduler`).
- Clear cache now reports "Removed 35 files (4.2 MB)"; `cache/downloads` is preserved.
- The config editor opens the whole of `files/config`; the toolbar and first line sit below
  the status bar, and Save rewrites the file.

**Quick Settings tile.**

- The tile is registered as
  `custom(io.github.iseaprojects.rivo.debug/io.github.iseaprojects.rivo.platform.RivoTileService)` in the
  `sysui_qs_tiles` secure setting; tapping "Rivo search" in the shade brings `MainActivity`
  forward on the search frame (`rivo://search`).

**Widget.**

- The launcher picker shows "Rivo recent / 1 widget" and a 4x2 preview; with no recents the
  preview reads "Open Rivo to start watching".
- Placed on the home screen it renders up to four rows from the Room recents table; after
  playback the newest entry appears as a title. Rows open `rivo://media?url=...` and the title
  opens `rivo://recent`.

**Tasker broadcast.**

- `adb shell am broadcast -n io.github.iseaprojects.rivo.debug/io.github.iseaprojects.rivo.platform.TaskerFireReceiver
  -a io.github.iseaprojects.rivo.action.TASKER_FIRE --es rivo_uri "rivo://recent"` without the overlay grant
  posts "Rivo action / Tap to open rivo://recent".
- With `SYSTEM_ALERT_WINDOW` granted
  (`adb shell appops set io.github.iseaprojects.rivo.debug SYSTEM_ALERT_WINDOW allow`) the same broadcast
  brings `MainActivity` to the front on Recent.

### Defects F7 found (all fixed)

1. **Boot crash `lateinit property graph has not been initialized`.** `AppGraph` scheduled the
   cache worker during construction and `CacheCleanupWorker.days()` asked `RivoApp.graph`.
   Retention is passed explicitly now and `days()` reads `ConfigStore`.
2. **A blank optional yt-dlp options field could not be confirmed.** `Prompt` had no
   "empty is valid" state; `allowEmpty` enables Confirm with an empty field.
3. **`es.lang.json` had a raw newline inside a JSON string** (the download-delete
   confirmation), so the whole pack failed to parse. Fixed; all three packs were re-validated
   with `json.loads`.
4. **93 of the 94 new string keys were missing** from the lang packs; added in en/es/pt-BR
   (`tools/check_strings.py`: 420 referenced, 543 defined, 0 missing).
5. **`StartActivityAndCollapseDeprecated` lint error** in the tile: the API 34+ PendingIntent
   overload is the only non-deprecated way to collapse the shade and start the activity;
   suppressed at the call site with the reason.
6. **"1 days"** came from the day-format string; `TXT_ANDROID_CFG_ONE_DAY` supplies the
   singular form.
7. **The fast flavor described the absent backend as "fast build"** instead of saying it is not
   included; the row now uses "Not included in this build".
8. **The config editor drew its toolbar under the status bar**; `statusBarsPadding()` was added
   to the header row.
9. **The Tasker receiver launched nothing.** A PendingIntent created with
   `MODE_BACKGROUND_ACTIVITY_START_ALLOWED` throws ("must not be set when creating"), and the
   send-time option is ignored because the creator must opt in (`balAllowedByPiCreator:
   BSP.NONE`). Final design: direct start only with the overlay permission, notification
   otherwise (D47). `TaskerEditActivity` was dropped with it; the broadcast is the contract.
10. **The notification body rendered as "Tap to open %1"** because a PowerShell here-string
    consumed `$s` while the string was added; corrected to `%1$s` and checked in
    `dumpsys notification`.
11. **Exported service/receiver, overdraw, UseKtx and DefaultLocale lint warnings** were
    cleaned or justified; 0 errors, 19 warnings remain.

### Not verified in F7

- `CONFIG_AUTOLOADED_EXTENSIONS` with an explicit subset (the parser is unit-tested; the
  subset was not exercised on a device).
- The update screen's available-release branch (no newer upstream tag during the test).
- The ytdlp flavor's in-app extractor update (compiles and routes; the binary swap was not
  run on a device).
- Tapping a widget row (rendering and the pending intents were checked; the tap was not).
- Tapping the Tasker notification (its content intent wraps the same verified link).
- The real Tasker app; the broadcast was sent with `am broadcast`, which is the same surface.

## Manual checklist

### Launch and navigation

- [ ] Cold start lands on **Main Menu**; header `Main Menu`, breadcrumb `Rivo`.
- [ ] All main rows are present in the documented order.
- [ ] Opening **Miscellaneous** pushes a frame; breadcrumb becomes `Rivo / Miscellaneous`;
      toolbar back arrow and system back both pop it.
- [ ] Tapping the `Rivo` breadcrumb from two levels deep returns to the root.
- [ ] System back at the root finishes the activity.
- [ ] **Exit** finishes the activity.

### Library

- [ ] **Save** on a video row flips to **UnSave** immediately and the item appears under
      **Saved Videos**.
- [ ] UnSave from the saved list removes the row and the list explains the empty state.
- [ ] **Add to playlist** creates a playlist from the prompt, adds the item, and the playlist
      detail reads `1 item` (singular) and then `2 items`.
- [ ] A playlist row opens its actions; **Open playlist** shows the contents and
      **Remove from playlist** empties it.
- [ ] **Save Playlist** adds the remote playlist to **Playlists**, where the same actions menu
      shows **UnSave Playlist**.
- [ ] **Playlists** starts with **Your YouTube playlists**, which reports the missing cookies
      or backend instead of showing an empty list.
- [ ] Rename shows the current name in the prompt; deleting asks first and names the playlist.
- [ ] **Recent** lists the last opened videos, `!1` in the search box re-runs the previous
      query, and `Edit histories → Clear recent videos` empties the list after a confirm.
- [ ] **Search History** re-runs a stored query; clearing it asks first.
- [ ] **Channels** lists local subscriptions; the channel action row toggles
      `Subscribe` / `Unsubscribe` and the list follows on return.
- [ ] **Sync subscriptions** asks first, and with account feeds off or no cookies it reports
      the reason instead of erasing local subscriptions.
- [ ] Killing the process and reopening keeps saved videos, playlists, subscriptions,
      recents (up to `CONFIG_NO_OF_RECENT`) and search history.

### Data screens not wired yet

- [ ] **Your Feed** explains that YouTube's home feed is not exposed to third-party clients.
- [ ] **Subscriptions Feed** (and the other account feeds) reports the specific missing
      precondition: `CONFIG_ACCOUNT_FEEDS` off, no cookies, or a backend that cannot read
      `/feed/...` (the fast flavor names yt-dlp).
- [ ] **Downloads** explains that the browser lands with the downloader (F6).
- [ ] An unknown handler shows `Action not available yet: <id>`.

### List shell

- [ ] The filter field is focused on arrival; typing narrows the list and moves the cursor
      to the first match.
- [ ] `Esc` clears the text, `Esc` again drops focus, `j`/`k` then move the cursor, `/`
      returns to the field.
- [ ] `Enter` opens the selected row; on the search screen it submits the typed query and
      clears the box.
- [ ] `Previous` / `Next` move the page window (`Page n · x items`), the first page shows
      `Previous` disabled, and a short last page disables `Next`.
- [ ] The toolbar `Preview` icon opens the bottom sheet; on a ≥720 dp screen the panel is
      docked on the right and the icon is gone.

### Config screen

- [ ] Preview / Preview images / Nerd Fonts / Enumerate / Wi-Fi only / SponsorBlock toggles
      flip visually and persist across a cold start.
- [ ] With no patched font in the APK, **Nerd Fonts** is shown disabled with the
      detail *No Nerd Font bundled* instead of pretending the glyphs are available.
- [ ] Theme picker lists Tokyo Night and Catppuccin Mocha; selecting one repaints the app
      immediately (status bar included) and survives restart.
- [ ] Language picker switches menus without a restart; `Automatic` follows the system.
- [ ] Page-size picker marks the current value and writes `CONFIG_PER_PAGE`.
- [ ] *Open config editor* hands `files/config` to an editor; edits appear on resume.
- [ ] Comments and ordering survive several GUI writes.

### Plain-text config round-trip

```bash
adb shell run-as io.github.iseaprojects.rivo.debug cat files/config
adb shell run-as io.github.iseaprojects.rivo.debug 'printf "CONFIG_PER_PAGE=15\n" >> files/config'
# resume the app: paging reflects the external edit
```

- [ ] An upstream yt-x `config` file copied in place is accepted; unknown keys survive.
- [ ] `CONFIG_PER_PAGE=abc` degrades to the default instead of crashing.

### Storage Access Framework

- [ ] **Rivo** appears in Files/DocumentsUI and lists `config`, `exports/`, `updates/`.
- [ ] Editing `config` through DocumentsUI is visible in the app after resume.
- [ ] Creating a file/folder works; deleting the root is refused.
- [ ] `cookies`/`no_backup`/dotfiles are not listed.

### Deep links and intents

```bash
adb shell am start -a android.intent.action.VIEW -d "rivo://feed?kind=trending"
adb shell am start -a android.intent.action.VIEW -d "rivo://search?q=eono"
adb shell am start -a android.intent.action.VIEW -d "https://youtu.be/dQw4w9WgXcQ"
adb shell am start -n io.github.iseaprojects.rivo.debug/io.github.iseaprojects.rivo.ui.MainActivity \
  --es RIVO_PER_PAGE 60 --es RIVO_THEME catppuccin-mocha
adb shell am start -n io.github.iseaprojects.rivo.debug/io.github.iseaprojects.rivo.ui.MainActivity \
  --es RIVO_CONFIG "CONFIG_LANG=es;CONFIG_NERD_FONTS=false"
```

- [ ] Each link opens the matching frame (or its pending-action row).
- [ ] `RIVO_PER_PAGE=60` changes paging for that process only; the file is unchanged.
- [ ] `RIVO_CONFIG` applies language and ASCII icons without persisting them.
- [ ] Rotating keeps the current screen and does not duplicate the pushed frame.
- [ ] Sharing a URL opens media actions for it; sharing text opens search prefilled.

### Appearance

- [ ] `CONFIG_NERD_FONTS=false` replaces every icon with an ASCII glyph, no tofu.
- [ ] Dropping a `.ttf` into `assets/fonts/` changes the UI font on next build.
- [ ] Long labels wrap to two lines and ellipsize.

### Extensions

- [ ] Copying `docs/extensions/example.ui.json` into `assets/extensions/ui/` adds the Sites
      submenu and the documentation row; nested items open the browser.
- [ ] Copying `docs/extensions/de.lang.json` into `assets/extensions/langs/` adds Deutsch.
- [ ] A deliberately broken `.ui.json` is ignored: app starts, menus intact, log shows the
      parse error under the `rivo` tag.

## What F1 does not verify

Playback and downloads belong to F5–F6; their checklist entries land with those phases.
Room-backed screens are covered by the F4 section, keyboard bindings and the search filter
UI by the F3 section above.

## F8 - Close-out

The port is feature-complete against the F1-F7 scope; this section states what "done" means,
it does not add behaviour.

- **Parity.** `docs/PARITY.md` has no `Planned` rows. The remaining `Partial` rows are
  external constraints rather than missing wiring: account feeds need the ytdlp flavor plus
  cookies, Android Auto has no host in the emulator, the yt-dlp in-app update is shipped but
  was not exercised on a device, and extension handlers are declarative by design (D43).
- **Naming.** The project ships as Rivo (`0.8.7-rivo.2`,
  `io.github.iseaprojects.rivo`, `rivo://`); the upstream yt-x name and repository stay in
  the about screen and README as the credit for the port.
- **Tests.** 344 unit test cases in 39 suites, 0 failures, 7 opt-in live tests skipped
  (`RIVO_LIVE=1`), in both flavors. `lintFastDebug` reports 0 errors and 19 justified warnings.
- **Strings.** `tools/check_strings.py` reports 419 referenced, 542 defined, 0 missing; the
  three lang packs parse as JSON.
- **Install.** `0.8.7-rivo.2-fast-debug` and `0.8.7-rivo.2-ytdlp-debug` both install
  and launch as `io.github.iseaprojects.rivo.debug` on Pixel_8_API_36; the root breadcrumb
  reads "Rivo" and `rivo://recent`, `rivo://config`, `rivo://misc` and
  `rivo://search?q=…` each push the expected frame. The Tasker broadcast opens the router
  directly when "Display over other apps" is granted and otherwise posts the
  `rivo://…` notification, as designed (D47).
- **Update endpoint.** `api.github.com/repos/iseaprojects/rivo/releases` answers 404 to an
  unauthenticated client while the repository is private, so the Update screen reports
  `Check failed` on this checkout. A repository with no releases answers `[]` and reads
  "Up to date"; the check works once the repository is public.
- **Artifacts.** `app-fast-debug.apk` 28.12 MB, `app-ytdlp-debug.apk` 105.50 MB,
  `app-fast-release-unsigned.apk` 3.82 MB (R8 + resource shrinking). Signing material is read
  from the environment or an ignored `keystore.properties` through `tools/release.sh`; no
  keystore is in the tree.
- **Permissions.** The app does not request `REQUEST_INSTALL_PACKAGES`: the update checker
  opens the release page. `SYSTEM_ALERT_WINDOW` is declared only as the opt-in that lets a
  Tasker broadcast start the UI from the background (D47).
- **Documentation.** README (English and Spanish), ARCHITECTURE, DEEPLINKS, DECISIONS (D1-D48),
  PARITY, EXTRACTORS, CONTRIBUTING, SECURITY and this file describe the shipped behaviour; the
  per-phase walkthroughs above are the manual checklist.
- **Known gaps.** The "Not verified" lists per phase name what was not exercised; none of
  them is a screen that is missing or stubbed.
