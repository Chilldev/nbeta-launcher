package com.mali.nbeta.data.apps

import org.junit.Assert.assertEquals
import org.junit.Test

class CategoriesTest {
    private fun cat(pkg: String, label: String, sys: Int = -1) = Categories.categorize(pkg, label, sys)

    @Test fun knownPackages() {
        assertEquals(AppCategory.Communication, cat("com.whatsapp", "WhatsApp"))
        assertEquals(AppCategory.Social, cat("com.instagram.android", "Instagram"))
        assertEquals(AppCategory.Media, cat("com.google.android.youtube", "YouTube"))
        assertEquals(AppCategory.Travel, cat("com.google.android.apps.maps", "Maps"))
    }

    @Test fun systemCategoryAndKeywords() {
        assertEquals(AppCategory.Games, cat("com.some.studio.thing", "Thing", android.content.pm.ApplicationInfo.CATEGORY_GAME))
        assertEquals(AppCategory.Finance, cat("com.cib.mobile", "CIB Egypt"))
        assertEquals(AppCategory.Finance, cat("com.example.wallet", "My Wallet"))
        assertEquals(AppCategory.Tools, cat("com.example.flashlight", "Flashlight"))
    }

    @Test fun keywordsMatchWordStarts() {
        // "pay" must not match inside "display"
        assertEquals(AppCategory.Tools, cat("com.example.display", "Display Tuner"))
    }
}
