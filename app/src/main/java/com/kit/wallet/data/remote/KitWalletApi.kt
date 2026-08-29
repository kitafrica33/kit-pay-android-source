package com.kit.wallet.data.remote

import com.kit.wallet.data.session.SessionFence
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PATCH
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Tag
import okhttp3.RequestBody

interface KitWalletApi {
    @GET("api/kit-wallet/v1/capabilities")
    suspend fun capabilities(): ApiEnvelope<CapabilitiesDto>

    @POST("api/kit-wallet/v1/auth/email/login")
    suspend fun loginWithEmail(@Body request: EmailLoginRequest): ApiEnvelope<AuthResultDto>

    // Registration is phone-only: the email register endpoint is retired server-side and
    // deliberately has no client binding. Email verify/resend and password recovery remain
    // for accounts that already carry an email.
    @POST("api/kit-wallet/v1/auth/email/verify")
    suspend fun verifyEmail(
        @Body request: VerifyIdentityTokenRequest,
    ): ApiEnvelope<VerifyEmailResultDto>

    @POST("api/kit-wallet/v1/auth/email/resend")
    suspend fun resendEmailVerification(
        @Body request: EmailAddressRequest,
    ): ApiEnvelope<MessageResultDto>

    @POST("api/kit-wallet/v1/auth/password/forgot")
    suspend fun forgotPassword(
        @Body request: EmailAddressRequest,
    ): ApiEnvelope<MessageResultDto>

    @POST("api/kit-wallet/v1/auth/password/reset")
    suspend fun resetPassword(
        @Body request: ResetPasswordRequest,
    ): ApiEnvelope<PasswordResetResultDto>

    @POST("api/kit-wallet/v1/auth/otp/request")
    suspend fun requestPhoneOtp(@Body request: PhoneOtpRequest): ApiEnvelope<AuthResultDto>

    @POST("api/kit-wallet/v1/auth/otp/verify")
    suspend fun verifyPhoneOtp(@Body request: PhoneOtpVerifyRequest): ApiEnvelope<AuthResultDto>

    @POST("api/kit-wallet/v1/auth/2fa/verify")
    suspend fun verifyTwoFactor(@Body request: TwoFactorVerifyRequest): ApiEnvelope<AuthResultDto>

    @POST("api/kit-wallet/v1/auth/2fa/totp/enroll")
    suspend fun enrollTotp(): ApiEnvelope<TotpEnrollmentDto>

    @POST("api/kit-wallet/v1/auth/2fa/totp/confirm")
    suspend fun confirmTotp(@Body request: MfaCodeRequest): ApiEnvelope<MfaStatusDto>

    @retrofit2.http.HTTP(method = "DELETE", path = "api/kit-wallet/v1/auth/2fa/totp", hasBody = true)
    suspend fun disableTotp(@Body request: MfaCodeRequest): ApiEnvelope<MfaStatusDto>

    @POST("api/kit-wallet/v1/auth/2fa/recovery-codes")
    suspend fun regenerateRecoveryCodes(
        @Body request: MfaCodeRequest,
    ): ApiEnvelope<RecoveryCodesDto>

    @POST("api/kit-wallet/v1/auth/refresh")
    suspend fun refresh(@Body request: RefreshSessionRequest): ApiEnvelope<AuthResultDto>

    @POST("api/kit-wallet/v1/auth/logout")
    suspend fun logout(@Body request: LogoutRequest = LogoutRequest()): ApiEnvelope<Map<String, Any?>>

    @GET("api/kit-wallet/v1/account/deletion")
    suspend fun accountDeletionPreflight(): ApiEnvelope<AccountDeletionPreflightDto>

    @POST("api/kit-wallet/v1/account/deletion-requests")
    suspend fun requestAccountDeletion(
        @Header("X-Kit-Wallet-Step-Up") stepUpToken: String,
        @Body request: RequestAccountDeletionDto,
    ): ApiEnvelope<AccountDeletionReceiptDto>

    @GET("api/kit-wallet/v1/bootstrap")
    suspend fun bootstrap(): ApiEnvelope<BootstrapDto>

    @GET("api/kit-wallet/v1/profile")
    suspend fun profile(): ApiEnvelope<UserDto>

    @PATCH("api/kit-wallet/v1/profile")
    suspend fun updateProfile(@Body request: UpdateProfileRequest): ApiEnvelope<UserDto>

    @GET("api/kit-wallet/v1/communications/preferences")
    suspend fun communicationPreferences(): ApiEnvelope<CommunicationPreferencesDto>

