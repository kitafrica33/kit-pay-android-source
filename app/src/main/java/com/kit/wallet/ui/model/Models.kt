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
    /** Absolute URL of this member's moderated profile photo, when one is attached. */
    val avatarUrl: String? = null,
)

data class UserProfile(
    /** The chosen display name. Anything the user likes; not proof of who they are. */
    val name: String,
    val phone: String,
    /** The chosen username, without the leading `@`. Empty once it is optional and unset. */
    val tag: String,
    val kycLabel: String,
    val email: String? = null,
    val emailVerified: Boolean = false,
    val profileSetupRequired: Boolean = false,
    /** Absolute URL of the moderated profile photo, when one is attached. */
    val avatarUrl: String? = null,
    /**
     * The name on the verified identity document. Set only by identity verification, never by
     * anything typed in the app, and never overwritten by [name] or [tag]. Null until verification
     * has been approved.
     */
    val legalName: String? = null,
    /** Whether a username still has to be chosen. False once a verified [legalName] exists. */
    val usernameRequired: Boolean = true,
) {
    /** The name to put in front of someone in a financial context: verified first, chosen after. */
    val displayIdentityName: String
        get() = legalName?.takeIf(String::isNotBlank) ?: name

    /**
     * Whether identity verification has produced a name this account can be known by.
     *
     * The same fact [usernameRequired] is the server's report of, kept as one derived property so
     * that a label reading "optional" and the rule that decides whether Save is allowed can never
     * disagree.
     */
    val identityVerified: Boolean
        get() = !legalName.isNullOrBlank()
}

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
    val currencyCode: String = "UGX",
    val currencyScale: Int = Money.SCALE,
    val feeMinor: Long? = null,
    val recipientAmountMinor: Long? = null,
    val customerDebitMinor: Long? = null,
    val feeMode: String? = null,
)

enum class DeliveryState {
    SENDING,
    SENT,
    DELIVERED,
    READ,
    RETRY_REQUIRED,
    FAILED,

    /**
     * Composed, saved and waiting for the time its author chose. Nothing has been encrypted for
     * anyone yet, and the peer has no idea it exists.
     */
    SCHEDULED,

    /**
     * A scheduled send whose device stopped between handing the message over and recording that it
     * had. Sending it again might duplicate it and dropping it might lose it, so it stays here
     * until the person who wrote it says which.
     */
    UNCONFIRMED,
}

enum class MessageKind {
    TEXT,
    PAYMENT,
    PAYMENT_REQUEST,

    /** A Kit → Kit transfer the recipient can accept or reject, shown as an actionable card. */
    PAYMENT_TRANSFER,

    /**
     * A settled outcome — accepted, declined, reversed, returned. Rendered as a centred line in
     * the conversation so money that came back says so, and says why, instead of disappearing.
     */
    PAYMENT_EVENT,
    /**
     * A payment shared out among a group's members, shown as one golden card. Each member claims
     * their own share from it, and sees only their own share — never the whole pot when the sender
     * chose the amounts individually.
     */
    GROUP_PAYMENT,

    /**
     * One member answering a group payment — taking their share, declining it, or the sender
     * pulling back what nobody claimed. Centred like [PAYMENT_EVENT], and only ever about the
     * member who wrote it.
     */
    GROUP_PAYMENT_EVENT,
    VOICE_NOTE,
    IMAGE,
    VIDEO,
    DOCUMENT,
    CALL,

    /**
     * A group saying what happened to it — somebody joined, somebody is no longer in it, somebody
     * became an admin. Centred and unattributed like [PAYMENT_EVENT], because it is the
     * conversation talking about itself rather than any member talking.
     */
    SYSTEM,
}

/** What a payment bubble records. Mirrors the encrypted descriptor's action. */
enum class PaymentEventKind {
    REQUESTED,
    PAID,
    DECLINED,
    CANCELLED,
    TRANSFER,
    SENT,
    ACCEPTED,
    REJECTED,
    REVERSED,
    EXPIRED,
}

/** Who ended a held transfer. `SYSTEM` means the claim window closed with nobody acting. */
enum class TransferClaimActor { SENDER, RECIPIENT, SYSTEM }

enum class TransferClaimStatus { PENDING, ACCEPTED, REJECTED, REVERSED, EXPIRED }

/**
 * Live state of a held Kit → Kit transfer, read from the wallet API rather than from the chat
 * descriptor. The chat message records that the transfer happened; this records what has become
 * of it, so a card is never stale just because a follow-up message went missing.
 */
