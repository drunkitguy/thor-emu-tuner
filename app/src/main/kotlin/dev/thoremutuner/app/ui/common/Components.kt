package dev.thoremutuner.app.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.thoremutuner.app.ui.theme.ErrorColor
import dev.thoremutuner.app.ui.theme.FocusColor
import dev.thoremutuner.app.ui.theme.OkColor
import dev.thoremutuner.app.ui.theme.WarnColor

/**
 * Visible focus for D-pad/gamepad navigation: a bright ring drawn while this element (or a child)
 * has focus. Place it before the clickable/focusable modifier.
 */
fun Modifier.focusRing(shape: Shape = RoundedCornerShape(14.dp)): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    this
        .onFocusChanged { focused = it.hasFocus }
        .border(BorderStroke(if (focused) 3.dp else 0.dp, if (focused) FocusColor else Color.Transparent), shape)
}

/** Requests focus once when first composed, so the D-pad has a starting point on every screen. */
fun Modifier.initialFocus(): Modifier = composed {
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
    this.focusRequester(requester)
}

enum class ButtonStyle { PRIMARY, SECONDARY, TEXT }

@Composable
fun ThorButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    style: ButtonStyle = ButtonStyle.PRIMARY,
) {
    val m = modifier.focusRing(RoundedCornerShape(24.dp)).heightIn(min = 48.dp)
    val content: @Composable RowScope.() -> Unit = {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    when (style) {
        ButtonStyle.PRIMARY -> Button(onClick = onClick, modifier = m, enabled = enabled, content = content)
        ButtonStyle.SECONDARY -> OutlinedButton(onClick = onClick, modifier = m, enabled = enabled, content = content)
        ButtonStyle.TEXT -> TextButton(onClick = onClick, modifier = m, enabled = enabled, content = content)
    }
}

/**
 * Screen frame: a compact top bar (the Thor's top screen is only ~360 dp tall in landscape) and a
 * width-limited content area that works on both the 1920x1080 and the 1080x1240 displays.
 */
@Composable
fun ScreenScaffold(
    title: String,
    onBack: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) {
                    IconButton(onClick = onBack, modifier = Modifier.focusRing(RoundedCornerShape(24.dp))) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                } else {
                    Spacer(Modifier.width(12.dp))
                }
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                actions()
            }
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.widthIn(max = 1000.dp).fillMaxSize()) {
                    content(PaddingValues(horizontal = 16.dp, vertical = 8.dp))
                }
            }
        }
    }
}

/**
 * A card. Read-only content that should be reachable with the D-pad (so long screens scroll with
 * focus) sets [focusable].
 */
@Composable
fun SectionCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    focusable: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = shape,
        modifier = modifier.fillMaxWidth().let { if (focusable) it.focusRing(shape).focusable() else it },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

/** A focusable, clickable row with a visible focus ring and a 56 dp minimum height. */
@Composable
fun ClickableRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = shape,
        modifier = modifier.fillMaxWidth().focusRing(shape),
    ) {
        Row(
            Modifier.clickable(enabled = enabled, onClick = onClick).heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

enum class BannerKind { INFO, OK, WARN, ERROR }

@Composable
fun Banner(
    text: String,
    kind: BannerKind = BannerKind.INFO,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val (color, icon) = when (kind) {
        BannerKind.INFO -> MaterialTheme.colorScheme.primary to Icons.Filled.Info
        BannerKind.OK -> OkColor to Icons.Filled.CheckCircle
        BannerKind.WARN -> WarnColor to Icons.Filled.Warning
        BannerKind.ERROR -> ErrorColor to Icons.Filled.Warning
    }
    Surface(
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.6f)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = color)
            Spacer(Modifier.width(12.dp))
            Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.width(8.dp))
                ThorButton(actionLabel, onAction, style = ButtonStyle.SECONDARY)
            }
        }
    }
}

/** Small coloured label (evidence badge, impact, id method). */
@Composable
fun Tag(text: String, color: Color = MaterialTheme.colorScheme.primary, modifier: Modifier = Modifier) {
    Surface(
        color = color.copy(alpha = 0.15f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.5f)),
        modifier = modifier,
    ) {
        Text(
            text,
            color = color,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            maxLines = 1,
        )
    }
}

@Composable
fun LabeledValue(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.45f))
        Text(value, fontWeight = FontWeight.Medium, modifier = Modifier.weight(0.55f))
    }
}

/** A single-choice list dialog; every row is D-pad focusable. */
@Composable
fun <T> ChoiceDialog(
    title: String,
    options: List<Pair<T, String>>,
    selected: T?,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(options) { (value, label) ->
                    val shape = RoundedCornerShape(10.dp)
                    val isInitial = value == selected || (selected == null && value == options.first().first)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .let { if (isInitial) it.initialFocus() else it }
                            .focusRing(shape)
                            .clickable { onSelect(value) }
                            .heightIn(min = 48.dp)
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = value == selected, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(label)
                    }
                }
            }
        },
        confirmButton = { ThorButton("Cancel", onDismiss, style = ButtonStyle.TEXT) },
    )
}

/** Text entry dialog with inline validation; [validate] returns an error message or null. */
@Composable
fun TextInputDialog(
    title: String,
    initial: String,
    label: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    supporting: String? = null,
    confirmLabel: String = "Save",
    validate: (String) -> String? = { null },
    singleLine: Boolean = true,
) {
    var text by remember { mutableStateOf(initial) }
    val error = validate(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(label) },
                    singleLine = singleLine,
                    isError = error != null,
                    supportingText = { Text(error ?: supporting ?: "") },
                    modifier = Modifier.fillMaxWidth().focusRing(RoundedCornerShape(6.dp)),
                )
            }
        },
        confirmButton = { ThorButton(confirmLabel, { onConfirm(text) }, enabled = error == null) },
        dismissButton = { ThorButton("Cancel", onDismiss, style = ButtonStyle.TEXT) },
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { ThorButton(confirmLabel, onConfirm) },
        dismissButton = { ThorButton("Cancel", onDismiss, style = ButtonStyle.TEXT) },
    )
}

@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
