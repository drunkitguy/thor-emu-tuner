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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/** Live view of the running (or just-ended) session. */
data class LiveSession(
    val session: TestSession,
    val running: Boolean,
    val lastWatts: Double? = null,
    val lastTempC: Double? = null,
    val lastThermal: Int? = null,
    val elapsedSec: Long = 0,
    /** True when the app was killed and no trustworthy end counter could be taken. */
    val powerIncomplete: Boolean = false,
) {
    val reachedPlannedEnd: Boolean get() = elapsedSec >= session.plannedDurationSec
}

/**
 * Owns the active test session. The foreground service feeds samples in; samples are kept in memory
 * and flushed to `active_session.json` every 30 s on a single background writer thread (never on
 * the main thread), so a killed process can be recovered.
 */
class SessionController(
    private val context: Context,
    private val store: JsonStore,
    private val sessions: SessionRepository,
    private val battery: BatterySampler,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val state = MutableStateFlow<LiveSession?>(null)
    val live: StateFlow<LiveSession?> = state.asStateFlow()
    private val lock = Any()
    private var lastFlush = 0L

    /** Serial writer: flushes, deletes and recovery run in order, off the main thread. */
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "session-writer") }.asCoroutineDispatcher()

    /** Unsaved result-form input, kept while the user navigates away and back (keyed by session id). */
    @Volatile var draft: Pair<String, String>? = null

    init {
        scope.launch(writer) { recover() }
    }

    /**
     * Starts sampling: stores the header and starts the foreground service. Refuses (returns false)
     * while another session is running or waiting for results, so nothing is silently replaced.
     * Runs on the serial writer, so it is ordered after startup recovery: a recovered session is
     * always seen (and refused against), never overwritten.
     */
    suspend fun start(session: TestSession): Boolean {
        val accepted = withContext(writer) {
            synchronized(lock) {
                if (state.value != null) return@withContext false
                state.value = LiveSession(session, running = true)
                draft = null
            }
            lastFlush = clock()
            writeNow()
            true
        }
        if (!accepted) return false
        val intent = Intent(context, TestSessionService::class.java).setAction(TestSessionService.ACTION_START)
        context.startForegroundService(intent)
        return true
    }

    /** Called by the service every 2 s. Returns false when the session is no longer running. */
    fun addSample(sample: Sample): Boolean {
        synchronized(lock) {
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
        }
        flush(force = false)
        return true
    }

    /** Ends sampling (user pressed "End test" or the overrun cap was hit). */
    fun end() {
        val cur = state.value ?: return
        if (cur.running) {
            scope.launch(writer) {
                val snap = runCatching { battery.snapshot() }.getOrNull()
                synchronized(lock) {
                    val now = state.value ?: return@synchronized
                    if (now.running) {
                        val plugged = (snap?.plugged ?: 0) != 0
                        state.value = now.copy(
                            running = false,
                            session = now.session.copy(endedAt = clock(), chargeCounterEndUah = if (plugged) null else snap?.chargeCounterUah),
                        )
                    }
                }
                writeNow()
            }
        }
        context.stopService(Intent(context, TestSessionService::class.java))
    }

    /** Saves the user's result with computed metrics and clears the active session. */
    suspend fun saveResult(result: SessionResult): TestSession? {
        val cur = state.value ?: return null
        val ended = if (cur.session.endedAt == null) cur.session.copy(endedAt = clock()) else cur.session
        val withResult = ended.copy(result = result)
        val final = withContext(writer) { withResult.copy(metrics = MetricsCalculator.compute(withResult)) }
        sessions.upsert(final)
        synchronized(lock) {
            state.value = null
            draft = null
        }
        scope.launch(writer) { store.delete(ACTIVE) }
        return final
    }

    fun discard() {
        if (state.value?.running == true) context.stopService(Intent(context, TestSessionService::class.java))
        synchronized(lock) {
            state.value = null
            draft = null
        }
        scope.launch(writer) { store.delete(ACTIVE) }
    }

    private fun flush(force: Boolean) {
        val now = clock()
        if (!force && now - lastFlush < FLUSH_MS) return
        lastFlush = now
        scope.launch(writer) { writeNow() }
    }

    private fun writeNow() {
        val cur = state.value ?: return
        runCatching { store.write(ACTIVE, ThorJson.store.encodeToString(TestSession.serializer(), cur.session)) }
    }

    /**
     * After a process death the service is gone, so the recovered session is ended now: endedAt is
     * the current time (capped at the planned duration plus the overrun cap) and the end charge
     * counter comes from a fresh snapshot, which lets metrics fall back to the counter. If the
     * device is charging, or the cap was exceeded, the counter is left empty and the result is
     * marked "power data incomplete".
     */
    private fun recover() {
        val text = store.read(ACTIVE) ?: return
        val session = runCatching { ThorJson.store.decodeFromString(TestSession.serializer(), text) }.getOrNull()
        if (session == null) {
            store.delete(ACTIVE); return
        }
        val lastT = session.samples.lastOrNull()?.t ?: 0L
        if (session.endedAt != null) {
            synchronized(lock) { if (state.value == null) state.value = LiveSession(session, running = false, elapsedSec = lastT / 1000) }
            return
        }
        val now = clock()
        val cap = session.startedAt + (session.plannedDurationSec + TestSession.OVERRUN_CAP_SEC) * 1000L
        val snap = runCatching { battery.snapshot() }.getOrNull()
        val plugged = (snap?.plugged ?: 0) != 0
        val usable = !plugged && now <= cap && snap?.chargeCounterUah != null
        val ended = session.copy(
            endedAt = minOf(now, cap),
            chargeCounterEndUah = if (usable) snap?.chargeCounterUah else null,
        )
        synchronized(lock) {
            if (state.value == null) {
                state.value = LiveSession(ended, running = false, elapsedSec = (ended.endedAt!! - ended.startedAt) / 1000, powerIncomplete = !usable)
            }
        }
        writeNow()
    }

    companion object {
        const val ACTIVE = "active_session.json"
        const val FLUSH_MS = 30_000L
    }
}
