package com.mali.nbeta.data.search

import java.math.BigDecimal
import java.math.MathContext

/** "5 km to mi", "70 f in c", "2.5 gb = mb": unit conversion for the search bar. */
object Converter {
    private enum class Kind { Length, Mass, Volume, Temperature, Speed, Data, Time, Area }

    /** factor converts to the kind's base unit (temperature is handled separately). */
    private class Unit(val symbol: String, val kind: Kind, val factor: Double)

    private val units: Map<String, Unit> = buildMap {
        fun add(symbol: String, kind: Kind, factor: Double, vararg aliases: String) {
            val u = Unit(symbol, kind, factor)
            (listOf(symbol) + aliases).forEach { put(it.lowercase(), u) }
        }
        add("mm", Kind.Length, 0.001, "millimeter", "millimeters", "millimetre", "millimetres")
        add("cm", Kind.Length, 0.01, "centimeter", "centimeters", "centimetre", "centimetres")
        add("m", Kind.Length, 1.0, "meter", "meters", "metre", "metres")
        add("km", Kind.Length, 1000.0, "kilometer", "kilometers", "kilometre", "kilometres")
        add("in", Kind.Length, 0.0254, "inch", "inches", "\"")
        add("ft", Kind.Length, 0.3048, "foot", "feet", "'")
        add("yd", Kind.Length, 0.9144, "yard", "yards")
        add("mi", Kind.Length, 1609.344, "mile", "miles")
        add("nmi", Kind.Length, 1852.0, "nautical mile", "nautical miles")
        add("mg", Kind.Mass, 0.001, "milligram", "milligrams")
        add("g", Kind.Mass, 1.0, "gram", "grams")
        add("kg", Kind.Mass, 1000.0, "kilo", "kilos", "kilogram", "kilograms")
        add("t", Kind.Mass, 1_000_000.0, "tonne", "tonnes", "ton", "tons")
        add("oz", Kind.Mass, 28.349523125, "ounce", "ounces")
        add("lb", Kind.Mass, 453.59237, "lbs", "pound", "pounds")
        add("st", Kind.Mass, 6350.29318, "stone", "stones")
        add("ml", Kind.Volume, 0.001, "milliliter", "milliliters", "millilitre", "millilitres")
        add("l", Kind.Volume, 1.0, "liter", "liters", "litre", "litres")
        add("tsp", Kind.Volume, 0.00492892159375, "teaspoon", "teaspoons")
        add("tbsp", Kind.Volume, 0.01478676478125, "tablespoon", "tablespoons")
        add("fl oz", Kind.Volume, 0.0295735295625, "floz", "fluid ounce", "fluid ounces")
        add("cup", Kind.Volume, 0.2365882365, "cups")
        add("pt", Kind.Volume, 0.473176473, "pint", "pints")
        add("qt", Kind.Volume, 0.946352946, "quart", "quarts")
        add("gal", Kind.Volume, 3.785411784, "gallon", "gallons")
        add("°C", Kind.Temperature, 0.0, "c", "celsius", "centigrade", "°c")
        add("°F", Kind.Temperature, 0.0, "f", "fahrenheit", "°f")
        add("K", Kind.Temperature, 0.0, "k", "kelvin")
        add("km/h", Kind.Speed, 1 / 3.6, "kph", "kmh", "kmph")
        add("mph", Kind.Speed, 0.44704, "mi/h")
        add("m/s", Kind.Speed, 1.0, "mps")
        add("kn", Kind.Speed, 0.514444, "knot", "knots", "kt")
        add("B", Kind.Data, 1.0, "b", "byte", "bytes")
        add("KB", Kind.Data, 1e3, "kb", "kilobyte", "kilobytes")
        add("MB", Kind.Data, 1e6, "mb", "megabyte", "megabytes")
        add("GB", Kind.Data, 1e9, "gb", "gigabyte", "gigabytes")
        add("TB", Kind.Data, 1e12, "tb", "terabyte", "terabytes")
        add("KiB", Kind.Data, 1024.0, "kib")
        add("MiB", Kind.Data, 1048576.0, "mib")
        add("GiB", Kind.Data, 1073741824.0, "gib")
        add("s", Kind.Time, 1.0, "sec", "secs", "second", "seconds")
        add("min", Kind.Time, 60.0, "mins", "minute", "minutes")
        add("h", Kind.Time, 3600.0, "hr", "hrs", "hour", "hours")
        add("d", Kind.Time, 86400.0, "day", "days")
        add("wk", Kind.Time, 604800.0, "week", "weeks")
        add("m²", Kind.Area, 1.0, "m2", "sq m", "square meter", "square meters", "square metre", "square metres")
        add("km²", Kind.Area, 1e6, "km2", "sq km", "square kilometer", "square kilometers")
        add("ft²", Kind.Area, 0.09290304, "ft2", "sq ft", "square foot", "square feet")
        add("ha", Kind.Area, 10_000.0, "hectare", "hectares")
        add("ac", Kind.Area, 4046.8564224, "acre", "acres")
    }

    private val pattern = Regex("^\\s*(-?\\d+(?:[.,]\\d+)?)\\s*(.+?)\\s+(?:to|in|into|=|->|as)\\s+(.+?)\\s*$", RegexOption.IGNORE_CASE)

    /** e.g. "5 km = 3.10686 mi", or null when the query isn't a conversion. */
    fun convert(input: String): String? {
        val m = pattern.find(input) ?: return null
        val value = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val from = units[m.groupValues[2].trim().lowercase()] ?: return null
        val to = units[m.groupValues[3].trim().lowercase()] ?: return null
        if (from.kind != to.kind) return null
        val result = if (from.kind == Kind.Temperature) temperature(value, from.symbol, to.symbol) else value * from.factor / to.factor
        return "${format(value)} ${from.symbol} = ${format(result)} ${to.symbol}"
    }

    private fun temperature(v: Double, from: String, to: String): Double {
        val c = when (from) {
            "°F" -> (v - 32) * 5 / 9
            "K" -> v - 273.15
            else -> v
        }
        return when (to) {
            "°F" -> c * 9 / 5 + 32
            "K" -> c + 273.15
            else -> c
        }
    }

    private fun format(v: Double): String = BigDecimal(v).round(MathContext(6)).stripTrailingZeros().toPlainString()
}
