package com.kit.wallet.data.repository

import com.kit.wallet.data.remote.CallDto
import com.kit.wallet.data.remote.CallPageDto

/** Validates and accumulates the authenticated cursor contract returned by `GET /calls`. */
internal class CallHistoryPageAccumulator(
    private val requestedLimit: Int = PAGE_LIMIT,
    private val maximumPageCount: Int = MAXIMUM_PAGES,
) {
    private val seenCallIds = mutableSetOf<String>()
    private val seenCursors = mutableSetOf<String>()
    private val mutableCalls = mutableListOf<CallDto>()

    val calls: List<CallDto> get() = mutableCalls.toList()
    var nextCursor: String? = null
        private set
    var pageCount: Int = 0
        private set

    init {
        require(requestedLimit in 1..PAGE_LIMIT)
        require(maximumPageCount > 0)
    }

    /** Returns true only after the service explicitly declares history complete. */
    fun append(response: CallPageDto): Boolean {
        check(pageCount < maximumPageCount) { "Call history exceeded its page limit" }
        val items = requireNotNull(response.items) { "Call history omitted its items" }
        val page = requireNotNull(response.page) { "Call history omitted its cursor metadata" }
        val hasMore = requireNotNull(page.hasMore) { "Call history omitted has_more" }
        require(page.limit == requestedLimit) { "Call history returned an unexpected page limit" }
        require(items.size <= requestedLimit) { "Call history returned too many items" }

        val continuation = if (hasMore) {
            requireNotNull(page.nextCursor)
                .trim()
                .also { cursor ->
                    require(cursor.isNotEmpty() && cursor.length <= MAXIMUM_CURSOR_LENGTH) {
                        "Call history returned an invalid continuation cursor"
                    }
                    check(pageCount + 1 < maximumPageCount) {
                        "Call history did not terminate before its page limit"
                    }
                    check(seenCursors.add(cursor)) {
                        "Call history repeated a continuation cursor"
                    }
                }
        } else {
            null
        }

        items.forEach { call ->
            if (seenCallIds.add(call.id.lowercase())) mutableCalls += call
        }
        pageCount += 1
        nextCursor = continuation
        return !hasMore
    }

    companion object {
        const val PAGE_LIMIT = 100
        const val MAXIMUM_PAGES = 1_000
        const val MAXIMUM_CURSOR_LENGTH = 2_048
    }
}
