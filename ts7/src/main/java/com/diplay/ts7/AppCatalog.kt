package com.diplay.ts7

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

internal data class LaunchableApp(val component: ComponentName, val label: String)

internal object AppCatalog {
    fun list(context: Context, home: Boolean = false): List<LaunchableApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(
            if (home) Intent.CATEGORY_HOME else Intent.CATEGORY_LAUNCHER)
        return context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY.takeIf { home } ?: 0)
            .filter { it.activityInfo.packageName != context.packageName && it.activityInfo.enabled && it.activityInfo.exported }
            .map { LaunchableApp(ComponentName(it.activityInfo.packageName, it.activityInfo.name),
                it.loadLabel(context.packageManager).toString()) }
            .distinctBy { it.component }.sortedBy { it.label.lowercase() }
    }

    fun intent(component: ComponentName) = Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LAUNCHER).setComponent(component)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
}
