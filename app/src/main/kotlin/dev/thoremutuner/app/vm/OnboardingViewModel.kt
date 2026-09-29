package dev.thoremutuner.app.vm

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.thoremutuner.app.AppContainer
import dev.thoremutuner.app.scan.ScanProgress
import dev.thoremutuner.core.preset.EmulatorDef
import dev.thoremutuner.core.store.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class OnboardingStep { WELCOME, ROMS, SCAN, CONFIG }

class OnboardingViewModel(private val c: AppContainer) : ViewModel() {
    private val folders = FolderActions(c)
    val settings: StateFlow<AppSettings?> = c.settings.flow
    val scan: StateFlow<ScanProgress> = c.scanner.progress
    private val _emulators = MutableStateFlow<List<EmulatorDef>>(emptyList())

    /** Emulators with a config folder to grant; loaded off the main thread. */
    val emulators: StateFlow<List<EmulatorDef>> = _emulators.asStateFlow()

    private val _step = MutableStateFlow(OnboardingStep.WELCOME)
    val step: StateFlow<OnboardingStep> = _step.asStateFlow()

    private val _statuses = MutableStateFlow<Map<String, FolderStatus>>(emptyMap())
    val statuses: StateFlow<Map<String, FolderStatus>> = _statuses.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        viewModelScope.launch {
            c.settings.get()
            _emulators.value = withContext(Dispatchers.IO) { folders.folderEmulators }
        }
    }

    fun goTo(step: OnboardingStep) {
        _step.value = step
        if (step == OnboardingStep.SCAN) c.scanner.start(force = false)
        if (step == OnboardingStep.CONFIG) refreshStatuses()
    }

    fun addRomFolder(uri: Uri) {
        viewModelScope.launch { _error.value = folders.addRomFolder(uri) }
    }
    fun removeRomFolder(treeUri: String) {
        viewModelScope.launch { folders.removeRomFolder(treeUri) }
    }
    fun cancelScan() = c.scanner.cancel()
    fun rescan() = c.scanner.start(force = false)

    fun grant(def: EmulatorDef, uri: Uri) {
        viewModelScope.launch {
            _statuses.value = _statuses.value + (def.emulatorId to folders.grantEmulatorFolder(def, uri))
        }
    }

    private fun refreshStatuses() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { folders.folderEmulators }
            _statuses.value = list.associate { it.emulatorId to folders.status(it) }
        }
    }

    fun finish(onDone: () -> Unit) {
        viewModelScope.launch {
            c.settings.update { it.copy(onboardingDone = true) }
            onDone()
        }
    }
}
