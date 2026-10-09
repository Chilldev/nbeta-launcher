package com.mali.nbeta.data.apps

import android.content.pm.ApplicationInfo

enum class AppCategory { Social, Communication, Media, Photos, Games, News, Travel, Productivity, Finance, Shopping, Health, Tools }

/**
 * Sorts apps into drawer categories. The system category (declared by the app) wins where it's meaningful; otherwise
 * well-known packages and name keywords decide, and everything left over is a tool.
 */
object Categories {
    private val byPackage = mapOf(
        AppCategory.Social to listOf("com.facebook.katana", "com.instagram.android", "com.twitter.android", "com.zhiliaoapp.musically", "com.ss.android.ugc", "com.snapchat.android", "com.reddit.frontpage", "com.linkedin.android", "com.pinterest", "org.joinmastodon", "com.discord", "com.bereal", "com.threads"),
        AppCategory.Communication to listOf("com.whatsapp", "org.telegram", "com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms", "com.google.android.dialer", "com.samsung.android.dialer", "com.android.dialer", "com.google.android.gm", "com.microsoft.office.outlook", "com.Slack", "com.microsoft.teams", "us.zoom.videomeetings", "com.viber.voip", "org.thoughtcrime.securesms", "com.skype", "com.google.android.apps.tachyon", "com.android.contacts", "com.google.android.contacts", "com.samsung.android.app.contacts"),
        AppCategory.Media to listOf("com.google.android.youtube", "com.google.android.apps.youtube.music", "com.spotify.music", "com.netflix.mediaclient", "com.amazon.avod", "com.disney", "com.anghami", "com.shahid", "com.soundcloud", "com.apple.android.music", "tv.twitch", "com.audible", "com.google.android.apps.podcasts"),
        AppCategory.Photos to listOf("com.google.android.apps.photos", "com.sec.android.gallery3d", "com.google.android.GoogleCamera", "com.sec.android.app.camera", "com.android.camera", "com.adobe.lrmobile", "com.vsco", "com.picsart"),
        AppCategory.Travel to listOf("com.google.android.apps.maps", "com.waze", "com.ubercab", "com.careem", "sinet.startup.inDriver", "com.didiglobal", "com.booking", "com.airbnb.android", "com.tripadvisor", "com.google.android.apps.travel"),
        AppCategory.Productivity to listOf("com.google.android.calendar", "com.samsung.android.calendar", "com.google.android.keep", "com.google.android.apps.docs", "com.microsoft.office", "com.notion", "com.todoist", "com.anthropic.claude", "com.openai.chatgpt", "com.google.android.apps.bard", "com.clickup", "com.trello", "com.google.android.apps.tasks", "com.github.android"),
        AppCategory.Shopping to listOf("com.amazon.mShop", "com.alibaba.aliexpresshd", "com.noon", "com.einnovation.temu", "com.zzkko", "com.ebay", "com.talabat", "com.deliveroo"),
        AppCategory.Health to listOf("com.google.android.apps.fitness", "com.huawei.health", "com.sec.android.app.shealth", "com.samsung.android.wellbeing", "com.strava", "com.myfitnesspal"),
    )

    private val keywords = listOf(
        AppCategory.Finance to listOf("bank", "pay", "wallet", "finance", "invest", "crypto", "binance", "bybit", "trade", "money", "cash", "card", "instapay", "vodafonecash", "fawry", "cib", "mashreq", "nbe", "qnb", "hsbc", "revolut", "wise"),
        AppCategory.Shopping to listOf("shop", "store", "mall", "market", "buy", "deal", "food", "delivery"),
        AppCategory.Games to listOf("game", "games", "play.games", "puzzle", "chess", "candy", "clash", "pubg", "roblox", "minecraft"),
        AppCategory.News to listOf("news", "times", "guardian", "bbc", "cnn", "reuters", "aljazeera", "feedly"),
        AppCategory.Travel to listOf("travel", "maps", "taxi", "ride", "flight", "airline", "hotel", "uber", "metro"),
        AppCategory.Health to listOf("health", "fit", "workout", "sleep", "medic", "doctor", "pharma", "clinic"),
        AppCategory.Photos to listOf("camera", "photo", "gallery", "picture"),
        AppCategory.Media to listOf("music", "video", "tv", "movie", "radio", "podcast", "player", "stream"),
        AppCategory.Communication to listOf("mail", "messag", "chat", "call", "dialer", "contacts", "sms"),
        AppCategory.Productivity to listOf("note", "docs", "office", "calendar", "task", "todo", "drive", "scan", "pdf", "translate"),
    )

    fun categorize(packageName: String, label: String, systemCategory: Int): AppCategory {
        byPackage.entries.firstOrNull { (_, pkgs) -> pkgs.any { packageName == it || packageName.startsWith("$it.") || packageName.startsWith(it) } }
            ?.let { return it.key }
        when (systemCategory) {
            ApplicationInfo.CATEGORY_GAME -> return AppCategory.Games
            ApplicationInfo.CATEGORY_SOCIAL -> return AppCategory.Social
            ApplicationInfo.CATEGORY_NEWS -> return AppCategory.News
            ApplicationInfo.CATEGORY_MAPS -> return AppCategory.Travel
            ApplicationInfo.CATEGORY_IMAGE -> return AppCategory.Photos
            ApplicationInfo.CATEGORY_AUDIO, ApplicationInfo.CATEGORY_VIDEO -> return AppCategory.Media
        }
        val hay = "$packageName ${label.lowercase()}"
        keywords.firstOrNull { (_, words) -> words.any { w -> Regex("(^|[^a-z])${Regex.escape(w)}").containsMatchIn(hay) } }?.let { return it.first }
        if (systemCategory == ApplicationInfo.CATEGORY_PRODUCTIVITY) return AppCategory.Productivity
        return AppCategory.Tools
    }
}
