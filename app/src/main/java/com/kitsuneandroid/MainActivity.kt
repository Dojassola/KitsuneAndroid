package com.kitsuneandroid

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kitsuneandroid.ui.theme.KitsuneAndroidTheme
import androidx.fragment.app.FragmentActivity

class MainActivity : FragmentActivity() {
    companion object {
        internal const val EXTRA_ANIME_ID = "com.kitsuneandroid.extra.ANIME_ID"
    }

    var videoPlaying = false
    var pauseVideoPlayback: (() -> Unit)? = null
    private var notificationAnimeId by mutableStateOf<Int?>(null)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.withInterfaceLanguage())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val startupStartedAt = AppPerformance.start()
        super.onCreate(savedInstanceState)
        EpisodeApi.initialize(this)
        EpisodeUpdateNotifications.ensureScheduled(this)
        AccountSync.handleOAuthCallback(this, intent?.data)
        AccountSync.flush(this)
        receiveNotificationIntent(intent)
        enableEdgeToEdge()
        setContent {
            KitsuneAndroidTheme(darkTheme = true, dynamicColor = false) {
                KitsuneApp(
                    notificationAnimeId = notificationAnimeId,
                    onNotificationAnimeConsumed = ::consumeNotificationAnime
                )
            }
        }
        window.decorView.post {
            AppPerformance.record("Inicialização até primeira tela", startupStartedAt)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        AccountSync.handleOAuthCallback(this, intent.data)
        receiveNotificationIntent(intent)
    }

    private fun receiveNotificationIntent(intent: Intent?) {
        val rawAnimeId = intent?.getIntExtra(EXTRA_ANIME_ID, 0) ?: 0
        notificationAnimeId = validNotificationAnimeId(rawAnimeId)
    }

    private fun consumeNotificationAnime(animeId: Int) {
        if (notificationAnimeId != animeId) {
            return
        }

        notificationAnimeId = null
        intent?.removeExtra(EXTRA_ANIME_ID)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (videoPlaying && Build.VERSION.SDK_INT in Build.VERSION_CODES.O until Build.VERSION_CODES.S) {
            enterPictureInPictureMode(PictureInPictureParams.Builder().build())
        }
    }

    override fun onStop() {
        super.onStop()
        AutomaticBackup.runIfNeeded(this)
        if (shouldPausePlaybackWhenStopped(videoPlaying, isInPictureInPictureMode)) {
            pauseVideoPlayback?.invoke()
        }
    }
}

internal fun validNotificationAnimeId(value: Int): Int? {
    if (value <= 0) {
        return null
    }

    return value
}

internal fun shouldPausePlaybackWhenStopped(
    videoPlaying: Boolean,
    inPictureInPictureMode: Boolean
): Boolean {
    return videoPlaying && !inPictureInPictureMode
}
