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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
        mediaSpool.prune(snapshot.filter { it.kind == ImmediateSendKind.MEDIA }.mapTo(mutableSetOf()) {
            it.id
        })
        if (snapshot.isEmpty()) return@withLock ImmediateSendDispatchOutcome.IDLE

        var committedAny = false
        var retryNeeded = false
        snapshot.groupBy(ImmediateSendIntent::conversationId).values.forEach { conversation ->
            // A person's explicit retry decision is the head-of-line boundary for this one chat,
            // but never for another conversation. A retired reaction is removed rather than left
            // in this queue, so every remaining RETRY_REQUIRED item is a real head-of-line stop.
            for (original in conversation) {
                if (original.state == ImmediateSendState.RETRY_REQUIRED) {
                    // Older builds and non-capability failures could leave a reaction in this state.
                    // Reactions have no standalone retry bubble, so such an item can never receive a
                    // user retry decision and must not strand every later message in the thread.
                    if (original.kind == ImmediateSendKind.REACTION) {
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
                    DispatchOneResult.RETRY_REQUIRED -> break
                    DispatchOneResult.GONE -> Unit
                }
            }
        }
        when {
            retryNeeded -> ImmediateSendDispatchOutcome.RETRY
            committedAny -> ImmediateSendDispatchOutcome.COMMITTED
            else -> ImmediateSendDispatchOutcome.IDLE
        }
    }

    private enum class DispatchOneResult {
        COMMITTED,
        COMMITTED_RETRY,
        COMMITTED_STOP,
        RETIRED,
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
        var mediaCiphertext: ByteArray? = null
        val failure = try {
            if (current.kind == ImmediateSendKind.MEDIA && current.preparedMediaDescriptor == null) {
                mediaCiphertext = mediaSpool.readCiphertext(current)
                val descriptor = chats.prepareImmediateMediaDescriptor(
                    owner = owner,
                    intent = current,
                    ciphertext = checkNotNull(mediaCiphertext),
                )
                val prepared = current.copy(preparedMediaDescriptor = descriptor)
                if (!store.replaceForOwner(owner, current, prepared)) {
                    return DispatchOneResult.GONE
                }
                current = prepared
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
        } finally {
            mediaCiphertext?.fill(0)
        }

        if (encryptedOutboxOwnsSend) {
            withContext(NonCancellable) {
                store.removeForOwner(owner, current.id)
                if (current.kind == ImmediateSendKind.MEDIA) mediaSpool.discard(current.id)
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
                if (current.kind == ImmediateSendKind.MEDIA) mediaSpool.discard(current.id)
            }
            return DispatchOneResult.COMMITTED
        }

        if (
            current.kind == ImmediateSendKind.REACTION &&
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
}
