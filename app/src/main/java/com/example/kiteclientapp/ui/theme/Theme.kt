package com.example.kiteclientapp.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF087F73), onPrimary = Color.White,
    secondary = Color(0xFFEF6B42), background = Color(0xFFF6F8FA),
    surface = Color.White, onSurface = Color(0xFF172B3A),
    onSurfaceVariant = Color(0xFF647681), outlineVariant = Color(0xFFE3E9ED),
)
private val Dark = darkColorScheme(
    primary = Color(0xFF70D8C5), secondary = Color(0xFFFF9978),
    background = Color(0xFF101B23), surface = Color(0xFF182731),
    onSurface = Color(0xFFE6EDF1), onSurfaceVariant = Color(0xFFA5B6C1),
)
@Composable
fun KiteClientAppTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) Dark else Light, typography = Typography, content = content)
}

// Shared by portfolio and options so their colours stay in sync.
object WorkspacePalette {
    val Background = Color(0xFF111313)
    val Surface = Color(0xFF181B1B)
    val Raised = Color(0xFF202424)
    val Border = Color(0xFF2C3231)
    val Text = Color(0xFFEEF1EF)
    val Muted = Color(0xFF939E99)
    val Accent = Color(0xFF76D9B1)
    val Loss = Color(0xFFF4A39F)
}

val WorkspaceColors = darkColorScheme(
    primary = WorkspacePalette.Accent, onPrimary = WorkspacePalette.Background,
    primaryContainer = WorkspacePalette.Raised, onPrimaryContainer = WorkspacePalette.Text,
    secondary = WorkspacePalette.Accent, onSecondary = WorkspacePalette.Background,
    secondaryContainer = WorkspacePalette.Raised, onSecondaryContainer = WorkspacePalette.Text,
    tertiary = WorkspacePalette.Accent, onTertiary = WorkspacePalette.Background,
    background = WorkspacePalette.Background, onBackground = WorkspacePalette.Text,
    surface = WorkspacePalette.Surface, onSurface = WorkspacePalette.Text,
    surfaceVariant = WorkspacePalette.Raised, onSurfaceVariant = WorkspacePalette.Muted,
    surfaceContainerLowest = WorkspacePalette.Background,
    surfaceContainerLow = WorkspacePalette.Surface,
    surfaceContainer = WorkspacePalette.Surface,
    surfaceContainerHigh = WorkspacePalette.Raised,
    surfaceContainerHighest = WorkspacePalette.Raised,
    surfaceBright = WorkspacePalette.Raised, surfaceDim = WorkspacePalette.Background,
    outline = WorkspacePalette.Border, outlineVariant = WorkspacePalette.Border,
    error = WorkspacePalette.Loss, onError = WorkspacePalette.Background,
    errorContainer = Color(0xFF3A2222), onErrorContainer = WorkspacePalette.Loss,
)

@Composable
fun WorkspaceTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = WorkspaceColors, typography = Typography, content = content)
}
