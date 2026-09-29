package dev.thoremutuner.core.launch

import dev.thoremutuner.core.preset.EmulatorDef
import dev.thoremutuner.core.preset.ExtraType
import dev.thoremutuner.core.preset.LaunchDef
import dev.thoremutuner.core.preset.LaunchFlag
import dev.thoremutuner.core.preset.LaunchTarget

/** A fully resolved extra. For [ExtraType.STRING_ARRAY], [values] holds the elements. */
data class IntentExtra(val name: String, val type: ExtraType, val value: String, val values: List<String> = emptyList())

/** Platform-independent description of an explicit launch intent (the app maps it to an Intent). */
data class IntentSpec(
    val packageName: String,
    val className: String,
    val action: String?,
    val categories: List<String>,
    val data: String?,
    val mimeType: String?,
    val extras: List<IntentExtra>,
    val flags: Set<LaunchFlag>,
    /** Set when data is null but an extra carries the ROM URI: goes into ClipData so the grant applies. */
    val clipDataUri: String?,
)

/** Inputs resolved by the app for one game. */
data class LaunchInputs(
    /** DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId) as a string. */
    val romUri: String? = null,
    /** SAF document id of the ROM (for `{romPath}`). */
    val documentId: String? = null,
    val titleId: String? = null,
    val customSettingsIni: String? = null,
    val coreFile: String? = null,
)

/** One way to launch; [appliesSettings] is false for plain fallbacks (e.g. Eden without its config). */
data class LaunchAttempt(val spec: IntentSpec, val appliesSettings: Boolean, val note: String? = null)

sealed class LaunchError(val message: String) {
    object UnsupportedRomPath : LaunchError(
        "This emulator needs a normal storage path; move the ROM folder to internal storage or SD card",
    )
    object MissingRomUri : LaunchError("The ROM file could not be located; rescan the library")
    object MissingTitleId : LaunchError("This game has no title ID; set it on the game screen")
    object MissingCore : LaunchError("No RetroArch core is configured for this system")
    object NoLaunchTarget : LaunchError("No launch target is defined for this emulator")
    data class Unresolved(val placeholder: String) : LaunchError("Launch template needs $placeholder")
}

sealed class LaunchPlan {
    data class Ready(val attempts: List<LaunchAttempt>, val warnings: List<String>) : LaunchPlan() {
        val primary: IntentSpec get() = attempts.first().spec
    }
    data class Failed(val error: LaunchError) : LaunchPlan()
}

/**
 * Turns a preset `launch` template into concrete intents (PLAN section 8). Everything comes from
 * the JSON; the only generic rules here are placeholder substitution, the alternate-launch fallback
 * when a primary template needs data the game lacks, and an action-prefix fallback for forks whose
 * action is derived from their own package name.
 */
object LaunchPlanner {
    private val PLACEHOLDER = Regex("\\{[A-Za-z]+}")

    fun plan(def: EmulatorDef, target: LaunchTarget, inputs: LaunchInputs): LaunchPlan {
        val warnings = mutableListOf<String>()
        val attempts = mutableListOf<LaunchAttempt>()
        val alt = def.alternateLaunch

        val primary = resolve(def.launch, target, inputs)
        when {
            primary is Resolved.Ok -> {
                attempts += LaunchAttempt(primary.spec, appliesSettings = true)
                forkActionFallback(def, target, primary.spec)?.let { attempts += LaunchAttempt(it, true, "fork action name") }
                if (alt != null) {
                    (resolve(alt, target, inputs) as? Resolved.Ok)?.let {
                        attempts += LaunchAttempt(it.spec, appliesSettings = false, "plain launch; settings not applied")
                    }
                }
            }
            alt != null && primary is Resolved.Err &&
                (primary.error == LaunchError.MissingTitleId || primary.error is LaunchError.Unresolved) -> {
                when (val a = resolve(alt, target, inputs)) {
                    is Resolved.Ok -> {
                        attempts += LaunchAttempt(a.spec, appliesSettings = false, "plain launch; settings not applied")
                        warnings += if (primary.error == LaunchError.MissingTitleId) {
                            "This game has no title ID, so ${def.name} starts it without the tuned settings."
                        } else {
                            "No saved profile yet, so ${def.name} starts the game with its own settings."
                        }
                    }
                    is Resolved.Err -> return LaunchPlan.Failed(a.error)
                }
            }
            primary is Resolved.Err -> return LaunchPlan.Failed(primary.error)
        }
        return LaunchPlan.Ready(attempts, warnings)
    }

