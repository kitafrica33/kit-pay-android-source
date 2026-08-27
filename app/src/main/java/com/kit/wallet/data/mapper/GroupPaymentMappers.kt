package com.kit.wallet.data.mapper

import com.kit.wallet.data.remote.GroupPaymentDto
import com.kit.wallet.data.remote.GroupPaymentRecipientDto
import com.kit.wallet.data.remote.GroupPaymentShareDto
import com.kit.wallet.ui.model.GroupPaymentRecipient
import com.kit.wallet.ui.model.GroupPaymentShare
import com.kit.wallet.ui.model.GroupPaymentShareStatus
import com.kit.wallet.ui.model.GroupPaymentSummary
import java.time.Instant
import kotlin.math.abs

/**
 * A group payment as this build can render it, or null.
 *
 * Anything unreadable — an unknown status, an amount this app cannot parse exactly — maps to null
 * rather than to a guess, exactly as a one-to-one claim does. A card that offered "Take your share"
 * over a state it had not understood would invite someone to settle the same money twice.
 */
fun GroupPaymentDto.toUiModel(): GroupPaymentSummary? {
    val scale = currency.scale.toIntOrNull() ?: return null
    val total = totalAmount?.let { minorOrNull(it, scale) ?: return null }
    val share = yourShare?.let { it.toUiModel(scale) ?: return null }
    val members = recipients.map { it.toUiModel(scale) ?: return null }
    return GroupPaymentSummary(
        id = id,
        conversationId = conversationId?.takeIf(String::isNotBlank),
        splitMode = splitMode,
        audience = audience,
        currencyCode = currency.code,
        currencyScale = scale,
        // An older service that omits the count still has the roster it disclosed to this viewer.
        recipientCount = recipientCount ?: members.size,
        totalAmountMinor = total,
        note = note?.takeIf(String::isNotBlank),
        senderUserId = sender?.id?.takeIf(String::isNotBlank),
        senderName = sender?.name?.takeIf(String::isNotBlank),
        settled = status.equals("settled", ignoreCase = true),
        pendingCount = pendingCount ?: 0,
        acceptedCount = acceptedCount ?: 0,
        returnedCount = returnedCount ?: 0,
        yourShare = share,
        canReverseUnclaimed = canReverseUnclaimed == true,
        recipients = members,
        expiresAtEpochMillis = expiresAt
            ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
            ?: 0L,
    )
}

private fun GroupPaymentShareDto.toUiModel(scale: Int): GroupPaymentShare? {
    val minor = minorOrNull(amount, scale) ?: return null
    val shareStatus = GroupPaymentShareStatus.fromWire(status.lowercase()) ?: return null
    val pending = shareStatus == GroupPaymentShareStatus.PENDING
    return GroupPaymentShare(
        amountMinor = minor,
        status = shareStatus,
        claimId = claimId?.takeIf(String::isNotBlank),
        // A settled share never offers an action, whatever an older or newer service says.
        canAccept = canAccept == true && pending,
        canReject = canReject == true && pending,
    )
}

private fun GroupPaymentRecipientDto.toUiModel(scale: Int): GroupPaymentRecipient? {
    val shareStatus = GroupPaymentShareStatus.fromWire(status.lowercase()) ?: return null
    val minor = amount?.let { minorOrNull(it, scale) ?: return null }
    return GroupPaymentRecipient(
        userId = userId?.takeIf(String::isNotBlank),
        name = name?.takeIf(String::isNotBlank),
        status = shareStatus,
        amountMinor = minor,
    )
}

/** Amounts are always shown as what somebody receives, so a signed wire value is read as size. */
private fun minorOrNull(amount: String, scale: Int): Long? =
    runCatching { abs(DecimalMoney.toMinor(amount, scale)) }.getOrNull()
