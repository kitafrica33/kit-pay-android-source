package com.kit.wallet.data.messaging

/**
 * The exact six-codepoint boundary set of the media-v2 contract:
 * {U+0009, U+000A, U+000B, U+000C, U+000D, U+0020}.
 *
 * Captions and reserved-prefix detection are defined byte-exactly over this set on both
 * platforms, and no platform default matches it: Kotlin `trim()` strips every char up to and
 * including U+0020 (so also U+0000–U+0008 and U+000E–U+001F), and `isWhitespace()`-based trims
 * add Unicode spaces such as U+2028. Either would accept or reject different captions than iOS,
 * so media-v2 code performs every edge test and strip through this policy and nothing else.
 *
 * All six codepoints are single UTF-16 units and no surrogate is in the set, so char-wise
 * scanning is codepoint-exact here.
 */
internal object KitMediaWhitespacePolicy {
    fun isBoundary(ch: Char): Boolean = when (ch.code) {
        0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x20 -> true
        else -> false
    }

    /** Strips leading and trailing codepoints of the exact set; interior bytes stay as typed. */
    fun strip(text: String): String {
        var start = 0
        var end = text.length
        while (start < end && isBoundary(text[start])) start++
        while (end > start && isBoundary(text[end - 1])) end--
        return if (start == 0 && end == text.length) text else text.substring(start, end)
    }

    /** A receiver-side edge violation: the text begins or ends inside the set. */
    fun beginsOrEndsWithBoundary(text: String): Boolean =
        text.isNotEmpty() && (isBoundary(text.first()) || isBoundary(text.last()))

    /** At least one codepoint outside the set — what makes a caption more than spacing. */
    fun hasContentOutsideBoundarySet(text: String): Boolean = text.any { !isBoundary(it) }
}