    @PATCH("api/kit-wallet/v1/communications/preferences")
    suspend fun updateCommunicationPreferences(
        @Body request: UpdateCommunicationPreferencesRequest,
    ): ApiEnvelope<CommunicationPreferencesDto>

    @GET("api/kit-wallet/v1/communications/blocks")
    suspend fun communicationBlocks(
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int = 100,
    ): ApiEnvelope<CommunicationBlockPageDto>

    @PUT("api/kit-wallet/v1/communications/blocks/{userId}")
    suspend fun blockCommunicationUser(
        @Path("userId") userId: String,
    ): ApiEnvelope<CommunicationBlockDto>

    @DELETE("api/kit-wallet/v1/communications/blocks/{userId}")
    suspend fun unblockCommunicationUser(
        @Path("userId") userId: String,
    ): ApiEnvelope<CommunicationBlockDto>

    @POST("api/kit-wallet/v1/communications/reports")
    suspend fun submitAbuseReport(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: CreateAbuseReportRequestDto,
        @Tag expectedOwner: SessionFence,
    ): ApiEnvelope<AbuseReportReceiptDto>

    @POST("api/kit-wallet/v1/profile/email")
    suspend fun requestProfileEmail(
        @Body request: EmailAddressRequest,
    ): ApiEnvelope<AuthResultDto>

    @POST("api/kit-wallet/v1/profile/email/verify")
    suspend fun verifyProfileEmail(
        @Body request: EmailAttachmentVerificationRequest,
    ): ApiEnvelope<UserDto>

    /**
     * Server-owned starter milestones. Only called after the capabilities response
     * advertises [KitFeature.STARTER_CHECKLIST] as exactly `true`; fenced because the
     * answer is about one account and must never be sent — or read — across a switch.
     */
    @GET("api/v1/onboarding/starter-checklist")
    suspend fun starterChecklist(
        @Tag expectedOwner: SessionFence,
    ): ApiEnvelope<StarterChecklistDto>

    @GET("api/kit-wallet/v1/wallets")
    suspend fun wallets(): ApiEnvelope<WalletListDto>

    @GET("api/kit-wallet/v1/wallets/{walletId}/transactions")
    suspend fun transactions(
        @Path("walletId") walletId: String,
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int = 50,
    ): ApiEnvelope<TransactionPageDto>

    @GET("api/kit-wallet/v1/devices")
    suspend fun devices(): ApiEnvelope<DeviceListDto>

    @DELETE("api/kit-wallet/v1/devices/{deviceId}")
    suspend fun revokeDevice(@Path("deviceId") deviceId: String): ApiEnvelope<Map<String, Any?>>

    @GET("api/kit-wallet/v1/contacts")
    suspend fun contacts(
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int = 500,
    ): ApiEnvelope<ContactListDto>

    @POST("api/kit-wallet/v1/contacts/sync")
    suspend fun syncContacts(@Body request: ContactSyncRequest): ApiEnvelope<ContactListDto>

    @POST("api/kit-wallet/v1/contacts/sync/sessions")
    suspend fun startContactSync(
        @Body request: BeginContactSyncRequest,
    ): ApiEnvelope<ContactSyncSessionResponseDto>

    @PUT("api/kit-wallet/v1/contacts/sync/sessions/{sessionId}/chunks/{chunkIndex}")
    suspend fun uploadContactSyncChunk(
        @Path("sessionId") sessionId: String,
        @Path("chunkIndex") chunkIndex: Int,
        @Body request: ContactSyncRequest,
    ): ApiEnvelope<ContactSyncChunkResponseDto>

    @POST("api/kit-wallet/v1/contacts/sync/sessions/{sessionId}/finalize")
    suspend fun finalizeContactSync(
        @Path("sessionId") sessionId: String,
    ): ApiEnvelope<ContactSyncSessionResponseDto>

    @GET("api/kit-wallet/v1/providers/catalog")
    suspend fun providerCatalog(
        @Query("service_type") serviceType: String? = null,
        @Query("category") category: String? = null,
    ): ApiEnvelope<ProviderProductListDto>

    @GET("api/kit-wallet/v1/calls")
    suspend fun calls(
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int = 50,
    ): ApiEnvelope<CallPageDto>

    @POST("api/kit-wallet/v1/calls")
    suspend fun startCall(@Body request: StartCallRequest): ApiEnvelope<CallSessionDto>

    @POST("api/kit-wallet/v1/calls/client-attempts/{clientCallId}/cancel")
    suspend fun cancelCallAttempt(
        @Path("clientCallId") clientCallId: String,
    ): ApiEnvelope<CancelCallAttemptDto>

