package com.kit.wallet.data.remote

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = false)
data class CurrencyDto(
    val code: String,
    val scale: String,
)

@JsonClass(generateAdapter = false)
data class MessagingProtocolDto(
    val ready: Boolean? = null,
    val version: String? = null,
    val suite: String? = null,
    @Json(name = "post_quantum") val postQuantum: Boolean? = null,
    @Json(name = "rich_media") val richMedia: RichMediaProtocolDto? = null,
)

@JsonClass(generateAdapter = false)
data class RichMediaProtocolDto(
    val ready: Boolean? = null,
    val profile: String? = null,
    @Json(name = "supported_platforms") val supportedPlatforms: List<String>? = null,
    @Json(name = "minimum_ciphertext_bytes") val minimumCiphertextBytes: Long? = null,
    @Json(name = "maximum_plaintext_bytes") val maximumPlaintextBytes: Long? = null,
    @Json(name = "maximum_ciphertext_bytes") val maximumCiphertextBytes: Long? = null,
    @Json(name = "media_types") val mediaTypes: List<String>? = null,
)

/**
 * The optional `protocols.realtime` block.
 *
 * Optional in the strongest sense: an absent block, an absent member or a `v` this
 * build does not speak all mean "open no socket and keep polling", which is the
 * server-side kill switch for the whole transport and needs no app release. Every
 * member is nullable for that reason — the negotiation rule lives in
 * `KitRealtimeConfig.from`, not in the parse.
 */
@JsonClass(generateAdapter = false)
data class RealtimeProtocolDto(
    val v: Int? = null,
    val scheme: String? = null,
    val host: String? = null,
    val port: Int? = null,
    /** The complete connect path including the app key segment. Used verbatim. */
    val path: String? = null,
    val key: String? = null,
    val protocol: Int? = null,
    @Json(name = "auth_path") val authPath: String? = null,
    @Json(name = "activity_timeout") val activityTimeoutSeconds: Int? = null,
    @Json(name = "max_connection_seconds") val maxConnectionSeconds: Int? = null,
    val channels: RealtimeChannelsDto? = null,
    val presence: Boolean? = null,
    val typing: Boolean? = null,
    /** Whether the server sends `kit.call.answered` on the user channel. */
    val calls: Boolean? = null,
)

@JsonClass(generateAdapter = false)
data class RealtimeChannelsDto(
    val user: String? = null,
    val conversation: String? = null,
)

@JsonClass(generateAdapter = false)
data class ProtocolsDto(
    val messaging: MessagingProtocolDto? = null,
    val realtime: RealtimeProtocolDto? = null,
)

@JsonClass(generateAdapter = false)
data class CapabilitiesDto(
    @Json(name = "api_version") val apiVersion: String? = null,
    val currency: CurrencyDto,
    val features: Map<String, Boolean?>? = null,
    val authentication: Map<String, Boolean?>? = null,
    val protocols: ProtocolsDto? = null,
)

@JsonClass(generateAdapter = false)
data class DeviceRegistrationDto(
    @Json(name = "installation_id") val installationId: String,
    val name: String,
    val platform: String = "android",
    @Json(name = "app_version") val appVersion: String,
    @Json(name = "os_version") val osVersion: String,
    val model: String,
)

@JsonClass(generateAdapter = false)
data class EmailLoginRequest(
    val email: String,
    val password: String,
    val device: DeviceRegistrationDto,
)

@JsonClass(generateAdapter = false)
data class EmailRegistrationRequest(
    val name: String,
    val tag: String,
    val email: String,
    val password: String,
    @Json(name = "password_confirmation") val passwordConfirmation: String,
    @Json(name = "country_code") val countryCode: String = "UG",
    val locale: String = "en",
    val timezone: String,
)

@JsonClass(generateAdapter = false)
data class EmailVerificationChallengeDto(
    val type: String,
    val method: String,
    val destination: String,
    @Json(name = "expires_at") val expiresAt: String,
)

@JsonClass(generateAdapter = false)
data class EmailRegistrationResultDto(
    val state: String,
    val challenge: EmailVerificationChallengeDto,
    val user: UserDto,
)

@JsonClass(generateAdapter = false)
data class VerifyIdentityTokenRequest(val token: String)

@JsonClass(generateAdapter = false)
data class VerifyEmailResultDto(
    val verified: Boolean? = null,
    val user: UserDto,
)

@JsonClass(generateAdapter = false)
data class EmailAddressRequest(val email: String)

@JsonClass(generateAdapter = false)
data class EmailAttachmentVerificationRequest(
    @Json(name = "challenge_id") val challengeId: String,
    val code: String,
)

@JsonClass(generateAdapter = false)
data class MessageResultDto(val message: String? = null)

@JsonClass(generateAdapter = false)
data class ResetPasswordRequest(
    val token: String,
    val password: String,
    @Json(name = "password_confirmation") val passwordConfirmation: String,
)

@JsonClass(generateAdapter = false)
data class PasswordResetResultDto(
    @Json(name = "password_reset") val passwordReset: Boolean? = null,
)

@JsonClass(generateAdapter = false)
data class PhoneOtpRequest(
    val phone: String,
    val device: DeviceRegistrationDto,
)

@JsonClass(generateAdapter = false)
data class PhoneOtpVerifyRequest(
    @Json(name = "challenge_id") val challengeId: String,
    val phone: String,
    val code: String,
    val device: DeviceRegistrationDto,
)

@JsonClass(generateAdapter = false)
data class TwoFactorVerifyRequest(
    @Json(name = "challenge_id") val challengeId: String,
    val code: String,
)

@JsonClass(generateAdapter = false)
data class RefreshSessionRequest(
    @Json(name = "refresh_token") val refreshToken: String,
    @Json(name = "refresh_replay_nonce") val refreshReplayNonce: String,
)

@JsonClass(generateAdapter = false)
data class LogoutRequest(
    @Json(name = "all_devices") val allDevices: Boolean = false,
)

@JsonClass(generateAdapter = false)
data class AccountDeletionRequirementDto(
    val code: String,
    val message: String,
)

@JsonClass(generateAdapter = false)
data class AccountDeletionNoticeDto(
    val version: String,
    @Json(name = "public_url") val publicUrl: String,
    @Json(name = "deleted_categories") val deletedCategories: List<String> = emptyList(),
    @Json(name = "retained_categories") val retainedCategories: List<String> = emptyList(),
)

@JsonClass(generateAdapter = false)
data class AccountDeletionStepUpDto(
    val purpose: String,
    val intent: Map<String, Any?>,
)

