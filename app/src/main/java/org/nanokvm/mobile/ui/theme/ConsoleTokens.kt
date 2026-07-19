package org.nanokvm.mobile.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Semantic colours for the latency-sensitive console surface.
 *
 * This palette is intentionally independent of wallpaper-derived colour. Stable neutrals avoid
 * glare and colour cast around the remote image, while status colours retain the same meaning on
 * every device. Status is always paired with text, an icon, or semantics at the call site.
 */
@Immutable
data class ConsoleColorScheme(
    val canvas: Color,
    val controlSurface: Color,
    val controlSurfaceElevated: Color,
    val outline: Color,
    val active: Color,
    val onActive: Color,
    val connected: Color,
    val warning: Color,
    val critical: Color,
    val onSurface: Color,
    val onSurfaceMuted: Color,
)

val DarkConsoleColorScheme = ConsoleColorScheme(
    canvas = Color(0xFF070A0D),
    controlSurface = Color(0xFF0D141B),
    controlSurfaceElevated = Color(0xFF15212B),
    outline = Color(0xFF30404D),
    active = Color(0xFF44D7B6),
    onActive = Color(0xFF002019),
    connected = Color(0xFF44D7B6),
    warning = Color(0xFFFFBF69),
    critical = Color(0xFFFF6B6B),
    onSurface = Color(0xFFEAF3F5),
    onSurfaceMuted = Color(0xFF9AACB5),
)

val LocalConsoleColorScheme = staticCompositionLocalOf { DarkConsoleColorScheme }

object NanoKvmConsoleTheme {
    val colors: ConsoleColorScheme
        @Composable
        @ReadOnlyComposable
        get() = LocalConsoleColorScheme.current
}

// Compatibility aliases keep the migration screen-by-screen and avoid a risky all-at-once change.
// New console code should use LocalConsoleColorScheme.current and semantic field names.
val ConsoleBlack = DarkConsoleColorScheme.canvas
val ConsoleSurface = DarkConsoleColorScheme.controlSurface
val ConsoleSurfaceRaised = DarkConsoleColorScheme.controlSurfaceElevated
val ConsoleOutline = DarkConsoleColorScheme.outline
val ConsoleMint = DarkConsoleColorScheme.active
val ConsoleMintMuted = Color(0xFF8FE8D2)
val ConsoleAmber = DarkConsoleColorScheme.warning
val ConsoleRed = DarkConsoleColorScheme.critical
val ConsoleText = DarkConsoleColorScheme.onSurface
val ConsoleTextMuted = DarkConsoleColorScheme.onSurfaceMuted
