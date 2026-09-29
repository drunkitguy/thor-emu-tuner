package dev.thoremutuner.app.vm

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.thoremutuner.app.AppContainer
import dev.thoremutuner.app.apply.ApplyOutcome
import dev.thoremutuner.core.preset.EmulatorDef
import dev.thoremutuner.core.store.AppSettings
import dev.thoremutuner.core.store.Exporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A titled block of the About screen. */
data class AboutSection(val title: String, val text: String)

/** Installed packages of an emulator with more than one candidate (for the preferred-package picker). */
data class PackageChoice(val def: EmulatorDef, val installed: List<String>)

class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    private val folders = FolderActions(c)
    val settings: StateFlow<AppSettings?> = c.settings.flow
    val folderEmulators: List<EmulatorDef> = folders.folderEmulators

    private val _statuses = MutableStateFlow<Map<String, FolderStatus>>(emptyMap())
    val statuses: StateFlow<Map<String, FolderStatus>> = _statuses.asStateFlow()
    private val _romAccess = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val romAccess: StateFlow<Map<String, Boolean>> = _romAccess.asStateFlow()
    private val _packages = MutableStateFlow<List<PackageChoice>>(emptyList())
    val packages: StateFlow<List<PackageChoice>> = _packages.asStateFlow()
    private val _message = MutableStateFlow<Pair<String, Boolean>?>(null)
    val message: StateFlow<Pair<String, Boolean>?> = _message.asStateFlow()

    val retroarch: EmulatorDef? = c.presets.emulator("retroarch")
    val azahar: EmulatorDef? = c.presets.emulator("azahar")

    fun refresh() {
        viewModelScope.launch {
            c.settings.get()
            _statuses.value = folderEmulators.associate { it.emulatorId to folders.status(it) }
            _romAccess.value = folders.romFolderAccess()
            _packages.value = withContext(Dispatchers.IO) {
                c.presets.emulators.map { PackageChoice(it, c.installed.installedPackages(it)) }.filter { it.installed.size > 1 }
            }
        }
    }

    /** Adds a ROM folder and scans (cached files are not re-probed). */
    fun addRomFolder(uri: Uri) {
        viewModelScope.launch {
            val error = folders.addRomFolder(uri)
            if (error != null) {
                _message.value = error to true
            } else {
                c.scanner.start(force = false)
                _message.value = "Folder added; scanning in the background." to false
            }
            refresh()
        }
    }

    fun removeRomFolder(treeUri: String) {
        viewModelScope.launch { folders.removeRomFolder(treeUri); refresh() }
    }

    fun grant(def: EmulatorDef, uri: Uri) {
        viewModelScope.launch {
            _statuses.value = _statuses.value + (def.emulatorId to folders.grantEmulatorFolder(def, uri))
        }
    }

    fun rescan(force: Boolean) {
        c.scanner.start(force)
        _message.value = "Scanning in the background; the library updates when it finishes." to false
    }

    fun setPreferredPackage(emulatorId: String, pkg: String) {
        viewModelScope.launch {
            c.settings.update { it.copy(preferredPackage = it.preferredPackage + (emulatorId to pkg)) }
        }
    }

    fun setPreferredCore(systemId: String, coreFile: String) {
        viewModelScope.launch {
            c.settings.update { it.copy(preferredCore = it.preferredCore + (systemId to coreFile)) }
        }
    }

    fun restoreAzahar() {
        viewModelScope.launch {
            val def = azahar ?: return@launch
            _message.value = when (val r = c.applier.restoreAzahar(def)) {
                is ApplyOutcome.Written -> "Azahar settings restored (backup ${r.backupStamp})." to false
                is ApplyOutcome.Blocked -> r.message to true
                is ApplyOutcome.Failed -> r.message to true
                is ApplyOutcome.Exported -> null
            }
        }
    }

    /** Writes the privacy-safe export (no folder URIs, document ids, paths or device ids). */
    fun exportData(uri: Uri) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val games = c.library.get().games
                    val profiles = games.flatMap { c.profiles.get(it.key) }
                    val sessions = c.sessionRepo.all()
                    val json = Exporter.toJson(Exporter.bundle(games, profiles, sessions, c.appVersion, System.currentTimeMillis()))
                    c.saf.writeBytes(uri, json.toByteArray(Charsets.UTF_8))
                }.isSuccess
            }
            _message.value = if (ok) "Exported." to false else "Export failed." to true
        }
    }

    /** Re-runs setup on top of the library; "onboardingDone" stays set, so leaving it returns to the library. */
    fun rerunOnboarding(then: () -> Unit) = then()

    fun dismissMessage() { _message.value = null }

    private val _about = MutableStateFlow<List<AboutSection>?>(null)
    /** About/sources/license texts, read from assets off the main thread. */
    val about: StateFlow<List<AboutSection>?> = _about.asStateFlow()

    fun loadAbout() {
        if (_about.value != null) return
        viewModelScope.launch {
            _about.value = withContext(Dispatchers.IO) {
                listOf(
                    AboutSection("Sources (docs/SOURCES.md)", asset("SOURCES.md")),
                    AboutSection("License: Thor Emu Tuner (MIT)", asset("licenses/MIT-Thor-Emu-Tuner.txt")),
                    AboutSection(
                        "Third-party notices",
                        "This app includes AndroidX (Jetpack Compose, Activity, Lifecycle, Navigation, Core), Kotlin and " +
                            "kotlinx (coroutines, serialization) libraries, copyright The Android Open Source Project, " +
                            "JetBrains s.r.o. and their contributors, licensed under the Apache License, Version 2.0 (full text below).",
                    ),
                    AboutSection("Apache License 2.0", asset("licenses/Apache-2.0.txt")),
                )
            }
        }
    }

    private fun asset(name: String): String = runCatching {
        c.appContext.assets.open(name).use { it.readBytes().toString(Charsets.UTF_8) }
    }.getOrDefault("$name is missing from this build.")
}
