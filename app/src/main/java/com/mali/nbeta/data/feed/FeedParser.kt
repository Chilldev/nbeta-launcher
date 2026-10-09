package com.mali.nbeta.data.feed

import android.util.Xml
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.util.Locale

data class ParsedFeed(val title: String?, val siteUrl: String?, val items: List<FeedItem>)

/**
 * One streaming pass over RSS 2.0, RSS 1.0 (RDF), Atom and JSON Feed. XmlPullParser is the platform's native
 * parser, so there is no DOM and no reflection, which keeps a 200-item feed in the low milliseconds.
 */
object FeedParser {
    private const val MEDIA = "http://search.yahoo.com/mrss/"
    private const val CONTENT = "http://purl.org/rss/1.0/modules/content/"
    private const val DC = "http://purl.org/dc/elements/1.1/"
    private const val ITUNES = "http://www.itunes.com/dtds/podcast-1.0.dtd"
    private const val YT = "http://www.youtube.com/xml/schemas/2015"

    fun parse(stream: InputStream, sourceId: String, baseUrl: String, contentType: String?): ParsedFeed {
        val bytes = stream.readBytes()
        val head = bytes.take(64).toByteArray().toString(Charsets.UTF_8).trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        return if (contentType?.contains("json") == true || head.startsWith("{")) {
            parseJson(bytes.toString(Charsets.UTF_8), sourceId, baseUrl)
        } else {
            parseXml(bytes.inputStream(), sourceId, baseUrl)
        }
    }

    private class Draft {
        var title: String? = null
        var link: String? = null
        var guid: String? = null
        var summary: String? = null
        var content: String? = null
        var image: String? = null
        var imageScore = 0
        var author: String? = null
        var date: Long = 0
        var videoId: String? = null

        fun offerImage(url: String?, score: Int) {
            if (url.isNullOrBlank() || score <= imageScore) return
            image = url
            imageScore = score
        }
    }

    private fun parseXml(input: InputStream, sourceId: String, baseUrl: String): ParsedFeed {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        // Real-world feeds use HTML entities (&nbsp; &rsquo; …) outside CDATA; strict parsing would reject the whole feed.
        runCatching { p.setFeature("http://xmlpull.org/v1/doc/features.html#relaxed", true) }
        p.setInput(input, null)
        for ((name, value) in named) if (name !in XML_ENTITIES) runCatching { p.defineEntityReplacementText(name, value) }
        var feedTitle: String? = null
        var siteUrl: String? = null
        val items = ArrayList<FeedItem>()
        var cur: Draft? = null
        var depthInItem = 0
        var inAuthor = false

        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> {
                    val name = p.name
                    val ns = p.namespace.orEmpty()
                    if (cur == null) {
                        when {
                            name == "item" || name == "entry" -> {
                                cur = Draft()
                                depthInItem = p.depth
                            }
                            name == "title" && feedTitle == null && ns.isPlainOrAtom() -> feedTitle = text(p)
                            name == "link" && siteUrl == null -> {
                                val href = p.getAttributeValue(null, "href")
                                val rel = p.getAttributeValue(null, "rel")
                                if (href != null) {
                                    if (rel == null || rel == "alternate") siteUrl = href
                                } else if (ns.isEmpty()) {
                                    siteUrl = text(p).takeIf { it.isNotBlank() }
                                }
                            }
                        }
                    } else {
                        val d = cur
                        when {
                            name == "title" && ns.isPlainOrAtom() -> d.title = text(p)
                            name == "link" && ns.isPlainOrAtom() -> {
                                val href = p.getAttributeValue(null, "href")
                                val rel = p.getAttributeValue(null, "rel")
                                val type = p.getAttributeValue(null, "type")
                                if (href == null) {
                                    d.link = d.link ?: text(p).trim()
                                } else if (rel == null || rel == "alternate") {
                                    d.link = href
                                } else if (rel == "enclosure" && type?.startsWith("image") == true) {
                                    d.offerImage(href, 60)
                                }
                            }
                            name == "guid" || (name == "id" && ns.isPlainOrAtom()) -> {
                                val perma = p.getAttributeValue(null, "isPermaLink")
                                val v = text(p).trim()
                                d.guid = v
                                if (name == "guid" && perma != "false" && d.link == null && v.startsWith("http")) d.link = v
                            }
                            name == "description" || (name == "summary" && ns.isPlainOrAtom()) -> d.summary = text(p)
                            name == "encoded" && ns == CONTENT -> d.content = text(p)
                            name == "content" && ns.isPlainOrAtom() -> d.content = text(p)
                            name == "pubDate" || name == "published" || (name == "date" && ns == DC) -> {
                                parseDate(text(p))?.let { d.date = it }
                            }
                            name == "updated" -> parseDate(text(p))?.let { if (d.date == 0L) d.date = it }
                            name == "creator" && ns == DC -> d.author = text(p)
                            // RSS: <author>email (Name)</author>; Atom: <author><name>..</name></author>
                            name == "author" && ns.isEmpty() -> d.author = d.author ?: text(p)
                            name == "author" && ns.isPlainOrAtom() -> inAuthor = true
                            name == "name" && inAuthor -> d.author = text(p)
                            name == "enclosure" -> {
                                val type = p.getAttributeValue(null, "type").orEmpty()
                                if (type.startsWith("image")) d.offerImage(p.getAttributeValue(null, "url"), 60)
                            }
                            name == "thumbnail" && ns == MEDIA -> d.offerImage(p.getAttributeValue(null, "url"), 70 + widthBonus(p))
                            name == "content" && ns == MEDIA -> {
                                val medium = p.getAttributeValue(null, "medium")
                                val type = p.getAttributeValue(null, "type").orEmpty()
                                if (medium == "image" || type.startsWith("image") || (medium == null && type.isEmpty())) {
                                    d.offerImage(p.getAttributeValue(null, "url"), 80 + widthBonus(p))
                                }
                            }
                            name == "image" && ns == ITUNES -> d.offerImage(p.getAttributeValue(null, "href"), 40)
                            name == "videoId" && ns == YT -> d.videoId = text(p)
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (p.name == "author") inAuthor = false
                    val d = cur
                    if (d != null && p.depth == depthInItem && (p.name == "item" || p.name == "entry")) {
                        build(d, sourceId, baseUrl)?.let(items::add)
                        cur = null
                    }
                }
            }
            ev = p.next()
        }
        return ParsedFeed(feedTitle?.let(::cleanText), siteUrl, items)
    }