    @GET("api/kit-wallet/v1/calls/{callId}")
    suspend fun call(@Path("callId") callId: String): ApiEnvelope<CallDto>

    @POST("api/kit-wallet/v1/calls/{callId}/invite")
    suspend fun inviteToCall(
        @Path("callId") callId: String,
        @Body request: InviteCallRequest,
    ): ApiEnvelope<CallDto>

    @POST("api/kit-wallet/v1/calls/{callId}/accept")
    suspend fun acceptCall(@Path("callId") callId: String): ApiEnvelope<CallSessionDto>

    @POST("api/kit-wallet/v1/calls/{callId}/decline")
    suspend fun declineCall(@Path("callId") callId: String): ApiEnvelope<CallDto>

    @POST("api/kit-wallet/v1/calls/{callId}/end")
    suspend fun endCall(
        @Path("callId") callId: String,
        @Body request: EndCallRequest,
    ): ApiEnvelope<CallDto>

    @POST("api/kit-wallet/v1/calls/{callId}/token")
    suspend fun callToken(@Path("callId") callId: String): ApiEnvelope<RtcCredentialsDto>

    @POST("api/kit-wallet/v1/auth/step-up/challenges")
    suspend fun createStepUpChallenge(
        @Body request: RequestBody,
    ): ApiEnvelope<StepUpChallengeDto>

    @POST("api/kit-wallet/v1/auth/step-up/challenges/{challengeId}/verify")
    suspend fun verifyStepUpChallenge(
        @Path("challengeId") challengeId: String,
        @Body request: VerifyStepUpChallengeRequest,
    ): ApiEnvelope<StepUpVerificationDto>

    @POST("api/kit-wallet/v1/auth/step-up/challenges/{challengeId}/verify")
    suspend fun verifyBiometricStepUpChallenge(
        @Path("challengeId") challengeId: String,
        @Body request: VerifyBiometricStepUpRequest,
    ): ApiEnvelope<StepUpVerificationDto>

    @GET("api/kit-wallet/v1/auth/session-assurance")
    suspend fun sessionAssurance(): ApiEnvelope<SessionAssuranceResultDto>

    @POST("api/kit-wallet/v1/auth/session-unlock/pin")
    suspend fun unlockSessionWithPin(
        @Body request: LoginUnlockPinRequest,
    ): ApiEnvelope<SessionAssuranceResultDto>

    @POST("api/kit-wallet/v1/auth/session-unlock/biometric/challenge")
    suspend fun createLoginBiometricChallenge(): ApiEnvelope<LoginBiometricChallengeDto>

    @POST("api/kit-wallet/v1/auth/session-unlock/biometric/assert")
    suspend fun assertLoginBiometricChallenge(
        @Body request: LoginBiometricAssertionRequest,
    ): ApiEnvelope<SessionAssuranceResultDto>

    @retrofit2.http.PUT("api/kit-wallet/v1/devices/current/biometric-key")
    suspend fun enrollBiometricKey(
        @Body request: EnrollBiometricKeyRequest,
    ): ApiEnvelope<BiometricKeyStatusDto>

    @DELETE("api/kit-wallet/v1/devices/current/biometric-key")
    suspend fun removeBiometricKey(): ApiEnvelope<BiometricKeyStatusDto>

    @POST("api/kit-wallet/v1/wallets/{walletId}/transfers")
    suspend fun transfer(
        @Path("walletId") walletId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Header("X-Kit-Wallet-Step-Up") stepUpToken: String,
        @Body request: WalletTransferRequest,
    ): ApiEnvelope<TransactionDto>

    @GET("api/kit-wallet/v1/transfer-claims")
    suspend fun transferClaims(
        @Query("status") status: String? = null,
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int = 50,
    ): ApiEnvelope<TransferClaimPageDto>

    @GET("api/kit-wallet/v1/transfer-claims/{claimId}")
    suspend fun transferClaim(
        @Path("claimId") claimId: String,
    ): ApiEnvelope<TransferClaimDto>

    @POST("api/kit-wallet/v1/transfer-claims/{claimId}/accept")
    suspend fun acceptTransferClaim(
        @Path("claimId") claimId: String,
    ): ApiEnvelope<TransferClaimDto>

    @POST("api/kit-wallet/v1/transfer-claims/{claimId}/reject")
    suspend fun rejectTransferClaim(
        @Path("claimId") claimId: String,
        @Body request: TransferClaimResolutionRequest,
    ): ApiEnvelope<TransferClaimDto>

