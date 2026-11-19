package io.github.iseaprojects.rivo.core.config

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Single owner of `filesDir/config`.
 *
 * Reads happen once per process (a few kB). Writes are atomic (temp file + rename), so
 * an interrupted edit can never truncate the config. Missing keys are appended on
 * startup, which makes new app versions self-migrating without ever overwriting a
 * value the user chose.
 */
class ConfigStore(context: Context) {

    private val appContext = context.applicationContext
    private val defaults: Map<String, String> by lazy { readDefaults() }
    private val writeMutex = Mutex()

    private val _state = MutableStateFlow(ConfigSnapshot(ConfigParser.parse(ensureInitialFile())))
    val state: StateFlow<ConfigSnapshot> = _state

    val configFile: File
        get() = File(appContext.filesDir, FILE_NAME).also { it.parentFile?.mkdirs() }

    fun snapshot(): ConfigSnapshot = _state.value

    fun reloadBlocking() {
        val text = runCatching { configFile.readText() }.getOrNull() ?: return
        _state.value = ConfigSnapshot(ConfigParser.parse(text))
    }

    suspend fun set(key: String, value: String) {
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                val document = ConfigDocument.parse(configFile.readText())
                document.set(key, value)
                writeAtomically(configFile, document.render())
                _state.value = ConfigSnapshot(document.toMap())
            }
        }
    }

    suspend fun setBoolean(key: String, value: Boolean) = set(key, value.toString())

    suspend fun remove(key: String) {
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                val document = ConfigDocument.parse(configFile.readText())
                document.remove(key)
                writeAtomically(configFile, document.render())
                _state.value = ConfigSnapshot(document.toMap())
            }
        }
    }

    /**
     * Used by the DocumentsProvider and Tasker receiver, which are synchronous
     * binder/receiver entry points.
     */
    fun setBlocking(key: String, value: String) {
        runBlocking { set(key, value) }
    }

    /** Replaces the whole file with `text`, then fills in any key it is missing. */
    suspend fun importText(text: String) {
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                val document = ConfigDocument.parse(text)
                defaults.forEach { (key, value) ->
                    if (document.get(key) == null) document.append(key, value)
                }
                writeAtomically(configFile, document.render())
                _state.value = ConfigSnapshot(document.toMap())
            }
        }
    }

    /** Merges missing defaults into an existing file. Returns the resulting text. */
    fun ensureDefaults() {
        val file = configFile
        if (!file.exists()) {
            writeAtomically(file, readDefaultAsset())
            return
        }
        val document = ConfigDocument.parse(file.readText())
        var changed = false
        defaults.forEach { (key, value) ->
            if (document.get(key) == null) {
                document.append(key, value)
                changed = true
            }
        }
        if (changed) writeAtomically(file, document.render())
    }

    private fun ensureInitialFile(): String {
        val file = configFile
        if (!file.exists()) {
            val text = readDefaultAsset()
            writeAtomically(file, text)
            return text
        }
        val raw = file.readText()
        val document = ConfigDocument.parse(raw)
        var changed = false
        defaults.forEach { (key, value) ->
            if (document.get(key) == null) {
                document.append(key, value)
                changed = true
            }
        }
        if (!changed) return raw
        val merged = document.render()
        writeAtomically(file, merged)
        return merged
    }

    private fun readDefaults(): Map<String, String> = ConfigParser.parse(readDefaultAsset())

    private fun readDefaultAsset(): String = try {
        appContext.assets.open(DEFAULT_ASSET).bufferedReader().use { it.readText() }
    } catch (_: java.io.IOException) {
        FALLBACK_DEFAULTS
    }

    private fun writeAtomically(target: File, text: String) {
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, "${target.name}.tmp")
        FileOutputStream(temporary).use { stream ->
            stream.write(text.toByteArray(Charsets.UTF_8))
            stream.flush()
            stream.fd.sync()
        }
        if (!temporary.renameTo(target)) {
            target.writeText(text)
            temporary.delete()
        }
    }

    companion object {
        const val FILE_NAME = "config"
        const val DEFAULT_ASSET = "config.default"

        private const val FALLBACK_DEFAULTS = """
CONFIG_PER_PAGE=30
CONFIG_ENABLE_PREVIEW=true
CONFIG_ENABLE_PREVIEW_IMAGES=true
CONFIG_NERD_FONTS=true
CONFIG_THEME=tokyo-night
CONFIG_LANG=auto
CONFIG_DOWNLOADS_ENUMERATE=false
CONFIG_NO_OF_RECENT=10
CONFIG_CACHE_RETENTION_DAYS=3
"""
    }
}
