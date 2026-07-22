package com.kit.wallet

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.kit.wallet.data.notifications.PushMessagingTransport
import com.kit.wallet.data.notifications.PushTokenCoordinator
import com.kit.wallet.feature.calls.KitTelecomBridge
import com.kit.wallet.worker.WalletRefreshScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/** Production entry point for Hilt, WorkManager, push and wallet refresh scheduling. */
@HiltAndroidApp
class KitApplication : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var walletRefreshScheduler: dagger.Lazy<WalletRefreshScheduler>
    @Inject lateinit var pushMessagingTransport: dagger.Lazy<PushMessagingTransport>
    @Inject lateinit var pushTokens: dagger.Lazy<PushTokenCoordinator>
    @Inject lateinit var telecomBridge: dagger.Lazy<KitTelecomBridge>

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        pushMessagingTransport.get().initialize()
        telecomBridge.get().registerPhoneAccount()
        walletRefreshScheduler.get().schedule()
        pushTokens.get().start()
    }
}
