package com.kit.wallet.data.auth

private val PROFILE_TAG_PATTERN = Regex("^[a-z0-9_]{3,32}$")

private val RESERVED_PROFILE_TAGS = setOf(
    "admin",
    "administrator",
    "api",
    "help",
    "kit",
    "kit_africa",
    "kit_pay",
    "kitafrica",
    "kitpay",
    "moderator",
    "official",
    "pay",
    "root",
    "security",
    "staff",
    "support",
    "system",
)

internal fun normalizeProfileName(value: String): String = buildString(value.length) {
    var separatorPending = false
    value.forEach { character ->
        if (character.isProfileWhitespace()) {
            separatorPending = isNotEmpty()
        } else {
            if (separatorPending) append(' ')
            append(character)
            separatorPending = false
        }
    }
}

internal fun profileNameOrPlaceholder(value: String?): String =
    normalizeProfileName(value.orEmpty()).ifBlank { "Kit Pay User" }

internal fun normalizeProfileTag(value: String): String =
    value.trim(Char::isProfileWhitespace).removePrefix("@").lowercase()

/** Whether a verified identity document has given this account a name it can be known by. */
internal fun hasVerifiedLegalName(legalName: String?): Boolean =
    normalizeProfileName(legalName.orEmpty()).isNotBlank()

/**
 * Validates the *chosen* half of an identity: a display name and a username. Neither is the legal
 * name, which only identity verification can set.
 *
 * Once [legalName] holds a verified name the account already has a name people and financial
 * screens can rely on, so both chosen fields become optional — leave them empty and keep only the
 * verified name. Whatever is filled in is still validated, so an optional field can never be saved
 * half-formed. With no verified legal name both stay required: an account with no name at all is
 * not usable.
 *
 * This mirrors `ProfileCompletionService::hasChosenIdentity` on the server. The two have to agree,
 * or the app offers a Save button the API rejects — or worse, refuses one the API would accept and
 * leaves someone stuck in setup.
 */
internal fun profileIdentityValidationError(
    name: String,
    tag: String,
    legalName: String? = null,
): String? {
    val normalizedName = normalizeProfileName(name)
    val normalizedTag = normalizeProfileTag(tag)
    val nameLength = normalizedName.codePointCount(0, normalizedName.length)
    val tagLength = normalizedTag.codePointCount(0, normalizedTag.length)
    val verified = hasVerifiedLegalName(legalName)
    val nameOptional = verified &&
        (normalizedName.isBlank() || isPlaceholderProfileName(normalizedName))
    val tagOptional = verified &&
        (normalizedTag.isBlank() || isProvisionalProfileTag(normalizedTag))

    val nameError = when {
        nameOptional -> null
        nameLength !in 2..120 -> "Enter a display name (2–120 characters)."
        isPlaceholderProfileName(normalizedName) ->
            "Choose the display name people should see."
        else -> null
    }
    if (nameError != null) return nameError

    return when {
        tagOptional -> null
        tagLength !in 3..32 -> "Your username must be 3 to 32 characters."
        isProvisionalProfileTag(normalizedTag) -> "Choose your own username."
        normalizedTag.startsWith("deleted_") || normalizedTag in RESERVED_PROFILE_TAGS ->
            "This username is reserved."
        !PROFILE_TAG_PATTERN.matches(normalizedTag) ->
            "Use only lowercase letters, numbers, and underscores in your username."
        else -> null
    }
}

private fun Char.isProfileWhitespace(): Boolean {
    if (this in '\u0009'..'\u000D' || this == '\u0020' || this == '\u0085') return true
    return when (Character.getType(this)) {
        Character.SPACE_SEPARATOR.toInt(),
        Character.LINE_SEPARATOR.toInt(),
        Character.PARAGRAPH_SEPARATOR.toInt() -> true
        else -> false
    }
}
