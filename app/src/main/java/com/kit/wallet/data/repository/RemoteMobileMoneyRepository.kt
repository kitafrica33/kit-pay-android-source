package com.kit.wallet.data.repository

import com.kit.wallet.data.local.WalletCache
import com.kit.wallet.data.mapper.DecimalMoney
import com.kit.wallet.data.remote.ApiCallExecutor
import com.kit.wallet.data.remote.CreateMobileMoneyAccountRequest
import com.kit.wallet.data.remote.CreateMobileMoneyOperationRequest
import com.kit.wallet.data.remote.CreateMobileMoneyVerificationRequest
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.remote.MobileMoneyAccountDto
import com.kit.wallet.data.remote.MobileMoneyNetworkDto
import com.kit.wallet.data.remote.MobileMoneyOperationDto
import com.kit.wallet.data.remote.MobileMoneyVerificationDto
import com.kit.wallet.data.session.SessionStore
import com.kit.wallet.di.ApplicationScope
import com.kit.wallet.ui.model.MobileMoneyAccount
import com.kit.wallet.ui.model.MobileMoneyNetwork
import com.kit.wallet.ui.model.MobileMoneyOperation
import com.kit.wallet.ui.model.MobileMoneyVerificationState
import java.math.BigDecimal
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@Singleton
class RemoteMobileMoneyRepository @Inject constructor(
    private val api: KitWalletApi,
    private val apiCalls: ApiCallExecutor,
    private val walletCache: WalletCache,
    private val paymentAuthorizer: PaymentAuthorizer,
    private val walletRefreshTrigger: WalletRefreshTrigger,
    private val sessions: SessionStore,
    @ApplicationScope private val scope: CoroutineScope,
) : MobileMoneyRepository {
    private val mutableNetworks = MutableStateFlow<List<MobileMoneyNetwork>>(emptyList())
    override val networks: StateFlow<List<MobileMoneyNetwork>> = mutableNetworks.asStateFlow()

    private val mutableAccounts = MutableStateFlow<List<MobileMoneyAccount>>(emptyList())
    override val accounts: StateFlow<List<MobileMoneyAccount>> = mutableAccounts.asStateFlow()

    private val mutableOperations = MutableStateFlow<List<MobileMoneyOperation>>(emptyList())
    override val operations: StateFlow<List<MobileMoneyOperation>> = mutableOperations.asStateFlow()

    private val mutableVerification = MutableStateFlow<MobileMoneyVerificationState?>(null)
    override val verification: StateFlow<MobileMoneyVerificationState?> = mutableVerification.asStateFlow()

    init {
        scope.launch {
            sessions.session.map { it?.cacheScopeId }.distinctUntilChanged().collectLatest { owner ->
                clearSessionProjections()
                if (owner != null) runCatching { refresh() }
            }
        }
    }

    override suspend fun refresh() = coroutineScope {
        val active = sessions.current() ?: return@coroutineScope
        val fence = active.fence()
        val networkRequest = async { apiCalls.execute { api.mobileMoneyNetworks() }.items.orEmpty() }
        val accountRequest = async { apiCalls.execute { api.mobileMoneyAccounts() }.items }
        val operationRequest = async { apiCalls.execute { api.mobileMoneyOperations() }.items }

        val networks = networkRequest.await().map { it.toUiModel() }
        val accounts = accountRequest.await().map { it.toUiModel() }
        val operations = operationRequest.await().map { it.toUiModel() }
        sessions.withCurrentSession(fence) {
            mutableNetworks.value = networks
            mutableAccounts.value = accounts
            mutableOperations.value = operations
        }
    }

    private fun clearSessionProjections() {
        mutableNetworks.value = emptyList()
        mutableAccounts.value = emptyList()
        mutableOperations.value = emptyList()
        mutableVerification.value = null
    }

    override suspend fun verifyAndSaveAccount(
        networkCode: String,
        phoneNumber: String,
        label: String,
        kind: String,
    ) {
        val normalizedNetwork = networkCode.trim().uppercase()
        val normalizedPhone = phoneNumber.filterNot { it.isWhitespace() || it == '-' }
        val normalizedLabel = label.trim()
        require(normalizedPhone.matches(Regex("^\\+?[0-9]{9,15}$"))) {
            "Enter a valid mobile money number, including its country code"
        }
        require(normalizedLabel.isNotBlank()) { "Enter a name for this mobile money account" }
        require(kind in ACCOUNT_KINDS) { "Choose whether this account is yours or a beneficiary's" }

        val network = findNetwork(normalizedNetwork)
        check(network.canVerifyAccount) { "${network.name} account verification is unavailable" }
        var verification = apiCalls.execute {
            api.createMobileMoneyVerification(
                idempotencyKey("verify"),
                CreateMobileMoneyVerificationRequest(normalizedNetwork, normalizedPhone),
            )
        }
        publishVerification(verification)

        var pollCount = 0
        while (verification.status.lowercase() in VERIFICATION_PENDING_STATUSES &&
            pollCount < VERIFICATION_POLL_LIMIT
        ) {
            delay(VERIFICATION_POLL_INTERVAL_MILLIS)
            verification = apiCalls.execute { api.mobileMoneyVerification(verification.id) }
            publishVerification(verification)
            pollCount++
        }

        check(verification.status.equals("verified", ignoreCase = true)) {
            verification.failure?.message
                ?: "The account is still being verified. Try again shortly."
        }
        apiCalls.execute {
            api.createMobileMoneyAccount(
                idempotencyKey("account"),
                CreateMobileMoneyAccountRequest(verification.id, kind, normalizedLabel),
            )
        }
        mutableAccounts.value = apiCalls.execute { api.mobileMoneyAccounts() }
            .items
            .map { it.toUiModel() }
    }

    override suspend fun createOperation(
        action: String,
        accountId: String,
        amountMinor: Long,
        paymentPin: String,
    ) {
        require(action in OPERATION_ACTIONS) { "Choose cash in or cash out" }
        require(amountMinor > 0) { "Enter a positive amount" }
        val account = findAccount(accountId)
        check(account.status == "active") { "This mobile money account is not active" }
        check(action != "collection" || account.isOwnAccount) {
            "Cash in requires a verified mobile money account that belongs to you"
        }
        val active = requireNotNull(sessions.current()) { "Sign in again to access this wallet" }
        val wallet = sessions.withCurrentSession(active.fence()) { current ->
            requireNotNull(walletCache.selectedWallet(current.cacheScopeId)) {
                "No active wallet is selected"
            }
        }
        check(wallet.currencyCode.equals(account.currencyCode, ignoreCase = true)) {
            "Choose a ${account.currencyCode} wallet for this mobile money account"
        }
        check(wallet.currencyScale == account.currencyScale) {
            "The wallet and mobile money currency scales do not match"
        }
        val amount = DecimalMoney.fromMinor(amountMinor, account.currencyScale)
        check(BigDecimal(amount).stripTrailingZeros().scale() <= 0) {
            "RukaPay mobile money amounts must be whole ${account.currencyCode} values"
        }

        val intent = linkedMapOf<String, Any?>(
            "action" to action,
            "wallet_id" to wallet.uuid,
            "mobile_money_account_id" to account.id,
            "network" to account.networkCode,
            "amount" to amount,
            "currency" to account.currencyCode,
        )
        val purpose = if (action == "collection") {
            "mobile_money_collection"
        } else {
            "mobile_money_payout"
        }
        val stepUpToken = paymentAuthorizer.authorize(purpose, intent, paymentPin)
        val request = CreateMobileMoneyOperationRequest(wallet.uuid, account.id, amount)
        val operation = apiCalls.execute {
            if (action == "collection") {
                api.createMobileMoneyCollection(idempotencyKey("collection"), stepUpToken, request)
            } else {
                api.createMobileMoneyPayout(idempotencyKey("payout"), stepUpToken, request)
            }
        }
        mergeOperation(operation.toUiModel())
        walletRefreshTrigger.refreshNow()
        scope.launch { pollOperation(operation.id) }
    }

    private suspend fun findNetwork(code: String): MobileMoneyNetwork {
        if (mutableNetworks.value.none { it.code == code }) {
            mutableNetworks.value = apiCalls.execute { api.mobileMoneyNetworks() }
                .items.orEmpty()
                .map { it.toUiModel() }
        }
        return requireNotNull(mutableNetworks.value.firstOrNull { it.code == code }) {
            "Choose an available mobile money network"
        }
    }

    private suspend fun findAccount(id: String): MobileMoneyAccount {
        if (mutableAccounts.value.none { it.id == id }) {
            mutableAccounts.value = apiCalls.execute { api.mobileMoneyAccounts() }
                .items
                .map { it.toUiModel() }
        }
        return requireNotNull(mutableAccounts.value.firstOrNull { it.id == id }) {
            "Choose a saved mobile money account"
        }
    }

    private suspend fun pollOperation(operationId: String) {
        repeat(OPERATION_POLL_LIMIT) {
            delay(OPERATION_POLL_INTERVAL_MILLIS)
            val operation = runCatching {
                apiCalls.execute { api.mobileMoneyOperation(operationId) }
            }.getOrNull() ?: return@repeat
            val model = operation.toUiModel()
            mergeOperation(model)
            if (model.status.lowercase() in OPERATION_TERMINAL_STATUSES) {
                walletRefreshTrigger.refreshNow()
                return
            }
        }
    }

    private fun publishVerification(value: MobileMoneyVerificationDto) {
        mutableVerification.value = MobileMoneyVerificationState(
            id = value.id,
            status = value.status,
            phoneNumberMasked = value.accountNumberMasked,
            accountName = value.verifiedAccountName,
            failureMessage = value.failure?.message,
        )
    }

    private fun mergeOperation(operation: MobileMoneyOperation) {
        mutableOperations.value = (listOf(operation) +
            mutableOperations.value.filterNot { it.id == operation.id })
            .sortedByDescending { it.createdAt.orEmpty() }
    }

    private fun idempotencyKey(command: String): String =
        "android-mobile-$command-${UUID.randomUUID()}"

    private fun MobileMoneyNetworkDto.toUiModel(): MobileMoneyNetwork {
        val scale = currency.scale.toInt()
        require(scale in 0..9) { "Unsupported currency scale: $scale" }
        return MobileMoneyNetwork(
            id = id,
            code = code.uppercase(),
            name = name,
            currencyCode = currency.code.uppercase(),
            currencyScale = scale,
            canCollect = capabilities?.get("collections") == true,
            canPayout = capabilities?.get("payouts") == true,
            canVerifyAccount = capabilities?.get("account_verification") == true,
        )
    }

    private fun MobileMoneyAccountDto.toUiModel(): MobileMoneyAccount {
        val mappedNetwork = network.toUiModel()
        return MobileMoneyAccount(
            id = id,
            kind = kind,
            label = label,
            networkCode = mappedNetwork.code,
            networkName = mappedNetwork.name,
            accountName = accountName?.takeIf(String::isNotBlank) ?: "Account holder unavailable",
            phoneNumberMasked = phoneNumberMasked,
            currencyCode = mappedNetwork.currencyCode,
            currencyScale = mappedNetwork.currencyScale,
            status = status.lowercase(),
        )
    }

    private fun MobileMoneyOperationDto.toUiModel(): MobileMoneyOperation {
        val scale = currency.scale.toInt()
        return MobileMoneyOperation(
            id = id,
            reference = reference,
            action = mobileMoneyType,
            accountId = beneficiaryId,
            networkCode = network.code.uppercase(),
            networkName = network.name,
            amountMinor = DecimalMoney.toMinor(amount, scale),
            currencyCode = currency.code.uppercase(),
            currencyScale = scale,
            status = status.lowercase(),
            submissionStage = submissionStage,
            createdAt = createdAt,
            failureMessage = failure?.message,
        )
    }

    private companion object {
        val ACCOUNT_KINDS = setOf("own", "third_party")
        val OPERATION_ACTIONS = setOf("collection", "payout")
        val VERIFICATION_PENDING_STATUSES = setOf("pending", "queued", "processing", "submitted")
        val OPERATION_TERMINAL_STATUSES = setOf(
            "completed",
            "succeeded",
            "failed",
            "reversed",
            "cancelled",
            "canceled",
        )
        const val VERIFICATION_POLL_LIMIT = 30
        const val VERIFICATION_POLL_INTERVAL_MILLIS = 1_000L
        const val OPERATION_POLL_LIMIT = 40
        const val OPERATION_POLL_INTERVAL_MILLIS = 1_500L
    }
}
