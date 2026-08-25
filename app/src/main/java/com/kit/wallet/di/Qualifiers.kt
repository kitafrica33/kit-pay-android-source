package com.kit.wallet.di

import javax.inject.Qualifier

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class RefreshHttpClient

/**
 * A client for Google, carrying nothing of Kit Pay's.
 *
 * The default client attaches a Kit Pay session bearer to every request it makes. Sending that to
 * accounts.google.com would hand a third party a credential to Kit Pay accounts, so Drive backup
 * requests are made on their own client with no session interceptor and no authenticator.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class GoogleHttpClient
