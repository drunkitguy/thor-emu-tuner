package dev.thoremutuner.app.vm

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.thoremutuner.app.AppContainer
import dev.thoremutuner.app.emu.LaunchResult
import dev.thoremutuner.core.config.ApplyPlanner
import dev.thoremutuner.core.config.ConfigWriters
import dev.thoremutuner.core.model.DetectedId
import dev.thoremutuner.core.model.DetectionMethod
import dev.thoremutuner.core.model.Game
import dev.thoremutuner.core.model.IdValidation
import dev.thoremutuner.core.preset.EmulatorDef
import dev.thoremutuner.core.preset.GameSpecificDef
import dev.thoremutuner.core.preset.PresetResolver
import dev.thoremutuner.core.profile.ProfileRevision
import dev.thoremutuner.core.store.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class EmuChoice(val def: EmulatorDef, val installed: Boolean, val versionName: String?)

data class UiMessage(val text: String, val error: Boolean = false, val appInfoPackage: String? = null)

data class GameUi(
    val loading: Boolean = true,
    val game: Game? = null,
    val choices: List<EmuChoice> = emptyList(),
    val selected: EmuChoice? = null,
    val latest: ProfileRevision? = null,
    val basePresetName: String? = null,
    val changesFromBaseline: Int = 0,
    val needsApply: Boolean = false,
    val sessionCount: Int = 0,
    val fixes: List<GameSpecificDef> = emptyList(),
    val coreName: String? = null,
    val cores: List<String> = emptyList(),
    val message: UiMessage? = null,
    val showFolderHint: Boolean = false,
    val launching: Boolean = false,
    val notFound: Boolean = false,
)

/** Pick the emulator for a game: the user's choice, the system default, else the first installed FULL one. */
fun chooseEmulator(settings: AppSettings, game: Game, choices: List<EmuChoice>): EmuChoice? {
    val wanted = settings.gameEmulator[game.key] ?: settings.preferredEmulator[game.system.id]
    return choices.firstOrNull { it.def.emulatorId == wanted }
        ?: choices.firstOrNull { it.installed && it.def.isFull }
        ?: choices.firstOrNull { it.installed }
        ?: choices.firstOrNull()
}

class GameViewModel(private val c: AppContainer, private val key: String) : ViewModel() {
    private val _ui = MutableStateFlow(GameUi())
    val ui: StateFlow<GameUi> = _ui.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            val next = withContext(Dispatchers.IO) { load() }
            _ui.value = next.copy(message = _ui.value.message)
        }
    }

    private suspend fun load(): GameUi {
        val settings = c.settings.get()
        val game = c.library.get().games.firstOrNull { it.key == key } ?: return GameUi(loading = false, notFound = true)
        val choices = c.presets.emulatorsFor(game.system).map { def ->
            val t = c.installed.resolve(def, settings.preferredPackage[def.emulatorId])
            EmuChoice(def, t != null, t?.versionName)
        }
        val sel = chooseEmulator(settings, game, choices)
        val profiles = c.profiles.get(key)
        val latest = sel?.let { s -> profiles.firstOrNull { it.emulatorId == s.def.emulatorId }?.latest }
        val baseline = if (sel != null && latest?.basePresetId != null) PresetResolver.baseline(sel.def, latest.basePresetId) else emptyList()
        val sessions = c.sessionRepo.get(key)
        val retroCores = sel?.def?.let { d -> d.configTarget.cores.filter { core -> core.systems.any { it in game.system.jsonIds } } }.orEmpty()
        val core = sel?.def?.let { ConfigWriters.coreFor(it, game.system.jsonIds, settings.preferredCore[game.system.id]) }
        return GameUi(
            loading = false,
            game = game,
            choices = choices,
            selected = sel,
            latest = latest,
            basePresetName = latest?.basePresetId?.let { id -> sel?.def?.preset(id)?.name },
            changesFromBaseline = if (latest != null) PresetResolver.changedRefs(baseline, latest.values).size else 0,
            needsApply = sel != null && c.applier.needsApply(game, sel.def, latest),
            sessionCount = sessions.size,
            fixes = sel?.def?.let { PresetResolver.gameFixes(it, game.id?.value) }.orEmpty(),
            coreName = core?.libraryName,
            cores = retroCores.map { it.libraryName },
        )
    }

    fun selectEmulator(emulatorId: String) {
        viewModelScope.launch {
            c.settings.update { it.copy(gameEmulator = it.gameEmulator + (key to emulatorId)) }
            refresh()
        }
    }

    fun selectCore(libraryName: String) {
        viewModelScope.launch {
            val game = _ui.value.game ?: return@launch
            val def = _ui.value.selected?.def ?: return@launch
            val core = def.configTarget.cores.firstOrNull { it.libraryName == libraryName } ?: return@launch
            c.settings.update { it.copy(preferredCore = it.preferredCore + (game.system.id to core.coreFile)) }
            refresh()
        }
    }

    /** Validates and stores a manual id. Returns an error message or null. */
    fun idError(input: String): String? {
        val game = _ui.value.game ?: return null
        val kind = IdValidation.kindFor(game.system)
        return if (IdValidation.normalize(kind, input) == null) "Expected ${IdValidation.hint(kind)}" else null
    }

    fun setManualId(input: String) {
        viewModelScope.launch {
            val game = _ui.value.game ?: return@launch
            val kind = IdValidation.kindFor(game.system)
            val value = IdValidation.normalize(kind, input) ?: return@launch
            c.library.updateGame(key) { it.copy(id = DetectedId(value, kind, DetectionMethod.MANUAL)) }
            refresh()
        }
    }

    fun dismissMessage() { _ui.value = _ui.value.copy(message = null) }

    /** Closing the one-time tip without choosing "Launch" must not launch anything. */
    fun dismissHint() { _ui.value = _ui.value.copy(showFolderHint = false) }

    /** First launch per emulator shows the folder-access hint (PLAN section 8.6). */
    fun requestLaunch(context: Context) {
        viewModelScope.launch {
            val sel = _ui.value.selected ?: return@launch
            val hint = "folderHint:${sel.def.emulatorId}"
            if (hint !in c.settings.get().hintsShown) {
                _ui.value = _ui.value.copy(showFolderHint = true)
                return@launch
            }
            launch(context)
        }
    }

    fun confirmHintAndLaunch(context: Context) {
        viewModelScope.launch {
            val sel = _ui.value.selected ?: return@launch
            c.settings.update { it.copy(hintsShown = it.hintsShown + "folderHint:${sel.def.emulatorId}") }
            _ui.value = _ui.value.copy(showFolderHint = false)
            launch(context)
        }
    }

    private suspend fun launch(context: Context) {
        val ui = _ui.value
        val game = ui.game ?: return
        val sel = ui.selected ?: return
        _ui.value = ui.copy(launching = true, message = null)
        val result = c.launcher.launch(context, game, sel.def, ui.latest, c.settings.get())
        val msg = when (result) {
            is LaunchResult.Started -> if (result.warnings.isEmpty()) null else UiMessage(result.warnings.joinToString("\n"))
            is LaunchResult.Failed -> UiMessage(result.message, error = true, appInfoPackage = result.packageName)
        }
        _ui.value = _ui.value.copy(launching = false, message = msg)
        refresh()
    }

    /** Game id in the emulator's format, for display. */
    fun emulatorGameId(): String? {
        val ui = _ui.value
        return ui.selected?.def?.let { d -> ui.game?.let { g -> ApplyPlanner.gameIdFor(d, g) } }
    }
}
