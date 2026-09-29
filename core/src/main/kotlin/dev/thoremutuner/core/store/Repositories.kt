package dev.thoremutuner.core.store

import dev.thoremutuner.core.bench.Outcome
import dev.thoremutuner.core.bench.TestSession
import dev.thoremutuner.core.config.AzaharState
import dev.thoremutuner.core.model.Game
import dev.thoremutuner.core.profile.GameProfile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A granted SAF tree. [label] is a display name only. */
@Serializable
data class FolderGrant(val treeUri: String, val rootDocumentId: String, val label: String)

/** `settings.json` (PLAN section 10). */
@Serializable
data class AppSettings(
    val schemaVersion: Int = CURRENT_SCHEMA,
    val onboardingDone: Boolean = false,
    val romFolders: List<FolderGrant> = emptyList(),
    /** emulatorId -> granted config folder. */
    val emulatorFolders: Map<String, FolderGrant> = emptyMap(),
    /** emulatorId -> preferred package name. */
    val preferredPackage: Map<String, String> = emptyMap(),
    /** system id -> preferred emulatorId. */
    val preferredEmulator: Map<String, String> = emptyMap(),
    /** system id -> preferred RetroArch core file. */
    val preferredCore: Map<String, String> = emptyMap(),
    /** gameKey -> emulatorId chosen on the game screen. */
    val gameEmulator: Map<String, String> = emptyMap(),
    val exportFolder: FolderGrant? = null,
    /** One-time hints already shown (e.g. "folderHint:dolphin"). */
    val hintsShown: Set<String> = emptySet(),
    val lastPerfMode: String = "",
    val lastFanMode: String = "",
)

/** `library.json`: games plus scan metadata (the games double as the scan cache). */
@Serializable
data class Library(
    val schemaVersion: Int = CURRENT_SCHEMA,
    val games: List<Game> = emptyList(),
    val lastScanAt: Long? = null,
)

@Serializable
data class ProfilesFile(val schemaVersion: Int = CURRENT_SCHEMA, val profiles: List<GameProfile> = emptyList())

@Serializable
data class SessionsFile(val schemaVersion: Int = CURRENT_SCHEMA, val sessions: List<TestSession> = emptyList())

/** Per-game session summary for the library (count and last result, no samples). */
@Serializable
data class SessionSummary(val count: Int, val lastStartedAt: Long, val lastOutcome: Outcome? = null)

/** `session_index.json`: summaries keyed by game key, maintained on every session write. */
@Serializable
data class SessionIndex(val schemaVersion: Int = CURRENT_SCHEMA, val games: Map<String, SessionSummary> = emptyMap())

const val CURRENT_SCHEMA = 1

class NewerSchemaException(path: String, version: Int) :
    IllegalStateException("$path was written by a newer app version (schema $version)")

/** Reads/writes versioned JSON documents; unreadable files fall back to defaults rather than crash. */
internal class Codec(private val store: JsonStore, private val json: Json = ThorJson.store) {
    fun <T> load(path: String, serializer: KSerializer<T>, default: () -> T): T {
        val text = store.read(path) ?: return default()
        val version = runCatching { json.parseToJsonElement(text).jsonObject["schemaVersion"]?.jsonPrimitive?.int }.getOrNull()
        if (version != null && version > CURRENT_SCHEMA) throw NewerSchemaException(path, version)
        return try {
            json.decodeFromString(serializer, migrate(text, version))
        } catch (e: kotlinx.serialization.SerializationException) {
            // Keep the unreadable file for inspection and start fresh.
            store.write("$path.corrupt", text)
            default()
        }
    }

    fun <T> save(path: String, serializer: KSerializer<T>, value: T) = store.write(path, json.encodeToString(serializer, value))

    /** Schema migrations (v1 is the first schema; add steps here when it changes). */
    private fun migrate(text: String, version: Int?): String = text
}

class SettingsRepository(store: JsonStore, private val io: CoroutineDispatcher = Dispatchers.IO) {
    private val codec = Codec(store)
    private val mutex = Mutex()
    private val state = MutableStateFlow<AppSettings?>(null)
    val flow: StateFlow<AppSettings?> = state.asStateFlow()

    suspend fun get(): AppSettings = withContext(io) { mutex.withLock { current() } }

    suspend fun update(transform: (AppSettings) -> AppSettings): AppSettings = withContext(io) {
        mutex.withLock {
            val next = transform(current())
            codec.save(PATH, AppSettings.serializer(), next)
            state.value = next
            next
        }
    }

    private fun current(): AppSettings =
        state.value ?: codec.load(PATH, AppSettings.serializer()) { AppSettings() }.also { state.value = it }

    companion object { const val PATH = "settings.json" }
}

class LibraryRepository(store: JsonStore, private val io: CoroutineDispatcher = Dispatchers.IO) {
    private val codec = Codec(store)
    private val mutex = Mutex()
    private val state = MutableStateFlow<Library?>(null)
    val flow: StateFlow<Library?> = state.asStateFlow()

    suspend fun get(): Library = withContext(io) { mutex.withLock { current() } }

    suspend fun save(library: Library) = withContext(io) {
        mutex.withLock {
            codec.save(PATH, Library.serializer(), library)
            state.value = library
        }
    }

    suspend fun updateGame(key: String, transform: (Game) -> Game): Game? = withContext(io) {
        mutex.withLock {
            val lib = current()
            var updated: Game? = null
            val games = lib.games.map { if (it.key == key) transform(it).also { g -> updated = g } else it }
            val next = lib.copy(games = games)
            codec.save(PATH, Library.serializer(), next)
            state.value = next
            updated
        }
    }

    private fun current(): Library =
        state.value ?: codec.load(PATH, Library.serializer()) { Library() }.also { state.value = it }

