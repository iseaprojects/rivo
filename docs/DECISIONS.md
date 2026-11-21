# Decisions

Short records of the choices that shape the port, with the reasoning that would
otherwise be lost between phases. Each entry is the decision, why it was made, and what
it costs.

---

## D1 — Native Kotlin/Compose app, not a Termux wrapper

**Decision.** Reimplement yt-x as an Android app; do not ship a script plus a terminal
emulator.

**Why.** Upstream requires `fzf`, `mpv`, `yt-dlp`, `jq` and a terminal. On Android that
means Termux, and every limitation the port is meant to fix comes from there: no media
session, no background audio that survives process death, no share-sheet integration,
downloads tied to a foreground shell, and the DASH audio bug caused by handing streams to
mpv through a workaround.

**Cost.** Extraction, playback and download logic must be reimplemented and kept in sync
with upstream semantics by hand. Mitigated by the parity table and by reusing the exact
config keys, search `sp` codes and menu vocabulary.

---

## D2 — One activity, navigation as a stack of menu frames

**Decision.** `MainActivity` hosts a single Compose tree; screens are `MenuFrame`s in a
`MenuNavController`, not destinations in a `NavHost`.

**Why.** Every level of yt-x — main menu, misc, channel tabs, search results, media
actions — is the same thing: a list of entries with a filter and a preview. Modeling that
as navigation destinations duplicates state for no benefit, and the back stack has to be
inspectable anyway to render the breadcrumb bar that upstream shows.

**Cost.** No deep-link arguments through `NavController`, no per-screen `ViewModel`
scoping. Deep links go through `LaunchSpec`/`LaunchRouter` instead, which is a better fit
for `am start` and Tasker anyway.

---

## D3 — Declarative `MenuNode` + JSON patches instead of code-only menus

**Decision.** Menus are `MenuNode` data with handler ids; extensions patch them with
`rivo.ui/1` (`sort`, `filter`, `inject`).

**Why.** This is the upstream extension contract. `_menu_main [sort] [filter_regex]
[extra_actions] [handler]` is exactly "reorder, remove, insert", and `.ui` files already
exist in the wild. Expressing menus as data keeps the extension engine honest: if the
built-in menus could do something patches cannot, the abstraction would be leaky.

**Cost.** Handler ids become an API surface; renaming one breaks extensions. They are
therefore grouped in `HandlerIds` and documented.

---

## D4 — Config stays a plain text file

**Decision.** `filesDir/config`, upstream `CONFIG_*` names, comment-preserving edits,
atomic writes, append-only migration. GUI settings and external editors write the same
file.

**Why.** The config being readable, diffable and copyable between a desktop yt-x install
and the phone is a feature, not an implementation detail. DataStore/SharedPreferences
would have been easier and would have broken it.

**Cost.** Every read is a parse, so `ConfigStore` caches the parsed map in a `StateFlow`
and re-reads on `onResume` (external edits) and on explicit writes. Typed accessors never
throw; a malformed value degrades to the caller's default.

---

## D5 — Strings as JSON assets, not Android resources

**Decision.** `assets/extensions/langs/<tag>.lang.json` with upstream `TXT_*` keys;
`Strings` abstraction in the UI; Android resources only for system-rendered strings.

**Why.** Upstream language packs are key→string files that users install. Compiling them
into `values-*` would make installing a language require a rebuild, and would fork the key
namespace from upstream. It also lets one bundle be partial and fall back per key.

**Cost.** No `getString()` in the UI, no automatic `Locale` plurals, and translations are
not lint-checked. Acceptable: the chains are short and the fallback is explicit.

---

## D6 — Dynamic color disabled

**Decision.** No Material You. Palette comes from a `*.theme.json` extension, default
Tokyo Night.

**Why.** The visual identity is the terminal palette, including the fzf accent colors used
for icons and previews. Wallpaper-derived colors would clash with the icon column and make
screenshots/tests unstable.

**Cost.** One less "modern Android" box ticked. Users who want Material You can write a
theme extension.

---

## D7 — Media3 with `MergingMediaSource`, not an external player by default

**Decision.** Ship an in-app ExoPlayer: `ProgressiveMediaSource` for muxed streams,
`MergingMediaSource(video, audio)` for DASH, `HlsMediaSource` for live.

**Why.** This is the fix for the reason the port exists. Forcing progressive formats to
get audio caps quality at 720p; sending streams to mpv/MPV-based players has the same
problem on Android. Merging two DASH streams gives full resolution with audio and no
re-encoding, and `MediaSessionService` then provides background audio, Bluetooth controls
and Android Auto for free.

**Cost.** More code than an `Intent.ACTION_VIEW` handoff, and codec/container combinations
must be handled explicitly (adaptive audio track selection, `MergingMediaSource` with a
single period). External players remain available as an action.

---

## D8 — mp4parser for DASH muxing, no FFmpegKit

**Superseded by D41.** F6 shipped with the platform `MediaMuxer` instead: the downloads only
join progressive MP4 video with MP4 audio (`MediaMuxer` does not accept segmented/fMP4
input), so the mp4parser dependency was not needed and is not in the build. The reasoning
about FFmpegKit still holds.

**Decision.** Downloads fetch video and audio separately and remux with mp4parser.

**Why.** FFmpegKit is tens of megabytes, was retired upstream, and drags licensing and
build questions. Remuxing two fMP4 streams into one container needs no transcoding, which
is exactly mp4parser's job.

**Cost.** Only container-compatible pairs can be merged. When a stream pair is not
compatible the download falls back to the best muxed format and says so, instead of
inventing a transcode path.

