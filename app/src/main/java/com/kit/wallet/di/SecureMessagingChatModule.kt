package com.kit.wallet.di

import com.kit.wallet.data.repository.DefaultSecureMessagingChatRuntime
import com.kit.wallet.data.repository.SecureMessagingChatRuntime
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Keeps the chat-runtime binding separate from the independently reviewed sync binding. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class SecureMessagingChatModule {
    @Binds
    @Singleton
    abstract fun bindSecureMessagingChatRuntime(
        implementation: DefaultSecureMessagingChatRuntime,
    ): SecureMessagingChatRuntime
}
