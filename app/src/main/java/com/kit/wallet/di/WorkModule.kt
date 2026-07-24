package com.kit.wallet.di

import android.content.Context
import androidx.work.WorkManager
import com.kit.wallet.data.messaging.SecureMessagingHistoryContinuationScheduler
import com.kit.wallet.worker.SecureMessagingSyncScheduler
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object WorkModule {
    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager =
        WorkManager.getInstance(context)

    @Provides
    @Singleton
    fun provideSecureMessagingHistoryContinuationScheduler(
        scheduler: SecureMessagingSyncScheduler,
    ): SecureMessagingHistoryContinuationScheduler =
        SecureMessagingHistoryContinuationScheduler(scheduler::scheduleHistoryContinuation)
}