    @POST("api/kit-wallet/v1/transfer-claims/{claimId}/reverse")
    suspend fun reverseTransferClaim(
        @Path("claimId") claimId: String,
        @Header("X-Kit-Wallet-Step-Up") stepUpToken: String,
        @Body request: TransferClaimResolutionRequest,
    ): ApiEnvelope<TransferClaimDto>

    /**
     * Sends into a conversation. The step-up proof covers the whole intent — recipients and their
     * amounts included — so an approval cannot be replayed against a different split.
     */
    @POST("api/kit-wallet/v1/conversations/{conversationId}/group-payments")
    suspend fun createGroupPayment(
        @Path("conversationId") conversationId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Header("X-Kit-Wallet-Step-Up") stepUpToken: String,
        @Body request: CreateGroupPaymentRequest,
    ): ApiEnvelope<GroupPaymentDto>

    @GET("api/kit-wallet/v1/group-payments/{groupPaymentId}")
    suspend fun groupPayment(
        @Path("groupPaymentId") groupPaymentId: String,
    ): ApiEnvelope<GroupPaymentDto>

    /**
     * Takes your own share. No step-up: this releases money that is already held for you. There is
     * deliberately no accept-by-claim-id here — money sent into a group is answered in the group.
     */
    @POST("api/kit-wallet/v1/group-payments/{groupPaymentId}/accept")
    suspend fun acceptGroupPaymentShare(
        @Path("groupPaymentId") groupPaymentId: String,
    ): ApiEnvelope<GroupPaymentDto>

    @POST("api/kit-wallet/v1/group-payments/{groupPaymentId}/reject")
    suspend fun rejectGroupPaymentShare(
        @Path("groupPaymentId") groupPaymentId: String,
        @Body request: GroupPaymentResolutionRequest,
    ): ApiEnvelope<GroupPaymentDto>

    /** The sender pulls back every share nobody has taken yet. Accepted shares are untouched. */
    @POST("api/kit-wallet/v1/group-payments/{groupPaymentId}/reverse-unclaimed")
    suspend fun reverseUnclaimedGroupPayment(
        @Path("groupPaymentId") groupPaymentId: String,
        @Header("X-Kit-Wallet-Step-Up") stepUpToken: String,
        @Body request: GroupPaymentResolutionRequest,
    ): ApiEnvelope<GroupPaymentDto>

    @GET("api/kit-wallet/v1/conversations/{conversationId}/group-payment-requests")
    suspend fun groupPaymentRequests(
        @Path("conversationId") conversationId: String,
        @Query("status") status: String? = null,
    ): ApiEnvelope<GroupPaymentRequestListDto>

    @POST("api/kit-wallet/v1/conversations/{conversationId}/group-payment-requests")
    suspend fun createGroupPaymentRequest(
        @Path("conversationId") conversationId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: CreateCollaborativeGroupPaymentRequest,
    ): ApiEnvelope<GroupPaymentRequestDto>

    @GET("api/kit-wallet/v1/group-payment-requests/{requestId}")
    suspend fun groupPaymentRequest(
        @Path("requestId") requestId: String,
    ): ApiEnvelope<GroupPaymentRequestDto>

    @GET("api/kit-wallet/v1/group-payment-requests/{requestId}/contributions")
    suspend fun groupPaymentRequestContributions(
        @Path("requestId") requestId: String,
        @Query("before") before: String? = null,
        @Query("limit") limit: Int = 50,
    ): ApiEnvelope<GroupPaymentRequestContributionPageDto>

    /** Exact lookup hydrates contribution events older than the newest embedded 50 rows. */
    @GET("api/kit-wallet/v1/group-payment-requests/{requestId}/contributions/{contributionId}")
    suspend fun groupPaymentRequestContribution(
        @Path("requestId") requestId: String,
        @Path("contributionId") contributionId: String,
    ): ApiEnvelope<GroupPaymentRequestContributionDto>

    @POST("api/kit-wallet/v1/group-payment-requests/{requestId}/contributions")
    suspend fun contributeToGroupPaymentRequest(
        @Path("requestId") requestId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Header("X-Kit-Wallet-Step-Up") stepUpToken: String,
        @Body request: ContributeGroupPaymentRequest,
    ): ApiEnvelope<GroupPaymentRequestContributionResultDto>

    @POST("api/kit-wallet/v1/group-payment-requests/{requestId}/cancel")
    suspend fun cancelGroupPaymentRequest(
        @Path("requestId") requestId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
    ): ApiEnvelope<GroupPaymentRequestDto>

