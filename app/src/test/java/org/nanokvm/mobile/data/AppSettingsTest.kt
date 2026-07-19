package org.nanokvm.mobile.data

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import java.io.IOException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AppSettingsTest {
    @Test
    fun `frame detection defaults off because the appliance exposes no readable state`() {
        assertEquals(false, AppSettings().mjpegFrameDetectionEnabled)
    }

    @Test
    fun `scroll sensitivity defaults and normalizes unsafe values`() {
        assertEquals(DEFAULT_SCROLL_SENSITIVITY, normalizeScrollSensitivity(null))
        assertEquals(DEFAULT_SCROLL_SENSITIVITY, normalizeScrollSensitivity(Float.NaN))
        assertEquals(DEFAULT_SCROLL_SENSITIVITY, normalizeScrollSensitivity(Float.POSITIVE_INFINITY))
        assertEquals(MIN_SCROLL_SENSITIVITY, normalizeScrollSensitivity(-10f))
        assertEquals(MAX_SCROLL_SENSITIVITY, normalizeScrollSensitivity(10f))
        assertEquals(1.5f, normalizeScrollSensitivity(1.5f))
    }

    @Test
    fun `theme mode decoding is stable and unknown values fall back to system`() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromPersistedValue(null))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromPersistedValue("future-mode"))
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromPersistedValue("light"))
        assertEquals(ThemeMode.DARK, ThemeMode.fromPersistedValue("dark"))
    }

    @Test
    fun `transient settings read failure emits defaults and retries collection`() = runTest {
        var collectionAttempts = 0
        val recovered = flow<Preferences> {
            collectionAttempts++
            if (collectionAttempts == 1) throw IOException("temporary read failure")
            emit(emptyPreferences())
        }.recoverIoReadFailures(retryDelayMillis = 0L)

        val values = recovered.take(2).toList()

        assertEquals(2, values.size)
        assertEquals(2, collectionAttempts)
    }
}
