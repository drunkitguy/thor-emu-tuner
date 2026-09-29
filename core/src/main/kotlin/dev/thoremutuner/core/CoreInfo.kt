package dev.thoremutuner.core

/** Static facts shared by :core and :app. */
object CoreInfo {
    const val APP_NAME = "Thor Emu Tuner"

    /** Thor battery: 6000 mAh x 3.7 V nominal. The 3.7 V nominal voltage is inferred, not sourced. */
    const val THOR_BATTERY_WH = 22.2
}
