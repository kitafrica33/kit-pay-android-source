package com.kit.wallet

import com.kit.wallet.data.local.WalletTransactionEntity
import com.kit.wallet.data.mapper.toUiModel
import com.kit.wallet.feature.wallet.receiptShareText
import com.kit.wallet.feature.wallet.receiveDetailsShareText
import com.kit.wallet.ui.model.Transaction
import com.kit.wallet.ui.model.TxStatus
import com.kit.wallet.ui.model.TxType
import com.kit.wallet.ui.model.UserProfile
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class WalletShareTextTest {
    @Test
    fun `receive details contain only usable Kit identifiers`() {
        val profile = UserProfile(
            name = " Amina ",
            phone = "0700000002",
            tag = "@amina",
            kycLabel = "Verified",
        )

        assertEquals(
            "Pay Amina on Kit Pay using @amina or 0700000002.",
            receiveDetailsShareText(profile),
        )
    }

    @Test
    fun `receipt text reports the server transaction reference and status`() {
        val transaction = Transaction(
            id = "tx-1",
            counterparty = "Amina",
            note = null,
            amountMinor = -250_000,
            time = "14:05",
            dateGroup = "Today",
            type = TxType.SEND,
            status = TxStatus.PENDING,
            reference = "KIT-123",
        )

        assertEquals(
            "Kit Pay receipt\n" +
                "Money deducted: UGX 2,500\n" +
                "To: Amina\n" +
                "Status: Pending\n" +
                "Reference: KIT-123\n" +
                "Date: Today, 14:05",
            receiptShareText("Amina", transaction),
        )
    }

    @Test
    fun `receipt reports the combined customer debit in the transaction currency`() {
        val transaction = Transaction(
            id = "tx-bank",
            counterparty = "Amina",
            note = null,
            amountMinor = -20_000,
            time = "14:05",
            dateGroup = "Today",
            type = TxType.BANK_OUT,
            status = TxStatus.COMPLETED,
            reference = "BANK-123",
            currencyCode = "KES",
            currencyScale = 0,
            customerDebitMinor = 21_000,
        )

        assertEquals(
            "Kit Pay receipt\n" +
                "Money deducted: KES 21,000\n" +
                "To: Amina\n" +
                "Status: Completed\n" +
                "Reference: BANK-123\n" +
                "Date: Today, 14:05",
            receiptShareText("Amina", transaction),
        )
    }

    @Test
    fun `receipt cannot expose a stale institutional counterparty from room`() {
        val cached = WalletTransactionEntity(
            id = "tx-stale-bank",
            walletUuid = "wallet-1",
            reference = "BANK-PRIVATE",
            amountMinor = -56_000,
            currencyCode = "UGX",
            currencyScale = 0,
            type = "bank_transfer",
            direction = "debit",
            status = "completed",
            counterpartyName = "Kit institutional commission",
            counterpartyUserId = "service-user-id",
            counterpartyAvatarUrl = "https://pay.kit.africa/media/service-wallet",
            counterpartyVerificationDesignation = "verified",
            counterpartyVerificationSince = "2026-08-29T10:11:12Z",
            note = null,
            occurredAtEpochMillis = 1_788_000_000_000,
            customerProjectionVerified = true,
        )

        assertEquals(
            "Kit Pay receipt\n" +
                "Money deducted: UGX 56,000\n" +
                "To: Kit Pay\n" +
                "Status: Completed\n" +
                "Reference: BANK-PRIVATE\n" +
                "Date: Today, 10:40 AM",
            receiptShareText(
                null,
                cached.toUiModel(
                    now = Instant.parse("2026-08-29T12:00:00Z"),
                    zoneId = ZoneOffset.UTC,
                ),
            ),
        )
    }
}
