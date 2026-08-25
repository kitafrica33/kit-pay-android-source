package com.kit.wallet

import com.kit.wallet.data.repository.WalletSyncRepository
import com.kit.wallet.data.repository.WalletSyncResult

internal object NoOpTestWalletSync : WalletSyncRepository {
    override suspend fun refresh(): WalletSyncResult = WalletSyncResult(0, 0, false)

    override suspend fun clearCachedUserData(ownerScopeId: String?) = Unit
}

internal class RecordingTestWalletSync(
    private val authoritativeBalanceMinor: Long? = null,
    private val authoritativeCurrencyCode: String? = null,
    private val authoritativeCurrencyScale: Int? = null,
    private val onRefresh: suspend () -> Unit = {},
) : WalletSyncRepository {
    var refreshCalls: Int = 0
        private set

    override suspend fun refresh(): WalletSyncResult {
        refreshCalls += 1
        onRefresh()
        return WalletSyncResult(
            walletCount = 1,
            transactionCount = 0,
            hasMoreTransactions = false,
            selectedAvailableBalanceMinor = authoritativeBalanceMinor,
            selectedCurrencyCode = authoritativeCurrencyCode,
            selectedCurrencyScale = authoritativeCurrencyScale,
        )
    }

    override suspend fun clearCachedUserData(ownerScopeId: String?) = Unit
}
