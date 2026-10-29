package com.diplay.ts7

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import android.widget.ImageView
import android.widget.Toast
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.AppLocale
import com.shilapi.xcertplay.DiPlayLauncherIntegration
import com.shilapi.xcertplay.orchestration.CarPlayTransport
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode

/** Native V2 shell. The original DiPlay owner handles both wired and wireless sessions. */
class LauncherActivity : Activity(), SurfaceHolder.Callback {
    private lateinit var store: LauncherStore
    private lateinit var ui: LauncherUi
    private lateinit var root: LinearLayout
    private lateinit var header: LinearLayout
    private lateinit var clock: TextClock
    private lateinit var status: TextView
    private lateinit var content: FrameLayout
    private lateinit var local: FrameLayout
    private lateinit var panel: CarPlayPanel
    private var service: Ts7SessionService? = null
    private var bound = false
    private var started = false
    private var resumed = false
    private var page = LauncherNavigation.HOME
    private var state = SessionState()
    private var availableSurface: Surface? = null
    private var languageAtCreate = AppLocale.SYSTEM
    private var wizard: DeviceSetupWizard? = null
    private var hotspot: HotspotControls? = null
    private var connectionSheet: AlertDialog? = null
    private var pendingReport: String? = null
    private val observer: (SessionState) -> Unit = { next ->
        val changed = state != next
        state = next
        updateStatus()
        if (changed) renderPage()
        maybeAutoConnect()
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as Ts7SessionService.LocalBinder).service
            if (started) service?.observe(observer)
            if (started && page == LauncherNavigation.CARPLAY) availableSurface?.let { service?.attachSurface(it) }
            service?.setVisible(resumed)
            updateStatus()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            state = SessionState(SessionPhase.FAILED, R.string.ts7_failure_start)
            updateStatus(); renderPage()
        }
    }

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLocale.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = LauncherStore(this)
        store.observeBoot()
        languageAtCreate = AppLocale.preference(this)
        if (store.originalHome == null) {
            val original = DeviceProfile.defaultHome(this)
            if (AppCatalog.list(this, true).any { it.component == original }) store.originalHome = original?.flattenToString()
        }
        page = savedInstanceState?.getString(LauncherNavigation.PAGE)
            ?: intent.getStringExtra(LauncherNavigation.PAGE) ?: LauncherNavigation.HOME
        ui = LauncherUi(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(LauncherUi.EDGE_GUTTER), ui.dp(4), ui.dp(LauncherUi.MARGIN), ui.dp(LauncherUi.GAP))
        }
        header = ui.row()
        clock = TextClock(this).apply { textSize = 14f; gravity = Gravity.CENTER_VERTICAL; format24Hour = "HH:mm"; format12Hour = "h:mm" }
        header.addView(clock, LinearLayout.LayoutParams(0, ui.dp(LauncherUi.TOUCH), 1f))
        status = ui.text("", 14f).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            minHeight = ui.dp(LauncherUi.TOUCH)
            setOnClickListener { showConnectionSheet() }
            isFocusable = true
        }
        header.addView(status, LinearLayout.LayoutParams(-2, ui.dp(LauncherUi.TOUCH)))
        root.addView(header)
        content = FrameLayout(this)
        content.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) content.post { if (!isDestroyed) renderPage() }
        }
        panel = CarPlayPanel(this)
        panel.video.holder.addCallback(this)
        panel.video.contentDescription = "CarPlay"
        panel.video.setOnTouchListener { _, event ->
            if (state.phase != SessionPhase.CONNECTED || !state.pictureAvailable) false else {
                service?.touch(event, panel.video.width, panel.video.height)
                if (event.actionMasked == MotionEvent.ACTION_UP) panel.video.performClick()
                true
            }
        }
        content.addView(panel, FrameLayout.LayoutParams(-1, -1))
        local = FrameLayout(this)
        content.addView(local, FrameLayout.LayoutParams(-1, -1))
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        applyTheme()
    }

    internal fun showPage(next: String) {
        if (next == LauncherNavigation.GUIDE) { showGuide(); return }
        page = next.takeIf { it in setOf(LauncherNavigation.HOME, LauncherNavigation.CARPLAY, LauncherNavigation.SETTINGS) }
            ?: LauncherNavigation.HOME
        releaseCarPlayTouches()
        renderPage()
        if (page == LauncherNavigation.CARPLAY) availableSurface?.let { service?.attachSurface(it) }
    }

    private fun applyTheme() {
        ui = LauncherUi(this)
        root.setBackgroundColor(ui.background)
        clock.setTextColor(ui.text)
        updateStatus(); renderPage()
        wizard?.refresh(); hotspot?.refresh()
        (application as Ts7Application).refreshChrome(this)
    }

    private fun updateStatus() {
        status.text = if (state.phase == SessionPhase.CONNECTED) getString(R.string.ts7_phone_connected)
            else getString(Ts7SessionService.phaseLabel(state.phase))
        status.setTextColor(if (state.phase == SessionPhase.CONNECTED) ui.success else ui.secondary)
        if (state.phase == SessionPhase.CONNECTED && resumed) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun renderPage() {
        panel.setStreamSize(state.videoWidth, state.videoHeight)
        val projected = page == LauncherNavigation.CARPLAY && state.phase == SessionPhase.CONNECTED && state.pictureAvailable
        panel.visibility = if (projected) View.VISIBLE else View.GONE
        local.visibility = if (projected) View.GONE else View.VISIBLE
        if (projected) return
        local.removeAllViews()
        val body = if (page == LauncherNavigation.SETTINGS) settingsPage() else homePage()
        local.addView(ScrollView(this).apply { isFillViewport = true; addView(body) }, FrameLayout.LayoutParams(-1, -1))
    }

    private fun homePage(): View {
        val body = ui.column().apply { gravity = Gravity.CENTER_VERTICAL }
        val availableWidth = content.width.takeIf { it > 0 }
            ?: (resources.displayMetrics.widthPixels - ui.dp(LauncherUi.EDGE_GUTTER + LauncherUi.MARGIN))
        val wide = availableWidth / resources.displayMetrics.density >= 600
        val layout = LinearLayout(this).apply {
            orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val card = ui.card().apply { tag = "ts7_connection_card" }
        card.addView(ImageView(this).apply { setImageResource(com.shilapi.xcertplay.host.R.drawable.ic_carplay) },
            LinearLayout.LayoutParams(ui.dp(64), ui.dp(64)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        card.addView(ui.gap())
        card.addView(ui.text("CarPlay", 20f, bold = true).apply { gravity = Gravity.CENTER })
        val description = when {
            state.phase == SessionPhase.CONNECTED -> getString(R.string.ts7_state_connected)
            DiPlayLauncherIntegration.authentication(this) == DiPlayLauncherIntegration.Authentication.MISSING -> getString(R.string.ts7_auth_not_configured)
            else -> getString(Ts7SessionService.phaseLabel(state.phase))
        }
        card.addView(ui.text(description, 16f, ui.secondary).apply { gravity = Gravity.CENTER })
        if (state.message != 0) card.addView(ui.text(getString(state.message), 14f, ui.attention))
        card.addView(ui.gap())
        if (state.phase == SessionPhase.CONNECTED) {
            card.addView(ui.button(getString(R.string.ts7_open_carplay), true) {
                if (state.pictureAvailable) showPage(LauncherNavigation.CARPLAY)
                else LauncherNavigation.open(this, DiPlayLauncherIntegration.projectionIntent(this))
            }, LinearLayout.LayoutParams(-1, ui.dp(LauncherUi.TOUCH)))
        } else if (state.phase in listOf(SessionPhase.CONNECTING, SessionPhase.WAITING_PHONE, SessionPhase.WAITING_NETWORK)) {
            card.addView(ui.button(getString(R.string.ts7_cancel_connection)) { service?.disconnect() },
                LinearLayout.LayoutParams(-1, ui.dp(LauncherUi.TOUCH)))
        } else {
            val actions = ui.row()
            actions.addView(ui.button(getString(R.string.ts7_wifi_connection), true) { connect(CarPlayTransport.WIRELESS) },
                LinearLayout.LayoutParams(0, ui.dp(LauncherUi.TOUCH), 1f))
            actions.addView(ui.button(getString(R.string.ts7_usb_connection)) { connect(CarPlayTransport.WIRED) },
                LinearLayout.LayoutParams(0, ui.dp(LauncherUi.TOUCH), 1f).apply { marginStart = ui.dp(LauncherUi.GAP) })
            card.addView(actions)
            card.addView(ui.gap())
            card.addView(ui.button(getString(R.string.ts7_connection_settings)) {
                LauncherNavigation.open(this, DiPlayLauncherIntegration.pageIntent(this, "connection"))
            }, LinearLayout.LayoutParams(-1, ui.dp(LauncherUi.TOUCH)))
        }
        layout.addView(card, if (wide && page == LauncherNavigation.HOME) LinearLayout.LayoutParams(0, -2, 1f)
            else LinearLayout.LayoutParams(-1, -2))
        if (page == LauncherNavigation.HOME) {
            val right = ui.column()
            val shortcuts = ui.row()
            repeat(2) { slot ->
                val selected = store.shortcut(slot)?.let(ComponentName::unflattenFromString)
                val label = AppCatalog.list(this).firstOrNull { it.component == selected }?.label ?: getString(R.string.ts7_shortcut_slot, slot + 1)
                val item = ui.card().apply {
                    gravity = Gravity.CENTER
                    addView(ImageView(this@LauncherActivity).apply {
                        val icon = selected?.let { runCatching { packageManager.getActivityIcon(it) }.getOrNull() }
                        if (icon != null) setImageDrawable(icon)
                        else {
                            setImageResource(if (slot == 0) com.shilapi.xcertplay.host.R.drawable.ic_dp_navigation else com.shilapi.xcertplay.host.R.drawable.ic_dp_audio)
                            imageTintList = android.content.res.ColorStateList.valueOf(ui.secondary)
                        }
                    }, LinearLayout.LayoutParams(ui.dp(56), ui.dp(56)))
                    addView(ui.gap())
                    addView(ui.text(label, 16f).apply { gravity = Gravity.CENTER; maxLines = 2 })
                    isClickable = true; isFocusable = true; contentDescription = label
                    setOnClickListener { LauncherNavigation.shortcut(this@LauncherActivity, slot) }
                }
                item.setOnLongClickListener { LauncherNavigation.chooseShortcut(this, slot) { renderPage() }; true }
                shortcuts.addView(item, LinearLayout.LayoutParams(0, ui.dp(if (wide) 220 else 120), 1f).apply { if (slot > 0) marginStart = ui.dp(LauncherUi.GAP) })
            }
            right.addView(shortcuts); right.addView(ui.gap())
            right.addView(ui.rowLabel(getString(R.string.ts7_device_setup), getString(R.string.ts7_setup_optional)) { showGuide() }.apply {
                setPadding(ui.dp(LauncherUi.GAP), ui.dp(LauncherUi.GAP), ui.dp(LauncherUi.GAP), ui.dp(LauncherUi.GAP))
                background = ui.shape(ui.surface)
            }, LinearLayout.LayoutParams(-1, ui.dp(72)))
            layout.addView(right, if (wide) LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(LauncherUi.GAP) }
                else LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(LauncherUi.GAP) })
        }
        body.addView(layout)
        return body
    }

    private fun settingsPage(): View = ui.card().apply {
        fun row(id: Int, value: String = "", action: () -> Unit) {
            addView(ui.rowLabel(getString(id), value, action)); addView(ui.gap())
        }
        row(R.string.ts7_device_setup) { showGuide() }
        addView(ui.rowLabel(getString(R.string.ts7_system_theme), getString(if (ui.night) R.string.ts7_theme_dark else R.string.ts7_theme_day)))
        row(R.string.ts7_default_home, getString(if (DeviceProfile.isDefaultHome(this@LauncherActivity)) R.string.ts7_home_selected else R.string.ts7_home_not_selected)) {
            LauncherNavigation.homeSettings(this@LauncherActivity)
        }
        row(R.string.ts7_startup, getString(R.string.ts7_boot_unverified)) { showGuide(3) }
        row(R.string.ts7_hotspot, DeviceProfile.hotspotLabel(this@LauncherActivity)) { openHotspotControls() }
        row(R.string.ts7_connection, DeviceProfile.authenticationLabel(this@LauncherActivity)) {
            LauncherNavigation.open(this@LauncherActivity, DiPlayLauncherIntegration.pageIntent(this@LauncherActivity, "connection"))
        }
        row(R.string.ts7_diplay_settings) { LauncherNavigation.diPlaySettings(this@LauncherActivity) }
        row(R.string.ts7_choose_home) { LauncherNavigation.chooseOriginal(this@LauncherActivity, changed = ::renderPage) }
        row(R.string.ts7_system_settings) { LauncherNavigation.systemSettings(this@LauncherActivity) }
        row(R.string.ts7_export_report) { exportReport() }
    }

    private fun connect(transport: CarPlayTransport) {
        if (service == null) { LauncherNavigation.connect(this, transport); return }
        service?.prepareDiPlay { ready ->
            if (resumed && !isFinishing && !isDestroyed) {
                if (ready) LauncherNavigation.connect(this, transport)
                else LauncherNavigation.toast(this, R.string.ts7_failure_cleanup)
            }
        }
    }

    private fun maybeAutoConnect() {
        if (!resumed || !window.decorView.hasWindowFocus() || wizard?.showing == true || !store.guideSeen || store.suppressed || store.autoConsumed ||
            !DiPlayLauncherIntegration.autoConnect(this) || DiPlayLauncherIntegration.hasSession() ||
            DiPlayLauncherIntegration.projectionOpen() || DiPlayLauncherIntegration.authentication(this) == DiPlayLauncherIntegration.Authentication.MISSING) return
        val transport = DiPlayLauncherIntegration.defaultTransport(this)
        if (!DiPlayLauncherIntegration.permissionsReady(this, transport)) return
        if (transport == CarPlayTransport.WIRED && DeviceProfile.usbDevices(this) == 0) return
        if (transport == CarPlayTransport.WIRELESS) {
            if (!DiPlayLauncherIntegration.phoneSelected(this)) return
            if (AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.EXISTING_WIFI && service?.localNetworkReady() != true) return
        }
        if (service == null) return
        store.autoConsumed = true
        connect(transport)
    }

    private fun showConnectionSheet() {
        releaseCarPlayTouches()
        connectionSheet?.dismiss()
        connectionSheet = ui.alert().setMessage(getString(Ts7SessionService.phaseLabel(state.phase)))
            .setPositiveButton(if (DiPlayLauncherIntegration.hasSession()) R.string.ts7_disconnect else R.string.ts7_connection_settings) { _, _ ->
                if (DiPlayLauncherIntegration.hasSession()) service?.disconnect()
                else LauncherNavigation.open(this, DiPlayLauncherIntegration.pageIntent(this, "connection"))
            }.setNegativeButton(R.string.ts7_cancel, null).show()
    }

    private fun showGuide(step: Int? = null) {
        if (wizard?.showing == true) return
        wizard = DeviceSetupWizard(this) { wizard = null; renderPage(); maybeAutoConnect() }.also { it.show(step) }
    }
    internal fun openHotspotControls() {
        hotspot?.close()
        hotspot = HotspotControls(this) { resumed }.also { it.show() }
    }
    internal fun releaseCarPlayTouches() { service?.releaseTouches(); DiPlayLauncherIntegration.releaseTouches() }

    override fun onStart() {
        super.onStart(); started = true
        if (!bound) bound = bindService(Intent(this, Ts7SessionService::class.java), connection, Context.BIND_AUTO_CREATE)
        service?.observe(observer)
        if (page == LauncherNavigation.CARPLAY) availableSurface?.let { service?.attachSurface(it) }
    }
    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT < 33 && languageAtCreate != AppLocale.preference(this)) { recreate(); return }
        resumed = true
        service?.setVisible(true)
        applyTheme()
        if (!store.guideSeen && !DiPlayLauncherIntegration.hasSession()) root.post { if (resumed && !store.guideSeen) showGuide() }
        else maybeAutoConnect()
    }
    override fun onPause() {
        resumed = false
        service?.cancelDiPlayHandoff(); service?.setVisible(false)
        connectionSheet?.dismiss(); connectionSheet = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onPause()
    }
    override fun onStop() {
        started = false
        service?.removeObserver(observer)
        availableSurface?.let { service?.detachSurface(it) }
        super.onStop()
    }
    override fun onDestroy() {
        wizard?.leave(); hotspot?.close()
        service?.removeObserver(observer)
        availableSurface?.let { service?.detachSurface(it) }
        if (bound) unbindService(connection)
        service = null
        super.onDestroy()
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        showPage(intent.getStringExtra(LauncherNavigation.PAGE) ?: LauncherNavigation.HOME)
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(LauncherNavigation.PAGE, page)
        super.onSaveInstanceState(outState)
    }
    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); applyTheme(); panel.requestLayout() }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::store.isInitialized) maybeAutoConnect()
    }
    @Deprecated("HOME keeps its root task alive")
    override fun onBackPressed() { if (wizard?.showing == true) wizard?.leave() else showPage(LauncherNavigation.HOME) }
    override fun surfaceCreated(holder: SurfaceHolder) {
        availableSurface = holder.surface
        if (started && page == LauncherNavigation.CARPLAY) availableSurface?.let { service?.attachSurface(it) }
    }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        availableSurface?.let { service?.detachSurface(it) }; availableSurface = null
    }

    private fun exportReport() {
        pendingReport = service?.report() ?: SessionDiagnostics(this).report(state)
        try {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/plain").putExtra(Intent.EXTRA_TITLE, "ts7-diagnostics.txt"), REPORT_REQUEST)
        } catch (_: ActivityNotFoundException) { Toast.makeText(this, R.string.ts7_report_failed, Toast.LENGTH_LONG).show() }
    }
    @Deprecated("Supported platform result API on API 25")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REPORT_REQUEST || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val report = pendingReport ?: service?.report() ?: return
        val saved = runCatching { checkNotNull(contentResolver.openOutputStream(uri, "wt")).bufferedWriter().use { it.write(report) } }.isSuccess
        pendingReport = null
        Toast.makeText(this, if (saved) R.string.ts7_report_saved else R.string.ts7_report_failed, Toast.LENGTH_LONG).show()
    }
    companion object { private const val REPORT_REQUEST = 11 }
}
