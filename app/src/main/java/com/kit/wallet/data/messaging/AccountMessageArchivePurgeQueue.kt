package com.kit.wallet.data.messaging

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.kit.wallet.data.session.SessionFence
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

internal interface AccountMessageArchivePurgeIntents {
    fun enqueue(target: SessionFence)
    fun complete(pending: PendingAccountMessageArchivePurge)
    fun pending(): Set<PendingAccountMessageArchivePurge>
    fun contains(pending: PendingAccountMessageArchivePurge): Boolean
}

internal object NoOpAccountMessageArchivePurgeIntents : AccountMessageArchivePurgeIntents {
    override fun enqueue(target: SessionFence) = Unit
    override fun complete(pending: PendingAccountMessageArchivePurge) = Unit
    override fun pending(): Set<PendingAccountMessageArchivePurge> = emptySet()
    override fun contains(pending: PendingAccountMessageArchivePurge): Boolean = false
}

/**
 * Crash-safe, authentication-independent intent to remove one account's retained display archive.
 * The account UUID is already authenticated routing metadata in the archive table; no message
 * body, key material, session credential, or profile field is stored here.
 */
@Singleton
internal class AccountMessageArchivePurgeQueue @Inject constructor(
    @ApplicationContext context: Context,
) : AccountMessageArchivePurgeIntents {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized
    override fun enqueue(target: SessionFence) {
        val pending = PendingAccountMessageArchivePurge.from(target)
        val updated = pendingTokensLocked() + pending.encode()
        check(preferences.edit().putStringSet(PENDING_TARGETS, updated).commit()) {
            "The account message archive purge marker could not be persisted"
        }
    }

    @Synchronized
    override fun complete(pending: PendingAccountMessageArchivePurge) {
        val updated = pendingTokensLocked() - pending.encode()
        val editor = preferences.edit()
        if (updated.isEmpty()) {
            editor.remove(PENDING_TARGETS)
        } else {
            editor.putStringSet(PENDING_TARGETS, updated)
        }
        check(editor.commit()) {
            "The account message archive purge marker could not be cleared"
        }
    }

    @Synchronized
    override fun pending(): Set<PendingAccountMessageArchivePurge> =
        pendingTokensLocked().mapTo(linkedSetOf(), PendingAccountMessageArchivePurge::decode)

    @Synchronized
    override fun contains(pending: PendingAccountMessageArchivePurge): Boolean =
        pending.encode() in pendingTokensLocked()

    private fun pendingTokensLocked(): Set<String> =
        preferences.getStringSet(PENDING_TARGETS, emptySet())
            .orEmpty()
            .toSet()

    private companion object {
        const val PREFERENCES = "kit_pay_account_message_archive_purge_v1"
        const val PENDING_TARGETS = "pending_session_fences"
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class AccountMessageArchivePurgeIntentsModule {
    @Binds
    @Singleton
    abstract fun bindAccountMessageArchivePurgeIntents(
        implementation: AccountMessageArchivePurgeQueue,
    ): AccountMessageArchivePurgeIntents
}

internal data class PendingAccountMessageArchivePurge(
    val ownerAccountId: String,
    val targetFingerprint: String,
) {
    init {
        requireCanonicalPurgeOwner(ownerAccountId)
        require(targetFingerprint.length == 64 && targetFingerprint.all { it in HEX_CHARS }) {
            "Archive purge target fingerprint is invalid"
        }
    }

    fun encode(): String = listOf(
        TOKEN_VERSION,
        ownerAccountId,
        targetFingerprint,
    ).joinToString(TOKEN_SEPARATOR)

    fun matches(fence: SessionFence?): Boolean =
        fence?.accountId == ownerAccountId &&
            targetFingerprint == fence.purgeFingerprint()

    companion object {
        private const val TOKEN_VERSION = "v1"
        private const val TOKEN_SEPARATOR = "\u001f"
        private const val HEX_CHARS = "0123456789abcdef"

        fun from(target: SessionFence): PendingAccountMessageArchivePurge =
            PendingAccountMessageArchivePurge(
                ownerAccountId = requireNotNull(target.accountId) {
                    "Archive purge target requires an account owner"
                },
                targetFingerprint = target.purgeFingerprint(),
            )

        fun decode(encoded: String): PendingAccountMessageArchivePurge {
            val parts = encoded.split(TOKEN_SEPARATOR, limit = 3)
            require(parts.size == 3 && parts[0] == TOKEN_VERSION) {
                "Invalid account message archive purge marker"
            }
            return PendingAccountMessageArchivePurge(
                ownerAccountId = parts[1],
                targetFingerprint = parts[2],
            )
        }
    }
}

@VisibleForTesting
internal fun requireCanonicalPurgeOwner(ownerAccountId: String) {
    require(runCatching { UUID.fromString(ownerAccountId).toString() }.getOrNull() == ownerAccountId) {
        "Archive purge owner must be a canonical lowercase UUID"
    }
}

private fun SessionFence.purgeFingerprint(): String {
    require(sessionId.isNotBlank() && cacheScopeId.isNotBlank()) {
        "Archive purge target fence is invalid"
    }
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(sessionId.toByteArray(Charsets.UTF_8))
    digest.update(0.toByte())
    digest.update(cacheScopeId.toByteArray(Charsets.UTF_8))
    return digest.digest().joinToString(separator = "") { byte ->
        val value = byte.toInt() and 0xff
        "${"0123456789abcdef"[value ushr 4]}${"0123456789abcdef"[value and 0x0f]}"
    }
}
