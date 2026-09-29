package dev.thoremutuner.app.ui.preset

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.thoremutuner.app.AppContainer
import dev.thoremutuner.app.ui.common.EmptyState
import dev.thoremutuner.app.ui.common.Fmt
import dev.thoremutuner.app.ui.common.ScreenScaffold
import dev.thoremutuner.app.ui.common.SectionCard
import dev.thoremutuner.app.ui.common.Tag
import dev.thoremutuner.app.ui.common.ThorButton
import dev.thoremutuner.app.ui.common.initialFocus
import dev.thoremutuner.app.ui.theme.OkColor
import dev.thoremutuner.app.ui.theme.WarnColor
import dev.thoremutuner.app.vm.BaselineViewModel
import dev.thoremutuner.core.preset.EmulatorDef
import dev.thoremutuner.core.preset.Evidence
import dev.thoremutuner.core.preset.PresetDef
import dev.thoremutuner.core.preset.PresetResolver

@Composable
fun BaselineScreen(container: AppContainer, key: String, emulatorId: String, onBack: () -> Unit) {
    val vm = viewModel(key = "baseline-$key-$emulatorId") { BaselineViewModel(container, key, emulatorId) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(ui.saved) { if (ui.saved) onBack() }
    val def = ui.def
    ScreenScaffold("Choose baseline" + (def?.let { " · ${it.name}" } ?: ""), onBack = onBack) { pad ->
        if (def == null) {
            EmptyState("Unknown emulator")
            return@ScreenScaffold
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = pad, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(
                    "Presets are starting points. Badges show how strong the evidence is; \"Starting guess\" means nobody has verified it on a Thor yet, so run a test session.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            itemsIndexed(def.presets) { i, p ->
                PresetCard(def, p, current = p.id == ui.currentPresetId, first = i == 0, onUse = { vm.use(p) })
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun PresetCard(def: EmulatorDef, preset: PresetDef, current: Boolean, first: Boolean, onUse: () -> Unit) {
    val evidence = PresetResolver.effectiveEvidence(preset)
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(preset.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Tag(Fmt.evidence(evidence), if (evidence == Evidence.INFERRED) WarnColor else OkColor)
            if (current) Tag("Current base")
        }
        Text(preset.description, style = MaterialTheme.typography.bodyMedium)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            preset.values.forEach { v ->
                val label: String
                val value: String
                if (def.isFull) {
                    val s = def.setting(v.section, v.key)
                    label = s?.label ?: v.key
                    value = s?.optionLabel(v.value) ?: v.value
                } else {
                    label = v.uiPath ?: v.key
                    value = v.uiValue ?: v.value
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.5f), style = MaterialTheme.typography.bodyMedium)
                    Text(value, modifier = Modifier.weight(0.5f), style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    (if (v.isInferred) "Why: " + (v.reason ?: "") else "Source: " + v.source),
                    color = MaterialTheme.colorScheme.outline,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        ThorButton("Use", onUse, modifier = if (first) Modifier.initialFocus() else Modifier)
    }
}