---

## D9 — Two flavors behind one `VideoExtractor` interface

**Decision.** `fast` = NewPipeExtractor only; `ytdlp` = NewPipeExtractor +
youtubedl-android.

**Why.** The two audiences are different: someone who wants a 12 MB app that browses
YouTube quickly, and someone who needs cookies, PO tokens or non-YouTube sites. A single
build would either bloat the first or cripple the second.

**Cost.** Two APKs, and every extractor-facing feature must be tested twice. The interface
is deliberately narrow (`video`, `search`, `suggestions`, `channel`, `playlist`, `kiosk`) so
that the flavor difference does not leak into the UI.

---

## D10 — Manual dependency graph

**Decision.** `AppGraph` in `Application`, constructor injection, no Hilt.

**Why.** The graph is ~15 singletons with obvious lifetimes. Hilt would add a KSP stage, an
extra generated layer and a second annotation processor (Room already needs KSP), for
wiring that fits on one screen and reads better explicitly.

**Cost.** New services must be added to `AppGraph` by hand. Acceptable at this size.

---

## D11 — Manifest written in full in F1 (reversed in F3: components land with their code)

**Decision.** Declare `PlayerActivity`, `PlaybackService`, `DownloadService`, the tile,
Tasker components and the widget before their classes exist.

**Why.** The manifest is the app's contract: intent filters, permissions,
`foregroundServiceType`, exported flags and authorities. Reviewing it once, in full, is
better than three migrations. Manifest merger does not resolve class names, so a missing
class only matters at runtime — and those components are not reachable yet because no menu
entry points at them.

**Cost.** A `ClassNotFoundException` if someone launches a declared component early via
`adb`. Guarded by the fact that F1 has no entry point for them, and each later phase lands
the class before enabling its menu.

**Reversed.** The assumption that a missing class is only reachable from `adb` was wrong.
Package scan resolves declared components at boot: with `PlaybackService` declared and
absent, the system probed it as a media browser service and the process died before drawing
the first frame. F3 removed every component that has no class yet; each phase now adds its
own component entries in the same change as the class. The intent-filter review point
stands, it just happens per phase instead of once.

---

## D12 — No infinite scroll

**Decision.** `CONFIG_PER_PAGE=30` with explicit Next/Previous, `PageInfo` on every list.

**Why.** Upstream pages with fzf; infinite scrolling would change the interaction model and
make "current position in a 900-item channel" unknowable. Explicit paging also keeps
extractor calls bounded, which matters on metered connections.

**Cost.** More taps for long lists. Pagination state is part of the frame payload, so it
survives navigation.

---

## D13 — Runtime overrides are never persisted

**Decision.** `RIVO_*` extras and deep-link parameters live in `RuntimeConfig` only.

**Why.** Upstream `YT_X_*` environment variables are per-invocation. A Tasker task that
runs `RIVO_PER_PAGE=100` should not rewrite the user's config, and nothing is worse than a
one-off automation silently changing a default forever.

**Cost.** Overrides vanish on process death, which is the intended semantic.

---

## D14 — Room for library state, files for everything else

**Decision.** Recent, saved, custom playlists, subscriptions, search history and the
download archive go into Room. Config, language packs, themes and UI extensions stay as
files.

**Why.** Library state is queried and joined (a playlist's items, a channel's videos,
"is this saved?"); files would mean re-implementing indexes and migrations. Configuration
is the opposite: small, human-edited, and part of the user's mental model.

**Cost.** Two persistence mechanisms. The split is by ownership: user-facing documents are
files, app-maintained records are a database.

---

## D15 — `LaunchInbox` with monotonic ids

**Decision.** Intents are pushed as `LaunchEvent(id, spec)` into a `StateFlow`, and the
consumer remembers the last handled id in `rememberSaveable`.

**Why.** A `SharedFlow(replay = 1)` re-emits on configuration change and would push a
duplicate navigation frame on rotation. Event ids make "already handled" explicit and
survive `rememberSaveable`, unlike a plain `remember`.

**Cost.** A tiny amount of ceremony, and events are consumed exactly once by design.

---

## D16 — Nerd Font binary not vendored

**Decision.** `FontRepository` loads any `assets/fonts/*.ttf` at runtime; the repo ships
none and `.gitignore` excludes `*.ttf`.

**Why.** The patched font is ~2 MB of binaries with its own license, and the app is fully
usable without it thanks to `IconCatalog`'s ASCII fallback. Runtime loading means a missing
font is a supported state, not a build failure.

**Cost.** Default installs look less like the terminal original until a font is added.
`CONFIG_NERD_FONTS` toggles the whole icon strategy at runtime either way.

---

## D17 — Graceful degradation instead of blocking on unwritten features

**Decision.** Unknown handler ids return `MenuResult.Unhandled`; unknown menu ids resolve
to one informational row. Both name what is missing.

**Why.** During a phased port, and permanently for third-party extensions, a menu can
reference something that does not exist. Blank screens and silent no-ops are the two worst
outcomes; a message that names the handler is debuggable.

**Cost.** A few strings and one branch in `MenuEngine`, and the app must stay honest about
what is not implemented — which is also why `misc.update` currently opens the releases
page rather than pretending to self-update before F7.

---

## D18 — Toolchain pinned to AGP 9.5.1 / Gradle 9.9.1 / Kotlin 2.4.21, built-in Kotlin

**Decision.** Build with AGP 9.5.1, Gradle 9.9.1 (wrapper committed), Kotlin 2.4.21 forced
on the build classpath, KSP 2.3.14, `compileSdk`/`targetSdk` 37, Build Tools 36, JDK 17.
Drop the `kotlin-android` plugin entirely and let AGP compile Kotlin (built-in Kotlin).

