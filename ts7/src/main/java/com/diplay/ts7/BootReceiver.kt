package com.diplay.ts7

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** HOME selection belongs to Android. Never launch an Activity over a camera or OEM screen. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) LauncherStore(context).observeBoot()
    }
}
