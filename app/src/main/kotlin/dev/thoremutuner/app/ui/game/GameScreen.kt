@file:OptIn(ExperimentalMaterial3Api::class)

package dev.thoremutuner.app.ui.game

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.thoremutuner.app.AppContainer
import dev.thoremutuner.app.emu.EmulatorLauncher
import dev.thoremutuner.app.ui.common.Banner
import dev.thoremutuner.app.ui.common.BannerKind
import dev.thoremutuner.app.ui.common.ButtonGrid
import dev.thoremutuner.app.ui.common.ButtonStyle
import dev.thoremutuner.app.ui.common.EmptyState
import dev.thoremutuner.app.ui.common.Fmt
import dev.thoremutuner.app.ui.common.GridAction
import dev.thoremutuner.app.ui.common.LabeledValue
import dev.thoremutuner.app.ui.common.ScreenScaffold
import dev.thoremutuner.app.ui.common.SectionCard
import dev.thoremutuner.app.ui.common.Tag
import dev.thoremutuner.app.ui.common.TextInputDialog
import dev.thoremutuner.app.ui.common.ThorButton
import dev.thoremutuner.app.ui.common.focusRing
import dev.thoremutuner.app.ui.theme.OkColor
import dev.thoremutuner.app.ui.theme.WarnColor
import dev.thoremutuner.app.vm.GameViewModel
import dev.thoremutuner.core.model.IdValidation

