package com.kit.wallet

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kit.wallet.data.messaging.AccountMessageArchivePurgeQueue
import com.kit.wallet.data.messaging.PendingAccountMessageArchivePurge
import com.kit.wallet.data.session.SessionFence
import java.util.UUID
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountMessageArchivePurgeQueueTest {
    @Test
    fun purgeMarkerSurvivesQueueRecreationUntilExplicitCompletion() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val ownerAccountId = UUID.randomUUID().toString()
        val target = SessionFence(
            sessionId = UUID.randomUUID().toString(),
            cacheScopeId = UUID.randomUUID().toString(),
            accountId = ownerAccountId,
        )
        val pending = PendingAccountMessageArchivePurge.from(target)
        val replacementTarget = target.copy(
            sessionId = UUID.randomUUID().toString(),
            cacheScopeId = UUID.randomUUID().toString(),
        )
        val replacement = PendingAccountMessageArchivePurge.from(replacementTarget)
        val queue = AccountMessageArchivePurgeQueue(context)

        try {
            queue.enqueue(target)
            queue.enqueue(replacementTarget)

            val recreated = AccountMessageArchivePurgeQueue(context)
            assertTrue(pending in recreated.pending())
            assertTrue(replacement in recreated.pending())
            recreated.complete(pending)
            assertFalse(pending in recreated.pending())
            assertTrue(replacement in recreated.pending())
            recreated.complete(replacement)
        } finally {
            if (pending in queue.pending()) queue.complete(pending)
            if (replacement in queue.pending()) queue.complete(replacement)
        }
    }
}
