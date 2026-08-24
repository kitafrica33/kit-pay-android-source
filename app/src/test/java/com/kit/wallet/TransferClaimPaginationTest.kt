package com.kit.wallet

import com.kit.wallet.data.remote.CurrencyDto
import com.kit.wallet.data.remote.CursorPageDto
import com.kit.wallet.data.remote.TransferClaimDto
import com.kit.wallet.data.remote.TransferClaimPageDto
import com.kit.wallet.data.repository.loadVisibleTransferClaims
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferClaimPaginationTest {
    @Test
    fun `a complete recent page needs no pending sweep`() = runTest {
        val requests = mutableListOf<Triple<String?, String?, Int>>()

        val claims = loadVisibleTransferClaims { status, cursor, limit ->
            requests += Triple(status, cursor, limit)
            page(items = listOf(claim("recent")), hasMore = false)
        }

        assertEquals(listOf("recent"), claims.map(TransferClaimDto::id))
        assertEquals(listOf(Triple(null, null, 50)), requests)
    }

    @Test
    fun `truncated history sweeps every pending page and keeps recent outcomes`() = runTest {
        val requests = mutableListOf<Triple<String?, String?, Int>>()

        val claims = loadVisibleTransferClaims { status, cursor, limit ->
            requests += Triple(status, cursor, limit)
            when (status to cursor) {
                null to null -> page(
                    items = listOf(claim("recent-settled"), claim("pending-one")),
                    hasMore = true,
                    nextCursor = "ignored-all-history-cursor",
                )
                "pending" to null -> page(
                    items = listOf(claim("pending-one"), claim("pending-two")),
                    hasMore = true,
                    nextCursor = "pending-page-two",
                )
                "pending" to "pending-page-two" -> page(
                    items = listOf(claim("older-pending")),
                    hasMore = false,
                )
                else -> error("Unexpected request: $status / $cursor")
            }
        }

        assertEquals(
            listOf("recent-settled", "pending-one", "pending-two", "older-pending"),
            claims.map(TransferClaimDto::id),
        )
        assertEquals(
            listOf(
                Triple(null, null, 50),
                Triple("pending", null, 50),
                Triple("pending", "pending-page-two", 50),
            ),
            requests,
        )
    }

    @Test
    fun `a repeated pending cursor fails closed`() = runTest {
        var calls = 0
        val failure = runCatching {
            loadVisibleTransferClaims { status, _, _ ->
                calls += 1
                if (status == null) {
                    page(emptyList(), hasMore = true, nextCursor = "all-next")
                } else {
                    page(emptyList(), hasMore = true, nextCursor = "same")
                }
            }
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(3, calls)
    }

    private fun page(
        items: List<TransferClaimDto>,
        hasMore: Boolean,
        nextCursor: String? = null,
    ) = TransferClaimPageDto(
        items = items,
        page = CursorPageDto(nextCursor = nextCursor, hasMore = hasMore, limit = 50),
    )

    private fun claim(id: String) = TransferClaimDto(
        id = id,
        transactionId = "transaction-$id",
        status = "pending",
        amount = "500",
        currency = CurrencyDto(code = "UGX", scale = "0"),
    )
}