    @POST("api/kit-wallet/v1/payments/requests")
    suspend fun createPaymentRequest(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: CreatePaymentRequestDto,
        @Tag expectedOwner: SessionFence? = null,
    ): ApiEnvelope<PaymentRequestDto>

    @GET("api/kit-wallet/v1/payments/requests")
    suspend fun paymentRequests(): ApiEnvelope<PaymentRequestListDto>

    @POST("api/kit-wallet/v1/payments/requests/{requestId}/pay")
    suspend fun payPaymentRequest(
        @Path("requestId") requestId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Header("X-Kit-Wallet-Step-Up") stepUpToken: String,
        @Body request: PayPaymentRequestDto,
    ): ApiEnvelope<PaymentRequestDto>

    @GET("api/kit-wallet/v1/payments/scheduled")
    suspend fun scheduledPayments(
        @Query("conversation_id") conversationId: String? = null,
        @Query("status") status: String? = null,
        @Query("before") before: String? = null,
        @Query("limit") limit: Int = 50,
    ): ApiEnvelope<ScheduledPaymentPageDto>

    @GET("api/kit-wallet/v1/payments/scheduled/{scheduledPaymentId}")
    suspend fun scheduledPayment(
        @Path("scheduledPaymentId") scheduledPaymentId: String,
    ): ApiEnvelope<ScheduledPaymentDto>

    @POST("api/kit-wallet/v1/payments/scheduled")
    suspend fun createScheduledPayment(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Header("X-Kit-Wallet-Step-Up") stepUpToken: String,
        @Body request: CreateScheduledPaymentRequest,
    ): ApiEnvelope<ScheduledPaymentDto>

    @POST("api/kit-wallet/v1/payments/scheduled/{scheduledPaymentId}/cancel")
    suspend fun cancelScheduledPayment(
        @Path("scheduledPaymentId") scheduledPaymentId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
    ): ApiEnvelope<ScheduledPaymentDto>

    @POST("api/kit-wallet/v1/conversations/{conversationId}/scheduled-group-payments/preview")
    suspend fun previewScheduledGroupPayment(
        @Path("conversationId") conversationId: String,
        @Body request: PreviewScheduledGroupPaymentRequest,
    ): ApiEnvelope<ScheduledGroupPaymentPlanDto>

    @POST("api/kit-wallet/v1/conversations/{conversationId}/scheduled-group-payments")
    suspend fun createScheduledGroupPayment(
        @Path("conversationId") conversationId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Header("X-Kit-Wallet-Step-Up") stepUpToken: String,
        @Body request: CreateScheduledGroupPaymentRequest,
    ): ApiEnvelope<ScheduledGroupPaymentDto>

    @GET("api/kit-wallet/v1/conversations/{conversationId}/scheduled-group-payments")
    suspend fun scheduledGroupPayments(
        @Path("conversationId") conversationId: String,
        @Query("status") status: String,
        @Query("before") before: String? = null,
        @Query("limit") limit: Int = 50,
    ): ApiEnvelope<ScheduledGroupPaymentPageDto>

    @GET("api/kit-wallet/v1/scheduled-group-payments/{scheduledGroupPaymentId}")
    suspend fun scheduledGroupPayment(
        @Path("scheduledGroupPaymentId") scheduledGroupPaymentId: String,
    ): ApiEnvelope<ScheduledGroupPaymentDto>

    @POST("api/kit-wallet/v1/scheduled-group-payments/{scheduledGroupPaymentId}/cancel")
    suspend fun cancelScheduledGroupPayment(
        @Path("scheduledGroupPaymentId") scheduledGroupPaymentId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
    ): ApiEnvelope<ScheduledGroupPaymentDto>

    @POST("api/kit-wallet/v1/payments/requests/{requestId}/cancel")
    suspend fun cancelPaymentRequest(
        @Path("requestId") requestId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
    ): ApiEnvelope<PaymentRequestDto>

    @GET("api/kit-wallet/v1/search")
    suspend fun search(
        @Query("q") query: String,
        @Query("types[]") types: List<String>,
        @Query("limit") limit: Int,
    ): ApiEnvelope<SearchResultsDto>

    @POST("api/kit-wallet/v1/providers/products/{productId}/quotes")
    suspend fun createProviderQuote(
        @Path("productId") productId: String,
        @Body request: CreateProviderQuoteRequest,
    ): ApiEnvelope<ProviderQuoteDto>

    @POST("api/kit-wallet/v1/providers/bill-payments")
    suspend fun createBillPayment(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Header("X-Kit-Wallet-Step-Up") stepUpToken: String,
        @Body request: CreateProviderOperationRequest,
    ): ApiEnvelope<ProviderOperationDto>

