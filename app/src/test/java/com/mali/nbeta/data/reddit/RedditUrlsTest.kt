package com.mali.nbeta.data.reddit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RedditUrlsTest {
    private fun path(url: String) = RedditUrls.listingPath(url)?.substringBefore('?')

    @Test fun subredditFeeds() {
        assertEquals("/r/Android/hot", path("https://www.reddit.com/r/Android/.rss"))
        assertEquals("/r/Android/hot", path("https://old.reddit.com/r/Android/"))
        assertEquals("/r/Android/top", path("https://www.reddit.com/r/Android/top/.rss?t=week"))
        assertEquals("/r/Android+pixel/new", path("https://www.reddit.com/r/Android+pixel/new.rss"))
        assertEquals("/r/Android/top?limit=50&raw_json=1&t=week", RedditUrls.listingPath("https://www.reddit.com/r/Android/top/.rss?t=week"))
    }

    @Test fun homeAndUsers() {
        assertEquals("/best", path("https://www.reddit.com/.rss"))
        assertEquals("/best", path("https://www.reddit.com/"))
        assertEquals("/user/spez/submitted", path("https://www.reddit.com/user/spez/.rss"))
    }

    @Test fun notListings() {
        assertNull(path("https://www.reddit.com/r/Android/comments/abc/some_thread/"))
        assertNull(path("https://example.com/r/Android/.rss"))
        assertNull(path("https://notreddit.com/r/x"))
    }
}
