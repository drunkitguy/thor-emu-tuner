package dev.thoremutuner.app.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.thoremutuner.app.AppContainer
import dev.thoremutuner.core.config.DolphinWriter
import dev.thoremutuner.core.preset.EmulatorDef
import dev.thoremutuner.core.preset.PresetResolver
import dev.thoremutuner.core.preset.SettingDef
import dev.thoremutuner.core.preset.SettingType
import dev.thoremutuner.core.preset.ValueCheck
import dev.thoremutuner.core.profile.ProfileService
import dev.thoremutuner.core.profile.SettingValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One free-form row of a reference emulator's manual revision (UI path, setting, value). */
data class ManualRow(val id: Int, val path: String, val label: String, val value: String)

data class TweakUi(
    val def: EmulatorDef? = null,
    val loaded: Boolean = false,
    val baseRev: Int? = null,
    val basePresetId: String? = null,
    val values: List<SettingValue> = emptyList(),
    val baseline: List<SettingValue> = emptyList(),
    val errors: Map<String, String> = emptyMap(),
    val warnings: Map<String, String> = emptyMap(),
    val conflicts: Map<String, String> = emptyMap(),
    val showAdvanced: Boolean = false,
    val editedRefs: Set<String> = emptySet(),
    val priorUserRefs: Set<String> = emptySet(),
    val manual: List<ManualRow> = emptyList(),
    val saved: Boolean = false,
    val saveError: String? = null,
) {
    fun value(s: SettingDef): String? = values.firstOrNull { it.section == s.section && it.key == s.key }?.value
    fun baselineValue(s: SettingDef): String? = baseline.firstOrNull { it.section == s.section && it.key == s.key }?.value
    val changedCount: Int get() = PresetResolver.changedRefs(baseline, values).size
}

