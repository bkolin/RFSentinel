package com.rfsentinel.app.oui

/**
 * A user's "re:" watchlist pattern, matched against device names and SSIDs on the scanner threads.
 *
 * A pattern can come from anyone (an imported watchlist file), and Java's regex engine backtracks: a pattern
 * like (a+)+$ takes exponential time on the wrong input. So patterns are length-capped, compiled once, and
 * every match runs over a [CharSequence] that gives up after [MAX_STEPS] character reads - a runaway pattern
 * then simply doesn't match instead of stalling the scan. Names are short (SSIDs are at most 32 bytes), so
 * real patterns finish in a few hundred reads.
 */
class NamePattern private constructor(val source: String, private val regex: Regex) {

    /** Whether [name] contains a match (like grep: anchor with ^ and $ for a whole-name match). */
    fun matches(name: String): Boolean = try {
        regex.containsMatchIn(Bounded(name.take(MAX_INPUT)))
    } catch (_: TooSlow) {
        false
    }

    private class TooSlow : RuntimeException(null, null, false, false)

    /** [s], but reading more than [MAX_STEPS] characters in total throws [TooSlow]. */
    private class Bounded(private val s: String, private val budget: IntArray = intArrayOf(MAX_STEPS)) : CharSequence {
        override val length get() = s.length
        override fun get(index: Int): Char {
            if (--budget[0] < 0) throw TooSlow()
            return s[index]
        }
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
            Bounded(s.substring(startIndex, endIndex), budget)
        override fun toString() = s
    }

    companion object {
        const val MAX_LENGTH = 200
        /** Longer names are cut: no SSID or advertised name comes close. */
        private const val MAX_INPUT = 128
        private const val MAX_STEPS = 50_000

        /** The compiled pattern (case-insensitive), or null when [source] is blank, too long or not a valid regex. */
        fun compile(source: String): NamePattern? {
            if (source.isBlank() || source.length > MAX_LENGTH) return null
            return runCatching { NamePattern(source, Regex(source, RegexOption.IGNORE_CASE)) }.getOrNull()
        }

        /** Why [source] can't be used, for the form; null when it can. */
        fun problem(source: String): String? = when {
            source.isBlank() -> "Enter a pattern"
            source.length > MAX_LENGTH -> "Keep the pattern under $MAX_LENGTH characters"
            else -> runCatching { Regex(source); null }.getOrElse { "Not a valid pattern: ${it.message?.lineSequence()?.firstOrNull()}" }
        }
    }
}