@JsonClass(generateAdapter = false)
data class AccountDeletionPreflightDto(
    val state: String,
    @Json(name = "can_request") val canRequest: Boolean? = null,
    @Json(name = "requires_support") val requiresSupport: Boolean? = null,
    @Json(name = "closure_requirements")
    val closureRequirements: List<AccountDeletionRequirementDto> = emptyList(),
    @Json(name = "step_up") val stepUp: AccountDeletionStepUpDto,
    @Json(name = "confirmation_text") val confirmationText: String,
    val notice: AccountDeletionNoticeDto,
)

@JsonClass(generateAdapter = false)
data class RequestAccountDeletionDto(
    val confirmation: String,
)

@JsonClass(generateAdapter = false)
data class AccountDeletionReceiptDto(
    @Json(name = "receipt_id") val receiptId: String,
    val state: String,
    @Json(name = "account_status") val accountStatus: String,
    @Json(name = "requested_at") val requestedAt: String,
    @Json(name = "requires_support") val requiresSupport: Boolean? = null,
    @Json(name = "closure_requirements")
    val closureRequirements: List<AccountDeletionRequirementDto> = emptyList(),
    val notice: AccountDeletionNoticeDto,
)

@JsonClass(generateAdapter = false)
data class AuthChallengeDto(
    val id: String,
    val type: String,
    val method: String? = null,
    val destination: String? = null,
    @Json(name = "expires_at") val expiresAt: String? = null,
    @Json(name = "resend_after_seconds") val resendAfterSeconds: Double? = null,
)

@JsonClass(generateAdapter = false)
data class AuthResultDto(
    val state: String,
    val challenge: AuthChallengeDto? = null,
    val session: SessionDto? = null,
    val user: UserDto? = null,
    @Json(name = "session_assurance") val sessionAssurance: SessionAssuranceDto? = null,
)

@JsonClass(generateAdapter = false)
data class DeviceIdentityAssuranceDto(
    val status: String,
    val required: Boolean,
    val epoch: Long,
    @Json(name = "verified_at") val verifiedAt: String? = null,
)

@JsonClass(generateAdapter = false)
data class LoginUnlockAssuranceDto(
    val status: String,
    val required: Boolean,
    val methods: List<String>,
    val method: String? = null,
    @Json(name = "unlocked_at") val unlockedAt: String? = null,
)

@JsonClass(generateAdapter = false)
data class SessionAssuranceDto(
    @Json(name = "device_identity") val deviceIdentity: DeviceIdentityAssuranceDto,
    @Json(name = "login_unlock") val loginUnlock: LoginUnlockAssuranceDto,
    val access: String,
)

@JsonClass(generateAdapter = false)
data class SessionAssuranceResultDto(
    @Json(name = "session_assurance") val sessionAssurance: SessionAssuranceDto,
    val method: String? = null,
)

@JsonClass(generateAdapter = false)
data class LoginUnlockPinRequest(val pin: String)

@JsonClass(generateAdapter = false)
data class LoginBiometricChallengeDto(
    @Json(name = "challenge_id") val challengeId: String,
    val nonce: String,
    @Json(name = "signing_payload") val signingPayload: String,
    @Json(name = "expires_at") val expiresAt: String,
)

@JsonClass(generateAdapter = false)
data class LoginBiometricAssertionRequest(
    @Json(name = "challenge_id") val challengeId: String,
    val nonce: String,
    val signature: String,
)

@JsonClass(generateAdapter = false)
data class EnrollBiometricKeyRequest(
    @Json(name = "public_key") val publicKey: String,
    val attestation: Map<String, String>? = null,
)

@JsonClass(generateAdapter = false)
data class BiometricKeyStatusDto(
    @Json(name = "device_id") val deviceId: String? = null,
    val algorithm: String? = null,
    @Json(name = "enrolled_at") val enrolledAt: String? = null,
    @Json(name = "attestation_status") val attestationStatus: String? = null,
    val removed: Boolean? = null,
    @Json(name = "session_assurance") val sessionAssurance: SessionAssuranceDto? = null,
)

@JsonClass(generateAdapter = false)
data class SessionDto(
    @Json(name = "access_token") val accessToken: String,
    @Json(name = "refresh_token") val refreshToken: String,
    @Json(name = "token_type") val tokenType: String = "Bearer",
    @Json(name = "access_expires_at") val accessExpiresAt: String? = null,
    @Json(name = "refresh_expires_at") val refreshExpiresAt: String? = null,
    @Json(name = "session_id") val sessionId: String,
)

@JsonClass(generateAdapter = false)
data class UserDto(
    val id: String,
    // Some legacy phone-created profiles can explicitly serialize a null name. Normalize that
    // presentation field to the setup placeholder instead of rejecting the authenticated response.
    val name: String? = null,
    /**
     * The name printed on the verified identity document.
     *
     * Server-owned and read-only: no request can set it, and neither the chosen display name nor
     * the username ever overwrites it. Null until identity verification has been approved, and on
     * any server that predates the field — which is why nothing here may treat null as "not that
     * person" rather than "not answered".
     */
    @Json(name = "legal_name") val legalName: String? = null,
    /**
     * False once a verified legal name exists, at which point the username is an optional public
     * handle. Null on a server that predates the field; the client then falls back to its own
     * reading of the legal name rather than assuming either answer.
     */
    @Json(name = "username_required") val usernameRequired: Boolean? = null,
    val email: String? = null,
    val phone: String? = null,
    val tag: String? = null,
    @Json(name = "kyc_status") val kycStatus: String? = null,
    // Older production records can explicitly serialize these optional flags as null. Keep the
    // transport values nullable and normalize them at the domain/UI boundary so a legacy record
    // cannot make Moshi reject the entire bootstrap response.
    @Json(name = "payment_pin_set") val paymentPinSet: Boolean? = null,
    @Json(name = "mfa_enabled") val mfaEnabled: Boolean? = null,
    @Json(name = "email_verified") val emailVerified: Boolean? = null,
    @Json(name = "phone_verified") val phoneVerified: Boolean? = null,
    @Json(name = "profile_setup_required") val profileSetupRequired: Boolean? = null,
    @Json(name = "avatar_url") val avatarUrl: String? = null,
)

@JsonClass(generateAdapter = false)
data class CreateMediaUploadIntentRequest(
    val kind: String = "image",
    val purpose: String = "avatar",
    val filename: String = "profile-avatar.jpg",
    @Json(name = "mime_type") val mimeType: String = "image/jpeg",
    @Json(name = "byte_size") val byteSize: Int,
    val sha256: String,
    @Json(name = "client_encrypted") val clientEncrypted: Boolean = false,
)

