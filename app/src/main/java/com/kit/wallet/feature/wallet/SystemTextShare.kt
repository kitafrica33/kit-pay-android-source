package com.kit.wallet.feature.wallet

import android.content.Context
import android.content.Intent
import android.widget.Toast

internal fun launchTextShare(
    context: Context,
    chooserTitle: String,
    text: String,
) {
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    runCatching {
        context.startActivity(Intent.createChooser(sendIntent, chooserTitle))
    }.onFailure {
        Toast.makeText(context, "No sharing app is available.", Toast.LENGTH_SHORT).show()
    }
}
