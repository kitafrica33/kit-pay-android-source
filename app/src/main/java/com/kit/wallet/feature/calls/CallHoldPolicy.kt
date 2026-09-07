package com.kit.wallet.feature.calls

/** A system interruption may resume itself; a user's hold must never do so. */
enum class CallHoldReason { MANUAL, WAITING, INTERRUPTION }

internal object CallHoldPolicy {
    fun mayAutoResume(reason: CallHoldReason?, audioAvailable: Boolean, switching: Boolean): Boolean =
        mayResume(reason, audioAvailable, switching, explicitlyRequested = false)

    fun mayResume(reason: CallHoldReason?, audioAvailable: Boolean, switching: Boolean,
        explicitlyRequested: Boolean): Boolean =
        reason != null && (explicitlyRequested || reason == CallHoldReason.INTERRUPTION) &&
            audioAvailable && !switching

    fun maySwitch(canHold: Boolean, hasOtherHeldCall: Boolean, switching: Boolean): Boolean =
        canHold && !hasOtherHeldCall && !switching

    fun restoreCamera(wasEnabled: Boolean, permissionGranted: Boolean, foreground: Boolean): Boolean =
        wasEnabled && permissionGranted && foreground
}
