# Extractors

The extraction layer decides what a video actually is: its metadata, every available
format, subtitles, chapters, sponsor segments, and the continuation token for the next page
of a list. It is the Android replacement for yt-x's `yt-dlp -J | jq` pipeline, with two
independent backends behind one interface.

```
                    ┌──────────────── ExtractorRouter ────────────────┐
config ──select──▶  │  backend choice · URL cache · re-resolve on 403 │
                    └───────┬─────────────────────────────┬───────────┘
                            │                             │
                   NewPipeBackend                   YtDlpBackend
                   (both flavors)                   (`ytdlp` flavor)
                            │                             │
                  NewPipeExtractor 0.27.1        youtubedl-android 0.18.1
                            │                     (yt-dlp 2025.11.12)
                    NewPipeTransport                  Python runtime
                    (OkHttp + cookies)
```

## The contract

`data/extract/VideoExtractor.kt` is the whole surface the UI is allowed to use:

| Method | Purpose |
| --- | --- |
| `video(idOrUrl)` | Full `VideoDetails`: summary, formats, subtitles, chapters, segments, heatmap |
| `search(query, filters, token)` | One page of search results |
| `suggestions(query)` | Autocomplete terms |
| `channel(url, tab, token)` | Channel identity, tab list, one page of the selected tab |
| `playlist(url, token)` | Playlist identity and one page of items |
| `kiosk(kiosk, token)` | One page of a YouTube kiosk feed |
| `warmUp()` | Report/initialize the runtime version (yt-dlp unpacks its interpreter) |
| `capabilities` | Feature matrix so unsupported actions can be hidden |

Everything a backend returns is an app model (`ExtractorModels.kt`), never an extractor
object. That is what lets lists survive process death: no live `Page`/`InfoItem` instances
are ever held by the UI.

### Formats

`StreamFormat` flattens both backends' notions of a rendition:

- `kind` — `MUXED` (progressive), `VIDEO_ONLY`, `AUDIO_ONLY`
- `delivery` — `PROGRESSIVE`, `DASH`, `HLS`, `OTHER`, which decides the Media3 source
- `isOriginalAudio` / `isDefaultAudio` — YouTube serves language dubs as separate audio
  tracks; the player defaults to the original, not the highest bitrate
- `httpHeaders` — some CDNs require the page's `Referer`; yt-dlp reports it per format
- `isDrm` — surfaced as `Availability.DRM_PROTECTED`, never worked around

`VideoDetails.bestMuxed/bestVideoOnly/bestAudio/needsMuxing` implement the selection policy
once, for both backends. F5's player reads those instead of re-deriving quality rules.

yt-dlp does not always probe a file it can hand over untouched. A direct media URL (or any
page the generic extractor reduces to one file) comes back as a single format with
`"vcodec": null` and no `acodec`; the mapper classifies it by container instead of dropping
it, with audio containers as `AUDIO_ONLY` and everything else as `MUXED` (D38).

## Which backend runs

`CONFIG_EXTRACTOR` accepts `auto` (default), `newpipe`, or `ytdlp`.

| Value | Video URLs | Lists |
| --- | --- | --- |
| `auto` | YouTube → NewPipe, anything else → yt-dlp (if compiled in) | NewPipe |
| `newpipe` | NewPipe | NewPipe |
| `ytdlp` | yt-dlp, falling back to NewPipe in the `fast` flavor | yt-dlp |

`auto` is the default because NewPipe answers in a few hundred milliseconds while yt-dlp
has to unpack and start CPython on first use. The `fast` flavor has no yt-dlp at all:
`YtdlpSupport` compiles to a stub that returns `null`, so the router degrades to NewPipe
instead of failing.

Handing a non-YouTube URL to that build produces NewPipe's "not a video URL", which reads
like a broken link. The router answers first instead: on a build without yt-dlp, a non-YouTube
host raises `UNSUPPORTED` — "the fast build opens YouTube links only; the yt-dlp build adds
non-YouTube sites" — so the message names the build that can open it.

If NewPipe fails with `EXTRACTOR_BROKEN`, `UNKNOWN` or `RATE_LIMITED` and yt-dlp is
available, the router retries once with yt-dlp. Definitive verdicts (`PRIVATE`, `REMOVED`,
`AGE_RESTRICTED`, …) are not retried: a second extractor cannot change them.

### Kiosks

Music, Gaming, Movies, Live and Podcasts are YouTube `/feed/...` URLs. yt-dlp does not
extract them, so `YtDlpBackend.capabilities.kiosks` is empty and `ExtractorRouter.kiosk()`
always routes those feeds to NewPipe, even when `ytdlp` is the selected backend.

**Trending is gone.** YouTube retired the `/feed/trending` page and now answers its InnerTube
browse call with HTTP 400, so `Kiosk.TRENDING` is not advertised in `capabilities.kiosks`.
Requesting it anyway throws `REMOVED` with a message that names the retirement instead of
surfacing a parse error. The menu node stays, so the gap is visible and honest rather than
silently hidden.

