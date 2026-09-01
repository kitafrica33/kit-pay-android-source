package com.kit.wallet

import com.kit.wallet.data.notifications.MobileMoneySettlementAlert
import com.kit.wallet.data.notifications.MobileMoneySettlementLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MobileMoneySettlementAlertTest {
    @Test
    fun `all six terminal contracts parse and round trip through a canonical local link`() {
        listOf("collection", "payout").forEach { kind ->
            listOf("succeeded", "failed", "reversed").forEach { status ->
                val alert = MobileMoneySettlementAlert.fromData(data(kind, status))

                assertEquals("mobile_money.$kind.$status", alert?.type)
                assertEquals(OPERATION_ID, alert?.operationId)
                assertEquals(kind, alert?.mobileMoneyType)
                assertEquals(status, alert?.status)
                assertEquals(alert?.link(), alert?.link()?.deepLinkUri()?.let {
                    MobileMoneySettlementLink.fromDeepLink(it)
                })
            }
        }
    }

    @Test
    fun `type status kind identifiers and tag must agree exactly`() {
        val valid = data("payout", "succeeded")

        assertNull(MobileMoneySettlementAlert.fromData(valid + ("status" to "failed")))
        assertNull(MobileMoneySettlementAlert.fromData(valid + ("mobile_money_type" to "collection")))
        assertNull(MobileMoneySettlementAlert.fromData(valid + ("operation_id" to "operation-1")))
        assertNull(MobileMoneySettlementAlert.fromData(valid + ("notification_id" to "notice-1")))
        assertNull(MobileMoneySettlementAlert.fromData(valid + ("notification_tag" to "wrong")))
        assertNull(MobileMoneySettlementAlert.fromData(valid + ("type" to "mobile_money.payout.pending")))
    }

    @Test
    fun `payload deep links and malformed local links cannot steer navigation`() {
        val alert = MobileMoneySettlementAlert.fromData(
            data("collection", "reversed") + ("deep_link" to "https://attacker.example"),
        )!!

        assertEquals(
            "kitwallet://mobile-money/operation?operation_id=$OPERATION_ID",
            alert.link().deepLinkUri(),
        )
        assertNull(MobileMoneySettlementLink.fromDeepLink("https://mobile-money/operation?operation_id=$OPERATION_ID"))
        assertNull(MobileMoneySettlementLink.fromDeepLink("kitwallet://mobile-money/operation?operation_id=bad"))
        assertNull(MobileMoneySettlementLink.fromDeepLink("kitwallet://mobile-money/operation?operation_id=$OPERATION_ID&extra=1"))
    }

    private fun data(kind: String, status: String) = mapOf(
        "notification_id" to NOTIFICATION_ID,
        "type" to "mobile_money.$kind.$status",
        "operation_id" to OPERATION_ID,
        "status" to status,
        "mobile_money_type" to kind,
        "notification_tag" to "mobile-money-operation:$OPERATION_ID",
        "notification_title" to "Mobile money updated",
        "notification_body" to "Open Kit Pay for details.",
    )

    private companion object {
        const val OPERATION_ID = "00000000-0000-4000-8000-000000000001"
        const val NOTIFICATION_ID = "00000000-0000-4000-8000-000000000002"
    }
}
