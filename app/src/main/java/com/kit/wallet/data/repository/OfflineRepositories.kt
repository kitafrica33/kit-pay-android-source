package com.kit.wallet.data.repository

import com.kit.wallet.data.local.AUTHENTICATED_CACHE_OWNER_KEY
import com.kit.wallet.data.local.ProfileDao
import com.kit.wallet.data.local.ProfileEntity
import com.kit.wallet.data.local.WalletCache
import com.kit.wallet.data.local.WalletDao
import com.kit.wallet.data.local.WalletTransactionDao
import com.kit.wallet.data.auth.normalizeProfileName
import com.kit.wallet.data.auth.normalizeProfileTag
import com.kit.wallet.data.mapper.DecimalMoney
import com.kit.wallet.data.mapper.toEntity
import com.kit.wallet.data.mapper.toUiModel
import com.kit.wallet.data.remote.ApiCallExecutor
import com.kit.wallet.data.remote.ApiEnvelope
import com.kit.wallet.data.remote.BootstrapDto
import com.kit.wallet.data.remote.CreatePaymentRequestDto
import com.kit.wallet.data.remote.CreateProviderOperationRequest
import com.kit.wallet.data.remote.CreateProviderQuoteRequest
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.remote.KitWalletApiException
import com.kit.wallet.data.remote.claimableTransfersAvailable
import com.kit.wallet.data.remote.ProfileAvatarUploader
import com.kit.wallet.data.remote.EmailAddressRequest
import com.kit.wallet.data.remote.EmailAttachmentVerificationRequest
import com.kit.wallet.data.remote.TransferClaimDto
import com.kit.wallet.data.remote.TransferClaimResolutionRequest
import com.kit.wallet.data.remote.UpdateProfileRequest
import com.kit.wallet.data.session.SessionStore
import com.kit.wallet.data.session.ProfileSetupState
import com.kit.wallet.data.session.SessionFence
import com.kit.wallet.data.session.SessionInvalidatedException
import com.kit.wallet.data.session.SessionTokens
import com.kit.wallet.di.ApplicationScope
import com.kit.wallet.ui.model.Beneficiary
import com.kit.wallet.ui.model.BillProvider
import com.kit.wallet.ui.model.Contact
import com.kit.wallet.ui.model.Transaction
import com.kit.wallet.ui.model.TransferClaim
import com.kit.wallet.ui.model.TxStatus
import com.kit.wallet.ui.model.TxType
import com.kit.wallet.ui.model.UserProfile
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi

