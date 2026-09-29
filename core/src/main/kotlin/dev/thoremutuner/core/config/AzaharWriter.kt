package dev.thoremutuner.core.config

import dev.thoremutuner.core.preset.EmulatorDef
import dev.thoremutuner.core.preset.PresetResolver
import dev.thoremutuner.core.preset.SettingType
import dev.thoremutuner.core.profile.SettingValue
import kotlinx.serialization.Serializable

/** Original value of a key the app touched in Azahar's config.ini (null = the key was absent). */
@Serializable
data class OriginalValue(val section: String, val key: String, val value: String?)

/** `azahar_state.json`: last applied (gameKey, rev) and the original value of every touched key. */
@Serializable
data class AzaharState(
    val schemaVersion: Int = 1,
    val lastAppliedGameKey: String? = null,
    val lastAppliedRev: Int? = null,
    val originals: List<OriginalValue> = emptyList(),
) {
    fun isApplied(gameKey: String, rev: Int): Boolean = lastAppliedGameKey == gameKey && lastAppliedRev == rev
}

data class AzaharApplyResult(val render: RenderResult, val state: AzaharState)

/**
 * Azahar has no per-game settings on Android, so the app swaps a game's managed keys into the global
 * `config/config.ini` right before launch (PLAN section 7.6). Booleans are always `true`/`false`
 * (Azahar's Kotlin parser uses `toBoolean()`, so `1` would read as false).
 */
object AzaharWriter : ConfigWriter {
    const val PATH = "config/config.ini"

    /** Plain merge without state (used for previews). */
    override fun render(existingText: String?, values: List<SettingValue>, ctx: WriteContext): RenderResult {
        val (ok, refused) = ConfigWriters.partition(ctx.def, values)
        val doc = IniDocument.parse(existingText)
        val written = ok.map { v -> v.copy(value = boolSafe(ctx.def, v)) }
        written.forEach { doc.set(it.section, it.key, it.value, ConfigWriters.separator(ctx.def)) }
        return RenderResult(doc.serialize(), PATH, written, refused)
    }

    /**
     * Applies one game's revision: captures originals on first touch, restores keys that an earlier
     * game changed but this revision does not manage, then merges this revision's values.
     */
    fun apply(
        existingText: String,
        values: List<SettingValue>,
        def: EmulatorDef,
        state: AzaharState,
        gameKey: String,
        rev: Int,
    ): AzaharApplyResult {
        val (ok, refused) = ConfigWriters.partition(def, values)
        val sep = ConfigWriters.separator(def)
        val doc = IniDocument.parse(existingText)
        val originals = state.originals.toMutableList()
        val known = originals.map { it.section to it.key }.toMutableSet()
        for (v in ok) {
            if ((v.section to v.key) !in known) {
                originals += OriginalValue(v.section, v.key, doc.get(v.section, v.key))
                known += v.section to v.key
            }
        }
        val managedNow = ok.map { it.section to it.key }.toSet()
        for (o in originals) {
            if ((o.section to o.key) !in managedNow) restoreOne(doc, o, sep)
        }
        val written = ok.map { it.copy(value = boolSafe(def, it)) }
        written.forEach { doc.set(it.section, it.key, it.value, sep) }
        val newState = state.copy(lastAppliedGameKey = gameKey, lastAppliedRev = rev, originals = originals)
        return AzaharApplyResult(RenderResult(doc.serialize(), PATH, written, refused), newState)
    }

    /** "Restore my Azahar settings": writes every captured original back and forgets the state. */
    fun restore(existingText: String, def: EmulatorDef, state: AzaharState): AzaharApplyResult {
        val doc = IniDocument.parse(existingText)
        val sep = ConfigWriters.separator(def)
        state.originals.forEach { restoreOne(doc, it, sep) }
        val restored = state.originals.mapNotNull { o -> o.value?.let { SettingValue(o.section, o.key, it) } }
        return AzaharApplyResult(RenderResult(doc.serialize(), PATH, restored), AzaharState())
    }

    private fun restoreOne(doc: IniDocument, o: OriginalValue, sep: String) {
        if (o.value == null) doc.remove(o.section, o.key) else doc.set(o.section, o.key, o.value, sep)
    }

    /** Booleans as true/false only, never 1/0. */
    private fun boolSafe(def: EmulatorDef, v: SettingValue): String {
        val setting = def.setting(v.section, v.key) ?: return v.value
        if (setting.type != SettingType.BOOL) return v.value
        return when (PresetResolver.parseBool(v.value)) {
            true -> "true"
            false -> "false"
            null -> throw WriterException("${v.key} must be true or false")
        }
    }
}
