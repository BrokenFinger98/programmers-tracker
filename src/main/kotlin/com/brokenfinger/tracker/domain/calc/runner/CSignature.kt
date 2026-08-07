package com.brokenfinger.tracker.domain.calc.runner

/** One **logical** parameter of the user's C `solution(...)` — see [CSignature]. */
sealed interface CParam {
    val name: String
}

/** `int n` · `long long n` · `double n` · `bool n`. */
data class CScalarParam(override val name: String, val type: String) : CParam

/** `const char* n` — no companion length, the terminator carries it (120822). */
data class CTextParam(override val name: String) : CParam

/** `int n[]` **plus** `size_t n_len` — two physical parameters, one wire value (120817). */
data class CIntArrayParam(override val name: String) : CParam

/** `int** n` **plus** `size_t n_rows` **plus** `size_t n_cols` (120860). */
data class CIntGridParam(override val name: String) : CParam

/** What `solution` returns — the measured kinds only. */
sealed interface CReturn

data class CScalarReturn(val type: String) : CReturn

/** Malloc'd `char*` (120822). */
data object CTextReturn : CReturn

/** Malloc'd `int*`; the length is implied by the expected answer (120821). */
data object CIntArrayReturn : CReturn

/**
 * The user's own C `solution(...)` declaration (#86). C is the language where **one wire
 * value is not one parameter**: the measured skeletons (editor captures 2026-08-07) pass a
 * 1-D array as `int name[], size_t name_len` and a 2-D array as `int** name, size_t
 * name_rows, size_t name_cols`. This parser reads the physical list and groups it into
 * logical parameters, refusing any grouping the skeletons never showed — an unpaired
 * `size_t`, a mis-named length, a string array, a non-int array element.
 */
data class CSignature(val returnType: CReturn, val parameters: List<CParam>) {
    companion object {
        private val SCALARS = setOf("int", "long long", "double", "bool")

        private val DECLARATION = Regex("""([\w* ]+?)\s*\bsolution\s*\(([^)]*)\)""")

        private val TEXT = Regex("""^const char\*(\w+)$""")
        private val SIZE = Regex("""^size_t (\w+)$""")
        private val ARRAY = Regex("""^int (\w+)\[\]$""")
        private val GRID = Regex("""^int\*\*(\w+)$""")
        private val SCALAR = Regex("""^(int|long long|double|bool) (\w+)$""")

        fun of(code: String): CSignature? = DECLARATION.findAll(code).firstNotNullOfOrNull { candidate ->
            val returnType = returnOf(candidate.groupValues[1]) ?: return@firstNotNullOfOrNull null
            parametersOf(candidate.groupValues[2])?.let { CSignature(returnType, it) }
        }

        private fun returnOf(raw: String): CReturn? = when (val type = normalized(raw)) {
            in SCALARS -> CScalarReturn(type)
            "char*" -> CTextReturn
            "int*" -> CIntArrayReturn
            else -> null
        }

        private fun parametersOf(list: String): List<CParam>? {
            val trimmed = list.trim()
            if (trimmed.isEmpty() || trimmed == "void") return emptyList()
            val physicals = trimmed.split(',').map { physicalOf(normalized(it)) ?: return null }
            return grouped(physicals)
        }

        /** Collapses whitespace and glues `*` to the type so star placement does not matter. */
        private fun normalized(raw: String): String = raw
            .replace(Regex("""\s+"""), " ")
            .replace(" *", "*").replace("* ", "*")
            .trim()

        // The physical parameters, before grouping ------------------------------------------

        private sealed interface Physical

        private data class PScalar(val name: String, val type: String) : Physical

        private data class PText(val name: String) : Physical

        private data class PArray(val name: String) : Physical

        private data class PGrid(val name: String) : Physical

        private data class PSize(val name: String) : Physical

        private fun physicalOf(cleaned: String): Physical? {
            TEXT.find(cleaned)?.let { return PText(it.groupValues[1]) }
            SIZE.find(cleaned)?.let { return PSize(it.groupValues[1]) }
            ARRAY.find(cleaned)?.let { return PArray(it.groupValues[1]) }
            GRID.find(cleaned)?.let { return PGrid(it.groupValues[1]) }
            SCALAR.find(cleaned)?.let { return PScalar(it.groupValues[2], it.groupValues[1]) }
            return null
        }

        /** An array head consumes its measured-convention size parameters or the whole parse refuses. */
        private fun grouped(physicals: List<Physical>): List<CParam>? {
            val logical = mutableListOf<CParam>()
            var i = 0
            while (i < physicals.size) {
                when (val p = physicals[i]) {
                    is PScalar -> logical += CScalarParam(p.name, p.type).also { i += 1 }
                    is PText -> logical += CTextParam(p.name).also { i += 1 }
                    is PArray -> {
                        val len = physicals.getOrNull(i + 1) as? PSize ?: return null
                        if (len.name != "${p.name}_len") return null
                        logical += CIntArrayParam(p.name).also { i += 2 }
                    }
                    is PGrid -> {
                        val rows = physicals.getOrNull(i + 1) as? PSize ?: return null
                        val cols = physicals.getOrNull(i + 2) as? PSize ?: return null
                        if (rows.name != "${p.name}_rows" || cols.name != "${p.name}_cols") return null
                        logical += CIntGridParam(p.name).also { i += 3 }
                    }
                    is PSize -> return null
                }
            }
            return logical
        }
    }
}
