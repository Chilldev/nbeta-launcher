package com.mali.nbeta.data.reader

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.jsoup.safety.Safelist

data class Article(
    val title: String,
    val byline: String?,
    val siteName: String?,
    val leadImage: String?,
    val blocks: List<Block>,
    val rtl: Boolean,
    val words: Int,
) {
    val minutes: Int get() = (words / 220).coerceAtLeast(1)
}

sealed interface Block {
    data class Heading(val text: String) : Block
    /** Inline HTML limited to links and emphasis (already sanitised). */
    data class Paragraph(val html: String) : Block
    data class Image(val url: String, val caption: String?) : Block
    data class Quote(val html: String) : Block
    data class Bullets(val items: List<String>, val ordered: Boolean) : Block
    data class Code(val text: String) : Block
}

/**
 * A compact take on Mozilla Readability: drop page chrome, score paragraph containers by text, commas and link
 * density, take the best one, and flatten it into simple blocks the reader can render natively.
 */
object Readability {
    private val unlikely = Regex(
        "comment|share|social|related|sidebar|promo|footer|header|menu|nav|subscribe|newsletter|advert|\\bad-|-ad\\b|" +
            "sponsor|cookie|popup|modal|banner|breadcrumb|pagination|signup|paywall|outbrain|taboola|recirc|most-?popular",
        RegexOption.IGNORE_CASE,
    )
    private val likely = Regex("article|body|content|main|post|entry|story|text|blog", RegexOption.IGNORE_CASE)
    private val inline = Safelist().addTags("a", "b", "strong", "i", "em", "br", "code", "sub", "sup").addAttributes("a", "href").addProtocols("a", "href", "http", "https")

    fun extract(html: String, url: String): Article? {
        val doc = Jsoup.parse(html, url)
        val title = meta(doc, "og:title") ?: doc.title().substringBefore(" | ").substringBefore(" - ").trim()
        val site = meta(doc, "og:site_name")
        val byline = meta(doc, "author") ?: doc.selectFirst("[rel=author], .author, .byline, [itemprop=author]")?.text()?.takeIf { it.length in 3..80 }
        val lead = meta(doc, "og:image")?.let { abs(doc, it) }
        val lang = doc.selectFirst("html")?.attr("lang").orEmpty().lowercase()
        val rtl = doc.selectFirst("html")?.attr("dir") == "rtl" || lang.startsWith("ar") || lang.startsWith("he") || lang.startsWith("fa") || lang.startsWith("ur")

        doc.select("script, style, noscript, iframe, form, nav, header, footer, aside, button, svg, input, select, textarea, link, meta").remove()
        doc.select("[role=navigation], [role=banner], [role=complementary], [aria-hidden=true], [hidden]").remove()
        for (el in doc.body()?.select("*").orEmpty().toList()) {
            if (el.tagName() == "body" || el.tagName() == "article" || el.tagName() == "main") continue
            val sig = el.className() + " " + el.id()
            if (sig.isNotBlank() && unlikely.containsMatchIn(sig) && !likely.containsMatchIn(sig)) el.remove()
        }

        val root = pickRoot(doc) ?: return null
        val blocks = ArrayList<Block>()
        flatten(root, blocks, doc)
        if (lead != null) blocks.removeAll { it is Block.Image && it.url == lead }
        val words = blocks.sumOf { b ->
            when (b) {
                is Block.Paragraph -> Jsoup.parse(b.html).text().split(' ').size
                is Block.Quote -> Jsoup.parse(b.html).text().split(' ').size
                is Block.Bullets -> b.items.sumOf { it.split(' ').size }
                is Block.Heading -> b.text.split(' ').size
                else -> 0
            }
        }
        if (words < 80) return null
        return Article(title.ifBlank { site ?: url }, byline, site, lead, blocks, rtl, words)
    }

