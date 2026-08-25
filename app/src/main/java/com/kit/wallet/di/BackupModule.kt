package com.kit.wallet.di

import com.kit.wallet.data.backup.GoogleDriveEndpoints
import com.kit.wallet.data.backup.MessageBackupTrigger
import com.kit.wallet.worker.MessageBackupScheduler
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object BackupModule {
    @Provides
    @Singleton
    fun provideGoogleDriveEndpoints(): GoogleDriveEndpoints = GoogleDriveEndpoints()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BackupBindingModule {
    @Binds
    abstract fun bindMessageBackupTrigger(
        implementation: MessageBackupScheduler,
    ): MessageBackupTrigger
}
