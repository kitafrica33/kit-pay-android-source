package com.kit.wallet.feature.wallet

import com.kit.wallet.data.mapper.isCustomerVisibleWalletTransactionType
import com.kit.wallet.data.repository.WalletCurrency
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
    expectedCurrency: WalletCurrency,
): String? {
    if (!transaction.hasVerifiedCustomerPresentation(expectedCurrency)) return null
    if (!transaction.rawDirection.equals("debit", ignoreCase = true)) return null
    return buildString {
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
        appendLine(
            "Status: ${transaction.status.name.lowercase().replaceFirstChar { it.titlecase() }}",
        )
        appendLine("Reference: ${transaction.reference}")
        append("Date: ${transaction.dateGroup}, ${transaction.time}")
    }
}

/** Final fail-closed gate shared by direct receipt and transaction-detail routes. */
internal fun Transaction.hasVerifiedCustomerPresentation(
    expectedCurrency: WalletCurrency,
): Boolean {
    if (!customerProjectionVerified) return false
    if (rawType?.isCustomerVisibleWalletTransactionType() != true) return false
    val direction = rawDirection?.trim()?.lowercase()
    if (direction != "credit" && direction != "debit") return false
    if (amountMinor == 0L || (direction == "credit") != (amountMinor > 0L)) return false
    if (walletId.isNullOrBlank() || walletId != expectedCurrency.walletId) return false
    if (!currencyCode.equals(expectedCurrency.code, ignoreCase = true)) return false
    return currencyScale == expectedCurrency.scale && currencyScale in 0..9
}
