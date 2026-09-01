package com.kit.wallet.feature.wallet

import com.kit.wallet.ui.model.Money
import com.kit.wallet.ui.model.Transaction
import com.kit.wallet.ui.model.UserProfile
import com.kit.wallet.ui.model.formatKitTag

internal fun receiveDetailsShareText(profile: UserProfile): String {
    // The verified name where there is one: this text is an instruction to send someone money,
    // and the name in it should be the name their account is actually known by.
    val name = profile.displayIdentityName.trim().ifBlank { "this Kit Pay user" }
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
    transaction: Transaction,
): String = buildString {
    val recipient = recipientName?.trim()?.takeIf(String::isNotBlank)
        ?: transaction.counterparty
    appendLine("Kit Pay receipt")
    appendLine(
        "Money deducted: " + Money.format(
            kotlin.math.abs(transaction.customerVisibleAmountMinor),
            transaction.currencyCode,
            transaction.currencyScale,
        ),
    )
    appendLine("To: $recipient")
    appendLine("Status: ${transaction.status.name.lowercase().replaceFirstChar { it.titlecase() }}")
    appendLine("Reference: ${transaction.reference}")
    append("Date: ${transaction.dateGroup}, ${transaction.time}")
}