**Why.** The earlier AGP 8.7.3 / Kotlin 2.1.0 pins do not run on current Gradle and
cannot compile against API 36+; AGP 9 requires Gradle 9.6+ and is the only line that
supports API 37. Built-in Kotlin also removes the AGP↔KGP version-matching class of
failures, and pinning KGP higher is the documented escape hatch when a newer Compose
BOM needs a newer compiler.

**Cost.** Two AGP 9 behaviours had to be absorbed: `resValues` is off by default (flavor
labels need `buildFeatures.resValues = true`), and `resourceConfigurations` is gone
(`androidResources.localeFilters`, which wants `pt-rBR`, not `pt-BR`). The configuration
cache is disabled: AGP 9.5.1's `ProcessNavigationXmlTask` cannot be serialized by Gradle
9.9 yet.

---

## D19 - Cookies live in an encrypted store; the WebView login is the way in

**Decision.** YouTube session cookies come from a `cookies.txt` import or from
`WebLoginActivity`, and are stored in `EncryptedSharedPreferences` (AES-256-GCM, key in the
Android Keystore). They are attached to requests by origin only, through `CookieTxt`, and
are exported to a temporary Netscape file for yt-dlp. `CONFIG_COOKIES_ENABLED` gates sending.

**Why.** Upstream yt-x runs `yt-dlp --cookies-from-browser`, which needs a desktop browser
profile that does not exist on Android. Importing a file alone would exclude everyone who
does not already know how to export cookies, so the app offers an in-app sign-in. Cookies
are account credentials: they must not sit in the plain-text `config` file, and they must
not travel to CDNs and unrelated hosts that the extractor talks to. The store is read
lazily on first extraction so app launch never touches the Keystore.

**Cost.** A WebView login screen, one more activity, and a hard dependency on the
security-crypto library. If the Keystore is unavailable the store degrades to memory-only
for the session instead of writing credentials in the clear - a deliberate trade that makes
the feature unavailable rather than insecure.

---

## D20 - yt-dlp is pinned, but self-updating; PO tokens use the client.context syntax

**Decision.** The `ytdlp` flavor ships youtubedl-android 0.18.1, which bundles yt-dlp
2025.11.12 and yt-dlp-ejs 0.3.1. `CONFIG_YTDLP_CHANNEL` selects stable, nightly or master,
and `YtdlpSupport.update()` unpacks a newer release in place. PO tokens are passed as
`--extractor-args "youtube:po_token=web.player+<token>[,web.gvs+<streaming>]"`.

**Why.** yt-dlp changes weekly because YouTube changes weekly; an APK that only knows its
build-time extractor is broken within months, but downloading an interpreter on first run
would break offline installs. Bundling a known-good version and offering a self-update gets
both: the app works out of the box and can be repaired without a store release. The
`CLIENT.CONTEXT+TOKEN` form is the syntax the bundled extractor actually parses; visitor
data is not an extractor argument because yt-dlp reads it from its own responses.

**Cost.** The release feeds are GitHub API endpoints that can rate-limit, the update is a
network operation the user has to trigger (F7), and the bundled version is whatever shipped
with the APK. `CONFIG_YT_DLP_OPTS` remains available for options the app does not model;
its text is tokenised by `CommandLine` and never reaches a shell.

---

## D21 - One router: NewPipe by default, yt-dlp where it wins, signed URLs treated as leases

**Decision.** `ExtractorRouter` picks the backend (`auto` = NewPipe for YouTube, yt-dlp for
everything else), caches `VideoDetails` for `CONFIG_RESOLVE_MAX_AGE_HOURS` (default 5), and
re-extracts on demand via `fresh()` when the CDN returns 403/410. It retries a broken
NewPipe parse with yt-dlp once, and always serves YouTube kiosk feeds from NewPipe.

**Why.** A single routing layer keeps backend choice out of every screen, and it is the only
place that knows both the feature matrix and the expiry rule. Signed URLs expire in about
six hours, so caching a URL forever produces a player that works after lunch and fails at
dinner; treating the failure as "expired" instead of "unplayable" is what makes the retry
useful. Kiosks are the one list type yt-dlp cannot fetch, hence the explicit exception.

**Cost.** Two code paths for the same data, and a cache that has to be invalidated on config
changes (F7 calls `clearCache` when the extractor setting changes). The fallback is
deliberately narrow: private, removed or age-gated videos are reported as such rather than
retried with a second backend that will reach the same verdict.

---

## D22 - Search filters are a backend capability, and the search box is not a row filter

**Decision.** `SearchFilters` keeps the upstream `sp` tokens (chips and `:token` text) as
the app-level contract. Each backend translates them:

- NewPipe maps the three scope codes to its content-filter names (`videos`, `channels`,
  `playlists`) and throws `UNSUPPORTED` for everything else.
- yt-dlp passes the `sp` verbatim to `results?search_query=…&sp=…`.

`ExtractorRouter.search` tries NewPipe first and retries on yt-dlp when it exists and the
error is `UNSUPPORTED`; the fast flavor surfaces the reason instead of an empty list.

The search box holds the query being typed, not a filter over the results: submitting
clears it and the submitted query becomes the screen title. Paged rows are therefore never
post-filtered by the text that produced them. Submitting also applies upstream's `:token`
grammar, so `:4k`, `:today` or `:newest` selects the matching chip and refines the previous
query rather than searching for the token text.

