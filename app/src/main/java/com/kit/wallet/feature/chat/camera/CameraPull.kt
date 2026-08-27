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
