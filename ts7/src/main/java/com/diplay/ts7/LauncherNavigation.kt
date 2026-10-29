package com.diplay.ts7

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import com.shilapi.xcertplay.DiPlayLauncherIntegration
import com.shilapi.xcertplay.orchestration.CarPlayTransport

/** One navigation path for the desktop and the hosted DiPlay windows. */
internal object LauncherNavigation {
    const val PAGE = "ts7_page"
    const val HOME = "home"
    const val CARPLAY = "carplay"
    const val SETTINGS = "settings"
    const val GUIDE = "guide"

    fun page(activity: Activity, page: String) {
        if (activity is LauncherActivity) activity.showPage(page)
        else open(activity, Intent(activity, LauncherActivity::class.java).putExtra(PAGE, page)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    fun connect(activity: Activity, transport: CarPlayTransport) {
        val store = LauncherStore(activity)
        store.suppressed = false
        store.autoConsumed = true
        open(activity, DiPlayLauncherIntegration.connectionIntent(activity, transport))
    }

    fun diPlaySettings(activity: Activity) = open(activity, DiPlayLauncherIntegration.pageIntent(activity, "settings"))
    fun systemSettings(activity: Activity) = open(activity, Intent(Settings.ACTION_SETTINGS))
    fun homeSettings(activity: Activity) = open(activity, Intent(Settings.ACTION_HOME_SETTINGS))

    fun originalHome(activity: Activity) {
        val saved = LauncherStore(activity).originalHome?.let(ComponentName::unflattenFromString)
        if (saved != null && AppCatalog.list(activity, true).any { it.component == saved }) launchHome(activity, saved)
        else chooseOriginal(activity, openAfter = true)
    }

    fun chooseOriginal(activity: Activity, openAfter: Boolean = false, changed: () -> Unit = {}) {
        val apps = AppCatalog.list(activity, true)
        if (apps.isEmpty()) { toast(activity, R.string.ts7_no_apps); return }
        LauncherUi(activity).alert().setTitle(R.string.ts7_choose_home)
            .setItems(apps.map { it.label }.toTypedArray()) { _, index ->
                LauncherStore(activity).originalHome = apps[index].component.flattenToString()
                changed()
                if (openAfter) launchHome(activity, apps[index].component)
            }.setNegativeButton(R.string.ts7_cancel, null).show()
    }

    fun shortcut(activity: Activity, slot: Int) {
        val saved = LauncherStore(activity).shortcut(slot)?.let(ComponentName::unflattenFromString)
        if (saved == null || AppCatalog.list(activity).none { it.component == saved }) chooseShortcut(activity, slot)
        else launch(activity, saved)
    }

    fun chooseShortcut(activity: Activity, slot: Int, changed: () -> Unit = {}) {
        val apps = AppCatalog.list(activity)
        LauncherUi(activity).alert().setTitle(R.string.ts7_choose_shortcut)
            .setItems(apps.map { it.label }.toTypedArray()) { _, index ->
                LauncherStore(activity).saveShortcut(slot, apps[index].component.flattenToString())
                changed()
            }.setNegativeButton(R.string.ts7_cancel, null).show()
    }

    fun apps(activity: Activity) {
        val ui = LauncherUi(activity)
        val grid = GridLayout(activity).apply { columnCount = 4; setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8)) }
        val dialog = LauncherUi(activity).alert().setView(ScrollView(activity).apply { addView(grid) })
            .setNegativeButton(R.string.ts7_close, null).create()
        for (app in AppCatalog.list(activity)) {
            val tile = ui.column(8).apply {
                gravity = android.view.Gravity.CENTER
                minimumHeight = ui.dp(104)
                addView(ImageView(activity).apply {
                    setImageDrawable(runCatching { activity.packageManager.getActivityIcon(app.component) }.getOrNull())
                }, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)))
                addView(ui.text(app.label, 14f).apply { gravity = android.view.Gravity.CENTER; maxLines = 2 })
                isClickable = true; isFocusable = true; contentDescription = app.label
                setOnClickListener { dialog.dismiss(); launch(activity, app.component) }
                setOnLongClickListener {
                    dialog.dismiss()
                    LauncherUi(activity).alert().setTitle(R.string.ts7_choose_shortcut)
                        .setItems(arrayOf(activity.getString(R.string.ts7_shortcut_slot, 1), activity.getString(R.string.ts7_shortcut_slot, 2))) { _, slot ->
                            LauncherStore(activity).saveShortcut(slot, app.component.flattenToString())
                        }.show()
                    true
                }
            }
            grid.addView(tile, GridLayout.LayoutParams().apply { width = 0; columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f) })
        }
        dialog.show()
    }

    fun launch(activity: Activity, component: ComponentName) {
        if (component.packageName == activity.packageName) return
        DiPlayLauncherIntegration.releaseTouches()
        open(activity, AppCatalog.intent(component))
    }

    private fun launchHome(activity: Activity, component: ComponentName) {
        DiPlayLauncherIntegration.releaseTouches()
        open(activity, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setComponent(component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
    }

    fun open(activity: Activity, intent: Intent) {
        try { activity.startActivity(intent) }
        catch (_: ActivityNotFoundException) { toast(activity, R.string.ts7_app_unavailable) }
        catch (_: SecurityException) { toast(activity, R.string.ts7_app_unavailable) }
    }
    fun toast(activity: Activity, message: Int) = Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
}
