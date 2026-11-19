package io.github.iseaprojects.rivo.data.extract

/**
 * The only extraction surface the rest of the app knows about.
 *
 * Two implementations exist:
 *
 *  - `NewPipeBackend` — NewPipeExtractor, always present, fast, YouTube-focused.
 *  - `YtDlpBackend`   — youtubedl-android, only in the `ytdlp` flavor, slower to warm up
 *                       but handles every site yt-dlp does and reads cookies/PO tokens.
 *
 * Implementations must be safe to call from any dispatcher and must translate every
 * failure into [ExtractorException]; leaking a backend-specific exception type past this
 * interface is a bug.
 */
interface VideoExtractor {

    /** Stable id used in [ContinueToken] and cache keys: `newpipe`, `ytdlp`. */
    val id: String

    /** Human-readable name for the About/Misc screens. */
    val displayName: String

    val capabilities: ExtractorCapabilities

    /** False when the backend cannot run at all (missing interpreter, init failure). */
    fun isAvailable(): Boolean

    /**
     * Performs one-time heavy initialisation (Python unpacking for yt-dlp) and returns
     * the backend version, or null when unknown. Safe to call repeatedly.
     */
    suspend fun warmUp(): String?

    suspend fun video(idOrUrl: String): VideoDetails

    suspend fun search(
        query: String,
        /** Raw YouTube `sp` filter code, already URL-encoded as upstream expects. */
        filters: String? = null,
        token: ContinueToken? = null,
    ): MediaPage

    suspend fun suggestions(query: String): List<String>

    /**
     * Channel metadata plus the first page of [tab]. When [tab] is null the backend picks
     * its default tab (Videos) or the channel landing page.
     */
    suspend fun channel(
        url: String,
        tab: ChannelTab? = null,
        token: ContinueToken? = null,
    ): ChannelResult

    suspend fun playlist(url: String, token: ContinueToken? = null): PlaylistResult

    /**
     * YouTube's Search tab inside one channel (`<channel>/search?query=…`).
     *
     * yt-dlp extracts it directly. NewPipeExtractor has no equivalent entry point and
     * inherits this default, so the fast flavor answers with a precise UNSUPPORTED
     * instead of pretending the channel has no matches.
     */
    suspend fun channelSearch(
        channelUrl: String,
        query: String,
        token: ContinueToken? = null,
    ): MediaPage = throw ExtractorException(
        FailureKind.UNSUPPORTED,
        "$displayName cannot search inside a channel; select the yt-dlp extractor for that",
    )

    suspend fun kiosk(kiosk: Kiosk, token: ContinueToken? = null): MediaPage

    /**
     * Account-only feed (subscriptions, history, watch later, liked, playlists).
     *
     * The default implementation refuses with UNSUPPORTED: these pages are rendered from
     * the signed-in session, which NewPipeExtractor cannot open. `YtDlpBackend` reads them
     * with the account cookies; the router only calls it when `CONFIG_ACCOUNT_FEEDS` is on
     * and a cookie set exists.
     */
    suspend fun accountFeed(feed: AccountFeed, token: ContinueToken? = null): MediaPage =
        throw ExtractorException(
            FailureKind.UNSUPPORTED,
            "$displayName cannot read the ${feed.name.lowercase()} feed; it needs the " +
                "yt-dlp extractor with account cookies",
        )
}
