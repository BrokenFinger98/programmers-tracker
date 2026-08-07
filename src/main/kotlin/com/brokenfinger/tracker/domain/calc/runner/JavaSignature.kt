package com.brokenfinger.tracker.domain.calc.runner

/** One parameter of the user's `solution(...)`, as declared. */
data class JavaParameter(val type: String, val name: String)

/**
 * The user's own `solution(...)` declaration — the only trustworthy source of argument
 * types. The wire sends `"3, 2"` with neither names nor arity (§7.1); JSON cannot tell an
 * `int` from a `long`; the signature can.
 *
 * **Shallow on purpose.** The measured skeletons declare one public `solution` method with
 * simple types — primitives, `String`, and their arrays. Generics, varargs and anything
 * else the regex below does not admit return null, and the generator refuses. A shallow
 * parser that refuses beats a deep one that is silently wrong about edge cases, because a
 * wrong type mapping *compiles* and then tests the wrong thing.
 */
data class JavaSignature(val returnType: String, val parameters: List<JavaParameter>) {
    companion object {
        /** `int` · `long` · `double` · `boolean` · `String` and their `[]`/`[][]` forms. */
        private const val TYPE = """(?:int|long|double|boolean|String)(?:\[\])*"""

        private val DECLARATION =
            Regex("""public\s+($TYPE)\s+solution\s*\(([^)]*)\)""")

        private val PARAMETER = Regex("""^($TYPE)\s+(\w+)$""")

        fun of(code: String): JavaSignature? {
            val match = DECLARATION.find(code) ?: return null
            val (returnType, paramList) = match.destructured
            val trimmed = paramList.trim()
            if (trimmed.isEmpty()) return JavaSignature(returnType, emptyList())
            val parameters = trimmed.split(',').map { raw ->
                val p = PARAMETER.find(raw.trim()) ?: return null
                JavaParameter(p.groupValues[1], p.groupValues[2])
            }
            return JavaSignature(returnType, parameters)
        }
    }
}
