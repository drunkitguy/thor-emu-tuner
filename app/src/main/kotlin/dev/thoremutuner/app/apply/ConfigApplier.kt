package dev.thoremutuner.app.apply

import dev.thoremutuner.app.data.BackupStore
import dev.thoremutuner.app.emu.InstalledEmulators
import dev.thoremutuner.app.saf.PermissionLostException
import dev.thoremutuner.app.saf.SafAccess
import dev.thoremutuner.core.config.ApplyPlanner
import dev.thoremutuner.core.config.AzaharWriter
import dev.thoremutuner.core.config.ConfigWriters
import dev.thoremutuner.core.config.DiffLine
import dev.thoremutuner.core.config.EdenIniBuilder
import dev.thoremutuner.core.config.RenderResult
import dev.thoremutuner.core.config.WriterException
import dev.thoremutuner.core.model.Game
import dev.thoremutuner.core.preset.ConfigMode
import dev.thoremutuner.core.preset.EmulatorDef
import dev.thoremutuner.core.profile.ProfileRevision
import dev.thoremutuner.core.profile.ProfileService
import dev.thoremutuner.core.store.AppSettings
import dev.thoremutuner.core.store.AzaharStateRepository
import dev.thoremutuner.core.store.FolderGrant
import dev.thoremutuner.core.store.ProfileRepository
import dev.thoremutuner.core.store.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Why applying is not possible right now (the UI offers the matching fix). */
enum class ApplyBlock {
    REFERENCE_ONLY, INLINE_AT_LAUNCH, NO_FOLDER, PERMISSION_LOST, WRONG_FOLDER, NO_GAME_ID, NO_CORE, NO_REVISION,
}

sealed class ApplyOutcome {
    data class Written(val relativePath: String, val render: RenderResult, val backupStamp: String?) : ApplyOutcome()
    data class Exported(val relativePath: String, val instructions: String) : ApplyOutcome()
    data class Blocked(val block: ApplyBlock, val message: String) : ApplyOutcome()
    data class Failed(val message: String) : ApplyOutcome()
}

/** What the Apply screen shows before writing. */
data class ApplyPreview(
    val relativePath: String?,
    val render: RenderResult?,
    val diff: List<DiffLine>,
    val block: ApplyOutcome.Blocked?,
    val folderLabel: String?,
)

/**
 * The write pipeline (PLAN section 7.2): validate the granted tree, read and back up the current
 * file, render with the pure :core writer, write with "wt", read back and compare (restoring the
 * backup on mismatch), then stamp the revision as applied. Reference-only emulators are never
 * written (their writer is null).
 */
