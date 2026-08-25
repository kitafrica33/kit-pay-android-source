package com.kit.wallet.ui.model

import com.kit.wallet.data.repository.isCanonicalBeneficiaryPhoneIdentity

/**
 * Whose face, if anyone's, belongs beside a saved payout destination.
 *
 * The server's explicit Kit account remains authoritative. A device-local fallback is permitted
 * only for a destination saved under this authenticated owner and only by comparing keyed digests
 * of full canonical international numbers. Masked numbers and final-digit suffixes never enter the
 * decision, so identical subscriber digits in different countries cannot select the wrong person.
 * This decides presentation only; it never routes or authorizes money.
 */
object BeneficiaryIdentity {
    /**
     * The one Kit Pay contact matching [savedPhoneIdentity], or null for malformed/ambiguous data.
     * Duplicate address-book rows for the same non-empty account id collapse before ambiguity is
     * considered.
     */
    fun contactFor(
        savedPhoneIdentity: String?,
        contacts: List<Contact>,
        phoneIdentityOf: (String?) -> String?,
    ): Contact? {
        val expected = savedPhoneIdentity
            ?.takeIf(::isCanonicalBeneficiaryPhoneIdentity)
            ?: return null
        return contacts.asSequence()
            .filter { it.isKitUser && it.id.isNotBlank() }
            .filter { phoneIdentityOf(it.phone) == expected }
            .distinctBy { it.id.trim().lowercase() }
            .singleOrNull()
    }

    fun avatarUrlFor(
        kitUserId: String? = null,
        serverAvatarUrl: String? = null,
        savedPhoneIdentity: String? = null,
        contacts: List<Contact> = emptyList(),
        knownPhotos: Map<String, String> = emptyMap(),
        phoneIdentityOf: (String?) -> String? = { null },
    ): String? {
        serverAvatarUrl?.trim()?.takeIf(String::isNotEmpty)?.let { return it }
        cachedPhoto(kitUserId, knownPhotos)?.let { return it }

        val contact = contactFor(savedPhoneIdentity, contacts, phoneIdentityOf) ?: return null
        contact.avatarUrl?.trim()?.takeIf(String::isNotEmpty)?.let { return it }
        return cachedPhoto(contact.id, knownPhotos)
    }

    private fun cachedPhoto(userId: String?, knownPhotos: Map<String, String>): String? = userId
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.let { knownPhotos[it.lowercase()] }
        ?.takeIf(String::isNotEmpty)
}