**Why.** `sp` is YouTube's own protobuf; NewPipeExtractor deliberately hides it behind
five named scopes, and passing it raw is what produced silently empty searches. Keeping one
token list in the UI and translating per backend preserves upstream parity without
teaching the screens which extractor is installed. Clearing the prompt on submit is what
fzf (and therefore yt-x) does, and it is the only way page 2 can show rows that do not
contain the query string.

**Cost.** The fast flavor cannot serve date/quality/sort chips; the user sees a precise
`Unsupported` message naming the yt-dlp extractor. Refining a query means retyping it (or
typing a bare `:token` to keep it), and the current query is only visible in the title.

---

## D23 - Channel search is a search frame scoped to a channel URL

**Decision.** `MenuPayload.Search.channelUrl` and `MediaSource.Search.channelUrl` mark a
channel-scoped search. The channel screen (toolbar icon) and the `Search` row in
`channel_actions` open the ordinary search frame with that URL, an empty query and no chips.
`ExtractorRouter.channelSearch` extracts `<channel>/search?query=…`:

- yt-dlp handles it as a normal channel tab (`YoutubeTabIE`), with the same `--playlist-start`
  window as any list.
- NewPipeExtractor has no channel-search entry point and inherits the interface default,
  which throws `UNSUPPORTED` with a message naming the missing extractor.

**Why.** Upstream's channel search is literally "open the channel's Search tab with this
term", and yt-dlp already understands that URL. Reusing the search frame keeps one list
implementation, one pager and one preview, and makes the scope visible in the breadcrumb
(the channel name). No `sp` codes exist for channel search, so the chips are hidden rather
than shown and ignored.

**Cost.** The feature is yt-dlp-only, so the fast flavor shows an unsupported state for an
entry it cannot serve. The search row stays visible on purpose: hiding it would make the
parity list depend on the installed flavor, and the error explains the limitation. Paging is
count-based (`--playlist-start/end`), because flat yt-dlp output carries no continuation
token; a window that returns fewer than `CONFIG_PER_PAGE` rows is treated as the end, which
is what a 30 + 27 channel search looks like on a channel with 57 matches.

---

## D24 - Native libraries are packaged for extraction (ytdlp flavor)

**Decision.** `packaging.jniLibs.useLegacyPackaging = true` for the app.

**Why.** youtubedl-android loads CPython by unzipping `libpython.zip.so` from
`ApplicationInfo.nativeLibraryDir`. With AGP's default page-aligned packaging that file is
only mapped from inside the APK and never exists as a directory entry, so
`YoutubeDL.init()` fails with `failed to initialize` and every yt-dlp call reports an
extractor error. Legacy packaging extracts the `.so` files at install time.

**Cost.** Larger installed footprint (the APK itself shrank from 113 MB to 105 MB because
the libraries compress again, but they are unpacked on disk) and slightly slower installs.
Only the `ytdlp` flavor carries those libraries, so the fast flavor is unaffected.

---

## D25 - The preview panel has an on-screen toggle on phones

**Decision.** `MenuScreen` shows a `Preview` icon in the top bar whenever the preview is
enabled and the layout is single-pane; tapping it toggles the bottom sheet. The `space`
binding still does the same thing, and the two-pane layout keeps the panel permanently
docked instead of toggled.

**Why.** `space` is fzf's preview key, and on a phone the soft keyboard eats it — the
feature would exist but be unreachable without a hardware keyboard. A top-bar affordance is
the only discoverable equivalent, and it costs one icon because the sheet already exists.
The wide layout does not need it: at ≥720 dp the panel is always visible, so a toggle there
would only hide information.

**Cost.** One control that upstream does not have. It is scoped to the case where upstream's
control cannot work, and it disappears in the layout where upstream's behaviour is
reproduced exactly.

---

## D26 - Library identity and state live in `core`, not in Room queries

**Decision.** Everything the library stores is keyed by `LibraryKeys` (provider + id, or URL
when an id is missing), and menus read the library through an immutable `MenuLibrary`
snapshot in `MenuContext`. Menu builders never touch `LibraryDao`.

**Why.** A bare video id is ambiguous across providers, so the provider belongs in the key;
the same rule has to be used by the tables, the "Save / UnSave" decision and the handlers,
and putting it anywhere but `core` lets the UI and the database disagree. Menus are pure
functions of `MenuContext`; giving them a snapshot keeps resolution synchronous and
testable, and a save or subscription re-emits the snapshot so every visible row relabels in
the same frame.

**Cost.** The snapshot holds the saved list, subscriptions, playlists, saved playlists and
the search history — small by construction, but it is copied on every write. Room Flows are
merged once in `AppGraph` (`combine` + `stateIn`) rather than per screen, so the cost is
paid once per change.

---

## D27 - Destructive library actions are stateless Prompt/Confirm results

**Decision.** Handlers that need a name or a yes/no return `MenuResult.Prompt` or
`MenuResult.Confirm`. The UI parks the request in state, shows the dialog, and re-dispatches
the *same handler* with `MenuNode.value` set to the answer; `LibraryHandlers.CONFIRMED` is
the sentinel.

**Why.** A handler that keeps "waiting for confirmation" as a field has to survive rotation,
process death and a second tap. Re-dispatching keeps every handler a pure function of
`(MenuContext, MenuNode)` and makes the two-step protocol testable by calling it twice.

**Cost.** The dialog is driven by the screen, not the handler, so a new interactive result
needs a matching dialog in `RivoRoot`; and the payload travels through the dialog, so a
renamed item answers with the snapshot it was opened from — the handlers re-read the title
from the repository before acting.

---

