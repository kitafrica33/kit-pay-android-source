package com.kit.wallet

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.kit.wallet.feature.chat.ACTION_OPEN_TEXT_SHARE
import com.kit.wallet.feature.chat.EXTRA_TEXT_SHARE_TOKEN
import com.kit.wallet.feature.chat.IncomingTextShare
import com.kit.wallet.feature.chat.IncomingTextShareStore
import com.kit.wallet.feature.chat.IncomingShareQueueFullException
import com.kit.wallet.feature.chat.SharedInboxStore
import com.kit.wallet.feature.chat.parseIncomingShare
import com.kit.wallet.ui.theme.KitWalletTheme
import com.kit.wallet.data.session.SessionStore
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Receives Android share-sheet content without forwarding its bytes through another Intent. Only
 * an opaque batch UUID crosses into [MainActivity]; its bounded manifest survives process death.
 *
 * Shared files are copied in here, off the main thread: a share can be a 200 MB video, and the
 * sheet's read grant does not outlive this activity while the destination picker is still open.
 *
 * This deliberately has a real, full-screen UI. A `Theme.NoDisplay` activity is required by
 * Android to finish before `onResume`; doing asynchronous staging from one made the share target
 * briefly open and then disappear on several Android versions.
 */
@AndroidEntryPoint
class ShareRelayActivity : ComponentActivity() {
    @Inject lateinit var sessions: SessionStore

    private var handedOff = false
    private var batchOwnedByActivity: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KitWalletTheme { PreparingSharedContent() }
        }

        // Keep this exact Intent (and therefore its temporary URI grants) alive until every file
        // has crossed into Kit Pay's private staging directory.
        val sharedIntent = intent
        val owner = sessions.current()?.fence()
        val batchId = com.kit.wallet.feature.chat.SharedInboxPolicy.newId()
        batchOwnedByActivity = batchId
        lifecycleScope.launch {
            var durable = false
            try {
                val payload = if (owner == null) {
                    IncomingTextShare.Rejected(
                        "Sign in to Kit Pay first, then share this item again.",
                    )
                } else try {
                    withContext(Dispatchers.IO) {
                        sharedIntent.parseIncomingShare(
                            context = applicationContext,
                            owner = owner,
                            batchId = batchId,
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // This Activity is exported for the Android resolver, so malformed explicit
                    // Intents must fail closed without crashing the main Kit Pay experience.
                    IncomingTextShare.Rejected("Kit Pay couldn't safely read what you shared.")
                }
                coroutineContext.ensureActive()
                val safePayload = if (
                    payload is IncomingTextShare.Accepted &&
                    sessions.current()?.fence() != owner
                ) {
                    IncomingTextShare.Rejected(
                        "Your Kit Pay session changed. Share this item again.",
                    )
                } else {
                    payload
                }
                val token = try {
                    withContext(Dispatchers.IO) {
                        IncomingTextShareStore.publish(applicationContext, safePayload)
                    }.also {
                        durable = safePayload is IncomingTextShare.Accepted
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (full: IncomingShareQueueFullException) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        SharedInboxStore.remove(applicationContext, batchId)
                        IncomingTextShareStore.publish(
                            applicationContext,
                            IncomingTextShare.Rejected(checkNotNull(full.message)),
                        )
                    }
                } catch (_: Exception) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        SharedInboxStore.remove(applicationContext, batchId)
                        IncomingTextShareStore.publish(
                            applicationContext,
                            IncomingTextShare.Rejected(
                                "Kit Pay couldn't safely save what you shared. Try again.",
                            ),
                        )
                    }
                }
                if (durable) batchOwnedByActivity = null
                handOff(token)
            } finally {
                if (!durable) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        SharedInboxStore.remove(applicationContext, batchId)
                    }
                    if (batchOwnedByActivity == batchId) batchOwnedByActivity = null
                }
            }
        }
    }

    private fun handOff(token: String) {
        if (handedOff) return
        handedOff = true
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

@Composable
private fun PreparingSharedContent() {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 32.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Rounded.Lock,
                contentDescription = null,
                modifier = Modifier.size(42.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.size(24.dp))
            Text(
                "Preparing your share",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.size(12.dp))
            Text(
                "Copying your selection into Kit Pay so you can choose a person or group.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.size(28.dp))
            CircularProgressIndicator()
        }
    }
}
