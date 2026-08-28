package com.kit.wallet

import com.kit.wallet.data.notifications.ActiveCallReturnLink
import com.kit.wallet.data.notifications.CallReopenAction
import com.kit.wallet.data.notifications.callReopenDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActiveCallReturnLinkTest {

    @Test
    fun `a link only exists for a server-issued uuid, canonicalized`() {
        assertEquals(
            "3f2504e0-4f89-11d3-9a0c-0305e82c3301",
            ActiveCallReturnLink.forCallId(" 3F2504E0-4F89-11D3-9A0C-0305E82C3301 ")?.callId,
        )
        assertNull(ActiveCallReturnLink.forCallId(null))
        assertNull(ActiveCallReturnLink.forCallId("  "))
        assertNull(ActiveCallReturnLink.forCallId("call-42"))
        assertNull(ActiveCallReturnLink.forCallId("kitwallet://call/active"))
    }

    @Test
    fun `the notification link round-trips back to the same call id`() {
        val link = ActiveCallReturnLink.forCallId("3F2504E0-4F89-11D3-9A0C-0305E82C3301")

        assertEquals(link, link?.deepLinkUri()?.let(ActiveCallReturnLink::fromDeepLink))
        assertEquals(
            "3f2504e0-4f89-11d3-9a0c-0305e82c3301",
            link?.let(ActiveCallReturnLink::deepLinkUri)
                ?.let(ActiveCallReturnLink::fromDeepLink)
                ?.callId,
        )
    }

    @Test
    fun `anything but the exact scheme, host, path and a valid id is rejected`() {
        val id = "3f2504e0-4f89-11d3-9a0c-0305e82c3301"

        assertNull(ActiveCallReturnLink.fromDeepLink("https://call/active?call_id=$id"))
        assertNull(ActiveCallReturnLink.fromDeepLink("kitwallet://calls/active?call_id=$id"))
        assertNull(ActiveCallReturnLink.fromDeepLink("kitwallet://call/incoming?call_id=$id"))
        assertNull(ActiveCallReturnLink.fromDeepLink("kitwallet://call/active"))
        assertNull(ActiveCallReturnLink.fromDeepLink("kitwallet://call/active?call_id="))
        assertNull(ActiveCallReturnLink.fromDeepLink("kitwallet://call/active?call_id=not-a-call"))
        assertNull(ActiveCallReturnLink.fromDeepLink("::not a uri::"))
    }

    @Test
    fun `reopening returns to the exact call this device is in`() {
        assertEquals(
            CallReopenAction.POP_BACK_TO_CALL,
            callReopenDecision("call-1", activeCallId = "CALL-1", onCallRoute = false),
        )
        assertEquals(
            CallReopenAction.ALREADY_OPEN,
            callReopenDecision("call-1", activeCallId = "call-1", onCallRoute = true),
        )
    }

    @Test
    fun `a stale or foreign link does nothing at all`() {
        assertEquals(
            CallReopenAction.IGNORE,
            callReopenDecision(null, activeCallId = "call-1", onCallRoute = false),
        )
        assertEquals(
            CallReopenAction.IGNORE,
            callReopenDecision(" ", activeCallId = "call-1", onCallRoute = false),
        )
        assertEquals(
            CallReopenAction.IGNORE,
            callReopenDecision("call-2", activeCallId = "call-1", onCallRoute = false),
        )
        assertEquals(
            CallReopenAction.IGNORE,
            callReopenDecision("call-1", activeCallId = null, onCallRoute = false),
        )
        assertEquals(
            CallReopenAction.IGNORE,
            callReopenDecision("call-1", activeCallId = "", onCallRoute = true),
        )
    }
}