class TweakViewModel(private val c: AppContainer, private val key: String, emulatorId: String) : ViewModel() {
    private val def = c.presets.emulator(emulatorId)
    private val _ui = MutableStateFlow(TweakUi(def))
    val ui: StateFlow<TweakUi> = _ui.asStateFlow()
    private var gameId: String? = null
    private var nextRowId = 0

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val d = def ?: return
        val game = c.library.get().games.firstOrNull { it.key == key }
        gameId = game?.id?.value
        val latest = c.profiles.get(key).firstOrNull { it.emulatorId == d.emulatorId }?.latest
        val baseline = PresetResolver.baseline(d, latest?.basePresetId)
        val values = latest?.values ?: baseline
        val manual = if (!d.isFull) values.map { ManualRow(nextRowId++, it.section, it.key, it.value) } else emptyList()
        _ui.value = TweakUi(
            def = d, loaded = true, baseRev = latest?.rev, basePresetId = latest?.basePresetId,
            values = values, baseline = baseline, priorUserRefs = latest?.userEditedRefs?.toSet().orEmpty(),
            manual = manual,
        ).withConflicts()
    }

    private fun TweakUi.withConflicts(): TweakUi {
        val d = def ?: return this
        val list = PresetResolver.conflicts(d, gameId, values)
        return copy(conflicts = list.associate { SettingValue.refOf(it.value.section, it.value.key) to "Overrides the known fix for ${it.fix.title} (${it.value.value})" })
    }

    fun setValue(s: SettingDef, raw: String) {
        val d = def ?: return
        val ref = SettingValue.refOf(s.section, s.key)
        val cur = _ui.value
        when (val check = PresetResolver.validate(d, s, raw)) {
            is ValueCheck.Ok -> {
                val values = PresetResolver.applyEdits(d, cur.values, listOf(SettingValue(s.section, s.key, check.normalized)))
                _ui.value = cur.copy(
                    values = values,
                    errors = cur.errors - ref,
                    warnings = if (check.warning != null) cur.warnings + (ref to check.warning!!) else cur.warnings - ref,
                    editedRefs = cur.editedRefs + ref,
                    saved = false,
                ).withConflicts()
            }
            is ValueCheck.Invalid -> _ui.value = cur.copy(errors = cur.errors + (ref to check.message))
        }
    }

    /** Numeric +/- stepping for gamepads. */
    fun step(s: SettingDef, direction: Int) {
        val current = _ui.value.value(s) ?: s.defaultValue ?: return
        val step = s.step ?: 1.0
        if (s.type == SettingType.INT) {
            val n = (current.toLongOrNull() ?: return) + (direction * step).toLong()
            val clamped = n.coerceIn(s.min?.toLong() ?: Long.MIN_VALUE, s.max?.toLong() ?: Long.MAX_VALUE)
            setValue(s, clamped.toString())
        } else if (s.type == SettingType.FLOAT) {
            val d = (current.toDoubleOrNull() ?: return) + direction * step
            val rounded = Math.round(d * 1000.0) / 1000.0
            setValue(s, rounded.toString())
        }
    }

    /** Starts managing a setting with its default (or first option / minimum). */
    fun manage(s: SettingDef) {
        val initial = s.defaultValue ?: s.options.firstOrNull()?.value ?: when (s.type) {
            SettingType.BOOL -> "false"
            SettingType.INT -> PresetResolver.formatNumber(s.min ?: 0.0)
            SettingType.FLOAT -> "1.0"
            else -> ""
        }
        setValue(s, initial)
    }

    fun reset(s: SettingDef) {
        val cur = _ui.value
        val ref = SettingValue.refOf(s.section, s.key)
        val base = cur.baselineValue(s)
        if (base != null) {
            setValue(s, base)
            _ui.value = _ui.value.copy(editedRefs = _ui.value.editedRefs - ref)
        } else {
            unmanage(s)
        }
    }

    fun unmanage(s: SettingDef) {
        val cur = _ui.value
        val ref = SettingValue.refOf(s.section, s.key)
        _ui.value = cur.copy(
            values = PresetResolver.remove(cur.values, s.section, s.key),
            errors = cur.errors - ref, warnings = cur.warnings - ref, editedRefs = cur.editedRefs - ref, saved = false,
        ).withConflicts()
    }

    fun toggleAdvanced() { _ui.value = _ui.value.copy(showAdvanced = !_ui.value.showAdvanced) }

    fun isHack(s: SettingDef): Boolean = def?.emulatorId == "dolphin" && s.section == DolphinWriter.HACKS_SECTION

    // ---------------------------------------------------------------- manual (reference emulators)

    fun upsertManual(row: ManualRow) {
        val cur = _ui.value
        val rows = if (cur.manual.any { it.id == row.id }) cur.manual.map { if (it.id == row.id) row else it } else cur.manual + row.copy(id = nextRowId++)
        _ui.value = cur.copy(manual = rows, saved = false)
    }

    fun removeManual(id: Int) { _ui.value = _ui.value.copy(manual = _ui.value.manual.filterNot { it.id == id }, saved = false) }

    fun newManualRow(): ManualRow = ManualRow(-1, "", "", "")

    // ---------------------------------------------------------------- save

    fun save(note: String) = viewModelScope.launch {
        val d = def ?: return@launch
        val cur = _ui.value
        if (cur.errors.isNotEmpty()) {
            _ui.value = cur.copy(saveError = "Fix the highlighted values first.")
            return@launch
        }
        val values = if (d.isFull) cur.values else cur.manual
            .filter { it.label.isNotBlank() && it.value.isNotBlank() }
            .map { SettingValue(it.path.trim(), it.label.trim(), it.value.trim()) }
        val changed = PresetResolver.changedRefs(cur.baseline, values)
        val userRefs = cur.priorUserRefs + cur.editedRefs.filter { it in changed }
        c.profiles.update(key) {
            ProfileService.addRevision(it, key, d.emulatorId, cur.basePresetId, values, note.ifBlank { "Tweaked" }, System.currentTimeMillis(), userRefs).first
        }
        _ui.value = cur.copy(saved = true, saveError = null)
    }
}
