package com.mali.nbeta.ui

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.mali.nbeta.NbetaApp
import com.mali.nbeta.ui.feed.CustomTabs

class LauncherActivity : ComponentActivity() {
    private val graph get() = (application as NbetaApp).graph
    lateinit var controller: LauncherController
        private set
    val customTabs by lazy { CustomTabs(this) }

    private val bindWidget = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        controller.widgets.onBindResult(res.resultCode == RESULT_OK)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val transparent = android.graphics.Color.TRANSPARENT
        enableEdgeToEdge(SystemBarStyle.dark(transparent), SystemBarStyle.dark(transparent))
        super.onCreate(savedInstanceState)
        window.isNavigationBarContrastEnforced = false
        controller = LauncherController(this, graph, lifecycleScope) { intent -> bindWidget.launch(intent) }
        graph.widgets.longPressListener = { id -> controller.widgetMenu = id }
        setContent { LauncherRoot(controller) }
    }

    override fun onStart() {
        super.onStart()
        graph.widgets.startListening()
    }

    override fun onResume() {
        super.onResume()
        graph.glance.refresh()
    }

    override fun onStop() {
        super.onStop()
        graph.widgets.stopListening()
        controller.onStopped()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)) {
            // Already in front: animate back to home. Coming from another app: reset instantly.
            controller.onHomePressed(animate = hasWindowFocus())
        }
    }

    @Deprecated("AppWidgetHost.startAppWidgetConfigureActivityForResult only reports through onActivityResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == WidgetFlow.REQUEST_CONFIGURE) {
            val id = data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1) ?: -1
            controller.widgets.onConfigureResult(resultCode == RESULT_OK, id)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        graph.widgets.longPressListener = null
        customTabs.unbind()
    }
}