@Singleton
class OfflineUserRepository @Inject constructor(
    private val profileDao: ProfileDao,
    private val cache: WalletCache,
    private val sessions: SessionStore,
    private val api: KitWalletApi,
    private val apiCalls: ApiCallExecutor,
    private val clock: Clock,
    @ApplicationScope scope: CoroutineScope,
    private val avatarUploader: ProfileAvatarUploader? = null,
    private val profilePhotos: ProfilePhotoDirectory? = null,
) : UserRepository {
    override val profile: StateFlow<UserProfile> = sessions.session.flatMapLatest { session ->
        if (session == null) {
            flowOf(EMPTY_PROFILE)
        } else {
            profileDao.observeForOwner(
                session.cacheScopeId,
                AUTHENTICATED_CACHE_OWNER_KEY,
            ).map { cached ->
                if (cached == null) {
                    EMPTY_PROFILE.copy(
                        profileSetupRequired = session.profileSetupState.requiresSetup,
                    )
                } else {
                    cached.toUiModel().let { profile ->
                        profile.copy(
                            profileSetupRequired = profile.profileSetupRequired ||
                                session.profileSetupState.requiresSetup,
                        )
                    }
                }
            }
        }
    }.stateIn(scope, SharingStarted.Eagerly, EMPTY_PROFILE)

    init {
        // The signed-in account is a Kit Pay member like any other, and every screen that draws a
        // face by user id — a group's participant list, a search result — looks it up in one shared
        // directory. Their own row was the one nobody wrote, which is why the person the app knows
        // best appeared to themselves as initials.
        //
        // Taken from the cached row rather than from the network response, so it is right on a cold
        // start and offline, and so a profile stored by an earlier build is indexed too. The profile
        // is the whole story about its own photo: removing the photo removes the row, rather than
        // leaving behind a face its owner has taken down.
        //
        // Signing out is not a removal and is deliberately not treated as one: the row goes with
        // the rest of the cache in `RoomWalletCache.clearRows`, so there is nothing to erase here.
        val directory = profilePhotos
        if (directory != null) {
            scope.launch {
                sessions.session
                    .flatMapLatest { session ->
                        if (session == null) {
                            flowOf(null)
                        } else {
                            profileDao.observeForOwner(
                                session.cacheScopeId,
                                AUTHENTICATED_CACHE_OWNER_KEY,
                            ).map { cached -> cached?.let { session.fence() to it } }
                        }
                    }
                    .distinctUntilChanged()
                    .collect { own ->
                        val (fence, cached) = own ?: return@collect
                        directory.learn(
                            fence,
                            mapOf(cached.userId to cached.avatarUrl),
                            complete = true,
                        )
                    }
            }
        }
    }

    override suspend fun refreshProfile() {
        val session = requireActiveSession()
        val user = apiCalls.execute { api.profile() }
        requireSameAccount(session, user.id)
        val updated = user.toEntity(clock.millis())
        persistProfile(session.fence(), updated)
    }

    override suspend fun updateProfile(name: String, tag: String) {
        val session = requireActiveSession()
        val current = profile.value
        val normalizedName = normalizeProfileName(name)
        val normalizedTag = normalizeProfileTag(tag)
        val user = apiCalls.execute {
            api.updateProfile(
                UpdateProfileRequest(
                    // Blank means "leave it alone", not "erase it". The API has no way to clear a
                    // display name, and a verified account already falls back to its legal name.
                    name = normalizedName.takeIf(String::isNotBlank),
                    tag = normalizedTag.takeIf(String::isNotBlank),
                    // Sent only when there is a username to drop and the account is allowed to
                    // drop it, so a required-username account never asks for a rejection.
                    clearUsername = normalizedTag.isBlank() &&
                        current.tag.isNotBlank() &&
                        !current.usernameRequired,
                ),
            )
        }
        requireSameAccount(session, user.id)
        val updated = user.toEntity(clock.millis())
        check(!updated.toUiModel().profileSetupRequired) {
            "Profile setup is still required after saving the profile"
        }
        persistProfile(session.fence(), updated)
        // Do not return to navigation until the observable cache has caught up. Otherwise the
        // restored-session gate can briefly see the previous true value and reopen setup.
        // Compared against what the server actually stored, so a dropped username — which comes
        // back as an empty tag — settles the wait rather than hanging on it.
        profile.first { cached ->
            !cached.profileSetupRequired &&
                cached.name == updated.name &&
                cached.tag == updated.tag
        }
    }

    override suspend fun attachAvatar(jpegBytes: ByteArray) {
        val uploader = avatarUploader ?: error("Profile photos are unavailable")
        val session = requireActiveSession()
        val user = uploader.upload(jpegBytes)
        requireSameAccount(session, user.id)
        check(!user.avatarUrl.isNullOrBlank()) {
            "The profile photo was not attached"
        }
        persistProfile(session.fence(), user.toEntity(clock.millis()))
    }

    override suspend fun requestEmailAttachment(email: String): ProfileEmailChallenge {
        val normalizedEmail = email.trim().lowercase()
        val result = apiCalls.execute {
            api.requestProfileEmail(EmailAddressRequest(normalizedEmail))
        }
        check(result.state == "challenge_required") {
            "Email verification did not return a challenge"
        }
        val challenge = requireNotNull(result.challenge) {
            "Email verification response omitted its challenge"
        }
        check(challenge.type == "email_attachment") {
            "Email verification returned an unsupported challenge"
        }
        return ProfileEmailChallenge(
            id = challenge.id,
            destination = requireNotNull(challenge.destination?.takeIf(String::isNotBlank)) {
                "Email verification response omitted its destination"
            },
            expiresAt = challenge.expiresAt,
            resendAfterSeconds = challenge.resendAfterSeconds
                ?.takeIf(Double::isFinite)
                ?.let { ceil(it).toLong().coerceAtLeast(0L) },
        )
    }

    override suspend fun verifyEmailAttachment(challengeId: String, code: String) {
        val session = requireActiveSession()
        val user = apiCalls.execute {
            api.verifyProfileEmail(
                EmailAttachmentVerificationRequest(
                    challengeId = challengeId,
                    code = code,
                ),
            )
        }
        check(user.emailVerified == true && !user.email.isNullOrBlank()) {
            "Email verification was not completed"
        }
        requireSameAccount(session, user.id)
        persistProfile(session.fence(), user.toEntity(clock.millis()))
    }

    private fun requireActiveSession(): SessionTokens = requireNotNull(sessions.current()) {
        "Sign in again to access this profile"
    }

    private fun requireSameAccount(session: SessionTokens, userId: String) {
        session.accountId?.let { expected ->
            check(expected == userId) { "The profile response belongs to another account" }
        }
    }

    private suspend fun persistProfile(fence: SessionFence, profile: ProfileEntity) {
        val setupState = if (profile.profileSetupRequired) {
            ProfileSetupState.REQUIRED
        } else {
            ProfileSetupState.COMPLETED
        }
        if (!sessions.updateProfileSetupState(fence, setupState)) {
            throw SessionInvalidatedException()
        }
        sessions.withCurrentSession(fence) { active ->
            cache.replaceProfile(active.cacheScopeId, profile)
        }
    }

    private companion object {
        val EMPTY_PROFILE = UserProfile(name = "", phone = "", tag = "", kycLabel = "")
    }
}

