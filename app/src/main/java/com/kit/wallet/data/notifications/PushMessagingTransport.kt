package com.kit.wallet.data.notifications

/**
 * Provider-neutral source of the current device push registration.
 *
 * Implementations own provider SDK initialization and token acquisition. The provider value is
 * sent explicitly to Kit's API so changing transports cannot silently keep an FCM registration.
 */
interface PushMessagingTransport {
    val provider: String
    val configured: Boolean

    fun initialize()

    suspend fun currentToken(): String
}
