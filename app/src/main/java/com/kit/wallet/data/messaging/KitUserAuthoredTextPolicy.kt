package com.kit.wallet.data.messaging

/** Prevents typed or externally shared text from impersonating a structured messaging event. */
internal object KitUserAuthoredTextPolicy {
    fun allows(text: String): Boolean {
        val normalized = text.trim()
        return normalized.isNotEmpty() &&
            KitPaymentMessage.allowsUserAuthoredText(normalized) &&
            !KitGroupPaymentMessage.beginsWithReservedPrefix(normalized) &&
            // The whole KITMEDIA namespace, every generation, detected with the contract's exact
            // six-codepoint edge set rather than a platform trim: the two disagree on characters
            // like U+2028, and the composer must refuse exactly what receivers treat as reserved.
            !KitMediaFamily.isFamilyText(text) &&
            !KitReactionMessage.beginsWithReservedPrefix(normalized) &&
            !KitEditMessage.beginsWithReservedPrefix(normalized)
    }
}
