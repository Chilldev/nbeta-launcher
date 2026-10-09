package com.mali.nbeta.data.search

import java.math.BigDecimal
import java.math.MathContext
import kotlin.math.E
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Small recursive-descent evaluator for the search bar: + - * / ^ %, parentheses, common functions and constants. */
object Calculator {
    private val looksLikeMath = Regex("[0-9.)πe]\\s*[-+*/^×÷%]|^\\s*(sqrt|sin|cos|tan|log|ln|abs)\\s*\\(|√")

    fun evaluate(input: String): String? {
        val src = input.trim().removeSuffix("=").trim()
        if (src.length < 2 || !looksLikeMath.containsMatchIn(src)) return null
        return try {
            val p = Parser(src.replace('×', '*').replace('÷', '/').replace(',', '.').replace("√", "sqrt"))
            val v = p.parseExpression()
            if (!p.atEnd() || v.isNaN() || v.isInfinite()) null else format(v)
        } catch (_: Exception) {
            null
        }
    }

    private fun format(v: Double): String {
        if (abs(v) >= 1e15 || (v != 0.0 && abs(v) < 1e-9)) return "%.6e".format(v)
        return BigDecimal(v).round(MathContext(12)).stripTrailingZeros().toPlainString()
    }

    private class Parser(val s: String) {
        var i = 0

        fun atEnd(): Boolean {
            skip()
            return i >= s.length
        }

        private fun skip() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        private fun eat(c: Char): Boolean {
            skip()
            if (i < s.length && s[i] == c) {
                i++
                return true
            }
            return false
        }

        fun parseExpression(): Double {
            var v = parseTerm()
            while (true) {
                v = when {
                    eat('+') -> v + parseTerm()
                    eat('-') -> v - parseTerm()
                    else -> return v
                }
            }
        }

        private fun parseTerm(): Double {
            var v = parseFactor()
            while (true) {
                v = when {
                    eat('*') -> v * parseFactor()
                    eat('/') -> v / parseFactor()
                    eat('%') -> {
                        // "50%" alone means 0.5; "10 % 3" is modulo.
                        skip()
                        if (i >= s.length || s[i] == ')' || s[i] in "+-*/") v / 100 else v % parseFactor()
                    }
                    else -> return v
                }
            }
        }

        private fun parseFactor(): Double {
            val base = parseUnary()
            return if (eat('^')) base.pow(parseFactor()) else base
        }

        private fun parseUnary(): Double = when {
            eat('-') -> -parseUnary()
            eat('+') -> parseUnary()
            else -> parsePrimary()
        }

        private fun parsePrimary(): Double {
            skip()
            if (eat('(')) {
                val v = parseExpression()
                eat(')')
                return v
            }
            val start = i
            if (i < s.length && (s[i].isDigit() || s[i] == '.')) {
                while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
                if (i < s.length && (s[i] == 'e' || s[i] == 'E') && i + 1 < s.length && (s[i + 1].isDigit() || s[i + 1] == '-')) {
                    i += 2
                    while (i < s.length && s[i].isDigit()) i++
                }
                return s.substring(start, i).toDouble()
            }
            while (i < s.length && (s[i].isLetter() || s[i] == 'π')) i++
            val name = s.substring(start, i).lowercase()
            return when (name) {
                "pi", "π" -> PI
                "e" -> E
                "sqrt" -> sqrt(arg())
                "sin" -> sin(Math.toRadians(arg()))
                "cos" -> cos(Math.toRadians(arg()))
                "tan" -> tan(Math.toRadians(arg()))
                "log" -> log10(arg())
                "ln" -> ln(arg())
                "abs" -> abs(arg())
                else -> throw IllegalArgumentException("Unexpected '$name'")
            }
        }

        private fun arg(): Double = if (eat('(')) parseExpression().also { eat(')') } else parseUnary()
    }
}
