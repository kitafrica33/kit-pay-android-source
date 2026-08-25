package com.kit.wallet.data.repository

import com.kit.wallet.ui.model.Beneficiary
import com.kit.wallet.ui.model.BillProvider
import com.kit.wallet.ui.model.BankInstitution
import com.kit.wallet.ui.model.CallEntry
import com.kit.wallet.ui.model.ChatMember
import com.kit.wallet.ui.model.ChatMemberRole
import com.kit.wallet.ui.model.ChatPreview
import com.kit.wallet.ui.model.Contact
import com.kit.wallet.ui.model.Message
import com.kit.wallet.ui.model.Money
import com.kit.wallet.ui.model.Transaction
import com.kit.wallet.ui.model.TransferClaim
import com.kit.wallet.ui.model.UserProfile
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.kit.wallet.data.session.SessionFence

/**
 * Presentation-facing contracts backed by the independent Kit Wallet API.
 * Demo implementations are used by Compose previews only; production bindings
 * must either use authenticated remote data or fail closed.
 */

/** One local search match: the decrypted message and the conversation it belongs to. */
data class MessageSearchHit(
    val chat: ChatPreview,
    val message: Message,
)

data class ProfileEmailChallenge(
    val id: String,
    val destination: String,
    val expiresAt: String?,
    val resendAfterSeconds: Long?,
)

/**
 * The outcome of a Kit → Kit send: the ledger entry, plus the claim when the money is being held
 * for the recipient to accept. A null claim means the money has already landed.
 */
data class SentTransfer(
    val transaction: Transaction,
    val claim: TransferClaim?,
)

/** A backend payment request created from inside a secure conversation. */
data class ChatPaymentRequest(
    val id: String,
    val amountMinor: Long,
    val currencyCode: String,
    val currencyScale: Int,
    val note: String?,
)

interface UserRepository {
    val profile: StateFlow<UserProfile>

    suspend fun refreshProfile()

    /**
     * Saves the *chosen* half of the identity. Neither argument is the legal name, which only
     * identity verification can set and which no request may overwrite.
     *
     * A blank [tag] means "no username". That is only accepted once a verified legal name exists;
     * otherwise it is a validation error long before it reaches here.
     */
    suspend fun updateProfile(name: String, tag: String)

    /** Uploads a JPEG profile photo through the moderated media pipeline and attaches it. */
    suspend fun attachAvatar(jpegBytes: ByteArray): Unit =
        error("Profile photos are unavailable")

    suspend fun requestEmailAttachment(email: String): ProfileEmailChallenge

    suspend fun verifyEmailAttachment(challengeId: String, code: String)
}

/** The currency and minor-unit scale of the wallet money is moving out of. */
data class WalletCurrency(
    val code: String = Money.SYMBOL,
    val scale: Int = Money.SCALE,
)

interface WalletRepository {
    /** Authenticated public account ID used to bind wallet objects to a direct conversation. */
    val currentAccountId: String?
        get() = null

    val balanceMinor: StateFlow<Long>

    /**
     * The selected wallet's currency, for amounts the app composes itself rather than reads off a
     * quote — a shortfall, for one. Defaults to the display currency so a repository that has no
     * wallet to speak for still answers something formattable.
     */
    val walletCurrency: StateFlow<WalletCurrency>
        get() = MutableStateFlow(WalletCurrency())

    val transactions: StateFlow<List<Transaction>>
    val beneficiaries: StateFlow<List<Beneficiary>>

    fun transaction(id: String): Transaction?

    /** Debits the wallet and prepends a SEND transaction. Returns it. */
    suspend fun send(
        recipient: Contact,
        amountMinor: Long,
        note: String?,
        paymentPin: String,
    ): Transaction

    /**
     * The same debit as [send], but also reporting the claim when the money is being held for
     * the recipient to accept. That claim is what lets the conversation post a card the recipient
     * can act on, instead of a line saying money moved that neither side can do anything about.
     */
    suspend fun sendToContact(
        recipient: Contact,
        amountMinor: Long,
        note: String?,
        paymentPin: String,
    ): SentTransfer = SentTransfer(send(recipient, amountMinor, note, paymentPin), null)

    /** Records an outgoing payment request (no balance change). */
    suspend fun request(from: Contact, amountMinor: Long, note: String?)

    /**
     * Creates an idempotent, non-debit payment request addressed to a chat peer.
     *
     * [idempotencyKey] lets a caller that can retry — a scheduled request, whose dispatch may run
     * again after failing to share the card — name the request it is retrying, so the server
     * returns the same one instead of minting a second ask. A null key means a fresh request.
     */
    suspend fun createChatPaymentRequest(
        peerUserId: String,
        amountMinor: Long,
        note: String?,
        idempotencyKey: String? = null,
    ): ChatPaymentRequest = error("Payment requests are unavailable")

