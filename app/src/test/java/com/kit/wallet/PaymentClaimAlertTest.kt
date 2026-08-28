package com.kit.wallet

import com.kit.wallet.data.notifications.PaymentClaimAlert
import com.kit.wallet.data.notifications.PaymentClaimLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PaymentClaimAlertTest {

    private val claimId = "3f2504e0-4f89-11d3-9a0c-0305e82c3301"
    private val notificationId = "b7f9d9a2-5a1e-4a5b-9d5c-2f9f4f4d1a10"
    private val conversationId = "0e5a9c3d-7a4b-4a4e-8a2f-6d3b1c9e7f21"
    private val groupPaymentId = "9c1d2e3f-4a5b-6c7d-8e9f-0a1b2c3d4e5f"

    private fun data(vararg overrides: Pair<String, String?>): Map<String, String> {
        val base = mutableMapOf(
            "type" to "wallet.transfer_claim.opened",
            "action" to "open_transfer_claim",
            "claim_id" to claimId,
            "notification_id" to notificationId,
            "notification_tag" to "wallet-transfer-claim:$claimId",
            "notification_title" to "Payment waiting",
            "notification_body" to "Open Kit Pay to accept or reject this transfer.",
        )
        for ((key, value) in overrides) {
            if (value == null) base.remove(key) else base[key] = value
        }
        return base
    }

    @Test
    fun `a contract-exact payload parses to canonical fields`() {
        val alert = PaymentClaimAlert.fromData(data())

        assertEquals(claimId, alert?.claimId)
        assertEquals(notificationId, alert?.notificationId)
        assertEquals("wallet-transfer-claim:$claimId", alert?.notificationTag)
        assertEquals("wallet.transfer_claim.opened", alert?.type)
        assertEquals("Payment waiting", alert?.title)
        assertNull(alert?.conversationId)
        assertNull(alert?.groupPaymentId)
        assertNull(alert?.expiresAtEpochMillis)
    }

    @Test
    fun `every settled type parses and everything else does not`() {
        for (type in listOf("accepted", "rejected", "reversed", "expired", "reminder")) {
            assertEquals(
                "wallet.transfer_claim.$type",
                PaymentClaimAlert.fromData(data("type" to "wallet.transfer_claim.$type"))?.type,
            )
        }
        assertNull(PaymentClaimAlert.fromData(data("type" to "wallet.transfer_claim.settled")))
        assertNull(PaymentClaimAlert.fromData(data("type" to "wallet.transfer_claim")))
        assertNull(PaymentClaimAlert.fromData(data("type" to "call.ringing")))
        assertNull(PaymentClaimAlert.fromData(data("type" to null)))
    }

    @Test
    fun `only the exact open action is accepted`() {
        assertNull(PaymentClaimAlert.fromData(data("action" to "open_claim")))
        assertNull(PaymentClaimAlert.fromData(data("action" to "OPEN_TRANSFER_CLAIM")))
        assertNull(PaymentClaimAlert.fromData(data("action" to "")))
        assertNull(PaymentClaimAlert.fromData(data("action" to null)))
    }

    @Test
    fun `claim and notification ids must both be uuids`() {
        assertNull(PaymentClaimAlert.fromData(data("claim_id" to "claim-42")))
        assertNull(PaymentClaimAlert.fromData(data("claim_id" to null)))
        assertNull(PaymentClaimAlert.fromData(data("claim_id" to "  ")))
        assertNull(PaymentClaimAlert.fromData(data("notification_id" to "not-a-uuid")))
        assertNull(PaymentClaimAlert.fromData(data("notification_id" to null)))
    }

    @Test
    fun `the tag must name exactly this claim in canonical form`() {
        assertNull(PaymentClaimAlert.fromData(data("notification_tag" to null)))
        assertNull(
            PaymentClaimAlert.fromData(
                data("notification_tag" to "wallet-transfer-claim:$notificationId"),
            ),
        )
        assertNull(
            PaymentClaimAlert.fromData(
                data("notification_tag" to "wallet-transfer-claim:${claimId.uppercase()}"),
            ),
        )
        assertNull(
            PaymentClaimAlert.fromData(data("notification_tag" to "wallet-claim:$claimId")),
        )
    }

    @Test
    fun `a case variant canonicalizes but nothing looser than the exact uuid form does`() {
        val alert = PaymentClaimAlert.fromData(data("claim_id" to claimId.uppercase()))

        assertEquals(claimId, alert?.claimId)
        // iOS canonicalUUID: the canonical rendering must equal the full untrimmed input.
        assertNull(PaymentClaimAlert.fromData(data("claim_id" to " $claimId ")))
        assertNull(PaymentClaimAlert.fromData(data("claim_id" to "$claimId\n")))
        assertNull(PaymentClaimAlert.fromData(data("claim_id" to "1-1-1-1-1")))
        assertNull(PaymentClaimAlert.fromData(data("claim_id" to claimId.replace("-", ""))))
        assertNull(PaymentClaimAlert.fromData(data("notification_id" to "1-1-1-1-1")))
        assertNull(
            PaymentClaimAlert.fromData(
                data(
                    "claim_id" to claimId.uppercase(),
                    "notification_tag" to "wallet-transfer-claim:${claimId.uppercase()}",
                ),
            ),
        )
    }

    @Test
    fun `contract fields are compared exactly, never trimmed`() {
        assertNull(PaymentClaimAlert.fromData(data("type" to " wallet.transfer_claim.opened")))
        assertNull(PaymentClaimAlert.fromData(data("action" to "open_transfer_claim ")))
        assertNull(
            PaymentClaimAlert.fromData(
                data("notification_tag" to " wallet-transfer-claim:$claimId"),
            ),
        )
        assertNull(PaymentClaimAlert.fromData(data("expires_at" to " 2025-01-01T00:00:00Z ")))
    }

    @Test
    fun `group hints are optional but must be uuids when present`() {
        val alert = PaymentClaimAlert.fromData(
            data("conversation_id" to conversationId, "group_payment_id" to groupPaymentId),
        )

        assertEquals(conversationId, alert?.conversationId)
        assertEquals(groupPaymentId, alert?.groupPaymentId)
        assertNull(PaymentClaimAlert.fromData(data("conversation_id" to "group-chat-7")))
        assertNull(PaymentClaimAlert.fromData(data("conversation_id" to "")))
        assertNull(PaymentClaimAlert.fromData(data("group_payment_id" to "payment-9")))
    }

    @Test
    fun `an expiry must parse as iso-8601 when present`() {
        assertEquals(
            1_735_689_600_000L,
            PaymentClaimAlert.fromData(data("expires_at" to "2025-01-01T00:00:00Z"))
                ?.expiresAtEpochMillis,
        )
        assertNull(PaymentClaimAlert.fromData(data("expires_at" to "tomorrow")))
        assertNull(PaymentClaimAlert.fromData(data("expires_at" to "2025-01-01")))
        assertNull(PaymentClaimAlert.fromData(data("expires_at" to "")))
    }

    @Test
    fun `the payload deep link is never read and never steers the reconstruction`() {
        val alert = PaymentClaimAlert.fromData(
            data("deep_link" to "kitwallet://payment/claim?claim_id=$notificationId"),
        )

        assertEquals(
            "kitwallet://payment/claim?claim_id=$claimId",
            alert?.claimLink()?.exactDeepLinkUri(),
        )
        // Even an unparseable deep_link changes nothing: the field does not participate.
        assertEquals(
            claimId,
            PaymentClaimAlert.fromData(data("deep_link" to "::junk::"))?.claimId,
        )
    }

    @Test
    fun `alert text is sanitized and blank text reads as absent`() {
        val alert = PaymentClaimAlert.fromData(
            data(
                "notification_title" to " Payment\u0000 waiting \n",
                "notification_body" to " \u0007 ",
            ),
        )

        assertEquals("Payment waiting", alert?.title)
        assertNull(alert?.body)
        assertNull(PaymentClaimAlert.fromData(data("notification_title" to null))?.title)
    }

    @Test
    fun `a reminder for the same claim carries the same coalescing tag`() {
        val opened = PaymentClaimAlert.fromData(data())
        val reminder = PaymentClaimAlert.fromData(
            data(
                "type" to "wallet.transfer_claim.reminder",
                "notification_id" to conversationId,
            ),
        )

        assertEquals(opened?.notificationTag, reminder?.notificationTag)
    }

    @Test
    fun `the reconstructed link round-trips, hints included`() {
        val link = PaymentClaimLink(claimId, conversationId, groupPaymentId)

        assertEquals(link, PaymentClaimLink.fromDeepLink(link.deepLinkUri()))
        assertEquals(
            PaymentClaimLink(claimId),
            PaymentClaimLink.fromDeepLink(link.exactDeepLinkUri()),
        )
    }

    @Test
    fun `anything but the exact scheme, host, path and valid ids is rejected`() {
        assertNull(PaymentClaimLink.fromDeepLink("https://payment/claim?claim_id=$claimId"))
        assertNull(PaymentClaimLink.fromDeepLink("kitwallet://payments/claim?claim_id=$claimId"))
        assertNull(PaymentClaimLink.fromDeepLink("kitwallet://payment/claims?claim_id=$claimId"))
        assertNull(PaymentClaimLink.fromDeepLink("kitwallet://payment/claim"))
        assertNull(PaymentClaimLink.fromDeepLink("kitwallet://payment/claim?claim_id="))
        assertNull(PaymentClaimLink.fromDeepLink("kitwallet://payment/claim?claim_id=claim-42"))
        assertNull(
            PaymentClaimLink.fromDeepLink(
                "kitwallet://payment/claim?claim_id= $claimId",
            ),
        )
        assertNull(
            PaymentClaimLink.fromDeepLink(
                "kitwallet://payment/claim?claim_id=$claimId&conversation_id=group-7",
            ),
        )
        assertNull(
            PaymentClaimLink.fromDeepLink(
                "kitwallet://payment/claim?claim_id=$claimId&claim_id=$notificationId",
            ),
        )
        assertNull(PaymentClaimLink.fromDeepLink("::not a uri::"))
        // A case-variant id still canonicalizes; only the string form is strict.
        assertEquals(
            PaymentClaimLink(claimId),
            PaymentClaimLink.fromDeepLink(
                "kitwallet://payment/claim?claim_id=${claimId.uppercase()}",
            ),
        )
    }

    @Test
    fun `unknown or malformed query segments, user info, ports and fragments all reject`() {
        val exact = "kitwallet://payment/claim?claim_id=$claimId"

        assertNull(PaymentClaimLink.fromDeepLink("$exact&utm_source=push"))
        assertNull(PaymentClaimLink.fromDeepLink("$exact&conversation_id"))
        assertNull(PaymentClaimLink.fromDeepLink("$exact&"))
        assertNull(PaymentClaimLink.fromDeepLink("$exact&=orphan"))
        assertNull(PaymentClaimLink.fromDeepLink("kitwallet://payment/claim?"))
        // Keys never percent-decode: an encoded spelling of a known key is not our link.
        assertNull(PaymentClaimLink.fromDeepLink("kitwallet://payment/claim?%63laim_id=$claimId"))
        assertNull(PaymentClaimLink.fromDeepLink("$exact#claims"))
        assertNull(PaymentClaimLink.fromDeepLink("kitwallet://user@payment/claim?claim_id=$claimId"))
        assertNull(PaymentClaimLink.fromDeepLink("kitwallet://payment:443/claim?claim_id=$claimId"))
    }

    @Test
    fun `extras attach as hints only while they stay canonical and uncontradicted`() {
        val bare = PaymentClaimLink(claimId)

        assertEquals(
            PaymentClaimLink(claimId, conversationId, groupPaymentId),
            bare.withExtraHints(conversationId, groupPaymentId),
        )
        assertEquals(bare, bare.withExtraHints(null, null))
        assertNull(bare.withExtraHints("group-chat-7", null))
        assertNull(bare.withExtraHints(null, "payment-9"))
        assertNull(bare.withExtraHints(" $conversationId", null))
        // An extra agreeing with a carried hint changes nothing, case aside.
        val hinted = PaymentClaimLink(claimId, conversationId)
        assertEquals(hinted, hinted.withExtraHints(conversationId.uppercase(), null))
        // An extra contradicting a carried hint rejects the link whole.
        assertNull(hinted.withExtraHints(groupPaymentId, null))
        assertNull(
            PaymentClaimLink(claimId, groupPaymentId = groupPaymentId)
                .withExtraHints(null, conversationId),
        )
    }
}
