package dev.thoremutuner.app.emu

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
        if (!saf.hasPermission(game.treeUri, write = false)) {
            return LaunchResult.Failed("Access to this game's ROM folder was lost. Add the folder again in Settings.")
        }
        val target = installed.resolve(def, settings.preferredPackage[def.emulatorId])
            ?: return LaunchResult.Failed("${def.name} is not installed (or its launch activity changed).")
        val warnings = mutableListOf<String>()

        if (autoApply && applier.needsApply(game, def, revision)) {
            when (val r = applier.apply(game, def, revision)) {
                is ApplyOutcome.Written -> Unit
                is ApplyOutcome.Blocked -> warnings += "Settings not applied: ${r.message}"
                is ApplyOutcome.Failed -> warnings += "Settings not applied: ${r.message}"
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
            is LaunchPlan.Failed -> return LaunchResult.Failed(p.error.message, target.target.packageName)
            is LaunchPlan.Ready -> p
        }
        warnings += plan.warnings
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

    companion object {
        /** Opens the emulator's page in system Settings (for "launch activity changed" errors). */
        fun openAppInfo(context: Context, packageName: String) {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
        }
    }
}