@JsonClass(generateAdapter = false)
data class MediaUploadIntentDto(
    val asset: MediaAssetDto,
    val upload: MediaUploadInstructionsDto,
)

@JsonClass(generateAdapter = false)
data class MediaUploadInstructionsDto(
    val method: String,
    val url: String,
    val headers: Map<String, String>? = null,
)

@JsonClass(generateAdapter = false)
data class MediaAssetDto(
    val id: String,
    val status: String,
    val scan: MediaScanDto? = null,
)

@JsonClass(generateAdapter = false)
data class MediaScanDto(
    val status: String? = null,
)

@JsonClass(generateAdapter = false)
data class AttachProfileAvatarRequest(
    @Json(name = "asset_id") val assetId: String,
)

/**
 * A profile edit, where "not mentioned" and "set to nothing" are different requests.
 *
 * Every field is optional and an omitted one is left alone. [clearUsername] is the only way to say
 * "drop my username", which the server accepts once a verified legal name exists — and which has to
 * travel as an explicit JSON null, so this type is written by [UpdateProfileRequestAdapter] rather
 * than by the reflective adapter that would silently drop it.
 *
 * An entirely empty request is meaningful: it is how someone who verified their identity and chose
 * no username at all completes profile setup.
 */
@JsonClass(generateAdapter = false)
data class UpdateProfileRequest(
    val name: String? = null,
    val tag: String? = null,
    val clearUsername: Boolean = false,
)

@JsonClass(generateAdapter = false)
data class CommunicationPreferencesDto(
    // Keep privacy flags nullable at the transport boundary. Missing legacy values are never
    // interpreted as consent; the repository maps every null flag to false.
    val version: Long? = null,
    @Json(name = "phone_discoverable") val phoneDiscoverable: Boolean? = null,
    @Json(name = "direct_message_requests_enabled")
    val directMessageRequestsEnabled: Boolean? = null,
    @Json(name = "incoming_calls_enabled") val incomingCallsEnabled: Boolean? = null,
    // The one flag whose server default is `true`, and therefore the one null the repository must
    // not read as `false`: a server old enough to omit it has no presence channels at all, so
    // rendering "hidden" would promise a privacy state nothing is actually enforcing, and would
    // silently become a lie the moment that server gained the column.
    @Json(name = "messaging_presence_visible") val messagingPresenceVisible: Boolean? = null,
    @Json(name = "updated_at") val updatedAt: String? = null,
)

@JsonClass(generateAdapter = false)
data class UpdateCommunicationPreferencesRequest(
    val version: Long,
    @Json(name = "phone_discoverable") val phoneDiscoverable: Boolean? = null,
    @Json(name = "direct_message_requests_enabled")
    val directMessageRequestsEnabled: Boolean? = null,
    @Json(name = "incoming_calls_enabled") val incomingCallsEnabled: Boolean? = null,
    @Json(name = "messaging_presence_visible") val messagingPresenceVisible: Boolean? = null,
)

@JsonClass(generateAdapter = false)
data class CommunicationBlockDto(
    @Json(name = "user_id") val userId: String? = null,
    val blocked: Boolean? = null,
    @Json(name = "blocked_at") val blockedAt: String? = null,
    @Json(name = "unblocked_at") val unblockedAt: String? = null,
)

@JsonClass(generateAdapter = false)
data class CommunicationBlockPageDto(
    val items: List<CommunicationBlockDto>? = null,
    val page: CursorPageDto? = null,
)

@JsonClass(generateAdapter = false)
data class TotpEnrollmentDto(
    @Json(name = "enrollment_id") val enrollmentId: String,
    val secret: String,
    @Json(name = "provisioning_uri") val provisioningUri: String,
    @Json(name = "expires_at") val expiresAt: String,
)

@JsonClass(generateAdapter = false)
data class MfaCodeRequest(val code: String)

@JsonClass(generateAdapter = false)
data class MfaStatusDto(
    val enabled: Boolean? = null,
    @Json(name = "recovery_codes") val recoveryCodes: List<String>? = null,
)

@JsonClass(generateAdapter = false)
data class RecoveryCodesDto(
    @Json(name = "recovery_codes") val recoveryCodes: List<String>? = null,
)

@JsonClass(generateAdapter = false)
data class BootstrapDto(
    val user: UserDto,
    // These are authoritative account and security collections. Keep them strict so a malformed
    // null response cannot be mistaken for an instruction to erase cached wallets or devices.
    val wallets: List<WalletDto> = emptyList(),
    val devices: List<DeviceDto> = emptyList(),
    @Json(name = "selected_wallet_id") val selectedWalletId: String? = null,
)

@JsonClass(generateAdapter = false)
data class WalletListDto(
    val items: List<WalletDto> = emptyList(),
)

@JsonClass(generateAdapter = false)
data class WalletBalancesDto(
    val available: String,
    val ledger: String? = null,
)

@JsonClass(generateAdapter = false)
data class WalletDto(
    val id: String,
    val name: String,
    @Json(name = "account_number") val accountNumber: String? = null,
    @Json(name = "account_type") val accountType: String? = null,
    val currency: CurrencyDto,
    val balances: WalletBalancesDto,
    val status: String,
    @Json(name = "kyc_status") val kycStatus: String? = null,
    @Json(name = "is_primary") val isPrimary: Boolean? = null,
    @Json(name = "updated_at") val updatedAt: String? = null,
)

@JsonClass(generateAdapter = false)
data class TransactionPageDto(
    val items: List<TransactionDto> = emptyList(),
    val page: CursorPageDto,
)

@JsonClass(generateAdapter = false)
data class CursorPageDto(
    @Json(name = "next_cursor") val nextCursor: String? = null,
    @Json(name = "has_more") val hasMore: Boolean? = null,
    // Pagination metadata is informational to the Android client. Tolerate legacy/null metadata
    // rather than rejecting an otherwise usable page during response deserialization.
    val limit: Int? = null,
)

@JsonClass(generateAdapter = false)
data class CounterpartyDto(
    val id: String? = null,
    val name: String? = null,
    val phone: String? = null,
    @Json(name = "account_number") val accountNumber: String? = null,
)

