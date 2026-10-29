package com.diplay.ts7

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.Window
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import com.shilapi.xcertplay.DiPlayLauncherIntegration

/** Five independently skippable steps. System readback and preferences stay separate from testing. */
internal class DeviceSetupWizard(private val activity: LauncherActivity, private val finished: () -> Unit) {
    private val store = LauncherStore(activity)
    private var step = store.guideStep
    private var facts: List<DeviceFact>? = null
    private var draftAuto = DiPlayLauncherIntegration.autoConnect(activity)
    private var draftWake = store.resumeConnect
    private var dialog: Dialog? = null
    val showing get() = dialog?.isShowing == true

    fun show(startAt: Int? = null) {
        step = startAt ?: store.guideStep
        if (step == 5 && startAt == null) step = (0..4).firstOrNull { store.stepResult(it) != "done" } ?: 0
        store.guideSeen = true
        dialog = Dialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setOnDismissListener {
                store.guideStep = step
                // Leaving optional setup must not itself start a receiver behind the user's next action.
                store.autoConsumed = true
                dialog = null; finished()
            }
            show()
        }
        refresh()
    }

    fun refresh() {
        val target = dialog ?: return
        val ui = LauncherUi(activity)
        val panel = ui.column(LauncherUi.MARGIN).apply { tag = TAG_WIZARD; background = ui.shape(ui.surface) }
        val top = ui.row()
        top.addView(ui.text(if (step < 5) "${step + 1} / 5" else activity.getString(R.string.ts7_guide_finish), 14f, ui.secondary),
            LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(ui.button(activity.getString(R.string.ts7_later)) { leave() })
        panel.addView(top)
        val body = ui.column()
        val title = when (step) {
            0 -> R.string.ts7_detect_device
            1 -> R.string.ts7_guide_restore
            2 -> R.string.ts7_default_home
            3 -> R.string.ts7_startup
            4 -> R.string.ts7_connection
            else -> R.string.ts7_guide_finish
        }
        body.addView(ui.text(activity.getString(title), 20f, bold = true)); body.addView(ui.gap())
        var primary: Int
        var action: () -> Unit
        fun fact(id: Int, value: String) { body.addView(ui.rowLabel(activity.getString(id), value)) }
        when (step) {
            0 -> {
                val current = facts
                if (current == null) {
                    for (id in listOf(R.string.ts7_device_system, R.string.ts7_device_screen, R.string.ts7_system_theme, R.string.ts7_startup))
                        fact(id, activity.getString(R.string.ts7_not_checked))
                    body.addView(ui.text(activity.getString(R.string.ts7_detection_read_only), 14f, ui.secondary))
                    primary = R.string.ts7_detect_start
                    action = { facts = DeviceProfile.collect(activity); store.saveStepResult(0, "done"); refresh() }
                } else {
                    current.forEach { fact(it.label, it.value) }
                    primary = R.string.ts7_continue
                    action = { advance(done = true) }
                }
            }
            1 -> {
                val saved = store.originalHome?.let(android.content.ComponentName::unflattenFromString)
                val app = AppCatalog.list(activity, true).firstOrNull { it.component == saved }
                fact(R.string.ts7_original_home, app?.label ?: activity.getString(R.string.ts7_not_selected))
                primary = if (app == null) R.string.ts7_choose_home else R.string.ts7_continue
                action = if (app == null) ({ LauncherNavigation.chooseOriginal(activity, changed = ::refresh) }) else ({ advance(true) })
            }
            2 -> {
                val selected = DeviceProfile.isDefaultHome(activity)
                fact(R.string.ts7_default_home, activity.getString(if (selected) R.string.ts7_home_selected else R.string.ts7_home_not_selected))
                body.addView(ui.text(activity.getString(R.string.ts7_home_readback), 14f, ui.secondary))
                primary = if (selected) R.string.ts7_continue else R.string.ts7_go_system_settings
                action = if (selected) ({ advance(true) }) else ({ LauncherNavigation.homeSettings(activity) })
            }
            3 -> {
                body.addView(ui.rowLabel(activity.getString(R.string.ts7_boot_home), activity.getString(R.string.ts7_boot_home_controlled)) {
                    LauncherNavigation.homeSettings(activity)
                })
                fun toggle(id: Int, checked: Boolean, update: (Boolean) -> Unit) {
                    body.addView(Switch(activity).apply {
                        text = activity.getString(id); textSize = 16f; setTextColor(ui.text)
                        minHeight = ui.dp(LauncherUi.TOUCH); isChecked = checked
                        setOnCheckedChangeListener { _, value -> update(value) }
                    })
                }
                toggle(R.string.ts7_auto_connect, draftAuto) { draftAuto = it }
                toggle(R.string.ts7_resume_connect, draftWake) { draftWake = it }
                body.addView(ui.text(activity.getString(R.string.ts7_boot_unverified), 14f, ui.attention))
                body.addView(ui.text(activity.getString(R.string.ts7_wake_foreground_only), 14f, ui.secondary))
                primary = R.string.ts7_save_continue
                action = {
                    DiPlayLauncherIntegration.saveAutoConnect(activity, draftAuto)
                    store.resumeConnect = draftWake
                    advance(true)
                }
            }
            4 -> {
                fact(R.string.ts7_permissions, activity.getString(if (DeviceProfile.microphoneGranted(activity)) R.string.ts7_permission_ready_short else R.string.ts7_permission_pending))
                fact(R.string.ts7_choose_phone, activity.getString(if (DiPlayLauncherIntegration.phoneSelected(activity)) R.string.ts7_phone_selected else R.string.ts7_not_selected))
                fact(R.string.ts7_authentication, DeviceProfile.authenticationLabel(activity))
                body.addView(ui.text(activity.getString(R.string.ts7_usb_wifi_supported), 14f, ui.secondary))
                body.addView(ui.button(activity.getString(R.string.ts7_hotspot)) { activity.openHotspotControls() })
                val configured = DiPlayLauncherIntegration.authentication(activity) != DiPlayLauncherIntegration.Authentication.MISSING &&
                    DeviceProfile.microphoneGranted(activity)
                primary = if (configured) R.string.ts7_continue else R.string.ts7_connection_settings
                action = if (configured) ({ advance(true) }) else ({ LauncherNavigation.open(activity,
                    DiPlayLauncherIntegration.pageIntent(activity, "connection")) })
            }
            else -> {
                val completed = (0..4).count { store.stepResult(it) == "done" }
                val skipped = (0..4).count { store.stepResult(it) == "skipped" }
                body.addView(ui.text(activity.getString(R.string.ts7_guide_summary, completed, skipped)))
                body.addView(ui.text(activity.getString(R.string.ts7_guide_resume_help), 14f, ui.secondary))
                primary = R.string.ts7_enter_home
                action = { leave() }
            }
        }
        panel.addView(ScrollView(activity).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        val footer = ui.row().apply { gravity = Gravity.CENTER_VERTICAL }
        if (step in 1..4) footer.addView(ui.button(activity.getString(R.string.ts7_previous)) {
            step--; store.guideStep = step; resetDrafts(); refresh()
        })
        if (step < 5) footer.addView(ui.button(activity.getString(R.string.ts7_skip_step)) { advance(false) }, LinearLayout.LayoutParams(0, ui.dp(LauncherUi.TOUCH), 1f))
        footer.addView(ui.button(activity.getString(primary), true, action), LinearLayout.LayoutParams(0, ui.dp(LauncherUi.TOUCH), 1f).apply { marginStart = ui.dp(LauncherUi.GAP) })
        panel.addView(ui.gap()); panel.addView(footer)
        target.setContentView(panel)
        target.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val metrics = activity.resources.displayMetrics
            setLayout(minOf(ui.dp(800), (metrics.widthPixels * 0.9).toInt()), (metrics.heightPixels * 0.88).toInt())
        }
    }

    private fun advance(done: Boolean) {
        val verified = done || when (step) {
            1 -> store.originalHome?.let(android.content.ComponentName::unflattenFromString)?.let { selected -> AppCatalog.list(activity, true).any { it.component == selected } } == true
            2 -> DeviceProfile.isDefaultHome(activity)
            else -> store.stepResult(step) == "done"
        }
        store.saveStepResult(step, if (verified) "done" else "skipped")
        step++; store.guideStep = step; resetDrafts(); refresh()
    }
    private fun resetDrafts() {
        draftAuto = DiPlayLauncherIntegration.autoConnect(activity)
        draftWake = store.resumeConnect
    }
    fun leave() { store.guideStep = step; dialog?.dismiss(); dialog = null }
    companion object { const val TAG_WIZARD = "ts7_setup_wizard" }
}
