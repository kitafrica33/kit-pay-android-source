package com.kit.wallet.data.messaging

/**
 * The exact server contract implemented by the reviewed direct-message E2EE client.
 *
 * Matching this metadata is necessary but never sufficient for messaging readiness. The
 * runtime activation still requires the server readiness advertisement and a message-ready,
 * authentication-epoch-fenced local session.
 */
object SecureMessagingContract {
    const val VERSION = "v2"
    const val SUITE = "signal-pqxdh-kyber1024-double-ratchet-v2"
    const val POST_QUANTUM = true

    fun matchesServerAdvertisement(
        ready: Boolean,
        version: String?,
        suite: String?,
        postQuantum: Boolean?,
    ): Boolean = ready &&
        version == VERSION &&
        suite == SUITE &&
        postQuantum == POST_QUANTUM
}
