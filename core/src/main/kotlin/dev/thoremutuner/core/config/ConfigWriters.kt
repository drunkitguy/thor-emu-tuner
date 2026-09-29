package dev.thoremutuner.core.config

import dev.thoremutuner.core.preset.ConfigMode
import dev.thoremutuner.core.preset.CoreDef
import dev.thoremutuner.core.preset.EmulatorDef
import dev.thoremutuner.core.preset.PresetResolver
import dev.thoremutuner.core.profile.SettingValue

/** A value a writer refused to write, with a user-facing explanation. */
data class Refusal(val value: SettingValue, val reason: String)

/** Output of a writer: the complete new file text and what was (not) written. Pure data, no I/O. */
data class RenderResult(
    val text: String,
    val relativePath: String,
    val written: List<SettingValue>,
    val refused: List<Refusal> = emptyList(),
    val warnings: List<String> = emptyList(),
)

/** Everything a writer needs besides the existing text and the values. */
data class WriteContext(
    val def: EmulatorDef,
    /** Game id in the form the emulator's path uses (ID6, DISCID, title id). */
    val gameId: String? = null,
    /** ROM file name without its last extension (RetroArch overrides). */
    val romBaseName: String? = null,
    /** RetroArch core chosen for the game's system. */
    val core: CoreDef? = null,
    /** Refs ("section/key") the user set by hand; only these may touch Dolphin Video_Hacks. */
    val manualRefs: Set<String> = emptySet(),
    /** Eden only: custom GPU driver file name picked by the user (written as [GpuDriver] driver_path). */
    val gpuDriverFileName: String? = null,
)

class WriterException(message: String) : IllegalArgumentException(message)

/** A config writer: a pure function of (existing text, values, context). */
fun interface ConfigWriter {
    fun render(existingText: String?, values: List<SettingValue>, ctx: WriteContext): RenderResult
}

/** The five writers (the only emulator-specific Kotlin besides presets, PLAN section 2). */
object ConfigWriters {

    fun forEmulator(def: EmulatorDef): ConfigWriter? = when {
        !def.isFull -> null // reference-only emulators are never written
        def.emulatorId == "dolphin" -> DolphinWriter
        def.emulatorId == "ppsspp" -> PpssppWriter
        def.emulatorId == "retroarch" -> RetroArchOverrideWriter
        def.emulatorId == "eden" -> EdenIniBuilder
        def.emulatorId == "azahar" -> AzaharWriter
        else -> null
    }

    /** Whether applying needs a granted folder (Eden travels inside the launch intent). */
    fun needsFolder(def: EmulatorDef): Boolean =
        def.isFull && def.configTarget.mode != ConfigMode.INTENT_INLINE_INI

    /** Target path relative to the granted tree, from `configTarget.pathTemplate`. */
    fun relativePath(def: EmulatorDef, ctx: WriteContext): String {
        val template = def.configTarget.pathTemplate ?: throw WriterException("${def.name} has no config path")
        var path = template
        if ("{gameId}" in path) {
            val id = ctx.gameId?.takeIf { it.isNotBlank() } ?: throw WriterException("This game has no ID yet; set it on the game screen")
            path = path.replace("{gameId}", safeSegment(id))
        }
        if ("{libraryName}" in path) {
            val lib = ctx.core?.libraryName ?: throw WriterException("No RetroArch core is configured for this system")
            path = path.replace("{libraryName}", safeSegment(lib))
        }
        if ("{romBaseName}" in path) {
            val base = ctx.romBaseName?.takeIf { it.isNotBlank() } ?: throw WriterException("Missing ROM file name")
            path = path.replace("{romBaseName}", safeSegment(base))
        }
        return path
    }

    /** RetroArch core for a system id (first core listing it, or the preferred core file). */
    fun coreFor(def: EmulatorDef, systemJsonIds: List<String>, preferredCoreFile: String? = null): CoreDef? {
        val cores = def.configTarget.cores.filter { c -> c.systems.any { it in systemJsonIds } }
        return cores.firstOrNull { it.coreFile == preferredCoreFile } ?: cores.firstOrNull()
    }

    private fun safeSegment(s: String): String {
        if (s.contains('/') || s.contains('\\') || s == "." || s == ".." || s.any { it.code < 32 }) {
            throw WriterException("Unsafe file name segment")
        }
        return s
    }

    /** Shared checks: catalog membership and single-line values. Returns (accepted, refused). */
    internal fun partition(def: EmulatorDef, values: List<SettingValue>): Pair<List<SettingValue>, List<Refusal>> {
        val ok = mutableListOf<SettingValue>()
        val refused = mutableListOf<Refusal>()
        for (v in values) {
            when {
                def.setting(v.section, v.key) == null ->
                    refused += Refusal(v, "Not in the ${def.name} settings catalog, so it is never written")
                v.value.any { it == '\n' || it == '\r' } ->
                    refused += Refusal(v, "Values must be a single line")
                else -> ok += v.copy(value = PresetResolver.normalize(def, v.section, v.key, v.value))
            }
        }
        return ok to refused
    }

    internal fun separator(def: EmulatorDef): String = def.configTarget.iniStyle?.keyValueSeparator ?: " = "
}
