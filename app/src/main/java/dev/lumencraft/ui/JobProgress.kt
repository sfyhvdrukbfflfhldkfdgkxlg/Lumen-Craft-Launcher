package dev.lumencraft.ui

import android.animation.ValueAnimator
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.ProgressBar
import android.widget.TextView
import dev.lumencraft.core.VanillaInstaller

/** Installer workers can finish many cached files in one frame. Only paint the latest observation. */
class JobProgress(private val bar: ProgressBar, private val info: TextView, private val motion: Boolean) {
    private data class Update(val label: String, val done: Int, val total: Int)
    private val main = Handler(Looper.getMainLooper())
    private var pending: Update? = null
    private var queued = false
    private var closed = false
    private var stage = ""
    private var total = 0
    private var animator: ValueAnimator? = null

    val files = VanillaInstaller.Progress { label, done, count -> submit(Update(label,done,count)) }
    operator fun invoke(message: String) = submit(Update(message,0,0))

    @Synchronized private fun submit(update: Update) {
        if(closed) return
        pending = update
        if(!queued) {
            queued = true
            main.post { if (!closed && bar.isAttachedToWindow) bar.postOnAnimation { paint() } }
        }
    }

    private fun paint() {
        val next = synchronized(this) {
            queued = false
            pending.also { pending = null }
        } ?: return
        if(closed) return
        val known = next.total > 0
        bar.visibility = if(known || motion) View.VISIBLE else View.INVISIBLE
        bar.isIndeterminate = !known && motion
        info.text = if(known) next.label + "    " + next.done + " / " + next.total else next.label
        animator?.cancel()
        if(known) {
            val fraction = (next.done.toLong().coerceIn(0,next.total.toLong()) * 1000 / next.total).toInt()
            val reset = stage != next.label || total != next.total
            if (reset) bar.progress = 0
            if (motion && fraction > bar.progress) {
                animator = ValueAnimator.ofInt(bar.progress,fraction).apply {
                    duration = 120
                    interpolator = LinearInterpolator()
                    addUpdateListener { bar.progress = it.animatedValue as Int }
                    start()
                }
            } else bar.progress = fraction
        }
        total = next.total
        stage = next.label
    }

    @Synchronized fun close() {
        closed = true
        pending = null
        main.removeCallbacksAndMessages(null)
        animator?.cancel()
    }
}
