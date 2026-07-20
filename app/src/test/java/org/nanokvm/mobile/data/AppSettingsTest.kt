package org.nanokvm.mobile.data

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun `transient settings read failure retries before emitting a value`() = runTest {
        var collectionAttempts = 0
        val recovered = flow<Preferences> {
            collectionAttempts++
            if (collectionAttempts == 1) throw IOException("temporary read failure")
            emit(emptyPreferences())
        }.recoverIoReadFailures()

        val values = recovered.toList()

        assertEquals(1, values.size)
        assertEquals(2, collectionAttempts)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `persistent settings read failure emits one fallback then recovers on slow retry`() =
        runTest {
            var collectionAttempts = 0
            val attemptTimes = mutableListOf<Long>()
            val recoveredKey = booleanPreferencesKey("recovered")
            val recovered = flow<Preferences> {
                collectionAttempts++
                attemptTimes += testScheduler.currentTime
                if (collectionAttempts <= 4) throw IOException("persistent read failure")
                emit(mutablePreferencesOf(recoveredKey to true))
            }.recoverIoReadFailures()

            val values = recovered.take(2).toList()

            assertEquals(5, collectionAttempts)
            assertEquals(listOf(0L, 250L, 750L, 1_750L, 6_750L), attemptTimes)
            assertEquals(emptyPreferences(), values.first())
            assertEquals(true, values.last()[recoveredKey])
        }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `settings read recovery retains success and later resumes without a fallback`() =
        runTest {
            var collectionAttempts = 0
            val attemptTimes = mutableListOf<Long>()
            val versionKey = booleanPreferencesKey("new_version")
            val recovered = flow<Preferences> {
                collectionAttempts++
                attemptTimes += testScheduler.currentTime
                when (collectionAttempts) {
                    1 -> {
                        emit(emptyPreferences())
                        throw IOException("read failed after a successful value")
                    }

                    2, 3 -> throw IOException("read remains unavailable")
                    else -> emit(mutablePreferencesOf(versionKey to true))
                }
            }.recoverIoReadFailures()

            val values = recovered.take(2).toList()

            assertEquals(4, collectionAttempts)
            assertEquals(listOf(0L, 250L, 750L, 1_750L), attemptTimes)
            assertEquals(emptyPreferences(), values.first())
            assertEquals(true, values.last()[versionKey])
        }

    @Test
    fun `settings read retries use bounded backoff`() {
        assertEquals(250L, settingsReadRetryDelayMillis(0))
        assertEquals(500L, settingsReadRetryDelayMillis(1))
        assertEquals(1_000L, settingsReadRetryDelayMillis(2))
        assertEquals(5_000L, settingsReadRetryDelayMillis(3))
        assertEquals(5_000L, settingsReadRetryDelayMillis(Int.MAX_VALUE))
    }

    @Test
    fun `settings read recovery preserves cancellation without retry or defaults`() = runTest {
        var collectionAttempts = 0
        val recovered = flow<Preferences> {
            collectionAttempts++
            throw CancellationException("collector stopped")
        }.recoverIoReadFailures()

        val failure = runCatching { recovered.toList() }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertEquals(1, collectionAttempts)
    }
}
