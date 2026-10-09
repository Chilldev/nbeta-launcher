package com.mali.nbeta.baselineprofile

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

/** ./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest — compares with and without the profile. */
@RunWith(AndroidJUnit4::class)
class LauncherBenchmarks {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test fun coldStartNoProfile() = coldStart(CompilationMode.None())
    @Test fun coldStartWithProfile() = coldStart(CompilationMode.Partial(BaselineProfileMode.Require))

    @Test fun scrollingWithProfile() = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
        iterations = 5,
        startupMode = StartupMode.WARM,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
        launcherJourney()
    }

    private fun coldStart(mode: CompilationMode) = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = mode,
        iterations = 8,
        startupMode = StartupMode.COLD,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
    }
}
