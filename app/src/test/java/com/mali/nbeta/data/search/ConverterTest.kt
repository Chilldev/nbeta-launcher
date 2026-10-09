package com.mali.nbeta.data.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConverterTest {
    @Test fun lengthsMassVolume() {
        assertEquals("5 km = 3.10686 mi", Converter.convert("5 km to mi"))
        assertEquals("12 in = 30.48 cm", Converter.convert("12 inches in cm"))
        assertEquals("1 lb = 0.453592 kg", Converter.convert("1 lb to kg"))
        assertEquals("2 cup = 473.176 ml", Converter.convert("2 cups to ml"))
    }

    @Test fun temperatureAndData() {
        assertEquals("100 °C = 212 °F", Converter.convert("100 c to f"))
        assertEquals("70 °F = 21.1111 °C", Converter.convert("70 fahrenheit in celsius"))
        assertEquals("2.5 GB = 2500 MB", Converter.convert("2,5 gb = mb"))
        assertEquals("90 min = 1.5 h", Converter.convert("90 minutes to hours"))
    }

    @Test fun notConversions() {
        assertNull(Converter.convert("5 km to kg"))
        assertNull(Converter.convert("weather in london"))
        assertNull(Converter.convert("5 km"))
    }
}
