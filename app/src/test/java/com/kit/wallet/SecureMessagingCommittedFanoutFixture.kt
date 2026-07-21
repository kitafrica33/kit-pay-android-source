package com.kit.wallet

import com.kit.wallet.data.messaging.FailClosedSecureMessagingCryptoTransaction
import com.kit.wallet.data.messaging.SecureMessagingActivationCapability
import com.kit.wallet.data.messaging.SecureMessagingCommittedResult
import com.kit.wallet.data.messaging.SecureMessagingCompanionStateIntent
import com.kit.wallet.data.messaging.SecureMessagingCryptoAddress
import com.kit.wallet.data.messaging.SecureMessagingCryptoOperation
import com.kit.wallet.data.messaging.SecureMessagingDecryptionRequest
import com.kit.wallet.data.messaging.SecureMessagingEncryptionPlan
import com.kit.wallet.data.messaging.SecureMessagingEncryptionRequest
import com.kit.wallet.data.messaging.SecureMessagingLocalPublicBundle
import com.kit.wallet.data.messaging.SecureMessagingPreparedFanout
import com.kit.wallet.data.messaging.SecureMessagingProvisioningPlan
import com.kit.wallet.data.messaging.SecureMessagingSessionEstablishmentRequest

/** Test-only adapter proving output passed the same opaque post-durable-commit gate. */
internal suspend fun commitEncryptedFanoutForTest(
    fanout: SecureMessagingPreparedFanout,
    plan: SecureMessagingEncryptionPlan,
    activation: SecureMessagingActivationCapability,
): SecureMessagingCommittedResult.Encrypted {
    val transaction = TestCommittedCryptoTransaction(
        activation = activation,
        prepared = FixturePrepared.Encryption(fanout),
    )
    val request = SecureMessagingEncryptionRequest(
        plan = plan,
        clientMessageId = fanout.clientMessageId,
        text = "test secure content",
        replyToMessageId = fanout.replyToMessageId,
    )
    return try {
        check(transaction.missingSessions(plan).isEmpty)
        transaction.stageEncryption(
            request,
            SecureMessagingCompanionStateIntent.outbound(
                namespace = "outbox",
                recordKey = fanout.clientMessageId,
            ),
        )
        transaction.commit() as SecureMessagingCommittedResult.Encrypted
    } finally {
        request.close()
    }
}

internal suspend fun commitProvisioningForTest(
    bundle: SecureMessagingLocalPublicBundle,
    activation: SecureMessagingActivationCapability,
): SecureMessagingCommittedResult.Provisioned {
    val transaction = TestCommittedCryptoTransaction(
        activation,
        FixturePrepared.Provisioning(bundle),
    )
    transaction.stageProvisioning(
        SecureMessagingProvisioningPlan(
            ecOneTimePrekeyCount = bundle.oneTimePrekeys.size,
            pqOneTimePrekeyCount = bundle.pqPrekeys.size,
        ),
    )
    return transaction.commit() as SecureMessagingCommittedResult.Provisioned
}

internal suspend fun sessionResultSubstitutionFailsForTest(
    request: SecureMessagingSessionEstablishmentRequest,
    plan: SecureMessagingEncryptionPlan,
    activation: SecureMessagingActivationCapability,
    substitutedConversationId: String,
): Boolean {
    val addresses = request.bundles().map { it.address }
    val transaction = TestCommittedCryptoTransaction(
        activation = activation,
        prepared = FixturePrepared.Sessions(
            conversationId = substitutedConversationId,
            rosterRevision = request.rosterRevision,
            addresses = addresses,
        ),
        missingAddresses = addresses,
    )
    transaction.missingSessions(plan)
    transaction.stageSessionEstablishment(request)
    return runCatching { transaction.commit() }.exceptionOrNull() is IllegalStateException
}

private sealed interface FixturePrepared {
    data class Provisioning(val bundle: SecureMessagingLocalPublicBundle) : FixturePrepared

    data class Sessions(
        val conversationId: String,
        val rosterRevision: String,
        val addresses: List<SecureMessagingCryptoAddress>,
    ) : FixturePrepared

    data class Encryption(val fanout: SecureMessagingPreparedFanout) : FixturePrepared
}

private class TestCommittedCryptoTransaction(
    activation: SecureMessagingActivationCapability,
    private val prepared: FixturePrepared,
    private val missingAddresses: Collection<SecureMessagingCryptoAddress> = emptyList(),
) : FailClosedSecureMessagingCryptoTransaction(activation) {
    override suspend fun stageProvisioningMaterial(plan: SecureMessagingProvisioningPlan) = Unit

    override suspend fun stageSessionMaterial(request: SecureMessagingSessionEstablishmentRequest) = Unit

    override suspend fun findMissingSessionAddresses(
        plan: SecureMessagingEncryptionPlan,
        candidates: List<SecureMessagingCryptoAddress>,
    ): Collection<SecureMessagingCryptoAddress> = missingAddresses

    override suspend fun stageEncryptionMaterial(request: SecureMessagingEncryptionRequest) = Unit

    override suspend fun stageDecryptionMaterial(request: SecureMessagingDecryptionRequest) = Unit

    override suspend fun prepareStaged(
        operation: SecureMessagingCryptoOperation,
        companionStateIntent: SecureMessagingCompanionStateIntent?,
    ): PreparedCommit {
        companionStateIntent?.let(::companionStateDestination)
        return when (val value = prepared) {
            is FixturePrepared.Provisioning -> preparedProvisioning(value.bundle)
            is FixturePrepared.Sessions -> preparedSessionsEstablished(
                value.conversationId,
                value.rosterRevision,
                value.addresses,
            )
            is FixturePrepared.Encryption -> preparedEncryption(value.fanout)
        }
    }

    override suspend fun commitPrepared(
        operation: SecureMessagingCryptoOperation,
        preparedResult: PreparedCommit,
    ) = Unit

    override suspend fun abortStaged() = Unit

    override fun wipeStagedSecrets() = Unit
}
