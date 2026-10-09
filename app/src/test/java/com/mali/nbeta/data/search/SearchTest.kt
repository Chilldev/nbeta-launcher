package com.mali.nbeta.data.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalculatorTest {
    @Test fun arithmetic() {
        assertEquals("87", Calculator.evaluate("12*7+3"))
        assertEquals("2.5", Calculator.evaluate("5/2"))
        assertEquals("14", Calculator.evaluate("2*(3+4)"))
        assertEquals("1024", Calculator.evaluate("2^10"))
        assertEquals("21", Calculator.evaluate("3 × 7"))
    }

    @Test fun functionsAndPercent() {
        assertEquals("1.41421356237", Calculator.evaluate("sqrt(2)"))
        assertEquals("0.5", Calculator.evaluate("50%"))
        assertEquals("1", Calculator.evaluate("10 % 3"))
    }

    @Test fun notMath() {
        assertNull(Calculator.evaluate("google maps"))
        assertNull(Calculator.evaluate("42"))
        assertNull(Calculator.evaluate("e-mail"))
        assertNull(Calculator.evaluate("1/0"))
    }
}

class MatcherTest {
    private fun score(q: String, label: String) = Matcher.score(TextFold.fold(q), Searchable(label))

    @Test fun ranksPrefixAboveContains() {
        assertTrue(score("cal", "Calendar") > score("cal", "Local Calls"))
        assertTrue(score("maps", "Google Maps") > score("maps", "Roadmaps"))
    }

    @Test fun initialsAndCamelCase() {
        assertTrue(score("gm", "Google Maps") > 0)
        assertTrue(score("app", "WhatsApp") > score("app", "Snapper"))
    }

    @Test fun foldsAccentsHyphensAndArabic() {
        assertTrue(score("wifi", "Wi‑Fi") >= 600)
        assertTrue(score("cafe", "Café") >= 900)
        assertTrue(score("احمد", "أحمد") >= 900)
    }

    @Test fun fuzzyAndTypos() {
        assertTrue(score("ytmsc", "YT Music") > 0)
        assertTrue(score("spotfy", "Spotify") > 0)
        assertEquals(0, score("zzz", "Calendar"))
    }
}
