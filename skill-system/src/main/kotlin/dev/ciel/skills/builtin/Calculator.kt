package dev.ciel.skills.builtin

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow

/**
 * Exact-enough arithmetic without a language model (works offline). Supports
 * + - * / ^ %, parentheses, unary minus, implicit "x" for multiply,
 * functions (sqrt, cbrt, abs, sin, cos, tan, asin, acos, atan, ln, log, log2, exp, floor, ceil, round)
 * and constants pi, e. Trigonometry is in radians; use deg(..) / rad(..) to convert.
 */
object Calculator {
    class CalcException(message: String) : IllegalArgumentException(message)

    fun evaluate(expression: String): Double {
        val normalised = expression.lowercase()
            .replace("×", "*").replace("÷", "/").replace("−", "-")
            .replace(Regex("(?<=\\d)\\s*x\\s*(?=[\\d(])"), "*")
            .replace(",", "")
        val p = Parser(normalised)
        val v = p.parseExpression()
        p.skipWs()
        if (!p.atEnd()) throw CalcException("Unexpected '${p.rest()}'")
        if (v.isNaN()) throw CalcException("Result is undefined")
        return v
    }

    fun format(value: Double): String {
        if (value.isInfinite()) return if (value > 0) "∞" else "-∞"
        if (value == floor(value) && abs(value) < 1e15) return value.toLong().toString()
        val s = "%.10g".format(java.util.Locale.ROOT, value).trimEnd('0').trimEnd('.')
        return if (s.contains('e')) s else s
    }

    private class Parser(val s: String) {
        var i = 0
        fun atEnd() = i >= s.length
        fun rest() = s.substring(i).take(10)
        fun skipWs() { while (i < s.length && s[i].isWhitespace()) i++ }
        private fun peek(): Char? { skipWs(); return s.getOrNull(i) }

        fun parseExpression(): Double {
            var v = parseTerm()
            while (true) {
                when (peek()) {
                    '+' -> { i++; v += parseTerm() }
                    '-' -> { i++; v -= parseTerm() }
                    else -> return v
                }
            }
        }

        private fun parseTerm(): Double {
            var v = parseFactor()
            while (true) {
                when (peek()) {
                    '*' -> { i++; v *= parseFactor() }
                    '/' -> { i++; val d = parseFactor(); if (d == 0.0) throw CalcException("Division by zero"); v /= d }
                    '%' -> {
                        // "50%" alone means 0.5; "a % b" means modulo
                        val save = i; i++
                        val next = peek()
                        if (next == null || next == ')' || next in "+-*/^") { v /= 100.0 } else { i = save + 1; v %= parseFactor() }
                    }
                    '(' -> v *= parseFactor() // implicit multiplication: 2(3+4)
                    else -> return v
                }
            }
        }

        private fun parseFactor(): Double {
            val base = parseUnary()
            return if (peek() == '^') { i++; base.pow(parseFactor()) } else base // right-associative
        }

        private fun parseUnary(): Double = when (peek()) {
            '-' -> { i++; -parseUnary() }
            '+' -> { i++; parseUnary() }
            else -> parsePrimary()
        }

        private fun parsePrimary(): Double {
            val c = peek() ?: throw CalcException("Unexpected end of expression")
            if (c == '(') {
                i++
                val v = parseExpression()
                if (peek() != ')') throw CalcException("Missing ')'")
                i++
                return v
            }
            if (c.isDigit() || c == '.') {
                val start = i
                while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
                if (i < s.length && s[i] == 'e' && i + 1 < s.length && (s[i + 1].isDigit() || s[i + 1] == '-' || s[i + 1] == '+')) {
                    i++
                    if (s[i] == '-' || s[i] == '+') i++
                    while (i < s.length && s[i].isDigit()) i++
                }
                return s.substring(start, i).toDoubleOrNull() ?: throw CalcException("Bad number '${s.substring(start, i)}'")
            }
            if (c.isLetter()) {
                val start = i
                while (i < s.length && (s[i].isLetterOrDigit())) i++
                val name = s.substring(start, i)
                when (name) {
                    "pi", "π" -> return Math.PI
                    "e" -> return Math.E
                }
                val arg = if (peek() == '(') parsePrimary() else parseUnary()
                return when (name) {
                    "sqrt" -> { if (arg < 0) throw CalcException("Square root of a negative number"); kotlin.math.sqrt(arg) }
                    "cbrt" -> kotlin.math.cbrt(arg)
                    "abs" -> abs(arg)
                    "sin" -> kotlin.math.sin(arg)
                    "cos" -> kotlin.math.cos(arg)
                    "tan" -> kotlin.math.tan(arg)
                    "asin" -> kotlin.math.asin(arg)
                    "acos" -> kotlin.math.acos(arg)
                    "atan" -> kotlin.math.atan(arg)
                    "ln" -> { if (arg <= 0) throw CalcException("ln of a non-positive number"); kotlin.math.ln(arg) }
                    "log", "log10" -> { if (arg <= 0) throw CalcException("log of a non-positive number"); kotlin.math.log10(arg) }
                    "log2" -> kotlin.math.log2(arg)
                    "exp" -> kotlin.math.exp(arg)
                    "floor" -> floor(arg)
                    "ceil" -> kotlin.math.ceil(arg)
                    "round" -> kotlin.math.round(arg)
                    "deg" -> Math.toDegrees(arg)
                    "rad" -> Math.toRadians(arg)
                    else -> throw CalcException("Unknown function '$name'")
                }
            }
            throw CalcException("Unexpected '$c'")
        }
    }
}
