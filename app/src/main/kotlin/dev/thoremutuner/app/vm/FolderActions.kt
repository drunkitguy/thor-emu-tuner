package dev.thoremutuner.app.vm

import android.net.Uri
import dev.thoremutuner.app.AppContainer
import dev.thoremutuner.core.config.ConfigWriters
import dev.thoremutuner.core.preset.EmulatorDef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Grant state of an emulator's config folder, as shown on the onboarding and settings cards. */
data class FolderStatus(val granted: Boolean, val ok: Boolean, val message: String, val label: String? = null)

/** Folder grant operations shared by onboarding and settings. */
class FolderActions(private val c: AppContainer) {

    /** Emulators whose config lives in a folder the user must grant (Eden needs none). */
    val folderEmulators: List<EmulatorDef> get() = c.presets.emulators.filter { ConfigWriters.needsFolder(it) }

    suspend fun addRomFolder(uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            c.saf.takePersistable(uri, write = false)
            val grant = c.saf.grantFor(uri)
            c.settings.update { s -> s.copy(romFolders = (s.romFolders.filterNot { it.treeUri == grant.treeUri } + grant)) }
            null
        } catch (e: SecurityException) {
            "Android did not allow access to that folder. Pick a folder inside internal storage or the SD card (not the storage root, Download or Android/data)."
        } catch (e: Exception) {
            "Could not add the folder (${e::class.simpleName})."
        }
    }

    suspend fun removeRomFolder(treeUri: String) = withContext(Dispatchers.IO) {
        c.settings.update { s -> s.copy(romFolders = s.romFolders.filterNot { it.treeUri == treeUri }) }
        c.saf.release(treeUri)
        c.scanner.dropFolder(treeUri)
    }

    suspend fun grantEmulatorFolder(def: EmulatorDef, uri: Uri): FolderStatus = withContext(Dispatchers.IO) {
        try {
            c.saf.takePersistable(uri, write = true)
            val grant = c.saf.grantFor(uri)
            val old = c.settings.get().emulatorFolders[def.emulatorId]
            c.settings.update { s -> s.copy(emulatorFolders = s.emulatorFolders + (def.emulatorId to grant)) }
            if (old != null && old.treeUri != grant.treeUri && c.settings.get().romFolders.none { it.treeUri == old.treeUri }) {
                c.saf.release(old.treeUri)
            }
            status(def)
        } catch (e: SecurityException) {
            FolderStatus(false, false, "Android did not allow write access to that folder.")
        } catch (e: Exception) {
            FolderStatus(false, false, "Could not use that folder (${e::class.simpleName}).")
        }
    }

    suspend fun status(def: EmulatorDef): FolderStatus = withContext(Dispatchers.IO) {
        val grant = c.settings.get().emulatorFolders[def.emulatorId]
            ?: return@withContext FolderStatus(false, false, "Not granted")
        val problem = c.applier.folderProblem(def, grant)
        if (problem == null) FolderStatus(true, true, "Ready", grant.label) else FolderStatus(true, false, problem.message, grant.label)
    }

    suspend fun romFolderAccess(): Map<String, Boolean> = withContext(Dispatchers.IO) {
        c.settings.get().romFolders.associate { it.treeUri to c.saf.hasPermission(it.treeUri, write = false) }
    }
}
