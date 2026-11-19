package io.github.iseaprojects.rivo.menu

import io.github.iseaprojects.rivo.core.config.ConfigSnapshot
import io.github.iseaprojects.rivo.core.i18n.MapStrings
import io.github.iseaprojects.rivo.core.menu.HandlerIds
import io.github.iseaprojects.rivo.core.menu.MenuContext
import io.github.iseaprojects.rivo.core.menu.MenuDownloadFile
import io.github.iseaprojects.rivo.core.menu.MenuDownloadState
import io.github.iseaprojects.rivo.core.menu.MenuDownloadTask
import io.github.iseaprojects.rivo.core.menu.MenuDownloads
import io.github.iseaprojects.rivo.core.menu.MenuIds
import io.github.iseaprojects.rivo.core.menu.MenuNavigator
import io.github.iseaprojects.rivo.core.menu.MenuNode
import io.github.iseaprojects.rivo.core.menu.MenuPayload
import io.github.iseaprojects.rivo.core.menu.MenuTone
import io.github.iseaprojects.rivo.core.theme.ThemeSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The downloads screen mixes three kinds of row — queue, files on disk, maintenance — in
 * one catalog menu, so these tests pin which rows exist for each state and where they lead.
 */
class DownloadMenusTest {

    private val labels = mapOf(
        "TXT_ANDROID_DOWNLOAD_STATE_QUEUED" to "queued",
        "TXT_ANDROID_DOWNLOAD_STATE_RUNNING" to "downloading",
        "TXT_ANDROID_DOWNLOAD_STATE_PAUSED" to "paused",
        "TXT_ANDROID_DOWNLOAD_STATE_DONE" to "done",
        "TXT_ANDROID_DOWNLOAD_STATE_FAILED" to "failed",
        "TXT_ANDROID_DOWNLOAD_STATE_CANCELLED" to "cancelled",
    )

    private fun context(
        downloads: MenuDownloads = MenuDownloads.EMPTY,
        payload: MenuPayload = MenuPayload.None,
    ): MenuContext = MenuContext(
        menuId = MenuIds.DOWNLOADS,
        payload = payload,
        strings = MapStrings("en", labels),
        config = ConfigSnapshot(emptyMap()),
        theme = ThemeSpec.FALLBACK,
        themes = emptyList(),
        languages = emptyList(),
        navigator = object : MenuNavigator {
            override fun open(
                menuId: String,
                payload: MenuPayload,
                title: String,
                nodes: List<MenuNode>?,
            ) = Unit

            override fun back(): Boolean = false
            override fun exit() = Unit
        },
        downloads = downloads,
    )

    private fun task(
        id: String = "video:abc",
        state: MenuDownloadState = MenuDownloadState.QUEUED,
        progress: Float? = null,
        error: String? = null,
    ) = MenuDownloadTask(
        id = id,
        title = "Lo-fi beats",
        state = state,
        channel = "Chill",
        error = error,
        bytesTotal = 5_000_000,
        progress = progress,
    )

    private fun file(
        name: String = "Lo-fi beats.mp4",
        isVideo: Boolean = true,
    ) = MenuDownloadFile(
        id = "content://media/external/video/media/42",
        name = name,
        relativePath = "video/individual/Chill/$name",
        sizeBytes = 5_000_000,
        isVideo = isVideo,
    )

    private fun rows(downloads: MenuDownloads) =
        DownloadMenus.downloads().build(context(downloads))

    private fun row(rows: List<MenuNode>, id: String): MenuNode =
        rows.firstOrNull { it.id == id } ?: error("missing row $id: ${rows.map { it.id }}")

    private fun has(rows: List<MenuNode>, id: String) = rows.any { it.id == id }

