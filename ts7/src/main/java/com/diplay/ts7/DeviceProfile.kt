package com.diplay.ts7

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.usb.UsbManager
import android.os.Build
import android.provider.Settings
import com.shilapi.xcertplay.DiPlayLauncherIntegration
import com.shilapi.xcertplay.network.CarHotspotStatus
import com.shilapi.xcertplay.network.CarHotspotTethering

internal data class DeviceFact(val label: Int, val value: String)

/** Read-only facts. Capability declarations are never reported as successful vehicle tests. */
internal object DeviceProfile {
    fun defaultHome(context: Context): ComponentName? = context.packageManager.resolveActivity(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
        ?.activityInfo?.let { ComponentName(it.packageName, it.name) }
    fun isDefaultHome(context: Context): Boolean = defaultHome(context) == ComponentName(context, LauncherActivity::class.java)
    fun homeSettingsAvailable(context: Context): Boolean =
        Intent(Settings.ACTION_HOME_SETTINGS).resolveActivity(context.packageManager) != null
    fun usbDevices(context: Context): Int = context.getSystemService(UsbManager::class.java)?.deviceList?.size ?: 0
    fun themeKnown(context: Context): Boolean = (context.applicationContext.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) in
        setOf(Configuration.UI_MODE_NIGHT_YES, Configuration.UI_MODE_NIGHT_NO)

    fun collect(activity: Activity): List<DeviceFact> {
        val c = activity
        val metrics = c.resources.displayMetrics
        val window = c.findViewById<android.view.View>(android.R.id.content)
        return listOf(
            DeviceFact(R.string.ts7_device_system, "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}"),
            DeviceFact(R.string.ts7_device_screen, c.getString(R.string.ts7_screen_fact,
                metrics.widthPixels, metrics.heightPixels, metrics.densityDpi, window.width, window.height)),
            DeviceFact(R.string.ts7_system_theme, if (!themeKnown(c)) c.getString(R.string.ts7_theme_unknown)
                else c.getString(if (DiPlayLauncherIntegration.systemNight(c)) R.string.ts7_theme_dark else R.string.ts7_theme_day)),
            DeviceFact(R.string.ts7_default_home, c.getString(if (isDefaultHome(c)) R.string.ts7_home_selected else R.string.ts7_home_not_selected)),
            DeviceFact(R.string.ts7_usb_connection, c.getString(if (c.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST))
                R.string.ts7_usb_declared else R.string.ts7_usb_not_declared) + " · " + c.getString(R.string.ts7_usb_device_count, usbDevices(c))),
            DeviceFact(R.string.ts7_wifi_connection, c.getString(if (c.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI))
                R.string.ts7_wifi_declared else R.string.ts7_wifi_not_declared)),
            DeviceFact(R.string.ts7_hotspot, hotspotLabel(c)),
            DeviceFact(R.string.ts7_hotspot_control, c.getString(if (CarHotspotTethering.appStartSupported())
                R.string.ts7_hotspot_app_api else R.string.ts7_hotspot_privileged_api)),
            DeviceFact(R.string.ts7_startup, c.getString(R.string.ts7_boot_unverified)),
            DeviceFact(R.string.ts7_resume_connect, c.getString(R.string.ts7_wake_unverified)),
        )
    }

    fun hotspotLabel(context: Context): String = context.getString(when (CarHotspotStatus.isEnabled(context)) {
        true -> R.string.ts7_enabled
        false -> R.string.ts7_disabled
        null -> R.string.ts7_unknown
    })
    fun authenticationLabel(context: Context): String = context.getString(when (DiPlayLauncherIntegration.authentication(context)) {
        DiPlayLauncherIntegration.Authentication.MISSING -> R.string.ts7_auth_not_configured
        DiPlayLauncherIntegration.Authentication.CONFIGURED -> R.string.ts7_auth_configured
        DiPlayLauncherIntegration.Authentication.EXTERNAL -> R.string.ts7_auth_external
    })
    fun microphoneGranted(context: Context) = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
}
