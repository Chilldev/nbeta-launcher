package com.mali.nbeta.data.reddit

import com.mali.nbeta.system.DiagLog
import android.content.Context
import android.net.Uri
import android.util.Base64
import com.mali.nbeta.BuildConfig
import com.mali.nbeta.R
import com.mali.nbeta.data.JsonStore
import com.mali.nbeta.data.feed.FeedItem
import com.mali.nbeta.data.feed.FeedParser
import com.mali.nbeta.data.feed.ParsedFeed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.security.SecureRandom

@Serializable
data class RedditSession(
    val clientId: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val username: String? = null,
)

class RedditAuthException(message: String) : Exception(message)

/**
 * Reddit sign-in with OAuth 2 ("installed app": a client id, no secret) and feed listings through
 * oauth.reddit.com. Unauthenticated .rss/.json traffic is throttled or blocked by Reddit; an authenticated client
 * gets the documented 100 requests/minute.
 *
 * Tokens live in noBackupFilesDir so they never leave the device in a backup or a settings export.
 */
class RedditClient(
    private val context: Context,
    scope: CoroutineScope,
    httpProvider: () -> OkHttpClient,
) {
    private val http by lazy(httpProvider)
    private val store = JsonStore(File(context.noBackupFilesDir, "reddit_session.json"), RedditSession.serializer().nullable, { null }, scope, debounceMs = 0)
    val session: StateFlow<RedditSession?> = store.flow
    private val prefs get() = context.getSharedPreferences("reddit_auth", Context.MODE_PRIVATE)

    val signedIn get() = session.value != null

    /** Reddit's required format: platform:app id:version (by /u/username). */
    val userAgent: String
        get() = "android:${BuildConfig.APPLICATION_ID}:v${BuildConfig.VERSION_NAME}" + (session.value?.username?.let { " (by /u/$it)" } ?: "")

    // --- Sign-in ---

    fun authorizeUri(clientId: String): Uri {
        val state = ByteArray(18).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        prefs.edit().putString("state", state).putString("client_id", clientId.trim()).apply()
        return Uri.parse("https://www.reddit.com/api/v1/authorize.compact").buildUpon()
            .appendQueryParameter("client_id", clientId.trim())
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("state", state)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("duration", "permanent")
            .appendQueryParameter("scope", SCOPES)
            .build()
    }

    /** Handles nbeta://reddit-auth?state=…&code=… (or ?error=…). Returns the signed-in username. */
    suspend fun completeSignIn(redirect: Uri): String = withContext(Dispatchers.IO) {
        redirect.getQueryParameter("error")?.let {
            throw RedditAuthException(if (it == "access_denied") context.getString(R.string.reddit_error_declined) else context.getString(R.string.reddit_error_returned, it))
        }
        val expected = prefs.getString("state", null)
        val clientId = prefs.getString("client_id", null) ?: throw RedditAuthException(context.getString(R.string.reddit_error_expired_retry))
        // The state ties this redirect to a sign-in we started; anything else could be a forged link.
        if (expected == null || redirect.getQueryParameter("state") != expected) throw RedditAuthException(context.getString(R.string.reddit_error_state))
        val code = redirect.getQueryParameter("code") ?: throw RedditAuthException(context.getString(R.string.reddit_error_no_code))
        prefs.edit().remove("state").apply()

        val tokens = tokenRequest(
            clientId,
            FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("redirect_uri", REDIRECT_URI)
                .build(),
        )
        val refresh = tokens.string("refresh_token") ?: throw RedditAuthException(context.getString(R.string.reddit_error_no_offline))
        var s = RedditSession(clientId, tokens.string("access_token")!!, refresh, expiry(tokens))
        store.replace(s)
        val name = runCatching { get("https://oauth.reddit.com/api/v1/me").use { r -> json(r).string("name") } }.getOrNull()
        s = s.copy(username = name)
        store.replace(s)
        name ?: context.getString(R.string.reddit_your_account)
    }

    suspend fun signOut() = withContext(Dispatchers.IO) {
        val s = session.value ?: return@withContext
        store.replace(null)
        runCatching {
            http.newCall(
                Request.Builder().url("https://www.reddit.com/api/v1/revoke_token")
                    .header("Authorization", basic(s.clientId))
                    .header("User-Agent", userAgent)
                    .post(FormBody.Builder().add("token", s.refreshToken).add("token_type_hint", "refresh_token").build())
                    .build(),
            ).execute().close()
        }
    }

    @Synchronized
    private fun accessToken(forceRefresh: Boolean = false): String {
        val s = session.value ?: throw RedditAuthException(context.getString(R.string.reddit_error_not_signed_in))
        if (!forceRefresh && System.currentTimeMillis() < s.expiresAt - 60_000) return s.accessToken
        val tokens = tokenRequest(
            s.clientId,
            FormBody.Builder().add("grant_type", "refresh_token").add("refresh_token", s.refreshToken).build(),
        )
        val next = s.copy(
            accessToken = tokens.string("access_token")!!,
            refreshToken = tokens.string("refresh_token") ?: s.refreshToken,
            expiresAt = expiry(tokens),
        )
        store.replace(next)
        return next.accessToken
    }

    private fun tokenRequest(clientId: String, body: FormBody): JsonObject {
        val req = Request.Builder().url("https://www.reddit.com/api/v1/access_token")
            .header("Authorization", basic(clientId))
            .header("User-Agent", userAgent)
            .post(body)
            .build()
        http.newCall(req).execute().use { r ->
            val o = runCatching { json(r) }.getOrNull()
            val err = o?.string("error")
            if (!r.isSuccessful || err != null || o?.string("access_token") == null) {
                if (r.code == 400 || err == "invalid_grant") store.replace(null) // refresh token revoked: sign out
                throw RedditAuthException(
                    when {
                        r.code == 401 -> context.getString(R.string.reddit_error_client_rejected)
                        err == "invalid_grant" -> context.getString(R.string.reddit_error_grant_expired)
                        else -> context.getString(R.string.reddit_error_failed, err ?: "HTTP ${r.code}")
                    },
                )
            }
            return o
        }
    }

    private fun basic(clientId: String) = "Basic " + Base64.encodeToString("$clientId:".toByteArray(), Base64.NO_WRAP)

    private fun expiry(tokens: JsonObject) =
        System.currentTimeMillis() + ((tokens["expires_in"] as? JsonPrimitive)?.doubleOrNull?.toLong() ?: 3600L) * 1000

    private fun get(url: String, retried: Boolean = false): Response {
        val r = http.newCall(
            Request.Builder().url(url)
                .header("Authorization", "bearer ${accessToken(forceRefresh = retried)}")
                .header("User-Agent", userAgent)
                // Listings change constantly and are cheap with OAuth; skip OkHttp's cache.
                .cacheControl(okhttp3.CacheControl.FORCE_NETWORK)
                .build(),
        ).execute()
        if (r.code == 401 && !retried) {
            r.close()
            return get(url, retried = true)
        }
        r.header("X-Ratelimit-Remaining")?.toDoubleOrNull()?.let { if (it < 10) DiagLog.w(TAG, "Reddit rate limit nearly used: $it left") }
        return r
    }

    // --- Feeds ---

    /**
     * Maps a Reddit RSS/web URL to an OAuth listing path: /r/X(+Y)/sort, the home page (/best), or a user's posts.
     * Returns null for URLs that aren't listings (e.g. a single thread).
     */
    fun listingPath(url: String): String? = RedditUrls.listingPath(url)

    fun handles(url: String) = signedIn && listingPath(url) != null

    fun fetchListing(url: String, sourceId: String): ParsedFeed {
        val path = listingPath(url) ?: throw IllegalArgumentException("Not a Reddit listing: $url")
        get("https://oauth.reddit.com$path").use { r ->
            if (r.code == 403) throw IllegalStateException(context.getString(R.string.reddit_error_private))
            if (r.code == 404) throw IllegalStateException(context.getString(R.string.reddit_error_not_found))
            if (!r.isSuccessful) throw IllegalStateException(context.getString(R.string.reddit_error_http, r.code))
            val items = RedditListing.parse(r.body.string(), sourceId)
            return ParsedFeed(null, null, items)
        }
    }

    /** Title for a subreddit added while signed in (RSS discovery would be rate-limited). */
    fun describe(url: String): String? {
        val path = listingPath(url) ?: return null
        return when {
            path.startsWith("/best") -> context.getString(R.string.reddit_home_title)
            path.startsWith("/r/") -> "r/" + path.removePrefix("/r/").substringBefore('/')
            path.startsWith("/user/") -> "u/" + path.removePrefix("/user/").substringBefore('/')
            else -> null
        }
    }

    private fun json(r: Response): JsonObject = Json.parseToJsonElement(r.body.string()).jsonObject
    private fun JsonObject.string(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.bool(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull == true

    companion object {
        private const val TAG = "Reddit"
        const val REDIRECT_URI = "nbeta://reddit-auth"
        private const val SCOPES = "identity read mysubreddits"
        const val APPS_URL = "https://www.reddit.com/prefs/apps"
        const val ACCESS_REQUEST_URL = "https://support.reddithelp.com/hc/en-us/requests/new?ticket_form_id=14868593862164"
        const val HOME_FEED_URL = "https://www.reddit.com/.rss"
    }
}

/** Pure URL mapping, kept free of Android types so it is unit-testable. */
object RedditUrls {
    private val sorts = setOf("hot", "new", "top", "rising", "best", "controversial")

    fun listingPath(url: String): String? {
        val uri = runCatching { java.net.URI(url.trim()) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (host != "reddit.com" && !host.endsWith(".reddit.com")) return null
        val seg = uri.path.orEmpty().split('/')
            .map { it.removeSuffix(".rss").removeSuffix(".json") }
            .filter { it.isNotEmpty() }
        val t = uri.rawQuery?.split('&')?.firstOrNull { it.startsWith("t=") }?.let { "&$it" }.orEmpty()
        val path = when {
            seg.isEmpty() -> "/best"
            seg.size == 1 && seg[0] in sorts -> "/${seg[0]}"
            seg[0] == "r" && seg.size >= 2 && (seg.size == 2 || seg[2] in sorts) -> "/r/${seg[1]}/${seg.getOrNull(2) ?: "hot"}"
            (seg[0] == "user" || seg[0] == "u") && seg.size >= 2 -> "/user/${seg[1]}/submitted"
            else -> return null
        }
        return "$path?limit=50&raw_json=1$t"
    }
}

/** Reddit listing JSON -> feed items. Pure, so it is unit-tested against Reddit's documented shape. */
object RedditListing {
    fun parse(text: String, sourceId: String): List<FeedItem> {
        val root = Json.parseToJsonElement(text).jsonObject
        val children = (root["data"] as? JsonObject)?.get("children") as? JsonArray ?: return emptyList()
        return children.mapNotNull { child ->
            val d = (child as? JsonObject)?.get("data") as? JsonObject ?: return@mapNotNull null
            if (d.bool("stickied") || d.bool("over_18")) return@mapNotNull null
            val id = d.string("name") ?: return@mapNotNull null // t3_xxxx, the same id the RSS feed uses
            val title = d.string("title") ?: return@mapNotNull null
            val permalink = d.string("permalink")?.let { "https://www.reddit.com$it" } ?: return@mapNotNull null
            val sub = d.string("subreddit_name_prefixed")
            FeedItem(
                id = FeedParser.hash("$sourceId|$id"),
                sourceId = sourceId,
                title = FeedParser.cleanText(title),
                link = permalink,
                summary = d.string("selftext")?.takeIf { it.isNotBlank() }?.let { FeedParser.cleanText(it).take(280) }
                    ?: listOfNotNull(sub, d.string("domain")?.takeIf { !it.startsWith("self.") }).joinToString(" · ").ifEmpty { null },
                imageUrl = previewImage(d),
                author = d.string("author")?.let { "u/$it" },
                published = ((d["created_utc"] as? JsonPrimitive)?.doubleOrNull?.toLong() ?: 0L) * 1000,
                imageTried = true, // Reddit gives a preview or nothing; don't scrape the comments page.
            )
        }
    }

    private fun previewImage(d: JsonObject): String? {
        val images = (d["preview"] as? JsonObject)?.get("images") as? JsonArray
        val src = (images?.firstOrNull() as? JsonObject)?.let { img ->
            // A mid-size resolution is plenty for a card and much lighter than the source image.
            val res = (img["resolutions"] as? JsonArray)?.mapNotNull { it as? JsonObject }
            res?.lastOrNull { (it["width"] as? JsonPrimitive)?.doubleOrNull?.let { w -> w <= 960 } == true }?.string("url")
                ?: (img["source"] as? JsonObject)?.string("url")
        }
        return src ?: d.string("thumbnail")?.takeIf { it.startsWith("http") }
    }

    private fun JsonObject.string(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.bool(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull == true
}