    companion object { const val PATH = "library.json" }
}

class ProfileRepository(store: JsonStore, private val io: CoroutineDispatcher = Dispatchers.IO) {
    private val codec = Codec(store)
    private val mutex = Mutex()

    suspend fun get(gameKey: String): List<GameProfile> = withContext(io) { mutex.withLock { load(gameKey) } }

    suspend fun update(gameKey: String, transform: (List<GameProfile>) -> List<GameProfile>): List<GameProfile> =
        withContext(io) {
            mutex.withLock {
                val next = transform(load(gameKey))
                codec.save(path(gameKey), ProfilesFile.serializer(), ProfilesFile(profiles = next))
                next
            }
        }

    private fun load(gameKey: String) = codec.load(path(gameKey), ProfilesFile.serializer()) { ProfilesFile() }.profiles

    companion object {
        fun path(gameKey: String): String {
            require(gameKey.matches(Regex("^[0-9a-f]{16}$"))) { "invalid game key" }
            return "profiles/$gameKey.json"
        }
    }
}

class SessionRepository(private val store: JsonStore, private val io: CoroutineDispatcher = Dispatchers.IO) {
    private val codec = Codec(store)
    private val mutex = Mutex()

    suspend fun get(gameKey: String): List<TestSession> = withContext(io) { mutex.withLock { load(gameKey) } }

    /** Inserts or replaces a session by id. */
    suspend fun upsert(session: TestSession) = withContext(io) {
        mutex.withLock {
            val list = (load(session.gameKey).filterNot { it.id == session.id } + session).sortedBy { it.startedAt }
            codec.save(path(session.gameKey), SessionsFile.serializer(), SessionsFile(sessions = list))
            updateIndex(session.gameKey, list)
        }
    }

    suspend fun delete(gameKey: String, id: String) = withContext(io) {
        mutex.withLock {
            val list = load(gameKey).filterNot { it.id == id }
            codec.save(path(gameKey), SessionsFile.serializer(), SessionsFile(sessions = list))
            updateIndex(gameKey, list)
        }
    }

    /**
     * Count and last result per game without reading any samples. Built once from the session
     * files if the index is missing (e.g. data from an older build), then kept up to date.
     */
    suspend fun summaries(): Map<String, SessionSummary> = withContext(io) {
        mutex.withLock { loadIndexOrRebuild().games }
    }

    /** The index, rebuilt from the session files (and saved) when the file is missing. */
    private fun loadIndexOrRebuild(): SessionIndex {
        if (store.read(INDEX) == null) {
            val all = store.list("sessions").filter { it.endsWith(".json") }.map { it.removeSuffix(".json") }
                .filter { KEY.matches(it) }
            val games = all.associateWith { summaryOf(load(it)) }.filterValues { it != null }.mapValues { it.value!! }
            val rebuilt = SessionIndex(games = games)
            codec.save(INDEX, SessionIndex.serializer(), rebuilt)
            return rebuilt
        }
        return codec.load(INDEX, SessionIndex.serializer()) { SessionIndex() }
    }

    private fun updateIndex(gameKey: String, list: List<TestSession>) {
        // Missing index (older data): rebuild from all session files first, never start empty.
        val index = loadIndexOrRebuild()
        val summary = summaryOf(list)
        val games = if (summary == null) index.games - gameKey else index.games + (gameKey to summary)
        codec.save(INDEX, SessionIndex.serializer(), index.copy(games = games))
    }

    private fun summaryOf(list: List<TestSession>): SessionSummary? {
        val last = list.maxByOrNull { it.startedAt } ?: return null
        return SessionSummary(list.size, last.startedAt, last.result?.outcome)
    }

    suspend fun all(): List<TestSession> = withContext(io) {
        mutex.withLock {
            store.list("sessions").filter { it.endsWith(".json") }.map { it.removeSuffix(".json") }
                .filter { KEY.matches(it) }.flatMap { load(it) }
        }
    }

    private fun load(gameKey: String) = codec.load(path(gameKey), SessionsFile.serializer()) { SessionsFile() }.sessions

    companion object {
        const val INDEX = "session_index.json"
        private val KEY = Regex("^[0-9a-f]{16}$")

        fun path(gameKey: String): String {
            require(gameKey.matches(KEY)) { "invalid game key" }
            return "sessions/$gameKey.json"
        }
    }
}

class AzaharStateRepository(store: JsonStore, private val io: CoroutineDispatcher = Dispatchers.IO) {
    private val codec = Codec(store)
    private val mutex = Mutex()

    suspend fun get(): AzaharState = withContext(io) { mutex.withLock { codec.load(PATH, AzaharState.serializer()) { AzaharState() } } }
    suspend fun save(state: AzaharState) = withContext(io) { mutex.withLock { codec.save(PATH, AzaharState.serializer(), state) } }

    companion object { const val PATH = "azahar_state.json" }
}

/**
 * Startup check: files written by a newer app version (higher schemaVersion) must not be read or
 * overwritten. Returns the offending paths; the app shows an error instead of crashing.
 */
object StorageCheck {
    fun newerSchemaFiles(store: JsonStore, json: Json = ThorJson.store): List<String> {
        val paths = listOf(SettingsRepository.PATH, LibraryRepository.PATH, AzaharStateRepository.PATH) +
            store.list("profiles").filter { it.endsWith(".json") }.map { "profiles/$it" } +
            store.list("sessions").filter { it.endsWith(".json") }.map { "sessions/$it" }
        return (paths + SessionRepository.INDEX).filter { p ->
            val text = store.read(p) ?: return@filter false
            val v = runCatching { json.parseToJsonElement(text).jsonObject["schemaVersion"]?.jsonPrimitive?.int }.getOrNull()
            v != null && v > CURRENT_SCHEMA
        }
    }
}
