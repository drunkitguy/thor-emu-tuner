package dev.thoremutuner.app.ui.common

import dev.thoremutuner.core.model.DetectionMethod
import dev.thoremutuner.core.preset.Evidence
import dev.thoremutuner.core.preset.Impact
import java.text.DateFormat
import java.util.Date
import java.util.Locale

object Fmt {
    fun num(v: Double?, digits: Int = 1): String = v?.let { String.format(Locale.US, "%.${digits}f", it) } ?: "-"
    fun watts(v: Double?): String = v?.let { String.format(Locale.US, "%.2f W", it) } ?: "-"
    fun celsius(v: Double?): String = v?.let { String.format(Locale.US, "%.1f °C", it) } ?: "-"
    fun date(epochMs: Long?): String =
        epochMs?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) } ?: "-"
    fun duration(sec: Long): String = String.format(Locale.US, "%d:%02d", sec / 60, sec % 60)

    fun method(m: DetectionMethod): String = when (m) {
        DetectionMethod.HEADER -> "from header"
        DetectionMethod.CONTAINER_METADATA -> "from container"
        DetectionMethod.FILENAME -> "from file name"
        DetectionMethod.MANUAL -> "set by you"
    }

    /** Badge text; `inferred` never says "tested" (PLAN section 15, item 29). */
    fun evidence(e: Evidence): String = e.badge

    fun impact(i: Impact): String = when (i) {
        Impact.HIGH -> "High impact"
        Impact.MEDIUM -> "Medium impact"
        Impact.LOW -> "Low impact"
    }
}
