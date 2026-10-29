package com.diplay.ts7

import android.content.Context
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.DiPlayLauncherIntegration

/** Shared native UI tokens; theme changes never replace the video Surface. */
internal class LauncherUi(val context: Context) {
    val night = DiPlayLauncherIntegration.systemNight(context)
    val background = color(if (night) "#111214" else "#F2F2F7")
    val surface = color(if (night) "#202124" else "#FFFFFF")
    val raised = color(if (night) "#2C2C2E" else "#E9E9EE")
    val text = color(if (night) "#F5F5F7" else "#1C1C1E")
    val secondary = color(if (night) "#C2C2C7" else "#5A5A60")
    val success = color(if (night) "#32D74B" else "#1D7A35")
    val attention = color(if (night) "#FFB340" else "#8C5200")
    val blue = color("#0066CC")

    fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    fun shape(fill: Int, radius: Int = CARD_RADIUS) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(radius).toFloat()
    }
    fun alert() = AlertDialog.Builder(context, if (night) android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert)
    fun text(value: String, size: Float = 16f, foreground: Int = text, bold: Boolean = false) = TextView(context).apply {
        this.text = value; textSize = size; setTextColor(foreground)
        typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
    }
    fun button(label: String, primary: Boolean = false, action: () -> Unit) = Button(context).apply {
        text = label; textSize = 16f; isAllCaps = false
        minWidth = 0; minimumWidth = 0; minHeight = dp(TOUCH); minimumHeight = dp(TOUCH)
        setPadding(dp(GAP), dp(4), dp(GAP), dp(4))
        setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(secondary, if (primary) Color.WHITE else this@LauncherUi.text)))
        background = StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), shape(raised, BUTTON_RADIUS))
            addState(intArrayOf(), RippleDrawable(ColorStateList.valueOf(if (primary) 0x33FFFFFF else 0x22000000),
                shape(if (primary) blue else raised, BUTTON_RADIUS), shape(Color.WHITE, BUTTON_RADIUS)))
        }
        setOnClickListener { action() }
    }
    fun column(padding: Int = 0) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
    }
    fun row() = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    fun card() = column(GAP).apply { background = shape(surface) }
    fun rowLabel(title: String, value: String, action: (() -> Unit)? = null): LinearLayout = row().apply {
        minimumHeight = dp(TOUCH)
        val label = column().apply {
            addView(text(title))
            if (value.isNotEmpty()) addView(text(value, 14f, secondary))
        }
        addView(label, LinearLayout.LayoutParams(0, -2, 1f))
        if (action != null) {
            addView(text("›", 24f, secondary))
            isClickable = true; isFocusable = true
            setOnClickListener { action() }
        }
    }
    fun gap() = View(context).apply { layoutParams = LinearLayout.LayoutParams(1, dp(GAP)) }

    companion object {
        const val GAP = 12
        const val MARGIN = 16
        const val TOUCH = 48
        const val FLOAT_TOUCH = 56
        const val FLOAT_DIAMETER = 44
        const val EDGE_GUTTER = DiPlayLauncherIntegration.EDGE_GUTTER_DP
        const val CARD_RADIUS = 20
        const val BUTTON_RADIUS = 12
        const val LAUNCHPAD_WIDTH = 800
        const val LAUNCHPAD_HEIGHT = 340
        private fun color(value: String) = Color.parseColor(value)
    }
}
