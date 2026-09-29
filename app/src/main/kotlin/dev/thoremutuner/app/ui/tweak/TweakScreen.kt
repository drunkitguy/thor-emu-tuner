package dev.thoremutuner.app.ui.tweak

import dev.thoremutuner.app.ui.common.ConfirmDialog
import dev.thoremutuner.app.ui.common.rememberInitialFocus
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.thoremutuner.app.AppContainer
import dev.thoremutuner.app.ui.common.Banner
import dev.thoremutuner.app.ui.common.BannerKind
import dev.thoremutuner.app.ui.common.ButtonStyle
import dev.thoremutuner.app.ui.common.ChoiceDialog
import dev.thoremutuner.app.ui.common.EmptyState
import dev.thoremutuner.app.ui.common.Fmt
import dev.thoremutuner.app.ui.common.ScreenScaffold
import dev.thoremutuner.app.ui.common.SectionCard
import dev.thoremutuner.app.ui.common.Tag
import dev.thoremutuner.app.ui.common.TextInputDialog
import dev.thoremutuner.app.ui.common.ThorButton
import dev.thoremutuner.app.ui.common.focusRing
import dev.thoremutuner.app.ui.theme.ErrorColor
import dev.thoremutuner.app.ui.theme.WarnColor
import dev.thoremutuner.app.vm.ManualRow
import dev.thoremutuner.app.vm.TweakUi
import dev.thoremutuner.app.vm.TweakViewModel
import dev.thoremutuner.core.preset.Impact
import dev.thoremutuner.core.preset.PresetResolver
import dev.thoremutuner.core.preset.SettingDef
import dev.thoremutuner.core.preset.SettingType
import dev.thoremutuner.core.profile.SettingValue

@Composable
fun TweakScreen(container: AppContainer, key: String, emulatorId: String, onBack: () -> Unit) {
    val vm = viewModel(key = "tweak-$key-$emulatorId") { TweakViewModel(container, key, emulatorId) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    var askNote by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    LaunchedEffect(ui.saved) { if (ui.saved) onBack() }
    val def = ui.def
    // B / system back and the top-bar back never drop unsaved edits silently.
    val back: () -> Unit = { if (ui.dirty) confirmDiscard = true else onBack() }
    BackHandler(enabled = ui.dirty) { confirmDiscard = true }
    val listState = rememberLazyListState()
    val firstFocus = rememberInitialFocus(ready = ui.loaded)
    ScreenScaffold(
        title = if (def?.isFull == false) "Manual settings · ${def.name}" else "Tweak" + (def?.let { " · ${it.name}" } ?: ""),
        onBack = back,
        scrollState = listState,
        actions = { ThorButton("Save", { askNote = true }, enabled = ui.loaded && ui.errors.isEmpty()) },
    ) { pad ->
        if (def == null) {
            EmptyState("Unknown emulator"); return@ScreenScaffold
        }
        if (!ui.loaded) return@ScreenScaffold
        if (def.isFull) FullEditor(vm, ui, pad, listState, firstFocus) else ManualEditor(vm, ui, pad, listState, firstFocus)
    }
    if (confirmDiscard) {
        ConfirmDialog(
            "Discard unsaved changes?",
            "Your edits have not been saved as a revision.",
            "Discard",
            onConfirm = { confirmDiscard = false; onBack() },
            onDismiss = { confirmDiscard = false },
        )
    }
    if (askNote) {
        TextInputDialog(
            title = "Save as new revision",
            initial = "",
            label = "Note (what did you change and why?)",
            confirmLabel = "Save revision",
            validate = { if (it.length > 500) "Max 500 characters" else null },
            onConfirm = { vm.save(it); askNote = false },
            onDismiss = { askNote = false },
        )
    }
}

@Composable
private fun FullEditor(
    vm: TweakViewModel,
    ui: TweakUi,
    pad: androidx.compose.foundation.layout.PaddingValues,
    listState: LazyListState,
    firstFocus: FocusRequester,
) {
    val def = ui.def!!
    val visible = def.settings.filter { ui.showAdvanced || !it.advanced }
    val groups = visible.groupBy { it.group }
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = pad, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text(
                (ui.baseRev?.let { "Editing a copy of rev $it. " } ?: "No baseline chosen yet: only settings you set are written. ") +
                    "${ui.changedCount} change(s) from the baseline.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ui.saveError?.let { item { Banner(it, BannerKind.ERROR) } }
        item {
            Row(
                Modifier.fillMaxWidth().focusRing().clickable { vm.toggleAdvanced() }.heightIn(min = 48.dp).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Show advanced settings", modifier = Modifier.weight(1f))
                Switch(checked = ui.showAdvanced, onCheckedChange = null)
            }
        }
        groups.forEach { (group, settings) ->
            item { Text(group, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp)) }
            items(settings, key = { "${it.section}/${it.key}" }) { s ->
                SettingRow(vm, ui, s, firstModifier = if (s == visible.first()) Modifier.focusRequester(firstFocus) else Modifier)
            }
        }
        item { Spacer(Modifier.height(32.dp)) }
    }
}

