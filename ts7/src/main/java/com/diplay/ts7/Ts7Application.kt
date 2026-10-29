package com.diplay.ts7

import android.app.Application
import com.shilapi.xcertplay.AppLocale
import com.shilapi.xcertplay.DiPlayLauncherIntegration

class Ts7Application : Application() {
    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences("ts7_ui", MODE_PRIVATE)
        if (!prefs.getBoolean("language_initialized", false)) {
            AppLocale.save(this, AppLocale.SIMPLIFIED_CHINESE)
            prefs.edit().putBoolean("language_initialized", true).apply()
        }
        DiPlayLauncherIntegration.initialize(this)
    }
}
