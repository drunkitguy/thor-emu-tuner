package dev.thoremutuner.app.scan

import dev.thoremutuner.app.saf.SafAccess
import dev.thoremutuner.core.model.Game
import dev.thoremutuner.core.model.SystemId
import dev.thoremutuner.core.scan.RomRoot
import dev.thoremutuner.core.scan.Scanner
import dev.thoremutuner.core.store.Library
import dev.thoremutuner.core.store.LibraryRepository
import dev.thoremutuner.core.store.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger

data class ScanProgress(
    val running: Boolean = false,
    val found: Int = 0,
    val probed: Int = 0,
    /** Probed games that have a detected game ID. */
    val withId: Int = 0,
    val bySystem: Map<SystemId, Int> = emptyMap(),
    val errors: Int = 0,
    val unreadableFolders: Int = 0,
    val finished: Boolean = false,
    val cancelled: Boolean = false,
)

/**
 * Runs the :core scanner over every granted ROM folder on Dispatchers.IO with at most 4 probes
 * in flight (PLAN section 3.1). Unchanged files come from the scan cache unless forced.
 */
class LibraryScanner(
    private val saf: SafAccess,
    private val library: LibraryRepository,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    private val scanner = Scanner()
    private val state = MutableStateFlow(ScanProgress())
    val progress: StateFlow<ScanProgress> = state.asStateFlow()
    private var job: Job? = null

    fun start(force: Boolean) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) { run(force) }
    }

    fun cancel() {
        job?.cancel()
        state.value = state.value.copy(running = false, cancelled = true, finished = true)
    }

    private suspend fun run(force: Boolean) {
        state.value = ScanProgress(running = true)
        val folders = settings.get().romFolders
        val previous = library.get().games
        val cache = previous.associateBy { it.key }
        val results = mutableListOf<Game>()
        var unreadable = 0
        val probed = AtomicInteger()
        val withId = AtomicInteger()
        val found = AtomicInteger()
        for (folder in folders) {
            if (!saf.hasPermission(folder.treeUri, write = false)) {
                unreadable++
                // Keep what we knew about this folder so profiles and history stay attached.
                results += previous.filter { it.treeUri == folder.treeUri }
                continue
            }
            val access = saf.treeAccess(folder.treeUri)
            val root = RomRoot(folder.treeUri, folder.rootDocumentId, folder.label)
            val candidates = try {
                scanner.collect(root, access) { job?.isActive == false }
            } catch (e: Exception) {
                unreadable++
                results += previous.filter { it.treeUri == folder.treeUri }
                continue
            }
            found.addAndGet(candidates.size)
            state.value = state.value.copy(found = found.get())
            val permits = Semaphore(MAX_PARALLEL)
            val games = coroutineScope {
                candidates.map { c ->
                    async(Dispatchers.IO) {
                        permits.withPermit {
                            val g = scanner.probe(c, access, cache[Scanner.gameKey(folder.treeUri, c.entry.documentId)], force)
                            val n = probed.incrementAndGet()
                            if (g.id != null) withId.incrementAndGet()
                            if (n % 5 == 0 || n == found.get()) {
                                state.value = state.value.copy(probed = n, withId = withId.get())
                            }
                            g
                        }
                    }
                }.awaitAll()
            }
            results += games
            state.value = state.value.copy(
                bySystem = results.groupingBy { it.system }.eachCount(),
                errors = results.count { it.scanError != null },
            )
            if (!scope.isActive) return
        }
        val deduped = results.distinctBy { it.key }.sortedWith(compareBy({ it.system.ordinal }, { it.title.lowercase() }))
        library.save(Library(games = deduped, lastScanAt = System.currentTimeMillis()))
        state.value = ScanProgress(
            running = false,
            found = found.get(),
            probed = probed.get(),
            withId = deduped.count { it.id != null },
            bySystem = deduped.groupingBy { it.system }.eachCount(),
            errors = deduped.count { it.scanError != null },
            unreadableFolders = unreadable,
            finished = true,
        )
    }

    /** Removes games of a folder the user removed. */
    suspend fun dropFolder(treeUri: String) {
        val lib = library.get()
        library.save(lib.copy(games = lib.games.filterNot { it.treeUri == treeUri }))
    }

    companion object {
        const val MAX_PARALLEL = 4
    }
}
