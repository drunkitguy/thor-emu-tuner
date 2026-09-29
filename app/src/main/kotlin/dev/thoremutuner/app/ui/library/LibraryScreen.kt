@file:OptIn(ExperimentalMaterial3Api::class)

package dev.thoremutuner.app.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.thoremutuner.app.AppContainer
import dev.thoremutuner.app.ui.common.Banner
import dev.thoremutuner.app.ui.common.BannerKind
import dev.thoremutuner.app.ui.common.ClickableRow
import dev.thoremutuner.app.ui.common.EmptyState
import dev.thoremutuner.app.ui.common.Fmt
import dev.thoremutuner.app.ui.common.ScreenScaffold
import dev.thoremutuner.app.ui.common.Tag
import dev.thoremutuner.app.ui.common.TextInputDialog
import dev.thoremutuner.app.ui.common.focusRing
import dev.thoremutuner.app.ui.common.rememberInitialFocus
import dev.thoremutuner.app.ui.theme.ErrorColor
import dev.thoremutuner.app.ui.theme.OkColor
import dev.thoremutuner.app.ui.theme.WarnColor
import dev.thoremutuner.app.vm.GameRow
import dev.thoremutuner.app.vm.LibraryViewModel
import dev.thoremutuner.core.bench.Outcome
import dev.thoremutuner.core.model.SystemId

