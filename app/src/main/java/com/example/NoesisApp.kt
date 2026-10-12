package com.example

import android.app.Application
import android.util.Log
import com.example.util.LogCollector

class NoesisApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.i("NoesisApp", "onCreate fileLogging=${BuildConfig.ENABLE_FILE_LOGGING}")
        if (BuildConfig.ENABLE_FILE_LOGGING) {
            LogCollector.start(this)
        }
    }
}
