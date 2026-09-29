package dev.thoremutuner.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** High-contrast focus ring colour, visible on true black. */
val FocusColor = Color(0xFFFFE14D)
val OkColor = Color(0xFF81C784)
val WarnColor = Color(0xFFFFB74D)
val ErrorColor = Color(0xFFFF6E6E)

/** True-black dark scheme for the Thor's AMOLED panels; dynamic colour is deliberately off. */
private val ThorColors = darkColorScheme(
    primary = Color(0xFF4FC3F7),
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF0B3A4F),
    onPrimaryContainer = Color(0xFFCDEFFF),
    secondary = Color(0xFFFFB74D),
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF4A3000),
    onSecondaryContainer = Color(0xFFFFE0B2),
    tertiary = OkColor,
    onTertiary = Color.Black,
    background = Color.Black,
    onBackground = Color(0xFFEDEDED),
    surface = Color.Black,
    onSurface = Color(0xFFEDEDED),
    surfaceVariant = Color(0xFF1E1E20),
    onSurfaceVariant = Color(0xFFBDBDC2),
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0B0B0C),
    surfaceContainer = Color(0xFF121214),
    surfaceContainerHigh = Color(0xFF1A1A1D),
    surfaceContainerHighest = Color(0xFF232327),
    error = ErrorColor,
    onError = Color.Black,
    outline = Color(0xFF5C5C63),
    outlineVariant = Color(0xFF34343A),
)

private val base = Typography()
private val ThorTypography = Typography(
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = base.bodyLarge.copy(fontSize = 17.sp, lineHeight = 24.sp),
    bodyMedium = base.bodyMedium.copy(fontSize = 15.sp, lineHeight = 21.sp),
    labelLarge = base.labelLarge.copy(fontSize = 15.sp),
)

val MonoStyle = TextStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp)

@Composable
fun ThorTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ThorColors, typography = ThorTypography, content = content)
}