Kiosks can legitimately return an empty page (Music is served by charts.youtube.com and
occasionally answers an empty grid); the UI treats an empty first page as "no results",
not as an error.

## Caching and stale URLs

YouTube's signed stream URLs expire after roughly six hours. The router keeps resolved
`VideoDetails` in memory keyed by video id, with a TTL from
`CONFIG_RESOLVE_MAX_AGE_HOURS` (default 5, clamped to 1–24). Two behaviours follow:

- A cached hit is returned without touching the network.
- `fresh(videoId)` forces a new extraction. F5 calls it when the CDN answers 403/410; the
  failure model marks those as `looksLikeExpiredUrl`, so the player knows to re-resolve
  rather than show an error.

`invalidate` and `clearCache` exist for the same reason: a signed URL is a lease, not an
identity.

## NewPipe backend

`NewPipeBackend` maps NewPipeExtractor's models into the app's. Four details matter:

1. **Transport.** NewPipe has no HTTP client of its own; `NewPipeTransport` implements
   `Downloader` on top of the shared OkHttp client. A 429 becomes NewPipe's
   `ReCaptchaException` (its rate-limit signal) so the exception mapping stays honest.
2. **Cookies.** NewPipe cannot store cookies, so the transport attaches `Cookie` from
   `CookieProvider` for every request, scoped by host/path/secure in `CookieTxt`.
3. **Paging.** NewPipe's `Page` holds a URL, an id, a list of ids, cookies and sometimes a
   POST body. `NewPipePageCodec` serialises all five into base64url JSON inside a
   `ContinueToken`, so an explicit "Next" survives rotation and process death.
4. **Channel tabs.** `ChannelExtractor` has no page stream in 0.27: content comes from
   `ChannelTabExtractor` links in `ChannelInfo.tabs`. A channel without tabs (should not
   happen) returns an empty page rather than crashing.
5. **Lazy parsing.** List, channel and playlist extractors parse on `fetchPage()`, and the
   `Info.getInfo(extractor)` helpers read accessors that assert the page was fetched. Every
   call in `NewPipeBackend` fetches first; search, channel tabs and kiosks previously failed
   with a bare `NullPointerException` or `Page is not fetched` without it.
6. **Identity comes from the URL.** `StreamInfoItem` and `PlaylistInfoItem` have no `id`
   member, so an unqualified `id` inside an extension function silently resolves to
   `NewPipeBackend.id` (the literal `"newpipe"`). The mapping derives every id from
   `UrlCanon` instead; the live test asserts that search results carry real video ids.

Availability is folded from three signals in one place: `ContentAvailability` (membership,
paid, upcoming), `StreamExtractor.Privacy` (private) and the exception type on failure.

### PO token

`CONFIG_PO_TOKEN` accepts the compact `visitorData:playerToken[:streamingToken]` form or a
JSON object (`visitorData`, `poToken`, `streamingPoToken`). NewPipe receives it through
`YoutubeStreamExtractor.setPoTokenProvider { PoTokenResult(...) }`; the provider is
re-registered when the configured value changes.

## yt-dlp backend

`ytdlp` flavor only. The APK ships **youtubedl-android 0.18.1**, which bundles
**yt-dlp 2025.11.12** (`yt_dlp/version.py` inside `res/raw/ytdlp`) and **yt-dlp-ejs
0.3.1**. The library keeps the historical name; the bundled extractor is the maintained
yt-dlp, not the discontinued youtube-dl.

**Extraction.** `--dump-single-json` prints the same object as `-J`, which
`YtdlpJsonMapper` parses. That mapper is pure Kotlin with no Android or Python dependency,
so recorded JSON fixtures exercise it on the JVM.

**Paging.** yt-dlp has no continuation tokens, so `ContinueToken("ytdlp", "<start index>")`
plus `--playlist-start/--playlist-end` produces explicit pages of `CONFIG_PER_PAGE` items.
A page that returns fewer items than requested ends the list.

**Cookies.** The encrypted store is exported to a Netscape file in `cacheDir` for the
lifetime of the call and passed as `--cookies`. The file never leaves app-private storage.

**PO token.** Passed as
`--extractor-args "youtube:po_token=web.player+<token>[,web.gvs+<streaming>]"`, which is the
`CLIENT.CONTEXT+TOKEN` syntax yt-dlp 2025.11 expects. Visitor data is not an extractor
argument: yt-dlp reads it from its own responses.

**User options.** `CONFIG_YT_DLP_OPTS` is the same string upstream users put in
`yt-dlp.conf`. Android has no shell, so `CommandLine` tokenises it with POSIX quoting rules
and each token is handed to `YoutubeDLRequest.addOption`; nothing is ever compiled into a
shell command.

