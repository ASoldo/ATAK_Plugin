package com.walaris.airscout.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

class DualJoystickView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    interface JoystickListener {
        fun onLeftJoystickChanged(x: Float, y: Float, active: Boolean)
        fun onRightJoystickChanged(x: Float, y: Float, active: Boolean)
    }

    var listener: JoystickListener? = null

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#40FFFFFF")
        style = Paint.Style.FILL
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#C0FFFFFF")
        style = Paint.Style.FILL
    }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#80FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2f
    }

    private val leftStick = StickState()
    private val rightStick = StickState()

    private var stickRadius: Float = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        stickRadius = min(w, h) / 6f
        leftStick.centerX = width * 0.25f
        leftStick.centerY = height * 0.75f
        rightStick.centerX = width * 0.75f
        rightStick.centerY = height * 0.75f
        resetStick(leftStick)
        resetStick(rightStick)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawStick(canvas, leftStick)
        drawStick(canvas, rightStick)
    }

    private fun drawStick(canvas: Canvas, state: StickState) {
        canvas.drawCircle(state.centerX, state.centerY, stickRadius, basePaint)
        canvas.drawCircle(state.centerX, state.centerY, stickRadius, outlinePaint)
        canvas.drawCircle(state.handleX, state.handleY, stickRadius * 0.35f, handlePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                val x = event.getX(index)
                val y = event.getY(index)
                val state = if (x < width / 2f) leftStick else rightStick
                if (!state.active) {
                    assignPointer(state, event.getPointerId(index), x, y)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val pointerId = event.getPointerId(i)
                    val x = event.getX(i)
                    val y = event.getY(i)
                    when (pointerId) {
                        leftStick.pointerId -> updateStick(leftStick, x, y)
                        rightStick.pointerId -> updateStick(rightStick, x, y)
                    }
                }
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_CANCEL -> {
                val pointerId = event.getPointerId(event.actionIndex)
                when (pointerId) {
                    leftStick.pointerId -> releaseStick(leftStick)
                    rightStick.pointerId -> releaseStick(rightStick)
                }
            }
        }
        invalidate()
        return true
    }

    private fun assignPointer(state: StickState, pointerId: Int, x: Float, y: Float) {
        state.pointerId = pointerId
        state.active = true
        updateStick(state, x, y)
    }

    private fun updateStick(state: StickState, x: Float, y: Float) {
        val dx = x - state.centerX
        val dy = y - state.centerY
        val distance = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        val clampedDistance = min(distance, stickRadius)
        val angle = atan2(dy.toDouble(), dx.toDouble())
        state.handleX = state.centerX + clampedDistance * cos(angle).toFloat()
        state.handleY = state.centerY + clampedDistance * sin(angle).toFloat()
        val normalizedX = (state.handleX - state.centerX) / stickRadius
        val normalizedY = (state.handleY - state.centerY) / stickRadius
        dispatch(state, normalizedX, normalizedY, true)
    }

    private fun releaseStick(state: StickState) {
        resetStick(state)
        dispatch(state, 0f, 0f, false)
    }

    private fun resetStick(state: StickState) {
        state.pointerId = -1
        state.handleX = state.centerX
        state.handleY = state.centerY
        state.active = false
    }

    private fun dispatch(state: StickState, x: Float, y: Float, active: Boolean) {
        when (state) {
            leftStick -> listener?.onLeftJoystickChanged(x.coerceIn(-1f, 1f), y.coerceIn(-1f, 1f), active)
            rightStick -> listener?.onRightJoystickChanged(x.coerceIn(-1f, 1f), y.coerceIn(-1f, 1f), active)
        }
    }

    private class StickState {
        var pointerId: Int = -1
        var centerX: Float = 0f
        var centerY: Float = 0f
        var handleX: Float = 0f
        var handleY: Float = 0f
        var active: Boolean = false
    }
}
