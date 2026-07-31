package com.kitsuneandroid

import android.app.PictureInPictureParams
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.kitsuneandroid.ui.theme.KitsuneAndroidTheme

class MainActivity : ComponentActivity() {
    var videoPlaying = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KitsuneAndroidTheme(darkTheme = true, dynamicColor = false) {
                KitsuneApp()
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (videoPlaying && Build.VERSION.SDK_INT in Build.VERSION_CODES.O until Build.VERSION_CODES.S) {
            enterPictureInPictureMode(PictureInPictureParams.Builder().build())
        }
    }
}
