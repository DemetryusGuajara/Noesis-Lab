package com.example

import android.app.Application
import android.util.Log
import com.example.util.AppLog
import com.example.util.LogCollector

class NoesisApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this, BuildConfig.ENABLE_FILE_LOGGING)
        AppLog.i("lifecycle", "app created fileLogging=${BuildConfig.ENABLE_FILE_LOGGING}")
        AppLog.checkGenMarker(consume = false)?.let { detail ->
            AppLog.w("watchdog", "previous process died natively mid-step ($detail)")
        }
        Log.i("NoesisApp", "onCreate fileLogging=${BuildConfig.ENABLE_FILE_LOGGING}")
        if (BuildConfig.ENABLE_FILE_LOGGING) {
            LogCollector.start(this)
        }
    }
}
