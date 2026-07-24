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
    fun callDeepLinkIsConsumedExactlyOnce() {
        val intent = Intent().setData(
            Uri.parse("kitwallet://call/incoming?call_id=$callId"),
        )

        assertEquals(
            "kitwallet://call/incoming?call_id=$callId",
            intent.takeKitDeepLink(),
        )
        assertNull(intent.data)
        assertNull(intent.takeKitDeepLink())
    }

    @Test
    fun providerCallExtrasAreRemovedAfterRouting() {
        val intent = Intent()
            .putExtra("type", "call.ringing")
            .putExtra("call_id", callId)
            .putExtra("call_type", "voice")
            .putExtra("initiator_name", "Florence")
            .putExtra("ring_expires_at", "2026-07-24T15:20:00Z")

        assertEquals(
            "kitwallet://call/incoming?call_id=$callId",
            intent.takeKitDeepLink(),
        )
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

        assertEquals(
            "kitwallet://call/incoming?call_id=$callId",
            intent.takeKitDeepLink(),
        )
        assertNull(intent.data)
        assertNull(intent.getStringExtra("call_id"))
        assertNull(intent.takeKitDeepLink())
    }

    @Test
    fun acceptedCallUriIsCanonicalizedBeforeItCanBeSaved() {
        val intent = Intent().setData(
            Uri.parse(
                "kitwallet://call/incoming?call_id=$callId&accept=1&" +
                    "ignored=${"x".repeat(100_000)}",
            ),
        )

        assertEquals(
            "kitwallet://call/incoming?call_id=$callId&accept=1",
            intent.takeKitDeepLink(),
        )
        assertNull(intent.data)
    }
}
