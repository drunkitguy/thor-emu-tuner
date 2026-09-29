package dev.thoremutuner.core.store

import dev.thoremutuner.core.bench.TestSession
import dev.thoremutuner.core.model.Game
import dev.thoremutuner.core.model.SystemId
import dev.thoremutuner.core.profile.GameProfile
import kotlinx.serialization.Serializable

/** A game as exported: no tree URIs, document ids, file names or paths (PLAN section 10). */
@Serializable
data class ExportGame(
    val key: String,
    val title: String,
    val system: SystemId,
    val gameId: String? = null,
    val idKind: String? = null,
)

@Serializable
data class ExportBundle(
    val schemaVersion: Int = CURRENT_SCHEMA,
    val app: String = "Thor Emu Tuner",
    val appVersion: String,
    val exportedAt: Long,
    val games: List<ExportGame>,
    val profiles: List<GameProfile>,
    val sessions: List<TestSession>,
)

object Exporter {
    fun bundle(
        games: List<Game>,
        profiles: List<GameProfile>,
        sessions: List<TestSession>,
        appVersion: String,
        now: Long,
    ): ExportBundle = ExportBundle(
        appVersion = appVersion,
        exportedAt = now,
        games = games.map { ExportGame(it.key, it.title, it.system, it.id?.value, it.id?.kind?.name) },
        profiles = profiles,
        sessions = sessions,
    )

    fun toJson(bundle: ExportBundle): String = ThorJson.store.encodeToString(ExportBundle.serializer(), bundle)
}
