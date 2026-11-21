package dev.pocketlab

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pocketlab.ui.PocketlabRoot
import dev.pocketlab.ui.theme.PocketlabTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Root shells, container logs, inventory and secret fields: keep them out of the recents
        // thumbnail, screenshots and screen recordings.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            val cfg by PocketlabApp.lab.store.config.collectAsStateWithLifecycle()
            PocketlabTheme(cfg.appearance) { PocketlabRoot() }
        }
    }
}
