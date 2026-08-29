package com.kit.wallet.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

internal const val SECURE_MESSAGING_LEGACY_KEY_CONTINUITY_KEY = "legacy_key_continuity"
internal const val SECURE_MESSAGING_LEGACY_KEY_CONTINUITY_VALUE = "pending"

@Entity(tableName = "profile")
data class ProfileEntity(
    @PrimaryKey val singletonId: Int = SINGLETON_ID,
    val userId: String,
    val name: String,
    val phone: String,
    val tag: String,
    val kycLabel: String,
    val email: String?,
    val emailVerified: Boolean,
    val profileSetupRequired: Boolean,
    val avatarUrl: String? = null,
    /**
     * The name on the verified identity document, as the server reported it.
     *
     * Distinct from [name], which is chosen and can be anything. Nothing on the device writes this
     * — it arrives with the profile and is cached so that a financial screen opened offline still
     * knows which name is the verified one. Null means "no verified name", never "not that person".
     */
    val legalName: String? = null,
    /**
     * Whether this account still has to choose a username. Cached rather than re-derived so the
     * setup gate offline agrees with the gate the server applied.
     */
    val usernameRequired: Boolean = true,
    val updatedAtEpochMillis: Long,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}

@Entity(tableName = "wallets")
data class WalletEntity(
    @PrimaryKey val uuid: String,
    val name: String,
    val accountNumber: String?,
    val currencyCode: String,
    val currencyScale: Int,
    val availableBalanceMinor: Long,
    val ledgerBalanceMinor: Long,
    val status: String,
    val kycStatus: String,
    val isPrimary: Boolean,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "wallet_transactions",
    indices = [
        Index(value = ["walletUuid", "occurredAtEpochMillis"]),
        Index(value = ["reference"]),
    ],
)
data class WalletTransactionEntity(
    @PrimaryKey val id: String,
    val walletUuid: String,
    val reference: String,
    val amountMinor: Long,
    val currencyCode: String,
    val currencyScale: Int = 2,
    val type: String,
    val direction: String,
    val status: String,
    val counterpartyName: String,
    val note: String?,
    val occurredAtEpochMillis: Long,
)

@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val key: String,
    val value: String?,
)

/**
 * Viewer-local conversation presentation preferences (pin/mute), matching iOS where these live
 * on-device only and never reach the messaging wire. Cleared with the rest of cached user data.
 */
@Entity(tableName = "conversation_prefs")
data class ConversationPrefEntity(
    @PrimaryKey val conversationId: String,
    val pinned: Boolean = false,
    val muted: Boolean = false,
)

/**
 * Which photo belongs to which person, kept so the answer survives a restart.
 *
 * The photos themselves are already stored on the device, but a stored photo is addressed by its
 * URL and the URL only ever existed in the in-memory contact list — which is empty until a contacts
 * fetch succeeds. So a cold start with no network drew initials for people whose faces were sitting
 * on disk the whole time. This table is the missing half: a plain directory of user to photo URL,
 * written whenever one is learned and read before anything is fetched.
 *
 * It is a display convenience and nothing else. No identity, authorisation or payment decision is
 * taken on it — a row is a URL somebody's picture was last seen at, not a claim about who they are.
 */
@Entity(
    tableName = "profile_photos",
    primaryKeys = ["ownerScopeId", "userId"],
    indices = [Index(value = ["ownerScopeId"])],
)
data class ProfilePhotoEntity(
    /** Exact authenticated cache epoch that is allowed to observe this row. */
    val ownerScopeId: String,
    /** The peer's public user id, lowercased, as every other Kit-user lookup keys on. */
    val userId: String,
    val avatarUrl: String,
    val updatedAtEpochMillis: Long,
)

/**
 * The number a payout destination was saved with, for destinations saved on this device.
 *
 * The server returns beneficiaries with their numbers masked, and a mask cannot be matched back to
 * a person — several real numbers fit one. But at the moment the beneficiary is created this device
 * has the number the user actually typed, so remembering it is what lets a saved destination show
 * the right face afterwards, including offline and after a restart.
 *
 * Only a device-keyed HMAC of the canonical international number is kept. Neither the number nor a
 * globally comparable suffix is present in Room. It is a display convenience and nothing else:
 * no payment is routed or authorised by a row here, and the destination itself remains whatever
 * the server holds. Rows are also scoped to the exact authenticated cache epoch.
 */
