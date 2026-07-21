package com.kit.wallet

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.kit.wallet.feature.chat.ACTION_OPEN_TEXT_SHARE
import com.kit.wallet.feature.chat.EXTRA_TEXT_SHARE_TOKEN
import com.kit.wallet.feature.chat.IncomingTextShare
import com.kit.wallet.feature.chat.IncomingTextShareStore
import com.kit.wallet.feature.chat.parseIncomingTextShare

/**
 * Receives Android share-sheet text without forwarding the content through another Intent.
 * The validated payload crosses into [MainActivity] through a single-use in-memory hand-off.
 */
class ShareRelayActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val payload = runCatching { intent.parseIncomingTextShare() }.getOrElse {
            // This Activity is exported for the Android resolver, so malformed explicit Intents
            // must fail closed without crashing the main Kit Pay experience.
            IncomingTextShare.Rejected("Kit Pay couldn't safely read that shared text.")
        }
        val token = IncomingTextShareStore.publish(payload)
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                action = ACTION_OPEN_TEXT_SHARE
                putExtra(EXTRA_TEXT_SHARE_TOKEN, token)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
        )
        finish()
    }
}
