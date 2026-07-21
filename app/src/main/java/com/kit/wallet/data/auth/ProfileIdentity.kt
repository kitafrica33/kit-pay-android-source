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

internal fun profileIdentityValidationError(name: String, tag: String): String? {
    val normalizedName = normalizeProfileName(name)
    val normalizedTag = normalizeProfileTag(tag)
    val nameLength = normalizedName.codePointCount(0, normalizedName.length)
    val tagLength = normalizedTag.codePointCount(0, normalizedTag.length)
    return when {
        nameLength !in 2..120 ->
            "Enter a username / display name (2–120 characters)."
        isPlaceholderProfileName(normalizedName) ->
            "Choose the username / display name people should see."
        tagLength !in 3..32 -> "Your Kit Pay tag must be 3 to 32 characters."
        isProvisionalProfileTag(normalizedTag) -> "Choose your own Kit Pay tag."
        normalizedTag.startsWith("deleted_") || normalizedTag in RESERVED_PROFILE_TAGS ->
            "This Kit Pay tag is reserved."
        !PROFILE_TAG_PATTERN.matches(normalizedTag) ->
            "Use only lowercase letters, numbers, and underscores in your Kit Pay tag."
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
