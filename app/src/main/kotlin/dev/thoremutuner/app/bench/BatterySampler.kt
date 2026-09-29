package dev.thoremutuner.app.bench

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Build

/** A snapshot of battery telemetry. Unsupported values are null. */
data class BatterySnapshot(
    val plugged: Int?,
    val levelPct: Int?,
    val voltageMv: Int?,
    val tempDeciC: Int?,
    val currentNow: Int?,
    val chargeCounterUah: Long?,
)

/**
 * Reads battery telemetry (PLAN section 9.2): CURRENT_NOW (µA per docs), CHARGE_COUNTER (µAh),
 * CAPACITY, and the sticky ACTION_BATTERY_CHANGED intent for voltage, temperature and plug state.
 * Needs no permission.
 */
class BatterySampler(private val context: Context) {
    private val bm: BatteryManager? = context.getSystemService(BatteryManager::class.java)

    fun snapshot(): BatterySnapshot {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val sticky: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(null, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(null, filter)
        }
        val plugged = sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)?.takeIf { it >= 0 }
        val level = sticky?.let {
            val l = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val s = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (l >= 0 && s > 0) l * 100 / s else null
        } ?: property(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return BatterySnapshot(
            plugged = plugged,
            levelPct = level,
            voltageMv = sticky?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)?.takeIf { it > 0 },
            tempDeciC = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE },
            currentNow = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW),
            chargeCounterUah = property(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)?.toLong(),
        )
    }

    private fun property(id: Int): Int? = bm?.getIntProperty(id)?.takeIf { it != Int.MIN_VALUE && it != 0 }

    /** Number of displays; the Thor has two internal ones, so more than 2 means an external display. */
    fun displayCount(): Int = context.getSystemService(DisplayManager::class.java)?.displays?.size ?: 1
}
