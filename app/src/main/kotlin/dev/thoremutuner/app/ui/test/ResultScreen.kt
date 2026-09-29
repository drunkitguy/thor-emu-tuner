@file:OptIn(ExperimentalMaterial3Api::class)

package dev.thoremutuner.app.ui.test

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.thoremutuner.core.bench.TargetFps
import dev.thoremutuner.app.AppContainer
import dev.thoremutuner.app.bench.LiveSession
import dev.thoremutuner.app.bench.ThermalSampler
import dev.thoremutuner.app.ui.common.Banner
import dev.thoremutuner.app.ui.common.BannerKind
import dev.thoremutuner.app.ui.common.ButtonStyle
import dev.thoremutuner.app.ui.common.ConfirmDialog
import dev.thoremutuner.app.ui.common.EmptyState
import dev.thoremutuner.app.ui.common.Fmt
import dev.thoremutuner.app.ui.common.LabeledValue
import dev.thoremutuner.app.ui.common.ScreenScaffold
import dev.thoremutuner.app.ui.common.SectionCard
import dev.thoremutuner.app.ui.common.TextInputDialog
import dev.thoremutuner.app.ui.common.ThorButton
import dev.thoremutuner.app.ui.common.focusRing
import dev.thoremutuner.app.ui.common.initialFocus
import dev.thoremutuner.app.vm.ResultViewModel
import dev.thoremutuner.core.bench.IssueLevel
import dev.thoremutuner.core.bench.Outcome
import dev.thoremutuner.core.bench.TestSession