@Entity(
    tableName = "beneficiary_contacts",
    primaryKeys = ["ownerScopeId", "beneficiaryId"],
    indices = [Index(value = ["ownerScopeId"])],
)
data class BeneficiaryContactEntity(
    /** Exact authenticated cache epoch that is allowed to observe this row. */
    val ownerScopeId: String,
    /** The server's id for the saved destination. */
    val beneficiaryId: String,
    /** HMAC-SHA-256 of a full canonical international phone identity. */
    val phoneIdentity: String,
    val updatedAtEpochMillis: Long,
)

/**
 * Opaque E2EE state only. Identity/prekey/session bytes and decrypted message projections must be
 * encrypted before they reach Room; namespace/key/version are authenticated as AES-GCM AAD.
 */
@Entity(
    tableName = "secure_messaging_records",
    primaryKeys = ["namespace", "recordKey"],
    indices = [Index(value = ["namespace"])],
)
data class SecureMessagingRecordEntity(
    val namespace: String,
    val recordKey: String,
    val version: Long,
    val iv: ByteArray,
    val ciphertext: ByteArray,
    val updatedAtEpochMillis: Long,
)

/**
 * Non-secret lifecycle metadata for encrypted secure-messaging state. Values in this table may
 * describe migration/recovery work only; keys, ciphertext, and message content remain in their
 * dedicated encrypted stores.
 */
@Entity(tableName = "secure_messaging_metadata")
data class SecureMessagingMetadataEntity(
    @PrimaryKey val key: String,
    val value: String,
)

/**
 * Opaque, account-bound message history that is cryptographically independent from active
 * libsignal state. Plaintext and active protocol/session material must never enter this table.
 */
@Entity(
    tableName = "account_message_archive",
    primaryKeys = ["ownerAccountId", "installationId", "recordKey"],
    indices = [Index(value = ["ownerAccountId", "installationId"])],
)
data class AccountMessageArchiveEntity(
    val ownerAccountId: String,
    val installationId: String,
    val recordKey: String,
    val version: Long,
    val iv: ByteArray,
    val ciphertext: ByteArray,
    val updatedAtEpochMillis: Long,
)

/** A queued support write awaiting the network; retryable with its idempotency identity fixed. */
const val SUPPORT_OUTBOX_STATUS_PENDING = "pending"

/** Definitively rejected by the support endpoint; kept for display until the user discards it. */
const val SUPPORT_OUTBOX_STATUS_FAILED = "failed"

const val SUPPORT_OUTBOX_KIND_OPEN_TICKET = "open_ticket"
const val SUPPORT_OUTBOX_KIND_MESSAGE = "message"

/**
 * Durable idempotent outbox for support writes. A queued ticket-open or message-send
 * survives process death and offline periods with its client-minted `clientMessageId`
 * fixed, so a retry of an unknown outcome can never create a second server record.
 * Content is immutable once enqueued (the server's replay fingerprint binds it); a row
 * leaves `pending` only on server success — which deletes it — or on a definitive
 * endpoint rejection, which marks it `failed` without ever rotating the id. Owner-scoped
 * like every authenticated cache table, so one account's queued support text can neither
 * render under nor be transmitted by another account.
 */
@Entity(
    tableName = "support_outbox",
    primaryKeys = ["ownerScopeId", "clientMessageId"],
    indices = [Index(value = ["ownerScopeId", "ticketId"])],
)
data class SupportOutboxEntity(
    /** Exact authenticated cache epoch that is allowed to observe and flush this row. */
    val ownerScopeId: String,
    /** Client-minted UUID; the server-side idempotency identity of this write. */
    val clientMessageId: String,
    /** [SUPPORT_OUTBOX_KIND_OPEN_TICKET] or [SUPPORT_OUTBOX_KIND_MESSAGE]. */
    val kind: String,
    /** Target ticket for a message row; null for an open-ticket row. */
    val ticketId: String?,
    /** Chosen category key; open-ticket rows only. */
    val categoryKey: String?,
    /** Ticket subject; open-ticket rows only. */
    val subject: String?,
    /** Message text — the opening message for an open-ticket row. */
    val body: String,
    /** [SUPPORT_OUTBOX_STATUS_PENDING] or [SUPPORT_OUTBOX_STATUS_FAILED]. */
    val status: String,
    /** Definitive rejection code once failed; null while pending. */
    val failureCode: String?,
    val createdAtEpochMillis: Long,
    /** When the row last left the device; null while it has never been attempted. */
    val lastAttemptAtEpochMillis: Long?,
)
