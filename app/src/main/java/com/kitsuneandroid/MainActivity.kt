package com.kitsuneandroid

import android.app.PictureInPictureParams
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.kitsuneandroid.ui.theme.KitsuneAndroidTheme

class MainActivity : ComponentActivity() {
    var videoPlaying = false
    var pauseVideoPlayback: (() -> Unit)? = null
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.withInterfaceLanguage())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val startupStartedAt = AppPerformance.start()
        super.onCreate(savedInstanceState)
        AppPerformance.initialize(this)
        EpisodeApi.initialize(this)
        EpisodeUpdateNotifications.ensureScheduled(this)
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