@Composable
fun GameScreen(
    container: AppContainer,
    key: String,
    onBack: () -> Unit,
    openBaseline: (String) -> Unit,
    openTweak: (String) -> Unit,
    openApply: (String) -> Unit,
    openTest: (String) -> Unit,
    openHistory: () -> Unit,
) {
    val vm = viewModel(key = "game-$key") { GameViewModel(container, key) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var editId by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }

    val scroll = rememberScrollState()
    ScreenScaffold(title = ui.game?.title ?: "Game", onBack = onBack, scrollState = scroll) { pad ->
        if (ui.notFound) {
            EmptyState("This game is no longer in the library. Rescan or go back.")
            return@ScreenScaffold
        }
        val game = ui.game ?: return@ScreenScaffold
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll).padding(pad),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ui.message?.let { m ->
                Banner(
                    m.text, if (m.error) BannerKind.ERROR else BannerKind.WARN,
                    actionLabel = if (m.appInfoPackage != null) "App info" else "OK",
                    onAction = { m.appInfoPackage?.let { EmulatorLauncher.openAppInfo(context, it) } ?: vm.dismissMessage() },
                )
            }
            val sel = ui.selected
            val emuId = sel?.def?.emulatorId
            ButtonGrid(
                listOf(
                    GridAction("Launch", { vm.requestLaunch(context) }, Icons.Filled.PlayArrow, enabled = sel?.installed == true && !ui.launching, style = ButtonStyle.PRIMARY, focusFirst = true),
                    GridAction("Choose baseline", { emuId?.let(openBaseline) }, Icons.Filled.Star, enabled = sel != null),
                    GridAction(if (sel?.def?.isFull == false) "Manual settings" else "Tweak", { emuId?.let(openTweak) }, Icons.Filled.Edit, enabled = sel != null),
                    GridAction(if (sel?.def?.isFull == false) "Checklist" else "Apply", { emuId?.let(openApply) }, Icons.Filled.Check, enabled = sel != null),
                    GridAction("Run test", { emuId?.let(openTest) }, Icons.Filled.DateRange, enabled = sel?.installed == true),
                    GridAction("History (${ui.sessionCount})", openHistory, Icons.AutoMirrored.Filled.List),
                ),
            )

            SectionCard("Game") {
                LabeledValue("System", game.system.displayName)
                LabeledValue("File", game.fileName)
                val id = game.id
                LabeledValue(IdValidation.kindFor(game.system).label, id?.let { "${it.value} (${Fmt.method(it.method)})" } ?: "Unknown")
                id?.extra?.get("productCode")?.let { LabeledValue("Product code", it) }
                id?.extra?.get("headerTitle")?.let { LabeledValue("Header title", it) }
                game.scanError?.let { Text("Could not read this file: $it", color = MaterialTheme.colorScheme.error) }
                ThorButton("Edit ID", { editId = true }, icon = Icons.Filled.Edit, style = ButtonStyle.SECONDARY)
            }

            SectionCard("Emulator") {
                if (ui.choices.isEmpty()) {
                    Text("No supported emulator for this system.")
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(ui.choices) { ch ->
                        FilterChip(
                            selected = ch.def.emulatorId == emuId,
                            onClick = { vm.selectEmulator(ch.def.emulatorId) },
                            label = { Text(ch.def.name + if (!ch.installed) " (not installed)" else "") },
                            modifier = Modifier.focusRing(RoundedCornerShape(8.dp)),
                        )
                    }
                }
                if (sel != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Tag(if (sel.def.isFull) "Writes settings" else "Reference only (checklist)", if (sel.def.isFull) OkColor else WarnColor)
                        Text(if (sel.installed) "Installed ${sel.versionName ?: ""}" else "Not installed", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (!sel.installed) Text("Install ${sel.def.name} to launch or test this game. You can still prepare a profile.", color = WarnColor)
                    if (ui.cores.size > 1) {
                        Text("RetroArch core")
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(ui.cores) { name ->
                                FilterChip(selected = name == ui.coreName, onClick = { vm.selectCore(name) }, label = { Text(name) },
                                    modifier = Modifier.focusRing(RoundedCornerShape(8.dp)))
                            }
                        }
                    } else if (ui.coreName != null) {
                        LabeledValue("RetroArch core", ui.coreName!!)
                    }
                }
            }

            SectionCard("Current profile", focusable = true) {
                val rev = ui.latest
                if (rev == null) {
                    Text("No profile yet. Choose a baseline to start.")
                } else {
                    LabeledValue("Revision", "rev ${rev.rev}")
                    LabeledValue("Baseline", ui.basePresetName ?: "None")
                    LabeledValue("Changes from baseline", ui.changesFromBaseline.toString())
                    if (rev.note.isNotBlank()) LabeledValue("Note", rev.note)
                    val applied = rev.appliedAt?.let { "Applied ${Fmt.date(it)}${rev.appliedEmulatorVersion?.let { v -> " (v$v)" } ?: ""}" }
                    LabeledValue("Status", when {
                        sel?.def?.isFull == false -> "Manual checklist (nothing is written)"
                        ui.needsApply -> "Not applied yet (Launch applies it)"
                        applied != null -> applied
                        else -> "Sent with each launch"
                    })
                }
            }

            if (ui.fixes.isNotEmpty()) {
                SectionCard("Known fixes for this game", focusable = true) {
                    ui.fixes.forEach { fix ->
                        Text(fix.title, style = MaterialTheme.typography.titleSmall)
                        fix.note?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        fix.values.forEach { v -> Text("• [${v.section}] ${v.key} = ${v.value}  (${v.description})") }
                    }
                }
            }
            sel?.def?.manualNotes?.takeIf { it.isNotEmpty() }?.let { notes ->
                SectionCard("Notes for ${sel.def.name}", focusable = true) { notes.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) } }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (editId) {
        val game = ui.game
        val kind = game?.let { IdValidation.kindFor(it.system) }
        TextInputDialog(
            title = "Game ID",
            initial = game?.id?.value.orEmpty(),
            label = kind?.label ?: "ID",
            supporting = kind?.let { IdValidation.hint(it) },
            validate = { vm.idError(it) },
            onConfirm = { vm.setManualId(it); editId = false },
            onDismiss = { editId = false },
        )
    }
    if (ui.showFolderHint) {
        AlertDialog(
            onDismissRequest = { vm.dismissHint() },
            title = { Text("One-time tip") },
            text = { Text("If the emulator says it cannot open the file, add this ROM folder inside the emulator too. Many emulators need their own access to your games folder.") },
            confirmButton = { ThorButton("Launch", { vm.confirmHintAndLaunch(context) }) },
            dismissButton = { ThorButton("Cancel", { vm.dismissHint() }, style = ButtonStyle.TEXT) },
        )
    }
}
