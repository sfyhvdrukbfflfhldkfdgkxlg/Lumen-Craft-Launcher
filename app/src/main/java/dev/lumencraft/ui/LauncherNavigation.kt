package dev.lumencraft.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.min

/** Single-colour glyphs share a 24-unit grid and line weight. Labels provide the spoken names. */
class NavSymbol(context: Context, val index: Int, color: Int) : View(context) {
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = 1.65f
        strokeCap = Paint.Cap.SQUARE
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path().apply {
        fun line(vararg points: Int) {
            moveTo(points[0].toFloat(), points[1].toFloat())
            for (i in 2 until points.size step 2) lineTo(points[i].toFloat(), points[i+1].toFloat())
        }
        when (index) {
            0 -> { line(3,7,12,3,21,7,12,12,3,7,3,17,12,21,21,17,21,7); line(12,12,12,21) }
            1 -> { line(4,5,8,5,8,20,4,20,4,5); line(12,5,12,20); line(16,6,20,5,22,19,18,20,16,6) }
            2 -> { addCircle(6f,12f,3f,Path.Direction.CW); addCircle(18f,6f,3f,Path.Direction.CW)
                addCircle(18f,18f,3f,Path.Direction.CW); line(9,11,15,7); line(9,13,15,17) }
            3 -> { addRect(3f,4f,21f,10f,Path.Direction.CW); addRect(3f,14f,21f,20f,Path.Direction.CW)
                line(6,7,8,7); line(6,17,8,17) }
            4 -> { line(4,7,8,7); line(13,7,20,7); line(4,17,13,17); line(18,17,20,17)
                addCircle(10.5f,7f,2.5f,Path.Direction.CW); addCircle(15.5f,17f,2.5f,Path.Direction.CW) }
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.save()
        val scale = min(width, height) / 24f
        canvas.translate((width - 24 * scale) / 2, (height - 24 * scale) / 2)
        canvas.scale(scale, scale)
        canvas.drawPath(path, ink)
        canvas.restore()
    }
}

/** Holds a single previous page beneath the current one for a bounded, interruptible back gesture. */
class PageDeck(context: Context, private val canGoBack: () -> Boolean,
               private val goBack: () -> Unit) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var velocity: VelocityTracker? = null
    private var startX = 0f
    private var startY = 0f
    private var candidate = false
    private var dragging = false
    private var animationId = 0
    private var foreground: View? = null
    private var underlay: View? = null
    private var underlayPage = -1
    var pageIndex = -1
        private set

    fun display(content: View, page: Int, backTarget: Int?, animateEntry: Boolean) {
        resetDrag()
        val old = foreground
        if (old != null) {
            ++animationId
            old.animate().cancel()
            old.translationX = 0f
            old.alpha = 1f
            if (page != pageIndex) {
                underlay?.let { removeView(it) }
                if (pageIndex == backTarget) {
                    underlay = old
                    underlayPage = pageIndex
                    old.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    old.visibility = INVISIBLE
                } else {
                    removeView(old)
                    underlay = null
                }
            } else removeView(old)
        }
        if (underlay != null && underlayPage != backTarget) {
            removeView(underlay)
            underlay = null
        }
        addView(content, LayoutParams(-1, -1))
        pageIndex = page
        foreground = content
        if (animateEntry && Appearance.motion(context)) {
            content.translationX = density * 12
            content.alpha = .65f
            content.animate().alpha(1f).translationX(0f).setDuration(145)
                .setInterpolator(DecelerateInterpolator()).start()
        }
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                resetDrag()
                candidate = canGoBack() && event.x <= 28 * density
                startX = event.x
                startY = event.y
                if (candidate) {
                    ++animationId
                    foreground?.let { it.animate().cancel(); it.translationX = 0f; it.alpha = 1f }
                    underlay?.visibility = INVISIBLE
                    velocity = VelocityTracker.obtain().also { it.addMovement(event) }
                }
            }
            MotionEvent.ACTION_MOVE -> if (candidate) {
                val dx = event.x - startX
                val dy = abs(event.y - startY)
                if (dy > slop && dy >= dx) resetDrag()
                else if (dx > slop && dx > dy * 1.4f) {
                    dragging = true
                    underlay?.visibility = VISIBLE
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> resetDrag()
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!dragging) return super.onTouchEvent(event)
        val front = foreground ?: return false
        velocity?.addMovement(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> front.translationX = (event.x - startX).coerceIn(0f, width.toFloat())
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                velocity?.computeCurrentVelocity(1000)
                val commit = event.actionMasked == MotionEvent.ACTION_UP &&
                    (front.translationX > width * .30f || (front.translationX > 28*density && (velocity?.xVelocity ?: 0f) > 650*density))
                resetDrag()
                val id = ++animationId
                val finish = Runnable {
                    if (id == animationId) {
                        front.translationX = 0f
                        underlay?.visibility = INVISIBLE
                        if (commit) goBack()
                    }
                }
                if (Appearance.motion(context)) front.animate().translationX(if(commit) width.toFloat() else 0f)
                    .setDuration(120).setInterpolator(DecelerateInterpolator()).withEndAction(finish).start()
                else finish.run()
            }
        }
        return true
    }

    fun previewSystemBack(amount: Float) {
        ++animationId
        foreground?.animate()?.cancel()
        foreground?.alpha = 1f
        underlay?.visibility = VISIBLE
        foreground?.translationX = width * amount.coerceIn(-1f,1f) * .6f
    }

    fun cancelSystemBack(animate: Boolean) {
        val id = ++animationId
        val finish = Runnable { if(id == animationId) underlay?.visibility = INVISIBLE }
        val front = foreground ?: return
        if(animate) front.animate().translationX(0f).setDuration(100)
            .setInterpolator(DecelerateInterpolator()).withEndAction(finish).start()
        else { front.translationX = 0f; finish.run() }
    }

    private fun resetDrag() {
        velocity?.recycle()
        velocity = null
        candidate = false
        dragging = false
    }

    override fun onDetachedFromWindow() {
        ++animationId
        foreground?.animate()?.cancel()
        resetDrag()
        super.onDetachedFromWindow()
    }
}