## D28 - Local lists are `MediaSource`s served from Room in one page

**Decision.** Saved videos, recents and custom playlist contents are `MediaSource.Library` /
`MediaSource.LocalPlaylist`. `MediaLoader` returns one `MediaPage` containing every row and
`PageWindow` re-chunks it, so Previous/Next stay instant and offline. Opening a video records
it as recent (upstream records on media activation), and local screens reload from
`LibraryRepository.changes` instead of polling.

**Why.** The list shell, pager, filter and preview already exist; making the library a source
means a local screen is a source plus a title, and the same row activation works everywhere.
A single page is correct because the data is local: there is no continuation token to page
with, and `CONFIG_PER_PAGE` is a view window, not a fetch size.

**Cost.** The recents ceiling (`LibraryLimits.RECENTS_CEILING = 200`) bounds a single read;
`CONFIG_NO_OF_RECENT` still decides what is kept. A reload resets the window to page 1, which
is the price of re-reading the table after a mutation.

---

## D29 - Account feeds are an extractor capability, gated by config and cookies

**Decision.** `AccountFeed` (`SUBSCRIPTIONS`, `CHANNELS`, `HISTORY`, `WATCH_LATER`, `LIKED`,
`PLAYLISTS`) is a capability of `VideoExtractor`. `ExtractorRouter.accountFeed()` requires
`CONFIG_ACCOUNT_FEEDS` and a stored cookie jar, and the fast flavor reports `UNSUPPORTED`
because NewPipeExtractor has no account-feed entry point. `FeedKind.HOME` and `FeedKind.CLIPS`
stay unsupported everywhere.

**Why.** These feeds are the account's own view of YouTube; serving them without cookies
returns a signed-out page that looks like an empty feed, which is worse than an explanation.
The gate is explicit so the message can name what to enable. yt-dlp implements them as flat
playlists over `/feed/...`, which is also how subscription sync works.

**Cost.** The fast flavor cannot show any account feed, and the parity row says so. Sync is
confirmed first and never replaces a non-empty local list with an empty result, because a
signed-out extraction must not erase subscriptions the user added by hand.

---

## D30 - Room schema v1 has no destructive migration

**Decision.** `LibraryDatabase` is version 1 with seven entities, `exportSchema` writes
`app/schemas`, and there is no `fallbackToDestructiveMigration`.

**Why.** Saved videos, subscriptions, playlists and history are user data: silently dropping
them on a schema change is data loss, not a migration strategy. Exporting the schema makes
the next version's migration diff a reviewable file instead of a guess.

**Cost.** A future schema change must ship a migration before it ships, or the app crashes on
open with the Room mismatch error. That is the intended failure: it is visible in development
and cannot destroy a library in the field.


---

## D31 - Breadcrumbs collapse adjacent identical labels

**Decision.** The crumb bar shows one label per visible level; a frame whose title repeats the
frame beneath it (a custom playlist's action menu leading into the playlist's contents) does
not add a second identical crumb. Each crumb still carries the stack index it pops to.

**Why.** "Trip / Trip" names one place twice and makes the bar read like a bug, while the two
frames are genuinely distinct (actions vs contents). Hiding the duplicate keeps the bar
honest about where you are without changing the back stack or what back does.

**Cost.** The crumb list is no longer one-to-one with frames, so it is a list of
(label, index) pairs rather than labels; any future code that maps crumbs to frames must
use the index, not the position.
---

## D32 - The player surface is a `TextureView`, not a `SurfaceView`

**Decision.** `res/layout/view_player.xml` inflates a `PlayerView` with
`app:surface_type="texture_view"`. The activity never hosts a `SurfaceView`.

**Why.** A `SurfaceView` has its own layer, and that layer is not composited into the
activity window in two situations this app creates on purpose: picture-in-picture, where the
system re-parents the task into a small window, and the modal bottom sheets, where a dialog
window sits above the activity. In both cases the video area renders black while audio keeps
playing, which reads as a crash. `TextureView` draws inside the normal view hierarchy, so the
picture survives both.

**Cost.** `TextureView` cannot use the HDR passthrough path of `SurfaceView`, so HDR
renditions are tone-mapped and a little softer than a surface composited by the display
hardware. A correct picture in PiP and under every sheet is worth more than HDR passthrough
for a client that picks SDR renditions by default anyway.

---

## D33 - The audio picker groups by track identity, not by codec

**Decision.** `audioOptions()` groups `AUDIO_ONLY` formats by `StreamFormat.audioTrackKey`
(the backend track id, falling back to the language) and offers one row per group; the row
plays the best-bitrate format of that group. A video with a single audio track offers no
choices at all, only the sheet's `Original` row.

**Why.** YouTube ships the same audio twice as `m4a` 128 kbps and `opus` 160 kbps, and NewPipe
exposes every codec as its own `AudioStream`. Listing them turned a normal video's "Audio
track" sheet into six rows called `Track 139`, `Track 140`, `Track 249`…, which look like
dubs and are not. Selecting one of them selected a *codec*, not a language, so the second one
was indistinguishable from the first.

**Cost.** Choosing between `m4a` and `opus` for the same track is no longer possible from the
UI. That is a job for the player's adaptive selection, not for a language picker.

---

## D34 - The queue is resolved lazily behind a prefetch window

**Decision.** `PlaybackCoordinator` resolves the first item, then appends items while the
resolved count stays within `PREFETCH_AHEAD` of the current index. `PlayerExtras.queueSize`
carries the *request's* length so the overlay can say "3 of 26" while the ExoPlayer timeline
holds three items. Dead items (private, removed, unavailable) are dropped from the queue
instead of stalling it.

