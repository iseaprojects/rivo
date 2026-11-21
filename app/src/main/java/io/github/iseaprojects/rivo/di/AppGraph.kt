package io.github.iseaprojects.rivo.di

import android.content.Context
import android.util.Log
import io.github.iseaprojects.rivo.BuildConfig
import io.github.iseaprojects.rivo.core.cache.CacheRetention
import io.github.iseaprojects.rivo.core.commands.CustomCommandStore
import io.github.iseaprojects.rivo.core.config.ConfigSnapshot
import io.github.iseaprojects.rivo.core.config.ConfigKeys
import io.github.iseaprojects.rivo.core.config.ConfigStore
import io.github.iseaprojects.rivo.core.config.RuntimeConfig
import io.github.iseaprojects.rivo.core.i18n.LangInfo
import io.github.iseaprojects.rivo.core.i18n.Strings
import io.github.iseaprojects.rivo.core.i18n.StringsRepository
import io.github.iseaprojects.rivo.core.menu.MenuCommand
import io.github.iseaprojects.rivo.core.menu.MenuCommandSource
import io.github.iseaprojects.rivo.core.menu.MenuContext
import io.github.iseaprojects.rivo.core.menu.MenuDownloadFile
import io.github.iseaprojects.rivo.core.menu.MenuDownloadState
import io.github.iseaprojects.rivo.core.menu.MenuDownloadTask
import io.github.iseaprojects.rivo.core.menu.MenuDownloads
import io.github.iseaprojects.rivo.core.menu.MenuEngine
import io.github.iseaprojects.rivo.core.menu.MenuExtensions
import io.github.iseaprojects.rivo.core.menu.MenuHandlerRegistry
import io.github.iseaprojects.rivo.core.menu.MenuLibrary
import io.github.iseaprojects.rivo.core.menu.MenuNavigator
import io.github.iseaprojects.rivo.core.menu.MenuPayload
import io.github.iseaprojects.rivo.core.menu.MenuPlaylist
import io.github.iseaprojects.rivo.core.menu.MenuUpdateState
import io.github.iseaprojects.rivo.core.menu.MenuUpdates
import io.github.iseaprojects.rivo.core.menu.extension.ExtensionRepository
import io.github.iseaprojects.rivo.core.theme.ThemeRepository
import io.github.iseaprojects.rivo.core.theme.ThemeSpec
import io.github.iseaprojects.rivo.core.update.UpdateRepository
import io.github.iseaprojects.rivo.core.update.UpdateState
import io.github.iseaprojects.rivo.data.download.DownloadEngine
import io.github.iseaprojects.rivo.data.download.DownloadRepository
import io.github.iseaprojects.rivo.data.download.DownloadState
import io.github.iseaprojects.rivo.data.extract.ExtractorRouter
import io.github.iseaprojects.rivo.data.extract.YtdlpSupport
import io.github.iseaprojects.rivo.data.extract.newpipe.NewPipeBackend
import io.github.iseaprojects.rivo.data.library.LibraryDatabase
import io.github.iseaprojects.rivo.data.library.LibraryLimits
import io.github.iseaprojects.rivo.data.library.LibraryRepository
import io.github.iseaprojects.rivo.data.net.CookieRepository
import io.github.iseaprojects.rivo.data.net.PoTokenConfig
import io.github.iseaprojects.rivo.menu.BuiltInMenus
import io.github.iseaprojects.rivo.menu.handlers.BuiltInHandlers
import io.github.iseaprojects.rivo.player.PlaybackActions
import io.github.iseaprojects.rivo.platform.CacheCleanupWorker
import io.github.iseaprojects.rivo.platform.ShortcutPublisher
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Manual service locator.
 *
 * Hilt would add a KSP processor, an extra build stage and generated indirection for
 * a graph this small. Everything here is a process-scoped singleton with explicit
 * constructor dependencies, which makes the wiring auditable and unit tests trivial.
 */
class AppGraph(context: Context) {

    val appContext: Context = context.applicationContext

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val config: ConfigStore = ConfigStore(appContext)
    val runtime: RuntimeConfig = RuntimeConfig(config, scope)

