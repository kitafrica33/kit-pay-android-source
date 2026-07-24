package com.kit.wallet.di

import com.kit.wallet.data.auth.AndroidDeviceIdentityProvider
import com.kit.wallet.data.auth.AuthRepository
import com.kit.wallet.data.auth.DeviceIdentityProvider
import com.kit.wallet.data.auth.RemoteAuthRepository
import com.kit.wallet.data.repository.BillsRepository
import com.kit.wallet.data.repository.BankingRepository
import com.kit.wallet.data.repository.CallRepository
import com.kit.wallet.data.repository.ChatRepository
import com.kit.wallet.data.repository.CommunicationPrivacyRepository
import com.kit.wallet.data.repository.EncryptedChatRepository
import com.kit.wallet.data.repository.ContactRepository
import com.kit.wallet.data.repository.OfflineUserRepository
import com.kit.wallet.data.repository.OfflineWalletRepository
import com.kit.wallet.data.repository.OfflineWalletSyncRepository
import com.kit.wallet.data.repository.ProviderCatalogRepository
import com.kit.wallet.data.repository.RemoteCallRepository
import com.kit.wallet.data.repository.RemoteCommunicationPrivacyRepository
import com.kit.wallet.data.repository.RemoteContactRepository
import com.kit.wallet.data.repository.RemoteBankingRepository
import com.kit.wallet.data.repository.MobileMoneyRepository
import com.kit.wallet.data.repository.RemoteMobileMoneyRepository
import com.kit.wallet.data.repository.KycRepository
import com.kit.wallet.data.repository.RemoteKycRepository
import com.kit.wallet.data.repository.UserRepository
import com.kit.wallet.data.repository.WalletRepository
import com.kit.wallet.data.repository.WalletSyncRepository
import com.kit.wallet.data.repository.WalletRefreshTrigger
import com.kit.wallet.data.messaging.SecureMessagingSyncEngine
import com.kit.wallet.data.messaging.RealSecureMessagingSyncEngine
import com.kit.wallet.data.messaging.AccountMessageHistoryCoordinator
import com.kit.wallet.data.messaging.AccountMessageHistoryRetention
import com.kit.wallet.feature.chat.AndroidMessageSoundPlayer
import com.kit.wallet.feature.chat.MessageSoundPlayer
import com.kit.wallet.data.local.RoomWalletCache
import com.kit.wallet.data.local.WalletCache
import com.kit.wallet.data.time.AndroidElapsedRealtimeClock
import com.kit.wallet.data.time.AndroidBootSessionIdProvider
import com.kit.wallet.data.time.BootSessionIdProvider
import com.kit.wallet.data.time.ElapsedRealtimeClock
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import com.kit.wallet.worker.WalletRefreshScheduler
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryBindingsModule {
    @Binds
    @Singleton
    abstract fun bindAuthRepository(implementation: RemoteAuthRepository): AuthRepository

    @Binds
    @Singleton
    abstract fun bindDeviceIdentityProvider(
        implementation: AndroidDeviceIdentityProvider,
    ): DeviceIdentityProvider

    @Binds
    @Singleton
    abstract fun bindElapsedRealtimeClock(
        implementation: AndroidElapsedRealtimeClock,
    ): ElapsedRealtimeClock

    @Binds
    @Singleton
    abstract fun bindBootSessionIdProvider(
        implementation: AndroidBootSessionIdProvider,
    ): BootSessionIdProvider

    @Binds
    @Singleton
    abstract fun bindUserRepository(implementation: OfflineUserRepository): UserRepository

    @Binds
    @Singleton
    abstract fun bindWalletRepository(implementation: OfflineWalletRepository): WalletRepository

    @Binds
    @Singleton
    abstract fun bindWalletSyncRepository(
        implementation: OfflineWalletSyncRepository,
    ): WalletSyncRepository

    @Binds
    @Singleton
    abstract fun bindWalletCache(implementation: RoomWalletCache): WalletCache

    @Binds
    @Singleton
    abstract fun bindWalletRefreshTrigger(
        implementation: WalletRefreshScheduler,
    ): WalletRefreshTrigger

    @Binds
    @Singleton
    abstract fun bindContactRepository(implementation: RemoteContactRepository): ContactRepository

    @Binds
    @Singleton
    abstract fun bindChatRepository(
        implementation: EncryptedChatRepository,
    ): ChatRepository

    @Binds
    @Singleton
    abstract fun bindSecureMessagingSyncEngine(
        implementation: RealSecureMessagingSyncEngine,
    ): SecureMessagingSyncEngine

    @Binds
    @Singleton
    abstract fun bindAccountMessageHistoryRetention(
        implementation: AccountMessageHistoryCoordinator,
    ): AccountMessageHistoryRetention

    @Binds
    @Singleton
    abstract fun bindCallRepository(implementation: RemoteCallRepository): CallRepository

    @Binds
    @Singleton
    abstract fun bindCommunicationPrivacyRepository(
        implementation: RemoteCommunicationPrivacyRepository,
    ): CommunicationPrivacyRepository

    @Binds
    @Singleton
    abstract fun bindBillsRepository(implementation: ProviderCatalogRepository): BillsRepository

    @Binds
    @Singleton
    abstract fun bindBankingRepository(implementation: RemoteBankingRepository): BankingRepository

    @Binds
    @Singleton
    abstract fun bindMobileMoneyRepository(
        implementation: RemoteMobileMoneyRepository,
    ): MobileMoneyRepository

    @Binds
    @Singleton
    abstract fun bindKycRepository(implementation: RemoteKycRepository): KycRepository

    @Binds
    @Singleton
    abstract fun bindMessageSoundPlayer(
        implementation: AndroidMessageSoundPlayer,
    ): MessageSoundPlayer
}

@Module
@InstallIn(SingletonComponent::class)
object RepositorySupportModule {
    @Provides
    @Singleton
    fun provideClock(): Clock = Clock.systemUTC()
}