@JsonClass(generateAdapter = false)
data class TransactionDto(
    val id: String,
    @Json(name = "wallet_id") val walletId: String,
    val reference: String,
    val amount: String,
    val currency: CurrencyDto,
    val type: String,
    val direction: String,
    val status: String,
    val counterparty: CounterpartyDto? = null,
    val note: String? = null,
    /** Present on held Kit → Kit transfers and on the reversal that returned one. */
    val claim: TransferClaimDto? = null,
    @Json(name = "occurred_at") val occurredAt: String,
)

/**
 * A Kit → Kit transfer the recipient has not taken yet, or the record of how one ended.
 *
 * `reason` and `resolvedBy` are what let the conversation say why a payment came back rather than
 * showing money quietly disappearing.
 */
@JsonClass(generateAdapter = false)
data class TransferClaimDto(
    val id: String,
    @Json(name = "transaction_id") val transactionId: String,
    val reference: String? = null,
    val status: String,
    val amount: String,
    val currency: CurrencyDto,
    val note: String? = null,
    val sender: TransferClaimPartyDto? = null,
    val recipient: TransferClaimPartyDto? = null,
    val reason: String? = null,
    @Json(name = "resolved_by") val resolvedBy: String? = null,
    @Json(name = "reversal_transaction_id") val reversalTransactionId: String? = null,
    @Json(name = "expires_at") val expiresAt: String? = null,
    @Json(name = "accepted_at") val acceptedAt: String? = null,
    @Json(name = "returned_at") val returnedAt: String? = null,
    @Json(name = "created_at") val createdAt: String? = null,
    @Json(name = "can_accept") val canAccept: Boolean = false,
    @Json(name = "can_reject") val canReject: Boolean = false,
    @Json(name = "can_reverse") val canReverse: Boolean = false,
)

@JsonClass(generateAdapter = false)
data class TransferClaimPartyDto(
    val id: String? = null,
    val name: String? = null,
    val phone: String? = null,
)

@JsonClass(generateAdapter = false)
data class TransferClaimPageDto(
    val items: List<TransferClaimDto> = emptyList(),
    val page: CursorPageDto? = null,
)

@JsonClass(generateAdapter = false)
data class TransferClaimResolutionRequest(
    val reason: String? = null,
)

@JsonClass(generateAdapter = false)
data class DeviceListDto(
    val items: List<DeviceDto> = emptyList(),
)

@JsonClass(generateAdapter = false)
data class DeviceDto(
    val id: String,
    val name: String,
    val platform: String,
    val model: String? = null,
    @Json(name = "is_current") val isCurrent: Boolean? = null,
    @Json(name = "last_seen_at") val lastSeenAt: String? = null,
    @Json(name = "created_at") val createdAt: String? = null,
    @Json(name = "is_trusted") val isTrusted: Boolean? = null,
    @Json(name = "trust_expires_at") val trustExpiresAt: String? = null,
)

@JsonClass(generateAdapter = false)
data class ContactListDto(
    val items: List<ContactDto>? = null,
    val page: CursorPageDto? = null,
)

@JsonClass(generateAdapter = false)
data class ContactDto(
    val id: String,
    @Json(name = "contact_id") val contactId: String? = null,
    val name: String,
    val phone: String,
    @Json(name = "is_kit_user") val isKitUser: Boolean? = null,
    val favorite: Boolean? = null,
    val status: String? = null,
    val tag: String? = null,
    @Json(name = "avatar_url") val avatarUrl: String? = null,
    @Json(name = "receiving_wallet_id") val receivingWalletId: String? = null,
)

@JsonClass(generateAdapter = false)
data class ContactSyncRequest(
    val contacts: List<DeviceContactDto>,
)

@JsonClass(generateAdapter = false)
data class BeginContactSyncRequest(
    @Json(name = "client_sync_id") val clientSyncId: String,
    @Json(name = "total_contact_count") val totalContactCount: Int,
    @Json(name = "snapshot_scope") val snapshotScope: String,
)

@JsonClass(generateAdapter = false)
data class ContactSyncSessionResponseDto(
    val sync: ContactSyncSessionDto,
)

@JsonClass(generateAdapter = false)
data class ContactSyncChunkResponseDto(
    val sync: ContactSyncSessionDto,
    val chunk: ContactSyncChunkDto,
)

@JsonClass(generateAdapter = false)
data class ContactSyncSessionDto(
    val id: String,
    @Json(name = "client_sync_id") val clientSyncId: String,
    val generation: Int,
    val status: String,
    @Json(name = "snapshot_scope") val snapshotScope: String,
    @Json(name = "chunk_size") val chunkSize: Int,
    @Json(name = "total_contact_count") val totalContactCount: Int,
    @Json(name = "total_chunk_count") val totalChunkCount: Int,
    @Json(name = "received_contact_count") val receivedContactCount: Int,
    @Json(name = "received_chunk_count") val receivedChunkCount: Int,
    @Json(name = "accepted_contact_count") val acceptedContactCount: Int,
    @Json(name = "stored_contact_count") val storedContactCount: Int? = null,
    @Json(name = "missing_chunk_indexes") val missingChunkIndexes: List<Int>,
    @Json(name = "expires_at") val expiresAt: String,
)

@JsonClass(generateAdapter = false)
data class ContactSyncChunkDto(
    val index: Int,
    @Json(name = "input_count") val inputCount: Int,
    @Json(name = "accepted_count") val acceptedCount: Int,
    val replayed: Boolean,
)

@JsonClass(generateAdapter = false)
data class DeviceContactDto(
    val phone: String,
    val name: String,
    val favorite: Boolean = false,
)

@JsonClass(generateAdapter = false)
data class ProviderProductListDto(
    val items: List<ProviderProductDto>? = null,
)

@JsonClass(generateAdapter = false)
data class ProviderProductDto(
    val id: String,
    val code: String,
    val name: String,
    @Json(name = "service_type") val serviceType: String,
    val provider: ProviderSummaryDto,
    val category: ProviderCategoryDto,
    val currency: CurrencyDto,
    @Json(name = "minimum_amount") val minimumAmount: String? = null,
    @Json(name = "maximum_amount") val maximumAmount: String? = null,
)

@JsonClass(generateAdapter = false)
data class ProviderSummaryDto(
    val id: String,
    val code: String,
    val name: String,
    @Json(name = "country_code") val countryCode: String,
)

@JsonClass(generateAdapter = false)
data class ProviderCategoryDto(
    val id: String,
    @Json(name = "service_type") val serviceType: String,
    val code: String,
    val name: String,
    // Ordering is currently supplied by the API list itself; an explicit null must not make the
    // provider catalogue fail to load.
    @Json(name = "display_order") val displayOrder: Int? = null,
)

