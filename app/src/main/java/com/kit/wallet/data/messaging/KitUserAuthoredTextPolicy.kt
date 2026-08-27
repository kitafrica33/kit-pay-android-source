package com.kit.wallet.data.messaging

/** Prevents typed or externally shared text from impersonating a structured messaging event. */
internal object KitUserAuthoredTextPolicy {
    fun allows(text: String): Boolean {
        val normalized = text.trim()
        return normalized.isNotEmpty() &&
            KitPaymentMessage.allowsUserAuthoredText(normalized) &&
            !KitGroupPaymentMessage.beginsWithReservedPrefix(normalized) &&
            !KitMediaMessage.isMediaText(normalized) &&
            !KitReactionMessage.beginsWithReservedPrefix(normalized) &&
            !KitEditMessage.beginsWithReservedPrefix(normalized)
    }
}
