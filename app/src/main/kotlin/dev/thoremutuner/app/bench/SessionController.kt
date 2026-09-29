package dev.thoremutuner.app.bench

import android.content.Context
import android.content.Intent
import dev.thoremutuner.core.bench.CurrentNormalizer
import dev.thoremutuner.core.bench.MetricsCalculator
import dev.thoremutuner.core.bench.Sample
import dev.thoremutuner.core.bench.SessionResult
import dev.thoremutuner.core.bench.TestSession
import dev.thoremutuner.core.store.JsonStore
import dev.thoremutuner.core.store.SessionRepository
import dev.thoremutuner.core.store.ThorJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Live view of the running (or just-ended) session. */
data class LiveSession(
    val session: TestSession,
    val running: Boolean,
    val lastWatts: Double? = null,
    val lastTempC: Double? = null,
    val lastThermal: Int? = null,
    val elapsedSec: Long = 0,
) {
    val reachedPlannedEnd: Boolean get() = elapsedSec >= session.plannedDurationSec
}

/**
 * Owns the active test session. The foreground service feeds samples in; samples are kept in memory
 * and flushed to `active_session.json` every 30 s so a killed process can be recovered (the result
 * form then opens with whatever was recorded, and metrics fall back to the charge counter).
 */
class SessionController(
    private val context: Context,
    private val store: JsonStore,
    private val sessions: SessionRepository,
    private val battery: BatterySampler,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val state = MutableStateFlow<LiveSession?>(null)
    val live: StateFlow<LiveSession?> = state.asStateFlow()
    private val lock = Any()
    private var lastFlush = 0L

    init {
        recover()
    }

    /** Starts sampling: stores the header and starts the foreground service. */
    fun start(session: TestSession) {
        synchronized(lock) {
            state.value = LiveSession(session, running = true)
            flushLocked(force = true)
        }
        val intent = Intent(context, TestSessionService::class.java).setAction(TestSessionService.ACTION_START)
        context.startForegroundService(intent)
    }

    /** Called by the service every 2 s. Returns false when the session is no longer running. */
    fun addSample(sample: Sample): Boolean = synchronized(lock) {
        val cur = state.value ?: return false
        if (!cur.running) return false
        val samples = cur.session.samples + sample
        val recent = CurrentNormalizer.normalize(samples.takeLast(10)).points.lastOrNull()?.watts
        state.value = cur.copy(
            session = cur.session.copy(samples = samples),
            lastWatts = recent ?: cur.lastWatts,
            lastTempC = sample.tempDeciC?.let { it / 10.0 } ?: cur.lastTempC,
            lastThermal = sample.thermalStatus ?: cur.lastThermal,
            elapsedSec = sample.t / 1000,
        )
        flushLocked(force = false)
        true
    }

    /** Ends sampling (user pressed "End test" or the overrun cap was hit). */
    fun end() {
        synchronized(lock) {
            val cur = state.value ?: return
            if (cur.running) {
                val snap = runCatching { battery.snapshot() }.getOrNull()
                state.value = cur.copy(
                    running = false,
                    session = cur.session.copy(endedAt = clock(), chargeCounterEndUah = snap?.chargeCounterUah),
                )
                flushLocked(force = true)
            }
        }
        context.stopService(Intent(context, TestSessionService::class.java))
    }

    /** Saves the user's result with computed metrics and clears the active session. */
    suspend fun saveResult(result: SessionResult): TestSession? {
        val cur = state.value ?: return null
        val ended = if (cur.session.endedAt == null) cur.session.copy(endedAt = clock()) else cur.session
        val withResult = ended.copy(result = result)
        val final = withResult.copy(metrics = MetricsCalculator.compute(withResult))
        sessions.upsert(final)
        synchronized(lock) {
            state.value = null
            store.delete(ACTIVE)
        }
        return final
    }

    fun discard() {
        if (state.value?.running == true) end()
        synchronized(lock) {
            state.value = null
            store.delete(ACTIVE)
        }
    }

    private fun flushLocked(force: Boolean) {
        val now = clock()
        if (!force && now - lastFlush < FLUSH_MS) return
        val cur = state.value ?: return
        runCatching { store.write(ACTIVE, ThorJson.store.encodeToString(TestSession.serializer(), cur.session)) }
        lastFlush = now
    }

    /** After a process death the service is gone: the recovered session is treated as ended. */
    private fun recover() {
        val text = store.read(ACTIVE) ?: return
        val session = runCatching { ThorJson.store.decodeFromString(TestSession.serializer(), text) }.getOrNull()
        if (session == null) {
            store.delete(ACTIVE); return
        }
        val lastT = session.samples.lastOrNull()?.t ?: 0L
        val ended = session.endedAt?.let { session } ?: session.copy(endedAt = session.startedAt + lastT)
        state.value = LiveSession(ended, running = false, elapsedSec = lastT / 1000)
    }

    companion object {
        const val ACTIVE = "active_session.json"
        const val FLUSH_MS = 30_000L
    }
}
