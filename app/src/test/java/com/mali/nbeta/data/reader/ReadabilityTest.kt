package com.mali.nbeta.data.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadabilityTest {
    private val para = "The quick brown fox jumps over the lazy dog, and then it keeps running across the field, past the barn, towards the river where nobody can see it anymore."

    private fun page(lang: String = "en", body: String) = """
        <html lang="$lang"><head><title>Big News - Example Times</title>
        <meta property="og:title" content="Big News Happened">
        <meta property="og:site_name" content="Example Times">
        <meta property="og:image" content="/img/lead.jpg"></head>
        <body>
          <nav><a href="/">Home</a><a href="/world">World</a></nav>
          <div class="share-bar"><a href="#">Share on X</a></div>
          $body
          <div class="related-stories"><p>${"Related story text that should never appear. ".repeat(4)}</p></div>
          <footer><p>Copyright</p></footer>
        </body></html>
    """.trimIndent()

    @Test fun extractsArticleWithoutChrome() {
        val html = page(body = """
            <div class="ad-slot">Buy now</div>
            <div class="story-body">
              <h2>What happened</h2>
              ${"<p>$para</p>".repeat(6)}
              <figure><img src="/img/inline.jpg" width="800"><figcaption>A caption</figcaption></figure>
              <blockquote><p>"It was remarkable," said a witness, who asked not to be named.</p></blockquote>
              <ul><li>First point</li><li>Second point</li></ul>
              <p>See <a href="https://example.com/more">more coverage</a>, for details.</p>
            </div>
            <div class="comments">${"<p>Great article, thanks for writing it all up!</p>".repeat(3)}</div>
        """)
        val a = Readability.extract(html, "https://example.com/news/1")
        assertNotNull(a)
        a!!
        assertEquals("Big News Happened", a.title)
        assertEquals("Example Times", a.siteName)
        assertEquals("https://example.com/img/lead.jpg", a.leadImage)
        assertTrue(a.blocks.first() is Block.Heading)
        assertEquals(7, a.blocks.count { it is Block.Paragraph })
        assertTrue(a.blocks.any { it is Block.Image && it.url == "https://example.com/img/inline.jpg" && it.caption == "A caption" })
        assertTrue(a.blocks.any { it is Block.Quote })
        assertTrue(a.blocks.any { it is Block.Bullets && it.items == listOf("First point", "Second point") })
        val all = a.blocks.joinToString(" ") { it.toString() }
        assertFalse(all.contains("Related story"))
        assertFalse(all.contains("Great article"))
        assertFalse(all.contains("Buy now"))
        assertTrue(all.contains("href=\"https://example.com/more\""))
        assertFalse(a.rtl)
    }

    @Test fun detectsRtl() {
        val arabic = "هذا نص عربي طويل بما يكفي لكي يعتبر فقرة حقيقية في المقال، ويحتوي على فواصل، وكلمات كثيرة جدا ومتنوعة."
        val a = Readability.extract(page("ar", "<article>${"<p>$arabic</p>".repeat(8)}</article>"), "https://example.com/ar")
        assertTrue(a!!.rtl)
    }

    @Test fun keepsArticleInsideMisleadinglyNamedWrapper() {
        // BBC: the whole story sits in a div whose class contains "Sidebar".
        val html = page(body = """
            <div class="ssrcss-js09yk-ContainerWithSidebarWrapper">
              <article class="ArticleWrapper">${"<p>$para</p>".repeat(6)}</article>
              <div class="ssrcss-sidebar">${"<a href='/x'>Most read story</a>".repeat(5)}</div>
            </div>
        """)
        val a = Readability.extract(html, "https://example.com/bbc")
        assertNotNull(a)
        assertEquals(6, a!!.blocks.count { it is Block.Paragraph })
    }

    @Test fun rejectsPagesWithoutArticleText() {
        assertNull(Readability.extract(page(body = "<div><p>Sign in to continue.</p></div>"), "https://example.com/x"))
    }
}
