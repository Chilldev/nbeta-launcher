package com.mali.nbeta.data.reddit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RedditListingTest {
    private val json = """
    {"kind":"Listing","data":{"after":"t3_c","children":[
      {"kind":"t3","data":{"name":"t3_sticky","title":"Weekly thread","permalink":"/r/Android/comments/s/weekly/","stickied":true,"created_utc":1760000000.0}},
      {"kind":"t3","data":{"name":"t3_a","title":"Pixel 11 &amp; more","permalink":"/r/Android/comments/a/pixel/","author":"someone",
        "subreddit_name_prefixed":"r/Android","domain":"theverge.com","created_utc":1760000000.0,"over_18":false,
        "preview":{"images":[{"source":{"url":"https://preview.redd.it/big.jpg","width":3000},
          "resolutions":[{"url":"https://preview.redd.it/320.jpg","width":320},{"url":"https://preview.redd.it/960.jpg","width":960},{"url":"https://preview.redd.it/1080.jpg","width":1080}]}]}}},
      {"kind":"t3","data":{"name":"t3_b","title":"Question","permalink":"/r/Android/comments/b/q/","author":"asker","selftext":"Is **this** normal?",
        "domain":"self.Android","thumbnail":"self","created_utc":1760000100}},
      {"kind":"t3","data":{"name":"t3_nsfw","title":"x","permalink":"/r/x/comments/n/x/","over_18":true,"created_utc":1760000000}}
    ]}}
    """.trimIndent()

    @Test fun parsesListing() {
        val items = RedditListing.parse(json, "src")
        assertEquals(2, items.size) // sticky and NSFW skipped
        val a = items[0]
        assertEquals("Pixel 11 & more", a.title)
        assertEquals("https://www.reddit.com/r/Android/comments/a/pixel/", a.link)
        assertEquals("https://preview.redd.it/960.jpg", a.imageUrl)
        assertEquals("u/someone", a.author)
        assertEquals(1760000000000L, a.published)
        assertEquals("r/Android · theverge.com", a.summary)
        val b = items[1]
        assertNull(b.imageUrl)
        assertTrue(b.summary!!.startsWith("Is **this** normal?"))
    }

    @Test fun idsMatchRssEntries() {
        // RSS (Atom) entries use <id>t3_a</id>; the same post must not appear twice after signing in.
        val a = RedditListing.parse(json, "src")[0]
        assertEquals(com.mali.nbeta.data.feed.FeedParser.hash("src|t3_a"), a.id)
    }
}
