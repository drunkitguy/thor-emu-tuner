package dev.thoremutuner.app.ui.test

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.thoremutuner.app.AppContainer
import dev.thoremutuner.app.ui.common.Banner
import dev.thoremutuner.app.ui.common.BannerKind
import dev.thoremutuner.app.ui.common.ButtonStyle
import dev.thoremutuner.app.ui.common.ChoiceDialog
import dev.thoremutuner.app.ui.common.EmptyState
import dev.thoremutuner.app.ui.common.LabeledValue
import dev.thoremutuner.app.ui.common.ScreenScaffold
import dev.thoremutuner.app.ui.common.SectionCard
import dev.thoremutuner.app.ui.common.TextInputDialog
import dev.thoremutuner.app.ui.common.ThorButton
import dev.thoremutuner.app.ui.common.focusRing
import dev.thoremutuner.app.ui.common.initialFocus
import dev.thoremutuner.app.ui.theme.ErrorColor
import dev.thoremutuner.app.ui.theme.OkColor
import dev.thoremutuner.app.ui.theme.WarnColor
import dev.thoremutuner.app.vm.TestViewModel
import dev.thoremutuner.core.bench.TestSession

private val PERF_SUGGESTIONS = listOf("Standard", "Performance", "Max performance", "Power saving", "Custom")
private val FAN_SUGGESTIONS = listOf("Auto", "Smart", "Quiet", "Sport", "Max", "Off")

/** Pre-flight checklist (PLAN section 9.1). Blocking items: charger connected, no revision, emulator missing. */
@Composable
fun TestScreen(container: AppContainer, key: String, emulatorId: String, onBack: () -> Unit, openResult: () -> Unit) {
    val vm = viewModel(key = "test-$key-$emulatorId") { TestViewModel(container, key, emulatorId) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pick by remember { mutableStateOf<String?>(null) }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Sampling works either way; the permission only makes the notification visible.
        vm.start(context)
    }
    LaunchedEffect(ui.started) { if (ui.started && ui.message == null) openResult() }

    ScreenScaffold("Run test" + (ui.def?.let { " · ${it.name}" } ?: ""), onBack = onBack) { pad ->
        if (ui.def == null) {
            EmptyState("Unknown emulator"); return@ScreenScaffold
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(pad), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ui.message?.let { m ->
                Banner(m, if (ui.started) BannerKind.WARN else BannerKind.INFO, actionLabel = if (ui.started) "Continue" else null, onAction = if (ui.started) openResult else null)
            }
            SectionCard("Before you start") {
                Check(!ui.charging, blocking = true, if (ui.charging) "Unplug the charger (power readings are meaningless while charging)" else "Not charging")
                Check(!ui.lowBattery, blocking = false, "Battery ${ui.battery?.levelPct ?: "?"}%" + if (ui.lowBattery) " (below 20%: results may be affected)" else "")
                Check(!ui.externalDisplay, blocking = false, if (ui.externalDisplay) "External display attached (Azahar may move output; results not comparable)" else "No external display")
                Check(ui.target != null, blocking = true, ui.target?.let { "Emulator installed (${it.versionName ?: "unknown version"})" } ?: "Emulator not installed")
                Check(ui.revision != null, blocking = true, ui.revision?.let { "Testing rev ${it.rev}" } ?: "Choose a baseline first")
                if (ui.needsApply) {
                    Check(false, blocking = false, "The revision to test is not applied yet (Launch applies it)")
                    ThorButton("Apply now", vm::applyNow, style = ButtonStyle.SECONDARY, enabled = !ui.busy)
                }
                ThorButton("Re-check", vm::refresh, style = ButtonStyle.TEXT)
            }
            SectionCard("Device mode (from AYN's quick settings)") {
                Text("These cannot be read by apps, so pick what is set now. Sessions with different modes are marked not comparable.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                LabeledValue("Performance mode", ui.perfMode.ifEmpty { "(not set)" })
                ThorButton("Choose performance mode", { pick = "perf" }, style = ButtonStyle.SECONDARY)
                LabeledValue("Fan mode", ui.fanMode.ifEmpty { "(not set)" })
                ThorButton("Choose fan mode", { pick = "fan" }, style = ButtonStyle.SECONDARY)
            }
            SectionCard("Duration") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(TestSession.DURATIONS_MIN) { m ->
                        FilterChip(ui.durationMin == m, { vm.setDuration(m) }, { Text("$m min") }, Modifier.focusRing(RoundedCornerShape(8.dp)))
                    }
                }
                Text("The first ${TestSession.DEFAULT_WARMUP_SEC} s are warm-up and excluded from averages. Keep the emulator's FPS overlay visible and note the average FPS you see.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ThorButton(
                "Start test and launch",
                {
                    val needAsk = Build.VERSION.SDK_INT >= 33 &&
                        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    if (needAsk) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.start(context)
                },
                enabled = ui.canStart,
                icon = Icons.Filled.PlayArrow,
                modifier = Modifier.fillMaxWidth().initialFocus(),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
    when (pick) {
        "perf" -> ModeDialog("Performance mode", PERF_SUGGESTIONS, ui.perfMode, { vm.setPerfMode(it); pick = null }, { pick = null })
        "fan" -> ModeDialog("Fan mode", FAN_SUGGESTIONS, ui.fanMode, { vm.setFanMode(it); pick = null }, { pick = null })
    }
}

@Composable
private fun ModeDialog(title: String, suggestions: List<String>, current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var custom by remember { mutableStateOf(false) }
    if (custom) {
        TextInputDialog(title, current, "Mode name", onConfirm = onPick, onDismiss = onDismiss,
            validate = { if (it.isBlank()) "Enter a name" else if (it.length > 40) "Max 40 characters" else null })
    } else {
        ChoiceDialog(title, suggestions.map { it to it } + ("__custom" to "Other..."), current, { if (it == "__custom") custom = true else onPick(it) }, onDismiss)
    }
}

@Composable
private fun Check(ok: Boolean, blocking: Boolean, text: String) {
    val color = when {
        ok -> OkColor
        blocking -> ErrorColor
        else -> WarnColor
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text(if (ok) "✓" else if (blocking) "✗" else "!", color = color, style = MaterialTheme.typography.titleMedium)
        Text(text, color = if (ok) MaterialTheme.colorScheme.onSurface else color)
    }
}
