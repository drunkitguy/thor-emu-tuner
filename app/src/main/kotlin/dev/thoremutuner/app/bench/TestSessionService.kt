package dev.thoremutuner.app.bench

import android.app.Service
import android.content.Intent
import android.os.IBinder

class TestSessionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
