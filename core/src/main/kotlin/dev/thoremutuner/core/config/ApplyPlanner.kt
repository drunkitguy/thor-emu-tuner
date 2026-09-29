package dev.thoremutuner.core.config

import dev.thoremutuner.core.model.Game
import dev.thoremutuner.core.model.IdKind
import dev.thoremutuner.core.preset.ConfigMode
import dev.thoremutuner.core.preset.EmulatorDef
import dev.thoremutuner.core.profile.ProfileRevision
import dev.thoremutuner.core.profile.SettingValue

/** One managed key in the Apply preview: old -> new. */
data class DiffLine(val section: String, val key: String, val old: String?, val new: String) {
    val changed: Boolean get() = old != new
}

/** Glue between a game, a profile revision and the writers (pure; the app does the I/O). */
object ApplyPlanner {

    /** The id the emulator's config path uses for this game, or null if the game has none. */
    fun gameIdFor(def: EmulatorDef, game: Game): String? {
        val id = game.id ?: return null
        return when (def.configTarget.gameIdKind) {
            "gcWiiGameId" -> id.value.takeIf { id.kind == IdKind.GC_WII_ID6 }
            "pspDiscId" -> id.value.replace("-", "").takeIf { id.kind == IdKind.PSP_DISC_ID }
            "switchTitleId" -> id.value.uppercase().takeIf { id.kind == IdKind.SWITCH_TITLE_ID }
            "n3dsTitleId" -> id.value.takeIf { id.kind == IdKind.N3DS_TITLE_ID }
            else -> id.value
        }
    }

    fun romBaseName(game: Game): String = game.fileName.substringBeforeLast('.', game.fileName)

    fun context(
        def: EmulatorDef,
        game: Game,
        revision: ProfileRevision?,
        preferredCoreFile: String? = null,
        gpuDriverFileName: String? = null,
    ): WriteContext = WriteContext(
        def = def,
        gameId = gameIdFor(def, game),
        romBaseName = romBaseName(game),
        core = if (def.configTarget.mode == ConfigMode.RETROARCH_OVERRIDE) {
            ConfigWriters.coreFor(def, game.system.jsonIds, preferredCoreFile)
        } else null,
        manualRefs = revision?.userEditedRefs?.toSet().orEmpty(),
        gpuDriverFileName = gpuDriverFileName,
    )

    /** Old -> new values for the keys a revision manages, read from the existing file text. */
    fun diff(def: EmulatorDef, existingText: String?, values: List<SettingValue>): List<DiffLine> {
        val doc = IniDocument.parse(existingText)
        val quoted = def.configTarget.iniStyle?.quoteValues == true
        return values.map { v ->
            val old = doc.get(v.section, v.key)?.let { if (quoted) it.removeSurrounding("\"") else it }
            DiffLine(v.section, v.key, old, v.value)
        }
    }
}
