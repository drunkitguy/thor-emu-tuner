package dev.thoremutuner.app.ui.apply

import dev.thoremutuner.core.profile.SettingValue
import dev.thoremutuner.app.ui.settings.textBlocks
import dev.thoremutuner.app.ui.settings.FocusableText
import dev.thoremutuner.app.ui.common.rememberInitialFocus
import dev.thoremutuner.app.ui.common.launchSafely
import dev.thoremutuner.app.ui.common.GridAction
import dev.thoremutuner.app.ui.common.ButtonGrid
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.thoremutuner.app.AppContainer
import dev.thoremutuner.app.apply.ApplyBlock
import dev.thoremutuner.app.apply.ApplyOutcome
import dev.thoremutuner.app.ui.common.Banner
import dev.thoremutuner.app.ui.common.BannerKind
import dev.thoremutuner.app.ui.common.ButtonStyle
import dev.thoremutuner.app.ui.common.EmptyState
import dev.thoremutuner.app.ui.common.LabeledValue
import dev.thoremutuner.app.ui.common.ScreenScaffold
import dev.thoremutuner.app.ui.common.SectionCard
import dev.thoremutuner.app.ui.common.ThorButton
import dev.thoremutuner.app.ui.common.focusRing
import dev.thoremutuner.app.ui.theme.OkColor
import dev.thoremutuner.app.vm.ApplyViewModel
import dev.thoremutuner.core.preset.ConfigMode

