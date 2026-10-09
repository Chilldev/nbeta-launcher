package com.mali.nbeta.ui

import com.mali.nbeta.ui.home.formatTime
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTimeTest {
    @Test
    fun formats() {
        assertEquals("0:00", formatTime(0))
        assertEquals("0:00", formatTime(-500))
        assertEquals("1:23", formatTime(83_400))
        assertEquals("4:03", formatTime(243_000))
        assertEquals("1:02:05", formatTime(3_725_000))
    }
}
