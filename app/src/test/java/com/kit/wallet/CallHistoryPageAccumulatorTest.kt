package com.kit.wallet

import com.kit.wallet.data.remote.CallDto
import com.kit.wallet.data.remote.CallPageDto
import com.kit.wallet.data.remote.CursorPageDto
import com.kit.wallet.data.repository.CallHistoryPageAccumulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CallHistoryPageAccumulatorTest {
    @Test fun `accumulates cursor pages and de-duplicates call ids`() {
        val pages = CallHistoryPageAccumulator(requestedLimit = 2)

        assertFalse(pages.append(page(listOf(call("a"), call("b")), "next", true, 2)))
        assertTrue(pages.append(page(listOf(call("B"), call("c")), null, false, 2)))

        assertEquals(listOf("a", "b", "c"), pages.calls.map(CallDto::id))
        assertEquals(2, pages.pageCount)
        assertEquals(null, pages.nextCursor)
    }

    @Test fun `rejects malformed or looping cursor metadata`() {
        assertThrows(IllegalArgumentException::class.java) {
            CallHistoryPageAccumulator(requestedLimit = 2).append(
                page(listOf(call("a")), "next", true, 50),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            CallHistoryPageAccumulator(requestedLimit = 2).append(
                page(listOf(call("a")), " ", true, 2),
            )
        }
        val looping = CallHistoryPageAccumulator(requestedLimit = 1)
        assertFalse(looping.append(page(listOf(call("a")), "same", true, 1)))
        assertThrows(IllegalStateException::class.java) {
            looping.append(page(listOf(call("b")), "same", true, 1))
        }
    }

    @Test fun `requires explicit completion before the finite page ceiling`() {
        val pages = CallHistoryPageAccumulator(requestedLimit = 1, maximumPageCount = 2)
        assertFalse(pages.append(page(listOf(call("a")), "two", true, 1)))
        assertThrows(IllegalStateException::class.java) {
            pages.append(page(listOf(call("b")), "three", true, 1))
        }
    }

    private fun page(
        calls: List<CallDto>,
        cursor: String?,
        hasMore: Boolean,
        limit: Int,
    ) = CallPageDto(calls, CursorPageDto(cursor, hasMore, limit))

    private fun call(id: String) = CallDto(
        id = id,
        direction = "incoming",
        type = "voice",
        state = "ended",
        startedAt = "2026-08-22T12:00:00Z",
    )
}
