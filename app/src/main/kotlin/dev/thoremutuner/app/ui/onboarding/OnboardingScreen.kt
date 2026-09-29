package dev.thoremutuner.app.ui.onboarding

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.thoremutuner.app.AppContainer
import dev.thoremutuner.app.ui.common.Banner
import dev.thoremutuner.app.ui.common.BannerKind
import dev.thoremutuner.app.ui.common.ButtonStyle
import dev.thoremutuner.app.ui.common.ScreenScaffold
import dev.thoremutuner.app.ui.common.SectionCard
import dev.thoremutuner.app.ui.common.ThorButton
import dev.thoremutuner.app.ui.common.initialFocus
import dev.thoremutuner.app.ui.common.launchSafely
import dev.thoremutuner.app.ui.theme.OkColor
import dev.thoremutuner.app.vm.OnboardingStep
import dev.thoremutuner.app.vm.OnboardingViewModel
import dev.thoremutuner.core.preset.EmulatorDef

@Composable
fun OnboardingScreen(container: AppContainer, onDone: () -> Unit, onExit: (() -> Unit)? = null) {
    val vm = viewModel { OnboardingViewModel(container) }
    val step by vm.step.collectAsStateWithLifecycle()
    val title = when (step) {
        OnboardingStep.WELCOME -> "Welcome"
        OnboardingStep.ROMS -> "1/3  ROM folders"
        OnboardingStep.SCAN -> "2/3  Scan"
        OnboardingStep.CONFIG -> "3/3  Emulator config folders"
    }
    val back: (() -> Unit)? = when (step) {
        OnboardingStep.WELCOME -> onExit
        OnboardingStep.ROMS -> { { vm.goTo(OnboardingStep.WELCOME) } }
        OnboardingStep.SCAN -> { { vm.cancelScan(); vm.goTo(OnboardingStep.ROMS) } }
        OnboardingStep.CONFIG -> { { vm.goTo(OnboardingStep.SCAN) } }
    }
    // B / system back steps back through the wizard instead of leaving the app.
    BackHandler(enabled = back != null) { back?.invoke() }
    val scroll = rememberScrollState()
    ScreenScaffold(title = title, onBack = back, scrollState = scroll) { pad ->
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll).padding(pad),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (step) {
                OnboardingStep.WELCOME -> Welcome { vm.goTo(OnboardingStep.ROMS) }
                OnboardingStep.ROMS -> RomFolders(vm)
                OnboardingStep.SCAN -> ScanStep(vm)
                OnboardingStep.CONFIG -> ConfigFolders(vm) { vm.finish(onDone) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Welcome(next: () -> Unit) {
    SectionCard("Tune, apply and test emulator settings per game", focusable = true) {
        Text("Thor Emu Tuner finds your games, suggests a starting preset for each emulator, lets you tweak settings in plain English, writes them to the emulator's per-game config (with a backup) and launches the game.")
        Text("A timed test session records battery power and temperature while you play; you enter the FPS you saw in the emulator's overlay. Compare revisions A/B to find the best settings.")
    }
    SectionCard("What it does not do", focusable = true) {
        Text("• It cannot read another app's FPS (Android does not allow it without root), so you type it in.")
        Text("• Presets are starting guesses, not verified Thor results: the community guides were unreachable during research. Your own A/B tests are the evidence.")
        Text("• No internet permission, no analytics. Everything stays on this device.")
    }
    ThorButton("Get started", next, modifier = Modifier.initialFocus())
}

@Composable
private fun RomFolders(vm: OnboardingViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.addRomFolder(uri)
    }
    SectionCard("Where are your ROMs?") {
        Text("Pick your ROMs root folder (for example ROMs with gc, psp, ps2... inside) or one folder per system. Folder names like gc, wii, psp, ps2, psx, 3ds, switch are used to recognise systems.")
        Text("Android does not allow picking the storage root, Download, or Android/data.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        ThorButton("Add folder", { picker.launchSafely(null, context) }, icon = Icons.Filled.Add, modifier = Modifier.initialFocus())
    }
    error?.let { Banner(it, BannerKind.ERROR) }
    val folders = settings?.romFolders.orEmpty()
    folders.forEach { f ->
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(f.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                ThorButton("Remove", { vm.removeRomFolder(f.treeUri) }, icon = Icons.Filled.Delete, style = ButtonStyle.SECONDARY)
            }
        }
    }
    ThorButton(
        if (folders.isEmpty()) "Skip for now" else "Scan ${folders.size} folder(s)",
        { vm.goTo(if (folders.isEmpty()) OnboardingStep.CONFIG else OnboardingStep.SCAN) },
        style = if (folders.isEmpty()) ButtonStyle.SECONDARY else ButtonStyle.PRIMARY,
    )
}

@Composable
private fun ScanStep(vm: OnboardingViewModel) {
    val p by vm.scan.collectAsStateWithLifecycle()
    SectionCard(if (p.running) "Scanning..." else if (p.cancelled) "Scan cancelled" else "Scan finished") {
        if (p.running) {
            if (p.found > 0) LinearProgressIndicator(progress = { p.probed.toFloat() / p.found }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Text("Files found: ${p.found}   checked: ${p.probed}   with a game ID: ${p.withId}")
        p.bySystem.entries.sortedBy { it.key.ordinal }.forEach { (s, n) -> Text("${s.displayName}: $n") }
        if (p.errors > 0) Text("${p.errors} file(s) could not be read; they are still listed.", color = MaterialTheme.colorScheme.secondary)
        if (p.unreadableFolders > 0) Text("${p.unreadableFolders} folder(s) could not be read.", color = MaterialTheme.colorScheme.error)
    }
    if (p.running) {
        ThorButton("Cancel", { vm.cancelScan() }, style = ButtonStyle.SECONDARY, modifier = Modifier.initialFocus())
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ThorButton("Next", { vm.goTo(OnboardingStep.CONFIG) }, modifier = Modifier.initialFocus())
            ThorButton("Scan again", { vm.rescan() }, style = ButtonStyle.SECONDARY)
        }
    }
}

@Composable
private fun ConfigFolders(vm: OnboardingViewModel, finish: () -> Unit) {
    val statuses by vm.statuses.collectAsStateWithLifecycle()
    var pending by remember { mutableStateOf<EmulatorDef?>(null) }
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val def = pending
        if (uri != null && def != null) vm.grant(def, uri)
        pending = null
    }
    Text(
        "Optional: grant each emulator's folder so Thor Emu Tuner can write per-game settings. Without it you can still export files and copy them by hand. Eden needs no folder (settings travel with the launch).",
        style = MaterialTheme.typography.bodyMedium,
    )
    ThorButton("Finish", finish, modifier = Modifier.initialFocus())
    vm.emulators.forEach { def ->
        val st = statuses[def.emulatorId]
        SectionCard(def.name) {
            Text(def.configTarget.folderToGrant.orEmpty(), style = MaterialTheme.typography.bodyMedium)
            if (def.emulatorId == "dolphin") {
                Text("In the picker, open the menu (≡) and choose Dolphin.", color = MaterialTheme.colorScheme.primary)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ThorButton(if (st?.granted == true) "Change" else "Grant", { pending = def; picker.launchSafely(null, context) }, style = ButtonStyle.SECONDARY)
                if (st?.ok == true) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = "Folder is valid", tint = OkColor)
                    Text(st.label ?: "Ready", color = OkColor)
                } else if (st != null && st.granted) {
                    Text(st.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                }
            }
        }
    }
    ThorButton("Finish", finish, style = ButtonStyle.SECONDARY)
}
