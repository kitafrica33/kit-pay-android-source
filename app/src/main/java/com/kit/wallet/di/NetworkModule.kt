package com.kit.wallet.di

import com.kit.wallet.BuildConfig
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.remote.SecureMessagingWireApi
import com.kit.wallet.data.remote.SessionAuthenticator
import com.kit.wallet.data.remote.SessionHeaderInterceptor
import com.kit.wallet.data.remote.SessionRefreshApi
import com.squareup.moshi.Moshi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.HttpUrl.Companion.toHttpUrl
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun provideBaseUrl(): HttpUrl = BuildConfig.KIT_WALLET_BASE_URL.toHttpUrl()

    @Provides
    @Singleton
    fun provideRequestMetadataInterceptor(): Interceptor = Interceptor { chain ->
        val request = chain.request().newBuilder()
            .header("Accept", "application/json")
            .header("X-Request-ID", UUID.randomUUID().toString())
            .header("X-Kit-Wallet-Client", "android/${BuildConfig.VERSION_NAME}")
            .build()
        chain.proceed(request)
    }

    @Provides
    @Singleton
    @RefreshHttpClient
    fun provideRefreshHttpClient(metadata: Interceptor): OkHttpClient =
        baseClient(metadata).build()

    @Provides
    @Singleton
    fun provideHttpClient(
        metadata: Interceptor,
        sessionHeaders: SessionHeaderInterceptor,
        sessionAuthenticator: SessionAuthenticator,
    ): OkHttpClient = baseClient(metadata)
        .addInterceptor(sessionHeaders)
        .authenticator(sessionAuthenticator)
        .build()

    @Provides
    @Singleton
    fun provideKitWalletApi(
        baseUrl: HttpUrl,
        moshi: Moshi,
        client: OkHttpClient,
    ): KitWalletApi = retrofit(baseUrl, moshi, client).create(KitWalletApi::class.java)

    @Provides
    @Singleton
    internal fun provideSecureMessagingWireApi(
        baseUrl: HttpUrl,
        moshi: Moshi,
        client: OkHttpClient,
    ): SecureMessagingWireApi =
        retrofit(baseUrl, moshi, client).create(SecureMessagingWireApi::class.java)

    @Provides
    @Singleton
    internal fun provideSessionRefreshApi(
        baseUrl: HttpUrl,
        moshi: Moshi,
        @RefreshHttpClient client: OkHttpClient,
    ): SessionRefreshApi = retrofit(baseUrl, moshi, client).create(SessionRefreshApi::class.java)

    private fun baseClient(metadata: Interceptor): OkHttpClient.Builder {
        val logging = HttpLoggingInterceptor().apply {
            // Never log bodies: authentication responses contain bearer credentials.
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC
            else HttpLoggingInterceptor.Level.NONE
        }
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(metadata)
            .addInterceptor(logging)
    }

    private fun retrofit(baseUrl: HttpUrl, moshi: Moshi, client: OkHttpClient): Retrofit =
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
}
