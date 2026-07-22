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
)

@JsonClass(generateAdapter = false)
data class ProtocolsDto(
    val messaging: MessagingProtocolDto? = null,
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
)

@JsonClass(generateAdapter = false)
data class UpdateProfileRequest(
    val name: String,
    val tag: String,
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
    @Json(name = "updated_at") val updatedAt: String? = null,
)

@JsonClass(generateAdapter = false)
data class UpdateCommunicationPreferencesRequest(
    val version: Long,
    @Json(name = "phone_discoverable") val phoneDiscoverable: Boolean? = null,
    @Json(name = "direct_message_requests_enabled")
    val directMessageRequestsEnabled: Boolean? = null,
    @Json(name = "incoming_calls_enabled") val incomingCallsEnabled: Boolean? = null,
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
    @Json(name = "occurred_at") val occurredAt: String,
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
)

@JsonClass(generateAdapter = false)
data class BankingOperationListDto(
    val items: List<BankingOperationDto> = emptyList(),
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
data class KycStatusDto(
    val status: String,
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
