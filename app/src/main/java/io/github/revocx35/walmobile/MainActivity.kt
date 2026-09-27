package io.github.revocx35.walmobile

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.lifecycleScope
import io.github.revocx35.walmobile.engine.Notifier
import io.github.revocx35.walmobile.ui.AppRoot
import io.github.revocx35.walmobile.ui.theme.WaTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** Page to open from a notification tap ("wa", "deleted"). */
    private val openTarget = MutableStateFlow<String?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(Notifier.EXTRA_OPEN)?.let { openTarget.value = it }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) intent?.getStringExtra(Notifier.EXTRA_OPEN)?.let { openTarget.value = it }
        val graph = (application as WaLoggerApp).graph
        // Private messages: no screenshots / recordings / app-switcher previews unless the owner turns it off.
        applySecure(graph.prefs.secureScreen.value)
        lifecycleScope.launch { graph.prefs.secureScreen.collect { applySecure(it) } }
        super.onCreate(savedInstanceState)
        setContent {
            val dark = isSystemInDarkTheme()
            LaunchedEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT) else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
                    navigationBarStyle = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT) else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
                )
            }
            WaTheme(dark) { AppRoot(graph, openTarget) }
        }
    }

    private fun applySecure(on: Boolean) {
        if (on) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}
