package dev.thoremutuner.app.ui.apply

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import dev.thoremutuner.app.ui.common.initialFocus
import dev.thoremutuner.app.ui.theme.MonoStyle
import dev.thoremutuner.app.ui.theme.OkColor
import dev.thoremutuner.app.vm.ApplyUi
import dev.thoremutuner.app.vm.ApplyViewModel
import dev.thoremutuner.core.preset.ConfigMode

@Composable
fun ApplyScreen(container: AppContainer, key: String, emulatorId: String, onBack: () -> Unit) {
    val vm = viewModel(key = "apply-$key-$emulatorId") { ApplyViewModel(container, key, emulatorId) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.setExportFolderAndExport(uri)
    }
    val def = ui.def
    ScreenScaffold(title = (if (def?.isFull == false) "Checklist" else "Apply") + (def?.let { " · ${it.name}" } ?: ""), onBack = onBack) { pad ->
        if (def == null) {
            EmptyState("Unknown emulator"); return@ScreenScaffold
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(pad),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (ui.busy) CircularProgressIndicator()
            ui.outcome?.let { OutcomeBanner(it) }
            if (!def.isFull) {
                ReferenceChecklist(ui, vm::toggleChecked)
                ThorButton("Copy checklist", { clipboard.setText(AnnotatedString(vm.copyText())) }, icon = Icons.Filled.Share, style = ButtonStyle.SECONDARY)
                return@Column
            }
            if (ui.revision == null) {
                Banner("No profile yet: choose a baseline first.", BannerKind.WARN)
                return@Column
            }
            val preview = ui.preview
            val block = preview?.block
            if (def.configTarget.mode == ConfigMode.GLOBAL_INI_MERGE) {
                Banner("Azahar has no per-game settings; Thor Emu Tuner swaps these keys in before each launch. Close Azahar first. \"Restore my Azahar settings\" is in Settings.", BannerKind.INFO)
            }
            if (def.configTarget.mode == ConfigMode.INTENT_INLINE_INI) {
                Banner("Will be sent when you press Launch. Eden asks for confirmation and may ask before overwriting its per-game config.", BannerKind.INFO)
            }
            if (block != null && block.block != ApplyBlock.INLINE_AT_LAUNCH) {
                Banner(block.message, if (block.block == ApplyBlock.NO_FOLDER) BannerKind.WARN else BannerKind.ERROR)
            }
            SectionCard("Target") {
                LabeledValue("File", preview?.relativePath ?: "-")
                preview?.folderLabel?.let { LabeledValue("In folder", it) }
                LabeledValue("Revision", "rev ${ui.revision!!.rev}")
            }
            val canWrite = block == null && preview?.render != null
            if (def.configTarget.mode != ConfigMode.INTENT_INLINE_INI) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThorButton("Write", vm::write, enabled = canWrite && !ui.busy, icon = Icons.Filled.Check, modifier = Modifier.initialFocus())
                    ThorButton(
                        "Export instead",
                        { if (ui.hasExportFolder) vm.export() else exportPicker.launch(null) },
                        enabled = !ui.busy && preview?.render != null,
                        style = ButtonStyle.SECONDARY,
                    )
                    ThorButton("Copy text", { clipboard.setText(AnnotatedString(vm.copyText())) }, style = ButtonStyle.TEXT, enabled = preview?.render != null)
                }
                if (ui.hasExportFolder) ThorButton("Pick another export folder", { exportPicker.launch(null) }, style = ButtonStyle.TEXT)
            } else {
                ThorButton("Copy text", { clipboard.setText(AnnotatedString(vm.copyText())) }, style = ButtonStyle.SECONDARY, modifier = Modifier.initialFocus())
            }
            preview?.render?.let { r ->
                if (preview.diff.isNotEmpty()) {
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
                if (r.refused.isNotEmpty()) {
                    SectionCard("Not written") {
                        r.refused.forEach { Text("• ${it.value.section}/${it.value.key}: ${it.reason}", style = MaterialTheme.typography.bodyMedium) }
                    }
                }
                r.warnings.forEach { Banner(it, BannerKind.WARN) }
                SectionCard("File preview", focusable = true) {
                    Text(r.text.ifEmpty { "(empty)" }, style = MonoStyle, modifier = Modifier.horizontalScroll(rememberScrollState()))
                }
            }
            Spacer(Modifier.height(24.dp))
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
private fun ReferenceChecklist(ui: ApplyUi, toggle: (Int) -> Unit) {
    Banner("${ui.def!!.name} is reference-only: set these inside the emulator. Nothing is written by this app.", BannerKind.INFO)
    val values = ui.revision?.values.orEmpty()
    if (values.isEmpty()) {
        EmptyState("No settings yet. Choose a baseline or add manual settings.")
        return
    }
    values.forEachIndexed { i, v ->
        Row(
            Modifier.fillMaxWidth().focusRing().clickable { toggle(i) }.heightIn(min = 56.dp).padding(horizontal = 8.dp)
                .let { if (i == 0) it.initialFocus() else it },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = i in ui.checked, onCheckedChange = null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(v.section, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                Text("${v.key}: ${v.value}", color = if (i in ui.checked) OkColor else MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}
