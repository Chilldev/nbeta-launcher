package com.mali.nbeta.data.reader

import org.jsoup.Jsoup
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class LocalPageDebug {
    @Test fun debug() {
        val path = System.getProperty("probe.html")
        assumeTrue(path != null)
        val doc = Jsoup.parse(File(path!!).readText(), "https://x/")
        val unlikely = Regex("comment|share|social|related|sidebar|promo|footer|header|menu|nav|subscribe|newsletter|advert|\\bad-|-ad\\b|sponsor|cookie|popup|modal|banner|breadcrumb|pagination|signup|paywall|outbrain|taboola|recirc|most-?popular", RegexOption.IGNORE_CASE)
        doc.select("script, style, noscript, iframe, form, nav, header, footer, aside, button, svg, input, select, textarea, link, meta").remove()
        println("PROBE article text after tag removal: " + (doc.selectFirst("article")?.text()?.length))
        doc.select("[role=navigation], [role=banner], [role=complementary], [aria-hidden=true], [hidden]").forEach {
            if (it.text().length > 200) println("PROBE attr-removed ${it.tagName()} ${it.attributes().html().take(120)} len=${it.text().length}")
        }
        for (el in doc.body().select("*")) {
            val sig = el.className() + " " + el.id()
            if (sig.isNotBlank() && unlikely.containsMatchIn(sig) && el.text().length > 300) println("PROBE unlikely ${el.tagName()} '${sig.take(80)}' match=${unlikely.find(sig)?.value} len=${el.text().length}")
        }
    }
}
