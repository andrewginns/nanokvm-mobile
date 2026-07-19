package org.nanokvm.mobile.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import org.nanokvm.mobile.data.ThemeMode

@Composable
fun shouldUseDarkTheme(themeMode: ThemeMode): Boolean = when (themeMode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun NanoKvmTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    useDynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val darkTheme = shouldUseDarkTheme(themeMode)
    val context = LocalContext.current
    val colorScheme = remember(context, darkTheme, useDynamicColor) {
        when {
            useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme ->
                dynamicDarkColorScheme(context)
            useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                dynamicLightColorScheme(context)
            darkTheme -> NanoDarkColorScheme
            else -> NanoLightColorScheme
        }
    }

    CompositionLocalProvider(LocalConsoleColorScheme provides DarkConsoleColorScheme) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = NanoKvmTypography,
            shapes = NanoKvmShapes,
            content = content,
        )
    }
}