**Why.** Resolving a 26-item playlist before the first frame takes tens of seconds; resolving
one item at a time breaks `next` in the notification, which has no idea an extraction is
pending. The window gives the session enough timeline to skip forward immediately without
extracting the whole playlist up front.

**Cost.** The timeline is not the queue. Anything that reads `mediaItemCount` for display is
wrong; the request length has to travel in the metadata, and the activity has to prefer it.

---

## D35 - Paused playback keeps the controls on screen

**Decision.** The four-second auto-hide only runs while the player is playing. Pausing — by
tap, by headset, or from the notification — brings the chrome back and leaves it up.

**Why.** A paused video with no chrome is a dead end: there is no motion to suggest that a tap
brings the controls back, and the most likely next action (resume) is invisible. Every player
people already use behaves this way.

**Cost.** A paused player cannot be used as a clean screenshot of the video. That is what
picture-in-picture and the notification are for.
---

## D36 - The player activity detaches the video surface when it stops

**Decision.** `PlayerActivity.onStop` calls `controller.clearVideoSurface()` before releasing the
`MediaController`. The session keeps playing with no video output; the next time the activity
starts, `PlayerView` attaches a fresh surface and the picture returns.

**Why.** The decoder renders into a `TextureView`, and that view dies with the activity. When
the codec is still pointed at a destroyed surface, `MediaCodecVideoRenderer` throws
(`MediaCodecVideoRenderer error … format_supported=YES`) and the session ends up in `ERROR`,
which is the opposite of what `CONFIG_DISOWN_PLAYER` promises. `PlayerView.setPlayer(null)`
does clear the surface on release, but it runs while the view tree is being torn down, after
the controller has already been released in `onStop` — too late, and on a dead controller.
Detaching in `onStop` puts the clear ahead of the teardown.

**Cost.** Video decoding stops when the player is not on screen, so reopening starts at the
current audio position with a short re-buffer of the video track. That is what a background
player should do; audio is the part that is supposed to keep going.

---

## D37 - Chapters come from the extractor's description chapter list

**Decision.** `NewPipeBackend` maps `streamSegments` to `VideoDetails.chapters` and advertises
`chapters = true`; ends are derived from the next chapter's start and the last chapter ends at
the video's duration. Chapters are not reported as sponsor segments.

**Why.** NewPipe parses the description-chapters panel
(`engagement-panel-macro-markers-description-chapters`) into `streamSegments`. The first F5
build dropped them into `segments` with `endMs = null`, which left `chapters` empty forever:
the "Chapters" chip never appeared, the chapter sheet never opened, and the timeline never drew
chapter ticks, on the backend that ships by default. Timestamps are a different thing from
SponsorBlock categories and mixing them in the sponsor list would make the skip logic read
chapter titles as categories.

**Cost.** YouTube's auto-generated chapters (which live in the player response but not in the
description panel) do not appear; the yt-dlp flavor can surface them where the description has
no timestamps.

---

## D38 - A format with no codecs is still a playable file

**Decision.** `YtdlpJsonMapper` infers the stream kind from the container when yt-dlp reports
neither codec: audio containers (`m4a`, `mp3`, `opus`, `flac`, ...) become `AUDIO_ONLY`,
anything else becomes `MUXED`. A format without a `format_id` falls back to its `ext` instead
of being dropped.

**Why.** The generic extractor reports a direct media URL as one format with
`"vcodec": null`, `"audio_ext": "none"` and `"format": "mp4 - unknown"` — it never probes the
file, because there is nothing to select. Treating unknown codecs as unplayable deleted the
only format: the player opened with an empty queue and nothing but a generic "could not
resolve" snackbar, so every non-YouTube URL that is not a page-with-formats failed on the
flavor that exists for them. Classifying such a file as video-only would have been worse —
`StreamPlanner` would then look for a separate audio track and play the video mutely.

**Cost.** A container the player cannot decode is now attempted instead of rejected up front;
ExoPlayer reports the decode error itself. In practice the container list is what the player
supports anyway.

---

## D39 - An unreadable link says why

**Decision.** `MediaLinkResolver` returns `ResolvedLink.Unreadable(error)` instead of `null`
when extraction fails, and the deep-link route shows the failure category plus the extractor's
first message line. `PlaybackActions` maps the same case to a `LaunchOutcome.Failed` whose
detail is the extractor message. On a build without yt-dlp, `ExtractorRouter` answers a
non-YouTube host itself — "the fast build opens YouTube links only" — before NewPipe gets to
call it "not a video URL".

**Why.** A link that no backend could read used to show `TXT_PLAYER_NOT_FOUND` — "No suitable
media player found. Please set CONFIG_PLAYER…" — because the route resolved to `null` and the
only null branch left was the external-player message. The link had nothing to do with
`CONFIG_PLAYER`, and the actual reason (a login wall, a 404, a site yt-dlp does not know) was
discarded one layer below. The same `null` also made `action=play` links silently do nothing.
Reporting the category and the extractor's verdict is what makes a failing link diagnosable
without logcat. The fast build needed the same treatment for a different reason: "not a video
URL" is technically true and practically useless, since the URL is a video — the build just
cannot open it.

**Cost.** A snackbar can now carry an extractor sentence that is not localized (it comes from
NewPipe or yt-dlp); it is truncated to its first line, 200 characters. The fast-build message
is English-only for the same reason as the extractor messages around it: it is a diagnostic,
not UI copy.

---

## D40 - The download queue is a JSON file and the runner is a foreground service

