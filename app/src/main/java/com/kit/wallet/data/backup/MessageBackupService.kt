package com.kit.wallet.data.backup

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.kit.wallet.BuildConfig
import com.kit.wallet.data.messaging.AccountArchivedMessage
import com.kit.wallet.data.messaging.AccountMessageHistoryAccess
import com.kit.wallet.data.repository.SecureMessagingChatRuntime
import com.kit.wallet.data.session.SessionStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What a completed backup left behind, for the screen to show. */
data class MessageBackupSummary(
    val messageCount: Int,
    val byteSize: Long,
    val createdAt: Instant,
)

/** What was found in Drive before deciding whether to restore it. */
data class MessageBackupDescription(
    val createdAt: Instant,
    val byteSize: Long,
)

/** Where connecting got to. Google decides whether the user has to be asked. */
sealed interface DriveConnectStep {
    data class Connected(val state: DriveBackupState) : DriveConnectStep

    data class NeedsConsent(val consent: PendingIntent) : DriveConnectStep
}

data class MessageRestoreSummary(
    val readCount: Int,
    val mergedCount: Int,
)

/**
 * Whatever actually books the recurring backup.
 *
 * An interface so the backup logic does not reach into WorkManager: the scheduler implements it in
 * the worker layer, and tests can watch what was asked for without a WorkManager instance.
 */
interface MessageBackupTrigger {
    fun apply(frequency: MessageBackupFrequency, requiresUnmeteredNetwork: Boolean)

    fun cancel()
}

