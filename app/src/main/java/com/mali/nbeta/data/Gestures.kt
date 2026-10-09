package com.mali.nbeta.data

/** What a home-screen gesture does. Stored as a short id ("lock", "app:<key>"). */
sealed interface GestureAction {
    val id: String

    data object None : GestureAction { override val id = "none" }
    data object Notifications : GestureAction { override val id = "notifications" }
    data object QuickSettings : GestureAction { override val id = "quick_settings" }
    data object Search : GestureAction { override val id = "search" }
    data object LockScreen : GestureAction { override val id = "lock" }
    data object Recents : GestureAction { override val id = "recents" }
    data object Drawer : GestureAction { override val id = "drawer" }
    data object Feed : GestureAction { override val id = "feed" }
    data object EditHome : GestureAction { override val id = "edit_home" }
    data class OpenApp(val key: String) : GestureAction { override val id = "app:$key" }

    companion object {
        val builtIns = listOf(None, Notifications, QuickSettings, Search, LockScreen, Recents, Drawer, Feed, EditHome)

        fun parse(id: String?): GestureAction? = when {
            id == null -> null
            id.startsWith("app:") -> OpenApp(id.removePrefix("app:"))
            else -> builtIns.firstOrNull { it.id == id }
        }
    }
}

enum class Gesture { SwipeDown, DoubleTap, TwoFingerUp, TwoFingerDown, PinchIn }

/** Effective action per gesture; the two original gestures fall back to their older settings. */
fun LauncherSettings.action(g: Gesture): GestureAction = GestureAction.parse(gestures[g.name]) ?: when (g) {
    Gesture.SwipeDown -> when (swipeDown) {
        SwipeDownAction.Notifications -> GestureAction.Notifications
        SwipeDownAction.QuickSettings -> GestureAction.QuickSettings
        SwipeDownAction.Search -> GestureAction.Search
        SwipeDownAction.None -> GestureAction.None
    }
    Gesture.DoubleTap -> if (doubleTap == DoubleTapAction.LockScreen) GestureAction.LockScreen else GestureAction.None
    Gesture.TwoFingerUp -> GestureAction.Search
    Gesture.TwoFingerDown -> GestureAction.QuickSettings
    Gesture.PinchIn -> GestureAction.EditHome
}
