package com.diplay.ts7

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import com.shilapi.xcertplay.orchestration.ManualHotspotValidation
import com.shilapi.xcertplay.AppLocale
import com.shilapi.xcertplay.DiPlayLauncherIntegration

class LauncherActivity : Activity(), SurfaceHolder.Callback {
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLocale.wrap(newBase))
    private lateinit var store: LauncherStore
    private lateinit var panel: CarPlayPanel
    private lateinit var status: TextView
    private lateinit var details: TextView
    private lateinit var local: LinearLayout
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var rail: LinearLayout
    private var service: Ts7SessionService? = null
    private var bound = false
    private var started = false
    private var resumed = false
    private var settingsDialog: AlertDialog? = null
    private var editing = false
    private var state = SessionState()
    private var pendingReport: String? = null
    private var availableSurface: Surface? = null
    private var languageAtCreate = AppLocale.SYSTEM
    private val observer: (SessionState) -> Unit = { render(it) }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val session = (binder as Ts7SessionService.LocalBinder).service
            service = session
            if (started) session.observe(observer)
            if (editing) session.beginEditing()
            if (started) availableSurface?.let(session::attachSurface)
            session.setVisible(resumed)
        }
        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            render(SessionState(SessionPhase.FAILED, R.string.ts7_failure_start))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        languageAtCreate = AppLocale.preference(this)
        store = LauncherStore(this)
        // Capture the resolved original HOME before asking the user to change the default.
        if (store.originalHome == null) {
            val original = packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            val component = original?.activityInfo?.let { ComponentName(it.packageName, it.name) }
            if (AppCatalog.list(this, home = true).any { it.component == component }) store.originalHome = component?.flattenToString()
        }
        buildHome()
    }

    private fun buildHome() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(GAP), dp(GAP), dp(GAP), dp(GAP))
            setBackgroundColor(BACKGROUND)
        }
        rail = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(rail, LinearLayout.LayoutParams(dp(RAIL_WIDTH), -1).apply { marginEnd = dp(GAP) })
        refreshRail()
        val main = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(main, LinearLayout.LayoutParams(0, -1, 1f))
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(label(getString(R.string.ts7_app_name), 22f, ACCENT), LinearLayout.LayoutParams(0, dp(CONTROL_HEIGHT), 1f))
        status = label("", 16f)
        header.addView(status, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(GAP) })
        main.addView(header)
        panel = CarPlayPanel(this)
        panel.video.contentDescription = getString(R.string.ts7_app_name)
        panel.video.holder.addCallback(this)
        panel.video.setOnTouchListener { _, event ->
            if (state.phase != SessionPhase.CONNECTED) return@setOnTouchListener false
            service?.touch(event, panel.video.width, panel.video.height)
            if (event.actionMasked == MotionEvent.ACTION_UP) panel.video.performClick()
            true
        }
        main.addView(panel, LinearLayout.LayoutParams(-1, 0, 1f))
        local = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(GAP), dp(GAP), dp(GAP), dp(GAP))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(24, 40, 53), Color.rgb(12, 23, 33)))
        }
        local.addView(TextClock(this).apply {
            gravity = Gravity.CENTER; textSize = 54f; setTextColor(Color.WHITE)
            format24Hour = "HH:mm"; format12Hour = "h:mm"
        })
        local.addView(TextClock(this).apply {
            gravity = Gravity.CENTER; textSize = 18f; setTextColor(MUTED)
            format24Hour = getString(R.string.ts7_date_format); format12Hour = getString(R.string.ts7_date_format)
        })
        local.addView(label(getString(R.string.ts7_local_hint), 15f, MUTED).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(GAP), 0, 0)
        })
        panel.addView(local, FrameLayout.LayoutParams(-1, -1))
        val footer = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        details = label("", 14f, MUTED)
        footer.addView(details, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(GAP) })
        connectButton = button(R.string.ts7_connect) { openDiPlay() }
        disconnectButton = button(R.string.ts7_disconnect) { service?.disconnect() }
        footer.addView(connectButton, LinearLayout.LayoutParams(dp(116), dp(CONTROL_HEIGHT)))
        footer.addView(disconnectButton, LinearLayout.LayoutParams(dp(116), dp(CONTROL_HEIGHT)).apply { marginStart = dp(GAP) })
        main.addView(footer, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(GAP) })
        setContentView(root)
        render(state)
    }

    private fun refreshRail() {
        rail.removeAllViews()
        rail.addView(label("TS7", 26f, ACCENT).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, dp(CONTROL_HEIGHT)))
        val quickApps = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        rail.addView(ScrollView(this).apply { addView(quickApps) }, LinearLayout.LayoutParams(-1, 0, 1f))
        railButton(R.string.ts7_diplay, quickApps) { openDiPlay() }
        railButton(R.string.ts7_apps, quickApps) { showApps() }
        repeat(2) { slot ->
            val saved = store.shortcut(slot)?.let(ComponentName::unflattenFromString)
            val app = saved?.let { selected -> AppCatalog.list(this).firstOrNull { it.component == selected } }
            val shortcut = button(app?.label ?: getString(R.string.ts7_shortcut_slot, slot + 1)) {
                if (saved != null) launch(saved) else chooseApp(slot = slot)
            }
            shortcut.setOnLongClickListener { chooseApp(slot = slot); true }
            quickApps.addView(shortcut, LinearLayout.LayoutParams(-1, dp(CONTROL_HEIGHT)).apply { bottomMargin = dp(GAP) })
        }
        railButton(R.string.ts7_original_home) { openOriginalHome() }
        railButton(R.string.ts7_system_settings) { open(Intent(Settings.ACTION_SETTINGS)) }
        railButton(R.string.ts7_settings) { showSettings() }
    }

    private fun railButton(text: Int, parent: LinearLayout = rail, action: () -> Unit) {
        parent.addView(button(text, action), LinearLayout.LayoutParams(-1, dp(CONTROL_HEIGHT)).apply { bottomMargin = dp(GAP) })
    }

    private fun render(next: SessionState) {
        state = next
        status.text = getString(Ts7SessionService.phaseLabel(next.phase))
        status.setTextColor(if (next.phase == SessionPhase.CONNECTED) ACCENT else Color.WHITE)
        local.visibility = if (next.phase == SessionPhase.CONNECTED && next.pictureAvailable) View.GONE else View.VISIBLE
        panel.setStreamSize(next.videoWidth, next.videoHeight)
        connectButton.isEnabled = service != null && next.phase !in listOf(SessionPhase.CONNECTING, SessionPhase.WAITING_PHONE, SessionPhase.CONNECTED)
        disconnectButton.isEnabled = service != null && (next.phase in listOf(SessionPhase.CONNECTING,
            SessionPhase.WAITING_PHONE, SessionPhase.CONNECTED, SessionPhase.WAITING_NETWORK) || next.retrying)
        details.text = listOfNotNull(next.message.takeIf { it != 0 }?.let(::getString),
            if (next.retrying) getString(R.string.ts7_state_retry) else null,
            if (next.phase == SessionPhase.FAILED && next.attempts >= ConnectionPolicy.MAX_ATTEMPTS) getString(R.string.ts7_retry_exhausted) else null)
            .joinToString("\n")
        if (next.phase == SessionPhase.CONNECTED && resumed) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onStart() {
        super.onStart()
        started = true
        if (!bound) bound = bindService(Intent(this, Ts7SessionService::class.java), connection, Context.BIND_AUTO_CREATE)
        service?.observe(observer)
        availableSurface?.let { service?.attachSurface(it) }
        refreshRail()
    }
    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT < 33 && languageAtCreate != AppLocale.preference(this)) {
            recreate()
            return
        }
        resumed = true
        service?.setVisible(true)
        render(state)
    }
    override fun onPause() {
        resumed = false
        service?.cancelDiPlayHandoff()
        service?.setVisible(false)
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
        settingsDialog?.dismiss()
        service?.removeObserver(observer)
        availableSurface?.let { service?.detachSurface(it) }
        if (bound) unbindService(connection)
        service = null
        super.onDestroy()
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // A HOME intent only reveals the existing view; it does not request another connection.
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        panel.requestLayout()
    }
    @Deprecated("HOME keeps the launcher task alive")
    override fun onBackPressed() { settingsDialog?.dismiss() }

    override fun surfaceCreated(holder: SurfaceHolder) {
        availableSurface = holder.surface
        if (started) availableSurface?.let { service?.attachSurface(it) }
    }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        availableSurface?.let { service?.detachSurface(it) }
        availableSurface = null
    }

    private fun showApps() {
        val apps = AppCatalog.list(this)
        val grid = GridLayout(this).apply { columnCount = 3; setPadding(dp(GAP), dp(GAP), dp(GAP), dp(GAP)) }
        val scroll = ScrollView(this).apply { addView(grid) }
        val dialog = AlertDialog.Builder(this).setTitle(R.string.ts7_apps).setMessage(R.string.ts7_shortcut_hint)
            .setView(scroll).setNegativeButton(R.string.ts7_close, null).create()
        apps.forEach { app ->
            val item = button(app.label) { dialog.dismiss(); launch(app.component) }
            item.setOnLongClickListener {
                AlertDialog.Builder(this).setTitle(R.string.ts7_choose_shortcut)
                    .setItems(arrayOf(getString(R.string.ts7_shortcut_slot, 1), getString(R.string.ts7_shortcut_slot, 2))) { _, slot ->
                        store.saveShortcut(slot, app.component.flattenToString()); refreshRail()
                    }.show()
                true
            }
            grid.addView(item, GridLayout.LayoutParams().apply {
                width = 0; height = dp(72); columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(4), dp(4), dp(4), dp(4))
            })
        }
        if (apps.isEmpty()) grid.addView(label(getString(R.string.ts7_no_apps), 18f))
        dialog.show()
    }

    private fun chooseApp(home: Boolean = false, slot: Int? = null) {
        val apps = AppCatalog.list(this, home)
        AlertDialog.Builder(this).setTitle(if (home) R.string.ts7_choose_home else R.string.ts7_choose_shortcut)
            .setItems(apps.map { it.label }.toTypedArray()) { _, index ->
                val component = apps[index].component.flattenToString()
                if (home) store.originalHome = component else slot?.let { store.saveShortcut(it, component) }
                refreshRail()
            }.setNegativeButton(R.string.ts7_cancel, null).show()
    }
    private fun openOriginalHome() {
        val saved = store.originalHome?.let(ComponentName::unflattenFromString)
        if (saved == null || AppCatalog.list(this, true).none { it.component == saved }) chooseApp(home = true)
        else launch(saved)
    }
    private fun launch(component: ComponentName) {
        if (component.packageName == packageName) return
        service?.releaseTouches()
        open(AppCatalog.intent(component))
    }
    private fun open(intent: Intent) {
        try { startActivity(intent) }
        catch (_: ActivityNotFoundException) { toast(R.string.ts7_app_unavailable) }
        catch (_: SecurityException) { toast(R.string.ts7_app_unavailable) }
    }

    private fun showSettings() {
        if (settingsDialog != null) return
        if (store.diPlayMode) { showDesktopSettings(); return }
        editing = true
        service?.beginEditing()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(GAP * 2), dp(GAP), dp(GAP * 2), dp(GAP))
        }
        fun section(title: Int) { content.addView(label(getString(title), 21f, ACCENT).apply { setPadding(0, dp(GAP * 2), 0, dp(GAP)) }) }
        fun note(text: Int) { content.addView(label(getString(text), 16f, MUTED)) }
        fun row(title: Int, action: () -> Unit) { content.addView(button(title, action), LinearLayout.LayoutParams(-1, dp(CONTROL_HEIGHT))) }
        fun toggle(title: Int, value: Boolean): Switch = Switch(this).apply {
            text = getString(title); textSize = 17f; isChecked = value; minHeight = dp(CONTROL_HEIGHT)
            content.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
        row(R.string.ts7_diplay_settings) { openDiPlay("settings") }
        section(R.string.ts7_connection)
        note(R.string.ts7_connection_help)
        val ssid = EditText(this).apply { hint = getString(R.string.ts7_ssid); setSingleLine(); setText(store.ssid) }
        val password = EditText(this).apply {
            hint = getString(R.string.ts7_password); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(); setText(store.password); setSaveEnabled(false)
        }
        content.addView(ssid); content.addView(password)
        var phone = store.phone
        val phoneButton = button(if (phone == null) R.string.ts7_choose_phone else R.string.ts7_phone_selected) {}
        phoneButton.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestConnectionPermissions(); return@setOnClickListener
            }
            val paired = getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter?.bondedDevices.orEmpty()
                .sortedBy { it.name ?: it.address }
            if (paired.isEmpty()) toast(R.string.ts7_no_phones)
            else AlertDialog.Builder(this).setTitle(R.string.ts7_choose_phone)
                .setItems(paired.map { it.name ?: getString(R.string.ts7_choose_phone) }.toTypedArray()) { _, which ->
                    phone = paired[which].address
                    phoneButton.setText(R.string.ts7_phone_selected)
                }.show()
        }
        content.addView(phoneButton, LinearLayout.LayoutParams(-1, dp(CONTROL_HEIGHT)))
        row(R.string.ts7_wifi_settings) { open(Intent(Settings.ACTION_WIFI_SETTINGS)) }
        row(R.string.ts7_bluetooth_settings) { open(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
        note(R.string.ts7_permission_help)
        row(R.string.ts7_permissions) { requestConnectionPermissions() }
        section(R.string.ts7_display); note(R.string.ts7_display_info)
        section(R.string.ts7_audio)
        val audio = toggle(R.string.ts7_audio_focus, store.audioFocus)
        val microphone = toggle(R.string.ts7_microphone, store.microphone)
        note(R.string.ts7_audio_help)
        section(R.string.ts7_startup)
        val auto = toggle(R.string.ts7_auto_connect, store.autoConnect)
        val wake = toggle(R.string.ts7_resume_connect, store.resumeConnect)
        note(R.string.ts7_resume_help)
        row(R.string.ts7_choose_home) { chooseApp(home = true) }
        row(R.string.ts7_default_home) { open(Intent(Settings.ACTION_HOME_SETTINGS)) }
        section(R.string.ts7_diagnostics)
        row(R.string.ts7_export_report) { exportReport() }
        val dialog = AlertDialog.Builder(this).setTitle(R.string.ts7_settings)
            .setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton(R.string.ts7_save, null).setNeutralButton(R.string.ts7_apply_reconnect, null)
            .setNegativeButton(R.string.ts7_cancel, null).create()
        settingsDialog = dialog
        dialog.setOnDismissListener {
            settingsDialog = null
            editing = false
            service?.endEditing()
        }
        dialog.setOnShowListener {
            fun save(reconnect: Boolean) {
                val connectionRequired = reconnect || auto.isChecked
                if (connectionRequired && ManualHotspotValidation.error(ssid.text.toString(), password.text.toString()) != null) {
                    ssid.error = getString(R.string.ts7_invalid_network); return
                }
                if (connectionRequired && phone == null) { toast(R.string.ts7_choose_phone_first); return }
                val previousAuto = store.autoConnect
                store.ssid = ssid.text.toString(); store.password = password.text.toString(); store.phone = phone
                store.audioFocus = audio.isChecked; store.microphone = microphone.isChecked
                store.autoConnect = auto.isChecked; store.resumeConnect = wake.isChecked
                service?.settingsSaved(previousAuto)
                dialog.dismiss()
                if (reconnect) service?.reconnect()
                else if (!previousAuto && store.autoConnect) service?.setVisible(resumed)
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { save(false) }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { save(true) }
        }
        dialog.show()
    }

    private fun openDiPlay(page: String = "home") {
        val session = service ?: return
        settingsDialog?.dismiss()
        session.prepareDiPlay { ready ->
            if (!resumed || isFinishing || isDestroyed) return@prepareDiPlay
            if (!ready) { toast(R.string.ts7_failure_cleanup); return@prepareDiPlay }
            DiPlayLauncherIntegration.importDesktopConnection(this, store.ssid, store.password,
                store.phone, store.audioFocus)
            open(DiPlayLauncherIntegration.pageIntent(this, page))
        }
    }

    private fun showDesktopSettings() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(GAP * 2), dp(GAP), dp(GAP * 2), dp(GAP))
        }
        content.addView(label(getString(R.string.ts7_diplay_settings_help), 16f, MUTED))
        fun row(title: Int, action: () -> Unit) {
            content.addView(button(title, action), LinearLayout.LayoutParams(-1, dp(CONTROL_HEIGHT)))
        }
        row(R.string.ts7_diplay_settings) { openDiPlay("settings") }
        row(R.string.ts7_choose_home) { chooseApp(home = true) }
        row(R.string.ts7_default_home) { open(Intent(Settings.ACTION_HOME_SETTINGS)) }
        row(R.string.ts7_export_report) { exportReport() }
        val dialog = AlertDialog.Builder(this).setTitle(R.string.ts7_settings)
            .setView(ScrollView(this).apply { addView(content) })
            .setNegativeButton(R.string.ts7_close, null).create()
        settingsDialog = dialog
        dialog.setOnDismissListener { settingsDialog = null }
        dialog.show()
    }

    private fun requestConnectionPermissions() {
        val permissions = LauncherStore.missingPermissions(this, true).toMutableList()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (permissions.isNotEmpty()) requestPermissions(permissions.toTypedArray(), PERMISSIONS_REQUEST)
        else toast(R.string.ts7_permissions_ready)
    }

    private fun exportReport() {
        pendingReport = service?.report() ?: SessionDiagnostics(this).report(state)
        try {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/plain").putExtra(Intent.EXTRA_TITLE, "ts7-diagnostics.txt"), REPORT_REQUEST)
        } catch (_: ActivityNotFoundException) { toast(R.string.ts7_report_failed) }
    }

    @Deprecated("Platform Activity result API is supported from API 25")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REPORT_REQUEST || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val report = pendingReport ?: service?.report() ?: return
        val saved = runCatching {
            checkNotNull(contentResolver.openOutputStream(uri, "wt")).bufferedWriter().use { it.write(report) }
        }.isSuccess
        pendingReport = null
        toast(if (saved) R.string.ts7_report_saved else R.string.ts7_report_failed)
    }

    private fun label(text: String, size: Float, color: Int = Color.WHITE) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color); typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private fun button(text: Int, action: () -> Unit) = button(getString(text), action)
    private fun button(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text; isAllCaps = false; textSize = 15f; minHeight = dp(CONTROL_HEIGHT)
        setPadding(dp(8), dp(4), dp(8), dp(4))
        setTextColor(android.content.res.ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(MUTED, Color.WHITE)))
        backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(35, 51, 65))
        setOnClickListener { action() }
    }
    private fun toast(text: Int) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val GAP = 12
        private const val CONTROL_HEIGHT = 52
        private const val RAIL_WIDTH = 120
        private const val PERMISSIONS_REQUEST = 10
        private const val REPORT_REQUEST = 11
        private val BACKGROUND = Color.rgb(16, 23, 31)
        private val ACCENT = Color.rgb(103, 232, 193)
        private val MUTED = Color.rgb(161, 179, 193)
    }
}