    @POST("api/kit-wallet/v1/providers/airtime-purchases")
    suspend fun createAirtimePurchase(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Header("X-Kit-Wallet-Step-Up") stepUpToken: String,
        @Body request: CreateProviderOperationRequest,
    ): ApiEnvelope<ProviderOperationDto>

    @GET("api/kit-wallet/v1/providers/operations/{operationId}")
    suspend fun providerOperation(
        @Path("operationId") operationId: String,
    ): ApiEnvelope<ProviderOperationDto>

    @PUT("api/kit-wallet/v1/auth/payment-pin")
    suspend fun setPaymentPin(
        @Body request: SetPaymentPinRequest,
    ): ApiEnvelope<PaymentPinStatusDto>

    @POST("api/kit-wallet/v1/media/upload-intents")
    suspend fun createMediaUploadIntent(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: CreateMediaUploadIntentRequest,
    ): ApiEnvelope<MediaUploadIntentDto>

    @GET("api/kit-wallet/v1/media/{assetId}")
    suspend fun mediaAsset(@Path("assetId") assetId: String): ApiEnvelope<MediaAssetDto>

    @POST("api/kit-wallet/v1/media/{assetId}/finalize")
    suspend fun finalizeMediaAsset(@Path("assetId") assetId: String): ApiEnvelope<MediaAssetDto>

    @POST("api/kit-wallet/v1/profile/avatar")
    suspend fun attachProfileAvatar(
        @Body request: AttachProfileAvatarRequest,
    ): ApiEnvelope<UserDto>

    @GET("api/kit-wallet/v1/banking/beneficiaries")
    suspend fun bankBeneficiaries(): ApiEnvelope<BankBeneficiaryListDto>

    @GET("api/kit-wallet/v1/banking/operations")
    suspend fun bankingOperations(): ApiEnvelope<BankingOperationListDto>

    @GET("api/kit-wallet/v1/banking/funding-accounts")
    suspend fun bankFundingAccounts(): ApiEnvelope<BankFundingAccountListDto>

    @GET("api/kit-wallet/v1/banking/deposit-requests")
    suspend fun bankDepositRequests(): ApiEnvelope<BankDepositRequestListDto>

    @GET("api/kit-wallet/v1/banking/deposit-requests/{depositId}")
    suspend fun bankDepositRequest(
        @Path("depositId") depositId: String,
    ): ApiEnvelope<BankDepositRequestDto>

    @POST("api/kit-wallet/v1/banking/deposit-requests")
    suspend fun createBankDepositRequest(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: CreateBankDepositRequest,
    ): ApiEnvelope<BankDepositRequestDto>

    @POST("api/kit-wallet/v1/banking/deposit-requests/{depositId}/proof")
    suspend fun attachBankDepositProof(
        @Path("depositId") depositId: String,
        @Body request: AttachBankDepositProofRequest,
    ): ApiEnvelope<BankDepositRequestDto>

    @GET("api/kit-wallet/v1/banking/banks")
    suspend fun banks(): ApiEnvelope<BankListDto>

    @POST("api/kit-wallet/v1/banking/account-verifications")
    suspend fun createBankVerification(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: CreateBankVerificationRequest,
    ): ApiEnvelope<BankVerificationDto>

    @GET("api/kit-wallet/v1/banking/account-verifications/{verificationId}")
    suspend fun bankVerification(
        @Path("verificationId") verificationId: String,
    ): ApiEnvelope<BankVerificationDto>

    @POST("api/kit-wallet/v1/banking/beneficiaries")
    suspend fun createBankBeneficiary(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: CreateBankBeneficiaryRequest,
    ): ApiEnvelope<BankBeneficiaryDto>

    @POST("api/kit-wallet/v1/banking/deposits")
    suspend fun createBankDeposit(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Header("X-Kit-Wallet-Step-Up") stepUpToken: String,
        @Body request: CreateBankingOperationRequest,
    ): ApiEnvelope<BankingOperationDto>

    @POST("api/kit-wallet/v1/banking/withdrawals")
    suspend fun createBankWithdrawal(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Header("X-Kit-Wallet-Step-Up") stepUpToken: String,
        @Body request: CreateBankingOperationRequest,
    ): ApiEnvelope<BankingOperationDto>

    @POST("api/kit-wallet/v1/banking/withdrawal-quotes")
    suspend fun createBankWithdrawalQuote(
        @Body request: CreateBankingOutboundQuoteRequest,
    ): ApiEnvelope<BankingOutboundQuoteDto>

