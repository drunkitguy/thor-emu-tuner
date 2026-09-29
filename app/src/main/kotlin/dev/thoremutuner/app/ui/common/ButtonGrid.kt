package dev.thoremutuner.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

data class GridAction(
    val label: String,
    val onClick: () -> Unit,
    val icon: ImageVector? = null,
    val enabled: Boolean = true,
    val style: ButtonStyle = ButtonStyle.SECONDARY,
    val focusFirst: Boolean = false,
)

/** Buttons in 2 or 3 columns depending on the available width (both Thor screens). */
@Composable
fun ButtonGrid(actions: List<GridAction>, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 600.dp) 3 else 2
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            actions.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    row.forEach { a ->
                        ThorButton(
                            a.label, a.onClick,
                            modifier = Modifier.weight(1f).let { if (a.focusFirst) it.initialFocus() else it },
                            enabled = a.enabled, icon = a.icon, style = a.style,
                        )
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}
