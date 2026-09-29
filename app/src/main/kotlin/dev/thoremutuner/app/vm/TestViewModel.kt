package dev.thoremutuner.app.vm

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.thoremutuner.app.AppContainer
import dev.thoremutuner.app.apply.ApplyOutcome
import dev.thoremutuner.app.bench.BatterySnapshot
import dev.thoremutuner.app.bench.LiveSession
import dev.thoremutuner.app.emu.InstalledTarget
import dev.thoremutuner.app.emu.LaunchResult
import dev.thoremutuner.core.bench.IssueLevel
import dev.thoremutuner.core.bench.Outcome
import dev.thoremutuner.core.bench.SessionResult
import dev.thoremutuner.core.bench.TargetFps
import dev.thoremutuner.core.bench.TestSession
import dev.thoremutuner.core.model.Game
import dev.thoremutuner.core.preset.EmulatorDef
import dev.thoremutuner.core.profile.ProfileRevision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

data class PreflightUi(
    val loaded: Boolean = false,
    val def: EmulatorDef? = null,
    val game: Game? = null,
    val revision: ProfileRevision? = null,
    val target: InstalledTarget? = null,
    val battery: BatterySnapshot? = null,
    val displays: Int = 1,
    val needsApply: Boolean = false,
    val perfMode: String = "",
    val fanMode: String = "",
    val durationMin: Int = TestSession.DEFAULT_DURATION_MIN,
    val busy: Boolean = false,
    val message: String? = null,
    val started: Boolean = false,
    /** Another session is running or waiting for results: it must be finished or discarded first. */
    val liveSession: Boolean = false,
) {
    val charging: Boolean get() = (battery?.plugged ?: 0) != 0
    val lowBattery: Boolean get() = (battery?.levelPct ?: 100) < 20
    val externalDisplay: Boolean get() = displays > 2
    val canStart: Boolean get() = loaded && !charging && revision != null && target != null && !busy && !liveSession
}

class TestViewModel(private val c: AppContainer, private val key: String, emulatorId: String) : ViewModel() {
    private val def = c.presets.emulator(emulatorId)
    private val _ui = MutableStateFlow(PreflightUi(def = def))
    val ui: StateFlow<PreflightUi> = _ui.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            c.sessions.live.collect { l ->
                // While this screen is starting its own session (busy) or has started it, it is not "another" session.
                _ui.value = _ui.value.copy(liveSession = l != null && !_ui.value.busy && !_ui.value.started)
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val d = def ?: return@launch
            val next = withContext(Dispatchers.IO) {
                val settings = c.settings.get()
                val game = c.library.get().games.firstOrNull { it.key == key }
                val rev = c.profiles.get(key).firstOrNull { it.emulatorId == d.emulatorId }?.latest
                _ui.value.copy(
                    loaded = true,
                    game = game,
                    revision = rev,
                    target = c.installed.resolve(d, settings.preferredPackage[d.emulatorId]),
                    battery = runCatching { c.battery.snapshot() }.getOrNull(),
                    displays = c.battery.displayCount(),
                    needsApply = game != null && c.applier.needsApply(game, d, rev),
                    perfMode = _ui.value.perfMode.ifEmpty { settings.lastPerfMode },
                    fanMode = _ui.value.fanMode.ifEmpty { settings.lastFanMode },
                )
            }
            _ui.value = next
        }
    }

    fun setPerfMode(v: String) { _ui.value = _ui.value.copy(perfMode = v.take(40)) }
    fun setFanMode(v: String) { _ui.value = _ui.value.copy(fanMode = v.take(40)) }
    fun setDuration(min: Int) { _ui.value = _ui.value.copy(durationMin = min) }

    fun applyNow() {
        viewModelScope.launch {
            val d = def ?: return@launch
            val game = _ui.value.game ?: return@launch
            _ui.value = _ui.value.copy(busy = true)
            val msg = when (val r = c.applier.apply(game, d, _ui.value.revision)) {
                is ApplyOutcome.Written -> "Applied."
                is ApplyOutcome.Blocked -> r.message
                is ApplyOutcome.Failed -> r.message
                is ApplyOutcome.Exported -> null
            }
            _ui.value = _ui.value.copy(busy = false, message = msg)
            refresh()
        }
    }

    /** Starts the foreground sampling service first, then launches the emulator. */
    fun start(context: Context) {
        viewModelScope.launch {
            val d = def ?: return@launch
            val pre = _ui.value
            val game = pre.game ?: return@launch
            val rev = pre.revision ?: return@launch
            if (c.sessions.live.value != null) {
                _ui.value = pre.copy(liveSession = true)
                return@launch
            }
            val snap = withContext(Dispatchers.IO) { runCatching { c.battery.snapshot() }.getOrNull() }
            if ((snap?.plugged ?: 0) != 0) {
                _ui.value = pre.copy(battery = snap, message = "Unplug the charger: power readings are meaningless while charging.")
                return@launch
            }
            val preferred = c.settings.get().preferredPackage[d.emulatorId]
            val target = withContext(Dispatchers.IO) { c.installed.resolve(d, preferred) }
                ?: run { _ui.value = pre.copy(message = "${d.name} is not installed."); return@launch }
            _ui.value = pre.copy(busy = true, message = null)
            c.settings.update { it.copy(lastPerfMode = pre.perfMode.trim(), lastFanMode = pre.fanMode.trim()) }
            val session = TestSession(
                id = UUID.randomUUID().toString(),
                gameKey = key,
                emulatorId = d.emulatorId,
                packageName = target.target.packageName,
                emulatorVersion = target.versionName,
                rev = rev.rev,
                presetName = rev.basePresetId?.let { d.preset(it)?.name },
                startedAt = System.currentTimeMillis(),
                plannedDurationSec = pre.durationMin * 60,
                perfMode = pre.perfMode.trim(),
                fanMode = pre.fanMode.trim(),
                startBatteryPct = snap?.levelPct,
                externalDisplay = pre.externalDisplay,
                chargeCounterStartUah = snap?.chargeCounterUah,
            )
            if (!c.sessions.start(session)) {
                _ui.value = _ui.value.copy(busy = false, liveSession = true)
                return@launch
            }
            val result = c.launcher.launch(context, game, d, rev, c.settings.get())
            val msg = when (result) {
                is LaunchResult.Started -> result.warnings.joinToString("\n").ifEmpty { null }
                is LaunchResult.Failed -> "Launch failed: ${result.message} The test is running; end it from the next screen."
            }
            _ui.value = _ui.value.copy(busy = false, started = true, message = msg)
        }
    }
}

