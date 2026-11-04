package com.fgogotran.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
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
    private var choiceExit: ChoiceExitFrame? = null
    private var choiceExitStartedAt = 0L
    private var choiceExitCompletion: ((Boolean) -> Unit)? = null
    private var choiceFrameCallback: Choreographer.FrameCallback? = null
    private var choiceWake: Runnable? = null
    private var retiredChoiceBounds: List<Rect> = emptyList()
    private var choiceSourceWidth = 0
    private var choiceSourceHeight = 0
    private var baseHiddenForChoiceExit = false
    private val choiceClip = RectF()

    fun startChoiceExit(frame: ChoiceExitFrame, startedAtMs: Long, onComplete: (Boolean) -> Unit) {
        resetChoiceExit()
        choiceExit = frame
        retiredChoiceBounds = frame.bounds.map(::Rect)
        choiceSourceWidth = frame.width
        choiceSourceHeight = frame.height
        choiceExitStartedAt = startedAtMs
        choiceExitCompletion = onComplete
        advanceChoiceExit()
    }

    fun hasChoiceExit(): Boolean = choiceExit != null
    fun hasRetiredChoices(): Boolean = retiredChoiceBounds.isNotEmpty()
    fun isBaseHiddenForChoiceExit(): Boolean = baseHiddenForChoiceExit

    /** Keeps only choice patches visible while other render kinds follow their original hide timing. */
    fun hideBaseDuringChoiceExit() {
        if (choiceExit == null) return
        baseHiddenForChoiceExit = true
        invalidate()
    }

    fun restoreBaseContent() {
        baseHiddenForChoiceExit = false
        invalidate()
    }

    fun cancelChoiceExit() = finishChoiceExit(false)

    /** New render only. Capture restoration deliberately retains the retired-choice mask. */
    fun resetChoiceExit() {
        finishChoiceExit(false)
        retiredChoiceBounds = emptyList()
        baseHiddenForChoiceExit = false
        invalidate()
    }

    private fun finishChoiceExit(completed: Boolean) {
        choiceWake?.let(::removeCallbacks)
        choiceWake = null
        choiceFrameCallback?.let { Choreographer.getInstance().removeFrameCallback(it) }
        choiceFrameCallback = null
        val frame = choiceExit
        choiceExit = null
        val callback = choiceExitCompletion
        choiceExitCompletion = null
        frame?.close()
        callback?.invoke(completed)
        invalidate()
    }

    private fun advanceChoiceExit() {
        val frame = choiceExit ?: return
        val elapsed = (SystemClock.elapsedRealtime() - choiceExitStartedAt).coerceAtLeast(0L)
        val wait = ChoiceExitController.nextUpdateDelayMs(elapsed, frame.hasUnselected, frame.hasSelectedEffect)
        if (wait == null) {
            finishChoiceExit(true)
            return
        }
        invalidate()
        if (wait > 0L) {
            val wake = object : Runnable {
                override fun run() {
                    if (choiceWake !== this) return
                    choiceWake = null
                    advanceChoiceExit()
                }
            }
            choiceWake = wake
            postDelayed(wake, wait)
        } else {
            val callback = object : Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    if (choiceFrameCallback !== this) return
                    choiceFrameCallback = null
                    advanceChoiceExit()
                }
            }
            choiceFrameCallback = callback
            Choreographer.getInstance().postFrameCallback(callback)
        }
    }

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
        if (!baseHiddenForChoiceExit) {
            canvas.save()
            for (bounds in retiredChoiceBounds) {
                choiceClip.set(bounds)
                drawable?.let { image ->
                    // FIT_XY stretches the drawable bounds directly and reports an identity matrix.
                    // Other scale types use intrinsic bounds plus imageMatrix. Both use this mapping.
                    choiceClip.left *= image.bounds.width().toFloat() / choiceSourceWidth
                    choiceClip.right *= image.bounds.width().toFloat() / choiceSourceWidth
                    choiceClip.top *= image.bounds.height().toFloat() / choiceSourceHeight
                    choiceClip.bottom *= image.bounds.height().toFloat() / choiceSourceHeight
                    choiceClip.offset(image.bounds.left.toFloat(), image.bounds.top.toFloat())
                }
                imageMatrix.mapRect(choiceClip)
                choiceClip.offset(paddingLeft.toFloat(), paddingTop.toFloat())
                canvas.clipOutRect(choiceClip)
            }
            drawBaseContent(canvas)
            canvas.restore()
        }
        choiceExit?.let { frame ->
            canvas.save()
            canvas.translate(paddingLeft.toFloat(), paddingTop.toFloat())
            canvas.concat(imageMatrix)
            drawable?.let { image ->
                canvas.translate(image.bounds.left.toFloat(), image.bounds.top.toFloat())
                canvas.scale(image.bounds.width().toFloat() / frame.width,
                    image.bounds.height().toFloat() / frame.height)
            }
            frame.draw(canvas, (SystemClock.elapsedRealtime() - choiceExitStartedAt).coerceAtLeast(0L))
            canvas.restore()
        }
    }

    private fun drawBaseContent(canvas: Canvas) {
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
        cancelChoiceExit()
        pausePlayback()
        super.onDetachedFromWindow()
    }

    private fun now() = SystemClock.elapsedRealtimeNanos()
}
