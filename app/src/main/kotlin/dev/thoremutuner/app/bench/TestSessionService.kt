package dev.thoremutuner.app.bench

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.ServiceCompat
import dev.thoremutuner.app.MainActivity
import dev.thoremutuner.app.ThorApp
import dev.thoremutuner.core.bench.Sample
import dev.thoremutuner.core.bench.TestSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service (type specialUse) that samples battery power and thermal status every 2 s
 * while the user plays (PLAN section 9.2). It starts before the emulator launch. At the planned
 * duration it notifies and vibrates once; sampling continues until the user ends the test, capped
 * at duration + 5 min. Works without the notification permission (the notification is then hidden).
 */
class TestSessionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSampling()
            return START_NOT_STICKY
        }
        startInForeground()
        if (loop?.isActive != true) loop = scope.launch { sampleLoop() }
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        ensureChannels(this)
        val notification = ongoingNotification()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private suspend fun sampleLoop() {
        val container = (application as ThorApp).container
        val controller = container.sessions
        val battery = container.battery
        val thermal = container.thermal
        val session = controller.live.value?.session ?: run { stopSampling(); return }
        val startElapsed = SystemClock.elapsedRealtime() - (session.samples.lastOrNull()?.t ?: 0L)
        var alerted = false
        while (scope.isActive) {
            val t = SystemClock.elapsedRealtime() - startElapsed
            val snap = runCatching { battery.snapshot() }.getOrNull()
            val sample = Sample(
                t = t,
                currentNow = snap?.currentNow,
                voltageMv = snap?.voltageMv,
                tempDeciC = snap?.tempDeciC,
                capacityPct = snap?.levelPct,
                thermalStatus = runCatching { thermal.status() }.getOrNull(),
                plugged = snap?.plugged,
            )
            if (!controller.addSample(sample)) break
            val planned = session.plannedDurationSec * 1000L
            if (!alerted && t >= planned) {
                alerted = true
                alertTimeReached()
            }
            if (t >= planned + TestSession.OVERRUN_CAP_SEC * 1000L) {
                controller.end()
                break
            }
            delay(TestSession.SAMPLE_INTERVAL_MS)
        }
        stopSampling()
    }

    private fun alertTimeReached() {
        val nm = getSystemService(NotificationManager::class.java)
        val n = Notification.Builder(this, CHANNEL_ALERT)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Test time reached")
            .setContentText("Return to Thor Emu Tuner to end the test and enter your results.")
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build()
        runCatching { nm?.notify(ALERT_ID, n) }
        runCatching {
            @Suppress("DEPRECATION")
            val vib = getSystemService(Vibrator::class.java)
            vib?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 200, 300), -1))
        }
    }

    private fun ongoingNotification(): Notification =
        Notification.Builder(this, CHANNEL_ONGOING)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle("Test session running")
            .setContentText("Sampling battery power and temperature every 2 s")
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .build()

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun stopSampling() {
        loop?.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "dev.thoremutuner.action.START_TEST"
        const val ACTION_STOP = "dev.thoremutuner.action.STOP_TEST"
        private const val CHANNEL_ONGOING = "test_session"
        private const val CHANNEL_ALERT = "test_alert"
        private const val NOTIFICATION_ID = 42
        private const val ALERT_ID = 43

        fun ensureChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ONGOING, "Test session", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ALERT, "Test time reached", NotificationManager.IMPORTANCE_HIGH))
        }
    }
}
