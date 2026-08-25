package com.kit.wallet.di

import android.os.SystemClock
import com.kit.wallet.data.realtime.KitConversationSignals
import com.kit.wallet.data.realtime.KitForegroundSource
import com.kit.wallet.data.realtime.KitNetworkSource
import com.kit.wallet.data.realtime.KitRealtimeAuthApi
import com.kit.wallet.data.realtime.KitRealtimeClient
import com.kit.wallet.data.realtime.KitRealtimeClock
import com.kit.wallet.data.realtime.KitRealtimeCoordinator
import com.kit.wallet.data.realtime.KitRealtimeForegroundMonitor
import com.kit.wallet.data.realtime.KitRealtimeNetworkMonitor
import com.kit.wallet.data.realtime.KitRealtimeTransport
import com.kit.wallet.data.realtime.KitTypingSignaller
import com.kit.wallet.data.realtime.KitTypingSignals
import com.squareup.moshi.Moshi
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/**
 * Everything the realtime transport binds, in a file of its own.
 *
 * Kept separate from `NetworkModule` and the messaging modules deliberately: the
 * secure-messaging dependency gate reads five named files and asserts literal
 * binding strings in them, and the API-isolation test pins exactly which files may
 * name the secure-messaging wire API. Nothing here appears in either set, so the
 * socket cannot quietly widen a boundary that exists to keep ciphertext handling
 * narrow. That gate matches on file text, so naming the interface here — even in
 * this comment — would itself be the widening it is there to catch.
 */
@Module
@InstallIn(SingletonComponent::class)
object RealtimeModule {
    @Provides
    @Singleton
    internal fun provideRealtimeAuthApi(
        baseUrl: HttpUrl,
        moshi: Moshi,
        // The authenticated client, so `SessionHeaderInterceptor` and
        // `SessionAuthenticator`'s refresh apply with no token logic of our own.
        client: OkHttpClient,
    ): KitRealtimeAuthApi = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(KitRealtimeAuthApi::class.java)

    /**
     * Elapsed real time, not wall clock: every deadline in the realtime package is
     * a duration, and a user changing the device clock — or an NTP correction —
     * must not be able to skip a backoff or freeze a typing bubble.
     */
    @Provides
    @Singleton
    internal fun provideRealtimeClock(): KitRealtimeClock =
        KitRealtimeClock { SystemClock.elapsedRealtime() }
}

@Module
@InstallIn(SingletonComponent::class)
internal interface RealtimeBindingsModule {
    @Binds
    @Singleton
    fun bindRealtimeTransport(client: KitRealtimeClient): KitRealtimeTransport

    /**
     * The socket, the foreground signal and connectivity are all bound as
     * interfaces so the state machine can be driven end to end by a JVM unit test.
     * The two monitors are the only things in the package that need an
     * `Application` or a `Context`, and without these bindings every transition
     * they trigger would be reachable only from a device run.
     */
    @Binds
    @Singleton
    fun bindForegroundSource(monitor: KitRealtimeForegroundMonitor): KitForegroundSource

    @Binds
    @Singleton
    fun bindNetworkSource(monitor: KitRealtimeNetworkMonitor): KitNetworkSource

    /**
     * The two ports the chat screens inject. Narrower than the objects behind them
     * on purpose: a ViewModel gets presence, typing and the poll interval, and no
     * way to reach the connection itself.
     */
    @Binds
    @Singleton
    fun bindConversationSignals(coordinator: KitRealtimeCoordinator): KitConversationSignals

    @Binds
    @Singleton
    fun bindTypingSignals(signaller: KitTypingSignaller): KitTypingSignals
}
