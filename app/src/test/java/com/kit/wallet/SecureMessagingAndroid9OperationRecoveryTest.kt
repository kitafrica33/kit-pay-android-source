package com.kit.wallet

import com.kit.wallet.data.messaging.SecureMessagingRecordKeyMissState
import com.kit.wallet.data.messaging.SecureMessagingRecordKeyOperation
import com.kit.wallet.data.messaging.observeSecureMessagingRecordKeyMiss
import com.kit.wallet.data.messaging.shouldClearSecureMessagingRecordKeyFailureEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureMessagingAndroid9OperationRecoveryTest {
    @Test
    fun repeated_encrypt_failures_survive_decrypt_success_and_reach_bounded_recovery() {
        var encryptEvidence = SecureMessagingRecordKeyMissState()
        var decryptEvidence = observeSecureMessagingRecordKeyMiss(
            previous = SecureMessagingRecordKeyMissState(),
            userUnlocked = true,
            nowEpochMillis = 500L,
        )

        fun recordSuccessfulOperation(operation: SecureMessagingRecordKeyOperation) {
            if (shouldClearSecureMessagingRecordKeyFailureEvidence(
                    failedOperation = SecureMessagingRecordKeyOperation.ENCRYPT,
                    successfulOperation = operation,
                )
            ) {
                encryptEvidence = SecureMessagingRecordKeyMissState()
            }
            if (shouldClearSecureMessagingRecordKeyFailureEvidence(
                    failedOperation = SecureMessagingRecordKeyOperation.DECRYPT,
                    successfulOperation = operation,
                )
            ) {
                decryptEvidence = SecureMessagingRecordKeyMissState()
            }
        }

        listOf(1_000L, 2_000L, 3_000L, 16_000L).forEachIndexed { index, observedAt ->
            encryptEvidence = observeSecureMessagingRecordKeyMiss(
                previous = encryptEvidence,
                userUnlocked = true,
                nowEpochMillis = observedAt,
            )

            // Reproduces activation retry: the protocol row decrypts successfully before the
            // pending-publication update reaches ENCRYPT_MODE and fails again.
            recordSuccessfulOperation(SecureMessagingRecordKeyOperation.DECRYPT)

            assertEquals(SecureMessagingRecordKeyMissState(), decryptEvidence)
            assertEquals(1_000L, encryptEvidence.firstUnlockedMissAtEpochMillis)
            if (index < 3) assertFalse(encryptEvidence.permanentlyMissing)
        }

        assertEquals(4, encryptEvidence.consecutiveUnlockedMisses)
        assertTrue(encryptEvidence.permanentlyMissing)

        recordSuccessfulOperation(SecureMessagingRecordKeyOperation.ENCRYPT)
        assertEquals(SecureMessagingRecordKeyMissState(), encryptEvidence)
    }
}
