package com.diplay.ts7

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Window
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.DiPlayLauncherIntegration
import com.shilapi.xcertplay.network.CarHotspotSettings
import com.shilapi.xcertplay.network.CarHotspotTethering
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import java.util.concurrent.Executors

/** Explicit user actions only. The existing DiPlay helper owns the bounded platform/ADB attempt. */
internal class HotspotControls(private val activity: Activity, private val foreground: () -> Boolean) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var dialog: Dialog? = null
    @Volatile private var closed = false
    private var busy = false
    private var result: Int? = null
    private fun hotspotMode() = AirPlayPersistence.loadWirelessHotspotMode(activity) == WirelessHotspotMode.MANUAL

    fun show() {
        dialog = Dialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setOnDismissListener { close() }
            show()
        }
        refresh()
    }

    fun refresh() {
        val target = dialog ?: return
        val ui = LauncherUi(activity)
        val panel = ui.column(LauncherUi.MARGIN).apply { background = ui.shape(ui.surface) }
        val header = ui.row()
        header.addView(ui.text(activity.getString(R.string.ts7_hotspot), 20f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(ui.button("×") { close() }.apply { contentDescription = activity.getString(R.string.ts7_close) },
            LinearLayout.LayoutParams(ui.dp(LauncherUi.TOUCH), ui.dp(LauncherUi.TOUCH)))
        panel.addView(header)
        val content = ui.column()
        content.addView(ui.rowLabel(activity.getString(R.string.ts7_hotspot_state), DeviceProfile.hotspotLabel(activity)))
        content.addView(ui.rowLabel(activity.getString(R.string.ts7_wireless_mode), when (AirPlayPersistence.loadWirelessHotspotMode(activity)) {
            WirelessHotspotMode.EXISTING_WIFI -> activity.getString(R.string.ts7_same_lan)
            WirelessHotspotMode.WIFI_P2P -> "Wi-Fi Direct"
            else -> activity.getString(R.string.ts7_hotspot)
        }))
        content.addView(ui.text(activity.getString(if (CarHotspotTethering.appStartSupported())
            R.string.ts7_hotspot_app_api else R.string.ts7_hotspot_privileged_api), 14f, ui.secondary))
        content.addView(ui.gap())
        content.addView(Switch(activity).apply {
            text = activity.getString(R.string.ts7_hotspot_auto)
            textSize = 16f; setTextColor(ui.text); minHeight = ui.dp(LauncherUi.TOUCH)
            isChecked = CarHotspotSettings.enabled(activity)
            setOnCheckedChangeListener { _, checked -> CarHotspotSettings.setEnabled(activity, checked) }
        })
        content.addView(ui.text(activity.getString(R.string.ts7_hotspot_mode_help), 14f, ui.secondary))
        fun action(id: Int, enabled: Boolean = true, block: () -> Unit) {
            content.addView(ui.gap())
            content.addView(ui.button(activity.getString(id), action = block).apply { isEnabled = enabled },
                LinearLayout.LayoutParams(-1, ui.dp(LauncherUi.TOUCH)))
        }
        if (!hotspotMode()) action(R.string.ts7_connection_settings) {
            close(); LauncherNavigation.open(activity, DiPlayLauncherIntegration.pageIntent(activity, "connection"))
        }
        if (!CarHotspotTethering.permitted(activity)) action(R.string.ts7_allow_hotspot_permission) {
            LauncherNavigation.open(activity, Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:${activity.packageName}")))
        }
        if (!CarHotspotTethering.appStartSupported()) {
            content.addView(ui.gap())
            content.addView(ui.text(activity.getString(R.string.ts7_hotspot_adb_help), 14f, ui.secondary))
            action(R.string.ts7_hotspot_adb_authorize, enabled = !busy) { authorizeAdb() }
        }
        result?.let { content.addView(ui.gap()); content.addView(ui.text(activity.getString(it), 14f, ui.attention)) }
        panel.addView(ScrollView(activity).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        panel.addView(ui.gap())
        panel.addView(ui.button(activity.getString(if (busy) R.string.ts7_hotspot_starting else R.string.ts7_hotspot_enable), true) { enable() }
            .apply { isEnabled = !busy && hotspotMode() }, LinearLayout.LayoutParams(-1, ui.dp(LauncherUi.TOUCH)))
        target.setContentView(panel)
        target.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val metrics = activity.resources.displayMetrics
            setLayout(minOf(ui.dp(640), (metrics.widthPixels * 0.88).toInt()), (metrics.heightPixels * 0.86).toInt())
        }
    }

    private fun enable() {
        if (busy || closed || !hotspotMode()) return
        busy = true; result = null; refresh()
        worker.execute {
            val outcome = runCatching {
                CarHotspotTethering.enable(activity.applicationContext,
                    isCancelled = { closed || !foreground() || !hotspotMode() },
                    log = {})
            }.getOrDefault(CarHotspotTethering.Result.FAILED)
            main.post {
                if (!closed) {
                    busy = false
                    activity.getSharedPreferences("ts7_hotspot_diagnostics", Activity.MODE_PRIVATE).edit()
                        .putString("last_result", outcome.name).putLong("at", System.currentTimeMillis()).apply()
                    result = when (outcome) {
                        CarHotspotTethering.Result.READY -> R.string.ts7_hotspot_ready
                        CarHotspotTethering.Result.PERMISSION_REQUIRED -> R.string.ts7_hotspot_permission_needed
                        CarHotspotTethering.Result.UNSUPPORTED -> R.string.ts7_hotspot_unsupported
                        CarHotspotTethering.Result.TIMED_OUT -> R.string.ts7_hotspot_timeout
                        CarHotspotTethering.Result.CANCELLED -> R.string.ts7_hotspot_cancelled
                        CarHotspotTethering.Result.FAILED -> R.string.ts7_hotspot_failed
                    }
                    refresh()
                }
            }
        }
    }

    private fun authorizeAdb() {
        if (busy || closed) return
        busy = true; refresh()
        worker.execute {
            val granted = runCatching { DiPlayLauncherIntegration.requestHotspotAccess(activity.applicationContext) }.getOrDefault(false)
            main.post {
                if (!closed) {
                    busy = false
                    result = if (granted) R.string.ts7_hotspot_permission_granted else R.string.ts7_hotspot_adb_unavailable
                    refresh()
                }
            }
        }
    }

    fun close() {
        if (closed) return
        closed = true
        main.removeCallbacksAndMessages(null)
        val current = dialog; dialog = null
        current?.dismiss()
        worker.shutdownNow()
    }
}