**Decision.** Queued downloads live in `files/downloads/queue.json`, next to
`archive.txt`, owned by `DownloadRepository`. `DownloadService` is a `dataSync` foreground
service that claims one `QUEUED` task at a time (`CONFIG_DOWNLOAD_CONCURRENCY`), runs it
through `DownloadEngine`, and re-reads the queue after every transition, so a task added
while it runs is picked up without a new start. The archive key is `kind<TAB>videoId`,
not yt-x's "kind plus playlist URL". Pause stops the run and keeps the partial file in
that task's own work directory; resume restarts from the retained bytes via a `Range`
request. Cancel deletes the work directory. `cleanupCache` deletes every work directory
that does not belong to a paused task, once, at repository construction.

**Why.** WorkManager is the wrong owner for a queue that has to be inspectable, reorderable
and pausable from a menu: its database is opaque, its constraints re-schedule rather than
report, and a user-visible "Paused" row would have to be mirrored into it anyway. A JSON file
the app already writes is the same amount of code and the queue is the UI's source of truth.
The service is what makes a long download survive the app leaving the foreground, and a
single notification that describes the aggregate avoids a stack of per-file notifications.
A playlist download is exactly the case the archive exists for: keying by playlist URL would
re-download a video the moment it appears in a second playlist, while the file itself does not
depend on where it was listed, so the key is the rendition identity.

**Cost.** The queue is not transactional: a process kill between "state written" and "file
moved" can leave a task marked `DONE` with no output path, which the file list shows as
missing rather than hiding. Retention of paused parts means a paused download holds disk space
until it is resumed, cancelled or the task is deleted; the cleanup pass deliberately leaves it
alone. `queue.json` has no schema migration story beyond a `version` field; a future shape
change has to read both.

---

## D41 - A download is muxed only from MP4 halves

**Decision.** `DownloadPlanner` prefers a muxed rendition at or above the best video-only
height. When the video-only rendition is higher, it is joined only with an MP4 audio track
(`m4a`/`mp4` container or `audio/mp4`); when no such track exists the download fails with a
message that names the workaround instead of writing a silent or unplayable file. An
audio-only download honours `CONFIG_AUDIO_FORMAT` through `DownloadPlanner.audioFor`, whose
default is `m4a`.

**Why.** Playback can hand two streams to ExoPlayer and let it handle Opus/WebM; a file cannot.
`MediaMuxer` writes MP4, and an Opus track inside MP4 is played by very little: the user would
get a file that "downloaded fine" and opens nowhere. Refusing the pair and explaining it is
more useful than quietly downgrading to the muxed rendition, because the height cap was the
user's explicit choice. The audio default follows the same reasoning: `MediaStore.Audio`
rejects `audio/webm`, so an Opus-only default would make audio downloads land in the downloads
collection on some devices; Opus remains selectable.

**Cost.** Videos whose only high-resolution video is VP9/AV1 download at the best muxed height
instead, unless the yt-dlp build is used (its fallback can remux). The error for "video
without a joinable audio track" is a terminal failure, not a retry.

---

## D42 - One configured folder maps to one root per MediaStore collection

**Decision.** On API 29+ the configured `CONFIG_DOWNLOAD_DIR` is a storage-relative path.
Its first segment is validated per collection: video accepts `Movies`, `DCIM`, `Pictures`;
audio accepts `Music`, `Podcasts`, `Audiobooks`, `Recordings`, `Ringtones`, `Alarms`,
`Notifications`; generic files accept `Download`. The remainder of the path is shared, so
`Movies/Rivo` means `Movies/Rivo` for video, `Music/Rivo` for audio and `Download/Rivo` for
anything else. A primary the collection does not allow is replaced by that collection's
default rather than failing the insert. A failed audio insert is retried in
`MediaStore.Downloads`, because the music library also validates MIME type. The config row
shows the resolved roots (`Movies/Rivo · Music/Rivo`), and "Open folder" converts the
video root into a `com.android.externalstorage.documents` document URI so Files opens at
the folder instead of the app's private path string.

**Why.** `MediaStore` is not a directory tree: each collection is a table with its own
allowed `RELATIVE_PATH` primaries, and `Audio` additionally rejects types it does not
consider audio. Treating the configured path as literal made audio-only downloads fail with
`the media library refused …` on a folder the user had just chosen for video, which reads as
a bug in the app rather than in the platform's rules. Deriving the collection root keeps one
setting meaningful for both kinds, and the fallback keeps a WebM audio download reachable
instead of lost. Showing the resolved roots makes the split visible before a download runs.

**Cost.** The configured path is not honoured literally for audio, which is documented on the
config row by the secondary label. Files from one download can therefore live under two
different top-level directories, and "open folder" can only open one of them (the video root).
On API 26-28 all three kinds still share one real directory, because there is no MediaStore
collection to satisfy.


---

## D43 - Extensions stay declarative; SAF never supplies code

**Decision.** `.ui.json`, `.lang.json`, `.theme.json`, `.sites.json` and `.cmds.json` are
parsed as documents with a `rivo.*/1` schema and resolved against a fixed handler registry;
nothing in a picked folder is executed, compiled or interpreted as shell. SAF access is one
persisted tree URI in `SharedPreferences`; enabling, choosing, autoloading, reloading and
forgetting the folder are explicit menu actions, and a malformed file is named and skipped.

**Why.** Extensions come from a Downloads folder that other apps can write to. Declarative
documents keep the attack surface at "a menu entry with a URL or a handler id", which cannot
escalate beyond what the app already does. The same loader serves asset-shipped and
user-supplied extensions, so one parser and one test suite cover both.