data class TransferClaim(
    val id: String,
    val transactionId: String,
    val status: TransferClaimStatus,
    val amountMinor: Long,
    val currencyCode: String = "UGX",
    val currencyScale: Int = Money.SCALE,
    val note: String? = null,
    /** Why the money went back, in the words of whoever sent it back. */
    val reason: String? = null,
    val resolvedBy: TransferClaimActor? = null,
    /** Public account IDs used to bind actions to this exact direct conversation. */
    val senderUserId: String? = null,
    val recipientUserId: String? = null,
    val senderName: String? = null,
    val recipientName: String? = null,
    val expiresAtEpochMillis: Long = 0,
    val canAccept: Boolean = false,
    val canReject: Boolean = false,
    val canReverse: Boolean = false,
)

/**
 * One emoji on a message, together with everyone who put it there.
 *
 * [reactorNames] is display copy resolved from the authenticated conversation membership, so a
 * peer can never attribute its reaction to somebody else. "You" is always listed first.
 */
data class MessageReaction(
    val emoji: String,
    val reactorNames: List<String>,
    val fromMe: Boolean,
) {
    val count: Int get() = reactorNames.size
}

/**
 * One person a message was addressed to, and how far it got with them.
 *
 * Zero moments mean the server has not witnessed that step, which is not the same as it having
 * failed — the same reading [Message.editedAtEpochMillis] gets.
 */
data class MessageDeliveryPerson(
    val userId: String,
    val name: String,
    val avatarUrl: String? = null,
    val deliveredAtEpochMillis: Long = 0,
    val readAtEpochMillis: Long = 0,
)

/**
 * When a message this account sent was accepted, and when it reached each of its recipients.
 *
 * A group lists everyone rather than averaging them, because "delivered to 30 people" answers a
 * question nobody asked: the question is always *who*.
 */
data class MessageDeliveryInfo(
    val messageId: String,
    val sentAtEpochMillis: Long,
    val recipients: List<MessageDeliveryPerson>,
) {
    val readCount: Int get() = recipients.count { it.readAtEpochMillis > 0 }
    val deliveredCount: Int get() = recipients.count { it.deliveredAtEpochMillis > 0 }
}

data class Message(
    val id: String,
    val text: String,
    val time: String,
    val fromMe: Boolean,
    val senderName: String? = null,
    val state: DeliveryState = DeliveryState.READ,
    val kind: MessageKind = MessageKind.TEXT,
    /** For IMAGE and payment messages: the opaque end-to-end descriptor for follow-up actions. */
    val mediaDescriptor: String? = null,
    /** For media messages: the authenticated MIME type (`mt`), the single kind source of truth. */
    val mediaType: String? = null,
    /** For media messages: the decrypted payload size, for placeholder byte labels. */
    val mediaPlaintextBytes: Int = 0,
    /** For PAYMENT messages: signed minor units. */
    val amountMinor: Long = 0,
    /**
     * For payment messages: the backend identifier this bubble refers to — a payment-request id
     * for request bubbles, a transfer-claim id for transfer bubbles.
     */
    val paymentReferenceId: String? = null,
    /** For payment messages: what the descriptor records happening. */
    val paymentEvent: PaymentEventKind? = null,
    /** For payment messages: an optional sender note carried inside the encrypted descriptor. */
    val paymentNote: String? = null,
    /** For returned payments: why it came back, as given by whoever sent it back. */
    val paymentReason: String? = null,
    /** For payment messages: the descriptor's authoritative currency and minor-unit scale. */
    val paymentCurrencyCode: String = "UGX",
    val paymentCurrencyScale: Int = Money.SCALE,
    /** For group-payment entries: the backend payment every member's share hangs off. */
    val groupPaymentId: String? = null,
    /** For group-payment entries: what the descriptor records happening. */
    val groupPaymentEvent: GroupPaymentEventKind? = null,
    /** Epoch millis used to interleave messages with call-log entries in a conversation. */
    val sortEpochMillis: Long = 0,
    /**
     * When a [DeliveryState.SCHEDULED] entry is due to go out; zero for everything else.
     *
     * Carried separately from [sortEpochMillis] because a scheduled entry sits at the foot of the
     * thread regardless of when it will be sent, and the bubble still has to say the real time.
     */
    val scheduledAtEpochMillis: Long = 0,
    /** For CALL entries: direction, whether it was a video call and the connected duration. */
    val callDirection: CallDirection? = null,
    val callVideo: Boolean = false,
    val callDurationSeconds: Long = 0,
    /** Emoji reactions on this message, most-reacted first, then by first appearance. */
    val reactions: List<MessageReaction> = emptyList(),
    /** The message this one answers, when it answers one. */
    val replyToMessageId: String? = null,
    /**
     * The quoted line, resolved from the thread this device has already decrypted.
     *
     * Null while [replyToMessageId] names something not held locally — an answer to a message
     * from before this installation, say. The quote is then simply not drawn: inventing a
     * placeholder for words nobody here can read would put text in someone else's mouth.
     */
    val replyToText: String? = null,
    /** Who wrote the quoted message, for the heading above it. */
    val replyToSenderName: String? = null,
    /** Whether the quoted message is this account's own, which the heading says as "You". */
    val replyToFromMe: Boolean = false,
    val durationSec: Int = 0,
    /** Authenticated public sender ID; required to bind group-message safety actions. */
    val senderUserId: String? = null,
    /**
     * When this message's author last replaced its wording; zero when it still reads as sent.
     *
     * The bubble says so rather than showing both versions: a reader is owed the knowledge that
     * the words changed, but the withdrawn wording is precisely what the sender took back.
     */
    val editedAtEpochMillis: Long = 0,
)

