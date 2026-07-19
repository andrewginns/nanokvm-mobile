package org.nanokvm.mobile.data

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.withContext

const val DEFAULT_SCROLL_SENSITIVITY = 1f
const val MIN_SCROLL_SENSITIVITY = 0.5f
const val MAX_SCROLL_SENSITIVITY = 3f

enum class ThemeMode(val persistedValue: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
    ;

    companion object {
        fun fromPersistedValue(value: String?): ThemeMode = entries
            .firstOrNull { it.persistedValue == value }
            ?: SYSTEM
    }
}

data class AppSettings(
    val scrollSensitivity: Float = DEFAULT_SCROLL_SENSITIVITY,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val useDynamicColor: Boolean = true,
    /** Browser-equivalent local preference; NanoKVM 2.4.3 exposes no state read endpoint. */
    val mjpegFrameDetectionEnabled: Boolean = false,
)

interface AppSettingsStore {
    val settings: Flow<AppSettings>
    suspend fun setScrollSensitivity(sensitivity: Float)
    suspend fun setThemeMode(themeMode: ThemeMode)
    suspend fun setUseDynamicColor(enabled: Boolean)
    suspend fun setMjpegFrameDetectionEnabled(enabled: Boolean)
}

private val Context.appSettingsDataStore by preferencesDataStore(
    name = "nanokvm_app_settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

class AppSettingsRepository(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AppSettingsStore {
    private val scrollSensitivityKey = floatPreferencesKey("scroll_sensitivity")
    private val themeModeKey = stringPreferencesKey("theme_mode")
    private val useDynamicColorKey = booleanPreferencesKey("use_dynamic_color")
    private val mjpegFrameDetectionKey = booleanPreferencesKey("mjpeg_frame_detection")

    override val settings: Flow<AppSettings> = context.appSettingsDataStore.data
        .recoverIoReadFailures()
        .map { preferences ->
            AppSettings(
                scrollSensitivity = normalizeScrollSensitivity(preferences[scrollSensitivityKey]),
                themeMode = ThemeMode.fromPersistedValue(preferences[themeModeKey]),
                useDynamicColor = preferences[useDynamicColorKey] ?: true,
                mjpegFrameDetectionEnabled = preferences[mjpegFrameDetectionKey] ?: false,
            )
        }
        .flowOn(ioDispatcher)

    override suspend fun setScrollSensitivity(sensitivity: Float) = withContext(ioDispatcher) {
        context.appSettingsDataStore.edit { preferences ->
            preferences[scrollSensitivityKey] = normalizeScrollSensitivity(sensitivity)
        }
        Unit
    }

    override suspend fun setThemeMode(themeMode: ThemeMode) = withContext(ioDispatcher) {
        context.appSettingsDataStore.edit { preferences ->
            preferences[themeModeKey] = themeMode.persistedValue
        }
        Unit
    }

    override suspend fun setUseDynamicColor(enabled: Boolean) = withContext(ioDispatcher) {
        context.appSettingsDataStore.edit { preferences ->
            preferences[useDynamicColorKey] = enabled
        }
        Unit
    }

    override suspend fun setMjpegFrameDetectionEnabled(enabled: Boolean) =
        withContext(ioDispatcher) {
            context.appSettingsDataStore.edit { preferences ->
                preferences[mjpegFrameDetectionKey] = enabled
            }
            Unit
        }
}

internal fun Flow<Preferences>.recoverIoReadFailures(
    retryDelayMillis: Long = 500L,
): Flow<Preferences> {
    require(retryDelayMillis >= 0L)
    return retryWhen { error, _ ->
        if (error !is IOException) return@retryWhen false
        emit(emptyPreferences())
        delay(retryDelayMillis)
        true
    }
}

fun normalizeScrollSensitivity(value: Float?): Float = value
    ?.takeIf(Float::isFinite)
    ?.coerceIn(MIN_SCROLL_SENSITIVITY, MAX_SCROLL_SENSITIVITY)
    ?: DEFAULT_SCROLL_SENSITIVITY
