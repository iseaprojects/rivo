package io.github.iseaprojects.rivo.core.launch

import io.github.iseaprojects.rivo.core.menu.MenuIds
import io.github.iseaprojects.rivo.core.menu.MenuPayload
import io.github.iseaprojects.rivo.core.model.ChannelSummary
import io.github.iseaprojects.rivo.core.model.FeedKind
import io.github.iseaprojects.rivo.core.model.LibraryKind
import io.github.iseaprojects.rivo.core.model.MediaProvider
import io.github.iseaprojects.rivo.core.model.PlaylistSummary
import io.github.iseaprojects.rivo.core.model.SearchKind

/**
 * Deep link -> navigation frame. This mirrors the CLI entry points of yt-x:
 *
 *  rivo://search?q=:4k+eonos&action=play&exit=true
 *  rivo://feed, rivo://saved, rivo://recent, rivo://downloads
 *  rivo://channel?name=X&tab=videos
 *  rivo://custom-playlist?name=X, rivo://custom-cmd?name=X
 *  rivo://playlist?name=X&id=PL...
 *  rivo://watch?v=... / any YouTube URL through ACTION_VIEW
 */
data class LaunchRoute(
    val menuId: String,
    val payload: MenuPayload = MenuPayload.None,
    val titleKey: String? = null,
    val title: String? = null,
)

class LaunchRouter {

    fun route(spec: LaunchSpec): LaunchRoute? {
        spec.deepLink?.let { link -> routeDeepLink(link)?.let { return it } }
        spec.externalUrl?.let { url ->
            return LaunchRoute(
                menuId = MenuIds.MEDIA_ACTIONS,
                payload = MenuPayload.Url(url),
            )
        }
        spec.sharedText?.let { text ->
            val url = extractUrl(text)
            return if (url != null) {
                LaunchRoute(menuId = MenuIds.MEDIA_ACTIONS, payload = MenuPayload.Url(url))
            } else {
                LaunchRoute(
                    menuId = MenuIds.SEARCH,
                    payload = MenuPayload.Search(text.trim()),
                    titleKey = "TXT_MENU_MAIN_SEARCH",
                )
            }
        }
        return null
    }