/** Running view (timer, live W/temp, End test), then the result form (PLAN 9.3), then a summary. */
@Composable
fun ResultScreen(container: AppContainer, onBack: () -> Unit, openHistory: (String) -> Unit) {
    val vm = viewModel { ResultViewModel(container, createSavedStateHandle()) }
    val live by vm.live.collectAsStateWithLifecycle()
    val saved by vm.saved.collectAsStateWithLifecycle()
    var confirmDiscard by remember { mutableStateOf(false) }
    val title = when {
        saved != null -> "Test summary"
        live?.running == true -> "Test running"
        else -> "Enter results"
    }
    val scroll = rememberScrollState()
    ScreenScaffold(title, onBack = onBack, scrollState = scroll) { pad ->
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(pad), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val s = saved
            val l = live
            when {
                s != null -> Summary(s) { openHistory(s.gameKey) }
                l == null -> EmptyState("No test session in progress.")
                l.running -> Running(l, vm::end)
                else -> {
                    Form(vm, l)
                    ThorButton("Discard this session", { confirmDiscard = true }, style = ButtonStyle.TEXT)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (confirmDiscard) {
        ConfirmDialog("Discard session?", "The samples recorded for this test will be deleted.", "Discard",
            onConfirm = { vm.discard(); confirmDiscard = false; onBack() }, onDismiss = { confirmDiscard = false })
    }
}

@Composable
private fun Running(l: LiveSession, end: () -> Unit) {
    SectionCard("Elapsed ${Fmt.duration(l.elapsedSec)} of ${l.session.plannedDurationSec / 60} min") {
        if (l.reachedPlannedEnd) Banner("Test time reached. Note the average FPS, then end the test.", BannerKind.OK)
        LabeledValue("Power now", Fmt.watts(l.lastWatts))
        LabeledValue("Battery temperature", Fmt.celsius(l.lastTempC))
        LabeledValue("Thermal status", ThermalSampler.label(l.lastThermal))
        LabeledValue("Samples", l.session.samples.size.toString())
        Text("Sampling continues in the background while you play (every 2 s), and stops automatically ${TestSession.OVERRUN_CAP_SEC / 60} min after the planned end.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    ThorButton("End test", end, modifier = Modifier.fillMaxWidth().initialFocus())
}

@Composable
private fun Form(vm: ResultViewModel, l: LiveSession) {
    val form by vm.form.collectAsStateWithLifecycle()
    val errors by vm.errors.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<String?>(null) }
    if (l.powerIncomplete) {
        Banner("The app was closed during the test: power data is incomplete (only the samples taken before that are used).", BannerKind.WARN)
    }
    Text("Test of ${Fmt.duration(l.elapsedSec)} recorded (${l.session.samples.size} samples). Enter what you saw in the emulator's overlay.",
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    SectionCard("Outcome") {
        ChipRow(Outcome.entries.toList(), form.outcome, { it.label }, first = true) { o -> vm.update { it.copy(outcome = o) } }
    }
    SectionCard("Frame rate") {
        LabeledValue("Average FPS" + if (form.outcome == Outcome.CRASH) " (optional)" else " (required)", form.avgFps.ifEmpty { "-" })
        errors["avgFps"]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        ThorButton("Enter average FPS", { editing = "avg" }, style = ButtonStyle.SECONDARY)
        Text("Target FPS")
        ChipRow(TargetFps.CHOICES + 0.0, form.targetFps, { if (it == 0.0) "Other" + (form.customTarget.takeIf { c -> c.isNotBlank() }?.let { c -> " ($c)" } ?: "") else TargetFps.label(it) }) { t ->
            if (t == 0.0) editing = "target" else vm.update { it.copy(targetFps = t) }
        }
        errors["targetFps"]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        LabeledValue("Lowest FPS seen (optional)", form.minFps.ifEmpty { "-" })
        errors["minFps"]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        ThorButton("Enter lowest FPS", { editing = "min" }, style = ButtonStyle.SECONDARY)
    }
    SectionCard("Stutter (1 = none, 5 = constant)") {
        ChipRow((1..5).toList(), form.stutter, { it.toString() }) { v -> vm.update { it.copy(stutter = v) } }
    }
    SectionCard("Audio issues") { ChipRow(IssueLevel.entries.toList(), form.audio, { it.label }) { v -> vm.update { it.copy(audio = v) } } }
    SectionCard("Graphics issues") { ChipRow(IssueLevel.entries.toList(), form.graphics, { it.label }) { v -> vm.update { it.copy(graphics = v) } } }
    SectionCard("Notes") {
        Text(form.notes.ifEmpty { "(none)" })
        errors["notes"]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        ThorButton("Edit notes", { editing = "notes" }, style = ButtonStyle.SECONDARY)
    }
    if (errors.isNotEmpty()) Banner("Please fix the highlighted fields.", BannerKind.ERROR)
    ThorButton("Save result", vm::save, modifier = Modifier.fillMaxWidth())

    when (editing) {
        "avg" -> NumberDialog("Average FPS", form.avgFps, { v -> vm.update { it.copy(avgFps = v) }; editing = null }) { editing = null }
        "min" -> NumberDialog("Lowest FPS", form.minFps, { v -> vm.update { it.copy(minFps = v) }; editing = null }, allowEmpty = true) { editing = null }
        "target" -> NumberDialog("Target FPS", form.customTarget, { v -> vm.update { it.copy(targetFps = 0.0, customTarget = v) }; editing = null }) { editing = null }
        "notes" -> TextInputDialog("Notes", form.notes, "Notes (max ${dev.thoremutuner.core.bench.SessionResult.MAX_NOTES} characters)",
            onConfirm = { v -> vm.update { it.copy(notes = v) }; editing = null }, onDismiss = { editing = null }, singleLine = false,
            validate = { if (it.length > dev.thoremutuner.core.bench.SessionResult.MAX_NOTES) "Too long" else null })
    }
}

@Composable
private fun NumberDialog(title: String, initial: String, onOk: (String) -> Unit, allowEmpty: Boolean = false, onDismiss: () -> Unit) {
    TextInputDialog(
        title, initial, "Number",
        onConfirm = { onOk(it.trim()) }, onDismiss = onDismiss,
        validate = { v ->
            val t = v.trim().replace(',', '.')
            when {
                t.isEmpty() && allowEmpty -> null
                t.toDoubleOrNull() == null -> "Enter a number"
                t.toDouble() < 0 || t.toDouble() > 1000 -> "0-1000"
                else -> null
            }
        },
    )
}

@Composable
private fun <T> ChipRow(options: List<T>, selected: T, label: (T) -> String, first: Boolean = false, onPick: (T) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(options) { o ->
            FilterChip(
                selected = o == selected,
                onClick = { onPick(o) },
                label = { Text(label(o)) },
                modifier = Modifier.focusRing(RoundedCornerShape(8.dp)).let { if (first && o == options.first()) it.initialFocus() else it },
            )
        }
    }
}

@Composable
private fun Summary(s: TestSession, openHistory: () -> Unit) {
    val m = s.metrics
    SectionCard("Saved: rev ${s.rev} · ${s.result?.outcome?.label ?: ""}", focusable = true) {
        LabeledValue("Average FPS / target", "${Fmt.num(s.result?.avgFps)} / ${s.result?.targetFps?.let { TargetFps.label(it) } ?: "-"}")
        LabeledValue("Speed", m?.speedRatio?.let { "${Fmt.num(it * 100, 0)}%" } ?: "-")
        LabeledValue("Average power", Fmt.watts(m?.avgW) + if (m?.usedCounterFallback == true) " (charge counter)" else "")
        LabeledValue("95th percentile power", Fmt.watts(m?.p95W))
        LabeledValue("Peak power", Fmt.watts(m?.peakW))
        LabeledValue("Energy used", m?.energyWh?.let { "${Fmt.num(it, 2)} Wh" } ?: "-")
        LabeledValue("Estimated runtime (estimate)", m?.estRuntimeH?.let { "${Fmt.num(it, 1)} h" } ?: "-")
        LabeledValue("FPS per watt", Fmt.num(m?.fpsPerWatt))
        LabeledValue("Battery temp start → max", "${Fmt.celsius(m?.tempStartC)} → ${Fmt.celsius(m?.tempMaxC)}")
        LabeledValue("Worst thermal status", ThermalSampler.label(m?.worstThermalStatus))
        LabeledValue("Emulator version", s.emulatorVersion ?: "unknown")
        if (m?.unitHeuristicApplied == true) Text("Current readings looked like mA and were scaled (heuristic).", color = MaterialTheme.colorScheme.secondary)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ThorButton("History and compare", openHistory, modifier = Modifier.initialFocus())
    }
}
