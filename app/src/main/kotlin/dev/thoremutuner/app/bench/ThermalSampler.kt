package dev.thoremutuner.app.bench

import android.content.Context
import android.os.PowerManager

/** PowerManager thermal status (0 = none ... 6 = shutdown). Headroom sampling is planned after v0.1. */
class ThermalSampler(context: Context) {
    private val pm: PowerManager? = context.getSystemService(PowerManager::class.java)

    fun status(): Int? = pm?.currentThermalStatus

    companion object {
        fun label(status: Int?): String = when (status) {
            null -> "Unknown"
            PowerManager.THERMAL_STATUS_NONE -> "None"
            PowerManager.THERMAL_STATUS_LIGHT -> "Light"
            PowerManager.THERMAL_STATUS_MODERATE -> "Moderate"
            PowerManager.THERMAL_STATUS_SEVERE -> "Severe"
            PowerManager.THERMAL_STATUS_CRITICAL -> "Critical"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "Shutdown"
            else -> "Level $status"
        }
    }
}