    private sealed class Resolved {
        data class Ok(val spec: IntentSpec) : Resolved()
        data class Err(val error: LaunchError) : Resolved()
    }

    private class Missing(val error: LaunchError) : Exception()

    private fun resolve(spec: LaunchDef, target: LaunchTarget, inputs: LaunchInputs): Resolved = try {
        fun sub(template: String): String = substitute(template, target, inputs)
        val className = if (spec.activity == EmulatorDef.PLACEHOLDER_PACKAGE_ACTIVITY) target.activity else sub(spec.activity)
        if (className.isBlank()) throw Missing(LaunchError.NoLaunchTarget)
        val extras = spec.extras.map { e ->
            if (e.type == ExtraType.STRING_ARRAY) {
                val parts = e.value.split(',').map { sub(it.trim()) }
                IntentExtra(e.name, e.type, parts.joinToString(","), parts)
            } else {
                IntentExtra(e.name, e.type, sub(e.value))
            }
        }
        val data = spec.data?.let { sub(it) }
        val clip = if (data == null && spec.extras.any { "{romUri}" in it.value }) inputs.romUri else null
        Resolved.Ok(
            IntentSpec(
                packageName = sub(spec.packageName),
                className = className,
                action = spec.action?.let { sub(it) },
                categories = spec.categories,
                data = data,
                mimeType = spec.mimeType,
                extras = extras,
                flags = spec.flags.toSet() + LaunchFlag.NEW_TASK,
                clipDataUri = clip,
            ),
        )
    } catch (m: Missing) {
        Resolved.Err(m.error)
    }

    private fun substitute(template: String, target: LaunchTarget, inputs: LaunchInputs): String {
        var out = template
        for (m in PLACEHOLDER.findAll(template).map { it.value }.distinct()) {
            val value = when (m) {
                "{package}" -> target.packageName
                "{packageActivity}" -> target.activity
                "{romUri}" -> inputs.romUri ?: throw Missing(LaunchError.MissingRomUri)
                "{romPath}" -> inputs.documentId?.let { SafPaths.toFilePath(it) } ?: throw Missing(LaunchError.UnsupportedRomPath)
                "{titleId}" -> inputs.titleId?.takeIf { it.isNotBlank() } ?: throw Missing(LaunchError.MissingTitleId)
                "{customSettingsIni}" -> inputs.customSettingsIni ?: throw Missing(LaunchError.Unresolved(m))
                "{coreFile}" -> inputs.coreFile ?: throw Missing(LaunchError.MissingCore)
                else -> throw Missing(LaunchError.Unresolved(m))
            }
            out = out.replace(m, value)
        }
        return out
    }

    /**
     * If the action is namespaced with another listed package (e.g. `me.magnum.melonds.LAUNCH_ROM`
     * while launching `me.magnum.melondualds`), also try the action under the target's own package.
     */
    private fun forkActionFallback(def: EmulatorDef, target: LaunchTarget, spec: IntentSpec): IntentSpec? {
        val action = spec.action ?: return null
        val owner = def.distinctPackageNames.filter { action.startsWith("$it.") }.maxByOrNull { it.length } ?: return null
        if (owner == target.packageName) return null
        val suffix = action.removePrefix("$owner.")
        if (suffix.contains('.')) return null
        return spec.copy(action = "${target.packageName}.$suffix")
    }
}
