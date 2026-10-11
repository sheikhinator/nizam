package com.nizam.app.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A small, safe expression evaluator.
 *
 * This is the honest version of "let the agent write code": it cannot compile Kotlin at runtime
 * (Android has no compiler and Play forbids loading code), but it CAN write formulas that run
 * against a module's own fields. `cost / distance`, `weight * reps`, `round(kcal / 100)`.
 *
 * Nothing here can touch the filesystem, the network, or the app — it evaluates arithmetic over
 * numbers and returns a number. That's a feature, not a shortcoming.
 */
object Formula {

    private val functions = setOf("min", "max", "abs", "round", "sqrt", "floor", "ceil")

    fun evaluate(expression: String, variables: Map<String, Double>): Double? = try {
        val tokens = tokenize(expression.lowercase())
        val rpn = toRpn(tokens)
        evalRpn(rpn, variables.mapKeys { it.key.lowercase() })
    } catch (e: Exception) {
        null
    }

    /** Field keys referenced by a formula, so the UI can show what it depends on. */
    fun dependencies(expression: String): List<String> =
        tokenize(expression.lowercase())
            .filter { it.isNotBlank() && it[0].isLetter() && it !in functions }
            .distinct()

    private fun tokenize(input: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < input.length) {
            val c = input[i]
            when {
                c.isWhitespace() -> i++
                c.isDigit() || c == '.' -> {
                    val start = i
                    while (i < input.length && (input[i].isDigit() || input[i] == '.')) i++
                    out.add(input.substring(start, i))
                }
                c.isLetter() || c == '_' -> {
                    val start = i
                    while (i < input.length && (input[i].isLetterOrDigit() || input[i] == '_')) i++
                    out.add(input.substring(start, i))
                }
                c in "+-*/%^(),"  -> { out.add(c.toString()); i++ }
                else -> throw IllegalArgumentException("Unexpected character: $c")
            }
        }
        return out
    }

    private fun precedence(op: String) = when (op) {
        "+", "-" -> 1
        "*", "/", "%" -> 2
        "^" -> 3
        else -> 0
    }

    private fun toRpn(tokens: List<String>): List<String> {
        val output = mutableListOf<String>()
        val stack = ArrayDeque<String>()
        tokens.forEach { token ->
            when {
                token.toDoubleOrNull() != null -> output.add(token)
                token in functions -> stack.addLast(token)
                token == "," -> {
                    while (stack.isNotEmpty() && stack.last() != "(") output.add(stack.removeLast())
                }
                token == "(" -> stack.addLast(token)
                token == ")" -> {
                    while (stack.isNotEmpty() && stack.last() != "(") output.add(stack.removeLast())
                    if (stack.isNotEmpty()) stack.removeLast()
                    if (stack.isNotEmpty() && stack.last() in functions) output.add(stack.removeLast())
                }
                precedence(token) > 0 -> {
                    while (stack.isNotEmpty() && precedence(stack.last()) >= precedence(token)) {
                        output.add(stack.removeLast())
                    }
                    stack.addLast(token)
                }
                else -> output.add(token)   // a variable
            }
        }
        while (stack.isNotEmpty()) output.add(stack.removeLast())
        return output
    }

    private fun evalRpn(rpn: List<String>, variables: Map<String, Double>): Double? {
        val stack = ArrayDeque<Double>()
        rpn.forEach { token ->
            when {
                token.toDoubleOrNull() != null -> stack.addLast(token.toDouble())
                token in functions -> {
                    when (token) {
                        "min", "max" -> {
                            if (stack.size < 2) return null
                            val b = stack.removeLast(); val a = stack.removeLast()
                            stack.addLast(if (token == "min") min(a, b) else max(a, b))
                        }
                        else -> {
                            if (stack.isEmpty()) return null
                            val a = stack.removeLast()
                            stack.addLast(
                                when (token) {
                                    "abs" -> abs(a)
                                    "round" -> a.roundToInt().toDouble()
                                    "sqrt" -> if (a < 0) return null else sqrt(a)
                                    "floor" -> kotlin.math.floor(a)
                                    else -> kotlin.math.ceil(a)
                                }
                            )
                        }
                    }
                }
                precedence(token) > 0 -> {
                    if (stack.size < 2) return null
                    val b = stack.removeLast()
                    val a = stack.removeLast()
                    stack.addLast(
                        when (token) {
                            "+" -> a + b
                            "-" -> a - b
                            "*" -> a * b
                            "/" -> if (b == 0.0) return null else a / b
                            "%" -> if (b == 0.0) return null else a % b
                            else -> a.pow(b)
                        }
                    )
                }
                else -> stack.addLast(variables[token] ?: return null)
            }
        }
        return stack.lastOrNull()?.takeIf { it.isFinite() }
    }
}