class ConfigApplier(
    private val saf: SafAccess,
    private val backups: BackupStore,
    private val settingsRepo: SettingsRepository,
    private val profiles: ProfileRepository,
    private val azahar: AzaharStateRepository,
    private val installed: InstalledEmulators,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** Checks the grant for [def]; null = usable. */
    fun folderProblem(def: EmulatorDef, grant: FolderGrant?): ApplyOutcome.Blocked? {
        if (grant == null) return ApplyOutcome.Blocked(ApplyBlock.NO_FOLDER, "Grant the ${def.name} folder first: ${def.configTarget.folderToGrant ?: ""}")
        if (!saf.hasPermission(grant.treeUri, write = true)) {
            return ApplyOutcome.Blocked(ApplyBlock.PERMISSION_LOST, "Access to the ${def.name} folder was lost (for example after an update). Grant it again.")
        }
        val ok = try {
            def.configTarget.folderValidation.all { saf.exists(grant, it) }
        } catch (e: PermissionLostException) {
            return ApplyOutcome.Blocked(ApplyBlock.PERMISSION_LOST, "Access to the ${def.name} folder was lost. Grant it again.")
        } catch (e: Exception) {
            false
        }
        if (!ok) {
            return ApplyOutcome.Blocked(
                ApplyBlock.WRONG_FOLDER,
                "This does not look like the right folder (missing ${def.configTarget.folderValidation.joinToString()}). " +
                    (def.configTarget.folderToGrant ?: ""),
            )
        }
        return null
    }

    private fun basicBlock(def: EmulatorDef, game: Game, revision: ProfileRevision?): ApplyOutcome.Blocked? {
        if (ConfigWriters.forEmulator(def) == null) {
            return ApplyOutcome.Blocked(ApplyBlock.REFERENCE_ONLY, "${def.name} is reference-only: follow the checklist inside the emulator.")
        }
        if (revision == null) return ApplyOutcome.Blocked(ApplyBlock.NO_REVISION, "Choose a baseline or save a revision first.")
        if (def.configTarget.mode == ConfigMode.INTENT_INLINE_INI) {
            return ApplyOutcome.Blocked(ApplyBlock.INLINE_AT_LAUNCH, "${def.name} settings are sent with the launch intent when you press Launch.")
        }
        val tpl = def.configTarget.pathTemplate.orEmpty()
        if ("{gameId}" in tpl && ApplyPlanner.gameIdFor(def, game) == null) {
            return ApplyOutcome.Blocked(ApplyBlock.NO_GAME_ID, "This game has no ${game.system.displayName} ID. Set it on the game screen first.")
        }
        return null
    }

    suspend fun preview(game: Game, def: EmulatorDef, revision: ProfileRevision?, settings: AppSettings): ApplyPreview =
        withContext(Dispatchers.IO) {
            basicBlock(def, game, revision)?.let { b ->
                // Eden: show the INI that will travel with the launch intent.
                if (b.block == ApplyBlock.INLINE_AT_LAUNCH && revision != null) {
                    val ctx = ApplyPlanner.context(def, game, revision)
                    val text = EdenIniBuilder.build(revision.values, def, ctx.gpuDriverFileName)
                    val render = RenderResult(text, "config/custom/${ctx.gameId ?: "<title id>"}.ini", revision.values)
                    return@withContext ApplyPreview(render.relativePath, render, emptyList(), b, null)
                }
                return@withContext ApplyPreview(null, null, emptyList(), b, null)
            }
            val rev = revision!!
            val ctx = ApplyPlanner.context(def, game, rev, settings.preferredCore[game.system.id])
            if (def.configTarget.mode == ConfigMode.RETROARCH_OVERRIDE && ctx.core == null) {
                return@withContext ApplyPreview(null, null, emptyList(), ApplyOutcome.Blocked(ApplyBlock.NO_CORE, "No RetroArch core is known for ${game.system.displayName}."), null)
            }
            val path = try {
                ConfigWriters.relativePath(def, ctx)
            } catch (e: WriterException) {
                return@withContext ApplyPreview(null, null, emptyList(), ApplyOutcome.Blocked(ApplyBlock.NO_GAME_ID, e.message ?: "Missing ID"), null)
            }
            val grant = settings.emulatorFolders[def.emulatorId]
            val problem = folderProblem(def, grant)
            val existing = if (problem == null) runCatching { saf.readTextOrNull(grant!!, path) }.getOrNull() else null
            val render = render(def, game, rev, existing, ctx)
            ApplyPreview(path, render, ApplyPlanner.diff(def, existing, render.written), problem, grant?.label)
        }

    private suspend fun render(
        def: EmulatorDef,
        game: Game,
        rev: ProfileRevision,
        existing: String?,
        ctx: dev.thoremutuner.core.config.WriteContext,
    ): RenderResult =
        if (def.configTarget.mode == ConfigMode.GLOBAL_INI_MERGE) {
            AzaharWriter.apply(existing.orEmpty(), rev.values, def, azahar.get(), game.key, rev.rev).render
        } else {
            ConfigWriters.forEmulator(def)!!.render(existing, rev.values, ctx)
        }

    /** Runs the full pipeline. */
    suspend fun apply(game: Game, def: EmulatorDef, revision: ProfileRevision?): ApplyOutcome = withContext(Dispatchers.IO) {
        basicBlock(def, game, revision)?.let { return@withContext it }
        val rev = revision!!
        val settings = settingsRepo.get()
        val grant = settings.emulatorFolders[def.emulatorId]
        folderProblem(def, grant)?.let { return@withContext it }
        grant!!
        val ctx = ApplyPlanner.context(def, game, rev, settings.preferredCore[game.system.id])
        try {
            val path = ConfigWriters.relativePath(def, ctx)
            // 1-2. Read and back up.
            val existing = saf.readTextOrNull(grant, path)
            val now = clock()
            val stamp = existing?.let { backups.save(def.emulatorId, path, it, now) }
            // 3. Render (pure).
            val newAzaharState = if (def.configTarget.mode == ConfigMode.GLOBAL_INI_MERGE) {
                if (existing == null) return@withContext ApplyOutcome.Failed("Azahar's config/config.ini was not found in the granted folder.")
                AzaharWriter.apply(existing, rev.values, def, azahar.get(), game.key, rev.rev)
            } else null
            val render = newAzaharState?.render ?: ConfigWriters.forEmulator(def)!!.render(existing, rev.values, ctx)
            // 4. Write ("wt").
            val uri = saf.writeText(grant, path, render.text)
            // 5. Verify by read-back; restore on mismatch.
            val readBack = runCatching { saf.readText(uri) }.getOrNull()
            if (readBack != render.text) {
                if (existing != null) runCatching { saf.writeText(grant, path, existing) } else saf.delete(uri)
                return@withContext ApplyOutcome.Failed("The file did not read back correctly; the previous version was restored.")
            }
            // 6. Record.
            newAzaharState?.let { azahar.save(it.state) }
            val version = installed.resolve(def, settings.preferredPackage[def.emulatorId])?.versionName
            profiles.update(game.key) { ProfileService.markApplied(it, def.emulatorId, rev.rev, now, version, path) }
            ApplyOutcome.Written(path, render, stamp)
        } catch (e: PermissionLostException) {
            ApplyOutcome.Blocked(ApplyBlock.PERMISSION_LOST, e.message ?: "Folder access was lost")
        } catch (e: WriterException) {
            ApplyOutcome.Failed(e.message ?: "Cannot write this configuration")
        } catch (e: SecurityException) {
            ApplyOutcome.Blocked(ApplyBlock.PERMISSION_LOST, "Folder access was lost. Grant the ${def.name} folder again.")
        } catch (e: Exception) {
            ApplyOutcome.Failed("Could not write the file (${e::class.simpleName}).")
        }
    }

    /** Fallback: write `<emulatorId>/<relativePath>` into a user-chosen shared folder. */
    suspend fun export(game: Game, def: EmulatorDef, revision: ProfileRevision?, exportGrant: FolderGrant): ApplyOutcome =
        withContext(Dispatchers.IO) {
            if (ConfigWriters.forEmulator(def) == null) {
                return@withContext ApplyOutcome.Blocked(ApplyBlock.REFERENCE_ONLY, "Reference-only emulators are never written.")
            }
            val rev = revision ?: return@withContext ApplyOutcome.Blocked(ApplyBlock.NO_REVISION, "Save a revision first.")
            if (!saf.hasPermission(exportGrant.treeUri, write = true)) {
                return@withContext ApplyOutcome.Blocked(ApplyBlock.PERMISSION_LOST, "Access to the export folder was lost. Pick it again.")
            }
            val settings = settingsRepo.get()
            val ctx = ApplyPlanner.context(def, game, rev, settings.preferredCore[game.system.id])
            try {
                val (path, text, instructions) = when (def.configTarget.mode) {
                    ConfigMode.GLOBAL_INI_MERGE -> Triple(
                        "${def.emulatorId}/thor-emu-tuner-${game.key}-keys.ini",
                        AzaharWriter.render(null, rev.values, ctx).text,
                        "Copy these keys into Azahar's config/config.ini (same sections), with Azahar closed.",
                    )
                    ConfigMode.INTENT_INLINE_INI -> Triple(
                        "${def.emulatorId}/${ConfigWriters.relativePath(def, ctx)}",
                        EdenIniBuilder.build(rev.values, def),
                        "Eden normally receives this file with the launch intent; copying it by hand is optional.",
                    )
                    else -> {
                        val rel = ConfigWriters.relativePath(def, ctx)
                        Triple(
                            "${def.emulatorId}/$rel",
                            ConfigWriters.forEmulator(def)!!.render(null, rev.values, ctx).text,
                            "Copy this file to <${def.name} folder>/$rel. It replaces an existing file there, so merge by hand if you have one.",
                        )
                    }
                }
                val uri = saf.writeText(exportGrant, path, text)
                if (runCatching { saf.readText(uri) }.getOrNull() != text) return@withContext ApplyOutcome.Failed("Export did not read back correctly.")
                ApplyOutcome.Exported(path, instructions)
            } catch (e: WriterException) {
                ApplyOutcome.Failed(e.message ?: "Cannot export")
            } catch (e: Exception) {
                ApplyOutcome.Failed("Could not export (${e::class.simpleName}).")
            }
        }

    /** "Restore my Azahar settings": writes the captured originals back into config.ini. */
    suspend fun restoreAzahar(def: EmulatorDef): ApplyOutcome = withContext(Dispatchers.IO) {
        val settings = settingsRepo.get()
        val grant = settings.emulatorFolders[def.emulatorId]
        folderProblem(def, grant)?.let { return@withContext it }
        grant!!
        try {
            val existing = saf.readTextOrNull(grant, AzaharWriter.PATH)
                ?: return@withContext ApplyOutcome.Failed("config/config.ini not found")
            val state = azahar.get()
            if (state.originals.isEmpty()) return@withContext ApplyOutcome.Failed("Nothing to restore: Thor Emu Tuner has not changed Azahar's settings.")
            val stamp = backups.save(def.emulatorId, AzaharWriter.PATH, existing, clock())
            val result = AzaharWriter.restore(existing, def, state)
            val uri = saf.writeText(grant, AzaharWriter.PATH, result.render.text)
            if (runCatching { saf.readText(uri) }.getOrNull() != result.render.text) {
                saf.writeText(grant, AzaharWriter.PATH, existing)
                return@withContext ApplyOutcome.Failed("Restore did not read back correctly; nothing was changed.")
            }
            azahar.save(result.state)
            ApplyOutcome.Written(AzaharWriter.PATH, result.render, stamp)
        } catch (e: Exception) {
            ApplyOutcome.Failed("Could not restore (${e::class.simpleName}).")
        }
    }

    /**
     * Whether launching should apply first (FULL, folder-based emulators only): never applied, or
     * applied to a different target path (e.g. a changed RetroArch core or a corrected game ID).
     */
    suspend fun needsApply(game: Game, def: EmulatorDef, revision: ProfileRevision?): Boolean = withContext(Dispatchers.IO) {
        if (revision == null || ConfigWriters.forEmulator(def) == null) return@withContext false
        when (def.configTarget.mode) {
            ConfigMode.INTENT_INLINE_INI, ConfigMode.MANUAL -> false
            ConfigMode.GLOBAL_INI_MERGE -> !azahar.get().isApplied(game.key, revision.rev)
            else -> {
                if (revision.appliedAt == null) return@withContext true
                val settings = settingsRepo.get()
                val current = runCatching {
                    ConfigWriters.relativePath(def, ApplyPlanner.context(def, game, revision, settings.preferredCore[game.system.id]))
                }.getOrNull()
                revision.appliedPath != null && current != null && current != revision.appliedPath
            }
        }
    }

    /**
     * A 3DS game without a profile must not run with another game's swapped-in Azahar keys:
     * true when such keys are currently active.
     */
    suspend fun azaharHasForeignKeys(game: Game, def: EmulatorDef, revision: ProfileRevision?): Boolean {
        if (def.configTarget.mode != ConfigMode.GLOBAL_INI_MERGE || revision != null) return false
        val state = azahar.get()
        return state.originals.isNotEmpty() && state.lastAppliedGameKey != null && state.lastAppliedGameKey != game.key
    }
}
