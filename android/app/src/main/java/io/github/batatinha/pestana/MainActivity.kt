package io.github.batatinha.pestana

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import io.github.batatinha.pestana.ui.AppRoot
import io.github.batatinha.pestana.ui.PestanaTheme
import io.github.batatinha.pestana.ui.Themes

class MainActivity : ComponentActivity() {
    private lateinit var state: AppState

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        state = App.state(this)
        state.seedIfEmpty()
        if (savedInstanceState == null) handleShare(intent)
        setContent {
            val t = Themes.resolve(state.settings.theme)
            SideEffect {
                val bar = t.bg.toArgb()
                enableEdgeToEdge(
                    statusBarStyle = if (t.dark) SystemBarStyle.dark(bar) else SystemBarStyle.light(bar, bar),
                    navigationBarStyle = if (t.dark) SystemBarStyle.dark(bar) else SystemBarStyle.light(bar, bar),
                )
            }
            PestanaTheme(t) { AppRoot(state) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** Text shared from another app: a link to a chord page, or the chords themselves. */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        if (text.isEmpty()) return
        val link = Regex("https?://\\S+").find(text)?.value
        if (link != null && Source.isPlaylistLink(link)) {
            state.go(Screen.ImportPlaylist(link))
        } else if (link != null && text.length < link.length + 120) {
            state.go(Screen.Import(link = link))
        } else {
            state.go(Screen.Import(text = text))
        }
    }

    override fun onStop() {
        super.onStop()
        // Keep the metronome only while the app is in front.
        if (!isChangingConfigurations) Metronome.stop()
    }
}

/** Process-wide singleton so the state survives rotation without a ViewModel. */
object App {
    private var s: AppState? = null
    fun state(ctx: android.content.Context): AppState = s ?: AppState(ctx).also { s = it }
}
