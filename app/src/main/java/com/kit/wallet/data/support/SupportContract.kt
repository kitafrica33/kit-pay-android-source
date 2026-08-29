package com.kit.wallet.data.support

import com.kit.wallet.data.remote.SupportProtocolDto

/**
 * The support protocol this build can safely speak, negotiated from
 * `protocols.support` with an exact fail-closed handshake: any deviation —
 * an absent block, a shape anomaly, an unexpected constant, an attachment
 * capability this build has no pipeline for — renders support unavailable
 * (docs/support-client.md N1/N2).
 */
data class NegotiatedSupportProtocol(
    /** Server-side readiness of the company-beneficiary payment lane. */
    val paymentsReady: Boolean,
    /** Display name of the only beneficiary a support payment can have. */
    val companyBeneficiaryName: String,
    /** Whether the server may put its AI assistant on new tickets. */
    val aiEnabled: Boolean,
)

object SupportContract {
    const val CONTENT_SERVER_READABLE = "server_readable"
    const val TRANSPORT_POLL = "poll"

    /**
     * The one designation that lights the blue verified badge. Names, sender
     * types, and the `official`/`automated` booleans never do.
     */
    const val VERIFIED_DESIGNATION = "official_support"

    /**
     * Returns the negotiated protocol, or null when the advertisement does not
     * match this build exactly. The block is truthful about privacy by
     * construction: support threads are server-readable and never end-to-end
     * encrypted, and this client refuses a server claiming otherwise.
     */
    fun negotiate(dto: SupportProtocolDto?): NegotiatedSupportProtocol? {
        if (dto == null || !dto.exactShape) return null
        if (dto.ready != true) return null
        if (dto.endToEndEncrypted != false) return null
        if (dto.content != CONTENT_SERVER_READABLE) return null
        if (dto.transport != TRANSPORT_POLL) return null
        // Hard false until this client grows a support-attachment pipeline.
        if (dto.attachments != false) return null
        val paymentsReady = dto.paymentsReady ?: return null
        val beneficiary = dto.paymentsBeneficiary ?: return null
        val aiEnabled = dto.aiEnabled ?: return null
        return NegotiatedSupportProtocol(
            paymentsReady = paymentsReady,
            companyBeneficiaryName = beneficiary.displayName,
            aiEnabled = aiEnabled,
        )
    }
}