    private fun meta(doc: Document, name: String): String? =
        (doc.selectFirst("meta[property=$name]") ?: doc.selectFirst("meta[name=$name]"))?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }

    private fun abs(doc: Document, src: String): String = runCatching { java.net.URI(doc.location()).resolve(src).toString() }.getOrDefault(src)

    private fun textLen(e: Element) = e.text().length

    private fun linkDensity(e: Element): Double {
        val total = textLen(e).coerceAtLeast(1)
        return e.select("a").sumOf { textLen(it) }.toDouble() / total
    }

    private fun pickRoot(doc: Document): Element? {
        val body = doc.body() ?: return null
        // A clear <article> (or <main>) with real text wins outright.
        doc.select("article, main, [itemprop=articleBody]").maxByOrNull { textLen(it) }?.let { if (textLen(it) > 600) return it }

        val scores = HashMap<Element, Double>()
        for (p in body.select("p, pre, td, blockquote")) {
            val len = textLen(p)
            if (len < 25) continue
            val score = 1.0 + p.text().count { it == ',' || it == '،' } + minOf(3, len / 100)
            val parent = p.parent() ?: continue
            scores[parent] = (scores[parent] ?: weight(parent)) + score
            parent.parent()?.let { gp -> scores[gp] = (scores[gp] ?: weight(gp)) + score / 2 }
        }
        return scores.maxByOrNull { (e, s) -> s * (1 - linkDensity(e)) }?.key
    }

    private fun weight(e: Element): Double {
        val sig = e.className() + " " + e.id()
        return (if (likely.containsMatchIn(sig)) 25.0 else 0.0) - (if (unlikely.containsMatchIn(sig)) 25.0 else 0.0)
    }

    private fun imageUrl(img: Element, doc: Document): String? {
        val candidates = listOf("data-src", "data-original", "data-lazy-src", "src")
        val direct = candidates.firstNotNullOfOrNull { img.attr(it).takeIf { v -> v.isNotBlank() && !v.startsWith("data:") } }
        val srcset = (img.attr("srcset").ifBlank { img.attr("data-srcset") }).split(',').map { it.trim().substringBefore(' ') }.lastOrNull { it.isNotBlank() }
        val src = srcset ?: direct ?: return null
        val w = img.attr("width").toIntOrNull()
        if (w != null && w < 120) return null // icons, avatars, tracking pixels
        return abs(doc, src)
    }

    private fun flatten(el: Element, out: MutableList<Block>, doc: Document) {
        for (child in el.children()) {
            when (child.tagName()) {
                "h1", "h2", "h3", "h4", "h5", "h6" -> child.text().trim().takeIf { it.isNotEmpty() }?.let { out += Block.Heading(it) }
                "p" -> {
                    child.select("img").forEach { img -> imageUrl(img, doc)?.let { out += Block.Image(it, img.attr("alt").ifBlank { null }) } }
                    val text = child.text().trim()
                    if (text.isNotEmpty() && !(text.length < 80 && linkDensity(child) > 0.6)) out += Block.Paragraph(clean(child))
                }
                "figure" -> {
                    val img = child.selectFirst("img")
                    val url = img?.let { imageUrl(it, doc) }
                    if (url != null) out += Block.Image(url, child.selectFirst("figcaption")?.text()?.takeIf { it.isNotBlank() })
                }
                "img" -> imageUrl(child, doc)?.let { out += Block.Image(it, child.attr("alt").ifBlank { null }) }
                "blockquote" -> child.text().trim().takeIf { it.isNotEmpty() }?.let { out += Block.Quote(clean(child)) }
                "ul", "ol" -> {
                    val items = child.select("> li").map { it.text().trim() }.filter { it.isNotEmpty() }
                    if (items.isNotEmpty() && linkDensity(child) < 0.5) out += Block.Bullets(items, child.tagName() == "ol")
                }
                "pre" -> out += Block.Code(child.wholeText().trimEnd())
                "table" -> Unit
                else -> {
                    // Containers: recurse; bare text inside divs becomes a paragraph.
                    val ownText = child.textNodes().joinToString(" ") { (it as TextNode).text().trim() }.trim()
                    if (ownText.length > 60 && child.children().isEmpty()) out += Block.Paragraph(clean(child))
                    else flatten(child, out, doc)
                }
            }
        }
    }

    private fun clean(e: Element): String = Jsoup.clean(e.html(), e.baseUri(), inline).trim()
}
