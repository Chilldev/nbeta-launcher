package com.mali.nbeta.baselineprofile

import android.graphics.Point
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real multi-touch on the home screen: pinch opens edit mode, two-finger swipe up opens search. Uses default bindings. */
@RunWith(AndroidJUnit4::class)
class GestureTest {
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val w get() = device.displayWidth
    private val h get() = device.displayHeight

    @Before fun home() {
        device.pressHome()
        device.wait(Until.hasObject(By.pkg(PACKAGE).depth(0)), 5_000)
        device.waitForIdle()
    }

    private fun twoFinger(fromY: Int, toY: Int) {
        val root = device.findObject(UiSelector().packageName(PACKAGE))
        root.performTwoPointerGesture(
            Point(w / 3, fromY), Point(2 * w / 3, fromY),
            Point(w / 3, toY), Point(2 * w / 3, toY),
            25,
        )
    }

    @Test fun pinchInOpensEditMode() {
        val root = device.findObject(UiSelector().packageName(PACKAGE))
        root.performTwoPointerGesture(
            Point(w / 6, h / 3), Point(5 * w / 6, 2 * h / 3),
            Point(w / 2 - 20, h / 2 - 20), Point(w / 2 + 20, h / 2 + 20),
            25,
        )
        assertTrue("edit bar visible", device.wait(Until.hasObject(By.text("Add page")), 3_000))
        device.pressHome()
    }

    @Test fun twoFingerSwipeUpOpensSearch() {
        twoFinger(h * 2 / 3, h / 4)
        assertTrue("search field focused", device.wait(Until.hasObject(By.clazz("android.widget.EditText").focused(true)), 3_000))
        device.pressHome()
    }

    @Test fun twoFingerSwipeDownOpensQuickSettings() {
        twoFinger(h / 3, h * 2 / 3)
        assertTrue("system shade open", device.wait(Until.hasObject(By.pkg("com.android.systemui").res("com.android.systemui", "quick_settings_panel")), 3_000) ||
            device.wait(Until.hasObject(By.pkg("com.android.systemui").descContains("Internet")), 2_000))
        device.pressBack()
    }
}
