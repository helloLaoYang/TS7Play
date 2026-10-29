package com.diplay.ts7

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import com.shilapi.xcertplay.DiPlayLauncherIntegration
import com.shilapi.xcertplay.host.R as DiPlayR
import kotlin.math.abs

/** An Activity-owned view, never a SYSTEM_ALERT_WINDOW overlay. */
internal class FloatingLauncher(private val activity: Activity) {
    private val store = LauncherStore(activity)
    private var ui = LauncherUi(activity)
    private val decor = requireNotNull(activity.window.decorView as? ViewGroup)
    private val content = activity.findViewById<View>(android.R.id.content)
    private val ball = FrameLayout(activity).apply {
        tag = TAG_ENTRY
        contentDescription = activity.getString(R.string.ts7_open_launchpad)
        isClickable = true; isFocusable = true
    }
    private val glyph = ImageView(activity)
    private var attached = false
    private var menu: Dialog? = null
    private var dragging = false
    private var downY = 0f
    private var downX = 0f
    private var startY = 0f
    private var upper = 0f
    private var lower = 0f
    private val layoutListener = android.view.ViewTreeObserver.OnGlobalLayoutListener { position() }

    init {
        glyph.setImageResource(DiPlayR.drawable.ic_dp_overview)
        glyph.imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        glyph.setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10))
        ball.addView(glyph, FrameLayout.LayoutParams(ui.dp(LauncherUi.FLOAT_DIAMETER), ui.dp(LauncherUi.FLOAT_DIAMETER), Gravity.CENTER))
        ball.setOnClickListener { if (menu?.isShowing == true) closeMenu() else showMenu() }
        ball.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = event.rawY; downX = event.rawX; startY = ball.y; dragging = false
                    glyph.background.alpha = 230
                }
                MotionEvent.ACTION_MOVE -> {
                    val slop = ViewConfiguration.get(activity).scaledTouchSlop
                    if (abs(event.rawY - downY) > slop || abs(event.rawX - downX) > slop) dragging = true
                    if (dragging) ball.y = (startY + event.rawY - downY).coerceIn(upper, lower)
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) store.floatingPosition = if (lower > upper) (ball.y - upper) / (lower - upper) else 0.5f
                    else ball.performClick()
                    dragging = false; idleAppearance()
                }
                MotionEvent.ACTION_CANCEL -> { dragging = false; idleAppearance(); position() }
            }
            true
        }
        refresh()
    }

    fun attach() {
        if (attached) return
        attached = true
        decor.addView(ball, ViewGroup.LayoutParams(ui.dp(LauncherUi.FLOAT_TOUCH), ui.dp(LauncherUi.FLOAT_TOUCH)))
        decor.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        ball.post { if (attached) position() }
    }

    fun refresh() {
        ui = LauncherUi(activity)
        glyph.background = ui.shape(Color.rgb(50, 52, 58), LauncherUi.FLOAT_DIAMETER)
        idleAppearance()
        if (menu?.isShowing == true) menu?.setContentView(menuContent())
        if (attached) position()
    }

    private fun idleAppearance() { glyph.background.alpha = if (ui.night) 115 else 153 }

    private fun position() {
        if (!attached || dragging) return
        val bounds = Rect()
        decor.getWindowVisibleDisplayFrame(bounds)
        val origin = IntArray(2); decor.getLocationOnScreen(origin)
        val contentOrigin = IntArray(2); content.getLocationOnScreen(contentOrigin)
        upper = maxOf(bounds.top - origin[1], contentOrigin[1] - origin[1]).toFloat()
        val bottom = minOf(bounds.bottom, contentOrigin[1] + content.height)
        lower = maxOf(upper, (bottom - origin[1] - ui.dp(LauncherUi.FLOAT_TOUCH)).toFloat())
        ball.x = (contentOrigin[0] - origin[0] + ui.dp(4)).coerceAtLeast(0).toFloat()
        ball.y = upper + (lower - upper) * store.floatingPosition
    }

    private fun showMenu() {
        DiPlayLauncherIntegration.releaseTouches()
        (activity as? LauncherActivity)?.releaseCarPlayTouches()
        val dialog = Dialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(menuContent())
            setCanceledOnTouchOutside(true)
            setOnDismissListener { menu = null }
        }
        menu = dialog
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.35f)
            val width = decor.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
            val height = decor.height.takeIf { it > 0 } ?: activity.resources.displayMetrics.heightPixels
            setLayout(minOf(ui.dp(LauncherUi.LAUNCHPAD_WIDTH), (width * 0.86).toInt()),
                minOf(ui.dp(LauncherUi.LAUNCHPAD_HEIGHT), (height * 0.82).toInt()))
        }
    }

    private fun menuContent(): View {
        val root = ui.column(LauncherUi.GAP).apply { tag = TAG_MENU; background = ui.shape(ui.surface, 24) }
        val close = ui.row().apply { gravity = Gravity.END }
        close.addView(ui.button("×") { closeMenu() }.apply { contentDescription = activity.getString(R.string.ts7_close) },
            LinearLayout.LayoutParams(ui.dp(LauncherUi.TOUCH), ui.dp(LauncherUi.TOUCH)))
        root.addView(close)
        val grid = GridLayout(activity).apply { columnCount = 4 }
        fun tile(label: String, icon: Int, action: () -> Unit, longPress: (() -> Unit)? = null) {
            val item = ui.column(6).apply {
                gravity = Gravity.CENTER
                minimumHeight = ui.dp(104)
                addView(ImageView(activity).apply {
                    setImageResource(icon)
                    imageTintList = android.content.res.ColorStateList.valueOf(ui.text)
                    setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8))
                    background = ui.shape(ui.raised, 16)
                }, LinearLayout.LayoutParams(ui.dp(56), ui.dp(56)))
                addView(ui.text(label, 14f).apply { gravity = Gravity.CENTER; maxLines = 2 })
                isClickable = true; isFocusable = true; contentDescription = label
                setOnClickListener { closeMenu(); action() }
                if (longPress != null) setOnLongClickListener { closeMenu(); longPress(); true }
            }
            grid.addView(item, GridLayout.LayoutParams().apply {
                width = 0; columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            })
        }
        tile(activity.getString(R.string.ts7_home), DiPlayR.drawable.ic_dp_home, { LauncherNavigation.page(activity, LauncherNavigation.HOME) })
        tile("CarPlay", DiPlayR.drawable.ic_dp_connection, { LauncherNavigation.page(activity, LauncherNavigation.CARPLAY) })
        tile(activity.getString(R.string.ts7_apps), DiPlayR.drawable.ic_dp_overview, { LauncherNavigation.apps(activity) })
        tile(activity.getString(R.string.ts7_settings), DiPlayR.drawable.ic_dp_controls, { LauncherNavigation.page(activity, LauncherNavigation.SETTINGS) })
        tile(activity.getString(R.string.ts7_original_home), DiPlayR.drawable.ic_dp_car, { LauncherNavigation.originalHome(activity) })
        tile(activity.getString(R.string.ts7_system_settings), DiPlayR.drawable.ic_dp_advanced, { LauncherNavigation.systemSettings(activity) })
        repeat(2) { slot ->
            val selected = store.shortcut(slot)?.let(android.content.ComponentName::unflattenFromString)
            val label = AppCatalog.list(activity).firstOrNull { it.component == selected }?.label
                ?: activity.getString(R.string.ts7_shortcut_slot, slot + 1)
            tile(label, if (slot == 0) DiPlayR.drawable.ic_dp_navigation else DiPlayR.drawable.ic_dp_audio,
                { LauncherNavigation.shortcut(activity, slot) }, { LauncherNavigation.chooseShortcut(activity, slot) })
        }
        root.addView(ScrollView(activity).apply { addView(grid) }, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    fun closeMenu() { menu?.dismiss(); menu = null }
    fun detach() {
        closeMenu()
        if (!attached) return
        attached = false
        decor.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
        decor.removeView(ball)
    }

    companion object {
        const val TAG_ENTRY = "ts7_floating_entry"
        const val TAG_MENU = "ts7_launchpad"
    }
}
