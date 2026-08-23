package com.kit.wallet.data.repository

/** Enqueues a durable refresh after authentication without coupling auth to WorkManager. */
fun interface WalletRefreshTrigger {
    fun refreshNow()
}