    private fun String.isPlainOrAtom() = isEmpty() || this == "http://www.w3.org/2005/Atom" || this == "http://purl.org/rss/1.0/"

    private fun widthBonus(p: XmlPullParser): Int {
        val w = p.getAttributeValue(null, "width")?.toIntOrNull() ?: return 0
        return (w / 100).coerceAtMost(15)
    }

    /** Reads the text of the current element, including any nested markup as raw text (for HTML in summaries). */
    private fun text(p: XmlPullParser): String {
        val sb = StringBuilder()
        val depth = p.depth
        var ev = p.next()
        while (!(ev == XmlPullParser.END_TAG && p.depth == depth)) {
            when (ev) {
                XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF -> sb.append(p.text)
                XmlPullParser.START_TAG -> {
                    sb.append('<').append(p.name)
                    for (i in 0 until p.attributeCount) sb.append(' ').append(p.getAttributeName(i)).append("=\"").append(p.getAttributeValue(i)).append('"')
                    sb.append('>')
                }
                XmlPullParser.END_TAG -> sb.append("</").append(p.name).append('>')
                XmlPullParser.END_DOCUMENT -> break
            }
            ev = p.next()
        }
        return sb.toString()
    }

    private fun build(d: Draft, sourceId: String, baseUrl: String): FeedItem? {
        val link = d.link?.let { resolve(baseUrl, it) } ?: d.guid?.takeIf { it.startsWith("http") } ?: return null
        val title = d.title?.let(::cleanText)?.takeIf { it.isNotBlank() } ?: return null
        val html = d.content ?: d.summary
        if (d.image == null && d.videoId != null) d.offerImage("https://i.ytimg.com/vi/${d.videoId}/hqdefault.jpg", 90)
        if (d.image == null && html != null) firstImage(html)?.let { d.offerImage(it, 30) }
        val summary = (d.summary ?: d.content)?.let(::cleanText)?.let { s ->
            if (s.length > 280) s.substring(0, 277).trimEnd() + "…" else s
        }?.takeIf { it.isNotBlank() && !it.equals(title, ignoreCase = true) }
        return FeedItem(
            id = hash(sourceId + "|" + (d.guid ?: link)),
            sourceId = sourceId,
            title = title,
            link = link,
            summary = summary,
            imageUrl = d.image?.let { resolve(link, it.replace("&amp;", "&")) },
            author = d.author?.let(::cleanText)?.substringAfter("(")?.removeSuffix(")")?.takeIf { it.isNotBlank() && it.length < 60 },
            published = d.date.takeIf { it > 0 } ?: System.currentTimeMillis(),
        )
    }