@Composable
fun LibraryScreen(container: AppContainer, openGame: (String) -> Unit, openSettings: () -> Unit, openResult: () -> Unit) {
    val vm = viewModel { LibraryViewModel(container) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val scan by vm.scan.collectAsStateWithLifecycle()
    val live by vm.live.collectAsStateWithLifecycle()
    var searching by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    // Focus rules:
    // - first visit: the first row, once (remembered in saved state, so it never fires again when
    //   the list refreshes, filters change or the user comes back);
    // - returning from an opened game: that game's row, once.
    val visibleNow = ui.visible
    val firstFocus = rememberInitialFocus(ready = visibleNow.isNotEmpty(), persistAcrossVisits = true)
    var restoreKey by rememberSaveable { mutableStateOf<String?>(null) }
    val restoreFocus = remember { FocusRequester() }
    var restoreToken by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        vm.refresh()
        if (restoreKey != null) restoreToken++
    }
    LaunchedEffect(restoreToken) {
        if (restoreToken == 0) return@LaunchedEffect
        repeat(20) {
            withFrameNanos { }
            if (runCatching { restoreFocus.requestFocus() }.isSuccess) {
                restoreKey = null
                return@LaunchedEffect
            }
        }
        restoreKey = null
    }
    val open: (String) -> Unit = { key ->
        restoreKey = key
        openGame(key)
    }
    if (searching) {
        TextInputDialog(
            title = "Search",
            initial = ui.query,
            label = "Title or game ID",
            confirmLabel = "Search",
            onConfirm = { vm.setQuery(it.trim()); searching = false },
            onDismiss = { searching = false },
        )
    }

    ScreenScaffold(
        title = "Library",
        onBack = null,
        scrollState = listState,
        actions = {
            IconButton(onClick = { searching = true }, modifier = Modifier.focusRing(RoundedCornerShape(24.dp))) {
                Icon(Icons.Filled.Search, contentDescription = "Search")
            }
            IconButton(onClick = { vm.rescan(force = true) }, modifier = Modifier.focusRing(RoundedCornerShape(24.dp))) {
                Icon(Icons.Filled.Refresh, contentDescription = "Rescan")
            }
            IconButton(onClick = openSettings, modifier = Modifier.focusRing(RoundedCornerShape(24.dp))) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings")
            }
        },
    ) { pad ->
        val visible = ui.visible
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = pad, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            live?.let { l ->
                item {
                    Banner(
                        if (l.running) "A test session is running (${Fmt.duration(l.elapsedSec)})." else "A test session is waiting for your results.",
                        BannerKind.WARN, actionLabel = "Open", onAction = openResult,
                    )
                }
            }
            if (scan.running) {
                item {
                    Column {
                        Text("Scanning: ${scan.probed}/${scan.found}", style = MaterialTheme.typography.bodyMedium)
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
            if (ui.lostFolders > 0) {
                item { Banner("${ui.lostFolders} ROM folder(s) lost their access permission. Re-add them in Settings.", BannerKind.ERROR, actionLabel = "Settings", onAction = openSettings) }
            }
            if (!scan.running && scan.finished && scan.errors > 0) {
                item { Banner("${scan.errors} file(s) could not be identified (they are still listed).", BannerKind.WARN) }
            }
            if (ui.query.isNotBlank()) {
                item {
                    FilterChip(
                        selected = true,
                        onClick = { vm.setQuery("") },
                        label = { Text("Search: \"${ui.query}\" (clear)") },
                        modifier = Modifier.focusRing(RoundedCornerShape(8.dp)),
                    )
                }
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(selected = ui.system == null, onClick = { vm.setSystem(null) }, label = { Text("All (${ui.rows.size})") },
                            modifier = Modifier.focusRing(RoundedCornerShape(8.dp)))
                    }
                    items(ui.systems) { (s, n) ->
                        FilterChip(
                            selected = ui.system == s,
                            onClick = { vm.setSystem(if (ui.system == s) null else s) },
                            label = { Text("${if (s == SystemId.UNKNOWN) "Unrecognized" else s.displayName} ($n)") },
                            modifier = Modifier.focusRing(RoundedCornerShape(8.dp)),
                        )
                    }
                }
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(ui.onlyProfiles, { vm.toggleProfiles() }, { Text("Has profile") }, Modifier.focusRing(RoundedCornerShape(8.dp))) }
                    item { FilterChip(ui.onlyTested, { vm.toggleTested() }, { Text("Tested") }, Modifier.focusRing(RoundedCornerShape(8.dp))) }
                    item { FilterChip(ui.onlyUnknownId, { vm.toggleUnknown() }, { Text("Unknown ID") }, Modifier.focusRing(RoundedCornerShape(8.dp))) }
                }
            }
            if (!ui.loading && ui.rows.isEmpty()) {
                item {
                    EmptyState(
                        if (ui.folderCount == 0) "No ROM folders yet. Add one in Settings, then rescan."
                        else "No games found yet. Press rescan after adding games.",
                    )
                }
            } else if (!ui.loading && visible.isEmpty()) {
                item { EmptyState("No games match the filters.") }
            }
            items(visible, key = { it.game.key }) { row ->
                val rowModifier = when {
                    row.game.key == restoreKey -> Modifier.focusRequester(restoreFocus)
                    row.game.key == visible.first().game.key -> Modifier.focusRequester(firstFocus)
                    else -> Modifier
                }
                GameRowItem(row, onClick = { open(row.game.key) }, modifier = rowModifier)
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun GameRowItem(row: GameRow, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ClickableRow(onClick = onClick, modifier = modifier) {
        Column(Modifier.weight(1f)) {
            Text(row.game.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(row.game.system.displayName, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                val id = row.game.id
                if (id != null) Tag("${id.value} · ${Fmt.method(id.method)}")
                else Tag("No ID", MaterialTheme.colorScheme.onSurfaceVariant)
                row.emulatorName?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, maxLines = 1) }
            }
        }
        Spacer(Modifier.width(8.dp))
        if (row.game.scanError != null) Tag("Unreadable", ErrorColor)
        row.lastOutcome?.let { o ->
            val color = when (o) {
                Outcome.PASS -> OkColor
                Outcome.PLAYABLE_WITH_ISSUES -> WarnColor
                Outcome.FAIL, Outcome.CRASH -> ErrorColor
            }
            Tag(o.label, color)
        }
        if (row.hasProfile && row.lastOutcome == null) Tag("Profile", MaterialTheme.colorScheme.secondary)
    }
}