    /** Scheduled-send boundary that cannot be redirected into a replacement login. */
    suspend fun createChatPaymentRequestForOwner(
        owner: SessionFence,
        peerUserId: String,
        amountMinor: Long,
        note: String?,
        idempotencyKey: String,
    ): ChatPaymentRequest = error("Owner-pinned payment requests are unavailable")

    /** Pays a payment request received in chat; a PIN step-up authorizes the debit. */
    suspend fun payChatPaymentRequest(
        requestId: String,
        amountMinor: Long,
        paymentPin: String,
    ): Unit = error("Payment requests are unavailable")

    /** Withdraws a payment request this account created; only the requester may. */
    suspend fun cancelChatPaymentRequest(requestId: String): Unit =
        error("Payment requests are unavailable")

    /**
     * Held Kit → Kit transfers this account is a party to, newest first.
     *
     * Empty rather than failing: a service without claimable transfers simply has none, and a
     * conversation must still open when this call cannot be made.
     */
    suspend fun transferClaims(): List<TransferClaim> = emptyList()

    /** Re-fetches the current server capability; false/missing fails closed. */
    suspend fun refreshClaimableTransfersCapability(): Boolean = false

    /** Reads one authoritative claim immediately before a resolution action. */
    suspend fun transferClaim(claimId: String): TransferClaim =
        error("Held transfers are unavailable")

    /** Takes a held transfer. Once accepted the payment is final and cannot be reversed. */
    suspend fun acceptTransferClaim(claimId: String): TransferClaim =
        error("Held transfers are unavailable")

    /** Sends a held transfer back, recording why so the conversation can say so. */
    suspend fun rejectTransferClaim(claimId: String, reason: String?): TransferClaim =
        error("Held transfers are unavailable")

    /** Takes back a transfer the recipient has not accepted yet, with the sender's reason. */
    suspend fun reverseTransferClaim(
        claimId: String,
        reason: String?,
        paymentPin: String,
    ): TransferClaim =
        error("Held transfers are unavailable")

    suspend fun payBill(
        provider: BillProvider,
        account: String,
        amountMinor: Long,
        paymentPin: String,
    ): Transaction

    suspend fun buyAirtime(
        productId: String,
        phone: String,
        amountMinor: Long,
        paymentPin: String,
    ): Transaction

    /** Fetches the authoritative bill quote (amount, fee, total) for review before approval. */
    suspend fun previewBill(
        provider: BillProvider,
        account: String,
        amountMinor: Long,
    ): FinancialOperationQuote = error("Bill payments are unavailable")

    /** Fetches the authoritative airtime quote (amount, fee, total) for review before approval. */
    suspend fun previewAirtime(
        productId: String,
        phone: String,
        amountMinor: Long,
    ): FinancialOperationQuote = error("Airtime purchases are unavailable")

    /** Submits the exact reviewed provider quote after PIN or biometric approval. */
    suspend fun submitProviderOperation(
        quote: FinancialOperationQuote,
        paymentPin: String,
    ): Transaction = error("Provider operations are unavailable")
}

interface ContactRepository {
    val contacts: StateFlow<List<Contact>>

    suspend fun refresh()
    suspend fun syncDeviceContacts()

    /** Refreshes a stale device row and returns its current Kit-account identity when available. */
    suspend fun resolveForMessaging(contact: Contact): Contact? {
        if (contact.isKitUser) return contact
        refresh()
        val phoneKey = contact.phone.filter(Char::isDigit).takeLast(9)
        return contacts.value.singleOrNull { candidate ->
            candidate.isKitUser &&
                candidate.phone.filter(Char::isDigit).takeLast(9) == phoneKey
        }
    }

    /** Finds Kit Pay members by their public @kittag; used when a search query starts with `@`. */
    suspend fun searchByKitTag(query: String): List<Contact> = emptyList()
}

interface ChatRepository {
    /** Reacts to the current authentication epoch's READY secure-messaging session. */
    val readiness: StateFlow<Boolean>

    /**
     * Whether [chats] and [conversation] reflect this device's own encrypted store.
     *
     * True long before [readiness] and independent of the network: it means the local database has
     * been read and what is on screen is real. [readiness] additionally means a message may be
     * sent. Screens render on this one and gate send actions on the other, which is why a chat
     * list, its previews and its transcripts survive a cold start, an offline launch and a failed
     * or still-running secure-session setup.
     */
    val localHistoryReady: StateFlow<Boolean>
        get() = readiness

