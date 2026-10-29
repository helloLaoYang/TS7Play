package com.diplay.ts7

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.SurfaceView
import android.widget.FrameLayout

/** The Surface and its touch coordinate space always occupy the same letterboxed rectangle. */
internal class CarPlayPanel(context: Context) : FrameLayout(context) {
    val video = SurfaceView(context)

    init {
        setBackgroundColor(Color.BLACK)
        addView(video, LayoutParams(1, 1, Gravity.CENTER))
        video.holder.setFixedSize(ReceiverEngine.WIDTH, ReceiverEngine.HEIGHT)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val size = fit(width, height)
        video.layoutParams.width = size.first
        video.layoutParams.height = size.second
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    companion object {
        fun fit(width: Int, height: Int): Pair<Int, Int> {
            val scale = minOf(width.toFloat() / ReceiverEngine.WIDTH, height.toFloat() / ReceiverEngine.HEIGHT)
            return (ReceiverEngine.WIDTH * scale).toInt().coerceAtLeast(1) to
                (ReceiverEngine.HEIGHT * scale).toInt().coerceAtLeast(1)
        }
    }
}