/**
 * Whether this entry can carry reactions.
 *
 * A reaction pins its target's message ID, and a send that has not been acknowledged is still
 * identified by its local client ID — the server ID replaces it once the send lands, which would
 * strand a reaction authored in between. Calls, settled payment outcomes and membership lines are
 * centred timeline records rather than bubbles, so they have nothing to attach a chip to — and so
 * is a group payment, whose card belongs to the whole group and spans the thread.
 */
val Message.acceptsReactions: Boolean
    get() = when {
        kind == MessageKind.CALL ||
            kind == MessageKind.PAYMENT_EVENT ||
            kind == MessageKind.GROUP_PAYMENT ||
            kind == MessageKind.GROUP_PAYMENT_EVENT ||
            kind == MessageKind.SYSTEM -> false
        else -> when (state) {
            DeliveryState.SENDING,
            DeliveryState.RETRY_REQUIRED,
            DeliveryState.FAILED,
            // A scheduled entry exists on this device only. There is nobody to react to yet, and
            // the message ID it would be pinned to has not been minted.
            DeliveryState.SCHEDULED,
            DeliveryState.UNCONFIRMED,
            -> false
            DeliveryState.SENT, DeliveryState.DELIVERED, DeliveryState.READ -> true
        }
    }

/**
 * Whether this entry can be quoted in an answer.
 *
 * The rule is [acceptsReactions]' rule, for [acceptsReactions]' reason: a reply pins its target's
 * message ID, and an entry that has no server-minted one yet — or is a centred timeline record
 * rather than anybody's words — has nothing an answer could point at on the other end.
 */
val Message.acceptsReplies: Boolean get() = acceptsReactions

/**
 * Whether this account may still replace this message's wording.
 *
 * Only your own words, only the kinds that have wording of their own, and only for fifteen
 * minutes — the same window the server enforces, so the menu never offers something the send
 * would refuse. Past it the message stands, and saying otherwise takes a new one.
 *
 * Payment and membership entries are records of something that happened rather than anybody's
 * phrasing, so there is nothing in them an author could honestly correct. Neither is a photo, a
 * voice note or a document: replacing a media descriptor with a sentence would strand the media
 * its recipients have already downloaded, so those are excluded here and on iOS alike.
 */
fun Message.acceptsEdits(nowEpochMillis: Long): Boolean = fromMe &&
    acceptsReactions &&
    kind != MessageKind.CALL &&
    mediaDescriptor == null &&
    editWindowRemainingMillis(nowEpochMillis) > 0

/**
 * Whether this account can ask what became of this message.
 *
 * Only your own, and only once the server has minted an ID for it: the record is answered to the
 * sender alone, so offering it anywhere else would promise an answer that is always a refusal.
 */
val Message.acceptsDeliveryInfo: Boolean get() = fromMe && acceptsReactions

/** How much of the edit window is left, in millis; zero once it has closed. */
fun Message.editWindowRemainingMillis(nowEpochMillis: Long): Long {
    if (sortEpochMillis <= 0) return 0
    return (sortEpochMillis + MESSAGE_EDIT_WINDOW_MILLIS - nowEpochMillis).coerceAtLeast(0)
}

