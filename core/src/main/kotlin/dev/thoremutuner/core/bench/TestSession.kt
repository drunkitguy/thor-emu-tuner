package dev.thoremutuner.core.bench

import kotlinx.serialization.Serializable

/**
 * One telemetry sample (every 2 s). Raw values as the platform reports them:
 * [currentNow] = BATTERY_PROPERTY_CURRENT_NOW (nominally µA, sign and unit vary by OEM),
 * [voltageMv] = EXTRA_VOLTAGE, [tempDeciC] = EXTRA_TEMPERATURE (tenths of °C),
 * [thermalStatus] = PowerManager.currentThermalStatus (0-6), [plugged] = EXTRA_PLUGGED.
 */
@Serializable
data class Sample(
    val t: Long,
    val currentNow: Int? = null,
    val voltageMv: Int? = null,
    val tempDeciC: Int? = null,
    val capacityPct: Int? = null,
    val thermalStatus: Int? = null,
    val plugged: Int? = null,
)

@Serializable
enum class IssueLevel(val label: String) { NONE("None"), MINOR("Minor"), MAJOR("Major") }

/** Outcomes, best first. */
@Serializable
enum class Outcome(val label: String) {
    PASS("Pass"),
    PLAYABLE_WITH_ISSUES("Playable with issues"),
    FAIL("Fail"),
    CRASH("Crash"),
}

/** The user-entered result form (PLAN section 9.3). */
@Serializable
data class SessionResult(
    val avgFps: Double?,
    val targetFps: Int,
    val minFps: Double? = null,
    val stutter: Int,
    val audio: IssueLevel,
    val graphics: IssueLevel,
    val outcome: Outcome,
    val notes: String = "",
) {
    /** Field errors keyed by field name; empty = valid. */
    fun validate(): Map<String, String> = buildMap {
        if (outcome != Outcome.CRASH) {
            when {
                avgFps == null -> put("avgFps", "Enter the average FPS you saw in the overlay")
                !avgFps.isFinite() || avgFps <= 0.0 || avgFps > 1000.0 -> put("avgFps", "Must be between 0 and 1000")
            }
        } else if (avgFps != null && (!avgFps.isFinite() || avgFps < 0.0 || avgFps > 1000.0)) {
            put("avgFps", "Must be between 0 and 1000")
        }
        if (targetFps !in 1..240) put("targetFps", "Target FPS must be 1-240")
        if (minFps != null) {
            if (!minFps.isFinite() || minFps < 0.0) put("minFps", "Must be 0 or more")
            else if (avgFps != null && minFps > avgFps) put("minFps", "Lowest FPS cannot exceed the average")
        }
        if (stutter !in 1..5) put("stutter", "Pick 1 (none) to 5 (constant)")
        if (notes.length > MAX_NOTES) put("notes", "Max $MAX_NOTES characters")
    }

    companion object {
        const val MAX_NOTES = 500
    }
}

/**
 * A timed test session. It always records which revision and emulator version were tested
 * (PLAN section 15, item 24).
 */
@Serializable
data class TestSession(
    val schemaVersion: Int = 1,
    val id: String,
    val gameKey: String,
    val emulatorId: String,
    val packageName: String?,
    val emulatorVersion: String?,
    val rev: Int,
    val presetName: String? = null,
    val startedAt: Long,
    val endedAt: Long? = null,
    val plannedDurationSec: Int,
    val warmupSec: Int = DEFAULT_WARMUP_SEC,
    val perfMode: String = "",
    val fanMode: String = "",
    val startBatteryPct: Int? = null,
    val externalDisplay: Boolean = false,
    val chargeCounterStartUah: Long? = null,
    val chargeCounterEndUah: Long? = null,
    val samples: List<Sample> = emptyList(),
    val result: SessionResult? = null,
    val metrics: SessionMetrics? = null,
) {
    val durationSec: Double get() = ((endedAt ?: startedAt) - startedAt) / 1000.0

    companion object {
        const val DEFAULT_WARMUP_SEC = 60
        const val SAMPLE_INTERVAL_MS = 2000L
        val DURATIONS_MIN = listOf(3, 5, 10, 20)
        const val DEFAULT_DURATION_MIN = 10
        /** Sampling continues after the planned end until the user ends the session, capped here. */
        const val OVERRUN_CAP_SEC = 5 * 60
    }
}
