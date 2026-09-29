package dev.thoremutuner.core.bench

import dev.thoremutuner.core.CoreInfo
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.ceil

/** Derived metrics (PLAN section 9.4). Null = not enough data. */
@Serializable
data class SessionMetrics(
    val avgW: Double? = null,
    val p95W: Double? = null,
    val peakW: Double? = null,
    val energyWh: Double? = null,
    /** 22.2 Wh / avgW. An estimate: the 3.7 V nominal battery voltage is inferred, not sourced. */
    val estRuntimeH: Double? = null,
    val tempStartC: Double? = null,
    val tempMaxC: Double? = null,
    val tempDeltaC: Double? = null,
    val worstThermalStatus: Int? = null,
    val fpsPerWatt: Double? = null,
    val speedRatio: Double? = null,
    val avgPowerFromCounterW: Double? = null,
    val usedCounterFallback: Boolean = false,
    val unitHeuristicApplied: Boolean = false,
    val validSamples: Int = 0,
    val expectedSamples: Int = 0,
    val durationSec: Double = 0.0,
)

/** A sample converted to watts. */
data class PowerPoint(val t: Long, val watts: Double)

/**
 * Current normalization (PLAN section 9.2): OEMs disagree on unit and sign. Positive = charging per
 * the docs; while unplugged the magnitude is used. `Integer.MIN_VALUE` (unsupported) and 0 are
 * discarded. If the median |I| of the first 10 valid samples is below 20 000 the values are taken
 * as mA and scaled to µA; this heuristic is inferred, so it is flagged on the session.
 */
object CurrentNormalizer {
    const val MA_THRESHOLD = 20_000
    private const val HEURISTIC_WINDOW = 10

    data class Normalized(val points: List<PowerPoint>, val unitHeuristicApplied: Boolean)

    fun normalize(samples: List<Sample>): Normalized {
        val valid = samples.filter { s ->
            val i = s.currentNow
            i != null && i != Int.MIN_VALUE && i != 0 && (s.plugged == null || s.plugged == 0)
        }
        val window = valid.take(HEURISTIC_WINDOW).map { abs(it.currentNow!!.toLong()) }.sorted()
        val median = if (window.isEmpty()) null else if (window.size % 2 == 1) window[window.size / 2].toDouble()
        else (window[window.size / 2 - 1] + window[window.size / 2]) / 2.0
        val asMilliamps = median != null && median < MA_THRESHOLD
        var lastVoltage: Int? = null
        val points = mutableListOf<PowerPoint>()
        for (s in samples) {
            s.voltageMv?.takeIf { it > 0 }?.let { lastVoltage = it }
            if (s !in valid) continue
            val v = s.voltageMv?.takeIf { it > 0 } ?: lastVoltage ?: continue
            val microAmps = abs(s.currentNow!!.toLong()) * (if (asMilliamps) 1000 else 1)
            points += PowerPoint(s.t, microAmps.toDouble() * v / 1e9)
        }
        return Normalized(points, asMilliamps)
    }
}

object MetricsCalculator {

    fun compute(session: TestSession): SessionMetrics {
        val samples = session.samples.sortedBy { it.t }
        val norm = CurrentNormalizer.normalize(samples)
        val warmupMs = session.warmupSec * 1000L
        val post = norm.points.filter { it.t >= warmupMs }.ifEmpty { norm.points }
        val duration = when {
            session.endedAt != null -> session.durationSec
            samples.isNotEmpty() -> samples.last().t / 1000.0
            else -> 0.0
        }
        val expected = ceil(duration * 1000 / TestSession.SAMPLE_INTERVAL_MS).toInt()

        val avgFromSamples = averageWatts(post)
        val counterW = counterWatts(session, samples, duration)
        val useFallback = expected > 0 && norm.points.size < expected / 2.0 && counterW != null
        val avgW = if (useFallback) counterW else avgFromSamples

        val temps = samples.mapNotNull { it.tempDeciC?.let { t -> t / 10.0 } }
        val tempStart = temps.firstOrNull()
        val tempMax = temps.maxOrNull()
        val result = session.result
        val fps = result?.avgFps
        return SessionMetrics(
            avgW = avgW,
            p95W = percentile(post.map { it.watts }, 0.95),
            peakW = norm.points.maxOfOrNull { it.watts },
            energyWh = if (useFallback) counterW!! * duration / 3600 else energyWh(norm.points),
            estRuntimeH = avgW?.takeIf { it > 0 }?.let { CoreInfo.THOR_BATTERY_WH / it },
            tempStartC = tempStart,
            tempMaxC = tempMax,
            tempDeltaC = if (tempStart != null && tempMax != null) tempMax - tempStart else null,
            worstThermalStatus = samples.mapNotNull { it.thermalStatus }.maxOrNull(),
            fpsPerWatt = if (fps != null && avgW != null && avgW > 0) fps / avgW else null,
            speedRatio = if (fps != null && result.targetFps > 0.0) fps / result.targetFps else null,
            avgPowerFromCounterW = counterW,
            usedCounterFallback = useFallback,
            unitHeuristicApplied = norm.unitHeuristicApplied,
            validSamples = norm.points.size,
            expectedSamples = expected,
            durationSec = duration,
        )
    }

    /** Time-weighted mean power (trapezoid); a single point is its own mean. */
    fun averageWatts(points: List<PowerPoint>): Double? {
        if (points.isEmpty()) return null
        if (points.size == 1) return points.single().watts
        val span = (points.last().t - points.first().t) / 1000.0
        if (span <= 0) return points.map { it.watts }.average()
        return energyWh(points)!! * 3600 / span
    }

    /** Trapezoid integration of power over time, in Wh. */
    fun energyWh(points: List<PowerPoint>): Double? {
        if (points.size < 2) return null
        var joules = 0.0
        for (i in 1 until points.size) {
            val dt = (points[i].t - points[i - 1].t) / 1000.0
            if (dt > 0) joules += (points[i].watts + points[i - 1].watts) / 2 * dt
        }
        return joules / 3600
    }

    /** Nearest-rank percentile. */
    fun percentile(values: List<Double>, p: Double): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val rank = ceil(p * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    /**
     * Fallback when the service was killed: BATTERY_PROPERTY_CHARGE_COUNTER (µAh) snapshots give the
     * average current = ΔµAh × 3600 / Δs, times the mean voltage.
     */
    fun counterWatts(session: TestSession, samples: List<Sample>, durationSec: Double): Double? {
        val start = session.chargeCounterStartUah ?: return null
        val end = session.chargeCounterEndUah ?: return null
        if (durationSec <= 0) return null
        val deltaUah = abs(start - end).toDouble()
        if (deltaUah == 0.0) return null
        val avgMicroAmps = deltaUah * 3600 / durationSec
        val volts = samples.mapNotNull { it.voltageMv?.takeIf { v -> v > 0 } }
        if (volts.isEmpty()) return null
        return avgMicroAmps * volts.average() / 1e9
    }
}