    val chats: StateFlow<List<ChatPreview>>
    fun chat(chatId: String): ChatPreview?
    fun conversation(chatId: String): StateFlow<List<Message>>
    suspend fun markConversationRead(chatId: String) = Unit
    suspend fun synchronizeConversation(chatId: String) = Unit

    /** Viewer-local pin/mute preferences; never sent to the server (iOS parity). */
    suspend fun setChatPinned(chatId: String, pinned: Boolean) = Unit
    suspend fun setChatMuted(chatId: String, muted: Boolean) = Unit

    /**
     * Local-only search over already-decrypted text projections; media and payment descriptors
     * are deliberately excluded from search text (same policy as iOS).
     */
    fun searchMessages(query: String, limit: Int = 50): List<MessageSearchHit> = emptyList()
    suspend fun openDirectConversation(contact: Contact): String
    suspend fun sendMessage(
        chatId: String,
        text: String,
        onDurablyCommitted: (clientMessageId: String) -> Unit = {},
    )

    /** Scheduled-send boundary that captures one exact encrypted activation before sending. */
    suspend fun sendMessageForOwner(
        owner: SessionFence,
        chatId: String,
        text: String,
        onDurablyCommitted: (clientMessageId: String) -> Unit = {},
    ): Unit = error("Owner-pinned secure messaging is unavailable")

    /** Sends a canonical descriptor produced by a payment flow, never by a text composer. */
    suspend fun sendPaymentEvent(
        chatId: String,
        descriptor: String,
        onDurablyCommitted: (clientMessageId: String) -> Unit = {},
    ) = sendMessage(chatId, descriptor, onDurablyCommitted)

    suspend fun sendPaymentEventForOwner(
        owner: SessionFence,
        chatId: String,
        descriptor: String,
        onDurablyCommitted: (clientMessageId: String) -> Unit = {},
    ): Unit = error("Owner-pinned secure messaging is unavailable")

    suspend fun retryMessage(chatId: String, clientMessageId: String, text: String) {
        error("This chat repository does not support explicit secure-message retries")
    }

    /** Retries an already-durable canonical payment descriptor through its original outbox row. */
    suspend fun retryPaymentEvent(
        chatId: String,
        clientMessageId: String,
        descriptor: String,
    ) = retryMessage(chatId, clientMessageId, descriptor)

    /** Sends one image end-to-end encrypted; the server stores only opaque ciphertext. */
    suspend fun sendImageMessage(
        chatId: String,
        bytes: ByteArray,
        mediaType: String,
        caption: String? = null,
    ) {
        error("This chat repository does not support secure media messages")
    }

    /** Downloads and decrypts the media referenced by a message's authenticated descriptor. */
    suspend fun openImageMessage(chatId: String, mediaDescriptor: String): ByteArray {
        error("This chat repository does not support secure media messages")
    }

    /**
     * Adds [emoji] to [messageId], or takes it off again when this account already reacted with
     * it. The reaction travels end-to-end encrypted inside the conversation, so the server never
     * learns which message was reacted to or with what.
     */
    suspend fun toggleReaction(chatId: String, messageId: String, emoji: String) {
        error("This chat repository does not support message reactions")
    }

    /**
     * Creates a group named [title] with [contacts] plus this account, returning its chat ID.
     *
     * The title is the one thing a group discloses to the server — everything inside it (messages,
     * media, payments, reactions) stays end-to-end encrypted exactly as a direct chat does.
     */
    suspend fun createGroupConversation(title: String, contacts: List<Contact>): String {
        error("This chat repository does not support group conversations")
    }

    /**
     * Who is in [chatId] right now, the role each of them holds, and who is presently watching.
     *
     * A flow rather than a snapshot because the presence half of it changes without the roster
     * changing — the participant list has to light up and go dark the same way a chat header does.
     */
    fun groupMembers(chatId: String): StateFlow<List<ChatMember>> = MutableStateFlow(emptyList())

    /** Adds one Kit Pay contact to a group this account may manage. */
    suspend fun addGroupMember(chatId: String, contact: Contact) {
        error("This chat repository does not support group conversations")
    }

    /** Promotes or demotes a member. Only an owner may change an owner's or admin's role. */
    suspend fun setGroupMemberRole(chatId: String, userId: String, role: ChatMemberRole) {
        error("This chat repository does not support group conversations")
    }

    /** Removes another member from a group this account may manage. */
    suspend fun removeGroupMember(chatId: String, userId: String) {
        error("This chat repository does not support group conversations")
    }

    /** Leaves the group and drops it locally. An owner must hand it over first. */
    suspend fun leaveGroupConversation(chatId: String) {
        error("This chat repository does not support group conversations")
    }

    /** Best-effort encrypted composer draft for [chatId]; null when none is stored. */
    suspend fun composerDraft(chatId: String): String? = null