    @Test
    fun `an empty screen still explains itself and offers the folder actions`() {
        val rows = rows(MenuDownloads.EMPTY)

        assertEquals("downloads.folder", rows.first().id)
        assertTrue(has(rows, "downloads.empty"))
        assertFalse(row(rows, "downloads.clear").enabled)
        assertEquals(HandlerIds.DOWNLOADS_CLEAR_FINISHED, row(rows, "downloads.clear").handler)
        assertEquals(HandlerIds.DOWNLOADS_REFRESH, row(rows, "downloads.refresh").handler)
        assertEquals(HandlerIds.DOWNLOADS_OPEN_FOLDER, row(rows, "downloads.open_folder").handler)
        assertEquals(HandlerIds.DOWNLOADS_PICK_FOLDER, row(rows, "downloads.choose_folder").handler)
        assertEquals(HandlerIds.BACK, rows[rows.lastIndex - 1].handler)
        assertEquals(HandlerIds.EXIT, rows.last().handler)
    }

    @Test
    fun `a queued task becomes a row that opens its own actions`() {
        val rows = rows(MenuDownloads(tasks = listOf(task())))

        val row = row(rows, "downloads.task.video:abc")
        assertEquals(MenuIds.DOWNLOAD_TASK_ACTIONS, row.targetMenuId)
        assertEquals(MenuPayload.Download("video:abc"), row.payload)
        assertEquals("Lo-fi beats", row.label)
        assertTrue(row.detail.orEmpty().contains("queued"))
        assertNull(row.handler)
    }

    @Test
    fun `a running task shows its percentage and is accented`() {
        val row = row(
            rows(MenuDownloads(tasks = listOf(task(state = MenuDownloadState.RUNNING, progress = 0.42f)))),
            "downloads.task.video:abc",
        )
        assertTrue(row.detail.orEmpty().contains("42%"))
        assertEquals(MenuTone.ACCENT, row.tone)
    }

    @Test
    fun `a failed task carries the reason and the destructive tone`() {
        val row = row(
            rows(
                MenuDownloads(
                    tasks = listOf(
                        task(state = MenuDownloadState.FAILED, error = "HTTP 403"),
                    ),
                ),
            ),
            "downloads.task.video:abc",
        )
        assertTrue(row.detail.orEmpty().contains("HTTP 403"))
        assertEquals(MenuTone.DESTRUCTIVE, row.tone)
    }

    @Test
    fun `finished tasks leave the queue list and enable clear finished`() {
        val downloads = MenuDownloads(
            tasks = listOf(
                task(state = MenuDownloadState.DONE),
                task(id = "audio:def", state = MenuDownloadState.CANCELLED),
                task(id = "video:ghi"),
            ),
        )
        val rows = rows(downloads)

        assertFalse(has(rows, "downloads.task.video:abc"))
        assertFalse(has(rows, "downloads.task.audio:def"))
        assertTrue(has(rows, "downloads.task.video:ghi"))
        assertTrue(row(rows, "downloads.clear").enabled)
        assertEquals("2", row(rows, "downloads.clear").detail)
    }

    @Test
    fun `files are listed with size and path and open the file actions`() {
        val rows = rows(MenuDownloads(files = listOf(file())))
        val row = row(rows, "downloads.file.content://media/external/video/media/42")

        assertEquals(MenuIds.DOWNLOAD_FILE_ACTIONS, row.targetMenuId)
        assertEquals(MenuPayload.DownloadFile("content://media/external/video/media/42"), row.payload)
        assertEquals("Lo-fi beats", row.label)
        assertTrue(row.detail.orEmpty().contains("5 MB"))
        assertTrue(row.detail.orEmpty().contains("video/individual/Chill"))
    }

    @Test
    fun `a folder problem replaces the empty hint`() {
        val rows = rows(MenuDownloads(problem = "the SD card is not mounted"))
        assertTrue(has(rows, "downloads.problem"))
        assertEquals(HandlerIds.DOWNLOADS_REFRESH, row(rows, "downloads.problem").handler)
    }

