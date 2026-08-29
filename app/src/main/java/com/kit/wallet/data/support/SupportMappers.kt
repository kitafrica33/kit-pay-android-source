package com.kit.wallet.data.support

import com.kit.wallet.data.local.SUPPORT_OUTBOX_STATUS_FAILED
import com.kit.wallet.data.local.SupportOutboxEntity
import com.kit.wallet.data.remote.SupportCategoryDto
import com.kit.wallet.data.remote.SupportMessageDto
import com.kit.wallet.data.remote.SupportMessageSenderDto
import com.kit.wallet.data.remote.SupportPaymentDto
import com.kit.wallet.data.remote.SupportTicketDto
import com.kit.wallet.data.remote.SupportVerificationBadgeDto

// DTO → domain projections. Two rules hold everywhere in this file:
//  1. The verified badge derives from the server verification designation and
//     nothing else (docs/support-client.md I1).
//  2. Unrecognized enum strings degrade to UNKNOWN, which every consumer
//     treats as the most restricted variant (closed ticket, unbadged sender).

internal fun SupportCategoryDto.toDomain(): SupportCategory = SupportCategory(
    id = id,
    key = key,
    name = name,
    description = description,
)

private fun SupportVerificationBadgeDto?.grantsBadge(): Boolean =
    this?.designation == SupportContract.VERIFIED_DESIGNATION

internal fun SupportTicketDto.toDomain(): SupportTicket = SupportTicket(
    id = id,
    reference = reference,
    subject = subject,
    status = when (status) {
        "open" -> SupportTicketStatus.OPEN
        "closed" -> SupportTicketStatus.CLOSED
        else -> SupportTicketStatus.UNKNOWN
    },
    categoryKey = category.key,
    categoryName = category.name,
    identityDisplayName = supportIdentity.displayName,
    identityVerified = supportIdentity.verification.grantsBadge(),
    agentAlias = agent?.alias,
    agentHasAvatar = agent?.hasAvatar == true,
    assistantActive = assistantActive,
    messageCount = messageCount.toLong(),
    createdAt = createdAt,
    lastMessageAt = lastMessageAt,
    closedAt = closed?.at,
    closedReasonCode = closed?.reasonCode,
)

internal fun SupportMessageSenderDto.toDomain(): SupportSender = SupportSender(
    type = when (type) {
        "customer" -> SupportSenderType.CUSTOMER
        "agent" -> SupportSenderType.AGENT
        "assistant" -> SupportSenderType.ASSISTANT
        "system" -> SupportSenderType.SYSTEM
        else -> SupportSenderType.UNKNOWN
    },
    displayName = displayName,
    automated = automated,
    verifiedOfficialSupport = verification.grantsBadge(),
    agentAlias = agentAlias,
)

internal fun SupportMessageDto.toDomain(): SupportMessage = SupportMessage(
    id = id,
    position = position,
    sender = sender.toDomain(),
    body = body,
    hasUndisplayableAttachment = attachment != null,
    createdAt = createdAt,
)

internal fun SupportPaymentDto.toReceipt(idempotentReplay: Boolean): SupportPaymentReceipt =
    SupportPaymentReceipt(
        transactionId = transaction.id,
        reference = transaction.reference,
        amount = transaction.amount,
        currencyCode = transaction.currency,
        status = transaction.status,
        occurredAt = transaction.occurredAt,
        beneficiaryName = beneficiary.displayName,
        ticketPaymentId = ticketPaymentId,
        idempotentReplay = idempotentReplay,
    )

internal fun SupportOutboxEntity.toDraft(): SupportDraft = SupportDraft(
    clientMessageId = clientMessageId,
    kind = kind,
    ticketId = ticketId,
    categoryKey = categoryKey,
    subject = subject,
    body = body,
    failed = status == SUPPORT_OUTBOX_STATUS_FAILED,
    failureCode = failureCode,
    attempted = lastAttemptAtEpochMillis != null,
    createdAtEpochMillis = createdAtEpochMillis,
)
