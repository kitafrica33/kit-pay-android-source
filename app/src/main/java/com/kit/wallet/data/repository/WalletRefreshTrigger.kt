package com.kit.wallet.data.repository

/** Enqueues a durable refresh after authentication without coupling auth to WorkManager. */
interface WalletRefreshTrigger {
    fun refreshNow()
}
