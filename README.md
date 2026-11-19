<div align="center">
<h1>Rivo</h1>
<p><strong>A native YouTube app for Android with a terminal soul.</strong></p>
<p>One filterable menu per level · background playback · downloads that survive the app being closed</p>
<p>
<a href="https://github.com/iseaprojects/rivo/actions/workflows/android.yml"><img src="https://github.com/iseaprojects/rivo/actions/workflows/android.yml/badge.svg?branch=main" alt="CI"></a>
<a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-2ac3de?style=flat-square" alt="License"></a>
<a href="#at-a-glance"><img src="https://img.shields.io/badge/Android-8.0%2B-2ac3de?style=flat-square&logo=android&logoColor=white" alt="Android 8.0+"></a>
<a href="#at-a-glance"><img src="https://img.shields.io/badge/Kotlin-2.4-2ac3de?style=flat-square&logo=kotlin&logoColor=white" alt="Kotlin 2.4"></a>
<a href="#at-a-glance"><img src="https://img.shields.io/badge/Compose-Material%203-2ac3de?style=flat-square&logo=jetpackcompose&logoColor=white" alt="Compose Material 3"></a>
<a href="#build"><img src="https://img.shields.io/badge/version-0.8.7--rivo.2-2ac3de?style=flat-square" alt="Version 0.8.7-rivo.2"></a>
</p>
<p><a href="README.md">English</a> · <a href="README.es.md">Español</a></p>
</div>

