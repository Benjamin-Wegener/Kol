package com.voiceassistant.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.appcompat.content.res.AppCompatResources
import com.voiceassistant.R
import kotlin.math.min
import kotlin.math.sin

/**
 * Brand status badge built around the Kol logo.
 *
 * The view keeps the logo visible while animating a soft halo and card lift that
 * changes with the assistant state.
 */
class OrbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class OrbState { IDLE, LISTENING, PROCESSING, THINKING, SPEAKING }

    private val logoDrawable = requireNotNull(AppCompatResources.getDrawable(context, R.drawable.kol_logo_mark))
    private var currentState = OrbState.IDLE
    private var pulsePhase = 0f
    private var ripplePhase = 0f

    private val surfacePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
    }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
        isDither = true
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val pulseAnimator = ValueAnimator.ofFloat(0f, (Math.PI * 2).toFloat()).apply {
        duration = 2400
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            pulsePhase = it.animatedValue as Float
            invalidate()
        }
    }

    private val rippleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1300
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            ripplePhase = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        pulseAnimator.start()
    }

    fun setState(state: OrbState) {
        currentState = state
        pulseAnimator.duration = when (state) {
            OrbState.IDLE -> 3400L
            OrbState.LISTENING -> 2500L
            OrbState.PROCESSING -> 1100L
            OrbState.THINKING -> 1600L
            OrbState.SPEAKING -> 900L
        }

        when (state) {
            OrbState.SPEAKING -> {
                if (!rippleAnimator.isStarted) rippleAnimator.start()
            }
            else -> rippleAnimator.cancel()
        }

        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val widthF = width.toFloat()
        val heightF = height.toFloat()
        val padding = min(widthF, heightF) * 0.08f
        val cardRect = RectF(
            padding,
            padding,
            widthF - padding,
            heightF - padding
        )

        val accentColor = when (currentState) {
            OrbState.IDLE -> Color.parseColor("#CFE1F6")
            OrbState.LISTENING -> Color.parseColor("#2D7BFF")
            OrbState.PROCESSING -> Color.parseColor("#185BE6")
            OrbState.THINKING -> Color.parseColor("#53B8FF")
            OrbState.SPEAKING -> Color.parseColor("#2D7BFF")
        }

        val haloAlpha = when (currentState) {
            OrbState.IDLE -> 35
            OrbState.LISTENING -> (85 + 20 * sin(pulsePhase.toDouble()).toFloat()).toInt()
            OrbState.PROCESSING -> (95 + 35 * sin(pulsePhase.toDouble()).toFloat()).toInt()
            OrbState.THINKING -> (75 + 25 * sin(pulsePhase.toDouble()).toFloat()).toInt()
            OrbState.SPEAKING -> (120 + 25 * sin(pulsePhase.toDouble()).toFloat()).toInt()
        }.coerceIn(24, 160)

        haloPaint.shader = LinearGradient(
            cardRect.left,
            cardRect.top,
            cardRect.right,
            cardRect.bottom,
            intArrayOf(
                Color.argb(0, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)),
                Color.argb(haloAlpha, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)),
                Color.argb(0, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        val glowInset = min(widthF, heightF) * when (currentState) {
            OrbState.IDLE -> 0.03f
            OrbState.LISTENING -> 0.045f
            OrbState.PROCESSING -> 0.05f
            OrbState.THINKING -> 0.04f
            OrbState.SPEAKING -> 0.055f
        }
        canvas.drawRoundRect(
            cardRect.left - glowInset,
            cardRect.top - glowInset,
            cardRect.right + glowInset,
            cardRect.bottom + glowInset,
            36f,
            36f,
            haloPaint
        )

        canvas.drawRoundRect(cardRect, 34f, 34f, surfacePaint)

        val lift = when (currentState) {
            OrbState.IDLE -> 0f
            OrbState.LISTENING -> 1.5f * sin(pulsePhase.toDouble()).toFloat()
            OrbState.PROCESSING -> 2.5f * sin(pulsePhase.toDouble()).toFloat()
            OrbState.THINKING -> 1.8f * sin(pulsePhase.toDouble()).toFloat()
            OrbState.SPEAKING -> 2.8f * sin(pulsePhase.toDouble()).toFloat()
        }

        val logoIntrinsicWidth = logoDrawable.intrinsicWidth.takeIf { it > 0 } ?: 512
        val logoIntrinsicHeight = logoDrawable.intrinsicHeight.takeIf { it > 0 } ?: 512
        val bitmapAspect = logoIntrinsicWidth.toFloat() / logoIntrinsicHeight.toFloat()
        val availableWidth = cardRect.width() * 0.88f
        val availableHeight = cardRect.height() * 0.72f
        val drawWidth: Float
        val drawHeight: Float
        if (availableWidth / availableHeight > bitmapAspect) {
            drawHeight = availableHeight
            drawWidth = drawHeight * bitmapAspect
        } else {
            drawWidth = availableWidth
            drawHeight = drawWidth / bitmapAspect
        }

        val bitmapLeft = cardRect.centerX() - drawWidth / 2f
        val bitmapTop = cardRect.centerY() - drawHeight / 2f + lift
        val bitmapRect = RectF(bitmapLeft, bitmapTop, bitmapLeft + drawWidth, bitmapTop + drawHeight)

        logoDrawable.setBounds(
            bitmapRect.left.toInt(),
            bitmapRect.top.toInt(),
            bitmapRect.right.toInt(),
            bitmapRect.bottom.toInt()
        )
        logoDrawable.alpha = 255
        logoDrawable.draw(canvas)

        ringPaint.color = Color.argb(
            when (currentState) {
                OrbState.IDLE -> 64
                OrbState.LISTENING -> 110
                OrbState.PROCESSING -> 120
                OrbState.THINKING -> 90
                OrbState.SPEAKING -> 130
            },
            Color.red(accentColor),
            Color.green(accentColor),
            Color.blue(accentColor)
        )
        canvas.drawRoundRect(cardRect, 34f, 34f, ringPaint)

        if (currentState == OrbState.SPEAKING) {
            for (i in 0..2) {
                val phase = (ripplePhase + i / 3f) % 1f
                val inset = 18f + phase * (min(widthF, heightF) * 0.16f)
                val alpha = ((1f - phase) * 160).toInt().coerceIn(0, 160)
                ringPaint.color = Color.argb(alpha, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
                canvas.drawRoundRect(
                    cardRect.left - inset,
                    cardRect.top - inset,
                    cardRect.right + inset,
                    cardRect.bottom + inset,
                    42f + inset * 0.3f,
                    42f + inset * 0.3f,
                    ringPaint
                )
            }
        }
    }

    override fun onDetachedFromWindow() {
        pulseAnimator.cancel()
        rippleAnimator.cancel()
        super.onDetachedFromWindow()
    }
}
