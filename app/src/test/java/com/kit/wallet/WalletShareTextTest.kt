package com.kit.wallet

import com.kit.wallet.feature.wallet.receiptShareText
import com.kit.wallet.feature.wallet.receiveDetailsShareText
import com.kit.wallet.ui.model.Transaction
import com.kit.wallet.ui.model.TxStatus
import com.kit.wallet.ui.model.TxType
import com.kit.wallet.ui.model.UserProfile
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
                "Sent UGX 2,500 to Amina\n" +
                "Status: Pending\n" +
                "Reference: KIT-123\n" +
                "Date: Today, 14:05",
            receiptShareText("Amina", 250_000, transaction),
        )
    }
}
