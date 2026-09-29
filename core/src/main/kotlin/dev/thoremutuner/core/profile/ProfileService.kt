package dev.thoremutuner.core.profile

/** Pure operations on profiles. Revisions are immutable; saving always creates rev = max + 1. */
object ProfileService {

    fun addRevision(
        profiles: List<GameProfile>,
        gameKey: String,
        emulatorId: String,
        basePresetId: String?,
        values: List<SettingValue>,
        note: String,
        now: Long,
        userEditedRefs: Collection<String> = emptyList(),
    ): Pair<List<GameProfile>, ProfileRevision> {
        val existing = profiles.firstOrNull { it.emulatorId == emulatorId }
            ?: GameProfile(gameKey, emulatorId)
        val rev = ProfileRevision(
            rev = (existing.revisions.maxOfOrNull { it.rev } ?: 0) + 1,
            emulatorId = emulatorId,
            basePresetId = basePresetId,
            values = values,
            note = note.trim().take(MAX_NOTE),
            createdAt = now,
            userEditedRefs = userEditedRefs.distinct().sorted(),
        )
        val updated = existing.copy(revisions = existing.revisions + rev)
        return replace(profiles, updated) to rev
    }

    fun markApplied(
        profiles: List<GameProfile>,
        emulatorId: String,
        rev: Int,
        at: Long,
        emulatorVersion: String?,
        appliedPath: String? = null,
    ): List<GameProfile> {
        val p = profiles.firstOrNull { it.emulatorId == emulatorId } ?: return profiles
        val updated = p.copy(revisions = p.revisions.map {
            if (it.rev == rev) it.copy(appliedAt = at, appliedEmulatorVersion = emulatorVersion, appliedPath = appliedPath) else it
        })
        return replace(profiles, updated)
    }

    fun profileFor(profiles: List<GameProfile>, emulatorId: String): GameProfile? =
        profiles.firstOrNull { it.emulatorId == emulatorId }

    private fun replace(profiles: List<GameProfile>, updated: GameProfile): List<GameProfile> {
        val others = profiles.filterNot { it.emulatorId == updated.emulatorId }
        return others + updated
    }

    const val MAX_NOTE = 500
}
