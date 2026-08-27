package com.kit.wallet.di

import com.kit.wallet.feature.chat.CacheSharedInboxAccess
import com.kit.wallet.feature.chat.SharedInboxAccess
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal abstract class SharedInboxModule {
    @Binds
    @Singleton
    abstract fun bindSharedInboxAccess(
        implementation: CacheSharedInboxAccess,
    ): SharedInboxAccess
}
