package com.diplay.ts7

import android.app.Application
import android.app.Activity
import android.os.Bundle
import android.content.res.Configuration
import com.shilapi.xcertplay.DiPlayActivity
import com.shilapi.xcertplay.CarPlayHostActivity
import com.shilapi.xcertplay.AppLocale
import com.shilapi.xcertplay.DiPlayLauncherIntegration

class Ts7Application : Application() {
    private val chrome = mutableMapOf<Activity, FloatingLauncher>()

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences("ts7_ui", MODE_PRIVATE)
        if (!prefs.getBoolean("language_initialized", false)) {
            AppLocale.save(this, AppLocale.SIMPLIFIED_CHINESE)
            prefs.edit().putBoolean("language_initialized", true).apply()
        }
        DiPlayLauncherIntegration.initialize(this)
        val store = LauncherStore(this)
        DiPlayLauncherIntegration.importDesktopConnection(this, store.ssid, store.password, store.phone, store.audioFocus)
        if (!store.diPlayMode && store.autoConnect) DiPlayLauncherIntegration.saveAutoConnect(this, true)
        store.autoConnect = false
        store.diPlayMode = true
        DiPlayLauncherIntegration.onManualDisconnect = {
            LauncherStore(this).apply { suppressed = true; autoConsumed = true }
        }
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (activity is LauncherActivity || activity is DiPlayActivity || activity is CarPlayHostActivity) {
                    chrome.getOrPut(activity) { FloatingLauncher(activity) }.apply { refresh(); attach() }
                }
            }
            override fun onActivityPaused(activity: Activity) { chrome[activity]?.detach() }
            override fun onActivityDestroyed(activity: Activity) { chrome.remove(activity)?.detach() }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        })
    }

    internal fun refreshChrome(activity: Activity) { chrome[activity]?.refresh() }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        chrome.values.toList().forEach { it.refresh() }
    }
}
