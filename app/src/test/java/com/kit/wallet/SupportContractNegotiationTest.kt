package com.kit.wallet

import com.kit.wallet.data.remote.SupportPaymentBeneficiaryDto
import com.kit.wallet.data.remote.SupportProtocolDto
import com.kit.wallet.data.support.SupportContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The capability handshake is the single switch for the whole support surface:
 * every deviation from the deployed contract must negotiate to null, which
 * keeps the surface dark (docs/support-client.md N1/N2).
 */
class SupportContractNegotiationTest {
    private fun deployed(
        ready: Boolean? = true,
        endToEndEncrypted: Boolean? = false,
        content: String? = "server_readable",
        transport: String? = "poll",
        attachments: Boolean? = false,
        paymentsReady: Boolean? = true,
        paymentsBeneficiary: SupportPaymentBeneficiaryDto? =
            SupportPaymentBeneficiaryDto(kind = "company", displayName = "Kit Pay"),
        aiEnabled: Boolean? = true,
        exactShape: Boolean = true,
    ) = SupportProtocolDto(
        ready = ready,
        endToEndEncrypted = endToEndEncrypted,
        content = content,
        transport = transport,
        attachments = attachments,
        paymentsReady = paymentsReady,
        paymentsBeneficiary = paymentsBeneficiary,
        aiEnabled = aiEnabled,
        exactShape = exactShape,
    )

    @Test
    fun `the exact deployed advertisement negotiates with its server-authored values`() {
        val negotiated = SupportContract.negotiate(deployed())

        assertNotNull(negotiated)
        assertTrue(negotiated!!.paymentsReady)
        assertEquals("Kit Pay", negotiated.companyBeneficiaryName)
        assertTrue(negotiated.aiEnabled)
    }

    @Test
    fun `payments and ai may be off without darkening support itself`() {
        val negotiated = SupportContract.negotiate(
            deployed(paymentsReady = false, aiEnabled = false),
        )

        assertNotNull(negotiated)
        assertFalse(negotiated!!.paymentsReady)
        assertFalse(negotiated.aiEnabled)
    }

    @Test
    fun `an absent or shape-drifted block fails closed`() {
        assertNull(SupportContract.negotiate(null))
        // A block that is perfect except for a structural anomaly the adapter
        // flagged: the flag alone must be terminal.
        assertNull(SupportContract.negotiate(deployed(exactShape = false)))
    }

    @Test
    fun `a not-ready or incomplete advertisement fails closed`() {
        assertNull(SupportContract.negotiate(deployed(ready = false)))
        assertNull(SupportContract.negotiate(deployed(ready = null)))
        assertNull(SupportContract.negotiate(deployed(paymentsReady = null)))
        assertNull(SupportContract.negotiate(deployed(paymentsBeneficiary = null)))
        assertNull(SupportContract.negotiate(deployed(aiEnabled = null)))
    }

    @Test
    fun `a server claiming end-to-end encryption for support is refused`() {
        // Support threads are server-readable by contract. A server claiming
        // otherwise would make this client's privacy notice a lie, so the
        // truthful-by-construction handshake refuses it.
        assertNull(SupportContract.negotiate(deployed(endToEndEncrypted = true)))
        assertNull(SupportContract.negotiate(deployed(endToEndEncrypted = null)))
    }

    @Test
    fun `unknown content or transport words fail closed`() {
        assertNull(SupportContract.negotiate(deployed(content = "end_to_end")))
        assertNull(SupportContract.negotiate(deployed(content = null)))
        assertNull(SupportContract.negotiate(deployed(transport = "websocket")))
        assertNull(SupportContract.negotiate(deployed(transport = null)))
    }

    @Test
    fun `an attachments-enabled server is incompatible with this build`() {
        // This client has no support-attachment pipeline; a server expecting
        // one is a different protocol, not a bonus feature.
        assertNull(SupportContract.negotiate(deployed(attachments = true)))
        assertNull(SupportContract.negotiate(deployed(attachments = null)))
    }

    @Test
    fun `contract constants match the deployed backend words`() {
        assertEquals("server_readable", SupportContract.CONTENT_SERVER_READABLE)
        assertEquals("poll", SupportContract.TRANSPORT_POLL)
        assertEquals("official_support", SupportContract.VERIFIED_DESIGNATION)
    }
}
