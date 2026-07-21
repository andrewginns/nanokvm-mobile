package org.nanokvm.mobile.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeContrastTest {
    @Test
    fun fixedApplicationSchemesMeetTextContrastForCorePairs() {
        assertMaterialTextPairs("light", NanoLightColorScheme)
        assertMaterialTextPairs("dark", NanoDarkColorScheme)
    }

    @Test
    fun consoleSchemeMeetsTextAndStatusContrast() {
        val scheme = DarkConsoleColorScheme
        assertContrast("console text", scheme.onSurface, scheme.canvas, 4.5)
        assertContrast("console text on controls", scheme.onSurface, scheme.controlSurface, 4.5)
        assertContrast("console muted text", scheme.onSurfaceMuted, scheme.controlSurface, 4.5)
        assertContrast(
            "console muted text on elevated controls",
            scheme.onSurfaceMuted,
            scheme.controlSurfaceElevated,
            4.5,
        )
        assertContrast("console control text", scheme.onSurface, scheme.controlSurfaceElevated, 4.5)
        assertContrast("console active label", scheme.active, scheme.controlSurfaceElevated, 4.5)
        assertContrast("console active content", scheme.onActive, scheme.active, 4.5)
        assertContrast("console warning status", scheme.warning, scheme.controlSurfaceElevated, 4.5)
        assertContrast("console critical status", scheme.critical, scheme.controlSurfaceElevated, 4.5)
    }

    @Test
    fun consoleMaterialBridgeKeepsStandardComponentsOnTheDarkConsolePalette() {
        val console = DarkConsoleColorScheme
        val material = consoleMaterialColorScheme(console)

        assertEquals(console.active, material.primary)
        assertEquals(console.onActive, material.onPrimary)
        assertEquals(console.canvas, material.background)
        assertEquals(console.onSurface, material.onBackground)
        assertEquals(console.controlSurface, material.surface)
        assertEquals(console.onSurface, material.onSurface)
        assertEquals(console.controlSurfaceElevated, material.surfaceContainerHigh)
        assertEquals(console.onSurfaceMuted, material.onSurfaceVariant)
        assertEquals(console.critical, material.error)
        assertEquals(console.canvas, material.onError)
        assertMaterialTextPairs("console material", material)
    }
}

private fun assertMaterialTextPairs(name: String, scheme: ColorScheme) {
    listOf(
        "primary" to (scheme.onPrimary to scheme.primary),
        "primary container" to (scheme.onPrimaryContainer to scheme.primaryContainer),
        "secondary" to (scheme.onSecondary to scheme.secondary),
        "secondary container" to (scheme.onSecondaryContainer to scheme.secondaryContainer),
        "tertiary" to (scheme.onTertiary to scheme.tertiary),
        "tertiary container" to (scheme.onTertiaryContainer to scheme.tertiaryContainer),
        "error" to (scheme.onError to scheme.error),
        "error container" to (scheme.onErrorContainer to scheme.errorContainer),
        "background" to (scheme.onBackground to scheme.background),
        "surface" to (scheme.onSurface to scheme.surface),
        "surface variant" to (scheme.onSurfaceVariant to scheme.surfaceVariant),
        "inverse surface" to (scheme.inverseOnSurface to scheme.inverseSurface),
    ).forEach { (pairName, colors) ->
        assertContrast("$name $pairName", colors.first, colors.second, 4.5)
    }
    listOf(
        "surface lowest" to scheme.surfaceContainerLowest,
        "surface low" to scheme.surfaceContainerLow,
        "surface container" to scheme.surfaceContainer,
        "surface high" to scheme.surfaceContainerHigh,
        "surface highest" to scheme.surfaceContainerHighest,
    ).forEach { (surfaceName, surface) ->
        assertContrast("$name $surfaceName", scheme.onSurface, surface, 4.5)
    }
}

private fun assertContrast(name: String, foreground: Color, background: Color, minimum: Double) {
    val contrast = contrastRatio(foreground, background)
    assertTrue("$name contrast was $contrast, expected at least $minimum", contrast >= minimum)
}

private fun contrastRatio(first: Color, second: Color): Double {
    val firstLuminance = relativeLuminance(first)
    val secondLuminance = relativeLuminance(second)
    val lighter = maxOf(firstLuminance, secondLuminance)
    val darker = minOf(firstLuminance, secondLuminance)
    return (lighter + 0.05) / (darker + 0.05)
}

private fun relativeLuminance(color: Color): Double {
    val argb = color.toArgb()
    fun channel(shift: Int): Double {
        val encoded = ((argb shr shift) and 0xff) / 255.0
        return if (encoded <= 0.04045) encoded / 12.92 else Math.pow((encoded + 0.055) / 1.055, 2.4)
    }
    return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
}
