package com.kit.wallet.di

import com.kit.wallet.data.messaging.ScheduledSendAlarm
import com.kit.wallet.data.messaging.ScheduledSendGateway
import com.kit.wallet.data.repository.DefaultScheduledSendGateway
import com.kit.wallet.worker.ScheduledSendScheduler
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Wires the send-later queue.
 *
 * Kept apart from the messaging and work modules on purpose: the queue reaches both ways — down to
 * the encrypted state store it persists in, and out to WorkManager for the wake — and this is the
 * one place where those two halves meet.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class ScheduledSendModule {
    @Binds
    @Singleton
    abstract fun bindScheduledSendGateway(
        implementation: DefaultScheduledSendGateway,
    ): ScheduledSendGateway

    @Binds
    @Singleton
    abstract fun bindScheduledSendAlarm(
        implementation: ScheduledSendScheduler,
    ): ScheduledSendAlarm
}
