package com.kit.wallet.data.remote

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Broadcasts server-observed session lockouts. The API boundary emits whenever any call is
 * refused with HTTP 428 (`LOGIN_UNLOCK_REQUIRED` / `DEVICE_IDENTITY_REQUIRED`), so the unlock
 * gate can re-verify immediately instead of each screen dead-ending on the raw error copy.
 */
@Singleton
class SessionAssuranceSignal @Inject constructor() {
    private val mutableLocked = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val locked: SharedFlow<Unit> = mutableLocked

    fun notifyLocked() {
        mutableLocked.tryEmit(Unit)
    }
}
