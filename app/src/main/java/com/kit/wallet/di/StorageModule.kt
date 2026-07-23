package com.kit.wallet.di

import android.content.Context
import androidx.room.Room
import com.kit.wallet.data.local.KitWalletDatabase
import com.kit.wallet.data.local.ProfileDao
import com.kit.wallet.data.local.SecureMessagingMetadataDao
import com.kit.wallet.data.local.SyncStateDao
import com.kit.wallet.data.local.WalletDao
import com.kit.wallet.data.local.WalletTransactionDao
import com.kit.wallet.data.session.KeystoreSessionStore
import com.kit.wallet.data.session.SessionStore
import com.kit.wallet.data.messaging.AndroidKeystoreMessagingRecordCipher
import com.kit.wallet.data.messaging.AccountMessageArchiveCipher
import com.kit.wallet.data.messaging.AccountMessageArchiveStore
import com.kit.wallet.data.messaging.AccountMessageHistoryAccess
import com.kit.wallet.data.messaging.AccountMessageHistoryArchive
import com.kit.wallet.data.messaging.AndroidKeystoreAccountMessageArchiveCipher
import com.kit.wallet.data.messaging.LibSignalSecureMessagingCryptoEngine
import com.kit.wallet.data.messaging.LibSignalSecureMessagingKeyActivation
import com.kit.wallet.data.messaging.RealSecureMessagingInitialSyncActivation
import com.kit.wallet.data.messaging.RoomSecureMessagingStateStore
import com.kit.wallet.data.messaging.RoomAccountMessageArchiveStore
import com.kit.wallet.data.messaging.SecureMessagingCryptoEngine
import com.kit.wallet.data.messaging.SecureMessagingInitialSyncActivation
import com.kit.wallet.data.messaging.SecureMessagingKeyActivation
import com.kit.wallet.data.messaging.SecureMessagingLegacyStateValidator
import com.kit.wallet.data.messaging.SecureMessagingRecordCipher
import com.kit.wallet.data.messaging.SecureMessagingStateEraser
import com.kit.wallet.data.messaging.SecureMessagingStateStore
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Module
@InstallIn(SingletonComponent::class)
abstract class SessionModule {
    @Binds
    @Singleton
    abstract fun bindSessionStore(implementation: KeystoreSessionStore): SessionStore
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class SecureMessagingStorageModule {
    @Binds
    @Singleton
    abstract fun bindAccountMessageHistoryAccess(
        implementation: AccountMessageHistoryArchive,
    ): AccountMessageHistoryAccess

    @Binds
    @Singleton
    abstract fun bindAccountMessageArchiveCipher(
        implementation: AndroidKeystoreAccountMessageArchiveCipher,
    ): AccountMessageArchiveCipher

    @Binds
    @Singleton
    abstract fun bindAccountMessageArchiveStore(
        implementation: RoomAccountMessageArchiveStore,
    ): AccountMessageArchiveStore

    @Binds
    @Singleton
    abstract fun bindSecureMessagingRecordCipher(
        implementation: AndroidKeystoreMessagingRecordCipher,
    ): SecureMessagingRecordCipher

    @Binds
    @Singleton
    abstract fun bindSecureMessagingStateStore(
        implementation: RoomSecureMessagingStateStore,
    ): SecureMessagingStateStore

    @Binds
    @Singleton
    abstract fun bindSecureMessagingLegacyStateValidator(
        implementation: RoomSecureMessagingStateStore,
    ): SecureMessagingLegacyStateValidator

    @Binds
    @Singleton
    abstract fun bindSecureMessagingCryptoEngine(
        implementation: LibSignalSecureMessagingCryptoEngine,
    ): SecureMessagingCryptoEngine

    @Binds
    @Singleton
    abstract fun bindSecureMessagingKeyActivation(
        implementation: LibSignalSecureMessagingKeyActivation,
    ): SecureMessagingKeyActivation

    @Binds
    @Singleton
    abstract fun bindSecureMessagingInitialSyncActivation(
        implementation: RealSecureMessagingInitialSyncActivation,
    ): SecureMessagingInitialSyncActivation
}

@Module
@InstallIn(SingletonComponent::class)
object StorageModule {
    @Provides
    @Singleton
    fun provideMoshi(): Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): KitWalletDatabase =
        Room.databaseBuilder(context, KitWalletDatabase::class.java, "kit-wallet.db")
            .addMigrations(
                KitWalletDatabase.MIGRATION_1_2,
                KitWalletDatabase.MIGRATION_2_3,
                KitWalletDatabase.MIGRATION_3_4,
                KitWalletDatabase.MIGRATION_4_5,
                KitWalletDatabase.MIGRATION_5_6,
            )
            .fallbackToDestructiveMigrationOnDowngrade()
            .build()

    @Provides
    fun provideProfileDao(database: KitWalletDatabase): ProfileDao = database.profileDao()

    @Provides
    fun provideWalletDao(database: KitWalletDatabase): WalletDao = database.walletDao()

    @Provides
    fun provideWalletTransactionDao(database: KitWalletDatabase): WalletTransactionDao =
        database.walletTransactionDao()

    @Provides
    fun provideSyncStateDao(database: KitWalletDatabase): SyncStateDao = database.syncStateDao()

    @Provides
    fun provideSecureMessagingMetadataDao(
        database: KitWalletDatabase,
    ): SecureMessagingMetadataDao = database.secureMessagingMetadataDao()

    @Provides
    @Singleton
    fun provideSecureMessagingStateEraser(
        store: SecureMessagingStateStore,
    ): SecureMessagingStateEraser = store

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