@JsonClass(generateAdapter = false)
data class CallPageDto(
    val items: List<CallDto>? = null,
    val page: CursorPageDto? = null,
)

@JsonClass(generateAdapter = false)
data class CallDto(
    val id: String,
    @Json(name = "conversation_id") val conversationId: String? = null,
    // Calls can reference a legacy phone-created user whose profile name is still null. Keep the
    // call usable with a neutral presentation label until that user completes profile setup.
    val name: String? = null,
    @Json(name = "participant_user_ids") val participantUserIds: List<String>? = null,
    val direction: String,
    val type: String,
    val video: Boolean? = null,
    val state: String,
    @Json(name = "started_at") val startedAt: String,
    @Json(name = "answered_at") val answeredAt: String? = null,
    @Json(name = "ended_at") val endedAt: String? = null,
    @Json(name = "ring_expires_at") val ringExpiresAt: String? = null,
)

@JsonClass(generateAdapter = false)
data class StartCallRequest(
    @Json(name = "recipient_user_ids") val recipientUserIds: List<String>,
    val type: String,
    @Json(name = "conversation_id") val conversationId: String? = null,
    @Json(name = "client_call_id") val clientCallId: String? = null,
)

@JsonClass(generateAdapter = false)
data class CancelCallAttemptDto(
    @Json(name = "client_call_id") val clientCallId: String,
    val cancelled: Boolean,
)

@JsonClass(generateAdapter = false)
data class InviteCallRequest(
    @Json(name = "recipient_user_ids") val recipientUserIds: List<String>,
)

@JsonClass(generateAdapter = false)
data class EndCallRequest(val reason: String = "completed")

@JsonClass(generateAdapter = false)
data class IceServerDto(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null,
    @Json(name = "credential_type") val credentialType: String? = null,
)

@JsonClass(generateAdapter = false)
data class RtcCredentialsDto(
    val provider: String,
    val url: String,
    val token: String,
    val room: String,
    @Json(name = "ice_servers") val iceServers: List<IceServerDto>? = null,
    @Json(name = "expires_at") val expiresAt: String,
)

@JsonClass(generateAdapter = false)
data class CallSessionDto(
    val call: CallDto,
    val rtc: RtcCredentialsDto,
    /**
     * When the server built this response. Paired with `call.answered_at` it gives the
     * call's true age at delivery measured on one clock, so a timer started from it never
     * inherits the phone's drift. Null on servers that predate the field.
     */
    @Json(name = "server_time") val serverTime: String? = null,
)

@JsonClass(generateAdapter = false)
data class CreateStepUpChallengeRequest(
    val purpose: String,
    val intent: Map<String, Any?>,
)

@JsonClass(generateAdapter = false)
data class StepUpChallengeDto(
    val id: String,
    val purpose: String,
    @Json(name = "intent_hash") val intentHash: String,
    val nonce: String,
    @Json(name = "signing_payload") val signingPayload: String,
    val methods: List<String>? = null,
    @Json(name = "expires_at") val expiresAt: String,
)

@JsonClass(generateAdapter = false)
data class VerifyStepUpChallengeRequest(
    val pin: String,
)

@JsonClass(generateAdapter = false)
data class VerifyBiometricStepUpRequest(
    val nonce: String,
    val signature: String,
)

@JsonClass(generateAdapter = false)
data class StepUpVerificationDto(
    @Json(name = "step_up_token") val stepUpToken: String,
    @Json(name = "expires_at") val expiresAt: String,
    val method: String,
)

@JsonClass(generateAdapter = false)
data class WalletTransferRequest(
    @Json(name = "destination_wallet_id") val destinationWalletId: String,
    val amount: String,
    val note: String? = null,
)

@JsonClass(generateAdapter = false)
data class CreatePaymentRequestDto(
    @Json(name = "destination_wallet_id") val destinationWalletId: String,
    @Json(name = "requested_from_user_id") val requestedFromUserId: String,
    val amount: String,
    val note: String? = null,
)

@JsonClass(generateAdapter = false)
data class PaymentRequestDto(
    val id: String,
    val type: String,
    val status: String,
    @Json(name = "destination_wallet_id") val destinationWalletId: String,
    @Json(name = "requested_from_user_id") val requestedFromUserId: String? = null,
    val amount: String,
    val currency: CurrencyDto,
    val note: String? = null,
    @Json(name = "expires_at") val expiresAt: String? = null,
    @Json(name = "wallet_transaction_id") val walletTransactionId: String? = null,
    @Json(name = "paid_at") val paidAt: String? = null,
    @Json(name = "created_at") val createdAt: String? = null,
)

@JsonClass(generateAdapter = false)
data class PaymentRequestListDto(
    val items: List<PaymentRequestDto>? = null,
)

@JsonClass(generateAdapter = false)
data class PayPaymentRequestDto(
    @Json(name = "source_wallet_id") val sourceWalletId: String,
)

@JsonClass(generateAdapter = false)
data class SearchResultsDto(
    val items: List<SearchResultItemDto>? = null,
)

@JsonClass(generateAdapter = false)
data class SearchResultItemDto(
    val type: String,
    val id: String,
    val title: String? = null,
    val subtitle: String? = null,
)

@JsonClass(generateAdapter = false)
data class CreateProviderQuoteRequest(
    val account: String,
    val amount: String,
)

@JsonClass(generateAdapter = false)
data class ProviderQuoteDto(
    val id: String,
    @Json(name = "product_id") val productId: String,
    @Json(name = "provider_code") val providerCode: String,
    @Json(name = "service_type") val serviceType: String,
    @Json(name = "account_display") val accountDisplay: String? = null,
    val amount: String,
    val fee: String,
    val total: String,
    val currency: CurrencyDto,
    @Json(name = "expires_at") val expiresAt: String,
)

@JsonClass(generateAdapter = false)
data class CreateProviderOperationRequest(
    @Json(name = "quote_id") val quoteId: String,
    @Json(name = "wallet_id") val walletId: String,
    @Json(name = "client_reference") val clientReference: String,
)

@JsonClass(generateAdapter = false)
data class ProviderOperationDto(
    val id: String,
    val type: String,
    val status: String,
    @Json(name = "wallet_id") val walletId: String,
    @Json(name = "provider_code") val providerCode: String,
    @Json(name = "product_id") val productId: String,
    @Json(name = "product_name") val productName: String,
    @Json(name = "account_display") val accountDisplay: String? = null,
    val amount: String,
    val fee: String,
    val total: String,
    val currency: CurrencyDto,
    @Json(name = "client_reference") val clientReference: String? = null,
    @Json(name = "provider_status") val providerStatus: String? = null,
    @Json(name = "provider_reference") val providerReference: String? = null,
    @Json(name = "created_at") val createdAt: String? = null,
)

