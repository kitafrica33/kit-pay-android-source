package com.kit.wallet

import com.kit.wallet.data.repository.BankDeposit
import com.kit.wallet.data.repository.BankFundingAccount
import com.kit.wallet.data.repository.isValidBankDepositReference
import com.kit.wallet.feature.bank.bankDepositInstructionsText
import com.kit.wallet.feature.bank.sanitizeAmountInput
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BankDepositFlowTest {
    @Test
    fun `deposit reference is exactly four uppercase mixed groups`() {
        assertTrue(isValidBankDepositReference("K7P2-9QMX-4R8C-T6WA"))

        assertFalse(isValidBankDepositReference("K7P2-9QMX-4R8C"))
        assertFalse(isValidBankDepositReference("k7P2-9QMX-4R8C-T6WA"))
        assertFalse(isValidBankDepositReference("ABCD-EFGH-IJKL-MNOP"))
        assertFalse(isValidBankDepositReference("1234-5678-9012-3456"))
        assertFalse(isValidBankDepositReference("K7P2_9QMX_4R8C_T6WA"))
    }

    @Test
    fun `amount input strips separators and rejects excess precision`() {
        assertEquals("1856.84", sanitizeAmountInput("1,856.849", scale = 2))
        assertEquals("1768.8", sanitizeAmountInput("1 768.8", scale = 2))
        assertEquals("5000000", sanitizeAmountInput("5,000,000.00", scale = 0))
    }

    @Test
    fun `copyable instructions contain receiving details but never a beneficiary`() {
        val text = bankDepositInstructionsText(deposit())

        assertTrue(text.contains("UGX 1,856.8"))
        assertTrue(text.contains("K7P2-9QMX-4R8C-T6WA"))
        assertTrue(text.contains("Kit Pos Uganda Limited"))
        assertTrue(text.contains("0123456789"))
        assertTrue(text.contains("Kampala Road"))
        assertTrue(text.contains("SWIFT / BIC: KITUUGKA"))
        assertFalse(text.contains("beneficiary", ignoreCase = true))
    }

    @Test
    fun `deposit and pending identity journeys are full screen rather than modal`() {
        val depositSource = source(
            "app/src/main/java/com/kit/wallet/feature/bank/BankDepositScreen.kt",
        )
        val unlockSource = source(
            "app/src/main/java/com/kit/wallet/feature/auth/SessionUnlockGate.kt",
        )

        assertTrue(depositSource.contains("internal fun BankDepositScreen("))
        assertTrue(depositSource.contains("Modifier\n                .fillMaxSize()"))
        assertFalse(depositSource.contains("ModalBottomSheet"))
        assertTrue(unlockSource.contains("Surface(Modifier.fillMaxSize()"))
        assertFalse(unlockSource.contains("Dialog("))
    }

    private fun deposit() = BankDeposit(
        id = "29daa91c-01cb-46c6-a02a-d848db2ddf65",
        reference = "K7P2-9QMX-4R8C-T6WA",
        walletId = "wallet-1",
        amountMinor = 185_680,
        currencyCode = "UGX",
        currencyScale = 2,
        status = "awaiting_proof",
        fundingAccount = BankFundingAccount(
            id = "93180d38-8551-4e72-84f6-62bfd43a8ccc",
            label = "Main collections",
            bankId = "bank-1",
            bankName = "Kit Bank Uganda",
            accountName = "Kit Pos Uganda Limited",
            accountNumber = "0123456789",
            accountNumberMasked = "•••• 6789",
            branchName = "Kampala Road",
            branchCode = "001",
            swiftCode = "KITUUGKA",
            instructions = "Use the generated reference.",
            currencyCode = "UGX",
            active = true,
        ),
        proof = null,
        bankTransactionReference = null,
        customerNote = null,
        rejectionReason = null,
        expiresAt = "2030-08-26T12:00:00Z",
        createdAt = "2026-08-26T12:00:00Z",
        completedAt = null,
    )

    private fun source(relativePath: String): String = File(repositoryRoot(), relativePath).readText()

    private fun repositoryRoot(): File {
        val workingDirectory = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        return generateSequence(workingDirectory) { it.parentFile }
            .firstOrNull { File(it, "app/build.gradle.kts").isFile }
            ?: error("Could not locate the Android repository root")
    }
}
