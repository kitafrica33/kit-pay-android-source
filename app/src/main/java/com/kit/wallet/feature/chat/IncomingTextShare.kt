package com.kit.wallet.feature.chat

import android.content.Intent
import java.util.UUID

/** A validated, short-lived share request. Shared text is never persisted by Kit Pay. */
sealed interface IncomingTextShare {
    data class Accepted(val text: String) : IncomingTextShare

    data class Rejected(val reason: String) : IncomingTextShare
}

data class IncomingTextShareRequest(
    val token: String,
    val payload: IncomingTextShare,
)

/**
 * Parses only bounded `text/plain` shares. Attachments and oversized values are rejected rather
 * than truncated so the review screen always represents exactly what the user chose to share.
 */
internal fun Intent.parseIncomingTextShare(): IncomingTextShare {
    if (action != Intent.ACTION_SEND || !type.equals("text/plain", ignoreCase = true)) {
        return IncomingTextShare.Rejected("Kit Pay can only share plain text.")
    }
    if (hasExtra(Intent.EXTRA_STREAM) || clipData?.containsUnsupportedItem() == true) {
        return IncomingTextShare.Rejected("Attachments cannot be shared to Kit Pay yet.")
    }

    val text = getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        ?: return IncomingTextShare.Rejected("There is no text to share.")
    if (text.isBlank()) {
        return IncomingTextShare.Rejected("There is no text to share.")
    }

    // Check UTF-16 length first so an explicitly targeted, abnormally large Intent cannot cause a
    // second large allocation while calculating its UTF-8 size.
    if (text.length > MAX_SHARED_TEXT_UTF16_UNITS) {
        return IncomingTextShare.Rejected(SHARED_TEXT_TOO_LONG_MESSAGE)
    }
    val codePoints = text.codePointCount(0, text.length)
    if (
        codePoints > MAX_SHARED_TEXT_CODE_POINTS ||
        text.toByteArray(Charsets.UTF_8).size > MAX_SHARED_TEXT_BYTES
    ) {
        return IncomingTextShare.Rejected(SHARED_TEXT_TOO_LONG_MESSAGE)
    }

    return IncomingTextShare.Accepted(text)
}

/**
 * Single-use, process-memory hand-off between the exported share relay and [com.kit.wallet.MainActivity].
 * Keeping only the latest bounded request also prevents an external caller from building an
 * unbounded in-process queue.
 */
internal object IncomingTextShareStore {
    private var pending: IncomingTextShareRequest? = null

    @Synchronized
    fun publish(payload: IncomingTextShare): String {
        val token = UUID.randomUUID().toString()
        pending = IncomingTextShareRequest(token, payload)
        return token
    }

    @Synchronized
    fun take(token: String): IncomingTextShareRequest? {
        val request = pending?.takeIf { it.token == token } ?: return null
        pending = null
        return request
    }
}

internal const val ACTION_OPEN_TEXT_SHARE = "com.kit.wallet.action.OPEN_TEXT_SHARE"
internal const val EXTRA_TEXT_SHARE_TOKEN = "com.kit.wallet.extra.TEXT_SHARE_TOKEN"

private const val MAX_SHARED_TEXT_CODE_POINTS = 4_000
private const val MAX_SHARED_TEXT_BYTES = 16_000
private const val MAX_SHARED_TEXT_UTF16_UNITS = MAX_SHARED_TEXT_CODE_POINTS * 2
private const val SHARED_TEXT_TOO_LONG_MESSAGE =
    "This text is too long. Share up to 4,000 characters at a time."

private fun android.content.ClipData.containsUnsupportedItem(): Boolean =
    (0 until itemCount).any { index ->
        getItemAt(index).let { it.uri != null || it.intent != null }
    }
