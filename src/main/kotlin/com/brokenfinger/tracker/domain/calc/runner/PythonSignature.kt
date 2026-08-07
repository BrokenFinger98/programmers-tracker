package com.brokenfinger.tracker.domain.calc.runner

/**
 * `def solution(...)` — names and arity, nothing more, because Python declares nothing more.
 * Arity can still refuse a mismatched example; types cannot, and the generator does not
 * pretend otherwise (#78).
 *
 * Shallow like [JavaSignature], for the same reason: the measured skeletons declare a bare
 * `def solution(a, b):`, and unmeasured shapes — `*args`, `**kwargs`, defaults — are refused
 * rather than half-parsed. Type hints are tolerated and discarded; they change nothing this
 * parser uses.
 */
data class PythonSignature(val parameters: List<String>) {
    companion object {
        private val DECLARATION = Regex("""def\s+solution\s*\(([^)]*)\)""")

        private val PARAMETER = Regex("""^(\w+)(?:\s*:\s*[\w\[\], ]+)?$""")

        fun of(code: String): PythonSignature? {
            val match = DECLARATION.find(code) ?: return null
            val list = match.groupValues[1].trim()
            if (list.isEmpty()) return PythonSignature(emptyList())
            val parameters = list.split(',').map { raw ->
                val p = PARAMETER.find(raw.trim()) ?: return null
                p.groupValues[1]
            }
            return PythonSignature(parameters)
        }
    }
}
