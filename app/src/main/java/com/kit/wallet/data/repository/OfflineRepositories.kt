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
import com.kit.wallet.data.remote.BootstrapDto
import com.kit.wallet.data.remote.CreatePaymentRequestDto
import com.kit.wallet.data.remote.CreateProviderOperationRequest
import com.kit.wallet.data.remote.CreateProviderQuoteRequest
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.remote.EmailAddressRequest
import com.kit.wallet.data.remote.EmailAttachmentVerificationRequest
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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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

    override suspend fun refreshProfile() {
        val session = requireActiveSession()
        val user = apiCalls.execute { api.profile() }
        requireSameAccount(session, user.id)
        val updated = user.toEntity(clock.millis())
        persistProfile(session.fence(), updated)
    }

    override suspend fun updateProfile(name: String, tag: String) {
        val session = requireActiveSession()
        val user = apiCalls.execute {
            api.updateProfile(
                UpdateProfileRequest(
                    name = normalizeProfileName(name),
                    tag = normalizeProfileTag(tag),
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
        profile.first { cached ->
            !cached.profileSetupRequired &&
                cached.name == updated.name &&
                cached.tag == updated.tag
        }
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
    ): Transaction {
        require(amountMinor > 0) { "Amount must be positive" }
        require(paymentPin.matches(Regex("^[0-9]{4}$"))) { "Enter the four-digit wallet PIN" }
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
        return transaction.toEntity(source.uuid).toUiModel()
    }

    override suspend fun request(from: Contact, amountMinor: Long, note: String?) {
        require(amountMinor > 0) { "Amount must be positive" }
        require(from.isKitUser) { "Payment requests can only be sent to Kit Pay users" }
        val destination = requireSelectedWallet()
        apiCalls.execute {
            api.createPaymentRequest(
                idempotencyKey = "android-request-${java.util.UUID.randomUUID()}",
                request = CreatePaymentRequestDto(
                    destinationWalletId = destination.uuid,
                    requestedFromUserId = from.id,
                    amount = DecimalMoney.fromMinor(amountMinor, destination.currencyScale),
                    note = note,
                ),
            )
        }
    }

    override suspend fun createChatPaymentRequest(
        peerUserId: String,
        amountMinor: Long,
        note: String?,
    ): ChatPaymentRequest {
        require(amountMinor > 0) { "Amount must be positive" }
        require(peerUserId.isNotBlank()) { "This conversation has no Kit Pay peer" }
        val destination = requireSelectedWallet()
        val created = apiCalls.execute {
            api.createPaymentRequest(
                idempotencyKey = "android-chat-request-${java.util.UUID.randomUUID()}",
                request = CreatePaymentRequestDto(
                    destinationWalletId = destination.uuid,
                    requestedFromUserId = peerUserId,
                    amount = DecimalMoney.fromMinor(amountMinor, destination.currencyScale),
                    note = note?.trim()?.takeIf(String::isNotBlank),
                ),
            )
        }
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
        require(paymentPin.matches(Regex("^[0-9]{4}$"))) { "Enter the four-digit wallet PIN" }
        val source = requireSelectedWallet()
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
        apiCalls.execute {
            api.payPaymentRequest(
                requestId = requestId,
                idempotencyKey = "android-chat-pay-${java.util.UUID.randomUUID()}",
                stepUpToken = stepUpToken,
                request = com.kit.wallet.data.remote.PayPaymentRequestDto(
                    sourceWalletId = source.uuid,
                ),
            )
        }
        walletSync.refresh()
    }

    override suspend fun payBill(
        provider: BillProvider,
        account: String,
        amountMinor: Long,
        paymentPin: String,
    ): Transaction = createProviderOperation(
        productId = provider.id,
        account = account,
        amountMinor = amountMinor,
        paymentPin = paymentPin,
        type = "bill_payment",
        counterparty = provider.name,
        transactionType = TxType.BILL,
    )

    override suspend fun buyAirtime(
        productId: String,
        phone: String,
        amountMinor: Long,
        paymentPin: String,
    ): Transaction {
        if (providerCatalog.product(productId) == null) providerCatalog.refresh()
        val product = requireNotNull(providerCatalog.product(productId)) {
            "Choose an available airtime network"
        }
        check(product.serviceType == "airtime") { "The selected product is not airtime" }
        return createProviderOperation(
            productId = product.id,
            account = phone,
            amountMinor = amountMinor,
            paymentPin = paymentPin,
            type = "airtime_purchase",
            counterparty = product.name,
            transactionType = TxType.AIRTIME,
        )
    }

    private suspend fun createProviderOperation(
        productId: String,
        account: String,
        amountMinor: Long,
        paymentPin: String,
        type: String,
        counterparty: String,
        transactionType: TxType,
    ): Transaction {
        require(amountMinor > 0) { "Amount must be positive" }
        val wallet = requireSelectedWallet()
        val product = requireNotNull(providerCatalog.product(productId)) { "Provider product is unavailable" }
        val amount = DecimalMoney.fromMinor(amountMinor, product.currency.scale.toInt())
        val quote = apiCalls.execute {
            api.createProviderQuote(productId, CreateProviderQuoteRequest(account, amount))
        }
        val clientReference = "android-provider-${java.util.UUID.randomUUID()}"
        val intent = linkedMapOf<String, Any?>(
            "quote_id" to quote.id,
            "wallet_id" to wallet.uuid,
            "client_reference" to clientReference,
        )
        val stepUpToken = paymentAuthorizer.authorize(type, intent, paymentPin)
        val request = CreateProviderOperationRequest(quote.id, wallet.uuid, clientReference)
        val operation = apiCalls.execute {
            if (type == "bill_payment") {
                api.createBillPayment(clientReference, stepUpToken, request)
            } else {
                api.createAirtimePurchase(clientReference, stepUpToken, request)
            }
        }
        runCatching { walletSync.refresh() }
        return Transaction(
            id = operation.id,
            counterparty = counterparty,
            note = providerDestinationPresentation(operation.accountDisplay),
            amountMinor = -amountMinor,
            time = "Just now",
            dateGroup = "Today",
            type = transactionType,
            status = when (operation.status) {
                "succeeded" -> TxStatus.COMPLETED
                "failed" -> TxStatus.FAILED
                else -> TxStatus.PENDING
            },
            reference = operation.providerReference ?: clientReference,
        )
    }

    private suspend fun requireSelectedWallet(): com.kit.wallet.data.local.WalletEntity {
        val active = requireNotNull(sessions.current()) { "Sign in again to access this wallet" }
        return sessions.withCurrentSession(active.fence()) { current ->
            requireNotNull(cache.selectedWallet(current.cacheScopeId)) {
                "No active wallet is selected"
            }
        }
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
        return WalletSyncResult(wallets.size, transactions.size, page.page.hasMore == true)
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