@JsonClass(generateAdapter = false)
data class SetPaymentPinRequest(
    val pin: String,
    @Json(name = "pin_confirmation") val pinConfirmation: String,
    @Json(name = "current_pin") val currentPin: String? = null,
)

@JsonClass(generateAdapter = false)
data class PaymentPinStatusDto(
    @Json(name = "payment_pin_set") val paymentPinSet: Boolean? = null,
    @Json(name = "payment_pin_set_at") val paymentPinSetAt: String? = null,
    /** Newer services return the session state so a first PIN can unlock without a re-fetch. */
    @Json(name = "session_assurance") val sessionAssurance: SessionAssuranceDto? = null,
)

@JsonClass(generateAdapter = false)
data class BankDto(
    val id: String,
    val code: String,
    val name: String,
    @Json(name = "country_code") val countryCode: String,
    val currency: String,
    val capabilities: Map<String, Boolean?>? = null,
)

@JsonClass(generateAdapter = false)
data class BankListDto(
    val items: List<BankDto>? = null,
)

@JsonClass(generateAdapter = false)
data class BankFundingAccountBankDto(
    val id: String,
    val name: String,
    val code: String,
    @Json(name = "country_code") val countryCode: String,
)

@JsonClass(generateAdapter = false)
data class BankFundingAccountDto(
    val id: String,
    val label: String,
    val bank: BankFundingAccountBankDto,
    @Json(name = "account_name") val accountName: String,
    @Json(name = "account_number") val accountNumber: String,
    @Json(name = "account_number_masked") val accountNumberMasked: String,
    @Json(name = "branch_name") val branchName: String? = null,
    @Json(name = "branch_code") val branchCode: String? = null,
    @Json(name = "swift_code") val swiftCode: String? = null,
    val instructions: String? = null,
    val currency: String,
    val status: String,
)

@JsonClass(generateAdapter = false)
data class BankFundingAccountListDto(
    val items: List<BankFundingAccountDto> = emptyList(),
)

@JsonClass(generateAdapter = false)
data class BankDepositProofDto(
    @Json(name = "asset_id") val assetId: String,
    val filename: String,
    val status: String,
    @Json(name = "scan_status") val scanStatus: String,
    @Json(name = "mime_type") val mimeType: String? = null,
    @Json(name = "byte_size") val byteSize: Long? = null,
)

@JsonClass(generateAdapter = false)
data class BankDepositRejectionDto(
    val code: String,
    val reason: String? = null,
)

@JsonClass(generateAdapter = false)
data class BankDepositRequestDto(
    val id: String,
    val reference: String,
    @Json(name = "wallet_id") val walletId: String,
    val amount: String,
    val currency: CurrencyDto,
    val status: String,
    val source: String,
    @Json(name = "funding_account") val fundingAccount: BankFundingAccountDto,
    val proof: BankDepositProofDto? = null,
    @Json(name = "bank_transaction_reference") val bankTransactionReference: String? = null,
    @Json(name = "customer_note") val customerNote: String? = null,
    val rejection: BankDepositRejectionDto? = null,
    @Json(name = "expires_at") val expiresAt: String,
    @Json(name = "created_at") val createdAt: String? = null,
    @Json(name = "proof_submitted_at") val proofSubmittedAt: String? = null,
    @Json(name = "completed_at") val completedAt: String? = null,
)

@JsonClass(generateAdapter = false)
data class BankDepositRequestListDto(
    val items: List<BankDepositRequestDto> = emptyList(),
)

@JsonClass(generateAdapter = false)
data class CreateBankDepositRequest(
    @Json(name = "wallet_id") val walletId: String,
    @Json(name = "funding_account_id") val fundingAccountId: String,
    val amount: String,
    val note: String? = null,
)

@JsonClass(generateAdapter = false)
data class AttachBankDepositProofRequest(
    @Json(name = "media_asset_id") val mediaAssetId: String,
)

@JsonClass(generateAdapter = false)
data class CreateBankVerificationRequest(
    @Json(name = "bank_id") val bankId: String,
    @Json(name = "account_number") val accountNumber: String,
)

@JsonClass(generateAdapter = false)
data class BankVerificationDto(
    val id: String,
    @Json(name = "bank_id") val bankId: String,
    val status: String,
    @Json(name = "account_number_masked") val accountNumberMasked: String,
    @Json(name = "verified_account_name") val verifiedAccountName: String? = null,
)

@JsonClass(generateAdapter = false)
data class CreateBankBeneficiaryRequest(
    @Json(name = "verification_id") val verificationId: String,
    val kind: String,
    val label: String,
)

@JsonClass(generateAdapter = false)
data class CreateBankingOperationRequest(
    @Json(name = "wallet_id") val walletId: String,
    @Json(name = "beneficiary_id") val beneficiaryId: String,
    val amount: String,
)

@JsonClass(generateAdapter = false)
data class CreateBankingOutboundQuoteRequest(
    @Json(name = "wallet_id") val walletId: String,
    @Json(name = "beneficiary_id") val beneficiaryId: String,
    val amount: String,
    @Json(name = "fee_mode") val feeMode: String,
)

@JsonClass(generateAdapter = false)
data class CreateQuotedBankingOperationRequest(
    @Json(name = "quote_id") val quoteId: String,
)

@JsonClass(generateAdapter = false)
data class BankingQuoteBankDto(val id: String, val code: String, val name: String)

@JsonClass(generateAdapter = false)
data class BankingQuoteStepUpDto(val purpose: String, val intent: Map<String, String>)

@JsonClass(generateAdapter = false)
data class BankingOutboundQuoteDto(
    val id: String,
    val action: String,
    @Json(name = "operation_type") val operationType: String,
    @Json(name = "fee_mode") val feeMode: String,
    @Json(name = "wallet_id") val walletId: String,
    @Json(name = "beneficiary_id") val beneficiaryId: String,
    val bank: BankingQuoteBankDto,
    @Json(name = "recipient_amount") val recipientAmount: String,
    @Json(name = "processing_fee") val processingFee: String,
    @Json(name = "provider_fee") val providerFee: String,
    @Json(name = "kit_fee") val kitFee: String,
    @Json(name = "provider_fee_cap") val providerFeeCap: String,
    @Json(name = "maximum_provider_total") val maximumProviderTotal: String,
    @Json(name = "customer_debit") val customerDebit: String,
    @Json(name = "kit_debit") val kitDebit: String,
    @Json(name = "schedule_version") val scheduleVersion: String,
    @Json(name = "schedule_verified") val scheduleVerified: Boolean,
    val currency: CurrencyDto,
    @Json(name = "expires_at") val expiresAt: String,
    @Json(name = "step_up") val stepUp: BankingQuoteStepUpDto,
)

