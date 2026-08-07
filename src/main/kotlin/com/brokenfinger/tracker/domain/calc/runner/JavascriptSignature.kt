package com.brokenfinger.tracker.domain.calc.runner

/**
 * `function solution(...)` — names and arity, nothing more, because JavaScript declares
 * nothing more (#82). The same one-check-fewer honesty as [PythonSignature].
 *
 * Only the measured declaration form (120803, editor capture 2026-08-07) is admitted, and
 * not merely out of caution: the runner loads `Solution.js` as a script, where a `function`
 * declaration lands on the global object but a `const solution = ...` stays script-scoped —
 * admitting the arrow form would generate a runner that cannot see the function it tests.
 * Rest parameters and defaults are refused as unmeasured shapes.
 */
data class JavascriptSignature(val parameters: List<String>) {
    companion object {
        private val DECLARATION = Regex("""function\s+solution\s*\(([^)]*)\)""")

        private val PARAMETER = Regex("""^\w+$""")

        fun of(code: String): JavascriptSignature? {
            val match = DECLARATION.find(code) ?: return null
            val list = match.groupValues[1].trim()
            if (list.isEmpty()) return JavascriptSignature(emptyList())
            val parameters = list.split(',').map { raw ->
                val name = raw.trim()
                if (!PARAMETER.matches(name)) return null
                name
            }
            return JavascriptSignature(parameters)
        }
    }
}