    @POST("api/kit-wallet/v1/banking/transfer-quotes")
    suspend fun createBankTransferQuote(
        @Body request: CreateBankingOutboundQuoteRequest,
    ): ApiEnvelope<BankingOutboundQuoteDto>

    @POST("api/kit-wallet/v1/banking/withdrawals")
    suspend fun createQuotedBankWithdrawal(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Header("X-Kit-Wallet-Step-Up") stepUpToken: String,
        @Body request: CreateQuotedBankingOperationRequest,
    ): ApiEnvelope<BankingOperationDto>

    @POST("api/kit-wallet/v1/banking/transfers")
    suspend fun createQuotedBankTransfer(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Header("X-Kit-Wallet-Step-Up") stepUpToken: String,
        @Body request: CreateQuotedBankingOperationRequest,
    ): ApiEnvelope<BankingOperationDto>

    @POST("api/kit-wallet/v1/banking/transfers")
    suspend fun createBankTransfer(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Header("X-Kit-Wallet-Step-Up") stepUpToken: String,
        @Body request: CreateBankingOperationRequest,
    ): ApiEnvelope<BankingOperationDto>

    @GET("api/kit-wallet/v1/mobile-money/networks")
    suspend fun mobileMoneyNetworks(): ApiEnvelope<MobileMoneyNetworkListDto>

    @POST("api/kit-wallet/v1/mobile-money/account-verifications")
    suspend fun createMobileMoneyVerification(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: CreateMobileMoneyVerificationRequest,
    ): ApiEnvelope<MobileMoneyVerificationDto>

    @GET("api/kit-wallet/v1/mobile-money/account-verifications/{verificationId}")
    suspend fun mobileMoneyVerification(
        @Path("verificationId") verificationId: String,
    ): ApiEnvelope<MobileMoneyVerificationDto>

    @GET("api/kit-wallet/v1/mobile-money/accounts")
    suspend fun mobileMoneyAccounts(): ApiEnvelope<MobileMoneyAccountListDto>

    @POST("api/kit-wallet/v1/mobile-money/accounts")
    suspend fun createMobileMoneyAccount(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: CreateMobileMoneyAccountRequest,
    ): ApiEnvelope<MobileMoneyAccountDto>

    @GET("api/kit-wallet/v1/mobile-money/operations")
    suspend fun mobileMoneyOperations(): ApiEnvelope<MobileMoneyOperationListDto>

    @GET("api/kit-wallet/v1/mobile-money/operations/{operationId}")
    suspend fun mobileMoneyOperation(
        @Path("operationId") operationId: String,
    ): ApiEnvelope<MobileMoneyOperationDto>

    @POST("api/kit-wallet/v1/mobile-money/collections")
    suspend fun createMobileMoneyCollection(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Header("X-Kit-Wallet-Step-Up") stepUpToken: String,
        @Body request: CreateMobileMoneyOperationRequest,
    ): ApiEnvelope<MobileMoneyOperationDto>

    @POST("api/kit-wallet/v1/mobile-money/collection-quotes")
    suspend fun createMobileMoneyCollectionQuote(
        @Body request: CreateMobileMoneyQuoteRequest,
    ): ApiEnvelope<MobileMoneyQuoteDto>

    @POST("api/kit-wallet/v1/mobile-money/payout-quotes")
    suspend fun createMobileMoneyPayoutQuote(
        @Body request: CreateMobileMoneyQuoteRequest,
    ): ApiEnvelope<MobileMoneyQuoteDto>

    @POST("api/kit-wallet/v1/mobile-money/collections")
    suspend fun createQuotedMobileMoneyCollection(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Header("X-Kit-Wallet-Step-Up") stepUpToken: String,
        @Body request: CreateQuotedMobileMoneyOperationRequest,
    ): ApiEnvelope<MobileMoneyOperationDto>

    @POST("api/kit-wallet/v1/mobile-money/payouts")
    suspend fun createQuotedMobileMoneyPayout(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Header("X-Kit-Wallet-Step-Up") stepUpToken: String,
        @Body request: CreateQuotedMobileMoneyOperationRequest,
    ): ApiEnvelope<MobileMoneyOperationDto>

    @POST("api/kit-wallet/v1/mobile-money/payouts")
    suspend fun createMobileMoneyPayout(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Header("X-Kit-Wallet-Step-Up") stepUpToken: String,
        @Body request: CreateMobileMoneyOperationRequest,
    ): ApiEnvelope<MobileMoneyOperationDto>

    @GET("api/kit-wallet/v1/kyc/")
    suspend fun kycStatus(): ApiEnvelope<KycStatusDto>

