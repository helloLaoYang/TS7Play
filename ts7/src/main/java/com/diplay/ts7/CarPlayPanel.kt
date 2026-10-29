package com.diplay.ts7

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.SurfaceView
import android.widget.FrameLayout

/** The Surface and its touch coordinate space always occupy the same letterboxed rectangle. */
internal class CarPlayPanel(context: Context) : FrameLayout(context) {
    val video = SurfaceView(context)
    private var streamWidth = ReceiverEngine.WIDTH
    private var streamHeight = ReceiverEngine.HEIGHT

    fun setStreamSize(width: Int, height: Int) {
        require(width > 0 && height > 0)
        if (width == streamWidth && height == streamHeight) return
        streamWidth = width
        streamHeight = height
        video.holder.setFixedSize(width, height)
        requestLayout()
    }

    init {
        setBackgroundColor(Color.BLACK)
        addView(video, LayoutParams(1, 1, Gravity.CENTER))
        video.holder.setFixedSize(ReceiverEngine.WIDTH, ReceiverEngine.HEIGHT)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val size = fit(width, height, streamWidth, streamHeight)
        video.layoutParams.width = size.first
        video.layoutParams.height = size.second
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    companion object {
        fun fit(width: Int, height: Int, streamWidth: Int = ReceiverEngine.WIDTH,
            streamHeight: Int = ReceiverEngine.HEIGHT): Pair<Int, Int> {
            val scale = minOf(width.toFloat() / streamWidth, height.toFloat() / streamHeight)
            return (streamWidth * scale).toInt().coerceAtLeast(1) to
                (streamHeight * scale).toInt().coerceAtLeast(1)
        }
    }
}
