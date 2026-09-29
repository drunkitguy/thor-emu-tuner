package dev.thoremutuner.core.config

import dev.thoremutuner.core.profile.SettingValue

/**
 * RetroArch game override `config/<libraryName>/<romBaseName>.cfg` (PLAN section 7.7). The whole
 * file belongs to the app (it is backed up before being overwritten); one `key = "value"` per line.
 */
object RetroArchOverrideWriter : ConfigWriter {
    override fun render(existingText: String?, values: List<SettingValue>, ctx: WriteContext): RenderResult {
        val path = ConfigWriters.relativePath(ctx.def, ctx)
        val (ok, refusedCatalog) = ConfigWriters.partition(ctx.def, values)
        val refused = refusedCatalog.toMutableList()
        val written = mutableListOf<SettingValue>()
        val sb = StringBuilder()
        sb.append("# Game override for ").append(ctx.romBaseName).append(" (").append(ctx.core?.libraryName)
            .append(") - written by Thor Emu Tuner\n")
        for (v in ok) {
            if (v.section.isNotEmpty()) {
                refused += Refusal(v, "RetroArch overrides have no sections"); continue
            }
            if ('"' in v.value) {
                refused += Refusal(v, "RetroArch values cannot contain quotes"); continue
            }
            sb.append(v.key).append(" = \"").append(v.value).append("\"\n")
            written += v
        }
        val warnings = if (existingText != null && !existingText.contains("written by Thor Emu Tuner")) {
            listOf("An override not made by Thor Emu Tuner exists and will be replaced (a backup is kept).")
        } else emptyList()
        return RenderResult(sb.toString(), path, written, refused, warnings)
    }
}