Rivo is a native Android port of [yt-x](https://github.com/Benexl/yt-x) 0.8.7 — the terminal
YouTube browser built around `fzf`, `yt-dlp` and `mpv`. It is not a wrapper around the shell
script: the nested-menu model, the declarative extension format, the `CONFIG_*` settings file
and the search filter grammar are reimplemented in Kotlin + Jetpack Compose, and playback uses
Media3/ExoPlayer instead of mpv.

The point of the port is to keep what makes yt-x good — keyboard-first navigation, one
filterable list per level, previews, per-item action menus, plain-text configuration — while
fixing what a terminal cannot do on a phone: background audio, downloads that survive the app
being killed, a media session, and DASH video with working audio.

## Why Rivo

- **The yt-x mental model, native.** Every screen is the same `MenuScreen<T>`: filter always
  focused, previews, explicit paging, breadcrumbs and the upstream keybindings. Nothing is a
  bespoke list, so every menu filters, pages and previews the same way.
- **One interface, two extractors.** NewPipeExtractor answers YouTube instantly; the bundled
  yt-dlp covers the rest of the sites and the full option set. Typed failures replace "error".
- **Playback outlives the screen.** A `MediaSessionService` owns the player, so lock screen,
  notification, Bluetooth, PiP and background audio all drive the same session.
- **Downloads are a feature.** A persisted queue, a foreground service, ranged resume,
  MediaStore/SAF/plain-directory targets and MP4 muxing without FFmpeg.
- **Configuration is a file.** The same `CONFIG_*` keys as upstream, editable in-app or over
  SAF, with comments preserved and runtime overrides that never touch it.
- **Nothing hidden.** No telemetry, no silent self-update, MIT with the upstream notice kept.

## At a glance

| | |
| --- | --- |
| **Platform** | Android 8.0+ (`minSdk` 26), `targetSdk` 37 |
| **Stack** | Kotlin 2.4.21 · Jetpack Compose · Material 3 · Media3 1.11.1 · Room · WorkManager |
| **Toolchain** | AGP 9.5.1 with built-in Kotlin · Gradle 9.9.1 · JDK 17 |
| **Languages** | English · Español · Português (Brasil), plus `rivo.lang/1` packs |
| **Themes** | Tokyo Night (default) · Catppuccin Mocha, both extensible via `*.theme.json` |
| **Flavors** | `fast` (NewPipeExtractor) · `ytdlp` (NewPipeExtractor + youtubedl-android) |
| **License** | MIT, upstream copyright preserved |

## What it does

| Area | What ships |
| --- | --- |
| **Menus** | Filterable nested menus, preview sheet on phones and docked panel on tablets, explicit `Next`/`Previous` paging, breadcrumb stack, per-item action menus, upstream keybindings, Nerd Font glyphs with ASCII fallback |
| **Feeds and search** | Home, subscriptions, trending, watch later, liked, history, clips; video/playlist/channel/movie search with the YouTube `sp` filters, live suggestions and channel-scoped search |
| **Extraction** | `VideoExtractor` over NewPipeExtractor and youtubedl-android, typed failures (`PRIVATE`, `AGE_RESTRICTED`, `DRM_PROTECTED`, `EXTRACTOR_BROKEN`, …), signed-URL re-resolve, cookies from `cookies.txt` or the built-in WebView, encrypted cookie storage, PO tokens |
| **Playback** | Progressive, DASH and HLS; background audio; `MediaSessionService`; PiP; subtitles; chapters; SponsorBlock; speed, quality and A-B loop; "most replayed" heatmap; queues from videos, playlists, mixes and channels; external-player handoff |
| **Downloads** | Video, audio-only and full playlist/channel queues; paused/resumed/retried/cancelled tasks; MediaStore, SAF tree or plain directory; MP4 muxing via `MediaMuxer`; download archive; local playback, share and open-with |
| **Library** | Room-backed recents, saved videos, custom playlists, saved remote playlists, search history and subscriptions, keyed by provider-scoped ids |
| **Configuration** | GUI settings and a text editor over the same `files/config`; theme, language, paging, cookies, extractor choice, cache retention, extensions and updates |
| **Extensions** | `rivo.ui/1` menu patches, `rivo.lang/1` languages, `rivo.theme/1` palettes, `.sites` and `.cmds` documents, loaded from a SAF folder; data only, never code (D43) |
| **Android surfaces** | Quick Settings tile, four-row recents widget, static and dynamic shortcuts, Tasker broadcast, `rivo://` deep links, documents provider, backup/transfer rules |
| **Updates** | GitHub release check with dotted-version comparison, per-channel (`stable`/prerelease), never installs an APK: it opens the release page (D45) |

```
Your Feed · Search · Subscriptions Feed · Trending · Watch Later · Liked Videos
Watch History · Clips · Playlists · Custom Playlists · Saved Videos · Recent
Channels · Miscellaneous
```

## Build

Requirements: JDK 17, Android SDK 37 (Build Tools 36+), Gradle 9.9.1. The Gradle wrapper,
`local.properties` and the SDK are the only local setup; nothing else is machine-specific.

```bash
./gradlew :app:assembleFastDebug     # NewPipeExtractor only
./gradlew :app:assembleYtdlpDebug    # + youtubedl-android (CPython, aria2)
./gradlew testFastDebugUnitTest      # 344 test cases (337 offline + 7 opt-in live checks)
./gradlew :app:lintFastDebug
```

Current artifacts: `app-fast-debug.apk` 28.12 MB, `app-ytdlp-debug.apk` 105.50 MB and
`app-fast-release-unsigned.apk` 3.82 MB (R8 + resource shrinking).

> AGP 9.5.1's `ProcessNavigationXmlTask` is not serializable by Gradle 9.9 yet, so
> `org.gradle.configuration-cache` is off in `gradle.properties`. Flip it back on when the AGP
> release notes list that fix.

Versions are `<upstream base>-rivo.<revision>` (`0.8.7-rivo.2` in this build). The update
checker compares the whole tag, including dotted revision identifiers, so a revision bump is a
real update. The check uses the unauthenticated GitHub API, so a **private** repository answers
`404` and the screen reports `Check failed`; it starts working as soon as the repository is
public or the API answers with a release list.

### Signing

Release signing is read from the environment, so no keystore is ever stored in the repo:

```bash
RIVO_KEYSTORE=/path/release.jks RIVO_KEYSTORE_PASSWORD=… RIVO_KEY_ALIAS=… RIVO_KEY_PASSWORD=… \
  ./gradlew :app:assembleFastRelease
```

`tools/release.sh` wraps that and reads the same four values from an ignored
`keystore.properties` when the variables are unset (`tools/release.sh ytdlp`,
`tools/release.sh fast --unsigned` for a check without keys).

### Flavors

| Flavor | Extraction | Use case |
| --- | --- | --- |
| `fast` | NewPipeExtractor v0.27.1 | Default. Sub-second search, small APK, YouTube only. |
| `ytdlp` | NewPipeExtractor + youtubedl-android 0.18.1 | Non-YouTube sites, chapters, full yt-dlp option parity, self-update. |

The `ytdlp` flavor bundles **yt-dlp 2025.11.12** (and yt-dlp-ejs 0.3.1) inside
youtubedl-android, which keeps its historical library name; it is the maintained yt-dlp, not the
discontinued youtube-dl. `CONFIG_EXTRACTOR` chooses `auto` (default: NewPipe for YouTube,
yt-dlp elsewhere), `newpipe` or `ytdlp`, and `CONFIG_YTDLP_CHANNEL` selects the release feed used
by the in-app update, whose action and channel live on the extractor screen. YouTube through the
bundled extractor is the one path that needs that update (or `CONFIG_PO_TOKEN`) to get past the
CDN's `403` in late 2026; `auto` sidesteps it with NewPipe. The same screen exposes the update
action and the `stable`/`nightly`/`master` channel.

`youtubedl-android` resolves from Maven Central (`io.github.junkfood02.youtubedl-android`, the
maintained fork); upstream's JitPack repo stopped publishing after 0.14.0 and its `0.18.x` tags
fail to build there.

The `ytdlp` flavor deliberately does **not** ship FFmpegKit. Downloads that need joining are
remuxed with the platform `MediaMuxer` (progressive MP4 video + MP4 audio, no re-encode), which
keeps the APK small and avoids the licensing and size cost of a full FFmpeg build; HLS/DASH
manifests that cannot be fetched as one file are handed to the bundled yt-dlp.

### Font

Nerd Font glyphs are optional. Drop any patched font as
`app/src/main/assets/fonts/*.ttf` (e.g. `JetBrainsMonoNerdFont-Regular.ttf`) and the app picks
it up; without it every menu still works through the ASCII icon fallback. The font binary is
git-ignored.

## Deep links

Everything Android-side enters through one router: `rivo://` links, shared text, `VIEW`
intents for YouTube URLs, Tasker broadcasts and shortcuts.

| Link | Opens |
| --- | --- |
| `rivo://` | Main menu |
| `rivo://search?q=…&type=video\|playlist\|channel\|shorts\|movie` | Search, optionally scoped |
| `rivo://feed?kind=subscriptions\|trending\|watch-later\|liked\|history\|clips` | Feed |
| `rivo://saved` · `rivo://recent` | Library |
| `rivo://downloads` | Download queue and files |
| `rivo://misc` · `rivo://config` | Miscellaneous and config screens |
| `rivo://channels` · `rivo://channel?name=…&tab=…` | Channels |
| `rivo://playlist?name=…&id=…` · `rivo://custom-playlist?name=…` | Playlists |
| `rivo://custom-cmd?name=…` | A saved custom command |
| `rivo://watch?v=…` · `rivo://media?url=…` | Media actions for a video or any supported URL |

`exit=true` closes the player or command screen when the action finishes;
`playlist-skip=true` starts a playlist at the second item. Automation can pass the same
overrides as intent extras (`RIVO_ACTION`, `RIVO_PLAY`, `RIVO_LISTEN`, `RIVO_DOWNLOAD`,
`RIVO_INPUT`, `RIVO_PER_PAGE`, `RIVO_CONFIG`), and a Tasker task broadcasts
`io.github.iseaprojects.rivo.action.TASKER_FIRE` with `rivo_uri`. Runtime overrides are applied
in memory and never written to `files/config`.

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
                                                  └────────▶ MenuScreen<T> (catalog + media)
```

- **Menus are data.** A `MenuNode` names its label/icon keys and either a child menu, an inline
  `children` list, or a handler id. `MenuEngine` builds → filters → sorts → injects → localizes,
  the direct analogue of yt-x's `_menu_*` functions plus `[sort]`, `filter_regex`,
  `extra_actions` and `handler` arguments in a `.ui` extension.
- **Config is a file.** `filesDir/config` stays the single source of truth, with the upstream
  `CONFIG_*` names, comments preserved on write, and missing keys appended on startup. The GUI
  settings screen and the text editor write the same bytes, and the bundled documents provider
  exposes it over SAF.
- **Everything is one activity.** Every level is the same list-of-entries screen, so navigation
  is a stack of `MenuFrame`s, not a graph of composables.
- **The library is Room; everything else is a file.** Saved videos, recents, subscriptions,
  custom playlists, saved playlists and search history live in a seven-table Room database keyed
  by provider-scoped ids. Menus see an immutable snapshot (`MenuLibrary`) instead of querying,
  so "Save" vs "UnSave" is a synchronous decision that relabels every row at once, and a
  Prompt/Confirm dialog re-dispatches the same handler with the answer instead of keeping
  "waiting" state anywhere.
- **Extraction is one interface with two backends.** `VideoExtractor` returns app models, never
  extractor objects, so lists survive process death and the UI never learns which backend
  answered. `auto` sends YouTube to NewPipeExtractor and everything else to yt-dlp; a broken
  NewPipe parse is retried once with yt-dlp, and signed stream URLs are re-resolved when they
  expire. Failures are typed so screens can explain a refusal instead of showing "error".
- **Playback outlives the screen.** `PlaybackService` owns the `ExoPlayer` and the
  `MediaSession`; `PlayerActivity` is a view over a `MediaController`, with no request of its
  own. Closing the player keeps the audio going (`CONFIG_DISOWN_PLAYER`), the notification and
  Bluetooth controls are the same session commands, and PiP re-parents a `TextureView` that
  survives the window change. The queue is resolved lazily behind a small prefetch window, so
  `Play All` starts on the first item instead of extracting twenty-six of them first.
- **Cookies are credentials.** A `cookies.txt` import or the built-in sign-in screen fills an
  `EncryptedSharedPreferences` store; cookies are attached by origin only, and only when
  `CONFIG_COOKIES_ENABLED` is on. PO tokens are a config value, translated to each backend's
  format.

More detail: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md), design choices in
[`docs/DECISIONS.md`](docs/DECISIONS.md), the extraction layer in
[`docs/EXTRACTORS.md`](docs/EXTRACTORS.md), upstream feature mapping in
[`docs/PARITY.md`](docs/PARITY.md), and the verification checklist in
[`docs/VERIFICATION.md`](docs/VERIFICATION.md).

## Playback: the DASH audio bug

Upstream yt-x on Android plays through Termux + mpv, and the common workaround for the resulting
"video plays, audio missing" symptom is to force a progressive (muxed) format, which caps
quality at 720p or lower. The port fixes this at the source: DASH video and DASH audio are
resolved as separate streams and handed to ExoPlayer as a
`MergingMediaSource(video, audio)`, with `ProgressiveMediaSource` only for genuinely muxed
streams and `HlsMediaSource` for live. That means 1080p/1440p/4K video with audio, seekable
subtitles and correct duration, without re-encoding anything.

## Intended use

Rivo is a client for content the user is already allowed to watch: public YouTube, self-hosted
PeerTube, Dailymotion, and any site yt-dlp supports. It is built for personal use — browsing
subscriptions, saving videos for offline viewing on a commute, listening to podcasts and music
in the background.

It does not bypass DRM, paywalls, age gates or regional restrictions, and it reports those cases
as typed errors (`Availability.DRM_PROTECTED`, `AGE_RESTRICTED`, `GEO_BLOCKED`) instead of
attempting to work around them. Cookies exist so that a signed-in user can see their own
subscriptions and playlists, not to impersonate anyone.

Nothing here is a substitute for the terms of service of the sites it reads from; that
responsibility sits with the person running it.

## Distribution

Google Play's device and content policies make a general-purpose extractor app inadmissible, so
this is distributed as a signed APK: GitHub Releases on this repository, and through
F-Droid-style repositories that build from source. The app has a built-in update checker that
reads the Rivo GitHub releases and opens the release page; installing an APK is left to the
browser and the system package installer, so no silent self-update path exists.

## Status

The port is complete against the F1–F8 scope: every screen is wired, both flavors build, and the
test suite covers config, menus, launch routing, themes, extraction and the library.

<details>
<summary><strong>Phase history (F1–F8)</strong></summary>

| Phase | Scope | State |
| --- | --- | --- |
| **F1** | Architecture, build, manifest, config/i18n/theme engines, declarative menus, extension loader, launcher/deep links, documents provider | delivered |
| **F2** | `VideoExtractor` router, NewPipeExtractor + youtubedl-android backends, typed failures, URL re-resolve, cookies, PO tokens | delivered |
| **F3** | Generic `MenuScreen<T>`: fuzzy filter, preview panel/sheet, paging, media lists | delivered |
| **F4** | Room library: recent, saved, custom playlists, search history, subscriptions | delivered |
| **F5** | Player: Media3 progressive/DASH/HLS, background audio, MediaSessionService, PiP, subtitles, SponsorBlock | delivered |
| **F6** | Downloads: MediaStore/SAF/plain-directory targets, persisted queue, foreground service, resume, MP4 muxing, archive, local playback | delivered |
| **F7** | Settings editor, extensions from SAF, tiles/Tasker/widget/shortcuts, update checker | delivered |
| **F8** | Parity close-out, tests, verification checklist | delivered |

F1 compiled as a complete menu app: it launched, rendered the main menu, navigated, edited
config, switched theme and language, and answered `rivo://` links. Menus owned by later phases
(playback, downloads) resolved to a frame that stated which action was not wired yet; those
frames became real screens in F5 and F6.

F2 added the extraction layer the later phases consume: one interface, two backends, typed
failures, signed-URL refresh, and cookie/PO-token handling. F3 replaced the terminal loop with
the list shell — filter always focused, Nerd Font rows, explicit paging, a preview sheet on
phones and keybindings. F4 gave the app a memory: Room stores saved videos, recents,
subscriptions, playlists and search history, and menus decide from a snapshot so rows relabel
immediately. F5 made it a player: a `MediaSessionService` behind a full-screen surface, overlay
controls, heatmap, chapters, SponsorBlock and lazy queues that re-resolve expired URLs. F6 made
downloads a feature: a persisted queue worked by a foreground service, three target kinds,
MediaMuxer muxing, ranged resume and an archive. F7 turned the remaining configuration into
screens and added the tile, widget, shortcuts and Tasker entry points.

F7 limitations are deliberate: extensions are data and cannot ship code; custom-command yt-dlp
options are handed only to Termux (`YT_X_YTDLP_OPTS`) and never bypass the in-app extractor
interface; the update checker never installs an APK; and a Tasker broadcast opens the UI directly
only when Rivo holds "Display over other apps", otherwise it posts a notification that opens the
same link (and is logged and dropped if notifications are off too).

</details>

## Repository layout

```
.
├── README.md                       this file
├── README.es.md                    Spanish version of this file
├── CONTRIBUTING.md                 build matrix and review rules
├── CHANGELOG.md                    release history
├── SECURITY.md                     private vulnerability reports
├── LICENSE                         MIT, upstream notice preserved
├── tools/check_strings.py          TXT_* coverage check (must report 0 missing)
├── tools/release.sh                signed release build from keystore.properties
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

## Project

Rivo is developed at [iseaprojects/rivo](https://github.com/iseaprojects/rivo). Issues and
releases live there; [CONTRIBUTING.md](CONTRIBUTING.md) has the build matrix and the rules that
are not negotiable. `yt-x` in this repository always names the upstream shell project; `Rivo` is
the Android app. Rivo is not affiliated with YouTube or with the upstream project.

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
