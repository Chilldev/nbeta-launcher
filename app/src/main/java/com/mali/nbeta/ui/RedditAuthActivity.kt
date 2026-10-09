package com.mali.nbeta.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.mali.nbeta.NbetaApp
import com.mali.nbeta.R
import com.mali.nbeta.ui.settings.SettingsActivity
import kotlinx.coroutines.launch

/** Receives Reddit's OAuth redirect (nbeta://reddit-auth), finishes sign-in, and returns to feed settings. */
class RedditAuthActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        if (uri == null) {
            finish()
            return
        }
        val graph = (application as NbetaApp).graph
        lifecycleScope.launch {
            val message = try {
                val name = graph.reddit.completeSignIn(uri)
                graph.scope.launch { graph.feed.refresh() }
                getString(R.string.reddit_signed_in_toast, name)
            } catch (e: Exception) {
                e.message ?: getString(R.string.reddit_sign_in_failed)
            }
            Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
            startActivity(
                Intent(this@RedditAuthActivity, SettingsActivity::class.java)
                    .putExtra(SettingsActivity.EXTRA_PAGE, "feed")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
            finish()
        }
    }
}
