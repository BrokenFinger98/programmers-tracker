package com.brokenfinger.tracker.domain.calc.runner

/** One parameter of the user's C# `solution(...)`, as declared. */
data class CsharpParameter(val type: String, val name: String)

/**
 * The user's own C# `solution(...)` declaration (#88) — the last language in the measured
 * order, and typed like Java's.
 *
 * Anchored on the skeletons measured from the actual editor (2026-08-07, title-guarded):
 * 120803 declares `public int solution(int num1, int num2)` on `public class Solution`,
 * 120817 takes `int[]` with **no length parameters** (C# arrays carry their own), 120860
 * takes the rectangular **`int[,]`** — not `int[][]` — and 120822 `string`. Admitted types
 * are the scalars, their `T[]`, and `int[,]`; any other `[,]` kind refuses as unmeasured.
 */
data class CsharpSignature(val returnType: String, val parameters: List<CsharpParameter>) {
    companion object {
        private val SCALARS = setOf("int", "long", "double", "bool", "string")

        private val DECLARATION = Regex("""public\s+([\w\[\],]+)\s+solution\s*\(([^)]*)\)""")

        private val PARAMETER = Regex("""^([\w\[\],]+)\s+(\w+)$""")

        fun of(code: String): CsharpSignature? {
            val match = DECLARATION.find(code) ?: return null
            val returnType = match.groupValues[1].takeIf { admitted(it) } ?: return null
            val parameters = parametersOf(match.groupValues[2]) ?: return null
            return CsharpSignature(returnType, parameters)
        }

        private fun parametersOf(list: String): List<CsharpParameter>? {
            val trimmed = list.trim()
            if (trimmed.isEmpty()) return emptyList()
            return splitOutsideBrackets(trimmed).map { raw ->
                val p = PARAMETER.find(raw.trim()) ?: return null
                val type = p.groupValues[1].takeIf { admitted(it) } ?: return null
                CsharpParameter(type, p.groupValues[2])
            }
        }

        /** `int[,]` carries a comma of its own — a naive split would cut the type in half. */
        private fun splitOutsideBrackets(list: String): List<String> {
            val parts = mutableListOf<String>()
            val current = StringBuilder()
            var depth = 0
            list.forEach { ch ->
                when {
                    ch == ',' && depth == 0 -> {
                        parts += current.toString()
                        current.clear()
                    }
                    else -> {
                        if (ch == '[') depth++
                        if (ch == ']') depth--
                        current.append(ch)
                    }
                }
            }
            parts += current.toString()
            return parts
        }

        private fun admitted(type: String): Boolean = when {
            type in SCALARS -> true
            type == "int[,]" -> true
            type.endsWith("[]") -> type.removeSuffix("[]") in SCALARS
            else -> false
        }
    }
}
