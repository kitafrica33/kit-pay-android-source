package com.kit.wallet.di

import com.kit.wallet.data.messaging.ErasingSecureMessagingCurrentActivationRevocation
import com.kit.wallet.data.messaging.SecureMessagingCurrentActivationRevocation
import com.kit.wallet.data.messaging.SecureMessagingIncomingNotificationSink
import com.kit.wallet.data.notifications.AuthenticatedMessageNotificationSink
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal abstract class SecureMessagingNotificationModule {
    @Binds
    @Singleton
    abstract fun bindSecureMessagingIncomingNotificationSink(
        implementation: AuthenticatedMessageNotificationSink,
    ): SecureMessagingIncomingNotificationSink

    @Binds
    @Singleton
    abstract fun bindSecureMessagingCurrentActivationRevocation(
        implementation: ErasingSecureMessagingCurrentActivationRevocation,
    ): SecureMessagingCurrentActivationRevocation
}
