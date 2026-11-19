package io.github.iseaprojects.rivo.data.download

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `CONFIG_DOWNLOAD_DIR` is a single string that selects the writer by shape (D42). The
 * normalisation is what keeps a hand-typed path from producing `Movies/Rivo//video/…`.
 */
class DownloadFolderTest {

    @Test
    fun `a blank folder falls back to the default`() {
        assertEquals(DownloadNaming.DEFAULT_FOLDER, DownloadFolder.normalise(""))
        assertEquals(DownloadNaming.DEFAULT_FOLDER, DownloadFolder.normalise("   "))
        assertEquals(DownloadNaming.DEFAULT_FOLDER, DownloadFolder.normalise("///"))
    }

    @Test
    fun `surrounding slashes are trimmed`() {
        assertEquals("Movies/Rivo", DownloadFolder.normalise("/Movies/Rivo/"))
        assertEquals("Download/Rivo", DownloadFolder.normalise("Download/Rivo//"))
    }

    @Test
    fun `an internal path survives untouched`() {
        assertEquals("Movies/Rivo/audio", DownloadFolder.normalise("Movies/Rivo/audio"))
    }

    @Test
    fun `the default root is the naming default`() {
        assertEquals(DownloadNaming.DEFAULT_FOLDER, DownloadFolder.DEFAULT_ROOT)
    }
}