    private fun parseJson(text: String, sourceId: String, baseUrl: String): ParsedFeed {
        val root = Json.parseToJsonElement(text).jsonObject
        val items = (root["items"] as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            fun s(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val link = s("url") ?: s("external_url") ?: return@mapNotNull null
            val title = s("title") ?: s("content_text")?.take(120) ?: return@mapNotNull null
            val html = s("content_html")
            val author = (o["authors"] as? JsonArray)?.firstOrNull()?.jsonObject?.get("name")?.jsonPrimitive?.content
                ?: (o["author"] as? JsonObject)?.get("name")?.jsonPrimitive?.content
            FeedItem(
                id = hash(sourceId + "|" + (s("id") ?: link)),
                sourceId = sourceId,
                title = cleanText(title),
                link = resolve(baseUrl, link),
                summary = (s("summary") ?: s("content_text") ?: html?.let(::cleanText))?.let { cleanText(it).take(280) },
                imageUrl = (s("image") ?: s("banner_image") ?: html?.let(::firstImage))?.let { resolve(link, it) },
                author = author,
                published = (s("date_published") ?: s("date_modified"))?.let(::parseDate) ?: System.currentTimeMillis(),
            )
        }
        return ParsedFeed((root["title"] as? JsonPrimitive)?.content, (root["home_page_url"] as? JsonPrimitive)?.content, items)
    }

    private val imgRegex = Regex("<img[^>]+src\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
    private val tagRegex = Regex("<[^>]*>")
    private val wsRegex = Regex("\\s+")
    private val skipImage = Regex("(feedburner|pixel|tracker|share|1x1|gravatar|emoji|\\.gif($|\\?))", RegexOption.IGNORE_CASE)

    fun firstImage(html: String): String? = imgRegex.findAll(html).map { it.groupValues[1] }.firstOrNull { !skipImage.containsMatchIn(it) }

    fun cleanText(s: String): String {
        val noTags = tagRegex.replace(s.replace("<br", " <br").replace("</p>", " </p>"), "")
        return wsRegex.replace(decodeEntities(noTags), " ").trim()
    }

    private val XML_ENTITIES = setOf("amp", "lt", "gt", "quot", "apos")
    private val entity = Regex("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);")
    private val named = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "hellip" to "…",
        "mdash" to "—", "ndash" to "–", "rsquo" to "’", "lsquo" to "‘", "rdquo" to "”", "ldquo" to "“", "copy" to "©",
    )

    private fun decodeEntities(s: String): String = entity.replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") || e.startsWith("#X") -> e.substring(2).toIntOrNull(16)?.takeIf(Character::isValidCodePoint)?.let { String(Character.toChars(it)) } ?: m.value
            e.startsWith("#") -> e.substring(1).toIntOrNull()?.takeIf(Character::isValidCodePoint)?.let { String(Character.toChars(it)) } ?: m.value
            else -> named[e] ?: m.value
        }
    }

    fun resolve(base: String, url: String): String = try {
        if (url.startsWith("http")) url else if (url.startsWith("//")) "https:$url" else URI(base).resolve(url).toString()
    } catch (_: Exception) {
        url
    }

    private val rfc822 = listOf(
        DateTimeFormatter.RFC_1123_DATE_TIME,
        fmt("EEE, d MMM yyyy HH:mm:ss zzz"),
        fmt("EEE, d MMM yyyy HH:mm zzz"),
        fmt("d MMM yyyy HH:mm:ss Z"),
        fmt("EEE, d MMM yyyy HH:mm:ss Z"),
        fmt("EEE, dd MMM yyyy HH:mm:ss Z"),
        fmt("EEE, d MMM yy HH:mm:ss Z"),
        // Without the weekday: feeds often state the wrong one, which strict resolution rejects.
        fmt("d MMM yyyy HH:mm:ss zzz"),
        fmt("d MMM yyyy HH:mm zzz"),
        fmt("d MMM yy HH:mm:ss Z"),
    )
    private val weekday = Regex("^[A-Za-z]{3,9},?\\s+")

    private fun fmt(p: String) = DateTimeFormatterBuilder().parseCaseInsensitive().parseLenient().appendPattern(p)
        .parseDefaulting(ChronoField.OFFSET_SECONDS, 0).toFormatter(Locale.US)

    fun parseDate(raw: String): Long? {
        val s = raw.trim().replace(Regex("\\s+"), " ")
        if (s.isEmpty()) return null
        try {
            return OffsetDateTime.parse(s).toInstant().toEpochMilli()
        } catch (_: Exception) {
        }
        try {
            return ZonedDateTime.parse(s).toInstant().toEpochMilli()
        } catch (_: Exception) {
        }
        try {
            return LocalDateTime.parse(s).toInstant(ZoneOffset.UTC).toEpochMilli()
        } catch (_: Exception) {
        }
        for (f in rfc822) {
            try {
                return ZonedDateTime.parse(s, f).toInstant().toEpochMilli()
            } catch (_: Exception) {
            }
        }
        val noWeekday = s.replace(weekday, "")
        // "Thu, 09 Oct 2026 10:00:00 EDT"-style zones the JDK may not know: drop the zone, assume UTC.
        val noZone = noWeekday.replace(Regex(" [A-Z]{2,5}$"), " +0000")
        for (candidate in listOf(noWeekday, noZone).distinct()) for (f in rfc822) {
            try {
                return ZonedDateTime.parse(candidate, f).toInstant().toEpochMilli()
            } catch (_: Exception) {
            }
        }
        return null
    }

    fun hash(s: String): String {
        val d = MessageDigest.getInstance("MD5").digest(s.toByteArray())
        return d.joinToString("") { "%02x".format(it) }.take(16)
    }
}
