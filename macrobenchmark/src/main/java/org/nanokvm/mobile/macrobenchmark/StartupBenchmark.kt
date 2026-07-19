package org.nanokvm.mobile.macrobenchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun coldStartupNoCompilation() = startup(StartupMode.COLD, CompilationMode.None())

    @Test
    fun coldStartupWithBaselineProfile() = startup(
        StartupMode.COLD,
        CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require),
    )

    @Test
    fun warmStartupWithBaselineProfile() = startup(
        StartupMode.WARM,
        CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require),
    )

    @Test
    fun hotStartupWithBaselineProfile() = startup(
        StartupMode.HOT,
        CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require),
    )

    private fun startup(
        startupMode: StartupMode,
        compilationMode: CompilationMode,
    ) = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric(), FrameTimingMetric()),
        compilationMode = compilationMode,
        startupMode = startupMode,
        iterations = 3,
        setupBlock = {
            pressHome()
        },
        measureBlock = {
            startActivityAndWait()
        },
    )

    private companion object {
        const val TARGET_PACKAGE = "org.nanokvm.mobile"
    }
}
