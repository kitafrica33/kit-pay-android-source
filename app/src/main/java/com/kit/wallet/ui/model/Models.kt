package com.kit.wallet.ui.model

/**
 * Presentation-shaped UI models populated by the Room/Retrofit repository layer.
 */

data class Contact(
    val id: String,
    /** Display name: the device address-book name when saved, otherwise the registered name. */
    val name: String,
    val phone: String,
    val isKitUser: Boolean = true,
    val favorite: Boolean = false,
    val status: String = "Hey there! I'm using Kit Pay",
    val receivingWalletId: String? = null,
    /** The name this person registered on Kit Pay, shown WhatsApp-style as "~ name" when it differs. */
    val registeredName: String? = null,
    /** True when the phone number is already saved in this device's address book. */
    val savedInDevice: Boolean = false,
)

data class UserProfile(
    val name: String,
    val phone: String,
    val tag: String,
    val kycLabel: String,
    val email: String? = null,
    val emailVerified: Boolean = false,
    val profileSetupRequired: Boolean = false,
)

fun formatKitTag(value: String): String = value.trim().trimStart('@')
    .takeIf(String::isNotBlank)
    ?.let { "@$it" }
    .orEmpty()

enum class TxType { SEND, RECEIVE, BILL, AIRTIME, BANK_IN, BANK_OUT, MERCHANT, REQUEST }
enum class TxStatus { COMPLETED, PENDING, FAILED }

data class Transaction(
    val id: String,
    val counterparty: String,
    val note: String?,
    /** Signed minor units (cents); negative = money out. */
    val amountMinor: Long,
    val time: String,
    val dateGroup: String,
    val type: TxType,
    val status: TxStatus = TxStatus.COMPLETED,
    val reference: String,
)

enum class DeliveryState { SENDING, SENT, DELIVERED, READ, RETRY_REQUIRED, FAILED }
enum class MessageKind { TEXT, PAYMENT, VOICE_NOTE, IMAGE }

data class Message(
    val id: String,
    val text: String,
    val time: String,
    val fromMe: Boolean,
    val senderName: String? = null,
    val state: DeliveryState = DeliveryState.READ,
    val kind: MessageKind = MessageKind.TEXT,
    /** For IMAGE messages: the opaque end-to-end media descriptor used to fetch and decrypt. */
    val mediaDescriptor: String? = null,
    /** For PAYMENT messages: signed minor units. */
    val amountMinor: Long = 0,
    val reactions: List<String> = emptyList(),
    val replyToText: String? = null,
    val durationSec: Int = 0,
)

data class ChatPreview(
    val id: String,
    val name: String,
    val lastMessage: String,
    val time: String,
    /** Authenticated backend user ID for direct-chat actions such as calling. */
    val peerUserId: String? = null,
    val unread: Int = 0,
    val isGroup: Boolean = false,
    val online: Boolean = false,
    val typing: Boolean = false,
    val pinned: Boolean = false,
    val muted: Boolean = false,
    val lastFromMe: Boolean = false,
    val lastState: DeliveryState = DeliveryState.READ,
)

enum class CallDirection { INCOMING, OUTGOING, MISSED }

data class CallEntry(
    val id: String,
    val name: String,
    val time: String,
    val direction: CallDirection,
    val video: Boolean = false,
    val participantUserIds: List<String> = emptyList(),
)

data class BillProvider(
    val id: String,
    val name: String,
    val category: String,
    val accountHint: String,
)

data class Beneficiary(
    val id: String,
    val name: String,
    val bank: String,
    val accountMasked: String,
    val verified: Boolean = true,
    val kind: String? = null,
    val bankId: String? = null,
)

data class BankInstitution(
    val id: String,
    val name: String,
    val currency: String,
    val capabilities: Map<String, Boolean> = emptyMap(),
) {
    fun supports(capability: String): Boolean = capabilities[capability] == true
}

object BankCapability {
    const val ACCOUNT_VERIFICATION = "account_verification"
    const val DEPOSITS = "deposits"
    const val WITHDRAWALS = "withdrawals"
    const val TRANSFERS = "transfers"
}

enum class BankOperationKind(
    val apiType: String,
    val capability: String,
    val requiresOwnAccount: Boolean,
) {
    DEPOSIT("deposit", BankCapability.DEPOSITS, true),
    WITHDRAWAL("withdrawal", BankCapability.WITHDRAWALS, true),
    TRANSFER("bank_transfer", BankCapability.TRANSFERS, false);

    companion object {
        fun fromApiType(value: String): BankOperationKind? = entries.firstOrNull {
            it.apiType == value
        }
    }
}

fun eligibleBankBeneficiaries(
    operation: BankOperationKind,
    banks: List<BankInstitution>,
    beneficiaries: List<Beneficiary>,
): List<Beneficiary> {
    val banksById = banks.associateBy(BankInstitution::id)
    return beneficiaries.filter { beneficiary ->
        beneficiary.verified &&
            (!operation.requiresOwnAccount || beneficiary.kind == "own") &&
            beneficiary.bankId?.let { bankId ->
                banksById[bankId]?.supports(operation.capability)
            } == true
    }
}
