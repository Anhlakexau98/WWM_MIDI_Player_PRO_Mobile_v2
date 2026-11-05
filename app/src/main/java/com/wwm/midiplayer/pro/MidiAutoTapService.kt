package com.wwm.midiplayer.pro

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

class MidiAutoTapService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: MidiAutoTapService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) instance = null
    }

    fun performChordTap120Hz(points: List<Pair<Float, Float>>, durationMs: Long = 38L) {
        if (points.isEmpty()) return
        val builder = GestureDescription.Builder()
        val safeDuration = durationMs.coerceIn(28L, 2500L)

        points.take(5).forEach { (x, y) ->
            val path = Path().apply { moveTo(x, y) }
            val stroke = GestureDescription.StrokeDescription(path, 0L, safeDuration)
            builder.addStroke(stroke)
        }

        dispatchGesture(builder.build(), null, null)
    }
}