@JsonClass(generateAdapter = false)
data class BankBeneficiaryListDto(
    val items: List<BankBeneficiaryDto> = emptyList(),
)

@JsonClass(generateAdapter = false)
data class BankBeneficiaryDto(
    val id: String,
    val kind: String,
    val label: String,
    val bank: BankDto,
    @Json(name = "account_name") val accountName: String? = null,
    @Json(name = "account_number_masked") val accountNumberMasked: String,
    val status: String,
    @Json(name = "kit_user") val kitUser: BeneficiaryKitUserDto? = null,
)

@JsonClass(generateAdapter = false)
data class BankingOperationListDto(
    val items: List<BankingOperationDto> = emptyList(),
)

@JsonClass(generateAdapter = false)
data class BankingOutboundPricingDto(
    @Json(name = "fee_mode") val feeMode: String,
    @Json(name = "recipient_amount") val recipientAmount: String,
    @Json(name = "processing_fee") val processingFee: String,
    @Json(name = "provider_fee") val providerFee: String,
    @Json(name = "kit_fee") val kitFee: String,
    @Json(name = "provider_fee_cap") val providerFeeCap: String,
    @Json(name = "maximum_provider_total") val maximumProviderTotal: String,
    @Json(name = "customer_debit") val customerDebit: String,
    @Json(name = "kit_debit") val kitDebit: String,
    @Json(name = "schedule_version") val scheduleVersion: String,
    @Json(name = "actual_provider_fee") val actualProviderFee: String? = null,
    @Json(name = "actual_provider_total") val actualProviderTotal: String? = null,
)

@JsonClass(generateAdapter = false)
data class BankingOperationDto(
    val id: String,
    val reference: String,
    val type: String,
    val direction: String,
    val status: String,
    @Json(name = "submission_stage") val submissionStage: String? = null,
    @Json(name = "bank_id") val bankId: String,
    @Json(name = "beneficiary_id") val beneficiaryId: String? = null,
    @Json(name = "wallet_id") val walletId: String,
    val amount: String,
    @Json(name = "outbound_quote_id") val outboundQuoteId: String? = null,
    @Json(name = "outbound_pricing") val outboundPricing: BankingOutboundPricingDto? = null,
    @Json(name = "fee_quote_id") val feeQuoteId: String? = null,
    @Json(name = "fee_mode") val feeMode: String? = null,
    @Json(name = "requested_amount") val requestedAmount: String? = null,
    @Json(name = "provider_fee") val providerFee: String? = null,
    @Json(name = "provider_fee_estimated") val providerFeeEstimated: Boolean? = null,
    @Json(name = "platform_fee") val platformFee: String? = null,
    @Json(name = "rounding_adjustment") val roundingAdjustment: String? = null,
    @Json(name = "total_fees") val totalFees: String? = null,
    @Json(name = "net_amount") val netAmount: String? = null,
    val currency: CurrencyDto,
    @Json(name = "provider_reference") val providerReference: String? = null,
    @Json(name = "wallet_transaction_id") val walletTransactionId: String? = null,
    @Json(name = "created_at") val createdAt: String? = null,
    @Json(name = "completed_at") val completedAt: String? = null,
)

@JsonClass(generateAdapter = false)
data class MobileMoneyNetworkDto(
    val id: String,
    val code: String,
    val name: String,
    val currency: CurrencyDto,
    val capabilities: Map<String, Boolean?>? = null,
)

@JsonClass(generateAdapter = false)
data class MobileMoneyNetworkListDto(
    val items: List<MobileMoneyNetworkDto>? = null,
)

@JsonClass(generateAdapter = false)
data class MobileMoneyFailureDto(
    val code: String,
    val message: String? = null,
)

@JsonClass(generateAdapter = false)
data class CreateMobileMoneyVerificationRequest(
    val network: String,
    @Json(name = "phone_number") val phoneNumber: String,
)

@JsonClass(generateAdapter = false)
data class MobileMoneyVerificationDto(
    val id: String,
    @Json(name = "bank_id") val bankId: String,
    val status: String,
    @Json(name = "account_number_masked") val accountNumberMasked: String,
    @Json(name = "verified_account_name") val verifiedAccountName: String? = null,
    val failure: MobileMoneyFailureDto? = null,
    @Json(name = "verified_at") val verifiedAt: String? = null,
    val network: MobileMoneyNetworkDto,
)

@JsonClass(generateAdapter = false)
data class CreateMobileMoneyAccountRequest(
    @Json(name = "verification_id") val verificationId: String,
    val kind: String,
    val label: String,
)

@JsonClass(generateAdapter = false)
data class MobileMoneyAccountDto(
    val id: String,
    val kind: String,
    val label: String,
    val network: MobileMoneyNetworkDto,
    @Json(name = "account_name") val accountName: String? = null,
    @Json(name = "phone_number_masked") val phoneNumberMasked: String,
    val status: String,
    @Json(name = "kit_user") val kitUser: BeneficiaryKitUserDto? = null,
)

/**
 * The Kit Pay account behind a saved payout destination, when the server can match one.
 *
 * Optional and absent from every response until the backend supplies it, which is deliberate: an
 * older or partial payload simply produces no photo rather than a wrong one. The server has to be
 * the one to answer this, because the number it matched on is masked by the time this app sees it.
 */
@JsonClass(generateAdapter = false)
data class BeneficiaryKitUserDto(
    val id: String,
    @Json(name = "avatar_url") val avatarUrl: String? = null,
)

@JsonClass(generateAdapter = false)
data class MobileMoneyAccountListDto(
    val items: List<MobileMoneyAccountDto> = emptyList(),
)

@JsonClass(generateAdapter = false)
data class CreateMobileMoneyOperationRequest(
    @Json(name = "wallet_id") val walletId: String,
    @Json(name = "account_id") val accountId: String,
    val amount: String,
)

@JsonClass(generateAdapter = false)
data class CreateMobileMoneyQuoteRequest(
    @Json(name = "wallet_id") val walletId: String,
    @Json(name = "account_id") val accountId: String,
    val amount: String,
    @Json(name = "fee_mode") val feeMode: String,
)