/**
 * How long after sending an author may still replace the wording.
 *
 * The same figure the server enforces and the same one the other platform shows, so "fifteen
 * minutes to edit" means one thing on the screen and another nowhere.
 */
const val MESSAGE_EDIT_WINDOW_MILLIS = 15L * 60L * 1_000L

/**
 * The one line a quoted message shows above the answer.
 *
 * Media says what it is rather than nothing, because a bare "Photo" placed above a reply is
 * exactly what the person swiped at, while an empty strip would read as a broken quote.
 */
fun Message.replyPreviewLabel(): String = when (kind) {
    MessageKind.VOICE_NOTE -> text.takeIf(String::isNotBlank) ?: "Voice note"
    MessageKind.IMAGE -> text.takeIf(String::isNotBlank) ?: "Photo"
    MessageKind.VIDEO -> text.takeIf(String::isNotBlank) ?: "Video"
    MessageKind.DOCUMENT -> text.takeIf(String::isNotBlank) ?: "Document"
    MessageKind.PAYMENT,
    MessageKind.PAYMENT_REQUEST,
    MessageKind.PAYMENT_TRANSFER,
    -> paymentNote?.takeIf(String::isNotBlank) ?: "Payment"
    MessageKind.TEXT,
    MessageKind.PAYMENT_EVENT,
    MessageKind.GROUP_PAYMENT,
    MessageKind.GROUP_PAYMENT_EVENT,
    MessageKind.CALL,
    MessageKind.SYSTEM,
    -> text
}

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
    /**
     * Who is typing, for a group that has more than one possible typist.
     *
     * Empty for a direct chat: the one person who can be typing there is already named at the top
     * of the screen, and repeating them would read as a second person.
     */
    val typingNames: List<String> = emptyList(),
    val pinned: Boolean = false,
    val muted: Boolean = false,
    val lastFromMe: Boolean = false,
    val lastState: DeliveryState = DeliveryState.READ,
    /**
     * The photo drawn on this row: the peer's profile photo for a direct chat, resolved from
     * the local address book, or the group's own photo for a group.
     */
    val avatarUrl: String? = null,
    /** The group's server-visible description; null for direct chats and undescribed groups. */
    val description: String? = null,
)

/** What a group grants a member. Anything the server sends that is not one of these reads as a
 * plain member, so an unfamiliar future role can never be mistaken for elevated rights. */
enum class ChatMemberRole { OWNER, ADMIN, MEMBER;

    val canManageMembers: Boolean get() = this == OWNER || this == ADMIN

    val label: String
        get() = when (this) {
            OWNER -> "Owner"
            ADMIN -> "Admin"
            MEMBER -> "Member"
        }
}

/** One participant of a group, as the server last reported it. */
data class ChatMember(
    val userId: String,
    val name: String,
    val role: ChatMemberRole = ChatMemberRole.MEMBER,
    /** True for the account looking at the list. */
    val isSelf: Boolean = false,
    val online: Boolean = false,
    val avatarUrl: String? = null,
    /** True when this person is in the local address book, so their name is the saved one. */
    val savedInDevice: Boolean = false,
)

enum class CallDirection { INCOMING, OUTGOING, MISSED }

data class CallEntry(
    val id: String,
    val name: String,
    val time: String,
    val direction: CallDirection,
    val video: Boolean = false,
    val participantUserIds: List<String> = emptyList(),
    /** The direct conversation this call belongs to, when the backend supplied it. */
    val conversationId: String? = null,
    /** Call start time in epoch millis, for interleaving call logs with chat messages. */
    val startedAtEpochMillis: Long = 0,
    /** Connected duration in seconds; zero for missed or unanswered calls. */
    val durationSeconds: Long = 0,
    /** True when the call actually connected (used to distinguish "no answer" from a real call). */
    val answered: Boolean = false,
    /** The single matched participant's profile photo URL, resolved from the local address book. */
    val avatarUrl: String? = null,
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
    /** The Kit Pay account this beneficiary belongs to, when the server says it belongs to one. */
    val kitUserId: String? = null,
    /**
     * The photo to draw over this row's glyph; null leaves the glyph alone. Resolved by
     * [BeneficiaryIdentity], which will not guess.
     */
    val avatarUrl: String? = null,
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
