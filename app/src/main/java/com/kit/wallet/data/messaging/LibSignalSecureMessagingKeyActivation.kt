package com.kit.wallet.data.messaging

import java.security.MessageDigest
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal class SecureMessagingKeyReconciliationException(
    val quarantineReason: SecureMessagingQuarantineReason,
    message: String,
) : IllegalStateException(message)

/**
 * Reconciles the server's current device enrollment with durable official-libsignal state.
 *
 * Provisioning commits locally before publication. The exact public bundle is retained atomically
 * with its private records, so interrupted requests re-publish identical material without growing
 * local state. Server material that is not recoverable from local state is never overwritten.
 */
@Singleton
class LibSignalSecureMessagingKeyActivation @Inject constructor(
    private val engine: LibSignalSecureMessagingCryptoEngine,
) : SecureMessagingKeyActivation {
    override suspend fun reconcile(session: RemoteSecureMessagingTransport.Session) {
        val remote = session.keyStatus()
        val local = session.localEnrollment(engine)
        var serverHasCurrentRotation = false

        if (remote.enrolled) {
            val enrolled = local ?: mismatch(
                SecureMessagingQuarantineReason.STATE_UNAVAILABLE,
                "The server has messaging keys but local private enrollment state is unavailable",
            )
            requireServerMaterialRecoverable(remote, enrolled)
            serverHasCurrentRotation =
                remote.signedPrekeyId == enrolled.currentSignedPrekeyId() &&
                    remote.pqLastResortPrekeyId == enrolled.currentPqLastResortPrekeyId()
            if (!remote.needsReplenishment && serverHasCurrentRotation) return
        }

        check(remote.replenishAt < PREKEY_UPLOAD_COUNT) {
            "The server prekey threshold cannot be replenished within the v2 upload bound"
        }
        val pending = local?.recoverablePendingPublication()
        val shouldRetryPending = pending != null && (
            !remote.enrolled || !serverHasCurrentRotation
        )
        val published = if (shouldRetryPending) {
            session.publishKeyBundle(checkNotNull(pending))
        } else {
            provisionAndPublish(session, local)
        }
        val after = session.localEnrollment(engine) ?: mismatch(
            SecureMessagingQuarantineReason.STATE_UNAVAILABLE,
            "Local enrollment disappeared after durable key provisioning",
        )
        requireServerMaterialRecoverable(published, after)
        if (published.signedPrekeyId != after.currentSignedPrekeyId()) {
            mismatch(
                SecureMessagingQuarantineReason.REPLAY_OR_ROLLBACK,
                "Published signed prekey does not match the current durable rotation",
            )
        }
        if (published.pqLastResortPrekeyId != after.currentPqLastResortPrekeyId()) {
            mismatch(
                SecureMessagingQuarantineReason.REPLAY_OR_ROLLBACK,
                "Published PQ last-resort prekey does not match the current durable rotation",
            )
        }
        check(!published.needsReplenishment) {
            "Published key inventory remains at or below the server replenishment threshold"
        }
    }

    private suspend fun provisionAndPublish(
        session: RemoteSecureMessagingTransport.Session,
        existing: LibSignalLocalEnrollment?,
    ): RemoteSecureMessagingTransport.Session.KeyStatus {
        val transaction = session.openCryptoTransaction(engine)
        return try {
            val plan = existing?.replenishmentPlan(
                ecOneTimePrekeyCount = PREKEY_UPLOAD_COUNT,
                pqOneTimePrekeyCount = PREKEY_UPLOAD_COUNT,
            ) ?: SecureMessagingProvisioningPlan(
                ecOneTimePrekeyCount = PREKEY_UPLOAD_COUNT,
                pqOneTimePrekeyCount = PREKEY_UPLOAD_COUNT,
            )
            transaction.stageProvisioning(plan)
            val committed = transaction.commit() as? SecureMessagingCommittedResult.Provisioned
                ?: error("Key provisioning returned another crypto operation")
            session.publishKeyBundle(SecureMessagingCryptoWireMapper.publication(committed))
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                runCatching { transaction.abort() }
                    .exceptionOrNull()
                    ?.let(error::addSuppressed)
            }
            throw error
        }
    }

    private fun requireServerMaterialRecoverable(
        remote: RemoteSecureMessagingTransport.Session.KeyStatus,
        local: LibSignalLocalEnrollment,
    ) {
        if (remote.registrationId != local.registrationId) {
            mismatch(
                SecureMessagingQuarantineReason.REPLAY_OR_ROLLBACK,
                "Server and local messaging registration IDs differ",
            )
        }
        if (remote.identityKeySha256 != local.identityKeySha256) {
            mismatch(
                SecureMessagingQuarantineReason.IDENTITY_CHANGED,
                "Server and local messaging identity keys differ",
            )
        }
        if (remote.signedPrekeyId !in local.signedPrekeyIds()) {
            mismatch(
                SecureMessagingQuarantineReason.REPLAY_OR_ROLLBACK,
                "The server signed prekey has no matching local private record",
            )
        }
        if (remote.pqLastResortPrekeyId !in local.pqLastResortPrekeyIds()) {
            mismatch(
                SecureMessagingQuarantineReason.REPLAY_OR_ROLLBACK,
                "The server PQ last-resort prekey has no matching local private record",
            )
        }
    }

    private fun LibSignalLocalEnrollment.currentSignedPrekeyId(): Int =
        signedPrekeyIds().maxOrNull() ?: mismatch(
            SecureMessagingQuarantineReason.STATE_UNAVAILABLE,
            "Local enrollment has no signed prekey",
        )

    private fun LibSignalLocalEnrollment.currentPqLastResortPrekeyId(): Int =
        pqLastResortPrekeyIds().maxOrNull() ?: mismatch(
            SecureMessagingQuarantineReason.STATE_UNAVAILABLE,
            "Local enrollment has no PQ last-resort prekey",
        )

    private fun LibSignalLocalEnrollment.recoverablePendingPublication(): SecureMessagingKeyPublication? {
        val pending = pendingPublication ?: return null
        if (pending.identityKeyChange) {
            mismatch(
                SecureMessagingQuarantineReason.IDENTITY_CHANGED,
                "An unconfirmed local identity-key change cannot be published automatically",
            )
        }
        if (pending.registrationId != registrationId ||
            identityDigest(pending.identityKey) != identityKeySha256 ||
            pending.signedPrekey.prekeyId != currentSignedPrekeyId() ||
            pending.pqLastResortPrekey.prekeyId != currentPqLastResortPrekeyId()
        ) {
            mismatch(
                SecureMessagingQuarantineReason.REPLAY_OR_ROLLBACK,
                "Pending key publication does not match current durable enrollment state",
            )
        }
        val ecIds = ecOneTimePrekeyIds().toSet()
        val pqIds = pqPrekeyIds().toSet()
        if (pending.oneTimePrekeys.any { it.prekeyId !in ecIds } ||
            pending.pqPrekeys.any { it.prekeyId !in pqIds }
        ) {
            // A previously published pending bundle can outlive one-time private keys consumed by
            // libsignal. Generate a fresh bounded batch instead of ever re-advertising those IDs.
            return null
        }
        return pending
    }

    private fun identityDigest(encoded: String): String {
        val bytes = Base64.getDecoder().decode(encoded)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return try {
            digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        } finally {
            bytes.fill(0)
            digest.fill(0)
        }
    }

    private fun mismatch(
        reason: SecureMessagingQuarantineReason,
        message: String,
    ): Nothing = throw SecureMessagingKeyReconciliationException(reason, message)

    private companion object {
        // The reviewed backend contract caps each upload at 100. Filling the bounded batch leaves
        // both inventories above the default inclusive threshold of 20 and amortizes PQXDH setup.
        const val PREKEY_UPLOAD_COUNT = 100
    }
}
