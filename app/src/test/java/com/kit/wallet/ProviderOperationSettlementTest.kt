package com.kit.wallet

import com.kit.wallet.data.remote.CurrencyDto
import com.kit.wallet.data.remote.ProviderOperationDto
import com.kit.wallet.data.repository.awaitProviderOperationTerminal
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderOperationSettlementTest {
    @Test
    fun `each authoritative terminal status refreshes the released or captured balance once`() = runTest {
        listOf("succeeded", "failed", "reversed").forEach { status ->
            var fetches = 0
            var refreshes = 0

            val result = awaitProviderOperationTerminal(
                initial = operation(status),
                fetch = {
                    fetches++
                    error("A terminal operation must not be polled")
                },
                refreshWallet = { refreshes++ },
                waitBeforePoll = {},
            )

            assertEquals(status, result.status)
            assertEquals(0, fetches)
            assertEquals(1, refreshes)
        }
    }

    @Test
    fun `pending airtime is polled through a fast provider failure before one wallet refresh`() = runTest {
        val statuses = ArrayDeque(listOf("submitting", "failed"))
        var refreshes = 0
        var waits = 0

        val result = awaitProviderOperationTerminal(
            initial = operation("pending"),
            fetch = { operation(statuses.removeFirst()) },
            refreshWallet = { refreshes++ },
            waitBeforePoll = { waits++ },
            pollLimit = 8,
            pollIntervalMillis = 0,
        )

        assertEquals("failed", result.status)
        assertEquals(2, waits)
        assertEquals(1, refreshes)
    }

    @Test
    fun `slow provider still refreshes the held balance once after the bounded window`() = runTest {
        var refreshes = 0
        var fetches = 0

        val result = awaitProviderOperationTerminal(
            initial = operation("pending"),
            fetch = {
                fetches++
                operation("unknown")
            },
            refreshWallet = { refreshes++ },
            waitBeforePoll = {},
            pollLimit = 3,
            pollIntervalMillis = 0,
        )

        assertEquals("unknown", result.status)
        assertEquals(3, fetches)
        assertEquals(1, refreshes)
    }

    private fun operation(status: String) = ProviderOperationDto(
        id = "10000000-0000-4000-8000-000000000001",
        type = "airtime_purchase",
        status = status,
        walletId = "20000000-0000-4000-8000-000000000002",
        providerCode = "rukapay",
        productId = "mtn-airtime",
        productName = "MTN Airtime",
        accountDisplay = "+256 700 000 001",
        amount = "1000.00",
        fee = "0.00",
        total = "1000.00",
        currency = CurrencyDto("UGX", "2"),
        clientReference = "android-provider-reference",
    )
}