/** Result form fields as typed by the user. `targetFps` 0 means "other" ([customTarget]). */
@Serializable
data class ResultForm(
    val avgFps: String = "",
    val targetFps: Double = 60.0,
    val customTarget: String = "",
    val minFps: String = "",
    val stutter: Int = 1,
    val audio: IssueLevel = IssueLevel.NONE,
    val graphics: IssueLevel = IssueLevel.NONE,
    val outcome: Outcome = Outcome.PASS,
    val notes: String = "",
) {
    fun toResult(): SessionResult = SessionResult(
        avgFps = avgFps.trim().replace(',', '.').toDoubleOrNull(),
        targetFps = if (targetFps > 0.0) targetFps else customTarget.trim().replace(',', '.').toDoubleOrNull() ?: 0.0,
        minFps = minFps.trim().replace(',', '.').takeIf { it.isNotEmpty() }?.toDoubleOrNull() ?: if (minFps.isBlank()) null else Double.NaN,
        stutter = stutter,
        audio = audio,
        graphics = graphics,
        outcome = outcome,
        notes = notes,
    )
}

/**
 * Result form. Input survives navigating away and back (draft kept by the SessionController for
 * this session id) and process death / configuration changes ([SavedStateHandle]).
 */
class ResultViewModel(private val c: AppContainer, private val handle: SavedStateHandle) : ViewModel() {
    val live: StateFlow<LiveSession?> = c.sessions.live
    private val _form = MutableStateFlow(ResultForm())

    init {
        val sessionId = c.sessions.live.value?.session?.id
        val saved = handle.get<String>(KEY_FORM) ?: c.sessions.draft?.takeIf { it.first == sessionId }?.second
        val restored = saved?.let { runCatching { Json.decodeFromString(ResultForm.serializer(), it) }.getOrNull() }
        if (restored != null) {
            _form.value = restored
        } else {
            // Default target FPS from the game's system (PLAN section 9.3).
            viewModelScope.launch {
                val key = c.sessions.live.value?.session?.gameKey ?: return@launch
                val system = c.library.get().games.firstOrNull { it.key == key }?.system ?: return@launch
                if (_form.value == ResultForm()) _form.value = _form.value.copy(targetFps = TargetFps.defaultFor(system))
            }
        }
    }

    private fun persist() {
        val json = Json.encodeToString(ResultForm.serializer(), _form.value)
        handle[KEY_FORM] = json
        c.sessions.live.value?.session?.id?.let { c.sessions.draft = it to json }
    }

    val form: StateFlow<ResultForm> = _form.asStateFlow()
    private val _errors = MutableStateFlow<Map<String, String>>(emptyMap())
    val errors: StateFlow<Map<String, String>> = _errors.asStateFlow()
    private val _saved = MutableStateFlow<TestSession?>(null)
    val saved: StateFlow<TestSession?> = _saved.asStateFlow()

    fun update(f: (ResultForm) -> ResultForm) {
        _form.value = f(_form.value)
        persist()
        if (_errors.value.isNotEmpty()) _errors.value = _form.value.toResult().validate()
    }

    fun end() = c.sessions.end()

    fun save() {
        viewModelScope.launch {
            val result = _form.value.toResult()
            val errs = result.validate()
            _errors.value = errs
            if (errs.isNotEmpty()) return@launch
            if (c.sessions.live.value?.running == true) c.sessions.end()
            _saved.value = c.sessions.saveResult(result)
        }
    }

    fun discard() = c.sessions.discard()

    companion object {
        private const val KEY_FORM = "resultForm"
    }
}