@JsonClass(generateAdapter = false)
data class CreateQuotedMobileMoneyOperationRequest(
    @Json(name = "quote_id") val quoteId: String,
)

@JsonClass(generateAdapter = false)
data class MobileMoneyQuoteStepUpDto(
    val purpose: String,
    val intent: Map<String, String>,
)

@JsonClass(generateAdapter = false)
data class MobileMoneyQuoteDto(
    val id: String,
    val action: String,
    @Json(name = "fee_mode") val feeMode: String,
    @Json(name = "wallet_id") val walletId: String,
    @Json(name = "account_id") val accountId: String,
    val network: String,
    val currency: CurrencyDto,
    @Json(name = "requested_amount") val requestedAmount: String? = null,
    @Json(name = "provider_amount") val providerAmount: String? = null,
    @Json(name = "recipient_amount") val recipientAmount: String? = null,
    @Json(name = "customer_debit") val customerDebit: String? = null,
    @Json(name = "total_fees") val totalFees: String? = null,
    @Json(name = "processing_fee") val processingFee: String? = null,
    @Json(name = "provider_fee") val providerFee: String,
    @Json(name = "platform_fee") val platformFee: String? = null,
    @Json(name = "kit_fee") val kitFee: String? = null,
    @Json(name = "rounding_adjustment") val roundingAdjustment: String? = null,
    @Json(name = "wallet_credit") val walletCredit: String? = null,
    @Json(name = "provider_fee_cap") val providerFeeCap: String? = null,
    @Json(name = "maximum_provider_total") val maximumProviderTotal: String? = null,
    @Json(name = "kit_debit") val kitDebit: String? = null,
    @Json(name = "schedule_version") val scheduleVersion: String? = null,
    @Json(name = "schedule_verified") val scheduleVerified: Boolean? = null,
    @Json(name = "expires_at") val expiresAt: String,
    @Json(name = "step_up") val stepUp: MobileMoneyQuoteStepUpDto,
)

@JsonClass(generateAdapter = false)
data class MobileMoneyOperationDto(
    val id: String,
    val reference: String,
    val type: String,
    val direction: String,
    val status: String,
    @Json(name = "submission_stage") val submissionStage: String? = null,
    @Json(name = "bank_id") val bankId: String,
    @Json(name = "beneficiary_id") val beneficiaryId: String? = null,
    @Json(name = "wallet_id") val walletId: String,
    val amount: String,
    @Json(name = "outbound_quote_id") val outboundQuoteId: String? = null,
    @Json(name = "outbound_pricing") val outboundPricing: BankingOutboundPricingDto? = null,
    @Json(name = "fee_quote_id") val feeQuoteId: String? = null,
    @Json(name = "fee_mode") val feeMode: String? = null,
    @Json(name = "requested_amount") val requestedAmount: String? = null,
    @Json(name = "provider_fee") val providerFee: String? = null,
    @Json(name = "provider_fee_estimated") val providerFeeEstimated: Boolean? = null,
    @Json(name = "platform_fee") val platformFee: String? = null,
    @Json(name = "rounding_adjustment") val roundingAdjustment: String? = null,
    @Json(name = "total_fees") val totalFees: String? = null,
    @Json(name = "net_amount") val netAmount: String? = null,
    val currency: CurrencyDto,
    @Json(name = "provider_reference") val providerReference: String? = null,
    @Json(name = "wallet_transaction_id") val walletTransactionId: String? = null,
    val failure: MobileMoneyFailureDto? = null,
    @Json(name = "created_at") val createdAt: String? = null,
    @Json(name = "completed_at") val completedAt: String? = null,
    @Json(name = "mobile_money_type") val mobileMoneyType: String,
    val network: MobileMoneyNetworkDto,
)

@JsonClass(generateAdapter = false)
data class MobileMoneyOperationListDto(
    val items: List<MobileMoneyOperationDto> = emptyList(),
)

@JsonClass(generateAdapter = false)
data class KycCaseDto(
    val reference: String,
    val status: String,
    @Json(name = "decision_code") val decisionCode: String? = null,
    @Json(name = "submitted_at") val submittedAt: String? = null,
    @Json(name = "reviewed_at") val reviewedAt: String? = null,
)

@JsonClass(generateAdapter = false)
data class KycProviderSessionDto(
    val provider: String,
    @Json(name = "session_id") val sessionId: String,
    val status: String,
    @Json(name = "verification_url") val verificationUrl: String? = null,
    @Json(name = "created_at") val createdAt: String? = null,
    @Json(name = "completed_at") val completedAt: String? = null,
)

@JsonClass(generateAdapter = false)
data class KycDocumentDto(
    val type: String,
    @Json(name = "issuing_country") val issuingCountry: String? = null,
    val status: String,
    @Json(name = "reason_codes") val reasonCodes: List<String>? = null,
)

@JsonClass(generateAdapter = false)
data class KycDeviceVerificationDto(
    val status: String,
    val required: Boolean = false,
    val epoch: Int? = null,
    @Json(name = "verified_at") val verifiedAt: String? = null,
)

@JsonClass(generateAdapter = false)
data class KycStatusDto(
    /**
     * The single status the server wants acted on, which is the *device* check whenever one is
     * outstanding and the account's own standing otherwise.
     */
    val status: String,
    /**
     * The account's identity standing, independent of any device.
     *
     * Older builds only read [status] and so could not tell "you have never verified" apart from
     * "you are verified, this new device just has not proved itself yet" — which is how a verified
     * user ended up being offered a fresh identity check.
     */
    @Json(name = "account_status") val accountStatus: String? = null,
    @Json(name = "device_verification") val deviceVerification: KycDeviceVerificationDto? = null,
    val case: KycCaseDto? = null,
    @Json(name = "provider_session") val providerSession: KycProviderSessionDto? = null,
    val documents: List<KycDocumentDto>? = null,
)

@JsonClass(generateAdapter = false)
data class CreateKycSessionRequest(
    val consent: Boolean,
    @Json(name = "privacy_notice_version") val privacyNoticeVersion: String,
)

@JsonClass(generateAdapter = false)
data class RegisterPushTokenRequest(
    val provider: String,
    val token: String,
)

@JsonClass(generateAdapter = false)
data class PushTokenStatusDto(
    val registered: Boolean? = null,
    val provider: String? = null,
    @Json(name = "updated_at") val updatedAt: String? = null,
)
