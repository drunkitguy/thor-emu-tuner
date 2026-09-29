package dev.thoremutuner.app.vm

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.thoremutuner.app.AppContainer
import dev.thoremutuner.app.apply.ApplyOutcome
import dev.thoremutuner.app.apply.ApplyPreview
import dev.thoremutuner.core.model.Game
import dev.thoremutuner.core.preset.EmulatorDef
import dev.thoremutuner.core.profile.ProfileRevision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ApplyUi(
    val def: EmulatorDef? = null,
    val game: Game? = null,
    val revision: ProfileRevision? = null,
    val preview: ApplyPreview? = null,
    val busy: Boolean = false,
    val outcome: ApplyOutcome? = null,
    val checked: Set<Int> = emptySet(),
    val hasExportFolder: Boolean = false,
)

class ApplyViewModel(private val c: AppContainer, private val key: String, emulatorId: String) : ViewModel() {
    private val def = c.presets.emulator(emulatorId)
    private val _ui = MutableStateFlow(ApplyUi(def))
    val ui: StateFlow<ApplyUi> = _ui.asStateFlow()

    init {
        refresh()
    }

    fun refresh() = viewModelScope.launch {
        val d = def ?: return@launch
        _ui.value = _ui.value.copy(busy = true)
        val settings = c.settings.get()
        val game = c.library.get().games.firstOrNull { it.key == key }
        val rev = c.profiles.get(key).firstOrNull { it.emulatorId == d.emulatorId }?.latest
        val preview = if (game != null && d.isFull) c.applier.preview(game, d, rev, settings) else null
        _ui.value = _ui.value.copy(game = game, revision = rev, preview = preview, busy = false, hasExportFolder = settings.exportFolder != null)
    }

    fun write() = viewModelScope.launch {
        val d = def ?: return@launch
        val game = _ui.value.game ?: return@launch
        _ui.value = _ui.value.copy(busy = true, outcome = null)
        val result = c.applier.apply(game, d, _ui.value.revision)
        _ui.value = _ui.value.copy(outcome = result)
        refresh()
    }

    /** Export to the remembered export folder. */
    fun export() = viewModelScope.launch {
        val d = def ?: return@launch
        val game = _ui.value.game ?: return@launch
        val grant = c.settings.get().exportFolder ?: return@launch
        _ui.value = _ui.value.copy(busy = true, outcome = null)
        val result = c.applier.export(game, d, _ui.value.revision, grant)
        _ui.value = _ui.value.copy(busy = false, outcome = result)
    }

    /** A new export folder was picked: remember it (read+write), then export. */
    fun setExportFolderAndExport(uri: Uri) = viewModelScope.launch {
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                c.saf.takePersistable(uri, write = true)
                val grant = c.saf.grantFor(uri)
                c.settings.update { it.copy(exportFolder = grant) }
            }.isSuccess
        }
        if (ok) export() else _ui.value = _ui.value.copy(outcome = ApplyOutcome.Failed("Android did not allow writing to that folder."))
    }

    fun toggleChecked(i: Int) {
        val cur = _ui.value.checked
        _ui.value = _ui.value.copy(checked = if (i in cur) cur - i else cur + i)
    }

    /** Plain text of the config (or checklist) for "Copy text". */
    fun copyText(): String {
        val ui = _ui.value
        val d = ui.def ?: return ""
        return if (d.isFull) {
            ui.preview?.render?.text.orEmpty()
        } else {
            ui.revision?.values.orEmpty().joinToString("\n") { "${it.section} > ${it.key} = ${it.value}" }
        }
    }
}
