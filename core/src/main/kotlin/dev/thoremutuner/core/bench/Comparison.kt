package dev.thoremutuner.core.bench

import kotlin.math.abs
import kotlin.math.max

enum class Side { A, B }

/** One metric row of the A/B table; [better] is null for ties or missing data. */
data class ComparisonRow(
    val label: String,
    val unit: String,
    val a: Double?,
    val b: Double?,
    val higherIsBetter: Boolean?,
) {
    val delta: Double? get() = if (a != null && b != null) b - a else null
    val better: Side? get() {
        if (a == null || b == null || higherIsBetter == null || a == b) return null
        return if ((b > a) == higherIsBetter) Side.B else Side.A
    }
}

data class ComparisonResult(
    val rows: List<ComparisonRow>,
    /** Winner by the ordered rules, or null for a tie. */
    val winner: Side?,
    /** Which rule decided (for the UI). */
    val decidedBy: String?,
    /** Non-empty = show the "Not comparable" badge. */
    val notComparableReasons: List<String>,
)

/** A/B comparison (PLAN section 9.5). */
object Comparison {

    /** Reasons two sessions are not comparable (empty = comparable). */
    fun comparabilityIssues(a: TestSession, b: TestSession): List<String> = buildList {
        if (a.gameKey != b.gameKey) add("Different games")
        val da = a.metrics?.durationSec ?: a.durationSec
        val db = b.metrics?.durationSec ?: b.durationSec
        if (max(da, db) > 0 && abs(da - db) / max(da, db) > 0.20) add("Durations differ by more than 20%")
        val ta = a.metrics?.tempStartC
        val tb = b.metrics?.tempStartC
        if (ta != null && tb != null && abs(ta - tb) > 5.0) add("Starting battery temperatures differ by more than 5 °C")
        if (a.emulatorVersion != b.emulatorVersion) add("Different emulator versions")
        if (a.emulatorId != b.emulatorId) add("Different emulators")
        if (!a.perfMode.trim().equals(b.perfMode.trim(), ignoreCase = true)) add("Different performance modes")
        if (!a.fanMode.trim().equals(b.fanMode.trim(), ignoreCase = true)) add("Different fan modes")
        if ((a.metrics?.unitHeuristicApplied ?: false) != (b.metrics?.unitHeuristicApplied ?: false)) {
            add("Current-unit heuristic applied to only one session")
        }
    }

    fun compare(a: TestSession, b: TestSession): ComparisonResult {
        val ma = a.metrics ?: MetricsCalculator.compute(a)
        val mb = b.metrics ?: MetricsCalculator.compute(b)
        val rows = listOf(
            ComparisonRow("Speed (FPS / target)", "x", ma.speedRatio, mb.speedRatio, true),
            ComparisonRow("Average FPS", "fps", a.result?.avgFps, b.result?.avgFps, true),
            ComparisonRow("Average power", "W", ma.avgW, mb.avgW, false),
            ComparisonRow("95th percentile power", "W", ma.p95W, mb.p95W, false),
            ComparisonRow("FPS per watt", "fps/W", ma.fpsPerWatt, mb.fpsPerWatt, true),
            ComparisonRow("Estimated runtime", "h", ma.estRuntimeH, mb.estRuntimeH, true),
            ComparisonRow("Max battery temp", "°C", ma.tempMaxC, mb.tempMaxC, false),
            ComparisonRow("Temp rise", "°C", ma.tempDeltaC, mb.tempDeltaC, false),
            ComparisonRow("Worst thermal status", "", ma.worstThermalStatus?.toDouble(), mb.worstThermalStatus?.toDouble(), false),
            ComparisonRow("Stutter (1-5)", "", a.result?.stutter?.toDouble(), b.result?.stutter?.toDouble(), false),
        )
        val (winner, rule) = winner(a, ma, b, mb)
        return ComparisonResult(rows, winner, rule, comparabilityIssues(a, b))
    }

    /**
     * Ordered rules: higher speedRatio, then lower avgW, then lower max temp, then lower stutter,
     * then better outcome. Small differences count as ties (2% speed, 3% power, 1 °C).
     */
    fun winner(a: TestSession, ma: SessionMetrics, b: TestSession, mb: SessionMetrics): Pair<Side?, String?> {
        val sa = ma.speedRatio ?: 0.0
        val sb = mb.speedRatio ?: 0.0
        if (abs(sa - sb) > 0.02) return (if (sa > sb) Side.A else Side.B) to "speed"
        val wa = ma.avgW
        val wb = mb.avgW
        if (wa != null && wb != null && abs(wa - wb) / max(wa, wb) > 0.03) return (if (wa < wb) Side.A else Side.B) to "power"
        val ta = ma.tempMaxC
        val tb = mb.tempMaxC
        if (ta != null && tb != null && abs(ta - tb) >= 1.0) return (if (ta < tb) Side.A else Side.B) to "temperature"
        val st = (a.result?.stutter ?: 3) - (b.result?.stutter ?: 3)
        if (st != 0) return (if (st < 0) Side.A else Side.B) to "stutter"
        val oa = a.result?.outcome?.ordinal ?: Outcome.entries.size
        val ob = b.result?.outcome?.ordinal ?: Outcome.entries.size
        if (oa != ob) return (if (oa < ob) Side.A else Side.B) to "outcome"
        return null to null
    }

    /** History: sessions grouped by revision, newest revision first, newest session first. */
    fun groupByRevision(sessions: List<TestSession>): List<Pair<Int, List<TestSession>>> =
        sessions.groupBy { it.rev }.toList().sortedByDescending { it.first }
            .map { (rev, list) -> rev to list.sortedByDescending { it.startedAt } }
}
