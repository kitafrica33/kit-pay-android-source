package com.kit.wallet.data.repository

import com.kit.wallet.ui.model.Beneficiary
import com.kit.wallet.ui.model.BillProvider
import com.kit.wallet.ui.model.BankInstitution
import com.kit.wallet.ui.model.CallEntry
import com.kit.wallet.ui.model.ChatPreview
import com.kit.wallet.ui.model.Contact
import com.kit.wallet.ui.model.Message
import com.kit.wallet.ui.model.Transaction
import com.kit.wallet.ui.model.UserProfile
import java.time.Instant
import kotlinx.coroutines.flow.StateFlow

/**
 * Presentation-facing contracts backed by the independent Kit Wallet API.
 * Demo implementations are used by Compose previews only; production bindings
 * must either use authenticated remote data or fail closed.
 */

data class ProfileEmailChallenge(
    val id: String,
    val destination: String,
    val expiresAt: String?,
    val resendAfterSeconds: Long?,
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

    suspend fun updateProfile(name: String, tag: String)

    suspend fun requestEmailAttachment(email: String): ProfileEmailChallenge

    suspend fun verifyEmailAttachment(challengeId: String, code: String)
}

interface WalletRepository {
    val balanceMinor: StateFlow<Long>
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

    /** Records an outgoing payment request (no balance change). */
    suspend fun request(from: Contact, amountMinor: Long, note: String?)

    /** Creates an idempotent, non-debit payment request addressed to a chat peer. */
    suspend fun createChatPaymentRequest(
        peerUserId: String,
        amountMinor: Long,
        note: String?,
    ): ChatPaymentRequest = error("Payment requests are unavailable")

    /** Pays a payment request received in chat; a PIN step-up authorizes the debit. */
    suspend fun payChatPaymentRequest(
        requestId: String,
        amountMinor: Long,
        paymentPin: String,
    ): Unit = error("Payment requests are unavailable")

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
    val chats: StateFlow<List<ChatPreview>>
    fun chat(chatId: String): ChatPreview?
    fun conversation(chatId: String): StateFlow<List<Message>>
    suspend fun markConversationRead(chatId: String) = Unit
    suspend fun synchronizeConversation(chatId: String) = Unit
    suspend fun openDirectConversation(contact: Contact): String
    suspend fun sendMessage(chatId: String, text: String)
    suspend fun retryMessage(chatId: String, clientMessageId: String, text: String) {
        error("This chat repository does not support explicit secure-message retries")
    }

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
    )
}
