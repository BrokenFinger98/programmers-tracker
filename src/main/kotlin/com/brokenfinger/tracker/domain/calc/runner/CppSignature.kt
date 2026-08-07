package com.brokenfinger.tracker.domain.calc.runner

/** One parameter of the user's `solution(...)`, as declared, with `const`/`&` stripped. */
data class CppParameter(val type: String, val name: String)

/**
 * The user's own C++ `solution(...)` declaration (#80) — like [JavaSignature], the only
 * trustworthy source of argument types.
 *
 * Anchored on the skeletons measured from the actual editor (2026-08-07): 120803 declares
 * `int solution(int num1, int num2)`, 120817 `double solution(vector<int> numbers)`, 12950
 * `vector<vector<int>> solution(...)` — all by-value, all under `using namespace std;`.
 * Admitted types are those scalars plus `vector<...>` of them at any depth; `std::` and
 * `const`/`&` decorations are stripped because they change nothing about how the runner
 * builds arguments (it passes named locals, which bind either reference form). Anything
 * else — `map`, `auto`, rvalue references — returns null and the generator refuses.
 */
data class CppSignature(val returnType: String, val parameters: List<CppParameter>) {
    companion object {
        private val SCALARS = setOf("int", "long long", "double", "bool", "string")

        /**
         * C++ has no `public`-like anchor, so every `<type> solution(` match is a candidate
         * and validation picks the declaration: a call site like `return solution(a)` puts
         * `return` where the type belongs, which [typeOf] rejects.
         */
        private val DECLARATION = Regex("""([\w:<>, ]+?)\s+solution\s*\(([^)]*)\)""")

        private val PARAMETER = Regex("""^(?:const\s+)?(.+?)\s*&?\s*(\w+)$""")

        fun of(code: String): CppSignature? = DECLARATION.findAll(code).firstNotNullOfOrNull { candidate ->
            val returnType = typeOf(candidate.groupValues[1]) ?: return@firstNotNullOfOrNull null
            parametersOf(candidate.groupValues[2])?.let { CppSignature(returnType, it) }
        }

        private fun parametersOf(list: String): List<CppParameter>? {
            val trimmed = list.trim()
            if (trimmed.isEmpty()) return emptyList()
            return trimmed.split(',').map { raw ->
                val match = PARAMETER.find(raw.trim()) ?: return null
                val type = typeOf(match.groupValues[1]) ?: return null
                CppParameter(type, match.groupValues[2])
            }
        }

        /** The type in canonical spelling, or null if it is not an admitted type. */
        private fun typeOf(raw: String): String? {
            val type = raw.replace("std::", "")
                .replace(WHITESPACE, " ")
                .replace(" <", "<").replace("< ", "<")
                .replace(" >", ">").replace("> ", ">")
                .trim()
            return type.takeIf { admitted(it) }
        }

        private fun admitted(type: String): Boolean = when {
            type in SCALARS -> true
            type.startsWith("vector<") && type.endsWith(">") -> admitted(type.removeSurrounding("vector<", ">"))
            else -> false
        }

        private val WHITESPACE = Regex("""\s+""")
    }
}
