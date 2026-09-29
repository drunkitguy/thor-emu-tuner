package dev.thoremutuner.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.thoremutuner.app.AppContainer
import dev.thoremutuner.app.ui.common.Banner
import dev.thoremutuner.app.ui.common.BannerKind
import dev.thoremutuner.app.ui.common.ButtonStyle
import dev.thoremutuner.app.ui.common.ChoiceDialog
import dev.thoremutuner.app.ui.common.ConfirmDialog
import dev.thoremutuner.app.ui.common.LabeledValue
import dev.thoremutuner.app.ui.common.ScreenScaffold
import dev.thoremutuner.app.ui.common.SectionCard
import dev.thoremutuner.app.ui.common.ThorButton
import dev.thoremutuner.app.ui.common.focusRing
import dev.thoremutuner.app.ui.common.launchSafely
import dev.thoremutuner.app.ui.common.rememberInitialFocus
import dev.thoremutuner.app.ui.theme.MonoStyle
import dev.thoremutuner.app.ui.theme.OkColor
import dev.thoremutuner.app.vm.SettingsViewModel
import dev.thoremutuner.core.model.SystemId
import dev.thoremutuner.core.preset.EmulatorDef
import dev.thoremutuner.core.text.TextBlocks

@Composable
fun SettingsScreen(container: AppContainer, onBack: () -> Unit, openAbout: () -> Unit, rerunOnboarding: () -> Unit) {
    val vm = viewModel { SettingsViewModel(container) }
    val settings by vm.settings.collectAsStateWithLifecycle()
    val statuses by vm.statuses.collectAsStateWithLifecycle()
    val romAccess by vm.romAccess.collectAsStateWithLifecycle()
    val packages by vm.packages.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var pendingGrant by remember { mutableStateOf<EmulatorDef?>(null) }
    var packagePick by remember { mutableStateOf<String?>(null) }
    var corePick by remember { mutableStateOf<String?>(null) }
    var confirmRestore by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val firstFocus = rememberInitialFocus()

    val romPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) vm.addRomFolder(uri) }
    val emuPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val def = pendingGrant
        if (uri != null && def != null) vm.grant(def, uri)
        pendingGrant = null
    }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> if (uri != null) vm.exportData(uri) }

    ScreenScaffold("Settings", onBack = onBack, scrollState = listState) { pad ->
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = pad, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            message?.let { (text, error) ->
                item { Banner(text, if (error) BannerKind.ERROR else BannerKind.OK, actionLabel = "OK", onAction = vm::dismissMessage) }
            }
            item {
                SectionCard("ROM folders") {
                    val folders = settings?.romFolders.orEmpty()
                    if (folders.isEmpty()) Text("No ROM folders yet.")
                    folders.forEach { f ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(f.label, style = MaterialTheme.typography.titleSmall)
                                if (romAccess[f.treeUri] == false) Text("Access lost: remove and add it again.", color = MaterialTheme.colorScheme.error)
                            }
                            if (romAccess[f.treeUri] == false) ThorButton("Re-grant", { romPicker.launchSafely(null, context) }, style = ButtonStyle.SECONDARY)
                            ThorButton("Remove", { vm.removeRomFolder(f.treeUri) }, icon = Icons.Filled.Delete, style = ButtonStyle.TEXT)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThorButton("Add folder", { romPicker.launchSafely(null, context) }, icon = Icons.Filled.Add, modifier = Modifier.focusRequester(firstFocus))
                        ThorButton("Rescan all", { vm.rescan(force = true) }, icon = Icons.Filled.Refresh, style = ButtonStyle.SECONDARY)
                    }
                }
            }
            item {
                SectionCard("Emulator config folders") {
                    Text("Needed to write per-game settings. Eden needs none; reference-only emulators are never written.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(vm.folderEmulators, key = { it.emulatorId }) { def ->
                val st = statuses[def.emulatorId]
                SectionCard(def.name) {
                    Text(def.configTarget.folderToGrant.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                    if (def.emulatorId == "dolphin") Text("In the picker, open the menu (≡) and choose Dolphin.", color = MaterialTheme.colorScheme.primary)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThorButton(if (st?.granted == true) (if (st.ok) "Change" else "Re-grant") else "Grant", { pendingGrant = def; emuPicker.launchSafely(null, context) }, style = ButtonStyle.SECONDARY)
                        when {
                            st == null -> Unit
                            st.ok -> Text("✓ ${st.label ?: "Ready"}", color = OkColor)
                            st.granted -> Text(st.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                            else -> Text("Not granted", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (packages.isNotEmpty()) {
                item {
                    SectionCard("Preferred emulator builds") {
                        packages.forEach { p ->
                            val cur = settings?.preferredPackage?.get(p.def.emulatorId) ?: p.installed.first()
                            LabeledValue(p.def.name, p.def.packages.firstOrNull { it.packageName == cur }?.label ?: cur)
                            ThorButton("Change ${p.def.name} build", { packagePick = p.def.emulatorId }, style = ButtonStyle.SECONDARY)
                        }
                    }
                }
            }
            vm.retroarch?.let { ra ->
                item {
                    SectionCard("RetroArch cores") {
                        val systems = ra.systems.mapNotNull { SystemId.fromId(it) }.distinct()
                            .filter { s -> ra.configTarget.cores.count { c -> c.systems.any { it in s.jsonIds } } > 1 }
                        ra.systems.mapNotNull { SystemId.fromId(it) }.distinct().forEach { s ->
                            val core = dev.thoremutuner.core.config.ConfigWriters.coreFor(ra, s.jsonIds, settings?.preferredCore?.get(s.id))
                            LabeledValue(s.displayName, core?.libraryName ?: "-")
                        }
                        systems.forEach { s -> ThorButton("Change ${s.displayName} core", { corePick = s.id }, style = ButtonStyle.SECONDARY) }
                        Text("The core must already be installed inside RetroArch.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                SectionCard("Data") {
                    ThorButton("Export data (JSON)", { exportPicker.launchSafely("thor-emu-tuner-export.json", context) }, icon = Icons.Filled.Share, style = ButtonStyle.SECONDARY)
                    Text("Exports titles, IDs, profiles and test sessions. Never folder locations, file paths or device identifiers.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    ThorButton("Restore my Azahar settings", { confirmRestore = true }, style = ButtonStyle.SECONDARY)
                    ThorButton("Run setup again", { vm.rerunOnboarding(rerunOnboarding) }, style = ButtonStyle.TEXT)
                }
            }
            item {
                SectionCard("About") {
                    ThorButton("About, sources and licenses", openAbout, icon = Icons.Filled.Info, style = ButtonStyle.SECONDARY)
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
    packagePick?.let { emu ->
        val p = packages.firstOrNull { it.def.emulatorId == emu }
        if (p != null) {
            ChoiceDialog("${p.def.name} build", p.installed.map { pkg -> pkg to (p.def.packages.firstOrNull { it.packageName == pkg }?.label ?: pkg) },
                settings?.preferredPackage?.get(emu), { vm.setPreferredPackage(emu, it); packagePick = null }, { packagePick = null })
        }
    }
    corePick?.let { sysId ->
        val ra = vm.retroarch
        val s = SystemId.fromId(sysId)
        if (ra != null && s != null) {
            val cores = ra.configTarget.cores.filter { c -> c.systems.any { it in s.jsonIds } }
            ChoiceDialog("${s.displayName} core", cores.map { it.coreFile to it.libraryName }, settings?.preferredCore?.get(sysId),
                { vm.setPreferredCore(sysId, it); corePick = null }, { corePick = null })
        }
    }
    if (confirmRestore) {
        ConfirmDialog(
            "Restore Azahar settings?",
            "Writes back the original values of every key Thor Emu Tuner changed in Azahar's config.ini. Close Azahar first.",
            "Restore",
            onConfirm = { vm.restoreAzahar(); confirmRestore = false },
            onDismiss = { confirmRestore = false },
        )
    }
}

@Composable
fun AboutScreen(container: AppContainer, onBack: () -> Unit) {
    val vm = viewModel { SettingsViewModel(container) }
    val sections by vm.about.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadAbout() }
    val listState = rememberLazyListState()
    val first = rememberInitialFocus()
    ScreenScaffold("About & sources", onBack = onBack, scrollState = listState) { pad ->
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = pad, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                SectionCard("Thor Emu Tuner ${container.appVersion}", modifier = Modifier.focusRequester(first), focusable = true) {
                    Text("MIT License, copyright Thor Emu Tuner contributors. Third-party libraries and their licenses are listed below.")
                    Text("No internet permission. No analytics. Your data stays on this device unless you export it.")
                    Text("Presets are starting guesses unless their badge says otherwise; verify them with your own test sessions.")
                }
            }
            sections.orEmpty().forEach { section ->
                item { Text(section.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp)) }
                // Focusable blocks of a few lines each, so the D-pad scrolls through long texts.
                items(TextBlocks.split(section.text)) { block -> FocusableText(block) }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
fun FocusableText(text: String) {
    val shape = RoundedCornerShape(8.dp)
    Text(
        text,
        style = MonoStyle,
        modifier = Modifier.fillMaxWidth().focusRing(shape).focusable().padding(horizontal = 8.dp, vertical = 4.dp),
    )
}