    private fun routeDeepLink(link: DeepLink): LaunchRoute? {
        val value = link.path.takeIf { it.isNotBlank() }
        return when (link.host) {
            "search" -> LaunchRoute(
                menuId = MenuIds.SEARCH,
                payload = MenuPayload.Search(link.param("q", "query") ?: "", kindOf(link.param("type"))),
                titleKey = "TXT_MENU_MAIN_SEARCH",
            )

            "feed" -> {
                val kind = when (link.param("kind")?.lowercase()) {
                    "subscriptions", "subs" -> FeedKind.SUBSCRIPTIONS
                    "trending" -> FeedKind.TRENDING
                    "watch-later", "watchlater" -> FeedKind.WATCH_LATER
                    "liked" -> FeedKind.LIKED
                    "history" -> FeedKind.HISTORY
                    "clips" -> FeedKind.CLIPS
                    else -> FeedKind.HOME
                }
                LaunchRoute(
                    menuId = MenuIds.FEED,
                    payload = MenuPayload.Feed(kind),
                    titleKey = when (kind) {
                        FeedKind.SUBSCRIPTIONS -> "TXT_MENU_MAIN_SUBS_FEED"
                        FeedKind.TRENDING -> "TXT_ANDROID_MENU_TRENDING"
                        FeedKind.WATCH_LATER -> "TXT_MENU_MAIN_WATCH_LATER"
                        FeedKind.LIKED -> "TXT_MENU_MAIN_LIKED"
                        FeedKind.HISTORY -> "TXT_MENU_MAIN_HISTORY"
                        FeedKind.CLIPS -> "TXT_ANDROID_MENU_CLIPS"
                        else -> "TXT_MENU_MAIN_FEED"
                    },
                )
            }

            "saved" -> LaunchRoute(
                menuId = MenuIds.LIBRARY,
                payload = MenuPayload.Library(LibraryKind.SAVED),
                titleKey = "TXT_ANDROID_MENU_SAVED",
            )

            "recent" -> LaunchRoute(
                menuId = MenuIds.LIBRARY,
                payload = MenuPayload.Library(LibraryKind.RECENT),
                titleKey = "TXT_MENU_MAIN_MY_RECENTS",
            )

            "downloads" -> LaunchRoute(
                menuId = MenuIds.DOWNLOADS,
                titleKey = "TXT_ANDROID_MENU_DOWNLOADS",
            )

            "misc" -> LaunchRoute(menuId = MenuIds.MISC, titleKey = "TXT_MENU_MAIN_MISC")

            "config" -> LaunchRoute(
                menuId = MenuIds.CONFIG,
                titleKey = "TXT_ANDROID_MENU_CONFIG_TOGGLES",
            )

            "channels" -> LaunchRoute(menuId = MenuIds.CHANNELS, titleKey = "TXT_MENU_MAIN_CHANNELS")

            "channel" -> {
                val name = link.param("name", "handle") ?: value
                val id = link.param("id") ?: value ?: name
                if (name.isNullOrBlank() && id.isNullOrBlank()) return null
                val channelId = id.orEmpty()
                val channelName = name ?: channelId
                LaunchRoute(
                    menuId = MenuIds.CHANNEL,
                    payload = MenuPayload.Channel(
                        ChannelSummary(
                            id = channelId,
                            url = link.param("url") ?: channelUrlFor(channelId),
                            name = channelName,
                        ),
                    ),
                    title = channelName,
                )
            }

            "playlist" -> {
                val name = link.param("name") ?: "Playlist"
                val id = link.param("id", "list") ?: value ?: name
                LaunchRoute(
                    menuId = MenuIds.PLAYLIST,
                    payload = MenuPayload.Playlist(
                        PlaylistSummary(
                            id = id,
                            url = link.param("url") ?: "https://www.youtube.com/playlist?list=$id",
                            title = name,
                        ),
                    ),
                    title = name,
                )
            }

            "custom-playlist" -> {
                val name = link.param("name") ?: value ?: return null
                LaunchRoute(
                    menuId = MenuIds.PLAYLIST,
                    payload = MenuPayload.Playlist(
                        PlaylistSummary(
                            id = name,
                            url = "",
                            title = name,
                            provider = MediaProvider.OTHER,
                        ),
                    ),
                    title = name,
                )
            }

            "custom-cmd" -> {
                val name = link.param("name") ?: value ?: return null
                LaunchRoute(
                    menuId = MenuIds.CUSTOM_COMMAND_ACTIONS,
                    payload = MenuPayload.Text(name),
                    title = name,
                )
            }

            "watch", "video", "shorts", "live" -> {
                val id = link.param("v", "id") ?: value
                val url = link.param("url")
                    ?: id?.let { "https://www.youtube.com/watch?v=$it" }
                    ?: return null
                LaunchRoute(menuId = MenuIds.MEDIA_ACTIONS, payload = MenuPayload.Url(url))
            }

            "media", "actions" -> {
                val url = link.param("url") ?: return null
                LaunchRoute(menuId = MenuIds.MEDIA_ACTIONS, payload = MenuPayload.Url(url))
            }

            else -> null
        }
    }

    private fun kindOf(type: String?): SearchKind = when (type?.lowercase()) {
        "playlist", "playlists" -> SearchKind.PLAYLISTS
        "channel", "channels" -> SearchKind.CHANNELS
        "short", "shorts" -> SearchKind.SHORTS
        "movie", "movies" -> SearchKind.MOVIES
        else -> SearchKind.VIDEOS
    }

    private fun channelUrlFor(id: String): String = when {
        id.startsWith("http", ignoreCase = true) -> id
        id.startsWith("UC") -> "https://www.youtube.com/channel/$id"
        id.startsWith("@") -> "https://www.youtube.com/$id"
        else -> "https://www.youtube.com/@$id"
    }

    private fun extractUrl(text: String): String? =
        URL_REGEX.find(text)?.value?.trimEnd('.', ',', ')', ']', '"', '\'')

    private companion object {
        val URL_REGEX = Regex("https?://[^\\s]+")
    }
}
