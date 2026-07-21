package com.kit.wallet.di

import com.kit.wallet.data.notifications.DefaultPushEnvelopeReceiver
import com.kit.wallet.data.notifications.PushEnvelopeReceiver
import com.kit.wallet.data.notifications.PushMessagingTransport
import com.kit.wallet.data.notifications.fcm.FirebasePushMessagingTransport
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class PushMessagingModule {
    @Binds
    @Singleton
    abstract fun bindPushMessagingTransport(
        implementation: FirebasePushMessagingTransport,
    ): PushMessagingTransport

    @Binds
    @Singleton
    abstract fun bindPushEnvelopeReceiver(
        implementation: DefaultPushEnvelopeReceiver,
    ): PushEnvelopeReceiver
}
