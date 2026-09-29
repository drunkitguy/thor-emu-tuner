package dev.thoremutuner.core.config

import dev.thoremutuner.core.profile.SettingValue

/**
 * Dolphin per-game INI `GameSettings/<ID6>.ini` (PLAN section 7.3): merge, legacy section names,
 * ` = `, True/False. `Video_Hacks` keys from generic presets are refused so Dolphin's built-in
 * per-game fixes (Sys/GameSettings) are never overridden by accident.
 */
object DolphinWriter : ConfigWriter {
    const val HACKS_SECTION = "Video_Hacks"
    const val HACK_REFUSAL = "Generic presets never write Video_Hacks: Dolphin's own game INIs set required hacks " +
        "(e.g. Super Mario Sunshine needs EFBToTextureEnable = False). Set it by hand under Advanced if you really need it."

    override fun render(existingText: String?, values: List<SettingValue>, ctx: WriteContext): RenderResult {
        val path = ConfigWriters.relativePath(ctx.def, ctx)
        val (ok, refused) = ConfigWriters.partition(ctx.def, values)
        val allRefused = refused.toMutableList()
        val doc = IniDocument.parse(existingText)
        val written = mutableListOf<SettingValue>()
        for (v in ok) {
            if (v.section == HACKS_SECTION && v.ref !in ctx.manualRefs) {
                allRefused += Refusal(v, HACK_REFUSAL)
                continue
            }
            doc.set(v.section, v.key, v.value, ConfigWriters.separator(ctx.def))
            written += v
        }
        return RenderResult(doc.serialize(), path, written, allRefused)
    }
}
