package dev.thoremutuner.core.config

import dev.thoremutuner.core.profile.SettingValue

/**
 * PPSSPP per-game INI `PSP/SYSTEM/<DISCID>_ppsspp.ini` (PLAN section 7.5). Only catalog keys (all
 * PER_GAME) are written; `GraphicsBackend` is a global setting and is never written.
 */
object PpssppWriter : ConfigWriter {
    const val FORBIDDEN_KEY = "GraphicsBackend"

    override fun render(existingText: String?, values: List<SettingValue>, ctx: WriteContext): RenderResult {
        val path = ConfigWriters.relativePath(ctx.def, ctx)
        val (candidates, refusedCatalog) = ConfigWriters.partition(ctx.def, values.filterNot { it.key == FORBIDDEN_KEY })
        val refused = refusedCatalog + values.filter { it.key == FORBIDDEN_KEY }
            .map { Refusal(it, "GraphicsBackend is not a per-game setting in PPSSPP; set it once in PPSSPP > Settings > Graphics") }
        val doc = if (existingText.isNullOrEmpty()) {
            IniDocument.parse("# Game config for ${ctx.gameId} - written by Thor Emu Tuner\n")
        } else {
            IniDocument.parse(existingText)
        }
        for (v in candidates) doc.set(v.section, v.key, v.value, ConfigWriters.separator(ctx.def))
        return RenderResult(doc.serialize(), path, candidates, refused)
    }
}
