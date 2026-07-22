package com.kit.wallet.data.messaging

import com.kit.wallet.data.auth.SecureMessagingEnrollmentResetTarget
import com.kit.wallet.data.session.SecureMessagingResetProofFence
import com.kit.wallet.data.session.SessionInvalidatedException
import com.kit.wallet.data.session.SessionStore
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
 * The authenticated server enrollment cannot safely continue in this local session.
 *
 * Replacing that identity inside the same server enrollment epoch would let older ciphertext be
 * routed to an unrelated private key. Recovery must atomically reset this exact server bundle and
 * advance its enrollment epoch before any replacement keys are published.
 */
internal class SecureMessagingReauthenticationRequiredException(
    val target: SecureMessagingEnrollmentResetTarget,
    val activationFence: SecureMessagingSessionFence,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/** Local state from a server enrollment that has already ended must be erased before reprovision. */
internal class SecureMessagingLocalEnrollmentResetRequiredException(
    val activationFence: SecureMessagingSessionFence,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

internal class SecureMessagingFreshAuthenticationRequiredException(
    val activationFence: SecureMessagingSessionFence,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

internal class SecureMessagingRevalidationCancellationException(
    cause: kotlinx.coroutines.CancellationException,
) : kotlinx.coroutines.CancellationException(
    "Secure messaging lifecycle revalidation was cancelled",
) {
    init {
        initCause(cause)
    }
}

internal class SecureMessagingRevalidationRetryException(
    cause: Throwable,
) : java.io.IOException("Secure messaging lifecycle revalidation must retry", cause)

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
    private val sessions: SessionStore? = null,
) : SecureMessagingKeyActivation {
    override suspend fun reconcile(session: RemoteSecureMessagingTransport.Session) {
        val resetProof = sessions?.current()
            ?.takeIf { it.sessionId == session.binding.sessionEpoch }
            ?.messagingResetProof
        val remote = session.keyStatus()
        if (resetProof != null && !resetProof.proved) {
            throw SecureMessagingReauthenticationRequiredException(
                target = resetProof.resetTarget(),
                activationFence = session.activationFence(),
                message = "The exact pending messaging reset must finish before activation",
            )
        }
        val local = try {
            session.localEnrollment(engine)
        } catch (error: SecureMessagingLocalEnrollmentUnavailableException) {
            if (resetProof != null) {
                throw proofRecoveryException(session, remote, resetProof, error)
            }
            if (remote.enrolled) {
                throw SecureMessagingReauthenticationRequiredException(
                    target = remote.resetTarget(session),
                    activationFence = session.activationFence(),
                    "Secure messaging private enrollment is unavailable; sign in again to recover",
                    error,
                )
            }
            throw SecureMessagingFreshAuthenticationRequiredException(
                activationFence = session.activationFence(),
                message = "Fresh authentication is required for unverifiable unenrolled state",
                cause = error,
            )
        }
        if (resetProof != null) {
            requireResetProofContinuity(session, remote, local, resetProof)
        }
        var serverHasCurrentRotation = false

        if (remote.enrolled) {
            val enrolled = local ?: throw SecureMessagingReauthenticationRequiredException(
                target = remote.resetTarget(session),
                activationFence = session.activationFence(),
                "Secure messaging private enrollment is unavailable; sign in again to recover",
            )
            try {
                requireServerMaterialRecoverable(remote, enrolled)
            } catch (error: SecureMessagingKeyReconciliationException) {
                if (resetProof != null) throw error
                throw SecureMessagingFreshAuthenticationRequiredException(
                    activationFence = session.activationFence(),
                    message = "Fresh authentication is required for changed server enrollment",
                    cause = error,
                )
            }
            serverHasCurrentRotation =
                remote.signedPrekeyId == enrolled.currentSignedPrekeyId() &&
                    remote.pqLastResortPrekeyId == enrolled.currentPqLastResortPrekeyId()
            if (!remote.needsReplenishment && serverHasCurrentRotation) {
                session.confirmLocalEnrollmentPublication(engine, remote)
                clearResetProofAfterConfirmation(session, remote, resetProof)
                session.recordReconciledKeyIdentity(remote)
                return
            }
        }

        check(remote.replenishAt < PREKEY_UPLOAD_COUNT) {
            "The server prekey threshold cannot be replenished within the v2 upload bound"
        }
        val pending = local?.recoverablePendingPublication()
        if (!remote.enrolled && local != null && pending == null) {
            throw SecureMessagingFreshAuthenticationRequiredException(
                activationFence = session.activationFence(),
                message = "Fresh authentication is required before replacing unenrolled state",
            )
        }
        val shouldRetryPending = pending != null && (
            !remote.enrolled || !serverHasCurrentRotation
        )
        val published = if (shouldRetryPending) {
            session.publishKeyBundle(checkNotNull(pending))
        } else {
            provisionAndPublish(session, local)
        }
        finishReconciliation(session, published, resetProof)
    }

    private fun proofRecoveryException(
        session: RemoteSecureMessagingTransport.Session,
        remote: RemoteSecureMessagingTransport.Session.KeyStatus,
        proof: SecureMessagingResetProofFence,
        cause: Throwable,
    ): IllegalStateException {
        val resultingEpoch = checkNotNull(proof.resultingEnrollmentEpoch)
        return if (
            !remote.enrolled &&
            remote.enrollmentEpoch == resultingEpoch &&
            session.binding.serverDeviceId == proof.serverDeviceId
        ) {
            SecureMessagingLocalEnrollmentResetRequiredException(
                activationFence = session.activationFence(),
                message = "Reset enrollment has unavailable residual local state",
                cause = cause,
            )
        } else {
            SecureMessagingFreshAuthenticationRequiredException(
                activationFence = session.activationFence(),
                message = "Server enrollment changed after the fenced reset proof",
                cause = cause,
            )
        }
    }

    private fun requireResetProofContinuity(
        session: RemoteSecureMessagingTransport.Session,
        remote: RemoteSecureMessagingTransport.Session.KeyStatus,
        local: LibSignalLocalEnrollment?,
        proof: SecureMessagingResetProofFence,
    ) {
        val resultingEpoch = checkNotNull(proof.resultingEnrollmentEpoch)
        val exactResult = session.binding.serverDeviceId == proof.serverDeviceId &&
            remote.enrollmentEpoch == resultingEpoch
        if (!exactResult) {
            throw SecureMessagingFreshAuthenticationRequiredException(
                session.activationFence(),
                "Messaging enrollment advanced beyond the fenced reset proof",
            )
        }
        if (!remote.enrolled) {
            if (local != null) {
                throw SecureMessagingLocalEnrollmentResetRequiredException(
                    session.activationFence(),
                    "Residual local state must be erased after enrollment reset",
                )
            }
            return
        }
        if (local == null || runCatching {
                requireServerMaterialRecoverable(remote, local)
            }.isFailure
        ) {
            throw SecureMessagingFreshAuthenticationRequiredException(
                session.activationFence(),
                "Another enrollment replaced the fenced reset result",
            )
        }
    }

    private fun RemoteSecureMessagingTransport.Session.KeyStatus.resetTarget(
        session: RemoteSecureMessagingTransport.Session,
    ): SecureMessagingEnrollmentResetTarget {
        check(enrolled) { "Only an active server enrollment can require a reset" }
        return SecureMessagingEnrollmentResetTarget(
            serverDeviceId = session.binding.serverDeviceId,
            enrollmentEpoch = enrollmentEpoch,
            registrationId = checkNotNull(registrationId),
            identityKeySha256 = checkNotNull(identityKeySha256),
            bundleVersion = checkNotNull(bundleVersion),
        )
    }

    private fun SecureMessagingResetProofFence.resetTarget() =
        SecureMessagingEnrollmentResetTarget(
            serverDeviceId = serverDeviceId,
            enrollmentEpoch = previousEnrollmentEpoch,
            registrationId = previousRegistrationId,
            identityKeySha256 = previousIdentityKeySha256,
            bundleVersion = previousBundleVersion,
        )

    private suspend fun finishReconciliation(
        session: RemoteSecureMessagingTransport.Session,
        published: RemoteSecureMessagingTransport.Session.KeyStatus,
        resetProof: SecureMessagingResetProofFence?,
    ) {
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
        session.confirmLocalEnrollmentPublication(engine, published)
        clearResetProofAfterConfirmation(session, published, resetProof)
        session.recordReconciledKeyIdentity(published)
    }

    private suspend fun clearResetProofAfterConfirmation(
        session: RemoteSecureMessagingTransport.Session,
        status: RemoteSecureMessagingTransport.Session.KeyStatus,
        proof: SecureMessagingResetProofFence?,
    ) {
        if (proof == null) return
        val resultingEpoch = checkNotNull(proof.resultingEnrollmentEpoch)
        check(status.enrolled && status.enrollmentEpoch == resultingEpoch) {
            "Only the exact post-reset enrollment can clear its durable proof"
        }
        val store = checkNotNull(sessions)
        val expected = store.current()?.fence() ?: throw SessionInvalidatedException()
        if (expected.sessionId != session.binding.sessionEpoch ||
            !store.clearMessagingResetProofIfCurrent(expected, proof)
        ) {
            throw SessionInvalidatedException()
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
