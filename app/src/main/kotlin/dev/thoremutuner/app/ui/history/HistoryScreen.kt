package dev.thoremutuner.app.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.thoremutuner.app.AppContainer
import dev.thoremutuner.app.ui.common.Banner
import dev.thoremutuner.app.ui.common.BannerKind
import dev.thoremutuner.app.ui.common.ConfirmDialog
import dev.thoremutuner.app.ui.common.EmptyState
import dev.thoremutuner.app.ui.common.Fmt
import dev.thoremutuner.app.ui.common.LabeledValue
import dev.thoremutuner.app.ui.common.ScreenScaffold
import dev.thoremutuner.app.ui.common.SectionCard
import dev.thoremutuner.app.ui.common.ThorButton
import dev.thoremutuner.app.ui.common.focusRing
import dev.thoremutuner.app.ui.common.initialFocus
import dev.thoremutuner.app.ui.theme.OkColor
import dev.thoremutuner.app.vm.CompareViewModel
import dev.thoremutuner.app.vm.HistoryViewModel
import dev.thoremutuner.core.bench.ComparisonRow
import dev.thoremutuner.core.bench.Side
import dev.thoremutuner.core.bench.TestSession
import java.util.Locale

@Composable
fun HistoryScreen(container: AppContainer, key: String, onBack: () -> Unit, openCompare: (String, String) -> Unit) {
    val vm = viewModel(key = "history-$key") { HistoryViewModel(container, key) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    var deleting by remember { mutableStateOf<TestSession?>(null) }
    ScreenScaffold(
        "History" + (ui.game?.let { " · ${it.title}" } ?: ""),
        onBack = onBack,
        actions = {
            ThorButton("Compare", { if (ui.selected.size == 2) openCompare(ui.selected[0], ui.selected[1]) }, enabled = ui.selected.size == 2)
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = pad, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("Select two sessions to compare them A/B.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (ui.loaded && ui.groups.isEmpty()) item { EmptyState("No test sessions yet. Run a test from the game screen.") }
            val firstId = ui.groups.firstOrNull()?.second?.firstOrNull()?.id
            ui.groups.forEach { (rev, sessions) ->
                item { Text("Revision $rev", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp)) }
                items(sessions, key = { it.id }) { s ->
                    SessionRow(
                        s, ui.emulatorNames[s.emulatorId] ?: s.emulatorId, selected = s.id in ui.selected,
                        onToggle = { vm.toggle(s.id) }, onDelete = { deleting = s },
                        modifier = if (s.id == firstId) Modifier.initialFocus() else Modifier,
                    )
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
    deleting?.let { s ->
        ConfirmDialog("Delete session?", "Session from ${Fmt.date(s.startedAt)} will be deleted.", "Delete",
            onConfirm = { vm.delete(s.id); deleting = null }, onDismiss = { deleting = null })
    }
}

@Composable
private fun SessionRow(s: TestSession, emulator: String, selected: Boolean, onToggle: () -> Unit, onDelete: () -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(14.dp)
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = shape, modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 8.dp)) {
            Row(
                Modifier.weight(1f).focusRing(shape).clickable(onClick = onToggle).heightIn(min = 56.dp).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = selected, onCheckedChange = null)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("rev ${s.rev} · ${s.presetName ?: "custom"} · ${Fmt.date(s.startedAt)}", fontWeight = FontWeight.Medium)
                    val r = s.result
                    val m = s.metrics
                    Text(
                        "${Fmt.num(r?.avgFps)}/${r?.targetFps ?: "-"} fps · ${Fmt.watts(m?.avgW)} · max ${Fmt.celsius(m?.tempMaxC)} · ${r?.outcome?.label ?: "no result"}",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text("$emulator ${s.emulatorVersion ?: ""} · ${s.perfMode.ifEmpty { "mode ?" }} / ${s.fanMode.ifEmpty { "fan ?" }}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
            }
            IconButton(onClick = onDelete, modifier = Modifier.focusRing(RoundedCornerShape(24.dp))) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete session")
            }
        }
    }
}

@Composable
fun CompareScreen(container: AppContainer, key: String, a: String, b: String, onBack: () -> Unit) {
    val vm = viewModel(key = "compare-$key-$a-$b") { CompareViewModel(container, key, a, b) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    ScreenScaffold("Compare A/B", onBack = onBack) { pad ->
        val result = ui.result
        val sa = ui.a
        val sb = ui.b
        if (result == null || sa == null || sb == null) {
            if (ui.loaded) EmptyState("Pick two sessions of this game in History.")
            return@ScreenScaffold
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(pad), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (result.notComparableReasons.isNotEmpty()) {
                Banner("Not comparable: " + result.notComparableReasons.joinToString("; "), BannerKind.WARN)
            }
            SectionCard(modifier = Modifier.initialFocus(), focusable = true) {
                LabeledValue("A", "rev ${sa.rev} · ${Fmt.date(sa.startedAt)}")
                LabeledValue("B", "rev ${sb.rev} · ${Fmt.date(sb.startedAt)}")
                val w = result.winner
                Text(
                    if (w == null) "No clear winner (differences are within noise)."
                    else "Better: ${if (w == Side.A) "A" else "B"} (decided by ${result.decidedBy})",
                    style = MaterialTheme.typography.titleMedium, color = if (w == null) MaterialTheme.colorScheme.onSurface else OkColor,
                )
            }
            SectionCard("Metrics", focusable = true) {
                Row(Modifier.fillMaxWidth()) {
                    Text("", modifier = Modifier.weight(0.4f))
                    Text("A", modifier = Modifier.weight(0.2f), fontWeight = FontWeight.Bold)
                    Text("B", modifier = Modifier.weight(0.2f), fontWeight = FontWeight.Bold)
                    Text("Δ", modifier = Modifier.weight(0.2f), fontWeight = FontWeight.Bold)
                }
                result.rows.forEach { MetricRow(it) }
                LabeledValue("Outcome", "${sa.result?.outcome?.label ?: "-"} vs ${sb.result?.outcome?.label ?: "-"}")
                LabeledValue("Emulator version", "${sa.emulatorVersion ?: "?"} vs ${sb.emulatorVersion ?: "?"}")
                LabeledValue("Modes", "${sa.perfMode}/${sa.fanMode} vs ${sb.perfMode}/${sb.fanMode}")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun MetricRow(r: ComparisonRow) {
    fun f(v: Double?) = v?.let { String.format(Locale.US, if (kotlin.math.abs(it) >= 100) "%.0f" else "%.2f", it) } ?: "-"
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(r.label + if (r.unit.isNotEmpty()) " (${r.unit})" else "", modifier = Modifier.weight(0.4f), style = MaterialTheme.typography.bodyMedium)
        Text(f(r.a), modifier = Modifier.weight(0.2f), color = if (r.better == Side.A) OkColor else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (r.better == Side.A) FontWeight.Bold else FontWeight.Normal)
        Text(f(r.b), modifier = Modifier.weight(0.2f), color = if (r.better == Side.B) OkColor else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (r.better == Side.B) FontWeight.Bold else FontWeight.Normal)
        Text(r.delta?.let { (if (it > 0) "+" else "") + f(it) } ?: "-", modifier = Modifier.weight(0.2f), style = MaterialTheme.typography.bodyMedium)
    }
}
