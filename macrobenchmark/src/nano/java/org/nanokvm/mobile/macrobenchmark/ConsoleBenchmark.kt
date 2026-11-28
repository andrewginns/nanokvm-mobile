package org.nanokvm.mobile.macrobenchmark

import android.content.ComponentName
import android.content.Intent
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConsoleBenchmark {
    @get:Rule
    val benchmark = MacrobenchmarkRule()

    @Test
    fun coldConsoleAndKeyboardWithoutCompilation() = measure(CompilationMode.None())

    @Test
    fun coldConsoleAndKeyboardWithCompilation() = measure(CompilationMode.Partial())

    private fun measure(compilation: CompilationMode) = benchmark.measureRepeated(
        packageName = CONSOLE_FIXTURE_PACKAGE,
        metrics = listOf(StartupTimingMetric(), FrameTimingMetric()),
        compilationMode = compilation,
        startupMode = StartupMode.COLD,
        iterations = 5,
        setupBlock = { pressHome() },
        measureBlock = {
            startActivityAndWait(
                Intent(Intent.ACTION_MAIN).setComponent(
                    ComponentName(CONSOLE_FIXTURE_PACKAGE, CONSOLE_FIXTURE_ACTIVITY),
                ),
            )
            checkNotNull(device.wait(Until.findObject(By.desc("Show view controls")), 8_000L)).click()
            checkNotNull(device.wait(Until.findObject(By.desc("Zoom in")), 4_000L)).click()
            device.pressBack()
            checkNotNull(device.wait(Until.findObject(By.desc("Show keyboard")), 8_000L)).click()
            check(device.wait(Until.hasObject(By.desc("Hide keyboard")), 4_000L))
            device.pressBack()
        },
    )
}

internal const val CONSOLE_FIXTURE_PACKAGE = "org.nanokvm.mobile"
internal const val CONSOLE_FIXTURE_ACTIVITY = "org.nanokvm.mobile.benchmark.ConsoleFixtureActivity"
