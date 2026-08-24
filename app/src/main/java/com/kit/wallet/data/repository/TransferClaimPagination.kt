package com.kit.wallet.data.repository

import com.kit.wallet.data.remote.TransferClaimDto
import com.kit.wallet.data.remote.TransferClaimPageDto

/**
 * Keeps recent settled claims for rich status display while guaranteeing that every actionable
 * pending claim is loaded. The unfiltered endpoint is newest-first and includes settled history,
 * so paging only that feed would grow without bound; once it truncates, a bounded pending-only
 * sweep finds older held money without repeatedly downloading the account's lifetime history.
 */
internal suspend fun loadVisibleTransferClaims(
    loadPage: suspend (status: String?, cursor: String?, limit: Int) -> TransferClaimPageDto,
): List<TransferClaimDto> {
    val claims = linkedMapOf<String, TransferClaimDto>()
    val recent = loadPage(null, null, TRANSFER_CLAIM_PAGE_LIMIT)
    addTransferClaimPage(claims, recent)
    if (recent.page?.hasMore != true) return claims.values.toList()

    val seenCursors = mutableSetOf<String>()
    var cursor: String? = null
    repeat(MAX_PENDING_TRANSFER_CLAIM_PAGES) {
        val page = loadPage("pending", cursor, TRANSFER_CLAIM_PAGE_LIMIT)
        addTransferClaimPage(claims, page)
        if (page.page?.hasMore != true) return claims.values.toList()
        val next = page.page.nextCursor?.trim().orEmpty()
        check(next.isNotEmpty() && next.length <= MAX_TRANSFER_CLAIM_CURSOR_LENGTH) {
            "Transfer claim pagination returned an invalid cursor"
        }
        check(seenCursors.add(next)) {
            "Transfer claim pagination repeated a cursor"
        }
        cursor = next
    }
    error("Pending transfer claims exceeded the safe pagination limit")
}

private fun addTransferClaimPage(
    destination: LinkedHashMap<String, TransferClaimDto>,
    page: TransferClaimPageDto,
) {
    check(page.items.size <= TRANSFER_CLAIM_PAGE_LIMIT) {
        "The transfer claim service returned too many items"
    }
    for (claim in page.items) {
        destination[claim.id.lowercase()] = claim
    }
    check(destination.size <= MAX_VISIBLE_TRANSFER_CLAIMS) {
        "The transfer claim service exceeded the safe item limit"
    }
}

private const val TRANSFER_CLAIM_PAGE_LIMIT = 50
private const val MAX_PENDING_TRANSFER_CLAIM_PAGES = 100
private const val MAX_VISIBLE_TRANSFER_CLAIMS = 5_050
private const val MAX_TRANSFER_CLAIM_CURSOR_LENGTH = 2_048
