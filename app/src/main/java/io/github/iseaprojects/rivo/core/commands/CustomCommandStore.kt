package io.github.iseaprojects.rivo.core.commands

import android.content.Context
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Persistent custom commands.
 *
 * The file lives at `filesDir/my/cmds.json`, which is the same layout yt-x uses under
 * `~/.config/yt-x/my/`. It is also the file the SAF provider exposes, so a command set
 * can be backed up, edited from a desktop, or copied between devices.
 *
 * Reads are lazy and tolerant: a corrupt or hand-edited file degrades to an empty list
 * (with the parse problem kept in [lastProblem]) instead of crashing the Miscellaneous
 * menu. Writes are atomic for the same reason the config file's are: a command is small
 * but losing the whole list to a truncated write is not.
 */
class CustomCommandStore(
    context: Context,
    private val idFactory: () -> String = { UUID.randomUUID().toString().take(8) },
) {

    private val file = File(context.filesDir, "$DIR/$FILE_NAME")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }
    private val lock = Mutex()

    private val _lastProblem = MutableStateFlow<String?>(null)
    val lastProblem: StateFlow<String?> = _lastProblem.asStateFlow()

    private val _commands = MutableStateFlow(load())
    val commands: StateFlow<List<StoredCommand>> = _commands.asStateFlow()

    fun current(): List<StoredCommand> = _commands.value

    fun find(id: String): StoredCommand? = _commands.value.firstOrNull { it.id == id }

    /** Adds a command. Blank names/URLs are rejected by returning null. */
    suspend fun add(name: String, url: String, options: String = ""): StoredCommand? {
        val cleanName = name.trim()
        val cleanUrl = url.trim()
        if (cleanName.isEmpty() || cleanUrl.isEmpty()) return null
        val command = StoredCommand(
            id = idFactory(),
            name = cleanName,
            url = cleanUrl,
            options = options.trim(),
        )
        lock.withLock {
            val next = _commands.value + command
            persist(next)
        }
        return command
    }

    suspend fun update(id: String, name: String, url: String, options: String = ""): Boolean {
        val cleanName = name.trim()
        val cleanUrl = url.trim()
        if (cleanName.isEmpty() || cleanUrl.isEmpty()) return false
        lock.withLock {
            val index = _commands.value.indexOfFirst { it.id == id }
            if (index < 0) return false
            val next = _commands.value.toMutableList()
            next[index] = next[index].copy(name = cleanName, url = cleanUrl, options = options.trim())
            persist(next)
        }
        return true
    }

    suspend fun remove(id: String): Boolean {
        lock.withLock {
            val next = _commands.value.filterNot { it.id == id }
            if (next.size == _commands.value.size) return false
            persist(next)
        }
        return true
    }

    fun reload() {
        _commands.value = load()
    }

    private fun load(): List<StoredCommand> {
        if (!file.exists()) return emptyList()
        return try {
            json.decodeFromString(CommandFile.serializer(), file.readText()).commands
                .filter { it.name.isNotBlank() && it.url.isNotBlank() }
                .distinctBy { it.id }
        } catch (error: Throwable) {
            _lastProblem.value = error.message ?: "cmds.json could not be read"
            emptyList()
        }
    }

    private suspend fun persist(commands: List<StoredCommand>) = withContext(Dispatchers.IO) {
        runCatching {
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, "${file.name}.tmp")
            temporary.writeText(
                json.encodeToString(CommandFile.serializer(), CommandFile(commands = commands)),
            )
            if (!temporary.renameTo(file)) {
                file.writeText(temporary.readText())
                temporary.delete()
            }
            _commands.value = commands
            _lastProblem.value = null
        }.onFailure { error ->
            _lastProblem.value = error.message ?: "cmds.json could not be written"
        }
    }

    @Serializable
    private data class CommandFile(
        val version: Int = 1,
        @SerialName("commands") val commands: List<StoredCommand> = emptyList(),
    )

    companion object {
        const val DIR = "my"
        const val FILE_NAME = "cmds.json"
    }
}
