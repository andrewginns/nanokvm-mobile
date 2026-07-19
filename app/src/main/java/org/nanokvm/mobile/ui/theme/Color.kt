package org.nanokvm.mobile.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// A quiet, high-contrast fallback identity for devices where dynamic colour is unavailable or
// disabled. The live console deliberately uses a separate fixed palette (ConsoleTokens.kt).
val NanoMint = Color(0xFF006B59)
val NanoMintLight = Color(0xFF44D7B6)
val NanoInk = Color(0xFF07110E)
val NanoPaper = Color(0xFFF5FBF8)

internal val NanoLightColorScheme = lightColorScheme(
    primary = NanoMint,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF9CF2D8),
    onPrimaryContainer = Color(0xFF002019),
    secondary = Color(0xFF4B635B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCDE8DE),
    onSecondaryContainer = Color(0xFF072019),
    tertiary = Color(0xFF3F6375),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFC2E8FD),
    onTertiaryContainer = Color(0xFF001F2A),
    background = NanoPaper,
    onBackground = Color(0xFF171D1B),
    surface = NanoPaper,
    onSurface = Color(0xFF171D1B),
    surfaceDim = Color(0xFFD5DBD8),
    surfaceBright = NanoPaper,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFEFF5F2),
    surfaceContainer = Color(0xFFE9EFEC),
    surfaceContainerHigh = Color(0xFFE3E9E6),
    surfaceContainerHighest = Color(0xFFDEE4E1),
    surfaceVariant = Color(0xFFDBE5E0),
    onSurfaceVariant = Color(0xFF3F4945),
    outline = Color(0xFF6F7975),
    outlineVariant = Color(0xFFBEC9C4),
    inverseSurface = Color(0xFF2C322F),
    inverseOnSurface = Color(0xFFECF2EF),
    inversePrimary = Color(0xFF80D5BC),
    surfaceTint = NanoMint,
    scrim = Color.Black,
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

internal val NanoDarkColorScheme = darkColorScheme(
    primary = NanoMintLight,
    onPrimary = Color(0xFF00382E),
    primaryContainer = Color(0xFF005143),
    onPrimaryContainer = Color(0xFF9CF2D8),
    secondary = Color(0xFFB1CCC2),
    onSecondary = Color(0xFF1D352E),
    secondaryContainer = Color(0xFF344C45),
    onSecondaryContainer = Color(0xFFCDE8DE),
    tertiary = Color(0xFFA7CCE0),
    onTertiary = Color(0xFF0B3445),
    tertiaryContainer = Color(0xFF274B5C),
    onTertiaryContainer = Color(0xFFC2E8FD),
    background = Color(0xFF0F1513),
    onBackground = Color(0xFFDEE4E1),
    surface = Color(0xFF0F1513),
    onSurface = Color(0xFFDEE4E1),
    surfaceDim = Color(0xFF0F1513),
    surfaceBright = Color(0xFF353B38),
    surfaceContainerLowest = Color(0xFF0A0F0D),
    surfaceContainerLow = Color(0xFF171D1B),
    surfaceContainer = Color(0xFF1B211F),
    surfaceContainerHigh = Color(0xFF252B29),
    surfaceContainerHighest = Color(0xFF303633),
    surfaceVariant = Color(0xFF3F4945),
    onSurfaceVariant = Color(0xFFBEC9C4),
    outline = Color(0xFF89938E),
    outlineVariant = Color(0xFF3F4945),
    inverseSurface = Color(0xFFDEE4E1),
    inverseOnSurface = Color(0xFF2C322F),
    inversePrimary = NanoMint,
    surfaceTint = NanoMintLight,
    scrim = Color.Black,
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)