    @POST("api/kit-wallet/v1/kyc/sessions")
    suspend fun createKycSession(
        @Body request: CreateKycSessionRequest,
    ): ApiEnvelope<KycStatusDto>

    @retrofit2.http.PUT("api/kit-wallet/v1/devices/current/push-token")
    suspend fun registerPushToken(
        @Body request: RegisterPushTokenRequest,
    ): ApiEnvelope<PushTokenStatusDto>

    @DELETE("api/kit-wallet/v1/devices/current/push-token")
    suspend fun unregisterPushToken(): ApiEnvelope<PushTokenStatusDto>

    // --- In-app support (docs/support-client.md). Every call is fenced to the session that
    // prepared it: ticket content is account-private and must never ride a successor session.

    @GET("api/kit-wallet/v1/support/categories")
    suspend fun supportCategories(
        @Tag expectedOwner: SessionFence,
    ): ApiEnvelope<SupportCategoryListDto>

    @GET("api/kit-wallet/v1/support/tickets")
    suspend fun supportTickets(
        @Query("status") status: String?,
        @Query("limit") limit: Int,
        /** Opaque `meta.next_cursor` from a prior page, verbatim, or null for the first page. */
        @Query("cursor") cursor: String?,
        @Tag expectedOwner: SessionFence,
    ): ApiEnvelope<SupportTicketListDto>

    @POST("api/kit-wallet/v1/support/tickets")
    suspend fun openSupportTicket(
        @Body request: OpenSupportTicketRequest,
        @Tag expectedOwner: SessionFence,
    ): ApiEnvelope<SupportTicketDto>

    @GET("api/kit-wallet/v1/support/tickets/{ticketId}")
    suspend fun supportTicket(
        @Path("ticketId") ticketId: String,
        @Query("limit") limit: Int,
        @Tag expectedOwner: SessionFence,
    ): ApiEnvelope<SupportTicketDetailDto>

    @POST("api/kit-wallet/v1/support/tickets/{ticketId}/close")
    suspend fun closeSupportTicket(
        @Path("ticketId") ticketId: String,
        @Tag expectedOwner: SessionFence,
    ): ApiEnvelope<SupportTicketDto>

    @POST("api/kit-wallet/v1/support/tickets/{ticketId}/escalate")
    suspend fun escalateSupportTicket(
        @Path("ticketId") ticketId: String,
        @Tag expectedOwner: SessionFence,
    ): ApiEnvelope<SupportTicketDto>

    @GET("api/kit-wallet/v1/support/tickets/{ticketId}/messages")
    suspend fun supportTicketMessages(
        @Path("ticketId") ticketId: String,
        @Query("after_position") afterPosition: Long,
        @Query("limit") limit: Int,
        @Tag expectedOwner: SessionFence,
    ): ApiEnvelope<SupportMessagePageDto>

    @POST("api/kit-wallet/v1/support/tickets/{ticketId}/messages")
    suspend fun sendSupportMessage(
        @Path("ticketId") ticketId: String,
        @Body request: SendSupportMessageRequest,
        @Tag expectedOwner: SessionFence,
    ): ApiEnvelope<SupportMessageDto>

    @POST("api/kit-wallet/v1/support/tickets/{ticketId}/payments")
    suspend fun createSupportPayment(
        @Path("ticketId") ticketId: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Header("X-Kit-Wallet-Step-Up") stepUpToken: String,
        @Body request: CreateSupportPaymentRequest,
        @Tag expectedOwner: SessionFence,
    ): ApiEnvelope<SupportPaymentDto>

    /**
     * Ticket-scoped agent photo. Bytes only, `Cache-Control: private, no-store`: the caller
     * must keep the decoded image in memory and never persist it. 404 covers every
     * unavailable state alike, so it is handled as "no photo", never as an error.
     */
    @GET("api/kit-wallet/v1/support/tickets/{ticketId}/agent-avatar")
    suspend fun supportAgentAvatar(
        @Path("ticketId") ticketId: String,
        @Tag expectedOwner: SessionFence,
    ): retrofit2.Response<okhttp3.ResponseBody>

    // --- Referrals (dark unless `features.referrals` is exactly true).

    @GET("api/kit-wallet/v1/referrals")
    suspend fun referralOverview(
        @Tag expectedOwner: SessionFence,
    ): ApiEnvelope<ReferralOverviewDto>

    @POST("api/kit-wallet/v1/referrals/code")
    suspend fun ensureReferralCode(
        @Tag expectedOwner: SessionFence,
    ): ApiEnvelope<ReferralCodeResultDto>
}
