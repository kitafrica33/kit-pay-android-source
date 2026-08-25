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
     * Whether the reveal has been pulled far enough that releasing now would open the camera.
     * This drives the peek panel's label, so it deliberately says nothing about *when* the camera
     * actually opens — see [shouldOpenOnRelease] for that.
     */
    fun shouldOpen(revealPx: Float, thresholdPx: Float): Boolean =
        thresholdPx > 0f && revealPx >= thresholdPx

    /**
     * The camera may only open once the gesture has genuinely ended. Scroll containers dispatch
     * fling callbacks that can arrive while a finger is still down, so crossing the threshold is
     * not on its own permission to open: the panel promises "release to open the camera", and
     * opening mid-drag breaks that promise and steals a scroll the user was still making.
     */
    fun shouldOpenOnRelease(
        revealPx: Float,
        thresholdPx: Float,
        pointerDown: Boolean,
    ): Boolean = !pointerDown && shouldOpen(revealPx, thresholdPx)

    data class CollapseResult(val revealPx: Float, val consumedY: Float)
}
