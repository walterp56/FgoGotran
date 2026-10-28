package com.fgogotran.overlay

import android.content.Context
import android.graphics.Canvas
import android.os.SystemClock
import android.view.Choreographer
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.ImageView

/** Keeps the existing ImageView/window and full-frame coordinates, animating dialogue patches only. */
internal class DialoguePlaybackView(context: Context) : ImageView(context) {
    private val controller = DialoguePlaybackController()
    private val tapGate = DialoguePlaybackTapGate()
    private var playback: DialoguePlaybackFrame? = null
    private var frameCallback: Choreographer.FrameCallback? = null
    private var paused = true
    private var visibleEnd = 0
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    fun startPlayback(frame: DialoguePlaybackFrame) {
        clearPlayback()
        playback = frame
        setImageBitmap(frame.bitmap)
        controller.start(frame.timeline, now())
        paused = false
        visibleEnd = controller.visibleEnd(now())
        schedule()
        invalidate()
    }

    fun pausePlayback() {
        controller.pause(now())
        paused = true
        unschedule()
        tapGate.cancel()
    }

    fun resumePlayback() {
        if (playback == null) return
        controller.resume(now())
        paused = false
        schedule()
        invalidate()
    }

    fun clearPlayback() {
        unschedule()
        tapGate.cancel()
        controller.cancel()
        paused = true
        // The ImageView may still reference the owned final bitmap.
        if (playback != null) setImageBitmap(null)
        playback?.close()
        playback = null
    }

    fun handlePlaybackTouch(event: MotionEvent): Boolean {
        val frame = playback ?: return false
        if (paused) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> tapGate.down(controller.generation, event.rawX, event.rawY, touchSlop,
                controller.isRevealing(now()) && frame.acceptsTap(event.rawX, event.rawY))
            MotionEvent.ACTION_MOVE -> tapGate.move(event.rawX, event.rawY)
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> tapGate.cancel()
            MotionEvent.ACTION_UP -> if (tapGate.up(controller.generation, event.rawX, event.rawY)) {
                controller.complete()
                visibleEnd = frame.timeline.text.length
                unschedule()
                invalidate()
                return true
            }
        }
        return false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        canvas.translate(paddingLeft.toFloat(), paddingTop.toFloat())
        canvas.concat(imageMatrix)
        playback?.let { frame ->
            drawable?.let { image ->
                // ImageView's matrix uses drawable (density-scaled) coordinates, while our plan
                // deliberately uses screenshot pixels. This also keeps the settings crop aligned.
                canvas.scale(image.intrinsicWidth.toFloat() / frame.bitmap.width,
                    image.intrinsicHeight.toFloat() / frame.bitmap.height)
            }
            frame.drawReveal(canvas, visibleEnd)
        }
        canvas.restore()
    }

    private fun schedule() {
        if (paused || frameCallback != null || !controller.isRevealing(now())) return
        val generation = controller.generation
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (frameCallback !== this || paused || generation != controller.generation) return
                val time = now()
                visibleEnd = controller.visibleEnd(time)
                invalidate()
                if (controller.isRevealing(time)) Choreographer.getInstance().postFrameCallback(this)
                else frameCallback = null
            }
        }
        frameCallback = callback
        Choreographer.getInstance().postFrameCallback(callback)
    }

    private fun unschedule() {
        frameCallback?.let { Choreographer.getInstance().removeFrameCallback(it) }
        frameCallback = null
    }

    override fun onDetachedFromWindow() {
        pausePlayback()
        super.onDetachedFromWindow()
    }

    private fun now() = SystemClock.elapsedRealtimeNanos()
}