    /**
     * User extensions. The repository owns both the bundled `.ui.json` patches and the
     * SAF folder, so the menu engine has exactly one source and a folder reload cannot
     * leave the asset patches behind. It is built before the string/theme repositories
     * because they layer its language packs and themes over the bundled ones.
     */
    val commands: CustomCommandStore = CustomCommandStore(appContext)
    val extensions: ExtensionRepository = ExtensionRepository(appContext, runtime.snapshot, scope)

    val strings: StringsRepository = StringsRepository(
        appContext,
        runtime.snapshot,
        scope,
        extensions.languageOverlays,
    )
    val themes: ThemeRepository = ThemeRepository(
        appContext,
        runtime.snapshot,
        scope,
        extensions.themes,
    )

    /** Local language packs and themes provided by the SAF folder. */
    val uiExtensions: ExtensionRepository get() = extensions

    /**
     * GitHub release state. `UPDATE_MANIFEST` names the Rivo repository; the
     * Android build has no release feed of its own, so the row can only report which
     * upstream release the port was based on and what has landed since.
     *
     * The "last checked" stamp lives in preferences rather than in the repository's
     * state so the six-hour window survives a cold start; that is what keeps automatic
     * checks to one per window instead of one per launch.
     */
    val updates: UpdateRepository = UpdateRepository(
        config = { runtime.snapshot.value },
        scope = scope,
        lastUpToDateCheck = { updatePrefs.getLong(KEY_LAST_UP_TO_DATE, 0L) },
        rememberUpToDateCheck = { at ->
            updatePrefs.edit().putLong(KEY_LAST_UP_TO_DATE, at).apply()
        },
    )

    private val updatePrefs by lazy {
        appContext.getSharedPreferences(PREFS_UPDATE, Context.MODE_PRIVATE)
    }

    /**
     * Local library. The database is created lazily by Room on first query, so building it
     * during Application.onCreate costs nothing; every write and read goes through the
     * repository's IO dispatcher.
     */
    val database: LibraryDatabase = LibraryDatabase.create(appContext)
    val library: LibraryRepository = LibraryRepository(database.library())

    val cookies: CookieRepository = CookieRepository(appContext) { runtime.snapshot.value }

    val extractors: ExtractorRouter = ExtractorRouter(
        config = { runtime.snapshot.value },
        cook = cookies,
        newPipe = NewPipeBackend(
            cookies = cookies,
            poToken = {
                PoTokenConfig.parse(runtime.snapshot.value.string(ConfigKeys.PO_TOKEN))
            },
            locale = ::extractorLocale,
        ),
        ytDlpFactory = { provider, snapshot ->
            YtdlpSupport.create(appContext, provider, snapshot)
        },
    )

    /**
     * Playback is created lazily: it needs the graph (extractors, library, config), and
     * constructing it eagerly during `AppGraph`'s own initialization would hand it a
     * half-built locator.
     */
    val playback: PlaybackActions by lazy { PlaybackActions(this) }

    /**
     * Downloads. The repository is lazy for the same reason as playback, and the engine
     * is built after it so both share one extractor router; the service is started by the
     * repository when a queued task is waiting.
     */
    val downloads: DownloadRepository by lazy {
        DownloadRepository.create(appContext, extractors, { runtime.snapshot.value }, scope)
    }

    val downloadEngine: DownloadEngine by lazy {
        DownloadEngine(
            context = appContext,
            router = extractors,
            config = { runtime.snapshot.value },
            fallback = YtdlpSupport.downloadFallback(appContext, cookies, { runtime.snapshot.value }),
        )
    }

