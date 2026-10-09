package com.mali.nbeta.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until

const val PACKAGE = "com.mali.nbeta"

/** The paths a launcher user hits constantly: home, drawer, search, feed. Shared by profile generation and benchmarks. */
fun MacrobenchmarkScope.launcherJourney() {
    val w = device.displayWidth
    val h = device.displayHeight
    device.waitForIdle()

    // Drawer: open, scroll, search, close.
    device.swipe(w / 2, (h * 0.65).toInt(), w / 2, (h * 0.2).toInt(), 12)
    device.wait(Until.hasObject(By.clazz("android.widget.EditText")), 2_000)
    device.findObject(By.clazz("android.widget.EditText"))?.text = "set"
    device.waitForIdle()
    device.findObject(By.clazz("android.widget.EditText"))?.text = ""
    device.pressBack()
    device.swipe(w / 2, (h * 0.75).toInt(), w / 2, (h * 0.25).toInt(), 8)
    device.waitForIdle()
    device.swipe(w / 2, (h * 0.25).toInt(), w / 2, (h * 0.75).toInt(), 8)
    device.waitForIdle()
    device.pressBack()
    device.waitForIdle()

    // Feed: swipe in, scroll, swipe back.
    device.swipe((w * 0.08).toInt(), h / 2, (w * 0.92).toInt(), h / 2, 12)
    device.waitForIdle()
    repeat(3) {
        device.swipe(w / 2, (h * 0.8).toInt(), w / 2, (h * 0.2).toInt(), 6)
        device.waitForIdle()
    }
    device.pressBack()
    device.waitForIdle()
}
