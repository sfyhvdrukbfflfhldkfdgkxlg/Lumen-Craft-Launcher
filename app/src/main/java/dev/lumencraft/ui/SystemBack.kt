package dev.lumencraft.ui

import android.app.Activity
import android.os.Build
import android.window.BackEvent
import android.window.OnBackAnimationCallback
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher

/** Loaded only on API 33 and newer. Leaving the root page stays under system control. */
object SystemBack {
    fun register(activity: Activity, deck: () -> PageDeck, back: () -> Unit): () -> Unit {
        val callback = if (Build.VERSION.SDK_INT >= 34) animated(deck, back)
            else OnBackInvokedCallback { back() }
        activity.onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT,callback)
        return { activity.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback) }
    }

    private fun animated(deck: () -> PageDeck, back: () -> Unit): OnBackInvokedCallback =
        object : OnBackAnimationCallback {
            private var motion = false
            override fun onBackStarted(event: BackEvent) {
                motion = Appearance.motion(deck().context)
                if(motion) deck().previewSystemBack(0f)
            }
            override fun onBackProgressed(event: BackEvent) {
                if(motion) deck().previewSystemBack(event.progress * (if(event.swipeEdge == BackEvent.EDGE_RIGHT) -1f else 1f))
            }
            override fun onBackCancelled() = deck().cancelSystemBack(motion)
            override fun onBackInvoked() = back()
        }
}
