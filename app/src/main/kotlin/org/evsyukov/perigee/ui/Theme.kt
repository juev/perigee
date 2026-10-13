package org.evsyukov.perigee.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The colours of the app by day: warm paper, dark brown ink and a terracotta
 * accent. The accent is a shade darker than the terracotta of the icon
 * (#B4532A), which is too light to be read as text on a card. The page of a
 * book is not painted with these: its colours are the reader's own choice
 * among the schemes of the reading settings.
 */
val LightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFFA04722),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF6DCCB),
    onPrimaryContainer = Color(0xFF3A1505),
    inversePrimary = Color(0xFFE08A5B),
    secondary = Color(0xFF7A5B45),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEBDCC4),
    onSecondaryContainer = Color(0xFF2E2620),
    tertiary = Color(0xFF5F6B3F),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE2E8C8),
    onTertiaryContainer = Color(0xFF1C2106),
    background = Color(0xFFFBF5EA),
    onBackground = Color(0xFF2E2620),
    surface = Color(0xFFFBF5EA),
    onSurface = Color(0xFF2E2620),
    surfaceVariant = Color(0xFFEBDFCC),
    onSurfaceVariant = Color(0xFF5E5146),
    surfaceTint = Color(0xFFA04722),
    inverseSurface = Color(0xFF362F29),
    inverseOnSurface = Color(0xFFF7EFE1),
    outline = Color(0xFF8C7D6E),
    outlineVariant = Color(0xFFD8CAB6),
    surfaceBright = Color(0xFFFBF5EA),
    surfaceDim = Color(0xFFE3D8C5),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7EFE1),
    surfaceContainer = Color(0xFFF1E6D3),
    surfaceContainerHigh = Color(0xFFECE0CB),
    surfaceContainerHighest = Color(0xFFE6D9C3),
)

/** The colours of the app by night: warm graphite, with the accent lightened to be read on it. */
val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFFE08A5B),
    onPrimary = Color(0xFF2A1608),
    primaryContainer = Color(0xFF6E3115),
    onPrimaryContainer = Color(0xFFFBDCCB),
    inversePrimary = Color(0xFFA04722),
    secondary = Color(0xFFD2BBA6),
    onSecondary = Color(0xFF382A1E),
    secondaryContainer = Color(0xFF4A3F36),
    onSecondaryContainer = Color(0xFFEDE3D6),
    tertiary = Color(0xFFC6CF9E),
    onTertiary = Color(0xFF2F3513),
    tertiaryContainer = Color(0xFF464C28),
    onTertiaryContainer = Color(0xFFE2E8C8),
    background = Color(0xFF1E1A17),
    onBackground = Color(0xFFEDE3D6),
    surface = Color(0xFF1E1A17),
    onSurface = Color(0xFFEDE3D6),
    surfaceVariant = Color(0xFF3A332D),
    onSurfaceVariant = Color(0xFFC9BBAB),
    surfaceTint = Color(0xFFE08A5B),
    inverseSurface = Color(0xFFEDE3D6),
    inverseOnSurface = Color(0xFF2E2620),
    outline = Color(0xFF96887A),
    outlineVariant = Color(0xFF4A413A),
    surfaceBright = Color(0xFF453D36),
    surfaceDim = Color(0xFF1E1A17),
    surfaceContainerLowest = Color(0xFF171411),
    surfaceContainerLow = Color(0xFF24201C),
    surfaceContainer = Color(0xFF2B2521),
    surfaceContainerHigh = Color(0xFF332D28),
    surfaceContainerHighest = Color(0xFF3D3630),
)

/** The look of every screen of the app; it follows the device between day and night. */
@Composable
fun PerigeeTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
