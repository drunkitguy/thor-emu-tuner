package dev.thoremutuner.core.bench

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** PLAN section 15, items 23-24. */
class BenchTest {
    private fun session(
        samples: List<Sample>,
        durationSec: Int = 600,
        result: SessionResult? = SessionResult(60.0, 60, null, 1, IssueLevel.NONE, IssueLevel.NONE, Outcome.PASS),
        start: Long? = null, end: Long? = null,
        version: String = "2506", perf: String = "Balanced", fan: String = "Auto",
    ) = TestSession(
        id = "s", gameKey = "g", emulatorId = "ppsspp", packageName = "org.ppsspp.ppsspp", emulatorVersion = version,
        rev = 1, startedAt = 0, endedAt = durationSec * 1000L, plannedDurationSec = durationSec,
        perfMode = perf, fanMode = fan, chargeCounterStartUah = start, chargeCounterEndUah = end,
        samples = samples, result = result,
    )

    /** Constant current every 2 s. */
    private fun steady(currentNow: Int, mv: Int = 4000, seconds: Int = 600, temp: Int = 300) =
        (0..seconds step 2).map { Sample(t = it * 1000L, currentNow = currentNow, voltageMv = mv, tempDeciC = temp, thermalStatus = 0, plugged = 0) }

    @Test fun microAmpsAreUsedAsIs() {
        val n = CurrentNormalizer.normalize(steady(-1_500_000))
        assertFalse(n.unitHeuristicApplied)
        assertEquals(6.0, n.points.first().watts, 1e-9) // 1.5 A * 4.0 V
    }

    @Test fun milliampsAreDetectedAndScaled() {
        val n = CurrentNormalizer.normalize(steady(-1500))
        assertTrue(n.unitHeuristicApplied)
        assertEquals(6.0, n.points.first().watts, 1e-9)
        val m = MetricsCalculator.compute(session(steady(-1500)))
        assertTrue(m.unitHeuristicApplied)
    }

    @Test fun signIsIgnoredWhileUnplugged() {
        val neg = CurrentNormalizer.normalize(steady(-1_000_000)).points.first().watts
        val pos = CurrentNormalizer.normalize(steady(1_000_000)).points.first().watts
        assertEquals(neg, pos, 1e-12)
    }

    @Test fun minValueZeroAndPluggedSamplesAreDiscarded() {
        val samples = listOf(
            Sample(0, Int.MIN_VALUE, 4000),
            Sample(2000, 0, 4000),
            Sample(4000, -2_000_000, 4000),
            Sample(6000, -2_000_000, 4000, plugged = 2),
            Sample(8000, null, 4000),
        )
        val n = CurrentNormalizer.normalize(samples)
        assertEquals(listOf(4000L), n.points.map { it.t })
        assertEquals(8.0, n.points.single().watts, 1e-9)
    }

    @Test fun missingVoltageUsesLastKnown() {
        val n = CurrentNormalizer.normalize(listOf(Sample(0, -1_000_000, 4000), Sample(2000, -1_000_000, null)))
        assertEquals(2, n.points.size)
        assertEquals(4.0, n.points[1].watts, 1e-9)
    }

    @Test fun warmupIsExcludedFromAverageAndP95() {
        // 10 W during the first 60 s warm-up, 5 W afterwards.
        val samples = (0..600 step 2).map { s ->
            Sample(s * 1000L, if (s < 60) -2_500_000 else -1_250_000, 4000, 300, 0, plugged = 0)
        }
        val m = MetricsCalculator.compute(session(samples))
        assertEquals(5.0, m.avgW!!, 1e-9)
        assertEquals(5.0, m.p95W!!, 1e-9)
        assertEquals(10.0, m.peakW!!, 1e-9)
    }

    @Test fun trapezoidEnergyIntegration() {
        // Ramp 0 -> 10 W over 3600 s: trapezoid gives exactly 5 Wh.
        val pts = listOf(PowerPoint(0, 0.0), PowerPoint(1_800_000, 5.0), PowerPoint(3_600_000, 10.0))
        assertEquals(5.0, MetricsCalculator.energyWh(pts)!!, 1e-9)
        assertEquals(5.0, MetricsCalculator.averageWatts(pts)!!, 1e-9)
        assertNull(MetricsCalculator.energyWh(listOf(PowerPoint(0, 3.0))))
        // 6 W for 600 s = 1 Wh.
        assertEquals(1.0, MetricsCalculator.compute(session(steady(-1_500_000))).energyWh!!, 1e-9)
    }

    @Test fun derivedMetrics() {
        val m = MetricsCalculator.compute(session(steady(-1_500_000, temp = 310)))
        assertEquals(6.0, m.avgW!!, 1e-9)
        assertEquals(22.2 / 6.0, m.estRuntimeH!!, 1e-9)
        assertEquals(10.0, m.fpsPerWatt!!, 1e-9)
        assertEquals(1.0, m.speedRatio!!, 1e-9)
        assertEquals(31.0, m.tempStartC!!, 1e-9)
        assertEquals(0.0, m.tempDeltaC!!, 1e-9)
        assertEquals(0, m.worstThermalStatus)
        assertFalse(m.usedCounterFallback)
    }

    @Test fun chargeCounterFallbackWhenServiceWasKilled() {
        // Only 10 samples for a 600 s session (expected 300) -> fall back to the charge counter.
        val few = steady(-1_500_000).take(10)
        // 1000 mAh over 600 s = 6 A average; at 4.0 V = 24 W.
        val m = MetricsCalculator.compute(session(few, start = 5_000_000, end = 4_000_000))
        assertTrue(m.usedCounterFallback)
        assertEquals(24.0, m.avgPowerFromCounterW!!, 1e-9)
        assertEquals(24.0, m.avgW!!, 1e-9)
        // With enough samples, the counter value is only stored.
        val full = MetricsCalculator.compute(session(steady(-1_500_000), start = 5_000_000, end = 4_000_000))
        assertFalse(full.usedCounterFallback)
        assertEquals(6.0, full.avgW!!, 1e-9)
        assertNotNull(full.avgPowerFromCounterW)
    }

