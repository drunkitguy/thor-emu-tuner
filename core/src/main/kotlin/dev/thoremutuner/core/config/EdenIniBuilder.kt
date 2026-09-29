package dev.thoremutuner.core.config

import dev.thoremutuner.core.preset.EmulatorDef
import dev.thoremutuner.core.profile.SettingValue

/**
 * Builds the complete per-game INI that Eden receives in the `custom_settings` extra of its
 * LAUNCH_WITH_CUSTOM_CONFIG intent (PLAN section 7.4). For every value:
 * ```
 * <key>\use_global=false
 * <key>\default=<true only if value == catalog default>
 * <key>=<value>
 * ```
 * Sections in the order Core, Cpu, Renderer, System. Optional `[GpuDriver] driver_path=<file>`
 * without use_global/default lines.
 */
object EdenIniBuilder : ConfigWriter {
    private val SECTION_ORDER = listOf("Core", "Cpu", "Renderer", "System")

    fun build(values: List<SettingValue>, def: EmulatorDef, driverFileName: String? = null): String {
        val bySection = values.groupBy { it.section }
        val sections = SECTION_ORDER.filter { it in bySection } + bySection.keys.filter { it !in SECTION_ORDER }
        val sb = StringBuilder()
        for (section in sections) {
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append('[').append(section).append("]\n")
            for (v in bySection.getValue(section)) {
                val default = def.setting(v.section, v.key)?.defaultValue
                val isDefault = default != null && default == v.value
                sb.append(v.key).append("\\use_global=false\n")
                sb.append(v.key).append("\\default=").append(if (isDefault) "true" else "false").append('\n')
                sb.append(v.key).append('=').append(v.value).append('\n')
            }
        }
        if (!driverFileName.isNullOrBlank()) {
            require('\n' !in driverFileName && '/' !in driverFileName) { "driver must be a plain file name" }
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append("[GpuDriver]\n").append("driver_path=").append(driverFileName).append('\n')
        }
        return sb.toString()
    }

    override fun render(existingText: String?, values: List<SettingValue>, ctx: WriteContext): RenderResult {
        val (ok, refused) = ConfigWriters.partition(ctx.def, values)
        val path = ConfigWriters.relativePath(ctx.def, ctx)
        return RenderResult(build(ok, ctx.def, ctx.gpuDriverFileName), path, ok, refused)
    }
}
