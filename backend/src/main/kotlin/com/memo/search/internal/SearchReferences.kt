package com.memo.search.internal

/**
 * Memo numbers as search keys (ADR-0054, design E05 §3): `M-2026-00042`, `m 2026 00042` and `M/2026/00042` share one
 * normal form, and the last digit group without leading zeros (`42`) finds a memo from `00042`. Plain B-tree lookups:
 * no trigram index (the migration role cannot create extensions).
 */
object SearchReferences {
    private val separators = Regex("[^A-Z0-9]+")
    private val digits = Regex("[0-9]+")
    private val lookalike = Regex("^[A-Za-z0-9]+([-/. ][A-Za-z0-9]+)*$")
    private const val MAX_LENGTH = 100

    /** The normal form of a stored number: upper case, each run of other characters one `-`. */
    fun norm(reference: String): String = reference.uppercase().replace(separators, "-").trim('-')

    /** The last digit group without leading zeros; null when there are no digits. */
    fun tail(reference: String): String? =
        digits
            .findAll(reference)
            .lastOrNull()
            ?.value
            ?.trimStart('0')
            ?.ifEmpty { "0" }

    /**
     * The number keys of a query: a query that is one number-like term with a digit is looked up by normal form, and a
     * query of digits alone also by its tail. Null when the query is not number-like.
     */
    fun keys(query: String): Pair<String?, String?>? {
        val q = query.trim()
        val numberLike = q.length in 1..MAX_LENGTH && q.any(Char::isDigit) && lookalike.matches(q)
        return if (numberLike) norm(q) to (if (q.all(Char::isDigit)) tail(q) else null) else null
    }
}
