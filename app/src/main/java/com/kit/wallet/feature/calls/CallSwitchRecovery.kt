package com.kit.wallet.feature.calls

import com.kit.wallet.data.repository.CallConnection
import com.kit.wallet.data.repository.CallRepository
import com.kit.wallet.data.session.SessionFence

/** Network compensation deliberately precedes any restoration of local media. */
internal object CallSwitchRecovery {
    suspend fun cancelUncertainAnswer(calls: CallRepository, originalId: String, waitingId: String, owner: SessionFence,
        onWaitingCancelled: () -> Unit = {}): CallConnection {
        // Resume alone cannot fence an accept which has not reached the server yet. End also
        // tombstones an invited participant, so neither arrival order can revive this answer.
        calls.end(waitingId, "cancelled", owner)
        onWaitingCancelled()
        val original = calls.status(originalId, owner)
        check(!original.terminal) { "The previous call has ended" }
        return calls.resume(originalId, requireNotNull(original.holdRevision), expectedOwner = owner)
    }

    suspend fun restoreUncertainSwap(calls: CallRepository, originalId: String, otherId: String, owner: SessionFence): CallConnection {
        val original = calls.status(originalId, owner)
        val other = calls.status(otherId, owner)
        check(!original.terminal) { "The previous call has ended" }
        // Both revisions fence reordered requests. Same-state resume consumes a revision on
        // the server too; an older delayed swap can no longer hold this restored call.
        return calls.resume(originalId, requireNotNull(original.holdRevision),
            otherId.takeUnless { other.terminal }, other.holdRevision.takeUnless { other.terminal }, owner)
    }
}
