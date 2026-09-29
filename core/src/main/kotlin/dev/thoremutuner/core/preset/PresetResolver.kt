package dev.thoremutuner.core.preset

import dev.thoremutuner.core.profile.SettingValue

/** Result of validating one user-entered value against the catalog. */
sealed class ValueCheck {
    /** [normalized] is the literal string to write. [warning] is shown but does not block saving. */
    data class Ok(val normalized: String, val warning: String? = null) : ValueCheck()
    data class Invalid(val message: String) : ValueCheck()
}

/** A user value that overrides a known per-game fix. */
data class GameFixConflict(val fix: GameSpecificDef, val value: GameSpecificValue, val userValue: String)

/**
 * Preset resolution (PLAN section 4): start from the preset's values, then apply user edits.
 * Every value is written explicitly even when it equals the emulator default (Eden computes
 * `\default` itself).
 */
object PresetResolver {

    /** Values of [presetId] (empty if unknown), with user [edits] applied on top. */
    fun resolve(def: EmulatorDef, presetId: String?, edits: List<SettingValue> = emptyList()): List<SettingValue> {
        val base = baseline(def, presetId)
        return applyEdits(def, base, edits)
    }

    /** The preset values as setting values. Reference emulators get their manual checklist form. */
    fun baseline(def: EmulatorDef, presetId: String?): List<SettingValue> {
        val preset = presetId?.let { def.preset(it) } ?: return emptyList()
        return preset.values.map { v ->
            if (def.isFull) {
                SettingValue(v.section, v.key, normalize(def, v.section, v.key, v.value))
            } else {
                manualEntry(v)
            }
        }
    }

    /** Manual checklist entry for a reference emulator: UI path, setting label, value to pick. */
    fun manualEntry(v: PresetValue): SettingValue =
        SettingValue(section = v.uiPath ?: v.section, key = v.key, value = v.uiValue ?: v.value)

    fun applyEdits(def: EmulatorDef, base: List<SettingValue>, edits: List<SettingValue>): List<SettingValue> {
        val result = base.toMutableList()
        for (e in edits) {
            val value = if (def.isFull) normalize(def, e.section, e.key, e.value) else e.value
            val idx = result.indexOfFirst { it.section == e.section && it.key == e.key }
            if (idx >= 0) result[idx] = SettingValue(e.section, e.key, value)
            else result += SettingValue(e.section, e.key, value)
        }
        return result
    }

    /** Removes one (section, key) from a value list (per-row "reset to emulator setting"). */
    fun remove(values: List<SettingValue>, section: String, key: String): List<SettingValue> =
        values.filterNot { it.section == section && it.key == key }

    /** Refs whose value differs between [baseline] and [current] (added, removed or changed). */
    fun changedRefs(baseline: List<SettingValue>, current: List<SettingValue>): Set<String> {
        val b = baseline.associate { it.ref to it.value }
        val c = current.associate { it.ref to it.value }
        return (b.keys + c.keys).filterTo(mutableSetOf()) { b[it] != c[it] }
    }

    /** Converts boolean spellings to the emulator's style for bool settings; other values unchanged. */
    fun normalize(def: EmulatorDef, section: String, key: String, value: String): String {
        val setting = def.setting(section, key) ?: return value
        if (setting.type != SettingType.BOOL) return value
        val style = def.configTarget.iniStyle ?: return value
        return when (parseBool(value)) {
            true -> style.boolTrue
            false -> style.boolFalse
            null -> value
        }
    }

    fun parseBool(value: String): Boolean? = when (value.trim().lowercase()) {
        "true", "1", "yes", "on" -> true
        "false", "0", "no", "off" -> false
        else -> null
    }

    /** Validates [raw] for [setting] and returns the literal value to write. */
    fun validate(def: EmulatorDef, setting: SettingDef, raw: String): ValueCheck {
        val value = raw.trim()
        return when (setting.type) {
            SettingType.ENUM ->
                if (setting.options.any { it.value == value }) ValueCheck.Ok(value)
                else ValueCheck.Invalid("Pick one of: ${setting.options.joinToString { it.label }}")

            SettingType.BOOL -> when (parseBool(value)) {
                null -> ValueCheck.Invalid("Must be on or off")
                else -> ValueCheck.Ok(normalize(def, setting.section, setting.key, value))
            }

            SettingType.INT -> {
                val n = value.toLongOrNull() ?: return ValueCheck.Invalid("Must be a whole number")
                rangeCheck(setting, n.toDouble()) ?: ValueCheck.Ok(n.toString())
            }

            SettingType.FLOAT -> {
                val d = value.toDoubleOrNull()
                if (d == null || !d.isFinite()) return ValueCheck.Invalid("Must be a number")
                rangeCheck(setting, d)?.let { return it }
                if (setting.min == null && setting.max == null) {
                    // No verified range in the source (e.g. Dolphin's clock multiplier): accept only
                    // positive values and warn outside a conservative 0.5-2.0 band.
                    if (d <= 0.0) return ValueCheck.Invalid("Must be greater than 0")
                    if (d < 0.5 || d > 2.0) {
                        return ValueCheck.Ok(formatFloat(d), "Outside 0.5-2.0: unverified range, games may break")
                    }
                }
                ValueCheck.Ok(formatFloat(d))
            }

            SettingType.STRING -> when {
                value.any { it == '\n' || it == '\r' } -> ValueCheck.Invalid("Must be a single line")
                value.length > 256 -> ValueCheck.Invalid("Too long (max 256 characters)")
                else -> ValueCheck.Ok(value)
            }
        }
    }

    private fun rangeCheck(setting: SettingDef, d: Double): ValueCheck? {
        setting.min?.let { if (d < it) return ValueCheck.Invalid("Minimum is ${formatNumber(it)}") }
        setting.max?.let { if (d > it) return ValueCheck.Invalid("Maximum is ${formatNumber(it)}") }
        return null
    }

    fun formatFloat(d: Double): String {
        val s = d.toString()
        return if (s.contains('E') || s.contains('e')) java.math.BigDecimal(d).stripTrailingZeros().toPlainString() else s
    }

    fun formatNumber(d: Double): String = if (d == Math.floor(d)) d.toLong().toString() else d.toString()

    /** Per-game fixes (display only in v1) that match [gameId]. */
    fun gameFixes(def: EmulatorDef, gameId: String?): List<GameSpecificDef> =
        def.gameSpecific.filter { it.matches(gameId) }

    /** User values that would override a matching per-game fix with a different value. */
    fun conflicts(def: EmulatorDef, gameId: String?, values: List<SettingValue>): List<GameFixConflict> =
        gameFixes(def, gameId).flatMap { fix ->
            fix.values.mapNotNull { fv ->
                val user = values.firstOrNull { it.section == fv.section && it.key == fv.key } ?: return@mapNotNull null
                if (user.value.equals(fv.value, ignoreCase = true)) null else GameFixConflict(fix, fv, user.value)
            }
        }

    /**
     * Evidence rule: a preset can never claim stronger evidence than its weakest value. Any
     * `inferred` value makes the whole preset `inferred`.
     */
    fun effectiveEvidence(preset: PresetDef): Evidence =
        if (preset.values.any { it.isInferred }) Evidence.INFERRED else preset.evidence
}