class WalletWriteNotAvailableException : IllegalStateException(
    "This Kit Pay operation is not available yet",
)

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class OfflineWalletRepository @Inject constructor(
    private val walletDao: WalletDao,
    transactionDao: WalletTransactionDao,
    private val cache: WalletCache,
    private val sessions: SessionStore,
    private val api: KitWalletApi,
    private val apiCalls: ApiCallExecutor,
    private val paymentAuthorizer: PaymentAuthorizer,
    private val walletSync: WalletSyncRepository,
    private val providerCatalog: ProviderCatalogRepository,
    @ApplicationScope scope: CoroutineScope,
) : WalletRepository {
    override val currentAccountId: String?
        get() = sessions.current()?.accountId

    private val selectedWallet = sessions.session.flatMapLatest { session ->
        if (session == null) {
            flowOf<OwnedSelectedWallet?>(null)
        } else {
            walletDao.observeSelectedForOwner(
                session.cacheScopeId,
                AUTHENTICATED_CACHE_OWNER_KEY,
            ).map { wallet ->
                wallet?.let { OwnedSelectedWallet(session.cacheScopeId, it) }
            }
        }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    override val balanceMinor: StateFlow<Long> = selectedWallet
        .map { it?.wallet?.availableBalanceMinor ?: 0L }
        .stateIn(scope, SharingStarted.Eagerly, 0L)

    override val walletCurrency: StateFlow<WalletCurrency> = selectedWallet
        .map { selected ->
            selected?.wallet?.let { WalletCurrency(it.currencyCode, it.currencyScale) }
                ?: WalletCurrency()
        }
        .stateIn(scope, SharingStarted.Eagerly, WalletCurrency())

    override val transactions: StateFlow<List<Transaction>> = selectedWallet
        .flatMapLatest { selected ->
            if (selected == null) {
                flowOf(emptyList())
            } else {
                transactionDao.observeForOwnerWallet(
                    selected.ownerScopeId,
                    AUTHENTICATED_CACHE_OWNER_KEY,
                    selected.wallet.uuid,
                )
            }
        }
        .map { rows -> rows.map { it.toUiModel() } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    override val beneficiaries: StateFlow<List<Beneficiary>> =
        flowOf(emptyList<Beneficiary>()).stateIn(scope, SharingStarted.Eagerly, emptyList())

    override fun transaction(id: String): Transaction? = transactions.value.find { it.id == id }

    override suspend fun send(
        recipient: Contact,
        amountMinor: Long,
        note: String?,
        paymentPin: String,
    ): Transaction = sendToContact(recipient, amountMinor, note, paymentPin).transaction

    override suspend fun sendToContact(
        recipient: Contact,
        amountMinor: Long,
        note: String?,
        paymentPin: String,
    ): SentTransfer {
        require(amountMinor > 0) { "Amount must be positive" }
        // An empty PIN defers to PaymentAuthorizer, which uses biometric approval when the server
        // advertises it and otherwise requires the four-digit wallet PIN itself.
        require(paymentPin.isEmpty() || paymentPin.matches(Regex("^[0-9]{4}$"))) {
            "Enter the four-digit wallet PIN"
        }
        val source = requireSelectedWallet()
        val destinationWalletId = requireNotNull(recipient.receivingWalletId) {
            "This contact cannot receive Kit Pay transfers yet"
        }
        val amount = DecimalMoney.fromMinor(amountMinor, source.currencyScale)
        val intent = linkedMapOf<String, Any?>(
            "source_wallet_id" to source.uuid,
            "destination_wallet_id" to destinationWalletId,
            "amount" to amount,
            "note" to note,
        )
        val stepUpToken = paymentAuthorizer.authorize("wallet_transfer", intent, paymentPin)
        val transaction = apiCalls.execute {
            api.transfer(
                walletId = source.uuid,
                idempotencyKey = "android-transfer-${java.util.UUID.randomUUID()}",
                stepUpToken = stepUpToken,
                request = com.kit.wallet.data.remote.WalletTransferRequest(
                    destinationWalletId = destinationWalletId,
                    amount = amount,
                    note = note,
                ),
            )
        }
        walletSync.refresh()
        return SentTransfer(
            transaction = transaction.toEntity(source.uuid).toUiModel(),
            claim = transaction.claim?.toUiModel(),
        )
    }

    override suspend fun request(from: Contact, amountMinor: Long, note: String?) {
        require(amountMinor > 0) { "Amount must be positive" }
        require(from.isKitUser) { "Payment requests can only be sent to Kit Pay users" }
        val destination = requireSelectedWallet()
        val amount = DecimalMoney.fromMinor(amountMinor, destination.currencyScale)
        val created = apiCalls.execute {
            api.createPaymentRequest(
                idempotencyKey = "android-request-${java.util.UUID.randomUUID()}",
                request = CreatePaymentRequestDto(
                    destinationWalletId = destination.uuid,
                    requestedFromUserId = from.id,
                    amount = amount,
                    note = note,
                ),
            )
        }
        validateCreatedPaymentRequest(
            created = created,
            destinationWalletId = destination.uuid,
            requestedFromUserId = from.id,
            amount = amount,
            currencyCode = destination.currencyCode,
            currencyScale = destination.currencyScale,
        )
    }

    override suspend fun createChatPaymentRequest(
        peerUserId: String,
        amountMinor: Long,
        note: String?,
        idempotencyKey: String?,
    ): ChatPaymentRequest = createChatPaymentRequestForOwnerOrCurrent(
        owner = null,
        peerUserId = peerUserId,
        amountMinor = amountMinor,
        note = note,
        idempotencyKey = idempotencyKey
            ?: "android-chat-request-${java.util.UUID.randomUUID()}",
    )

    override suspend fun createChatPaymentRequestForOwner(
        owner: SessionFence,
        peerUserId: String,
        amountMinor: Long,
        note: String?,
        idempotencyKey: String,
    ): ChatPaymentRequest = createChatPaymentRequestForOwnerOrCurrent(
        owner = owner,
        peerUserId = peerUserId,
        amountMinor = amountMinor,
        note = note,
        idempotencyKey = idempotencyKey,
    )

    private suspend fun createChatPaymentRequestForOwnerOrCurrent(
        owner: SessionFence?,
        peerUserId: String,
        amountMinor: Long,
        note: String?,
        idempotencyKey: String,
    ): ChatPaymentRequest {
        require(amountMinor > 0) { "Amount must be positive" }
        require(peerUserId.isNotBlank()) { "This conversation has no Kit Pay peer" }
        val destination = if (owner == null) {
            requireSelectedWallet()
        } else {
            requireSelectedWallet(owner)
        }
        val amount = DecimalMoney.fromMinor(amountMinor, destination.currencyScale)
        val created = apiCalls.execute {
            api.createPaymentRequest(
                idempotencyKey = idempotencyKey,
                request = CreatePaymentRequestDto(
                    destinationWalletId = destination.uuid,
                    requestedFromUserId = peerUserId,
                    amount = amount,
                    note = note?.trim()?.takeIf(String::isNotBlank),
                ),
                expectedOwner = owner,
            )
        }
        validateCreatedPaymentRequest(
            created = created,
            destinationWalletId = destination.uuid,
            requestedFromUserId = peerUserId,
            amount = amount,
            currencyCode = destination.currencyCode,
            currencyScale = destination.currencyScale,
        )
        owner?.let { expected -> sessions.withCurrentSession(expected) { } }
        return ChatPaymentRequest(
            id = created.id,
            amountMinor = amountMinor,
            currencyCode = destination.currencyCode,
            currencyScale = destination.currencyScale,
            note = created.note,
        )
    }

    override suspend fun payChatPaymentRequest(
        requestId: String,
        amountMinor: Long,
        paymentPin: String,
    ) {
        require(amountMinor > 0) { "The payment request amount is invalid" }
        // An empty PIN defers to PaymentAuthorizer's biometric-or-PIN selection.
        require(paymentPin.isEmpty() || paymentPin.matches(Regex("^[0-9]{4}$"))) {
            "Enter the four-digit wallet PIN"
        }
        val source = requireSelectedWallet()
        // Reconcile the card with the authoritative request list before any step-up, so paid,
        // cancelled, expired, mutated or unknown requests are refused with clear reasons instead
        // of asking for approval first. Older services without the read endpoint skip this check.
        val listed = try {
            apiCalls.execute { api.paymentRequests() }.items
        } catch (error: KitWalletApiException) {
            if (error.statusCode != 404) throw error
            null
        }
        if (listed != null) {
            requirePayablePaymentRequest(
                records = listed,
                requestId = requestId,
                amountMinor = amountMinor,
                currencyCode = source.currencyCode,
                currencyScale = source.currencyScale,
            )
        }
        val amount = DecimalMoney.fromMinor(amountMinor, source.currencyScale)
        // The intent fields and their values must exactly match the backend's pay-time
        // step-up intent hash: action, payment_request_id, source_wallet_id, amount, currency.
        val intent = linkedMapOf<String, Any?>(
            "action" to "pay",
            "payment_request_id" to requestId,
            "source_wallet_id" to source.uuid,
            "amount" to amount,
            "currency" to source.currencyCode,
        )
        val stepUpToken = paymentAuthorizer.authorize("payment_request", intent, paymentPin)
        val paid = apiCalls.execute {
            api.payPaymentRequest(
                requestId = requestId,
                idempotencyKey = "android-chat-pay-${java.util.UUID.randomUUID()}",
                stepUpToken = stepUpToken,
                request = com.kit.wallet.data.remote.PayPaymentRequestDto(
                    sourceWalletId = source.uuid,
                ),
            )
        }
        validatePaidPaymentRequest(paid, requestId)
        walletSync.refresh()
    }

    override suspend fun cancelChatPaymentRequest(requestId: String) {
        require(requestId.isNotBlank()) { "This card has no payment request to cancel" }
        apiCalls.execute {
            api.cancelPaymentRequest(
                requestId = requestId,
                idempotencyKey = "android-chat-cancel-${java.util.UUID.randomUUID()}",
            )
        }
    }

    override suspend fun transferClaims(): List<TransferClaim> {
        val claims = try {
            loadVisibleTransferClaims { status, cursor, limit ->
                apiCalls.execute {
                    api.transferClaims(status = status, cursor = cursor, limit = limit)
                }
            }
        } catch (error: KitWalletApiException) {
            // A service that predates held transfers simply has none to report. A conversation
            // must still open, and its ordinary messages must still render, without them.
            if (error.statusCode == 404) return emptyList()
            throw error
        }
        return claims.mapNotNull { it.toUiModel() }
    }

    override suspend fun refreshClaimableTransfersCapability(): Boolean {
        return apiCalls.execute { api.capabilities() }.claimableTransfersAvailable()
    }

    override suspend fun transferClaim(claimId: String): TransferClaim {
        require(claimId.isNotBlank()) { "This transfer has no claim to verify" }
        val claim = apiCalls.execute { api.transferClaim(claimId) }.toUiModel()
            ?: error("The transfer state could not be verified")
        check(claim.id.equals(claimId, ignoreCase = true)) {
            "The transfer state did not match this payment"
        }
        return claim
    }

    override suspend fun acceptTransferClaim(claimId: String): TransferClaim =
        settleTransferClaim(claimId) { api.acceptTransferClaim(claimId) }

    override suspend fun rejectTransferClaim(claimId: String, reason: String?): TransferClaim =
        settleTransferClaim(claimId) {
            api.rejectTransferClaim(claimId, TransferClaimResolutionRequest(reason.orNullIfBlank()))
        }

    override suspend fun reverseTransferClaim(
        claimId: String,
        reason: String?,
        paymentPin: String,
    ): TransferClaim {
        require(paymentPin.isEmpty() || paymentPin.matches(Regex("^[0-9]{4}$"))) {
            "Enter the four-digit wallet PIN"
        }
        val canonicalReason = canonicalTransferClaimReason(reason)
        val authorizationIntent = transferClaimReverseIntent(claimId, canonicalReason)
        val stepUpToken = paymentAuthorizer.authorize(
            "wallet_transfer_reverse",
            authorizationIntent,
            paymentPin,
            "Approve reversing this payment",
        )
        return settleTransferClaim(claimId) {
            api.reverseTransferClaim(
                claimId,
                stepUpToken,
                TransferClaimResolutionRequest(canonicalReason),
            )
        }
    }

    /**
     * Settling a claim is the moment money either becomes final or goes back, so the cached
     * balance is stale the instant the call returns. Refresh before the caller reads it.
     *
     * Accept and reject require authoritative party checks at the caller and server. Reverse also
     * carries a claim-bound biometric-or-PIN token because it cancels the sender's payment intent.
     */
    private suspend fun settleTransferClaim(
        claimId: String,
        call: suspend () -> ApiEnvelope<TransferClaimDto>,
    ): TransferClaim {
        require(claimId.isNotBlank()) { "This transfer has no claim to settle" }
        val settled = apiCalls.execute(call)
        walletSync.refresh()
        return settled.toUiModel()
            ?: error("The transfer was settled, but its new state could not be read")
    }

    private fun String?.orNullIfBlank(): String? = this?.trim()?.takeIf(String::isNotBlank)

    override suspend fun payBill(
        provider: BillProvider,
        account: String,
        amountMinor: Long,
        paymentPin: String,
    ): Transaction = submitProviderOperation(previewBill(provider, account, amountMinor), paymentPin)

    override suspend fun buyAirtime(
        productId: String,
        phone: String,
        amountMinor: Long,
        paymentPin: String,
    ): Transaction = submitProviderOperation(previewAirtime(productId, phone, amountMinor), paymentPin)

    override suspend fun previewBill(
        provider: BillProvider,
        account: String,
        amountMinor: Long,
    ): FinancialOperationQuote = previewProviderOperation(
        productId = provider.id,
        account = account,
        amountMinor = amountMinor,
        type = "bill_payment",
        serviceType = "bill",
        unavailableMessage = "The selected bill provider is no longer available",
    )

    override suspend fun previewAirtime(
        productId: String,
        phone: String,
        amountMinor: Long,
    ): FinancialOperationQuote = previewProviderOperation(
        productId = productId,
        account = phone,
        amountMinor = amountMinor,
        type = "airtime_purchase",
        serviceType = "airtime",
        unavailableMessage = "Choose an available airtime network",
    )

    private suspend fun previewProviderOperation(
        productId: String,
        account: String,
        amountMinor: Long,
        type: String,
        serviceType: String,
        unavailableMessage: String,
    ): FinancialOperationQuote {
        require(amountMinor > 0) { "Amount must be positive" }
        require(account.isNotBlank()) { "Enter the destination account" }
        val active = requireNotNull(sessions.current()) { "Sign in again to access this wallet" }
        val wallet = requireSelectedWallet()
        if (providerCatalog.product(productId) == null) runCatching { providerCatalog.refresh() }
        val product = requireNotNull(providerCatalog.product(productId)) { unavailableMessage }
        check(product.serviceType == serviceType) { unavailableMessage }
        val scale = product.currency.scale.toInt()
        val amount = DecimalMoney.fromMinor(amountMinor, scale)
        val quote = apiCalls.execute {
            api.createProviderQuote(productId, CreateProviderQuoteRequest(account, amount))
        }
        validateProviderQuote(
            quote = quote,
            productId = product.id,
            serviceType = serviceType,
            amount = amount,
            currencyCode = product.currency.code,
            currencyScale = scale,
        )
        // The client reference is fixed at review time so approval, submission and the backend
        // step-up intent hash all describe one immutable operation.
        val clientReference = "android-provider-${java.util.UUID.randomUUID()}"
        return FinancialOperationQuote(
            quoteId = quote.id,
            operationType = type,
            destinationId = account,
            amountMinor = amountMinor,
            recipientAmountMinor = amountMinor,
            feesMinor = DecimalMoney.toMinor(quote.fee, scale),
            customerDebitMinor = DecimalMoney.toMinor(quote.total, scale),
            currencyCode = product.currency.code.uppercase(),
            currencyScale = scale,
            feeMode = "sender_absorbs",
            expiresAt = quote.expiresAt,
            feesKnown = true,
            authorizationPurpose = type,
            authorizationIntent = linkedMapOf(
                "quote_id" to quote.id,
                "wallet_id" to wallet.uuid,
                "client_reference" to clientReference,
            ),
            sessionFence = active.fence(),
            destinationName = product.name,
            accountDisplay = quote.accountDisplay,
            productId = product.id,
        )
    }

    override suspend fun submitProviderOperation(
        quote: FinancialOperationQuote,
        paymentPin: String,
    ): Transaction {
        require(quote.operationType in setOf("bill_payment", "airtime_purchase")) {
            "The provider quote is invalid"
        }
        require(paymentPin.isEmpty() || paymentPin.matches(Regex("^[0-9]{4}$"))) {
            "Enter the four-digit wallet PIN"
        }
        check(sessions.current()?.fence() == quote.sessionFence) {
            "The signed-in account changed after this quote was created"
        }
        quote.expiresAt?.let { expiresAt ->
            check(
                runCatching {
                    java.time.Instant.parse(expiresAt).isAfter(java.time.Instant.now())
                }.getOrDefault(false),
            ) { "This quote has expired. Review a new quote." }
        }
        val quoteId = quote.quoteId ?: error("The provider quote is invalid")
        val walletId = quote.authorizationIntent["wallet_id"] as? String
            ?: error("The provider quote omitted its wallet")
        val clientReference = quote.authorizationIntent["client_reference"] as? String
            ?: error("The provider quote omitted its reference")
        val stepUpToken = paymentAuthorizer.authorize(
            quote.authorizationPurpose,
            quote.authorizationIntent,
            paymentPin,
        )
        val request = CreateProviderOperationRequest(quoteId, walletId, clientReference)
        val operation = apiCalls.execute {
            if (quote.operationType == "bill_payment") {
                api.createBillPayment(clientReference, stepUpToken, request)
            } else {
                api.createAirtimePurchase(clientReference, stepUpToken, request)
            }
        }
        validateProviderOperationResponse(operation, quote, walletId, clientReference)
        runCatching { walletSync.refresh() }
        return Transaction(
            id = operation.id,
            counterparty = quote.destinationName ?: operation.productName,
            note = providerDestinationPresentation(operation.accountDisplay),
            amountMinor = -quote.amountMinor,
            time = "Just now",
            dateGroup = "Today",
            type = if (quote.operationType == "bill_payment") TxType.BILL else TxType.AIRTIME,
            status = when (operation.status) {
                "succeeded" -> TxStatus.COMPLETED
                "failed" -> TxStatus.FAILED
                else -> TxStatus.PENDING
            },
            reference = operation.providerReference ?: clientReference,
            currencyCode = quote.currencyCode,
            currencyScale = quote.currencyScale,
            feeMinor = quote.feesMinor,
            customerDebitMinor = quote.customerDebitMinor,
        )
    }

    private suspend fun requireSelectedWallet(): com.kit.wallet.data.local.WalletEntity {
        val active = requireNotNull(sessions.current()) { "Sign in again to access this wallet" }
        return requireSelectedWallet(active.fence())
    }

    private suspend fun requireSelectedWallet(
        owner: SessionFence,
    ): com.kit.wallet.data.local.WalletEntity =
        sessions.withCurrentSession(owner) { current ->
            requireNotNull(cache.selectedWallet(current.cacheScopeId)) {
                "No active wallet is selected"
            }
        }

    override suspend fun spendingSource(): WalletSpendingSource {
        val wallet = requireSelectedWallet()
        return WalletSpendingSource(
            walletId = wallet.uuid,
            currencyCode = wallet.currencyCode,
            currencyScale = wallet.currencyScale,
            availableBalanceMinor = wallet.availableBalanceMinor,
        )
    }

    private data class OwnedSelectedWallet(
        val ownerScopeId: String,
        val wallet: com.kit.wallet.data.local.WalletEntity,
    )
}

internal fun providerDestinationPresentation(value: String?): String =
    value?.trim()?.takeIf(String::isNotBlank) ?: "Destination unavailable"

data class WalletSyncResult(
    val walletCount: Int,
    val transactionCount: Int,
    val hasMoreTransactions: Boolean,
    /** Server-backed selected-wallet values, returned directly to avoid Room/StateFlow lag. */
    val selectedAvailableBalanceMinor: Long? = null,
    val selectedCurrencyCode: String? = null,
    val selectedCurrencyScale: Int? = null,
)

interface WalletSyncRepository {
    suspend fun refresh(): WalletSyncResult
    suspend fun clearCachedUserData(ownerScopeId: String? = null)
}

@Singleton
class OfflineWalletSyncRepository @Inject constructor(
    private val api: KitWalletApi,
    private val apiCalls: ApiCallExecutor,
    private val cache: WalletCache,
    private val sessions: SessionStore,
    private val clock: Clock,
) : WalletSyncRepository {
    override suspend fun refresh(): WalletSyncResult {
        val session = sessions.current() ?: return WalletSyncResult(0, 0, false)
        val fence = session.fence()

        val bootstrap = apiCalls.execute { api.bootstrap() }
        requireSameAccount(session, bootstrap.user.id)
        cacheBootstrap(fence, bootstrap)

        // The wallet list is authoritative and may change independently of bootstrap.
        val wallets = apiCalls.execute { api.wallets() }.items
        val now = clock.millis()
        sessions.withCurrentSession(fence) { current ->
            cache.replaceWallets(current.cacheScopeId, wallets.map { it.toEntity(now) })
        }

        val selected = sessions.withCurrentSession(fence) { current ->
            cache.selectedWallet(current.cacheScopeId)
        }
            ?: return WalletSyncResult(wallets.size, 0, false)
        val page = apiCalls.execute { api.transactions(selected.uuid, limit = PAGE_SIZE) }
        val transactions = page.items.map { it.toEntity(selected.uuid) }
        sessions.withCurrentSession(fence) { current ->
            cache.replaceTransactions(
                current.cacheScopeId,
                selected.uuid,
                transactions,
                page.page.nextCursor,
            )
        }
        return WalletSyncResult(
            walletCount = wallets.size,
            transactionCount = transactions.size,
            hasMoreTransactions = page.page.hasMore == true,
            selectedAvailableBalanceMinor = selected.availableBalanceMinor,
            selectedCurrencyCode = selected.currencyCode,
            selectedCurrencyScale = selected.currencyScale,
        )
    }

    override suspend fun clearCachedUserData(ownerScopeId: String?) {
        cache.clearUserData(ownerScopeId)
    }

    private suspend fun cacheBootstrap(fence: SessionFence, bootstrap: BootstrapDto) {
        val now = clock.millis()
        val wallets = bootstrap.wallets.map { wallet ->
            val mapped = wallet.toEntity(now)
            if (bootstrap.selectedWalletId == null) mapped
            else mapped.copy(isPrimary = wallet.id == bootstrap.selectedWalletId)
        }
        val profile = bootstrap.user.toEntity(now)
        val setupState = if (profile.profileSetupRequired) {
            ProfileSetupState.REQUIRED
        } else {
            ProfileSetupState.COMPLETED
        }
        if (!sessions.updateProfileSetupState(fence, setupState)) {
            throw SessionInvalidatedException()
        }
        sessions.withCurrentSession(fence) { current ->
            cache.replaceProfileAndWallets(current.cacheScopeId, profile, wallets)
        }
    }

    private fun requireSameAccount(session: SessionTokens, userId: String) {
        session.accountId?.let { expected ->
            check(expected == userId) { "The wallet response belongs to another account" }
        }
    }

    private companion object {
        const val PAGE_SIZE = 50
    }
}

/**
 * The authoritative create response must describe exactly the request that was submitted: a fresh
 * pending payment_request into this wallet, addressed to this user, for this amount and currency.
 * Anything else is treated as a failed creation instead of being shown as a sendable card.
 */
internal fun validateCreatedPaymentRequest(
    created: com.kit.wallet.data.remote.PaymentRequestDto,
    destinationWalletId: String,
    requestedFromUserId: String,
    amount: String,
    currencyCode: String,
    currencyScale: Int,
) {
    check(
        runCatching { java.util.UUID.fromString(created.id) }.isSuccess &&
            created.type == "payment_request" &&
            created.status.equals("pending", ignoreCase = true) &&
            created.destinationWalletId == destinationWalletId &&
            created.requestedFromUserId.equals(requestedFromUserId, ignoreCase = true) &&
            created.amount == amount &&
            created.currency.code.equals(currencyCode, ignoreCase = true) &&
            created.currency.scale.toIntOrNull() == currencyScale,
    ) { "The server did not confirm this exact payment request" }
}

/**
 * A decoded 2xx alone must not surface a paid card: the server has to confirm this exact request
 * settled as `paid` and reference the wallet transaction that actually moved the money.
 */
internal fun validatePaidPaymentRequest(
    paid: com.kit.wallet.data.remote.PaymentRequestDto,
    requestId: String,
) {
    check(
        paid.id.equals(requestId, ignoreCase = true) &&
            paid.type == "payment_request" &&
            paid.status.equals("paid", ignoreCase = true) &&
            !paid.walletTransactionId.isNullOrBlank(),
    ) { "The server did not confirm this payment request was paid" }
}

/**
 * A payment-request card may only be paid while the authoritative backend record is a pending,
 * unexpired `payment_request` whose amount and currency still match the card. Everything else —
 * paid, cancelled, expired, mutated, or absent from the actor's request list — is refused with a
 * clear customer-facing reason before any PIN or biometric approval is requested.
 */
internal fun requirePayablePaymentRequest(
    records: List<com.kit.wallet.data.remote.PaymentRequestDto>,
    requestId: String,
    amountMinor: Long,
    currencyCode: String,
    currencyScale: Int,
    now: java.time.Instant = java.time.Instant.now(),
) {
    val record = records.firstOrNull {
        it.type == "payment_request" && it.id.equals(requestId, ignoreCase = true)
    }
    checkNotNull(record) { "This payment request is no longer available" }
    when (record.status.lowercase()) {
        "pending" -> Unit
        "paid" -> error("This request was already paid")
        "cancelled" -> error("This request was cancelled by the requester")
        "expired" -> error("This request has expired. Ask for a new one.")
        else -> error("This payment request is no longer available")
    }
    record.expiresAt?.let { expiresAt ->
        check(
            runCatching { java.time.Instant.parse(expiresAt).isAfter(now) }.getOrDefault(true),
        ) { "This request has expired. Ask for a new one." }
    }
    check(
        record.currency.code.equals(currencyCode, ignoreCase = true) &&
            record.currency.scale.toIntOrNull() == currencyScale &&
            runCatching { DecimalMoney.toMinor(record.amount, currencyScale) }
                .getOrNull() == amountMinor,
    ) { "This request no longer matches the shown amount. Ask for a new one." }
}

/**
 * The reviewed provider quote must be exactly what was requested: this product and service, the
 * entered amount, an internally consistent `amount + fee == total`, and an unexpired review window.
 */
internal fun validateProviderQuote(
    quote: com.kit.wallet.data.remote.ProviderQuoteDto,
    productId: String,
    serviceType: String,
    amount: String,
    currencyCode: String,
    currencyScale: Int,
    now: java.time.Instant = java.time.Instant.now(),
) {
    fun decimal(value: String) = runCatching { java.math.BigDecimal(value) }.getOrNull()
    val quotedAmount = decimal(quote.amount)
    val fee = decimal(quote.fee)
    val total = decimal(quote.total)
    check(
        quote.id.isNotBlank() && quote.productId == productId && quote.serviceType == serviceType &&
            quote.currency.code.equals(currencyCode, ignoreCase = true) &&
            quote.currency.scale.toIntOrNull() == currencyScale &&
            quotedAmount != null && fee != null && total != null &&
            quotedAmount.signum() > 0 && fee.signum() >= 0 &&
            decimal(amount)?.compareTo(quotedAmount) == 0 &&
            total.compareTo(quotedAmount + fee) == 0 &&
            runCatching { java.time.Instant.parse(quote.expiresAt).isAfter(now) }
                .getOrDefault(false),
    ) { "The quote does not match this request. Try again." }
}

/**
 * The created operation must be exactly the reviewed and approved quote — same wallet, product,
 * reference, currency and money amounts — before it is presented as a submitted payment.
 */
internal fun validateProviderOperationResponse(
    operation: com.kit.wallet.data.remote.ProviderOperationDto,
    quote: FinancialOperationQuote,
    walletId: String,
    clientReference: String,
) {
    fun minor(value: String) = runCatching {
        DecimalMoney.toMinor(value, quote.currencyScale)
    }.getOrNull()
    check(
        operation.id.isNotBlank() && operation.type == quote.operationType &&
            operation.walletId == walletId &&
            (quote.productId == null || operation.productId == quote.productId) &&
            (operation.clientReference == null || operation.clientReference == clientReference) &&
            operation.currency.code.equals(quote.currencyCode, ignoreCase = true) &&
            operation.currency.scale.toIntOrNull() == quote.currencyScale &&
            minor(operation.amount) == quote.amountMinor &&
            minor(operation.fee) == quote.feesMinor &&
            minor(operation.total) == quote.customerDebitMinor,
    ) { "The submitted operation does not match the approved quote" }
}
