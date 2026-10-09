package com.mali.nbeta.data.feed

import com.mali.nbeta.data.search.TextFold

/**
 * Whole-word keyword matching that ignores case, accents and Arabic letter variants, and also accepts a simple
 * plural ("election" matches "elections"). Multi-word keywords match as phrases.
 */
class KeywordMatcher(keywords: Collection<String>) {
    private val terms = keywords.map { normalize(it) }.filter { it.isNotEmpty() }

    val isEmpty get() = terms.isEmpty()

    fun matches(vararg texts: String?): Boolean {
        if (terms.isEmpty()) return false
        val t = " " + texts.filterNotNull().joinToString(" ") { normalize(it) } + " "
        return terms.any { term -> t.contains(" $term ") || t.contains(" ${term}s ") || t.contains(" ${term}es ") }
    }

    fun matches(item: FeedItem) = matches(item.title, item.summary)

    companion object {
        private val nonWord = Regex("[^\\p{L}\\p{N}]+")
        fun normalize(s: String) = nonWord.replace(TextFold.fold(s), " ").trim()
    }
}
