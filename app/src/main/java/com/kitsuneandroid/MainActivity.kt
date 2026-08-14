package com.kitsuneandroid

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kitsuneandroid.ui.theme.KitsuneAndroidTheme

class MainActivity : ComponentActivity() {
    var videoPlaying = false
    var pauseVideoPlayback: (() -> Unit)? = null
    var malAuthUri by mutableStateOf<Uri?>(null)
        private set

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.withInterfaceLanguage())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val startupStartedAt = AppPerformance.start()
        super.onCreate(savedInstanceState)
        receiveMalAuthorization(intent)
        AppPerformance.initialize(this)
        EpisodeApi.initialize(this)
        enableEdgeToEdge()
        setContent {
            KitsuneAndroidTheme(darkTheme = true, dynamicColor = false) {
                KitsuneApp()
            }
        }
        window.decorView.post {
            AppPerformance.record("Inicialização até primeira tela", startupStartedAt)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receiveMalAuthorization(intent)
    }

    fun consumeMalAuthorization() {
        malAuthUri = null
    }

    private fun receiveMalAuthorization(intent: Intent) {
        malAuthUri = intent.data?.takeIf { uri ->
            uri.scheme == "kitsuneandroid" && uri.host == "mal-auth"
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (videoPlaying && Build.VERSION.SDK_INT in Build.VERSION_CODES.O until Build.VERSION_CODES.S) {
            enterPictureInPictureMode(PictureInPictureParams.Builder().build())
        }
    }

    override fun onStop() {
        super.onStop()
        if (shouldPausePlaybackWhenStopped(videoPlaying, isInPictureInPictureMode)) {
            pauseVideoPlayback?.invoke()
        }
    }
}

internal fun shouldPausePlaybackWhenStopped(
    videoPlaying: Boolean,
    inPictureInPictureMode: Boolean
): Boolean {
    return videoPlaying && !inPictureInPictureMode
}
