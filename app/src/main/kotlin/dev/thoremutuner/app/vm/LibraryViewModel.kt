package dev.thoremutuner.app.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.thoremutuner.app.AppContainer
import dev.thoremutuner.app.bench.LiveSession
import dev.thoremutuner.app.scan.ScanProgress
import dev.thoremutuner.core.bench.Outcome
import dev.thoremutuner.core.model.Game
import dev.thoremutuner.core.model.SystemId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class GameRow(
    val game: Game,
    val emulatorName: String?,
    val hasProfile: Boolean,
    val lastOutcome: Outcome?,
)

data class LibraryUi(
    val loading: Boolean = true,
    val rows: List<GameRow> = emptyList(),
    val systems: List<Pair<SystemId, Int>> = emptyList(),
    val system: SystemId? = null,
    val query: String = "",
    val onlyProfiles: Boolean = false,
    val onlyTested: Boolean = false,
    val onlyUnknownId: Boolean = false,
    val lostFolders: Int = 0,
    val folderCount: Int = 0,
) {
    val visible: List<GameRow>
        get() = rows.filter { r ->
            (system == null || r.game.system == system) &&
                (query.isBlank() || r.game.title.contains(query, true) || r.game.id?.value?.contains(query, true) == true) &&
                (!onlyProfiles || r.hasProfile) &&
                (!onlyTested || r.lastOutcome != null) &&
                (!onlyUnknownId || r.game.id == null)
        }
}

class LibraryViewModel(private val c: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(LibraryUi())
    val ui: StateFlow<LibraryUi> = _ui.asStateFlow()
    val scan: StateFlow<ScanProgress> = c.scanner.progress
    val live: StateFlow<LiveSession?> = c.sessions.live

    init {
        viewModelScope.launch { c.library.flow.collectLatest { refresh() } }
    }

    fun refresh() = viewModelScope.launch {
        val built = withContext(Dispatchers.IO) {
            val settings = c.settings.get()
            val games = c.library.get().games
            val withProfiles = c.store.list("profiles").map { it.removeSuffix(".json") }.toSet()
            val withSessions = c.store.list("sessions").map { it.removeSuffix(".json") }.toSet()
            val rows = games.map { g ->
                val emuId = settings.gameEmulator[g.key] ?: settings.preferredEmulator[g.system.id]
                val emu = emuId?.let { c.presets.emulator(it) } ?: c.presets.emulatorsFor(g.system).firstOrNull()
                val outcome = if (g.key in withSessions) {
                    runCatching { c.sessionRepo.get(g.key).maxByOrNull { it.startedAt }?.result?.outcome }.getOrNull()
                } else null
                GameRow(g, emu?.name, g.key in withProfiles, outcome)
            }
            val lost = settings.romFolders.count { !c.saf.hasPermission(it.treeUri, write = false) }
            Triple(rows, lost, settings.romFolders.size)
        }
        val (rows, lost, folders) = built
        val systems = rows.groupingBy { it.game.system }.eachCount().toList().sortedBy { it.first.ordinal }
        _ui.value = _ui.value.copy(
            loading = false, rows = rows, systems = systems, lostFolders = lost, folderCount = folders,
            system = _ui.value.system?.takeIf { s -> systems.any { it.first == s } },
        )
    }

    fun setSystem(s: SystemId?) { _ui.value = _ui.value.copy(system = s) }
    fun setQuery(q: String) { _ui.value = _ui.value.copy(query = q) }
    fun toggleProfiles() { _ui.value = _ui.value.copy(onlyProfiles = !_ui.value.onlyProfiles) }
    fun toggleTested() { _ui.value = _ui.value.copy(onlyTested = !_ui.value.onlyTested) }
    fun toggleUnknown() { _ui.value = _ui.value.copy(onlyUnknownId = !_ui.value.onlyUnknownId) }
    fun rescan(force: Boolean) = c.scanner.start(force)
}
