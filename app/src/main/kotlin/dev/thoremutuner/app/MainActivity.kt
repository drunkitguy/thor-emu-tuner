package dev.thoremutuner.app

import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.thoremutuner.app.ui.nav.AppNav
import dev.thoremutuner.app.ui.theme.ThorTheme

/**
 * Single activity. Gamepad mapping (PLAN section 11): B = back, A = activate the focused control
 * (sent as DPAD_CENTER, which Compose clickables handle). The D-pad moves focus natively.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // targetSdk 35 draws edge to edge; opt in explicitly (also on Android 13) with light icons on
        // the true-black UI. ScreenScaffold pads content by the safe-drawing insets.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            ThorTheme {
                AppNav(container)
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_B -> {
                if (event.action == KeyEvent.ACTION_UP) onBackPressedDispatcher.onBackPressed()
                return true
            }
            KeyEvent.KEYCODE_BUTTON_A -> {
                val mapped = KeyEvent(
                    event.downTime, event.eventTime, event.action, KeyEvent.KEYCODE_DPAD_CENTER,
                    event.repeatCount, event.metaState, event.deviceId, event.scanCode, event.flags, event.source,
                )
                return super.dispatchKeyEvent(mapped)
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