    /** Persists (or clears, when blank) the encrypted composer draft for [chatId]. */
    suspend fun saveComposerDraft(chatId: String, text: String) = Unit

    suspend fun clearComposerDraft(chatId: String) = Unit
}

interface CallRepository {
    val calls: StateFlow<List<CallEntry>>

    suspend fun refresh()

    /** Server-authoritative lookup used before presenting any incoming-call identity or controls. */
    suspend fun incoming(callId: String): IncomingCallDetails = error("Calling is unavailable")

    suspend fun start(
        recipientUserId: String,
        video: Boolean,
        conversationId: String? = null,
    ): CallConnection = error("Calling is unavailable")

    /**
     * Starts or safely replays one process-owned call attempt. [clientCallId] must remain stable
     * across transport retries and must never be restored after process death.
     */
    suspend fun start(
        recipientUserId: String,
        video: Boolean,
        conversationId: String? = null,
        clientCallId: String,
    ): CallConnection = start(recipientUserId, video, conversationId)

    /** Prevents a still-in-flight process-owned call attempt from ringing after local dismissal. */
    suspend fun cancelAttempt(clientCallId: String) = Unit

    /** Adds more Kit Pay users to an active or ringing call, turning it into a group call. */
    suspend fun invite(callId: String, recipientUserIds: List<String>) = Unit

    suspend fun accept(callId: String): CallConnection = error("Calling is unavailable")

    suspend fun decline(callId: String) = Unit

    suspend fun end(callId: String, reason: String = "completed") = Unit
}

data class IncomingCallDetails(
    val callId: String,
    val name: String,
    val phone: String? = null,
    val participantUserIds: List<String> = emptyList(),
    val video: Boolean,
    val direction: String,
    val state: String,
    val ringExpiresAt: String?,
)

fun IncomingCallDetails.requireAnswerable(now: Instant = Instant.now()): IncomingCallDetails {
    require(direction.equals("incoming", ignoreCase = true)) {
        "This call is not an incoming call for the current account"
    }
    require(state.lowercase() in setOf("ringing", "active")) {
        "This incoming call is no longer available"
    }
    val expiry = ringExpiresAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
    require(expiry?.isAfter(now) == true) { "This incoming call has expired" }
    return this
}

data class CallConnection(
    val callId: String,
    val name: String,
    val phone: String? = null,
    val participantUserIds: List<String> = emptyList(),
    val video: Boolean,
    val provider: String,
    val url: String,
    val token: String,
    val room: String,
    /** Server-authoritative end of the ringing window; null only for legacy responses. */
    val ringExpiresAt: String? = null,
)

interface BillsRepository {
    val providers: StateFlow<List<BillProvider>>
    val airtimeProducts: StateFlow<List<BillProvider>>
    fun provider(id: String): BillProvider?
    fun airtimeProduct(id: String): BillProvider?

    suspend fun refresh()
}

interface BankingRepository {
    val banks: StateFlow<List<BankInstitution>>
    val beneficiaries: StateFlow<List<Beneficiary>>
    val operations: StateFlow<List<Transaction>>

    suspend fun refresh()
    suspend fun addBeneficiary(
        bankId: String,
        accountNumber: String,
        label: String,
        kind: String = "own",
    )
    suspend fun createOperation(
        type: String,
        beneficiaryId: String,
        amountMinor: Long,
        paymentPin: String,
        feeMode: String = "sender_absorbs",
    )
    suspend fun previewOperation(
        type: String,
        beneficiaryId: String,
        amountMinor: Long,
        feeMode: String = "sender_absorbs",
    ): FinancialOperationQuote
    /**
     * Submits an approved quote and returns the banking operation it created.
     *
     * See [MobileMoneyRepository.submitOperation] for why the id matters: a top-up covering a
     * payment has to be able to watch its own operation rather than the newest one in the list.
     */
    suspend fun submitOperation(quote: FinancialOperationQuote, paymentPin: String): String
}

class FinancialOperationQuote internal constructor(
    val quoteId: String?,
    val operationType: String,
    val destinationId: String,
    val amountMinor: Long,
    val recipientAmountMinor: Long,
    val feesMinor: Long,
    val customerDebitMinor: Long,
    val currencyCode: String,
    val currencyScale: Int,
    val feeMode: String,
    val expiresAt: String?,
    val feesKnown: Boolean,
    internal val authorizationPurpose: String,
    internal val authorizationIntent: Map<String, Any?>,
    internal val sessionFence: SessionFence,
    /** Customer-facing destination label (e.g. the provider or product name), when known. */
    val destinationName: String? = null,
    /** Provider-verified account presentation (e.g. the meter or subscriber name), when known. */
    val accountDisplay: String? = null,
    internal val productId: String? = null,
)
