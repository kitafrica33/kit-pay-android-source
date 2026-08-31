package com.kit.wallet.data.messaging

import com.kit.wallet.data.remote.KitWalletApiException
import com.kit.wallet.data.remote.isKitConnectivityError
import com.kit.wallet.data.repository.EncryptedChatRepository
import com.kit.wallet.data.session.SessionFence
import com.kit.wallet.data.session.SessionInvalidatedException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

internal enum class ImmediateSendDispatchOutcome { IDLE, COMMITTED, RETRY }

/** Promotes local plaintext intents to the existing encrypted companion outbox. */
@Singleton
internal class ImmediateSendDispatcher @Inject constructor(
    private val store: ImmediateSendIntentStore,
    private val mediaSpool: ImmediateMediaSpool,
    private val chats: EncryptedChatRepository,
) {
    private val mutex = Mutex()

    suspend fun dispatch(): ImmediateSendDispatchOutcome = mutex.withLock {
        val owner = store.loadForCurrentOwner()
            ?: return@withLock ImmediateSendDispatchOutcome.IDLE
        val snapshot = store.itemsForOwner(owner)
        mediaSpool.prune(
            snapshot.filter { it.state != ImmediateSendState.PREPARING }
                .flatMapTo(mutableSetOf(), ImmediateSendIntent::spoolIds),
        )
        if (snapshot.isEmpty()) return@withLock ImmediateSendDispatchOutcome.IDLE

        // Uploading or encrypting one large attachment must not hold every other conversation
        // behind it. Each worker still walks exactly one conversation in snapshot order, retaining
        // strict per-chat FIFO, while the semaphore caps cross-chat CPU/network pressure.
        val permit = Semaphore(MAX_CONCURRENT_CONVERSATIONS)
        val results = coroutineScope {
            snapshot.groupBy(ImmediateSendIntent::conversationId).values.map { conversation ->
                async {
                    permit.withPermit { dispatchConversation(owner, conversation) }
                }
            }.awaitAll()
        }
        val committedAny = results.any(ConversationDispatchResult::committedAny)
        val retryNeeded = results.any(ConversationDispatchResult::retryNeeded)
        when {
            retryNeeded -> ImmediateSendDispatchOutcome.RETRY
            committedAny -> ImmediateSendDispatchOutcome.COMMITTED
            else -> ImmediateSendDispatchOutcome.IDLE
        }
    }

    private data class ConversationDispatchResult(
        val committedAny: Boolean,
        val retryNeeded: Boolean,
    )

    private suspend fun dispatchConversation(
        owner: SessionFence,
        conversation: List<ImmediateSendIntent>,
    ): ConversationDispatchResult {
        var committedAny = false
        var retryNeeded = false
        // A person's explicit retry decision is the head-of-line boundary for this one chat,
        // but never for another conversation. A retired reaction is removed rather than left
        // in this queue, so every remaining RETRY_REQUIRED item is a real head-of-line stop.
        for (original in conversation) {
            // Terminal and already visible as a failed bubble; nothing behind it waits.
            if (original.state == ImmediateSendState.FAILED) {
                runCatching { chats.releaseImmediateMediaRetention(owner, original) }
                continue
            }
            if (original.state == ImmediateSendState.RETRY_REQUIRED) {
                // Older builds and non-capability failures could leave a reaction or an edit
                // in this state. Neither has a standalone retry bubble, so such an item can
                // never receive a user retry decision and must not strand every later message
                // in the thread.
                if (
                    original.kind == ImmediateSendKind.REACTION ||
                    original.kind == ImmediateSendKind.EDIT
                ) {
                    store.removeForOwner(owner, original.id)
                    continue
                }
                break
            }
            when (dispatchOne(owner, original)) {
                DispatchOneResult.COMMITTED -> committedAny = true
                DispatchOneResult.COMMITTED_RETRY -> {
                    committedAny = true
                    retryNeeded = true
                    break
                }
                DispatchOneResult.COMMITTED_STOP -> {
                    committedAny = true
                    break
                }
                DispatchOneResult.RETRY -> {
                    retryNeeded = true
                    break
                }
                DispatchOneResult.RETIRED -> Unit
                DispatchOneResult.FAILED -> Unit
                DispatchOneResult.RETRY_REQUIRED -> break
                DispatchOneResult.GONE -> Unit
            }
        }
        return ConversationDispatchResult(committedAny, retryNeeded)
    }

    private enum class DispatchOneResult {
        COMMITTED,
        COMMITTED_RETRY,
        COMMITTED_STOP,
        RETIRED,

        /** Irrecoverable, and the record stays behind as its own visible failed bubble. */
        FAILED,
        RETRY,
        RETRY_REQUIRED,
        GONE,
    }

    private suspend fun dispatchOne(
        owner: SessionFence,
        original: ImmediateSendIntent,
    ): DispatchOneResult {
        var current = store.itemsForOwner(owner).firstOrNull { it.id == original.id }
            ?: return DispatchOneResult.GONE
        var encryptedOutboxOwnsSend = false
        val failure = try {
            if (current.state == ImmediateSendState.PREPARING) {
                val prepared = chats.prepareImmediateMediaCiphertext(owner, current)
                if (!store.replaceForOwner(owner, current, prepared)) {
                    prepared.spoolIds().forEach { mediaSpool.discard(it) }
                    return DispatchOneResult.GONE
                }
                current = prepared
                // If the process dies after the queue replacement but before this release, the
                // idempotent release below on the next WAITING dispatch completes the handoff.
                runCatching { chats.releaseImmediateMediaRetention(owner, current) }
            } else if (
                current.kind == ImmediateSendKind.MEDIA ||
                current.kind == ImmediateSendKind.MEDIA_V2
            ) {
                runCatching { chats.releaseImmediateMediaRetention(owner, current) }
            }
            if (current.kind == ImmediateSendKind.MEDIA && current.preparedMediaDescriptor == null) {
                // The spool verifies the blob is still the one the queue recorded and hands back
                // the file itself, so the upload streams off disk rather than through heap.
                val descriptor = chats.prepareImmediateMediaDescriptor(
                    owner = owner,
                    intent = current,
                    ciphertext = mediaSpool.ciphertextFile(current),
                )
                val prepared = current.copy(preparedMediaDescriptor = descriptor)
                if (!store.replaceForOwner(owner, current, prepared)) {
                    return DispatchOneResult.GONE
                }
                current = prepared
            }
            if (
                current.kind == ImmediateSendKind.MEDIA_V2 &&
                current.preparedMediaDescriptor == null
            ) {
                // One roster admission before any byte is uploaded (KITMEDIA2 §6). This is the
                // gate that predicts the server's unanimous check — the current device's own
                // attestation included — so an incompatible conversation costs zero uploads.
                // A capability refusal lands in the RETRY_REQUIRED classification below.
                chats.assertImmediateAlbumAdmission(owner, current)
                // Uploads run in ascending attachment-id order — the canonical wire order — and
                // every confirmed storage key is persisted before the next upload begins, so a
                // process death resumes exactly where the record says and repeats nothing.
                for (item in current.mediaItems.sortedBy(ImmediateSendMediaItem::attachmentId)) {
                    if (item.storageKey != null) continue
                    val storageKey = chats.uploadImmediateAlbumItem(
                        owner = owner,
                        intent = current,
                        attachmentId = item.attachmentId,
                        ciphertext = mediaSpool.albumItemCiphertextFile(current, item.attachmentId),
                    )
                    val recorded = current.withAlbumItemStorageKey(item.attachmentId, storageKey)
                    if (!store.replaceForOwner(owner, current, recorded)) {
                        return DispatchOneResult.GONE
                    }
                    current = recorded
                }
                // Sealed before Signal encryption: the persisted record now carries the exact
                // canonical descriptor, and the intent's own invariant re-proves it on decode.
                val sealed = current.copy(preparedMediaDescriptor = current.buildAlbumDescriptor())
                if (!store.replaceForOwner(owner, current, sealed)) {
                    return DispatchOneResult.GONE
                }
                current = sealed
            }
            chats.promoteImmediateSend(owner, current) { encryptedOutboxOwnsSend = true }
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (invalidated: SessionInvalidatedException) {
            return DispatchOneResult.GONE
        } catch (authenticationChanged: SecureMessagingAuthenticationEpochChangedException) {
            throw authenticationChanged
        } catch (cryptographic: SecureMessagingCryptographicFailureException) {
            throw cryptographic
        } catch (error: Exception) {
            error
        }

        if (encryptedOutboxOwnsSend) {
            withContext(NonCancellable) {
                store.removeForOwner(owner, current.id)
                current.spoolIds().forEach { mediaSpool.discard(it) }
            }
            // A network error after companion commit belongs to the encrypted outbox, not this
            // plaintext queue. Stop this conversation here: its encrypted head must be accepted (or
            // explicitly retired) before a later local intent can be promoted, otherwise recovery's
            // UUID-keyed scan could deliver the thread out of order. Other chats remain independent.
            return when {
                failure == null -> DispatchOneResult.COMMITTED
                failure.isTransientImmediateSendFailure() -> DispatchOneResult.COMMITTED_RETRY
                else -> DispatchOneResult.COMMITTED_STOP
            }
        }
        if (failure == null) {
            // Production always invokes the durable callback. This fallback avoids wedging a test
            // or alternate runtime that completed the full network send before notifying it.
            withContext(NonCancellable) {
                store.removeForOwner(owner, current.id)
                current.spoolIds().forEach { mediaSpool.discard(it) }
            }
            return DispatchOneResult.COMMITTED
        }

        if (current.state == ImmediateSendState.PREPARING) {
            return if (failure.isTransientImmediateSendFailure()) {
                DispatchOneResult.RETRY
            } else {
                withContext(NonCancellable) {
                    store.replaceForOwner(
                        owner,
                        current,
                        current.copy(state = ImmediateSendState.FAILED),
                    )
                    current.spoolIds().forEach { mediaSpool.discard(it) }
                    runCatching { chats.releaseImmediateMediaRetention(owner, current) }
                }
                DispatchOneResult.FAILED
            }
        }

        if (
            (
                current.kind == ImmediateSendKind.REACTION ||
                    current.kind == ImmediateSendKind.EDIT
                ) &&
            failure is SecureMessagingConversationCapabilityUnavailableException
        ) {
            withContext(NonCancellable) { store.removeForOwner(owner, current.id) }
            return DispatchOneResult.RETIRED
        }
        if (
            current.kind == ImmediateSendKind.MEDIA &&
            failure is ImmediateMediaSpoolUnavailableException
        ) {
            // No retry can reconstruct missing/corrupt ciphertext. Retire this irrecoverable local
            // item instead of presenting an endless retry and blocking every later chat message.
            withContext(NonCancellable) {
                store.removeForOwner(owner, current.id)
                mediaSpool.discard(current.id)
            }
            return DispatchOneResult.RETIRED
        }
        if (
            current.kind == ImmediateSendKind.MEDIA_V2 &&
            failure is ImmediateMediaSpoolUnavailableException
        ) {
            // An album's lost ciphertext is just as irrecoverable, but KITMEDIA2 §7 requires the
            // failure to happen in front of the person, never silently — and never as a partial
            // send. The record flips to its terminal visible state, its remaining spool files
            // are useless and released, and dispatch skips it from now on.
            withContext(NonCancellable) {
                store.replaceForOwner(
                    owner,
                    current,
                    current.copy(state = ImmediateSendState.FAILED),
                )
                current.spoolIds().forEach { mediaSpool.discard(it) }
            }
            return DispatchOneResult.FAILED
        }
        return if (failure.isTransientImmediateSendFailure()) {
            DispatchOneResult.RETRY
        } else {
            store.markRetryRequiredForOwner(owner, current)
            DispatchOneResult.RETRY_REQUIRED
        }
    }

    private fun Throwable.isTransientImmediateSendFailure(): Boolean = when (this) {
        is IOException,
        is SecureMessagingStateConflictException,
        -> true
        is KitWalletApiException -> isKitConnectivityError() || statusCode == null ||
            statusCode == 408 || statusCode == 425 || statusCode == 429 || statusCode >= 500
        // A roster/device capability refusal is deliberately not here. It is message-local and
        // becomes RETRY_REQUIRED, allowing all unrelated conversations to continue.
        else -> false
    }

    private companion object {
        const val MAX_CONCURRENT_CONVERSATIONS = 4
    }
}
