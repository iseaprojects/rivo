package io.github.iseaprojects.rivo.data.library

import io.github.iseaprojects.rivo.core.model.ChannelSummary
import io.github.iseaprojects.rivo.core.model.MediaProvider
import io.github.iseaprojects.rivo.core.model.MediaSummary
import io.github.iseaprojects.rivo.core.model.PlaylistSummary
import io.github.iseaprojects.rivo.core.model.libraryKey

/**
 * Row <-> model translation for the library tables.
 *
 * Identity itself is [io.github.iseaprojects.rivo.core.model.LibraryKeys] so the menu layer can ask
 * "is this saved?" without reaching into Room. This file only decides which columns are
 * snapshotted, and how to rebuild a model when the network is unavailable.
 */

fun MediaSummary.toRecord(): VideoRecord = VideoRecord(
    videoId = id,
    url = url,
    title = title,
    provider = provider.name,
    channelId = channelId,
    channelName = channelName,
    channelUrl = channelUrl,
    durationSeconds = durationSeconds,
    viewCount = viewCount,
    publishedText = publishedText,
    thumbnailUrl = thumbnailUrl,
    isLive = isLive,
)

fun VideoRecord.toSummary(): MediaSummary = MediaSummary(
    id = videoId,
    url = url,
    title = title,
    provider = runCatching { MediaProvider.valueOf(provider) }.getOrDefault(MediaProvider.OTHER),
    channelId = channelId,
    channelName = channelName,
    channelUrl = channelUrl,
    durationSeconds = durationSeconds,
    viewCount = viewCount,
    publishedText = publishedText,
    thumbnailUrl = thumbnailUrl,
    isLive = isLive,
)

fun ChannelSummary.toSubscription(now: Long): SubscriptionEntity = SubscriptionEntity(
    channelKey = libraryKey(),
    channelId = id.takeIf { it.isNotBlank() },
    url = url,
    name = name,
    provider = provider.name,
    avatarUrl = avatarUrl,
    subscribedAt = now,
)

fun SubscriptionEntity.toSummary(): ChannelSummary = ChannelSummary(
    id = channelId ?: url,
    url = url,
    name = name,
    provider = runCatching { MediaProvider.valueOf(provider) }.getOrDefault(MediaProvider.YOUTUBE),
    avatarUrl = avatarUrl,
    isSubscribed = true,
)

fun PlaylistSummary.toSavedPlaylist(now: Long): SavedPlaylistEntity = SavedPlaylistEntity(
    key = libraryKey(),
    playlistId = id,
    url = url,
    title = title,
    provider = provider.name,
    channelName = channelName,
    channelId = channelId,
    itemCount = itemCount,
    thumbnailUrl = thumbnailUrl,
    savedAt = now,
)

fun SavedPlaylistEntity.toSummary(): PlaylistSummary = PlaylistSummary(
    id = playlistId,
    url = url,
    title = title,
    provider = runCatching { MediaProvider.valueOf(provider) }.getOrDefault(MediaProvider.OTHER),
    channelName = channelName,
    channelId = channelId,
    itemCount = itemCount,
    thumbnailUrl = thumbnailUrl,
)
