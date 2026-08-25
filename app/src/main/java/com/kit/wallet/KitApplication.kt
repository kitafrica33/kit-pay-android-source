package com.kit.wallet

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.kit.wallet.data.backup.MessageBackupService
import com.kit.wallet.data.media.ProfileAvatarImages
import com.kit.wallet.data.notifications.PushMessagingTransport
import com.kit.wallet.data.notifications.PushTokenCoordinator
import com.kit.wallet.data.realtime.KitRealtimeCoordinator
import com.kit.wallet.feature.calls.KitTelecomBridge
import com.kit.wallet.worker.WalletRefreshScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/** Production entry point for Hilt, WorkManager, push and wallet refresh scheduling. */
@HiltAndroidApp
class KitApplication : Application(), Configuration.Provider, ImageLoaderFactory {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var walletRefreshScheduler: dagger.Lazy<WalletRefreshScheduler>
    @Inject lateinit var pushMessagingTransport: dagger.Lazy<PushMessagingTransport>
    @Inject lateinit var pushTokens: dagger.Lazy<PushTokenCoordinator>
    @Inject lateinit var telecomBridge: dagger.Lazy<KitTelecomBridge>
    @Inject internal lateinit var realtime: dagger.Lazy<KitRealtimeCoordinator>
    @Inject lateinit var messageBackups: dagger.Lazy<MessageBackupService>

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    /**
     * Built the first time a photo is actually requested, not here, so nothing about profile
     * pictures is on the cold-start path.
     */
    override fun newImageLoader(): ImageLoader = ProfileAvatarImages.newImageLoader(this)

    override fun onCreate() {
        super.onCreate()
        pushMessagingTransport.get().initialize()
        telecomBridge.get().registerPhoneAccount()
        walletRefreshScheduler.get().schedule()
        // WorkManager's queue does not survive a reinstall or a "clear data", so the chosen
        // backup schedule is re-booked here rather than only when the settings screen is opened.
        messageBackups.get().restoreSchedule()
        pushTokens.get().start()
        // Registers the lifecycle and connectivity monitors and returns. No socket
        // is opened until the app is foregrounded, a session exists, and the server
        // actually advertises the transport.
        realtime.get().start()
    }
}
