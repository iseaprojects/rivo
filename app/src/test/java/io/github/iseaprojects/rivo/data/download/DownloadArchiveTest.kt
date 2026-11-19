package io.github.iseaprojects.rivo.data.download

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `CONFIG_DOWNLOAD_ARCHIVE` exists to stop the same bytes being fetched twice. The key is
 * `kind<TAB>videoId` — deliberately not yt-x's "kind + playlist URL" (D40), because the
 * playlist an item was listed in does not change the file.
 */
class DownloadArchiveTest {

    private lateinit var directory: File
    private lateinit var file: File

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("rivo-archive").toFile()
        file = File(directory, DownloadArchive.FILE_NAME)
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    private fun task(
        videoId: String = "abc",
        kind: DownloadKind = DownloadKind.VIDEO,
        playlist: String? = null,
    ) = DownloadTask(
        videoId = videoId,
        url = "https://www.youtube.com/watch?v=$videoId",
        title = "Video $videoId",
        kind = kind,
        playlist = playlist,
    )

    @Test
    fun `a finished download is remembered`() = runBlocking {
        val archive = DownloadArchive(file)
        assertFalse(archive.contains(task()))
        archive.add(task())
        assertTrue(archive.contains(task()))
        assertTrue(file.readText().contains("video\tabc"))
    }

    @Test
    fun `the playlist a video came from is not part of the key`() = runBlocking {
        val archive = DownloadArchive(file)
        archive.add(task(playlist = "Roadtrip"))
        assertTrue(archive.contains(task(playlist = "Something else")))
        assertTrue(archive.contains(task(playlist = null)))
    }

    @Test
    fun `video and audio are archived separately`() = runBlocking {
        val archive = DownloadArchive(file)
        archive.add(task(kind = DownloadKind.AUDIO))
        assertFalse(archive.contains(task(kind = DownloadKind.VIDEO)))
        assertTrue(archive.contains(task(kind = DownloadKind.AUDIO)))
    }

    @Test
    fun `adding twice writes one line`() = runBlocking {
        val archive = DownloadArchive(file)
        archive.add(task())
        archive.add(task())
        assertEquals(1, file.readLines().count { it.isNotBlank() })
    }

    @Test
    fun `removing a video forgets every rendition of it`() = runBlocking {
        val archive = DownloadArchive(file)
        archive.add(task())
        archive.add(task(kind = DownloadKind.AUDIO))
        archive.remove("abc")
        assertFalse(archive.contains(task()))
        assertFalse(archive.contains(task(kind = DownloadKind.AUDIO)))
    }

    @Test
    fun `removing a video that is not archived leaves the file alone`() = runBlocking {
        val archive = DownloadArchive(file)
        archive.add(task(videoId = "keep"))
        archive.remove("other")
        assertTrue(archive.contains(task(videoId = "keep")))
    }

    @Test
    fun `an existing archive is read back including comments and blanks`() = runBlocking {
        file.parentFile?.mkdirs()
        file.writeText("# Rivo archive\n\nvideo\tabc\n  audio\tdef  \n")
        val archive = DownloadArchive(file)
        assertTrue(archive.contains(task(videoId = "abc")))
        assertTrue(archive.contains(task(videoId = "def", kind = DownloadKind.AUDIO)))
        assertEquals(2, archive.size())
    }

    @Test
    fun `a missing archive file is simply empty`() = runBlocking {
        val archive = DownloadArchive(file)
        assertFalse(archive.contains(task()))
        assertFalse(file.exists())
    }
}
