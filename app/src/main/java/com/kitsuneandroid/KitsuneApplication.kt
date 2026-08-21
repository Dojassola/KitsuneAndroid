package com.kitsuneandroid

import android.app.Application

class KitsuneApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppPerformance.initialize(this)
    }
}
