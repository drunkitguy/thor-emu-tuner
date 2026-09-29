package dev.thoremutuner.app.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.thoremutuner.app.AppContainer
import dev.thoremutuner.core.preset.EmulatorDef
import dev.thoremutuner.core.preset.PresetDef
import dev.thoremutuner.core.preset.PresetResolver
import dev.thoremutuner.core.profile.ProfileService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class BaselineUi(val def: EmulatorDef? = null, val currentPresetId: String? = null, val saved: Boolean = false)

class BaselineViewModel(private val c: AppContainer, private val key: String, emulatorId: String) : ViewModel() {
    private val def = c.presets.emulator(emulatorId)
    private val _ui = MutableStateFlow(BaselineUi(def))
    val ui: StateFlow<BaselineUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            val latest = c.profiles.get(key).firstOrNull { it.emulatorId == emulatorId }?.latest
            _ui.value = _ui.value.copy(currentPresetId = latest?.basePresetId)
        }
    }

    /** "Use" creates a new revision with the note "Baseline: <preset>". */
    fun use(preset: PresetDef) = viewModelScope.launch {
        val d = def ?: return@launch
        val values = PresetResolver.resolve(d, preset.id)
        c.profiles.update(key) {
            ProfileService.addRevision(it, key, d.emulatorId, preset.id, values, "Baseline: ${preset.name}", System.currentTimeMillis()).first
        }
        _ui.value = _ui.value.copy(saved = true, currentPresetId = preset.id)
    }
}
