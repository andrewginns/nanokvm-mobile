package org.nanokvm.mobile.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeContrastTest {
    @Test
    fun fixedApplicationSchemesMeetTextContrastForCorePairs() {
        assertContrast("light on background", NanoLightColorScheme.onBackground, NanoLightColorScheme.background, 4.5)
        assertContrast("light primary", NanoLightColorScheme.onPrimary, NanoLightColorScheme.primary, 4.5)
        assertContrast(
            "light surface variant",
            NanoLightColorScheme.onSurfaceVariant,
            NanoLightColorScheme.surfaceContainer,
            4.5,
        )
        assertContrast("dark on background", NanoDarkColorScheme.onBackground, NanoDarkColorScheme.background, 4.5)
        assertContrast("dark primary", NanoDarkColorScheme.onPrimary, NanoDarkColorScheme.primary, 4.5)
        assertContrast(
            "dark surface variant",
            NanoDarkColorScheme.onSurfaceVariant,
            NanoDarkColorScheme.surfaceContainer,
            4.5,
        )
    }

    @Test
    fun consoleSchemeMeetsTextAndStatusContrast() {
        val scheme = DarkConsoleColorScheme
        assertContrast("console text", scheme.onSurface, scheme.canvas, 4.5)
        assertContrast("console muted text", scheme.onSurfaceMuted, scheme.controlSurface, 4.5)
        assertContrast("console control text", scheme.onSurface, scheme.controlSurfaceElevated, 4.5)
        assertContrast("console active control", scheme.active, scheme.controlSurfaceElevated, 3.0)
        assertContrast("console warning status", scheme.warning, scheme.canvas, 3.0)
        assertContrast("console critical status", scheme.critical, scheme.canvas, 3.0)
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