    @Test
    fun `a running task offers pause, cancel and remove`() {
        val rows = DownloadMenus.taskActions().build(
            context(
                downloads = MenuDownloads(tasks = listOf(task(state = MenuDownloadState.RUNNING))),
                payload = MenuPayload.Download("video:abc"),
            ),
        )
        assertEquals(HandlerIds.DOWNLOAD_TASK_PAUSE, row(rows, "downloads.task.pause").handler)
        assertEquals(HandlerIds.DOWNLOAD_TASK_CANCEL, row(rows, "downloads.task.cancel").handler)
        assertEquals(HandlerIds.DOWNLOAD_TASK_REMOVE, row(rows, "downloads.task.remove").handler)
        assertFalse(has(rows, "downloads.task.resume"))
        assertEquals(MenuPayload.Download("video:abc"), row(rows, "downloads.task.pause").payload)
    }

    @Test
    fun `a paused task offers resume instead of pause`() {
        val rows = DownloadMenus.taskActions().build(
            context(
                downloads = MenuDownloads(tasks = listOf(task(state = MenuDownloadState.PAUSED))),
                payload = MenuPayload.Download("video:abc"),
            ),
        )
        assertEquals(HandlerIds.DOWNLOAD_TASK_RESUME, row(rows, "downloads.task.resume").handler)
        assertFalse(has(rows, "downloads.task.pause"))
    }

    @Test
    fun `a failed task offers retry and no cancel`() {
        val rows = DownloadMenus.taskActions().build(
            context(
                downloads = MenuDownloads(
                    tasks = listOf(task(state = MenuDownloadState.FAILED, error = "timeout")),
                ),
                payload = MenuPayload.Download("video:abc"),
            ),
        )
        assertEquals(HandlerIds.DOWNLOAD_TASK_RETRY, row(rows, "downloads.task.retry").handler)
        assertFalse(has(rows, "downloads.task.cancel"))
        assertTrue(has(rows, "downloads.task.remove"))
    }

    @Test
    fun `a missing task says so instead of failing silently`() {
        val rows = DownloadMenus.taskActions().build(
            context(payload = MenuPayload.Download("video:gone")),
        )
        assertTrue(has(rows, "downloads.missing"))
        assertEquals(HandlerIds.BACK, rows[rows.lastIndex - 1].handler)
    }

    @Test
    fun `file actions play, open, share and delete`() {
        val rows = DownloadMenus.fileActions().build(
            context(
                downloads = MenuDownloads(files = listOf(file())),
                payload = MenuPayload.DownloadFile("content://media/external/video/media/42"),
            ),
        )
        assertEquals(HandlerIds.DOWNLOAD_FILE_PLAY, row(rows, "downloads.file.play").handler)
        assertEquals(HandlerIds.DOWNLOAD_FILE_OPEN, row(rows, "downloads.file.open").handler)
        assertEquals(HandlerIds.DOWNLOAD_FILE_SHARE, row(rows, "downloads.file.share").handler)
        assertEquals(HandlerIds.DOWNLOAD_FILE_DELETE, row(rows, "downloads.file.delete").handler)
        assertEquals("TXT_ANDROID_DOWNLOAD_PLAY", row(rows, "downloads.file.play").labelKey)
        assertEquals(
            "content://media/external/video/media/42",
            (row(rows, "downloads.file.delete").payload as MenuPayload.DownloadFile).id,
        )
    }

    @Test
    fun `an audio file says listen rather than play`() {
        val rows = DownloadMenus.fileActions().build(
            context(
                downloads = MenuDownloads(files = listOf(file(name = "Track.m4a", isVideo = false))),
                payload = MenuPayload.DownloadFile("content://media/external/video/media/42"),
            ),
        )
        assertEquals("TXT_ANDROID_DOWNLOAD_LISTEN", row(rows, "downloads.file.play").labelKey)
    }

    @Test
    fun `a missing file reports itself`() {
        val rows = DownloadMenus.fileActions().build(
            context(payload = MenuPayload.DownloadFile("content://gone")),
        )
        assertTrue(has(rows, "downloads.missing"))
    }
}