@Composable
fun ApplyScreen(container: AppContainer, key: String, emulatorId: String, onBack: () -> Unit) {
    val vm = viewModel(key = "apply-$key-$emulatorId") { ApplyViewModel(container, key, emulatorId) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.setExportFolderAndExport(uri)
    }
    val def = ui.def
    val listState = rememberLazyListState()
    val loaded = ui.game != null && !ui.busy
    val firstFocus = rememberInitialFocus(ready = loaded)
    ScreenScaffold(
        title = (if (def?.isFull == false) "Checklist" else "Apply") + (def?.let { " · ${it.name}" } ?: ""),
        onBack = onBack,
        scrollState = listState,
    ) { pad ->
        if (def == null) {
            EmptyState("Unknown emulator"); return@ScreenScaffold
        }
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = pad, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (ui.busy) item { CircularProgressIndicator() }
            ui.outcome?.let { o -> item { OutcomeBanner(o) } }
            if (!def.isFull) {
                item { Banner("${def.name} is reference-only: set these inside the emulator. Nothing is written by this app.", BannerKind.INFO) }
                val values = ui.revision?.values.orEmpty()
                if (values.isEmpty()) item { EmptyState("No settings yet. Choose a baseline or add manual settings.") }
                itemsIndexed(values) { i, v ->
                    ChecklistRow(i, v, i in ui.checked, { vm.toggleChecked(i) }, if (i == 0) Modifier.focusRequester(firstFocus) else Modifier)
                }
                item {
                    ThorButton(
                        "Copy checklist", { clipboard.setText(AnnotatedString(vm.copyText())) },
                        icon = Icons.Filled.Share, style = ButtonStyle.SECONDARY,
                        modifier = if (values.isEmpty()) Modifier.focusRequester(firstFocus) else Modifier,
                    )
                }
                return@LazyColumn
            }
            if (ui.revision == null) {
                item { Banner("No profile yet: choose a baseline first.", BannerKind.WARN) }
                return@LazyColumn
            }
            val preview = ui.preview
            val block = preview?.block
            val inline = def.configTarget.mode == ConfigMode.INTENT_INLINE_INI
            // Actions first (as a grid, so they fit the narrow bottom screen), then details.
            item {
                val actions = if (inline) {
                    listOf(GridAction("Copy text", { clipboard.setText(AnnotatedString(vm.copyText())) }, Icons.Filled.Share, enabled = preview?.render != null))
                } else {
                    listOfNotNull(
                        GridAction("Write", { vm.write() }, Icons.Filled.Check, enabled = block == null && preview?.render != null && !ui.busy, style = ButtonStyle.PRIMARY),
                        GridAction(
                            "Export instead",
                            { if (ui.hasExportFolder) vm.export() else exportPicker.launchSafely(null, context) },
                            enabled = !ui.busy && preview?.render != null,
                        ),
                        GridAction("Copy text", { clipboard.setText(AnnotatedString(vm.copyText())) }, Icons.Filled.Share, enabled = preview?.render != null),
                        if (ui.hasExportFolder) GridAction("Other export folder", { exportPicker.launchSafely(null, context) }) else null,
                    )
                }
                // Initial focus goes to the first enabled action.
                ButtonGrid(actions.mapIndexed { i, a -> if (i == actions.indexOfFirst { it.enabled }) a.copy(modifier = Modifier.focusRequester(firstFocus)) else a })
            }
            if (def.configTarget.mode == ConfigMode.GLOBAL_INI_MERGE) {
                item { Banner("Azahar has no per-game settings; Thor Emu Tuner swaps these keys in before each launch. Close Azahar first. \"Restore my Azahar settings\" is in Settings.", BannerKind.INFO) }
            }
            if (inline) {
                item { Banner("Will be sent when you press Launch. Eden asks for confirmation and may ask before overwriting its per-game config.", BannerKind.INFO) }
            }
            if (block != null && block.block != ApplyBlock.INLINE_AT_LAUNCH) {
                item { Banner(block.message, if (block.block == ApplyBlock.NO_FOLDER) BannerKind.WARN else BannerKind.ERROR) }
            }
            item {
                SectionCard("Target", focusable = true) {
                    LabeledValue("File", preview?.relativePath ?: "-")
                    preview?.folderLabel?.let { LabeledValue("In folder", it) }
                    LabeledValue("Revision", "rev ${ui.revision!!.rev}")
                }
            }
            val r = preview?.render ?: return@LazyColumn
            if (preview.diff.isNotEmpty()) {
                item {
                    SectionCard("Changes (old → new)", focusable = true) {
                        preview.diff.forEach { d ->
                            val label = def.setting(d.section, d.key)?.label ?: d.key
                            Text(
                                "$label: ${d.old ?: "(not set)"} → ${d.new}",
                                color = if (d.changed) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
            if (r.refused.isNotEmpty()) {
                item {
                    SectionCard("Not written", focusable = true) {
                        r.refused.forEach { Text("• ${it.value.section}/${it.value.key}: ${it.reason}", style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
            items(r.warnings) { Banner(it, BannerKind.WARN) }
            item { Text("File preview", style = MaterialTheme.typography.titleMedium) }
            // Wrapped lines in focusable blocks: no horizontal scrolling needed, D-pad reaches everything.
            items(textBlocks(r.text.ifEmpty { "(empty)" })) { FocusableText(it) }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun OutcomeBanner(o: ApplyOutcome) {
    when (o) {
        is ApplyOutcome.Written -> Banner(
            "Written and verified: ${o.relativePath}" + (o.backupStamp?.let { ". Previous file backed up ($it)." } ?: " (new file)."),
            BannerKind.OK,
        )
        is ApplyOutcome.Exported -> Banner("Exported ${o.relativePath}. ${o.instructions}", BannerKind.OK)
        is ApplyOutcome.Blocked -> Banner(o.message, BannerKind.WARN)
        is ApplyOutcome.Failed -> Banner(o.message, BannerKind.ERROR)
    }
}

@Composable
private fun ChecklistRow(i: Int, v: SettingValue, checked: Boolean, toggle: () -> Unit, modifier: Modifier) {
    // Order: focus requester, focus ring, then the clickable (focus target).
    Row(
        modifier.fillMaxWidth().focusRing().clickable(onClick = toggle).heightIn(min = 56.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(v.section, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            Text("${v.key}: ${v.value}", color = if (checked) OkColor else MaterialTheme.colorScheme.onSurface)
        }
    }
}