    /**
     * Downloader state for the menu layer, mapped once here so `core.menu` never imports
     * the data layer (same shape as [librarySnapshot]). Progress is combined in because
     * the store keeps it out of the queue file on purpose; the row still needs a percent.
     */
    val downloadSnapshot: StateFlow<MenuDownloads> = combine(
        downloads.snapshot,
        downloads.progress,
    ) { snapshot, progress ->
        MenuDownloads(
            tasks = snapshot.tasks.map { task ->
                MenuDownloadTask(
                    id = task.id,
                    title = task.title,
                    state = when (task.state) {
                        DownloadState.QUEUED -> MenuDownloadState.QUEUED
                        DownloadState.RUNNING -> MenuDownloadState.RUNNING
                        DownloadState.PAUSED -> MenuDownloadState.PAUSED
                        DownloadState.DONE -> MenuDownloadState.DONE
                        DownloadState.FAILED -> MenuDownloadState.FAILED
                        DownloadState.CANCELLED -> MenuDownloadState.CANCELLED
                    },
                    channel = task.channel,
                    error = task.error,
                    outputPath = task.outputPath,
                    bytesTotal = task.bytesTotal,
                    bytesDone = task.bytesDone,
                    progress = progress[task.id],
                )
            },
            files = snapshot.files.map { file ->
                MenuDownloadFile(
                    id = file.uri.toString(),
                    name = file.name,
                    relativePath = file.relativePath,
                    sizeBytes = file.sizeBytes,
                    isVideo = file.isVideo,
                )
            },
            folderLabel = downloads.folderLabel(),
            problem = snapshot.folderProblem,
        )
    }
        .stateIn(scope, SharingStarted.Eagerly, MenuDownloads.EMPTY)

    val handlers: MenuHandlerRegistry = BuiltInHandlers.registry(
        appContext = appContext,
        config = config,
        router = extractors,
        library = library,
        playback = playback,
        downloads = downloads,
        cookies = cookies,
        commands = commands,
        extensions = extensions,
        updates = updates,
    )

    val menus: MenuEngine = MenuEngine(
        catalog = BuiltInMenus.catalog(),
        handlers = handlers,
        extensions = extensions,
        onIssue = { issue -> Log.w(TAG, issue) },
    )

    /**
     * One snapshot of the library for the menu layer. Combining here, once, keeps
     * `MenuContext` immutable: saving a video re-emits the snapshot, RivoRoot rebuilds the
     * context, and every visible row relabels from "Save" to "UnSave" in the same frame.
     */
    val librarySnapshot: StateFlow<MenuLibrary> = combine(
        library.saved(),
        library.subscriptions(),
        library.playlists(),
        library.searchHistory(SEARCH_HISTORY_LIMIT),
        library.savedPlaylists(),
    ) { saved, subs, playlists, history, savedPlaylists ->
        MenuLibrary(
            saved = saved,
            subscriptions = subs,
            playlists = playlists.map { MenuPlaylist(it.id, it.title, it.itemCount) },
            searchHistory = history,
            savedPlaylists = savedPlaylists,
        )
    }.stateIn(scope, SharingStarted.Eagerly, MenuLibrary.EMPTY)

    /**
     * Extension state for the menu layer: the repository snapshot plus the locally
     * created commands, merged once. `MenuExtensions` is immutable for the same reason as
     * [MenuLibrary]; a menu rebuild reads it without touching the SAF provider.
     */
    val extensionsSnapshot: StateFlow<MenuExtensions> = combine(
        extensions.state,
        commands.commands,
        commands.lastProblem,
    ) { extensionState, local, problem ->
        val localCommands = local.map { stored ->
            MenuCommand(
                id = stored.id,
                name = stored.name,
                url = stored.url,
                options = stored.options,
                source = MenuCommandSource.LOCAL,
            )
        }
        MenuExtensions(
            enabled = extensionState.enabled,
            folderLabel = extensionState.folderLabel,
            autoloaded = extensionState.autoloaded,
            commands = (localCommands + extensionState.commands)
                .distinctBy { it.id }
                .sortedBy { it.name.lowercase() },
            sites = extensionState.sites,
            entries = extensionState.entries,
            issues = (extensionState.issues + listOfNotNull(problem)).distinct(),
        )
    }.stateIn(scope, SharingStarted.Eagerly, MenuExtensions.EMPTY)

