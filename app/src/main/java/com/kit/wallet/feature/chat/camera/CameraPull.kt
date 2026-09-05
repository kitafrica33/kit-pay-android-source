package com.kit.wallet.feature.chat.camera

/**
 * Pure math for the pull-beyond-latest camera gesture: dragging up past the newest message
 * accumulates a reveal, dragging back down pays it off before the list scrolls, and releasing
 * past the threshold opens the camera (the TikTok/Instagram feel).
 */
internal object CameraPull {

    /**
     * Grows the reveal from scroll the list left unconsumed at its bottom edge. Upward drags
     * carry a negative [availableY]; anything else leaves the reveal unchanged.
     */
    fun pull(revealPx: Float, availableY: Float, maxPx: Float): Float {
        if (availableY >= 0f || maxPx <= 0f) return revealPx
        return (revealPx - availableY).coerceIn(0f, maxPx)
    }

    /**
     * Collapses an open reveal before the list is allowed to scroll down again. Returns the new
     * reveal and the scroll the gesture consumed doing it.
     */
    fun collapse(revealPx: Float, availableY: Float): CollapseResult {
        if (availableY <= 0f || revealPx <= 0f) return CollapseResult(revealPx, 0f)
        val consumed = availableY.coerceAtMost(revealPx)
        return CollapseResult(revealPx - consumed, consumed)
    }

    /**
     * Whether the reveal has been pulled far enough to open the camera.
     *
     * Answers two questions at once, which is safe because only one of them is ever asked while a
     * finger is down: it labels the peek panel during the drag, and it decides the camera at the
     * end of it. "Release to open the camera" and what release actually does can therefore never
     * disagree. The screen asks the second question from the scroll container's end-of-drag
     * callback, so crossing the threshold mid-pull cannot open anything on its own.
     */
    fun shouldOpen(revealPx: Float, thresholdPx: Float): Boolean =
        thresholdPx > 0f && revealPx >= thresholdPx

    data class CollapseResult(val revealPx: Float, val consumedY: Float)
}

/** One real finger gesture; cancellation, layout changes and release consume its eligibility. */
internal class CameraPullGesture(private val maximumPx: Float, private val thresholdPx: Float) {
    var revealPx: Float = 0f
        private set
    var active: Boolean = false
        private set
    private var hapticSent = false

    val pastThreshold: Boolean get() = active && CameraPull.shouldOpen(revealPx, thresholdPx)

    fun begin(atBottom: Boolean, enabled: Boolean) {
        cancel()
        active = atBottom && enabled
        hapticSent = false
    }

    fun pull(availableY: Float, userInput: Boolean, atBottom: Boolean): Float {
        if (!active || !userInput || !availableY.isFinite()) return 0f
        if (!atBottom) {
            cancel()
            return 0f
        }
        val previous = revealPx
        revealPx = CameraPull.pull(revealPx, availableY, maximumPx)
        return previous - revealPx
    }

    fun collapse(availableY: Float): Float {
        if (!active || !availableY.isFinite()) return 0f
        val result = CameraPull.collapse(revealPx, availableY)
        revealPx = result.revealPx
        return result.consumedY
    }

    fun takeThresholdHaptic(): Boolean {
        if (!pastThreshold || hapticSent) return false
        hapticSent = true
        return true
    }

    fun release(completed: Boolean): Boolean {
        val open = completed && pastThreshold
        cancel()
        return open
    }

    fun cancel() {
        active = false
        revealPx = 0f
    }
}
