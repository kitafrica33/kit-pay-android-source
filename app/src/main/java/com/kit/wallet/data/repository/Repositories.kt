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
}

interface ChatRepository {
    /** Reacts to the current authentication epoch's READY secure-messaging session. */
    val readiness: StateFlow<Boolean>
    val chats: StateFlow<List<ChatPreview>>
    fun chat(chatId: String): ChatPreview?
    fun conversation(chatId: String): StateFlow<List<Message>>
    suspend fun markConversationRead(chatId: String) = Unit
    suspend fun openDirectConversation(contact: Contact): String
    suspend fun sendMessage(chatId: String, text: String)
    suspend fun retryMessage(chatId: String, clientMessageId: String, text: String) {
        error("This chat repository does not support explicit secure-message retries")
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

    suspend fun accept(callId: String): CallConnection = error("Calling is unavailable")

    suspend fun decline(callId: String) = Unit

    suspend fun end(callId: String, reason: String = "completed") = Unit
}

data class IncomingCallDetails(
    val callId: String,
    val name: String,
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