**Updates.** `CONFIG_YTDLP_CHANNEL` selects `stable`, `nightly` or `master`, each mapped to
that build's GitHub `releases/latest` endpoint. `YtdlpSupport.update()` unpacks the newer
release in place, so a YouTube change can be absorbed without a new APK. The bundled
version remains the offline fallback.

## Errors

Both backends normalise into `ExtractorException(kind, message, statusCode)`:

| Kind | Typical cause | Retryable |
| --- | --- | --- |
| `NETWORK`, `TIMEOUT` | no connectivity, slow CDN | yes |
| `RATE_LIMITED` | HTTP 429, "confirm you're not a bot", captcha | yes |
| `FORBIDDEN` | HTTP 403, expired signature | yes (re-resolve) |
| `PRIVATE`, `AGE_RESTRICTED`, `GEO_BLOCKED`, `DRM_PROTECTED`, `MEMBERS_ONLY`, `UPCOMING`, `REMOVED` | the content itself | no |
| `NOT_FOUND`, `UNSUPPORTED` | wrong URL, unsupported site | no |
| `EXTRACTOR_BROKEN` | parser/format change in the site | yes (yt-dlp fallback) |

The mapping keeps extractor imports out of the failure model: NewPipe exception classes are
recognised by name and yt-dlp by its stderr phrases, which is also what makes the whole
table testable without a network.

## Cookies

Two sources, one store:

- **`cookies.txt` import** through the storage access framework. Netscape format and the
  JSON array exported by browser cookie extensions are both accepted; entries are merged by
  `(domain, path, name)`.
- **WebView login** (`ui/login/WebLoginActivity`). The user signs in to Google inside a
  WebView whose user agent is a desktop Chrome one; the resulting YouTube cookies are
  captured, and the WebView jar plus web storage are wiped so the session exists in exactly
  one place.

The store is `EncryptedSharedPreferences` (AES-256-GCM value encryption, key in the Android
Keystore). If the Keystore is unavailable it degrades to memory-only for the session rather
than writing credentials in the clear. The first read happens on first extraction, not at
process start, so app launch never pays for Keystore access.

Cookies are attached by origin: `CookieTxt.matches` enforces host-only vs domain cookies,
path boundaries (`/feed` does not match `/feedback`), the secure flag, and expiry. A
`.youtube.com` session cookie is never sent to `i.ytimg.com`, `googlevideo.com` or any other
host the extractor requests. `CONFIG_COOKIES_ENABLED=false` (the default) disables sending
entirely, even when a store exists.

## Feature matrix

| Capability | NewPipe | yt-dlp |
| --- | --- | --- |
| Search, filters, suggestions | yes | yes |
| Channels, channel tabs | yes | yes |
| Playlists | yes | yes |
| Kiosks (trending feeds) | yes | no (router uses NewPipe) |
| Chapters | yes (description timestamps) | yes |
| Subtitles (manual + auto) | yes | yes |
| SponsorBlock segments | no (NewPipe's streamSegments are chapters) | no (F5 uses the API) |
| Storyboards / preview frames | yes | yes |
| Non-YouTube sites | no | yes |
| Cookies | yes | yes |
| PO token | yes | yes |
| Account feeds (subscriptions/liked) | no | yes (F4) |

## Tests

`app/src/test` covers the layer without a device:

- `UrlCanonTest` — every URL shape yt-x accepts, plus cache-key stability
- `CookieTxtTest` — Netscape/JSON parsing, RFC 6265 scoping, export round-trip
- `PoTokenTest` — both config syntaxes and the yt-dlp extractor-arg form
- `YtdlpJsonMapperTest` — recorded `-J` JSON: metadata, format selection, subtitles,
  chapters, availability, flat lists, channel tabs
- `CommandLineTest` — POSIX quoting for `CONFIG_YT_DLP_OPTS`
- `ExtractorFailureTest` — HTTP status, exception-name and stderr-phrase mapping
- `ExtractorRouterTest` — capability merging and backend selection

88 test cases run against both flavors (`testFastDebugUnitTest`, `testYtdlpDebugUnitTest`).
83 are offline and deterministic; the other five are the opt-in live checks below.

### Live checks

The offline suite cannot notice a renamed YouTube field. `NewPipeLiveTest` is opt-in and
talks to the real site:

```bash
RIVO_LIVE=1 ./gradlew :app:testFastDebugUnitTest --tests '*NewPipeLiveTest'
```

It resolves a public video, walks search into page two (proving the continuation token
round-trips), reads a channel's tabs and first page, fetches the Music kiosk, and asserts the
retired Trending feed fails with `REMOVED`. It caught two real defects that fixtures missed:
every search result was keyed `"newpipe"` (the shadowing bug above), and list/channel
extractors were consumed without `fetchPage()`.
