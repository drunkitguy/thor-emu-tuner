package dev.thoremutuner.core.profile

import kotlinx.serialization.Serializable

/** One (section, key) = value line. Values are the literal strings written to the emulator config. */
@Serializable
data class SettingValue(val section: String, val key: String, val value: String) {
    val ref: String get() = refOf(section, key)

    companion object {
        fun refOf(section: String, key: String): String = "$section/$key"
    }
}

/**
 * Immutable profile revision. [values] is the full resolved list handed to the writer.
 * For reference-only emulators the values are a manual checklist (section = UI path, key = setting
 * label, value = what to pick) and nothing is ever written to the emulator.
 */
@Serializable
data class ProfileRevision(
    val rev: Int,
    val emulatorId: String,
    val basePresetId: String?,
    val values: List<SettingValue>,
    val note: String,
    val createdAt: Long,
    val appliedAt: Long? = null,
    val appliedEmulatorVersion: String? = null,
    /** Target path the revision was written to (a changed core or game ID means it must be re-applied). */
    val appliedPath: String? = null,
    /** Refs ("section/key") the user edited by hand in this revision's lineage (enables Dolphin hacks). */
    val userEditedRefs: List<String> = emptyList(),
)

@Serializable
data class GameProfile(
    val gameKey: String,
    val emulatorId: String,
    val revisions: List<ProfileRevision> = emptyList(),
) {
    val latest: ProfileRevision? get() = revisions.maxByOrNull { it.rev }
    fun revision(rev: Int): ProfileRevision? = revisions.firstOrNull { it.rev == rev }
}