/** A failure with something the user can actually read. */
class MessageBackupException(
    message: String,
    /** True when the only way forward is signing in to Google again. */
    val requiresSignIn: Boolean = false,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * Backing up encrypted conversations to the user's own Google Drive.
 *
 * The shape of this is deliberate. Kit Pay reads the message archive, writes it into a sealed
 * container using a key Google never sees, and uploads that container to a folder in the user's
 * Drive that only Kit Pay can open. Google stores bytes it cannot read. If the user loses the key
 * the backup is gone, and no support process can recover it — that is what end-to-end means, and
 * saying otherwise would be the lie.
 *
 * Restoring merges. The archive's own immutability rule decides every disagreement, so a restore
 * can fill in messages this device never had but can never overwrite or delete one it does.
 */
@Singleton
class MessageBackupService @Inject internal constructor(
    @ApplicationContext private val context: Context,
    private val sessions: SessionStore,
    private val history: AccountMessageHistoryAccess,
    private val store: DriveBackupStore,
    private val authorizer: DriveAuthorizer,
    private val drive: GoogleDriveClient,
    private val chats: SecureMessagingChatRuntime,
    private val schedule: MessageBackupTrigger,
) {
    private val operation = Mutex()

    val state get() = store.state

    fun snapshot(): DriveBackupState = store.forAccount(currentAccountId())

    /** The code the user writes down. Reading it mints the key if this is the first time. */
    suspend fun recoveryCode(): String {
        val accountId = requireAccount()
        return store.key(accountId).formattedRecoveryCode()
    }

    suspend fun markRecoveryCodeConfirmed() {
        val accountId = requireAccount()
        store.update(accountId) { it.copy(recoveryCodeConfirmed = true) }
    }

    suspend fun setFrequency(frequency: MessageBackupFrequency): DriveBackupState {
        val accountId = requireAccount()
        return store.update(accountId) { it.copy(frequency = frequency) }.also(::reschedule)
    }

    suspend fun setRequiresUnmeteredNetwork(required: Boolean): DriveBackupState {
        val accountId = requireAccount()
        return store.update(accountId) { it.copy(requiresUnmeteredNetwork = required) }
            .also(::reschedule)
    }

    /** Re-books the recurring backup after a cold start, so a reinstall does not drop the schedule. */
    fun restoreSchedule() {
        reschedule(store.forAccount(currentAccountId()))
    }

    private fun reschedule(state: DriveBackupState) {
        if (state.connected && state.frequency != MessageBackupFrequency.OFF) {
            schedule.apply(state.frequency, state.requiresUnmeteredNetwork)
        } else {
            schedule.cancel()
        }
    }

    /** True when this device has the Google services the Authorization API needs. */
    val supported: Boolean get() = authorizer.available

    /**
     * Starts connecting. Returns the consent screen to show when Google wants one, and otherwise
     * completes there and then — a user who has approved Kit Pay before is not asked twice.
     */
    suspend fun connect(): DriveConnectStep {
        val accountId = requireAccount()
        return when (val grant = authorizer.authorize()) {
            is DriveGrant.ConsentRequired -> DriveConnectStep.NeedsConsent(grant.consent)
            is DriveGrant.Granted -> DriveConnectStep.Connected(markConnected(accountId))
        }
    }

    /** Finishes connecting once the user has been through Google's consent screen. */
    suspend fun completeConnect(data: Intent?): DriveBackupState {
        val accountId = requireAccount()
        // Reading the result is what surfaces a declined consent as a failure rather than a
        // connection that looks fine until the first backup.
        authorizer.readConsent(data)
        return markConnected(accountId)
    }

    private suspend fun markConnected(accountId: String): DriveBackupState {
        // Minting the key here rather than at the first backup means the recovery code exists
        // before anything has been uploaded that would need it.
        store.key(accountId)
        return store.connect(accountId)
    }

    suspend fun disconnect(): DriveBackupState {
        val accountId = requireAccount()
        revokeGrant()
        schedule.cancel()
        return store.disconnect(accountId)
    }

    /**
     * Tells Google to forget the grant, so the scope disappears from the user's Google account page
     * too. Best effort: a failure here must not leave the app still claiming to be connected.
     */
    private suspend fun revokeGrant() {
        runCatching {
            (authorizer.authorize() as? DriveGrant.Granted)?.let { drive.revoke(it.accessToken) }
        }
    }

    /** Reads the archive, seals it and uploads it, replacing whatever was there before. */
    suspend fun backUpNow(now: Long): MessageBackupSummary = operation.withLock {
        val accountId = requireAccount()
        val current = store.forAccount(accountId)
        if (!current.connected) {
            throw MessageBackupException("Connect a Google account first", requiresSignIn = true)
        }
        val key = store.key(accountId)
        val archived = history.capture(accountId).readAll()
        if (archived.isEmpty()) {
            throw MessageBackupException("There are no messages on this phone to back up")
        }
        val staged = stagingFile("upload")
        val summary = try {
            val written = withContext(Dispatchers.IO) {
                staged.outputStream().use { file ->
                    KitBackupArchive.encryptingStream(file, key).use { sealed ->
                        KitBackupPayload.write(
                            sink = sealed,
                            manifest = KitBackupManifest(
                                ownerAccountId = accountId,
                                createdAt = Instant.ofEpochMilli(now),
                                writerVersion = BuildConfig.VERSION_NAME,
                            ),
                            messages = archived.asSequence(),
                        )
                    }
                }
            }
            val uploaded = withToken(accountId) { token ->
                drive.upload(
                    accessToken = token,
                    fileId = current.driveFileId,
                    name = backupFileName(accountId),
                    source = staged,
                )
            }
            MessageBackupSummary(
                messageCount = written,
                byteSize = uploaded.sizeBytes ?: staged.length(),
                createdAt = Instant.ofEpochMilli(now),
            ).also { done ->
                store.update(accountId) {
                    it.copy(
                        driveFileId = uploaded.id,
                        lastBackupAtEpochMillis = now,
                        lastBackupBytes = done.byteSize,
                        lastBackupMessageCount = done.messageCount,
                    )
                }
            }
        } finally {
            staged.delete()
        }
        summary
    }

    /** Looks for a backup in Drive without downloading it, so the screen can describe one. */
    suspend fun findBackup(): MessageBackupDescription? {
        val accountId = requireAccount()
        if (!store.forAccount(accountId).connected) return null
        val found = withToken(accountId) { token ->
            drive.findFile(token, backupFileName(accountId))
        } ?: return null
        store.update(accountId) { it.copy(driveFileId = found.id) }
        // The file name is derived from the account, but that is only a hint. Whether this backup
        // really belongs to this account is settled by the manifest inside the sealed archive,
        // which cannot be forged without the key.
        return MessageBackupDescription(
            createdAt = Instant.ofEpochMilli(found.modifiedAtEpochMillis ?: 0L),
            byteSize = found.sizeBytes ?: 0L,
        )
    }

    /**
     * Downloads and merges the Drive backup.
     *
     * [recoveryCode] is required only when this device does not already hold the key — a user
     * restoring onto a new phone types it in; a user restoring onto the phone that made the backup
     * does not have to.
     */
    suspend fun restore(recoveryCode: String? = null): MessageRestoreSummary = operation.withLock {
        val accountId = requireAccount()
        val current = store.forAccount(accountId)
        if (!current.connected) {
            throw MessageBackupException("Connect a Google account first", requiresSignIn = true)
        }
        val key = recoveryCode?.let { typed ->
            KitBackupKey.fromRecoveryCode(typed)
                ?: throw MessageBackupException("That recovery code is not complete or correct")
        } ?: store.key(accountId)
        val fileId = current.driveFileId
            ?: withToken(accountId) { token -> drive.findFile(token, backupFileName(accountId)) }
                ?.id
            ?: throw MessageBackupException("There is no Kit Pay backup in this Google account")
        val staged = stagingFile("restore")
        try {
            withToken(accountId) { token ->
                withContext(Dispatchers.IO) {
                    staged.outputStream().use { file -> drive.download(token, fileId, file) }
                }
            }
            val captured = history.capture(accountId)
            var read = 0
            var merged = 0
            val restored = ArrayList<AccountArchivedMessage>(RESTORE_INITIAL_CAPACITY)
            // Only the sealed archive is ever written to disk. Decryption happens in memory and
            // the plaintext history goes straight into the encrypted store.
            withContext(Dispatchers.IO) {
                staged.inputStream().use { file ->
                    KitBackupArchive.decryptingStream(file, key).use { plain ->
                        val manifest = KitBackupPayload.open(plain)
                        if (manifest.ownerAccountId != accountId) {
                            throw MessageBackupException(
                                "That backup belongs to a different Kit Pay account",
                            )
                        }
                        read = KitBackupPayload.readMessages(plain) { message ->
                            if (restored.size >= MAX_RESTORED_MESSAGES) {
                                throw MessageBackupException(
                                    "This backup is larger than Kit Pay can restore in one go",
                                )
                            }
                            restored.add(message)
                        }
                    }
                }
            }
            // Merging happens outside the decrypting stream so a slow archive write cannot hold a
            // decryption buffer open, and so a failure part-way leaves the file untouched.
            restored.forEach { message ->
                try {
                    captured.restore(message)
                    merged++
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // One unmergeable message must not abandon the rest of somebody's history.
                }
            }
            if (merged > 0) chats.invalidateArchivedHistoryProjection()
            if (recoveryCode != null) store.adoptKey(accountId, key)
            MessageRestoreSummary(readCount = read, mergedCount = merged)
        } catch (format: KitBackupFormatException) {
            throw MessageBackupException(format.message.orEmpty(), cause = format)
        } catch (integrity: KitBackupIntegrityException) {
            throw MessageBackupException(
                "That backup could not be opened with this recovery code",
                cause = integrity,
            )
        } finally {
            staged.delete()
        }
    }

    /** Deletes the archive from Drive and forgets the key, which is what "delete" has to mean. */
    suspend fun deleteBackup(): Unit = operation.withLock {
        val accountId = requireAccount()
        val current = store.forAccount(accountId)
        val fileId = current.driveFileId
            ?: withToken(accountId) { token -> drive.findFile(token, backupFileName(accountId)) }
                ?.id
        if (fileId != null) withToken(accountId) { token -> drive.delete(token, fileId) }
        revokeGrant()
        schedule.cancel()
        store.clear()
    }

    /** For the scheduled worker: true when a backup is configured, connected and overdue. */
    fun isDue(now: Long): Boolean {
        val accountId = currentAccountId() ?: return false
        val current = store.forAccount(accountId)
        return current.connected && current.isDue(now)
    }

    private suspend fun <T> withToken(accountId: String, block: suspend (String) -> T): T {
        val token = accessToken()
        return try {
            block(token)
        } catch (expired: GoogleAuthorizationException) {
            if (!expired.requiresSignIn) throw expired
            // Drive can reject a token that Play Services still considers current. One fresh token
            // separates that from a grant the user has actually revoked.
            val retryToken = try {
                accessToken()
            } catch (dead: GoogleAuthorizationException) {
                throw signInRequired(dead)
            }
            try {
                block(retryToken)
            } catch (stillDead: GoogleAuthorizationException) {
                throw signInRequired(stillDead)
            }
        }
    }

    /**
     * Play Services is the cache. It holds the grant and hands out a short-lived token per call,
     * which is why nothing here stores one — including for the overnight backup, where the app is
     * not running and there is nobody to ask.
     */
    private suspend fun accessToken(): String = when (val grant = authorizer.authorize()) {
        is DriveGrant.Granted -> grant.accessToken
        is DriveGrant.ConsentRequired -> throw GoogleAuthorizationException(
            "Google Drive needs you to sign in again",
            requiresSignIn = true,
        )
    }

    private suspend fun signInRequired(cause: GoogleAuthorizationException): MessageBackupException {
        val accountId = currentAccountId()
        if (accountId != null) store.disconnect(accountId)
        return MessageBackupException(
            cause.message ?: "Google Drive needs you to sign in again",
            requiresSignIn = true,
            cause = cause,
        )
    }

    private fun currentAccountId(): String? = sessions.current()?.fence()?.accountId

    private fun requireAccount(): String = currentAccountId()
        ?: throw MessageBackupException("Sign in to Kit Pay before backing up messages")

    private fun stagingFile(purpose: String): File {
        val directory = File(context.cacheDir, STAGING_DIRECTORY).apply { mkdirs() }
        // Overwritten every run, and deleted in a finally: a decrypted backup never lingers.
        return File(directory, "$purpose.kitbak").apply { delete() }
    }

    /**
     * A per-account file name, so two Kit Pay accounts sharing one Google account do not overwrite
     * each other. Hashed rather than plain, because the account ID is not Google's to keep.
     */
    private fun backupFileName(accountId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(accountId.toByteArray(Charsets.UTF_8))
        val suffix = digest.take(8).joinToString("") { "%02x".format(it) }
        return "kit-messages-$suffix.kitbak"
    }

    private companion object {
        const val STAGING_DIRECTORY = "backup"
        const val RESTORE_INITIAL_CAPACITY = 512

        /**
         * A ceiling on what one restore will hold in memory at once. Far above any real Kit Pay
         * history, and low enough that a corrupt or hostile archive cannot claim the whole heap.
         */
        const val MAX_RESTORED_MESSAGES = 50_000
    }
}
