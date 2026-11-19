package io.github.iseaprojects.rivo.core.model

import java.util.Locale

/**
 * Canonical identity for everything the local library stores.
 *
 * A bare video id is ambiguous across providers (YouTube `dQw4w9WgXcQ` vs a PeerTube id),
 * so the provider is part of the key. The URL is used when an item has no id at all, which
 * happens for some yt-dlp entries from non-YouTube sites.
 *
 * The object lives in `core` because three layers need the same rule: the Room tables,
 * the menu builders that decide whether to show "Save" or "UnSave", and the handlers that
 * mutate the tables. Duplicating the rule anywhere else would let the UI and the database
 * disagree about identity.
 */
object LibraryKeys {

    fun video(media: MediaSummary): String {
        val id = media.id.takeIf { it.isNotBlank() } ?: media.url.trim()
        return media.provider.name.lowercase(Locale.ROOT) + ":" + id
    }

    fun playlist(playlist: PlaylistSummary): String {
        val id = playlist.id.takeIf { it.isNotBlank() } ?: playlist.url.trim()
        return playlist.provider.name.lowercase(Locale.ROOT) + ":playlist:" + id
    }

    fun channel(summary: ChannelSummary): String = channel(summary.id, summary.url)

    /**
     * Channels are identified by their `UC…` id when one is known, then by the id embedded
     * in a `/channel/` URL, then by the trimmed URL. Videos rarely carry the id but almost
     * always carry a `/channel/UC…` uploader URL, so both paths land on the same key.
     */
    fun channel(channelId: String?, url: String): String {
        val fromUrl = url.substringAfter("/channel/", "").substringBefore('/')
        val id = channelId?.takeIf { it.isNotBlank() }
            ?: fromUrl.takeIf { it.isNotBlank() }
            ?: url.trim().trimEnd('/').lowercase(Locale.ROOT)
        return "channel:" + id
    }

    /** Case-folded search key: `SearchHistoryEntity.queryKey`. */
    fun query(text: String): String = text.trim().lowercase(Locale.ROOT)

    /**
     * Upstream `!1`, `!2` recall. Returns the 0-based index, or null when [raw] is a
     * normal query. `!0` and negative numbers are rejected rather than clamped.
     */
    fun recallIndex(raw: String): Int? {
        val text = raw.trim()
        if (text.length < 2 || text[0] != '!') return null
        val digits = text.substring(1)
        if (!digits.all { it.isDigit() }) return null
        val number = digits.toIntOrNull() ?: return null
        return if (number <= 0) null else number - 1
    }
}

fun MediaSummary.libraryKey(): String = LibraryKeys.video(this)

fun PlaylistSummary.libraryKey(): String = LibraryKeys.playlist(this)

fun ChannelSummary.libraryKey(): String = LibraryKeys.channel(this)
