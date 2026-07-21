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
    )
}