@Composable
private fun SettingRow(vm: TweakViewModel, ui: TweakUi, s: SettingDef, firstModifier: Modifier) {
    val ref = SettingValue.refOf(s.section, s.key)
    val value = ui.value(s)
    val base = ui.baselineValue(s)
    val changed = value != base
    var choose by remember { mutableStateOf(false) }
    var edit by remember { mutableStateOf(false) }
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (changed) Box(Modifier.size(10.dp).background(MaterialTheme.colorScheme.secondary, CircleShape))
            Text(s.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Tag(Fmt.impact(s.impact), when (s.impact) { Impact.HIGH -> ErrorColor; Impact.MEDIUM -> WarnColor; Impact.LOW -> MaterialTheme.colorScheme.onSurfaceVariant })
        }
        Text(s.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (vm.isHack(s)) {
            Text("Dolphin's own game INIs set required hacks for some games. Only change this with a reason; it is written only because you set it by hand.", color = WarnColor)
        }
        if (value == null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Not managed: the emulator's own setting applies.", modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                ThorButton("Set", { vm.manage(s) }, style = ButtonStyle.SECONDARY, modifier = firstModifier)
            }
        } else {
            when (s.type) {
                SettingType.ENUM -> ThorButton(s.optionLabel(value) ?: value, { choose = true }, modifier = firstModifier, style = ButtonStyle.SECONDARY)
                SettingType.BOOL -> {
                    val on = PresetResolver.parseBool(value) == true
                    Row(
                        firstModifier.fillMaxWidth().focusRing().clickable { vm.setValue(s, (!on).toString()) }.heightIn(min = 48.dp).padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(if (on) "On" else "Off", modifier = Modifier.weight(1f))
                        Switch(checked = on, onCheckedChange = null)
                    }
                }
                SettingType.INT, SettingType.FLOAT -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThorButton("−", { vm.step(s, -1) }, modifier = firstModifier, style = ButtonStyle.SECONDARY)
                        Text(value, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 8.dp))
                        ThorButton("+", { vm.step(s, +1) }, style = ButtonStyle.SECONDARY)
                        ThorButton("Edit", { edit = true }, icon = Icons.Filled.Edit, style = ButtonStyle.TEXT)
                    }
                    val min = s.min
                    val max = s.max
                    val current = value.toDoubleOrNull()
                    if (min != null && max != null && max > min && current != null) {
                        val stepSize = s.step ?: 1.0
                        val steps = (((max - min) / stepSize).toInt() - 1).coerceIn(0, 200)
                        Slider(
                            value = current.toFloat().coerceIn(min.toFloat(), max.toFloat()),
                            onValueChange = { v ->
                                val snapped = min + Math.round((v - min) / stepSize) * stepSize
                                vm.setValue(s, if (s.type == SettingType.INT) snapped.toLong().toString() else PresetResolver.formatFloat(snapped))
                            },
                            valueRange = min.toFloat()..max.toFloat(),
                            steps = steps,
                            modifier = Modifier.fillMaxWidth().focusRing(),
                        )
                    }
                }
                SettingType.STRING -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(value.ifEmpty { "(empty)" }, modifier = Modifier.weight(1f))
                    ThorButton("Edit", { edit = true }, icon = Icons.Filled.Edit, style = ButtonStyle.SECONDARY, modifier = firstModifier)
                }
            }
            ui.errors[ref]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            ui.warnings[ref]?.let { Text(it, color = WarnColor) }
            ui.conflicts[ref]?.let { Text(it, color = WarnColor) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (changed) ThorButton(if (base != null) "Reset to baseline" else "Remove", { vm.reset(s) }, style = ButtonStyle.TEXT)
                if (base == null && !changed) ThorButton("Don't manage", { vm.unmanage(s) }, style = ButtonStyle.TEXT)
            }
        }
    }
    if (choose) {
        ChoiceDialog(
            title = s.label,
            options = s.options.map { it.value to it.label },
            selected = value,
            onSelect = { vm.setValue(s, it); choose = false },
            onDismiss = { choose = false },
        )
    }
    if (edit) {
        val d = ui.def!!
        TextInputDialog(
            title = s.label,
            initial = value.orEmpty(),
            label = when (s.type) {
                SettingType.INT -> "Whole number" + rangeText(s)
                SettingType.FLOAT -> "Number" + rangeText(s)
                else -> "Value"
            },
            validate = { raw -> (PresetResolver.validate(d, s, raw) as? dev.thoremutuner.core.preset.ValueCheck.Invalid)?.message },
            onConfirm = { vm.setValue(s, it); edit = false },
            onDismiss = { edit = false },
        )
    }
}