    /** GitHub release state, mapped so `core.menu` never imports the update client. */
    val updatesSnapshot: StateFlow<MenuUpdates> = updates.state
        .map { state ->
            when (state) {
                UpdateState.Idle -> MenuUpdates(
                    state = MenuUpdateState.IDLE,
                    currentVersion = BuildConfig.RIVO_VERSION,
                )

                UpdateState.Checking -> MenuUpdates(
                    state = MenuUpdateState.CHECKING,
                    currentVersion = BuildConfig.RIVO_VERSION,
                )

                is UpdateState.Current -> MenuUpdates(
                    state = MenuUpdateState.CURRENT,
                    currentVersion = state.version,
                    latestVersion = state.version,
                    checkedAt = state.checkedAt,
                )

                is UpdateState.Available -> MenuUpdates(
                    state = MenuUpdateState.AVAILABLE,
                    currentVersion = state.currentVersion,
                    latestVersion = state.release.tag.removePrefix("v"),
                    releaseName = state.release.name,
                    notes = state.release.notes,
                    url = state.release.url,
                    publishedAt = state.release.publishedAt,
                    checkedAt = state.checkedAt,
                )

                is UpdateState.Failed -> MenuUpdates(
                    state = MenuUpdateState.FAILED,
                    currentVersion = BuildConfig.RIVO_VERSION,
                    message = state.message,
                    checkedAt = state.checkedAt,
                )
            }
        }
        .stateIn(
            scope,
            SharingStarted.Eagerly,
            MenuUpdates(currentVersion = BuildConfig.RIVO_VERSION),
        )

    init {
        extensions.state.value.issues.forEach { issue -> Log.w(TAG, "extension: $issue") }
        // The worker is idempotent; scheduling at start keeps the schedule valid after an
        // app update without needing a boot receiver. The retention is passed explicitly:
        // this runs inside the graph's constructor, so the worker must not reach back into
        // RivoApp.graph while it is still uninitialized.
        CacheCleanupWorker.schedule(
            appContext,
            CacheRetention.normaliseDays(
                runtime.snapshot.value.int(ConfigKeys.CACHE_RETENTION_DAYS, CacheRetention.DEFAULT_DAYS),
            ),
        )
        scope.launch {
            commands.commands.collect { local -> ShortcutPublisher.publish(appContext, local) }
        }
    }

    /**
     * Extractor language follows the UI language so titles/descriptions come back in the
     * language the user reads, not the system one after an in-app switch.
     */
    private fun extractorLocale(): Locale {
        val configured = runtime.snapshot.value.string(ConfigKeys.LANG, "auto")
        if (configured.isBlank() || configured.equals("auto", ignoreCase = true)) {
            return Locale.getDefault()
        }
        return runCatching { Locale.forLanguageTag(configured) }.getOrDefault(Locale.getDefault())
    }

    fun menuContext(
        menuId: String,
        payload: MenuPayload,
        strings: Strings,
        snapshot: ConfigSnapshot,
        theme: ThemeSpec,
        themes: List<ThemeSpec>,
        navigator: MenuNavigator,
        languages: List<LangInfo> = this.strings.languages,
        nerdFontAvailable: Boolean = true,
        library: MenuLibrary = librarySnapshot.value,
        downloads: MenuDownloads = downloadSnapshot.value,
        extensions: MenuExtensions = extensionsSnapshot.value,
        updates: MenuUpdates = updatesSnapshot.value,
    ): MenuContext = MenuContext(
        menuId = menuId,
        payload = payload,
        strings = strings,
        config = snapshot,
        theme = theme,
        themes = themes,
        languages = languages,
        navigator = navigator,
        nerdFontAvailable = nerdFontAvailable,
        library = library,
        downloads = downloads,
        extensions = extensions,
        updates = updates,
    )

    fun shutdown() {
        runCatching { database.close() }
        scope.cancel()
    }

    private companion object {
        const val TAG = "rivo"

        /** Preferences file for the update checker's cross-process throttle. */
        const val PREFS_UPDATE = "rivo-updates"
        const val KEY_LAST_UP_TO_DATE = "lastUpToDateCheck"

        /**
         * Rows kept in the search-history table and shown in the history menu. Upstream
         * keeps the file unbounded; a phone needs a ceiling, and 50 covers every `!N`
         * recall a person will remember.
         */
        const val SEARCH_HISTORY_LIMIT = LibraryLimits.SEARCH_HISTORY
    }
}
