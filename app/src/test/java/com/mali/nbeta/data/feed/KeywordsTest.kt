package com.mali.nbeta.data.feed

import com.mali.nbeta.data.LauncherSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeywordsTest {
    @Test fun wholeWordsAndPlurals() {
        val m = KeywordMatcher(listOf("election", "AI"))
        assertTrue(m.matches("Elections: what to expect"))
        assertTrue(m.matches("New AI model released"))
        assertFalse(m.matches("Airline prices climb")) // "AI" must not match inside "Airline"
        assertFalse(m.matches("Selection of the week"))
    }

    @Test fun phrasesAccentsArabic() {
        assertTrue(KeywordMatcher(listOf("real madrid")).matches("Real Madrid win again"))
        assertTrue(KeywordMatcher(listOf("cafe")).matches("Best café in town"))
        assertTrue(KeywordMatcher(listOf("الأهلي")).matches(null, "فوز الاهلي في المباراة"))
    }

    private fun item(id: String, src: String, title: String, ageMin: Long = 5) =
        FeedItem(id, src, title, "https://x/$id", published = System.currentTimeMillis() - ageMin * 60_000)

    @Test fun alertSelection() {
        val s = LauncherSettings(newsAlerts = true, alertSources = setOf("bbc"), alertKeywords = listOf("spacex"), mutedKeywords = listOf("football"))
        val fresh = listOf(
            item("1", "bbc", "Storm hits coast"),
            item("2", "verge", "SpaceX launches Starship"),
            item("3", "verge", "New phone review"),
            item("4", "bbc", "Football final tonight"),
            item("5", "bbc", "Old news", ageMin = 600),
        )
        val picked = NewsNotifier.select(fresh, s, alreadyNotified = setOf())
        assertEquals(setOf("1", "2"), picked.map { it.id }.toSet())
        assertTrue(NewsNotifier.select(fresh, s, alreadyNotified = setOf("1", "2")).isEmpty())
        assertTrue(NewsNotifier.select(fresh, s.copy(newsAlerts = false), setOf()).isEmpty())
    }
}
