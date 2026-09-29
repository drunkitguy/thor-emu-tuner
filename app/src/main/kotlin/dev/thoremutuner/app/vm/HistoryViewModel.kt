package dev.thoremutuner.app.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.thoremutuner.app.AppContainer
import dev.thoremutuner.core.bench.Comparison
import dev.thoremutuner.core.bench.ComparisonResult
import dev.thoremutuner.core.bench.TestSession
import dev.thoremutuner.core.model.Game
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HistoryUi(
    val game: Game? = null,
    val groups: List<Pair<Int, List<TestSession>>> = emptyList(),
    val emulatorNames: Map<String, String> = emptyMap(),
    val selected: List<String> = emptyList(),
    val loaded: Boolean = false,
)

class HistoryViewModel(private val c: AppContainer, private val key: String) : ViewModel() {
    private val _ui = MutableStateFlow(HistoryUi())
    val ui: StateFlow<HistoryUi> = _ui.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val game = c.library.get().games.firstOrNull { it.key == key }
            val sessions = c.sessionRepo.get(key)
            _ui.value = _ui.value.copy(
                game = game,
                groups = Comparison.groupByRevision(sessions),
                emulatorNames = c.presets.emulators.associate { it.emulatorId to it.name },
                selected = _ui.value.selected.filter { id -> sessions.any { it.id == id } },
                loaded = true,
            )
        }
    }

    /** Select up to two sessions for A/B; a third selection replaces the oldest. */
    fun toggle(id: String) {
        val sel = _ui.value.selected
        _ui.value = _ui.value.copy(selected = if (id in sel) sel - id else (sel + id).takeLast(2))
    }

    fun delete(id: String) {
        viewModelScope.launch {
            c.sessionRepo.delete(key, id)
            refresh()
        }
    }
}

data class CompareUi(val a: TestSession? = null, val b: TestSession? = null, val result: ComparisonResult? = null, val loaded: Boolean = false)

class CompareViewModel(private val c: AppContainer, private val key: String, private val idA: String, private val idB: String) : ViewModel() {
    private val _ui = MutableStateFlow(CompareUi())
    val ui: StateFlow<CompareUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            val sessions = c.sessionRepo.get(key)
            // A = the older session, B = the newer one.
            val pair = listOfNotNull(sessions.firstOrNull { it.id == idA }, sessions.firstOrNull { it.id == idB }).sortedBy { it.startedAt }
            val a = pair.getOrNull(0)
            val b = pair.getOrNull(1)
            _ui.value = CompareUi(a, b, if (a != null && b != null) Comparison.compare(a, b) else null, loaded = true)
        }
    }
}
