package org.nanokvm.mobile

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.nanokvm.mobile.data.AppSettingsRepository
import org.nanokvm.mobile.data.DEFAULT_SCROLL_SENSITIVITY
import org.nanokvm.mobile.data.ThemeMode

@RunWith(AndroidJUnit4::class)
class AppSettingsRepositoryInstrumentedTest {
    @Test
    fun scrollSensitivityIsPersistedAndObservedByANewRepository() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = AppSettingsRepository(context)
        try {
            repository.setScrollSensitivity(2.5f)

            val reloaded = AppSettingsRepository(context)
            val settings = withTimeout(5_000L) {
                reloaded.settings.first { it.scrollSensitivity == 2.5f }
            }

            assertEquals(2.5f, settings.scrollSensitivity)
        } finally {
            repository.setScrollSensitivity(DEFAULT_SCROLL_SENSITIVITY)
        }
    }

    @Test
    fun appearanceIsPersistedAndObservedByANewRepository() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = AppSettingsRepository(context)
        try {
            repository.setThemeMode(ThemeMode.DARK)
            repository.setUseDynamicColor(false)

            val reloaded = AppSettingsRepository(context)
            val settings = withTimeout(5_000L) {
                reloaded.settings.first {
                    it.themeMode == ThemeMode.DARK && !it.useDynamicColor
                }
            }

            assertEquals(ThemeMode.DARK, settings.themeMode)
            assertEquals(false, settings.useDynamicColor)
        } finally {
            repository.setThemeMode(ThemeMode.SYSTEM)
            repository.setUseDynamicColor(true)
        }
    }

    @Test
    fun mjpegFrameDetectionIsOffByDefaultAndPersistsExplicitChanges() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = AppSettingsRepository(context)
        try {
            repository.setMjpegFrameDetectionEnabled(false)
            assertEquals(false, AppSettingsRepository(context).settings.first().mjpegFrameDetectionEnabled)

            repository.setMjpegFrameDetectionEnabled(true)
            val reloaded = AppSettingsRepository(context)
            val settings = withTimeout(5_000L) {
                reloaded.settings.first { it.mjpegFrameDetectionEnabled }
            }

            assertEquals(true, settings.mjpegFrameDetectionEnabled)
        } finally {
            repository.setMjpegFrameDetectionEnabled(false)
        }
    }
}
