package dev.thoremutuner.app.emu

import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import dev.thoremutuner.app.apply.ApplyOutcome
import dev.thoremutuner.app.apply.ConfigApplier
import dev.thoremutuner.app.saf.SafAccess
import dev.thoremutuner.core.config.ApplyPlanner
import dev.thoremutuner.core.config.ConfigWriters
import dev.thoremutuner.core.config.EdenIniBuilder
import dev.thoremutuner.core.launch.LaunchInputs
import dev.thoremutuner.core.launch.LaunchPlan
import dev.thoremutuner.core.launch.LaunchPlanner
import dev.thoremutuner.core.model.Game
import dev.thoremutuner.core.preset.ConfigMode
import dev.thoremutuner.core.preset.EmulatorDef
import dev.thoremutuner.core.profile.ProfileRevision
import dev.thoremutuner.core.store.AppSettings

sealed class LaunchResult {
    data class Started(val packageName: String, val versionName: String?, val warnings: List<String>) : LaunchResult()
    data class Failed(val message: String, val packageName: String? = null) : LaunchResult()
}

/**
 * Launches a game (PLAN section 8): applies the active revision first when needed (FULL emulators),
 * resolves the installed (package, activity) target, builds the intent from the preset template and
 * tries the planned attempts in order. ActivityNotFound/SecurityException never crash the app.
 */
class EmulatorLauncher(
    private val saf: SafAccess,
    private val installed: InstalledEmulators,
    private val applier: ConfigApplier,
) {
    suspend fun launch(
        context: Context,
        game: Game,
        def: EmulatorDef,
        revision: ProfileRevision?,
        settings: AppSettings,
        autoApply: Boolean = true,
    ): LaunchResult {
        // Everything before startActivity (PackageManager queries, SAF checks, file reads/writes)
        // runs off the main thread; only the activity start itself happens on the caller's thread.
        val prepared = withContext(Dispatchers.IO) { prepare(game, def, revision, settings, autoApply) }
        val (plan, target, warnings) = when (prepared) {
            is Prepared.Fail -> return prepared.result
            is Prepared.Ok -> Triple(prepared.plan, prepared.target, prepared.warnings)
        }
        val romUri = saf.documentUri(game.treeUri, game.documentId)
        val inline = def.configTarget.mode == ConfigMode.INTENT_INLINE_INI
        for (attempt in plan.attempts) {
            val intent = IntentFactory.build(attempt.spec)
            try {
                runCatching { context.grantUriPermission(attempt.spec.packageName, romUri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                context.startActivity(intent)
                if (!attempt.appliesSettings && inline && revision != null) {
                    warnings += "${def.name} was started without the tuned settings."
                }
                return LaunchResult.Started(target.target.packageName, target.versionName, warnings)
            } catch (e: ActivityNotFoundException) {
                continue
            } catch (e: SecurityException) {
                continue
            }
        }
        return LaunchResult.Failed("Emulator not installed or its launch activity changed.", target.target.packageName)
    }

    private sealed class Prepared {
        data class Ok(val plan: LaunchPlan.Ready, val target: InstalledTarget, val warnings: MutableList<String>) : Prepared()
        data class Fail(val result: LaunchResult) : Prepared()
    }

    private suspend fun prepare(
        game: Game,
        def: EmulatorDef,
        revision: ProfileRevision?,
        settings: AppSettings,
        autoApply: Boolean,
    ): Prepared {
        if (!saf.hasPermission(game.treeUri, write = false)) {
            return Prepared.Fail(LaunchResult.Failed("Access to this game's ROM folder was lost. Add the folder again in Settings."))
        }
        val target = installed.resolve(def, settings.preferredPackage[def.emulatorId])
            ?: return Prepared.Fail(LaunchResult.Failed("${def.name} is not installed (or its launch activity changed)."))
        val warnings = mutableListOf<String>()

        if (autoApply && applier.needsApply(game, def, revision)) {
            when (val r = applier.apply(game, def, revision)) {
                is ApplyOutcome.Written -> Unit
                is ApplyOutcome.Blocked -> warnings += "Settings not applied: ${r.message}"
                is ApplyOutcome.Failed -> warnings += "Settings not applied: ${r.message}"
                is ApplyOutcome.Exported -> Unit
            }
        }
        if (autoApply && applier.azaharHasForeignKeys(game, def, revision)) {
            // No profile for this 3DS game: put the user's own Azahar settings back first.
            when (val r = applier.restoreAzahar(def)) {
                is ApplyOutcome.Written -> warnings += "Restored your own Azahar settings (this game has no profile)."
                is ApplyOutcome.Blocked -> warnings += "Another game's Azahar settings are still active: ${r.message}"
                is ApplyOutcome.Failed -> warnings += "Another game's Azahar settings are still active: ${r.message}"
                is ApplyOutcome.Exported -> Unit
            }
        }

        val romUri = saf.documentUri(game.treeUri, game.documentId)
        val inline = def.configTarget.mode == ConfigMode.INTENT_INLINE_INI
        val customIni = if (inline && revision != null) {
            val ok = revision.values.filter { def.setting(it.section, it.key) != null }
            EdenIniBuilder.build(ok, def)
        } else null
        val core = if (def.configTarget.mode == ConfigMode.RETROARCH_OVERRIDE) {
            ConfigWriters.coreFor(def, game.system.jsonIds, settings.preferredCore[game.system.id])
        } else null
        val inputs = LaunchInputs(
            romUri = romUri.toString(),
            documentId = game.documentId,
            titleId = ApplyPlanner.gameIdFor(def, game),
            customSettingsIni = customIni,
            coreFile = core?.coreFile,
        )
        val plan = when (val p = LaunchPlanner.plan(def, target.target, inputs)) {
            is LaunchPlan.Failed -> return Prepared.Fail(LaunchResult.Failed(p.error.message, target.target.packageName))
            is LaunchPlan.Ready -> p
        }
        warnings += plan.warnings
        return Prepared.Ok(plan, target, warnings)
    }

    companion object {
        /** Opens the emulator's page in system Settings (for "launch activity changed" errors). */
        fun openAppInfo(context: Context, packageName: String) {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
        }
    }
}