**Cost.** Upstream extensions may add shell behaviour; this port can only recombine existing
actions. A site that needs bespoke parsing is covered by yt-dlp, not by an extension.

---

## D44 - Custom commands are links; yt-dlp options stay on the Termux side

**Decision.** A command stores a name, a URL and optional raw yt-dlp options. Running it
resolves the URL through the same router as a shared link and then shows the media actions
menu; when no bundled extractor supports the site, the row says so. The options are exported
as `YT_X_YTDLP_OPTS` only to a Termux `RUN_COMMAND` hand-off and are never merged into the
in-app extractor. Commands shipped by an extension are read-only: Run, Open in Termux, or
open the URL.

**Why.** Playback and downloads are driven by the typed `VideoExtractor` interface; a
free-form option string would bypass its capability checks and make failures unattributable.
Handing the options to Termux keeps the upstream "anything yt-dlp can do" escape hatch
without turning the app into a shell wrapper.

**Cost.** A site that only works with a specific yt-dlp flag runs in Termux, not in the app,
and the menu states that instead of pretending parity.

---

## D45 - Update checks read this repository's releases and stop at the browser

**Decision.** `CONFIG_CHECK_FOR_UPDATES` with `CONFIG_UPDATE_CHANNEL` (stable, prerelease)
enables a check against Rivo's own GitHub releases (`UPDATE_MANIFEST`), compared with
`VersionNumbers` against `BuildConfig.RIVO_VERSION`. The check runs at startup and when the
screen opens, at most once every six hours unless "Check again" is pressed, and failures are
not cached. The six-hour window is stored in preferences, so a cold start does not turn into
one request per launch. An available release shows its tag, name, date, notes and an "Open
release page" row; nothing is downloaded or installed.

**Why.** The Android port had no release feed of its own in the first builds, so the only
honest claim was "a newer yt-x release exists than the one this build ports". Now that the
repository publishes `0.8.7-rivo.N` tags, the row tracks the port itself; opening the page
leaves the update decision and the APK provenance with the user, and avoids wrapping a
third-party artifact in an in-app installer.

**Cost.** The row cannot install anything; a user who expects a one-tap update gets a browser
instead, which the README states.

**Rename note.** Once the project became Rivo it gained its own repository, so
`UPDATE_MANIFEST` points at the Rivo GitHub releases and the comparison is against the version
this build reports. `UPSTREAM_REPO` stays on the about screen as the credit for the yt-x port.
The six-hour floor, the channel filter and the "open the page, never install" rule are
unchanged.

---

## D46 - Cache retention is a WorkManager sweep with an explicit period

**Decision.** `CONFIG_CACHE_RETENTION_DAYS` (Off, 1, 3, 7, 14, 30) is scheduled as a unique
periodic `CacheCleanupWorker` from `AppGraph`, with the configured value passed explicitly.
Changing the value reschedules the worker. The sweep deletes cache files older than the
period, leaves `cache/downloads` alone so a paused download keeps its part, and never touches
the library database, downloaded media or the archive. "Clear cache now" sweeps everything
except that same directory.

**Why.** Android has no cron; WorkManager is the supported periodic mechanism and survives
reboot. The worker originally asked the graph for the retention period while the graph was
being constructed, which re-entered `RivoApp.graph` before assignment and crashed the process
on boot. Passing the value in makes the dependency acyclic.

**Cost.** WorkManager is inexact: the sweep runs about once a day inside the maintenance
window, and a phone that never idles can go longer. "Off" enqueues nothing, so caches grow
until Android reclaims them.

---

## D47 - Tasker is a broadcast-to-deep-link adapter, with a notification fallback

**Decision.** `TaskerFireReceiver` accepts `io.github.iseaprojects.rivo.action.TASKER_FIRE` with a
`rivo_uri` extra (or a `rivo://` data URI), forwards only `RIVO_*` extras, and opens the matching
screen. Android 10+ blocks activity starts from a background receiver, so the receiver starts
the activity directly only when the app holds "Display over other apps" (the one exemption a
normal app can be granted), and otherwise posts a notification whose tap opens the same link.
No Locale edit activity is shipped: the broadcast is the whole contract, usable from Tasker,
MacroDroid or `am broadcast` without a plugin SDK.

**Why.** The first implementation created the PendingIntent with
`MODE_BACKGROUND_ACTIVITY_START_ALLOWED`; the platform rejects that at creation, and when the
option is passed at send time it is still ignored unless the creator opts in, so the launch
was silently dropped (the log names `balAllowedByPiCreator: BSP.NONE`). A deterministic
fallback that still delivers the route beats a receiver that appears to succeed and does
nothing. Leaving launch policy to Android also keeps the exported surface at "a link".

**Cost.** Direct launches need a manual overlay permission; users who decline it tap a
notification instead. Tasker tasks that expect a configuration screen do not get one.

---

## D48 - Android entry points are adapters over the deep-link router

**Decision.** The Quick Settings tile, the recent-videos widget, static shortcuts and dynamic
shortcuts all build a `rivo://` URI and hand it to `MainActivity`; none of them reads menus or
calls handlers. The widget reads the Room recents table for its four rows and opens each
item's actions screen, not playback. Dynamic shortcuts mirror the first four custom commands
and are pruned when a command is deleted.

**Why.** One router already owns override parsing, prompts and back-stack behaviour;
duplicating any of it in `RemoteViews` or a tile would drift. Firing the same link is also how
the surfaces are verified.

**Cost.** A surface can only reach what a link can express; the widget cannot show thumbnails
or durations without a second rendering path.
