# Rivo

Rivo is a native YouTube player and downloader for Android: one filterable menu per level,
background playback, and downloads that survive the app being closed.

A native Android port of [yt-x](https://github.com/Benexl/yt-x) 0.8.7 — the terminal YouTube
browser built around fzf, yt-dlp and mpv. This is not a wrapper around the shell script:
the nested-menu model, the declarative extension format, the `CONFIG_*` settings file and
the search filter grammar are reimplemented in Kotlin + Jetpack Compose, and playback uses
Media3/ExoPlayer instead of mpv.

The point of the port is to keep what makes yt-x good — keyboard-first navigation, one
filterable list per level, previews, per-item action menus, plain-text configuration — while
fixing what a terminal cannot do on a phone: background audio, downloads that survive the
app being killed, a media session, and DASH video with working audio.

```
Your Feed · Search · Subscriptions Feed · Trending · Watch Later · Liked Videos
Watch History · Clips · Playlists · Custom Playlists · Saved Videos · Recent
Channels · Miscellaneous
```

## Project

Rivo is developed at [iseaprojects/rivo](https://github.com/iseaprojects/rivo). Issues and
releases live there; [CONTRIBUTING.md](CONTRIBUTING.md) has the build matrix and the rules
that are not negotiable. `yt-x` in this repository always names the upstream shell project;
`Rivo` is the Android app. Rivo is not affiliated with YouTube or with the upstream project.

## Status

| Phase | Scope | State |
| --- | --- | --- |
| **F1** | Architecture, build, manifest, config/i18n/theme engines, declarative menus, extension loader, launcher/deep links, documents provider | **delivered** |
| **F2** | `VideoExtractor` router, NewPipeExtractor + youtubedl-android backends, typed failures, URL re-resolve, cookies, PO tokens | **delivered** |
| F3 | Generic `MenuScreen<T>`: fuzzy filter, preview panel/sheet, paging, media lists | **delivered** |
| F4 | Room library: recent, saved, custom playlists, search history, subscriptions | **delivered** |
| F5 | Player: Media3 progressive/DASH/HLS, background audio, MediaSessionService, PiP, subtitles, SponsorBlock | **delivered** |
| F6 | Downloads: MediaStore/SAF/plain-directory targets, persisted queue, foreground service, resume, MP4 muxing, archive, local playback | **delivered** |
| F7 | Settings editor, extensions from SAF, tiles/Tasker/widget/shortcuts, update checker | **delivered** |
| F8 | README parity close-out, tests, verification checklist | **delivered** |

Everything in F1 compiled as a complete menu app: it launched, rendered the main menu,
navigated, edited config, switched theme and language, and answered `rivo://` links. Menus
owned by later phases (playback, downloads) then resolved to a frame that stated which action
was not wired yet rather than doing nothing; those frames became real screens in F5 and F6.

F2 adds the extraction layer the later phases consume: one interface, two backends, typed
failures, signed-URL refresh, and cookie/PO-token handling. It has no screens of its own -
F3 is the first phase that renders what it returns.

F3 replaces the terminal loop with the app's list shell. Every menu is the same
`MenuScreen<T>`: filter always focused, Nerd Font rows, explicit `Next`/`Previous` paging,
a preview sheet on phones (`space` or the toolbar button) and a docked panel on tablets,
and the upstream keybindings. Feed, search, channel and playlist frames are
extractor-backed and use that shell, with search chips for the YouTube `sp` filters, live
suggestions and channel-scoped search.

F4 gives the app a memory. Room stores saved videos, recents, subscriptions, custom
playlists, saved remote playlists and search history; every menu that used to say "not
stored yet" now reads and writes it. Save/UnSave, Subscribe/Unsubscribe and playlist
membership are decided from a snapshot, so a row relabels the moment it changes, and
destructive actions ask first through a Prompt/Confirm round trip. At that point downloads
were still pending (F6), so their rows named the action they would perform.

F5 makes it a player. Watch/Listen open a full-screen surface backed by a
`MediaSessionService`, so closing it, locking the phone or entering picture-in-picture does
not stop the audio. The overlay carries speed, quality, audio track, subtitles, chapters,
A–B loop and the external-player handoff; the timeline draws the "most replayed" heatmap,
chapter ticks and SponsorBlock segments. Queues come from a single video, a playlist, a mix
or a channel tab, are resolved lazily, and re-resolve a signed URL when it expires instead
of failing the item.

F6 makes downloads a feature rather than a handoff. `Download` and `Download (Audio Only)`
queue a video, a playlist or a channel, and the queue survives leaving the app: a `dataSync`
foreground service works through it, one aggregate notification shows progress, and the
Downloads menu lists tasks and files with pause, resume, retry, cancel, remove, clear and
open-folder. Files land in a MediaStore collection, a picked SAF folder or a plain directory
(`CONFIG_DOWNLOAD_DIR`), video-only renditions are muxed with their audio at download time,
a paused transfer keeps its part and resumes with a ranged request, and an archive stops the
same rendition being fetched twice. Downloaded files play in the internal player, share, and
open in other apps.

F7 turns the remaining configuration into screens and gives the app Android entry points.
Settings are the same filterable menus as everything else — theme, language, paging, cookies
and accounts, extractor choice, cache retention, extensions and updates — and each writes the
same plain-text `files/config`, comments included. An extension folder is picked through SAF;
`.ui`, `.lang`, `.theme`, `.sites` and `.cmds` documents load from it, appear in a per-file
list, and can be reloaded or forgotten, with a theme or language applying without a restart.
Custom commands are created, edited, run through the normal resolver and mirrored as dynamic
shortcuts. The update screen reads Rivo releases and opens the release page, cache
retention runs as periodic work, and a Quick Settings tile, a four-row recents widget and a
Tasker broadcast all enter through the same `rivo://` router.

F7 limitations are deliberate: extensions are data and cannot ship code; custom-command yt-dlp
options are handed only to Termux (`YT_X_YTDLP_OPTS`) and never bypass the in-app extractor
interface; the update checker never installs an APK; and a Tasker broadcast opens the UI
directly only when Rivo holds "Display over other apps", otherwise it posts a notification
that opens the same link (and is logged and dropped if notifications are off too).

## Build

Requirements: JDK 17, Android SDK 37 (Build Tools 36+), Gradle 9.9.1. The Gradle
wrapper, `local.properties` and the SDK are the only local setup; nothing else is
machine-specific.

Stack: AGP 9.5.1 with **built-in Kotlin** (no `kotlin-android` plugin), Kotlin 2.4.21,
KSP 2.3.14, Compose BOM 2026.09.00, Media3 1.11.1, Room 2.8.5, WorkManager 2.12.0,
`compileSdk`/`targetSdk` 37, `minSdk` 26.

```bash
./gradlew :app:assembleFastDebug     # NewPipeExtractor only
./gradlew :app:assembleYtdlpDebug    # + youtubedl-android (CPython, aria2)
./gradlew testFastDebugUnitTest      # 344 test cases (337 offline + 7 opt-in live checks)
./gradlew :app:lintFastDebug
```

> AGP 9.5.1's `ProcessNavigationXmlTask` is not serializable by Gradle 9.9 yet, so
> `org.gradle.configuration-cache` is off in `gradle.properties`. Flip it back on when
> the AGP release notes list that fix.

Versions are `<upstream base>-rivo.<revision>` (`0.8.7-rivo.2` in this build). The update
checker compares the whole tag, including dotted revision identifiers, so a revision bump is
a real update.

Release signing is read from the environment, so no keystore is ever stored in the repo:

```bash
RIVO_KEYSTORE=/path/release.jks RIVO_KEYSTORE_PASSWORD=… RIVO_KEY_ALIAS=… RIVO_KEY_PASSWORD=… \
  ./gradlew :app:assembleFastRelease
```

### Flavors

| Flavor | Extraction | Use case |
| --- | --- | --- |
| `fast` | NewPipeExtractor v0.27.1 | Default. Sub-second search, small APK, YouTube only. |
| `ytdlp` | NewPipeExtractor + youtubedl-android 0.18.1 | Non-YouTube sites, chapters, full yt-dlp option parity, self-update. |

The `ytdlp` flavor bundles **yt-dlp 2025.11.12** (and yt-dlp-ejs 0.3.1) inside
youtubedl-android, which keeps its historical library name; it is the maintained yt-dlp, not
the discontinued youtube-dl. `CONFIG_EXTRACTOR` chooses `auto` (default: NewPipe for
YouTube, yt-dlp elsewhere), `newpipe` or `ytdlp`, and `CONFIG_YTDLP_CHANNEL` selects the
release feed used by the in-app update, whose action and channel live on the extractor screen.
YouTube through the bundled extractor is the one path that needs that update (or
`CONFIG_PO_TOKEN`) to get past the CDN's `403` in late 2026; `auto` sidesteps it with NewPipe.
The same screen exposes the update action and the `stable`/`nightly`/`master` channel.

`youtubedl-android` resolves from Maven Central (`io.github.junkfood02.youtubedl-android`,
the maintained fork); upstream's JitPack repo stopped publishing after 0.14.0 and its
`0.18.x` tags fail to build there.

The `ytdlp` flavor deliberately does **not** ship FFmpegKit. Downloads that need joining are
remuxed with the platform `MediaMuxer` (progressive MP4 video + MP4 audio, no re-encode),
which keeps the APK small and avoids the licensing and size cost of a full FFmpeg build;
HLS/DASH manifests that cannot be fetched as one file are handed to the bundled yt-dlp.

### Font

Nerd Font glyphs are optional. Drop any patched font as
`app/src/main/assets/fonts/*.ttf` (e.g. `JetBrainsMonoNerdFont-Regular.ttf`) and the app
picks it up; without it every menu still works through the ASCII icon fallback. The font
binary is git-ignored.

## How it works

```
MainActivity ──intent──▶ LaunchSpecParser ──▶ LaunchInbox ──▶ LaunchRouter
                                                                    │
                                                          MenuNavController
                                                                    │
                                             MenuEngine ──▶ MenuCatalog (+ .ui.json patches)
                                                  │                 │
                                            handlers          localized MenuNode list
                                                  │                 │
                                                  └────────▶ MenuScreen&lt;T&gt; (catalog + media)
```

- **Menus are data.** A `MenuNode` names its label/icon keys and either a child menu, an
  inline `children` list, or a handler id. `MenuEngine` builds → filters → sorts → injects →
  localizes, which is the direct analogue of yt-x's `_menu_*` functions plus `[sort]`,
  `filter_regex`, `extra_actions` and `handler` arguments in a `.ui` extension.
- **Config is a file.** `filesDir/config` stays the single source of truth, with the
  upstream `CONFIG_*` names, comments preserved on write, and missing keys appended on
  startup. The GUI settings screen and the text editor write the same bytes. It is
  reachable over SAF through the bundled documents provider.
- **Overrides are runtime-only.** `RIVO_PER_PAGE=60`, `RIVO_CONFIG="CONFIG_THEME=…"` and
  `rivo://…?per_page=60` never touch the file — the Android equivalent of `YT_X_*`
  environment variables.
- **Extensions are declarative.** `rivo.ui/1` patches menus, `rivo.lang/1` adds a language,
  `*.theme.json` adds a palette. A broken extension is logged and ignored, never fatal.
- **Everything is one activity.** Every level of the app is the same list-of-entries
  screen, so navigation is a stack of `MenuFrame`s, not a graph of composables.
- **The library is Room; everything else is a file.** Saved videos, recents, subscriptions,
  custom playlists, saved playlists and search history live in a seven-table Room database
  keyed by provider-scoped ids. Menus see an immutable snapshot (`MenuLibrary`) instead of
  querying, so "Save" vs "UnSave" is a synchronous decision that relabels every row at once,
  and a Prompt/Confirm dialog re-dispatches the same handler with the answer instead of
  keeping "waiting" state anywhere.
- **Extraction is one interface with two backends.** `VideoExtractor` returns app models,
  never extractor objects, so lists survive process death and the UI never learns which
  backend answered. `auto` sends YouTube to NewPipeExtractor and everything else to yt-dlp;
  a broken NewPipe parse is retried once with yt-dlp, and signed stream URLs are re-resolved
  when they expire. Failures are typed (`PRIVATE`, `AGE_RESTRICTED`, `DRM_PROTECTED`,
  `EXTRACTOR_BROKEN`, …) so screens can explain a refusal instead of showing "error".
- **Playback outlives the screen.** `PlaybackService` owns the `ExoPlayer` and the
  `MediaSession`; `PlayerActivity` is a view over a `MediaController`, with no request of its
  own. Closing the player keeps the audio going (`CONFIG_DISOWN_PLAYER`), the notification and
  Bluetooth controls are the same session commands, and PiP re-parents a `TextureView` that
  survives the window change. The queue is resolved lazily behind a small prefetch window, so
  `Play All` starts on the first item instead of extracting twenty-six of them first.

- **Cookies are credentials.** A `cookies.txt` import or the built-in sign-in screen fills
  an `EncryptedSharedPreferences` store; cookies are attached by origin only, and only when
  `CONFIG_COOKIES_ENABLED` is on. PO tokens are a config value, translated to each backend's
  format.

More detail: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md), design choices in
[`docs/DECISIONS.md`](docs/DECISIONS.md), the extraction layer in
[`docs/EXTRACTORS.md`](docs/EXTRACTORS.md), upstream feature mapping in
[`docs/PARITY.md`](docs/PARITY.md), and the verification checklist in
[`docs/VERIFICATION.md`](docs/VERIFICATION.md).

## Playback: the DASH audio bug

Upstream yt-x on Android plays through Termux + mpv, and the common workaround for the
resulting "video plays, audio missing" symptom is to force a progressive (muxed) format,
which caps quality at 720p or lower. The port fixes this at the source: DASH video and
DASH audio are resolved as separate streams and handed to ExoPlayer as a
`MergingMediaSource(video, audio)`, with `ProgressiveMediaSource` only for genuinely muxed
streams and `HlsMediaSource` for live. That means 1080p/1440p/4K video with audio, seekable
subtitles and correct duration, without re-encoding anything.

## Intended use

Rivo is a client for content the user is already allowed to watch: public
YouTube, self-hosted PeerTube, Dailymotion, and any site yt-dlp supports. It is built for
personal use — browsing subscriptions, saving videos for offline viewing on a commute,
listening to podcasts and music in the background.

It does not bypass DRM, paywalls, age gates or regional restrictions, and it will report
those cases as typed errors (`Availability.DRM_PROTECTED`, `AGE_RESTRICTED`,
`GEO_BLOCKED`) instead of attempting to work around them. Cookies exist so that a signed-in
user can see their own subscriptions and playlists, not to impersonate anyone.

Nothing here is a substitute for the terms of service of the sites it reads from; that
responsibility sits with the person running it.

## Distribution

Google Play's device and content policies make a general-purpose extractor app
inadmissible, so this is distributed as a signed APK: GitHub Releases on this repository,
and through F-Droid-style repositories that build from source. The app has a built-in
update checker (F7) that reads the Rivo GitHub releases and opens the release page;
installing an APK is left to the browser and the system package installer, so no silent
self-update path exists.

## License

MIT, like upstream yt-x. The original copyright notice is preserved:

```
MIT License

Copyright (c) 2024 Benexl
Copyright (c) 2026 Rivo contributors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Repository layout

```
.
├── README.md                       this file
├── CONTRIBUTING.md                 build matrix and review rules
├── CHANGELOG.md                    release history
├── SECURITY.md                     private vulnerability reports
├── LICENSE                         MIT, upstream notice preserved
├── tools/check_strings.py          TXT_* coverage check (must report 0 missing)
├── .github/workflows/android.yml   CI: tests, lint, both debug APKs
├── build.gradle.kts                root build (plugins only)
├── settings.gradle.kts             repositories, module list
├── gradle.properties               JVM args, AndroidX, non-transitive R
├── gradle/
│   ├── libs.versions.toml          every pinned version
│   └── wrapper/gradle-wrapper.properties
├── app/
│   ├── build.gradle.kts            flavors, KSP, buildConfig fields, dependencies
│   ├── proguard-rules.pro          keep rules for serialization and extractors
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml components declared per phase (F5-F7 add theirs)
│       │   ├── assets/
│       │   │   ├── config.default              canonical CONFIG_* defaults
│       │   │   └── extensions/
│       │   │       ├── langs/en|es|pt-BR.lang.json
│       │   │       └── themes/tokyo-night|catppuccin-mocha.theme.json
│       │   ├── java/io/github/iseaprojects/rivo/
│       │   │   ├── RivoApp.kt                   Application + AppGraph
│       │   │   ├── core/
│       │   │   │   ├── config/                 ConfigKeys, ConfigParser, ConfigStore, ConfigSnapshot
│       │   │   │   ├── i18n/Strings.kt         lang bundles, locale resolution
│       │   │   │   ├── launch/                 LaunchSpec, LaunchRouter, LaunchInbox
│       │   │   │   ├── menu/                   MenuNode, catalog, engine, navigator, patcher, icons
│       │   │   │   ├── menu/extension/         rivo.ui/1 loader, SAF folder, site/command documents
│       │   │   │   ├── update/                 GitHub release feed, version compare
│       │   │   │   ├── model/Media.kt          MediaSummary, Playlist/Channel, FeedKind, PageInfo
│       │   │   │   ├── model/LibraryKeys.kt    provider-scoped identity, `!N` recall
│       │   │   │   └── theme/ThemeSpec.kt      theme extension model + hex parsing
│       │   │   ├── data/
│       │   │   │   ├── extract/                VideoExtractor, router, NewPipe backend, yt-dlp mapper
│       │   │   │   ├── library/                Room entities, DAO, repository, mapping
│       │   │   │   └── net/                    cookie store, cookies.txt, PO token, OkHttp transport
│       │   │   ├── di/AppGraph.kt              manual DI
│       │   │   ├── menu/BuiltInMenus.kt        main, miscellaneous, config menus
│       │   │   ├── menu/MediaMenus.kt          per-item, channel, playlist and library actions
│       │   │   ├── menu/Glyphs.kt              literal Nerd Font glyphs
│       │   │   ├── menu/handlers/              navigation, config, URL, share, library handlers
│       │   │   ├── platform/                    notifications, tile, receiver, widget, shortcuts
│       │   │   ├── provider/RivoDocumentsProvider.kt
│       │   │   └── ui/
│       │   │       ├── MainActivity.kt         single activity, intent intake
│       │   │       ├── RivoViewModel.kt         back stack + inbox across rotation
│       │   │       ├── RivoRoot.kt              state wiring, back handling, snackbars, dialogs
│       │   │       ├── login/WebLoginActivity.kt  WebView sign-in for cookies
│       │   │       ├── screens/MenuScreen.kt     list shell: filter, pager, preview, keys
│       │   │       ├── screens/PromptDialogs.kt  text prompt + destructive confirm
│       │   │       ├── media/                   extractor- and library-backed screens
│       │   │       └── theme/Theme.kt, Fonts.kt
│       │   └── res/                            system strings, themes, icons, shortcuts, widget
│       └── test/java/io/github/iseaprojects/rivo/          config, menu, launch, theme, extractor, library tests
└── docs/
    ├── ARCHITECTURE.md
    ├── DECISIONS.md
    ├── EXTRACTORS.md
    ├── PARITY.md
    ├── VERIFICATION.md
    └── extensions/                             annotated extension examples
```
