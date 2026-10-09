package com.mali.nbeta.data.search

import java.text.Normalizer

/** Folds case, accents, Arabic diacritics/letter variants and punctuation so "cafe" finds "Café" and "احمد" finds "أحمد". */
object TextFold {
    private val marks = Regex("\\p{Mn}+")
    private val sep = Regex("[\\s\\p{Punct}·•–—_]+")

    fun fold(s: String): String {
        var t = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        t = marks.replace(t, "")
        val sb = StringBuilder(t.length)
        for (c in t) {
            sb.append(
                when (c) {
                    'أ', 'إ', 'آ', 'ٱ' -> 'ا'
                    'ة' -> 'ه'
                    'ى' -> 'ي'
                    'ـ' -> continue // tatweel
                    'ß' -> 's'
                    else -> c
                },
            )
        }
        return sb.toString()
    }

    fun words(folded: String): List<String> = folded.split(sep).filter { it.isNotEmpty() }
}

/** Precomputed, folded forms of one searchable label. Build once per data change, not per keystroke. */
class Searchable(label: String, extra: String? = null) {
    val folded = TextFold.fold(label)
    val compact = folded.replace(" ", "")
    val words = TextFold.words(folded) + splitCamel(label)
    val initials = TextFold.words(folded).joinToString("") { it.take(1) }
    val extra = extra?.let { TextFold.fold(it) }

    private fun splitCamel(s: String): List<String> {
        // "WhatsApp" -> "whats", "app" so "app" matches the second half.
        val parts = s.split(Regex("(?<=[a-z])(?=[A-Z])")).map { TextFold.fold(it) }.filter { it.isNotEmpty() }
        return if (parts.size > 1) parts.drop(1) else emptyList()
    }
}

object Matcher {
    /**
     * Returns 0 for no match; higher is better. Tiers are wide apart so ranking within a tier (frecency, length)
     * never jumps a better kind of match.
     */
    fun score(query: String, target: Searchable): Int {
        if (query.isEmpty()) return 0
        val f = target.folded
        return when {
            f == query -> 1000
            f.startsWith(query) -> 900 - (f.length - query.length).coerceAtMost(50)
            target.words.any { it.startsWith(query) } -> 800 - (f.length - query.length).coerceAtMost(50)
            target.compact.startsWith(query) -> 760
            query.length >= 2 && target.initials.startsWith(query) -> 720
            f.contains(query) -> 600 - f.indexOf(query).coerceAtMost(50)
            target.extra != null && target.extra.contains(query) -> 400
            else -> fuzzy(query, target)
        }
    }

    private fun fuzzy(q: String, t: Searchable): Int {
        if (q.length < 3) return 0
        // In-order subsequence ("gmps" -> "google maps"), rewarding tight matches.
        val s = t.compact
        var qi = 0
        var first = -1
        var last = -1
        for (i in s.indices) {
            if (qi < q.length && s[i] == q[qi]) {
                if (first < 0) first = i
                last = i
                qi++
            }
        }
        if (qi == q.length) {
            val spread = last - first + 1 - q.length
            return (450 - spread * 15).coerceAtLeast(260)
        }
        // One typo tolerance against word prefixes ("whatsap", "spotfy").
        if (q.length >= 4) {
            for (w in t.words + t.compact) {
                val prefix = w.take(q.length + 1)
                if (damerau(q, prefix.take(q.length)) <= 1 || damerau(q, prefix) <= 1) return 250
            }
        }
        return 0
    }

    private fun damerau(a: String, b: String): Int {
        val n = a.length
        val m = b.length
        if (abs(n - m) > 1) return 2
        val d = Array(n + 1) { IntArray(m + 1) }
        for (i in 0..n) d[i][0] = i
        for (j in 0..m) d[0][j] = j
        for (i in 1..n) for (j in 1..m) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
        }
        return d[n][m]
    }

    private fun abs(x: Int) = if (x < 0) -x else x
}
