package com.kit.wallet

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityCallIntentConsumptionTest {
    private val callId = "550e8400-e29b-41d4-a716-446655440000"

    @Test
    fun untrustedIncomingCallDeepLinkIsRejectedAndRemoved() {
        val intent = Intent().setData(
            Uri.parse("kitwallet://call/incoming?call_id=$callId"),
        )

        assertNull(intent.takeKitDeepLink())
        assertNull(intent.data)
        assertNull(intent.takeKitDeepLink())
    }

    @Test
    fun providerCallExtrasAreRemovedWithoutCreatingAnIncomingRoute() {
        val intent = Intent()
            .putExtra("type", "call.ringing")
            .putExtra("call_id", callId)
            .putExtra("call_type", "voice")
            .putExtra("initiator_name", "Florence")
            .putExtra("ring_expires_at", "2026-07-24T15:20:00Z")

        assertNull(intent.takeKitDeepLink())
        assertNull(intent.getStringExtra("call_id"))
        assertNull(intent.takeKitDeepLink())
    }

    @Test
    fun callUriAlsoConsumesDuplicateProviderExtras() {
        val intent = Intent()
            .setData(Uri.parse("kitwallet://call/incoming?call_id=$callId"))
            .putExtra("type", "call.ringing")
            .putExtra("call_id", callId)
            .putExtra("call_type", "voice")
            .putExtra("initiator_name", "Florence")
            .putExtra("ring_expires_at", "2026-07-24T15:20:00Z")

        assertNull(intent.takeKitDeepLink())
        assertNull(intent.data)
        assertNull(intent.getStringExtra("call_id"))
        assertNull(intent.takeKitDeepLink())
    }

    @Test
    fun externalAutoAnswerUriCannotBypassThePrivateCallAuthorizer() {
        val intent = Intent().setData(
            Uri.parse(
                "kitwallet://call/incoming?call_id=$callId&accept=1&" +
                    "ignored=${"x".repeat(100_000)}",
            ),
        )

        assertNull(intent.takeKitDeepLink())
        assertNull(intent.data)
    }

    @Test
    fun missedCallTapOpensOnlyHistoryAndIsConsumedExactlyOnce() {
        val intent = Intent(Intent.ACTION_VIEW)
            .setData(Uri.parse("kitwallet://calls/history"))
            .putExtra("call_id", callId)
            .putExtra("type", "call.ringing")
        assertEquals("kitwallet://calls/history", intent.takeKitDeepLink())
        assertNull(intent.data)
        assertNull(intent.getStringExtra("call_id"))
        assertNull(intent.takeKitDeepLink())
    }

    @Test
    fun historyRouteRejectsAnAttachedIncomingCallOrAnswerCommand() {
        val intent = Intent().setData(Uri.parse("kitwallet://calls/history?call_id=$callId&accept=1"))
        assertNull(intent.takeKitDeepLink())
    }
}
