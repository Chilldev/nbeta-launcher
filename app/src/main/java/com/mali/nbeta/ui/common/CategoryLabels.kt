package com.mali.nbeta.ui.common

import androidx.annotation.StringRes
import com.mali.nbeta.R
import com.mali.nbeta.data.apps.AppCategory

@get:StringRes
val AppCategory.label: Int
    get() = when (this) {
        AppCategory.Social -> R.string.category_social
        AppCategory.Communication -> R.string.category_communication
        AppCategory.Media -> R.string.category_media
        AppCategory.Photos -> R.string.category_photos
        AppCategory.Games -> R.string.category_games
        AppCategory.News -> R.string.category_news
        AppCategory.Travel -> R.string.category_travel
        AppCategory.Productivity -> R.string.category_productivity
        AppCategory.Finance -> R.string.category_finance
        AppCategory.Shopping -> R.string.category_shopping
        AppCategory.Health -> R.string.category_health
        AppCategory.Tools -> R.string.category_tools
    }
