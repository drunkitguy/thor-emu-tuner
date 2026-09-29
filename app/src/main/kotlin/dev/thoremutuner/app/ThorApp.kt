package dev.thoremutuner.app

import android.app.Application
import android.content.Context
import dev.thoremutuner.app.apply.ConfigApplier
import dev.thoremutuner.app.bench.BatterySampler
import dev.thoremutuner.app.bench.SessionController
import dev.thoremutuner.app.bench.ThermalSampler
import dev.thoremutuner.app.data.BackupStore
import dev.thoremutuner.app.data.FileJsonStore
import dev.thoremutuner.app.emu.EmulatorLauncher
import dev.thoremutuner.app.emu.InstalledEmulators
import dev.thoremutuner.app.saf.SafAccess
import dev.thoremutuner.app.scan.LibraryScanner
import dev.thoremutuner.core.preset.PresetRepository
import dev.thoremutuner.core.store.AzaharStateRepository
import dev.thoremutuner.core.store.LibraryRepository
import dev.thoremutuner.core.store.ProfileRepository
import dev.thoremutuner.core.store.SessionRepository
import dev.thoremutuner.core.store.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class ThorApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** Hand-written dependency container (no DI framework, PLAN section 3.1). */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val tunerRoot = File(appContext.filesDir, "tuner")
    val store = FileJsonStore(tunerRoot)
    val presets = PresetRepository()

    val settings = SettingsRepository(store)
    val library = LibraryRepository(store)
    val profiles = ProfileRepository(store)
    val sessionRepo = SessionRepository(store)
    val azaharState = AzaharStateRepository(store)

    val saf = SafAccess(appContext.contentResolver)
    val backups = BackupStore(tunerRoot)
    val installed = InstalledEmulators(appContext.packageManager)
    val applier = ConfigApplier(saf, backups, settings, profiles, azaharState, installed)
    val launcher = EmulatorLauncher(saf, installed, applier)
    val scanner = LibraryScanner(saf, library, settings, appScope)

    val battery = BatterySampler(appContext)
    val thermal = ThermalSampler(appContext)
    val sessions = SessionController(appContext, store, sessionRepo, battery, appScope)

    init {
        // Parse the bundled preset JSON off the main thread before the first screen needs it.
        appScope.launch(Dispatchers.IO) { runCatching { presets.emulators } }
    }

    /** This app's own versionName (for exports). */
    val appVersion: String by lazy {
        @Suppress("DEPRECATION")
        runCatching { appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName }.getOrNull() ?: "unknown"
    }
}

val Context.container: AppContainer get() = (applicationContext as ThorApp).container
