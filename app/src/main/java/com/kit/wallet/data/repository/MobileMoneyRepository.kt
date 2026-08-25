package com.kit.wallet.data.repository

import com.kit.wallet.ui.model.MobileMoneyAccount
import com.kit.wallet.ui.model.MobileMoneyNetwork
import com.kit.wallet.ui.model.MobileMoneyOperation
import com.kit.wallet.ui.model.MobileMoneyVerificationState
import kotlinx.coroutines.flow.StateFlow

interface MobileMoneyRepository {
    val networks: StateFlow<List<MobileMoneyNetwork>>
    val accounts: StateFlow<List<MobileMoneyAccount>>
    val operations: StateFlow<List<MobileMoneyOperation>>
    val verification: StateFlow<MobileMoneyVerificationState?>

    suspend fun refresh()

    suspend fun verifyAndSaveAccount(
        networkCode: String,
        phoneNumber: String,
        label: String,
        kind: String,
    )

    suspend fun createOperation(
        action: String,
        accountId: String,
        amountMinor: Long,
        paymentPin: String,
        feeMode: String = if (action == "collection") "inclusive" else "sender_absorbs",
    )
    suspend fun previewOperation(
        action: String,
        accountId: String,
        amountMinor: Long,
        feeMode: String = if (action == "collection") "inclusive" else "sender_absorbs",
    ): FinancialOperationQuote
    /**
     * Submits an approved quote and returns the operation it created.
     *
     * The id is returned rather than dropped because a caller that is waiting on this money — a
     * top-up covering a payment the wallet cannot yet afford — has to be able to tell *this*
     * operation failing from any other one in the list moving.
     */
    suspend fun submitOperation(quote: FinancialOperationQuote, paymentPin: String): String
}