    @Test fun resultFormEnforcesRequiredFields() {
        val ok = SessionResult(58.5, 60, 45.0, 2, IssueLevel.NONE, IssueLevel.MINOR, Outcome.PLAYABLE_WITH_ISSUES, "fine")
        assertTrue(ok.validate().isEmpty())
        assertTrue("avgFps" in ok.copy(avgFps = null).validate())
        assertTrue(ok.copy(avgFps = null, outcome = Outcome.CRASH).validate().isEmpty(), "FPS optional for a crash")
        assertTrue("minFps" in ok.copy(minFps = 70.0).validate())
        assertTrue("stutter" in ok.copy(stutter = 0).validate())
        assertTrue("stutter" in ok.copy(stutter = 6).validate())
        assertTrue("notes" in ok.copy(notes = "x".repeat(501)).validate())
        assertTrue("targetFps" in ok.copy(targetFps = 0).validate())
        assertTrue("avgFps" in ok.copy(avgFps = Double.NaN).validate())
    }

    // ---------------------------------------------------------------- comparability rules

    private fun metered(s: TestSession) = s.copy(metrics = MetricsCalculator.compute(s))
    private val base = metered(session(steady(-1_500_000)))

    @Test fun comparableSessionsHaveNoIssues() {
        assertTrue(Comparison.comparabilityIssues(base, base.copy(id = "b")).isEmpty())
    }

    @Test fun durationRule() {
        val short = metered(session(steady(-1_500_000, seconds = 300), durationSec = 300))
        assertTrue(Comparison.comparabilityIssues(base, short).any { "Durations" in it })
        val close = metered(session(steady(-1_500_000, seconds = 540), durationSec = 540)) // 10% shorter
        assertTrue(Comparison.comparabilityIssues(base, close).none { "Durations" in it })
    }

    @Test fun startTemperatureRule() {
        val hot = metered(session(steady(-1_500_000, temp = 370)))
        assertTrue(Comparison.comparabilityIssues(base, hot).any { "temperatures" in it })
        val warm = metered(session(steady(-1_500_000, temp = 340)))
        assertTrue(Comparison.comparabilityIssues(base, warm).none { "temperatures" in it })
    }

    @Test fun versionModeAndHeuristicRules() {
        assertTrue(Comparison.comparabilityIssues(base, metered(session(steady(-1_500_000), version = "2507"))).any { "versions" in it })
        assertTrue(Comparison.comparabilityIssues(base, metered(session(steady(-1_500_000), perf = "Max"))).any { "performance" in it })
        assertTrue(Comparison.comparabilityIssues(base, metered(session(steady(-1_500_000), fan = "Smart"))).any { "fan" in it })
        assertTrue(Comparison.comparabilityIssues(base, metered(session(steady(-1_500_000), perf = " balanced "))).none { "performance" in it })
        assertTrue(Comparison.comparabilityIssues(base, metered(session(steady(-1500)))).any { "heuristic" in it })
    }

    @Test fun winnerOrdering() {
        val faster = metered(session(steady(-2_000_000), result = SessionResult(60.0, 60, null, 1, IssueLevel.NONE, IssueLevel.NONE, Outcome.PASS)))
        val slower = metered(session(steady(-1_000_000), result = SessionResult(50.0, 60, null, 1, IssueLevel.NONE, IssueLevel.NONE, Outcome.PASS)))
        // Speed decides first even though the faster one uses more power.
        val c = Comparison.compare(slower, faster)
        assertEquals(Side.B, c.winner); assertEquals("speed", c.decidedBy)
        // Equal speed: lower power wins.
        val frugal = metered(session(steady(-1_000_000)))
        assertEquals(Side.B to "power", Comparison.winner(base, base.metrics!!, frugal, frugal.metrics!!))
        // Equal speed and power: cooler wins.
        val cooler = metered(session(steady(-1_500_000, temp = 280)))
        assertEquals(Side.B to "temperature", Comparison.winner(base, base.metrics!!, cooler, cooler.metrics!!))
        // Then stutter, then outcome.
        val smooth = base.copy(result = base.result!!.copy(stutter = 1))
        val stuttery = base.copy(result = base.result!!.copy(stutter = 4))
        assertEquals(Side.A to "stutter", Comparison.winner(smooth, smooth.metrics!!, stuttery, stuttery.metrics!!))
        val issues = base.copy(result = base.result!!.copy(outcome = Outcome.PLAYABLE_WITH_ISSUES))
        assertEquals(Side.A to "outcome", Comparison.winner(base, base.metrics!!, issues, issues.metrics!!))
        assertEquals(null to null, Comparison.winner(base, base.metrics!!, base, base.metrics!!))
    }

    @Test fun rowsHighlightBetterSide() {
        val frugal = metered(session(steady(-1_000_000)))
        val row = Comparison.compare(base, frugal).rows.first { it.label == "Average power" }
        assertEquals(Side.B, row.better)
        assertEquals(-2.0, row.delta!!, 1e-9)
    }

    @Test fun historyGroupsByRevision() {
        val s = listOf(base.copy(id = "1", rev = 1, startedAt = 1), base.copy(id = "2", rev = 2, startedAt = 2), base.copy(id = "3", rev = 1, startedAt = 3))
        val groups = Comparison.groupByRevision(s)
        assertEquals(listOf(2, 1), groups.map { it.first })
        assertEquals(listOf("3", "1"), groups[1].second.map { it.id })
    }
}
