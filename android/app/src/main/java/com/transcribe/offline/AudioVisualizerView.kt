package com.transcribe.offline

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Custom Android View that reconstructs Google Live Transcribe's
 * iconic green decibel meter bubble.
 */
class AudioVisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var normalizedLevel: Float = 0f
    private var isListening: Boolean = false

    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#757575")
    }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#4000E676")
    }

    fun setLevel(level: Float) {
        // Smooth interpolation
        this.normalizedLevel = (this.normalizedLevel * 0.7f) + (level.coerceIn(0f, 1f) * 0.3f)
        invalidate()
    }

    fun setListening(listening: Boolean) {
        this.isListening = listening
        if (!listening) {
            this.normalizedLevel = 0f
            centerPaint.color = Color.parseColor("#757575")
        } else {
            centerPaint.color = Color.parseColor("#00E676")
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val baseRadius = width.coerceAtMost(height) / 2f

        if (isListening) {
            // Check if loud/clipping
            if (normalizedLevel > 0.75f) {
                ringPaint.color = Color.parseColor("#50FFAB00")
                centerPaint.color = Color.parseColor("#FFAB00")
            } else {
                ringPaint.color = Color.parseColor("#4000E676")
                centerPaint.color = Color.parseColor("#00E676")
            }

            // Outer pulsing ring
            val outerRadius = baseRadius * (0.5f + (normalizedLevel * 0.5f))
            canvas.drawCircle(cx, cy, outerRadius, ringPaint)
        }

        // Center dot
        val centerRadius = baseRadius * 0.35f
        canvas.drawCircle(cx, cy, centerRadius, centerPaint)
    }
}
