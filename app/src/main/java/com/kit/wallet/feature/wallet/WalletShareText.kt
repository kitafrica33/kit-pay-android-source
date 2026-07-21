package com.kit.wallet.feature.wallet

import com.kit.wallet.ui.model.Money
import com.kit.wallet.ui.model.Transaction
import com.kit.wallet.ui.model.UserProfile
import com.kit.wallet.ui.model.formatKitTag

internal fun receiveDetailsShareText(profile: UserProfile): String {
    val name = profile.name.trim().ifBlank { "this Kit Pay user" }
    val identifiers = listOf(formatKitTag(profile.tag), profile.phone.trim())
        .filter(String::isNotBlank)
        .joinToString(" or ")
    return if (identifiers.isBlank()) {
        "Find $name on Kit Pay."
    } else {
        "Pay $name on Kit Pay using $identifiers."
    }
}

internal fun receiptShareText(
    recipientName: String?,
    amountMinor: Long,
    transaction: Transaction,
): String = buildString {
    val recipient = recipientName?.trim()?.takeIf(String::isNotBlank)
        ?: transaction.counterparty
    appendLine("Kit Pay receipt")
    append("Sent ${Money.format(amountMinor)} to ")
    appendLine(recipient)
    appendLine("Status: ${transaction.status.name.lowercase().replaceFirstChar { it.titlecase() }}")
    appendLine("Reference: ${transaction.reference}")
    append("Date: ${transaction.dateGroup}, ${transaction.time}")
}
