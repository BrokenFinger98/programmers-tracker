package com.brokenfinger.tracker.domain.calc.runner

/** One parameter of the user's `solution(...)`, as declared. */
data class KotlinParameter(val name: String, val type: String)

/**
 * The user's own Kotlin `solution(...)` declaration (#84) — like [JavaSignature], the only
 * trustworthy source of argument types.
 *
 * Anchored on the skeletons measured from the actual editor (2026-08-07): 120803 declares
 * `fun solution(num1: Int, num2: Int): Int` inside `class Solution`, 120817 takes the
 * **primitive** `IntArray` (not `Array<Int>`, not `List<Int>`), 12950 nests as
 * `Array<IntArray>`. Admitted types are exactly that pattern: the scalars, their primitive
 * arrays, and `Array<T>` over `String`, a primitive array, or another admitted `Array`.
 * A declared return type is required — an expression body infers one, and the declared
 * type is the only honest source for building the expected value.
 */
data class KotlinSignature(val returnType: String, val parameters: List<KotlinParameter>) {
    companion object {
        private val SCALARS = setOf("Int", "Long", "Double", "Boolean", "String")

        private val PRIMITIVE_ARRAYS = setOf("IntArray", "LongArray", "DoubleArray", "BooleanArray")

        private val DECLARATION = Regex("""fun\s+solution\s*\(([^)]*)\)\s*:\s*([\w<>]+)""")

        private val PARAMETER = Regex("""^(\w+)\s*:\s*([\w<>]+)$""")

        fun of(code: String): KotlinSignature? {
            val match = DECLARATION.find(code) ?: return null
            val returnType = match.groupValues[2].takeIf { admitted(it) } ?: return null
            val parameters = parametersOf(match.groupValues[1]) ?: return null
            return KotlinSignature(returnType, parameters)
        }

        private fun parametersOf(list: String): List<KotlinParameter>? {
            val trimmed = list.trim()
            if (trimmed.isEmpty()) return emptyList()
            return trimmed.split(',').map { raw ->
                val p = PARAMETER.find(raw.trim()) ?: return null
                val type = p.groupValues[2].takeIf { admitted(it) } ?: return null
                KotlinParameter(p.groupValues[1], type)
            }
        }

        private fun admitted(type: String): Boolean = when {
            type in SCALARS -> true
            type in PRIMITIVE_ARRAYS -> true
            type.startsWith("Array<") && type.endsWith(">") -> elementAdmitted(type.removeSurrounding("Array<", ">"))
            else -> false
        }

        /** Inside `Array<...>`: String, a primitive array, or another Array — never a boxed scalar. */
        private fun elementAdmitted(element: String): Boolean = when {
            element == "String" -> true
            element in PRIMITIVE_ARRAYS -> true
            element.startsWith("Array<") && element.endsWith(">") ->
                elementAdmitted(element.removeSurrounding("Array<", ">"))
            else -> false
        }
    }
}