private fun rangeText(s: SettingDef): String =
    if (s.min != null || s.max != null) " (${s.min?.let { PresetResolver.formatNumber(it) } ?: "…"}-${s.max?.let { PresetResolver.formatNumber(it) } ?: "…"})" else ""

@Composable
private fun ManualEditor(
    vm: TweakViewModel,
    ui: TweakUi,
    pad: androidx.compose.foundation.layout.PaddingValues,
    listState: LazyListState,
    firstFocus: FocusRequester,
) {
    var editing by remember { mutableStateOf<ManualRow?>(null) }
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = pad, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Banner(
                "${ui.def!!.name} is reference-only: Thor Emu Tuner never writes its files. Keep a list of what you set inside the emulator; test sessions are linked to these revisions so you can compare them.",
                BannerKind.INFO,
            )
        }
        item { ThorButton("Add setting", { editing = vm.newManualRow() }, icon = Icons.Filled.Add, modifier = Modifier.focusRequester(firstFocus)) }
        if (ui.manual.isEmpty()) item { EmptyState("No settings yet. Choose a baseline or add one.") }
        items(ui.manual, key = { it.id }) { row ->
            SectionCard {
                if (row.path.isNotBlank()) Text(row.path, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                Text("${row.label} = ${row.value}", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThorButton("Edit", { editing = row }, icon = Icons.Filled.Edit, style = ButtonStyle.SECONDARY)
                    ThorButton("Delete", { vm.removeManual(row.id) }, icon = Icons.Filled.Delete, style = ButtonStyle.TEXT)
                }
            }
        }
        item { Spacer(Modifier.height(32.dp)) }
    }
    editing?.let { row -> ManualRowDialog(row, onSave = { vm.upsertManual(it); editing = null }, onDismiss = { editing = null }) }
}

@Composable
private fun ManualRowDialog(row: ManualRow, onSave: (ManualRow) -> Unit, onDismiss: () -> Unit) {
    var path by remember { mutableStateOf(row.path) }
    var label by remember { mutableStateOf(row.label) }
    var value by remember { mutableStateOf(row.value) }
    val valid = label.isNotBlank() && value.isNotBlank() && (path + label + value).length <= 300 &&
        listOf(path, label, value).none { it.contains('\n') }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (row.id < 0) "Add setting" else "Edit setting") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(path, { path = it }, label = { Text("Where (menu path)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRing(RoundedCornerShape(6.dp)))
                OutlinedTextField(label, { label = it }, label = { Text("Setting") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRing(RoundedCornerShape(6.dp)))
                OutlinedTextField(value, { value = it }, label = { Text("Value") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRing(RoundedCornerShape(6.dp)))
            }
        },
        confirmButton = { ThorButton("OK", { onSave(row.copy(path = path.trim(), label = label.trim(), value = value.trim())) }, enabled = valid) },
        dismissButton = { ThorButton("Cancel", onDismiss, style = ButtonStyle.TEXT) },
    )
}
