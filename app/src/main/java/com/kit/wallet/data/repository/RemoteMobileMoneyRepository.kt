package com.kit.wallet.data.repository

import com.kit.wallet.data.local.WalletCache
import com.kit.wallet.data.mapper.DecimalMoney
import com.kit.wallet.data.remote.ApiCallExecutor
import com.kit.wallet.data.remote.CreateMobileMoneyAccountRequest
import com.kit.wallet.data.remote.CreateMobileMoneyOperationRequest
import com.kit.wallet.data.remote.CreateMobileMoneyQuoteRequest
import com.kit.wallet.data.remote.CreateQuotedMobileMoneyOperationRequest
import com.kit.wallet.data.remote.KitWalletApiException
import com.kit.wallet.data.remote.CreateMobileMoneyVerificationRequest
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.remote.MobileMoneyAccountDto
import com.kit.wallet.data.remote.MobileMoneyNetworkDto
import com.kit.wallet.data.remote.MobileMoneyOperationDto
import com.kit.wallet.data.remote.MobileMoneyVerificationDto
import com.kit.wallet.data.realtime.KitNetworkSource
import com.kit.wallet.data.session.SessionFence
import com.kit.wallet.data.session.SessionStore
import com.kit.wallet.di.ApplicationScope
import com.kit.wallet.ui.model.MobileMoneyAccount
import com.kit.wallet.ui.model.MobileMoneyNetwork
import com.kit.wallet.ui.model.MobileMoneyOperation
import com.kit.wallet.ui.model.MobileMoneyVerificationState
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Singleton
class RemoteMobileMoneyRepository @Inject constructor(
    private val api: KitWalletApi,
    private val apiCalls: ApiCallExecutor,
    private val walletCache: WalletCache,
    private val paymentAuthorizer: PaymentAuthorizer,
    private val walletRefreshTrigger: WalletRefreshTrigger,
    private val beneficiaryContacts: BeneficiaryContactDirectory,
    private val sessions: SessionStore,
    private val networkSource: KitNetworkSource,
    @ApplicationScope private val scope: CoroutineScope,
) : MobileMoneyRepository {
    private val activeSettlementScreens = AtomicInteger(0)
    private val operationPoller = SettlementReconciliationPoller(
        scope = scope,
        currentSession = { sessions.current()?.fence() },
        canPoll = { activeSettlementScreens.get() > 0 && networkSource.online.value },
    )

    private val mutableNetworks = MutableStateFlow<List<MobileMoneyNetwork>>(emptyList())
    override val networks: StateFlow<List<MobileMoneyNetwork>> = mutableNetworks.asStateFlow()

    private val mutableAccounts = MutableStateFlow<List<MobileMoneyAccount>>(emptyList())
    override val accounts: StateFlow<List<MobileMoneyAccount>> = mutableAccounts.asStateFlow()

    private val mutableOperations = MutableStateFlow<List<MobileMoneyOperation>>(emptyList())
    override val operations: StateFlow<List<MobileMoneyOperation>> = mutableOperations.asStateFlow()

    private val mutableVerification = MutableStateFlow<MobileMoneyVerificationState?>(null)
    override val verification: StateFlow<MobileMoneyVerificationState?> = mutableVerification.asStateFlow()

    init {
        networkSource.start()
        scope.launch {
            sessions.session.map { it?.cacheScopeId }.distinctUntilChanged().collectLatest { owner ->
                operationPoller.cancelAll()
                clearSessionProjections()
                if (owner != null) runCatching { refresh() }
            }
        }
        scope.launch {
            networkSource.online.collectLatest { online ->
                if (!online) {
                    operationPoller.cancelAll()
                } else if (activeSettlementScreens.get() > 0) {
                    runCatching { refresh() }
                }
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
        val operationItems = operationRequest.await()
        val operations = operationItems
            .filter { it.hasVerifiedCustomerActivityProjection() }
            .map { it.toUiModel() }
        sessions.withCurrentSession(fence) {
            // Only ids this repository already knew to be mobile money accounts are named as gone,
            // so a refresh here can never drop a bank beneficiary's link out of the shared table.
            beneficiaryContacts.forget(
                fence,
                mutableAccounts.value.map { it.id } - accounts.map { it.id }.toSet(),
            )
            mutableNetworks.value = networks
            mutableAccounts.value = accounts
            mutableOperations.value = operations
        }
        if (activeSettlementScreens.get() > 0) {
            operationItems
                .filterNot { it.status.isTerminalSettlementStatus() }
                .forEach { ensureOperationPolling(fence, it.id) }
        }
    }

    override fun setSettlementScreenActive(active: Boolean) {
        val count = if (active) {
            activeSettlementScreens.incrementAndGet()
        } else {
            activeSettlementScreens.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
        }
        if (active && count == 1) {
            if (networkSource.online.value) scope.launch { runCatching { refresh() } }
        } else if (!active && count == 0) {
            operationPoller.cancelAll()
        }
    }

    override fun reconcileSettlementHint(operationId: String) {
        val owner = sessions.current()?.fence() ?: return
        // Durable wallet refresh survives service/process teardown and waits for connectivity.
        walletRefreshTrigger.refreshNow()
        if (!networkSource.online.value) return
        if (activeSettlementScreens.get() > 0) {
            ensureOperationPolling(owner, operationId, restart = true)
        } else {
            // A push is one piece of new information, so one exact read is warranted even when
            // the financial screen is closed. It never turns into a background polling loop.
            scope.launch { runCatching { reconcileOperation(owner, operationId) } }
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
        val expected = requireNotNull(sessions.current()) {
            "Sign in again to save a mobile money account"
        }.fence()
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

        requireCustomerVerifiedMobileMoneyAccount(verification)
        val saved = apiCalls.execute {
            api.createMobileMoneyAccount(
                idempotencyKey("account"),
                CreateMobileMoneyAccountRequest(verification.id, kind, normalizedLabel),
            )
        }
        // Every later response masks this number, so this is the one moment the app can record
        // which contact the destination belongs to. See [BeneficiaryContactDirectory].
        beneficiaryContacts.remember(expected, saved.id, normalizedPhone)
        val refreshedAccounts = apiCalls.execute { api.mobileMoneyAccounts() }
            .items
            .map { it.toUiModel() }
        sessions.withCurrentSession(expected) {
            mutableAccounts.value = refreshedAccounts
        }
    }

    override suspend fun createOperation(
        action: String,
        accountId: String,
        amountMinor: Long,
        paymentPin: String,
        feeMode: String,
    ) {
        submitOperation(previewOperation(action, accountId, amountMinor, feeMode), paymentPin)
    }

    override suspend fun previewOperation(
        action: String,
        accountId: String,
        amountMinor: Long,
        feeMode: String,
    ): FinancialOperationQuote {
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
            "Mobile money amounts must be whole ${account.currencyCode} values"
        }

        val legacyIntent = linkedMapOf<String, Any?>(
            "action" to action,
            "wallet_id" to wallet.uuid,
            "mobile_money_account_id" to account.id,
            "network" to account.networkCode,
            "amount" to amount,
            "currency" to account.currencyCode,
        )
        require(feeMode in if (action == "collection") COLLECTION_FEE_MODES else PAYOUT_FEE_MODES) {
            "Choose how mobile money fees are paid"
        }
        val quote = try {
            apiCalls.execute {
                val request = CreateMobileMoneyQuoteRequest(wallet.uuid, account.id, amount, feeMode)
                if (action == "collection") api.createMobileMoneyCollectionQuote(request)
                else api.createMobileMoneyPayoutQuote(request)
            }
        } catch (error: KitWalletApiException) {
            if (error.statusCode != 404) throw error
            null
        }
        return if (quote != null) {
            validateMobileMoneyQuote(
                quote = quote,
                action = action,
                walletId = wallet.uuid,
                accountId = account.id,
                amount = amount,
                feeMode = feeMode,
                currency = account.currencyCode,
                currencyScale = account.currencyScale,
            )
            val recipient = if (action == "collection") quote.walletCredit else quote.recipientAmount
            val fees = customerFeeAmountForPublicContract(
                totalFees = quote.totalFees,
                processingFee = quote.processingFee,
                pricingScope = quote.pricingScope,
            )
            val debit = if (action == "collection") quote.providerAmount else quote.customerDebit
            FinancialOperationQuote(
                quoteId = quote.id,
                operationType = action,
                destinationId = account.id,
                amountMinor = amountMinor,
                recipientAmountMinor = DecimalMoney.toMinor(requireNotNull(recipient), account.currencyScale),
                feesMinor = DecimalMoney.toMinor(requireNotNull(fees), account.currencyScale),
                customerDebitMinor = DecimalMoney.toMinor(requireNotNull(debit), account.currencyScale),
                currencyCode = account.currencyCode,
                currencyScale = account.currencyScale,
                feeMode = feeMode,
                expiresAt = quote.expiresAt,
                feesKnown = true,
                authorizationPurpose = quote.stepUp.purpose,
                authorizationIntent = quote.stepUp.intent.mapValues { it.value as Any? },
                sessionFence = active.fence(),
            )
        } else {
            FinancialOperationQuote(
                quoteId = null,
                operationType = action,
                destinationId = account.id,
                amountMinor = amountMinor,
                recipientAmountMinor = amountMinor,
                feesMinor = 0,
                customerDebitMinor = amountMinor,
                currencyCode = account.currencyCode,
                currencyScale = account.currencyScale,
                feeMode = feeMode,
                expiresAt = null,
                feesKnown = false,
                authorizationPurpose = "mobile_money_$action",
                authorizationIntent = legacyIntent,
                sessionFence = active.fence(),
            )
        }
    }

    override suspend fun submitOperation(
        quote: FinancialOperationQuote,
        paymentPin: String,
    ): String {
        require(quote.operationType in OPERATION_ACTIONS) { "The mobile money quote is invalid" }
        check(sessions.current()?.fence() == quote.sessionFence) {
            "The signed-in account changed after this quote was created"
        }
        quote.expiresAt?.let { expiresAt ->
            check(runCatching { Instant.parse(expiresAt).isAfter(Instant.now()) }.getOrDefault(false)) {
                "This mobile money quote has expired. Review a new quote."
            }
        }
        val stepUpToken = paymentAuthorizer.authorize(
            quote.authorizationPurpose,
            quote.authorizationIntent,
            paymentPin,
        )
        val operation = if (quote.quoteId != null) {
            val request = CreateQuotedMobileMoneyOperationRequest(quote.quoteId)
            apiCalls.execute {
                if (quote.operationType == "collection") {
                    api.createQuotedMobileMoneyCollection(idempotencyKey("collection"), stepUpToken, request)
                } else {
                    api.createQuotedMobileMoneyPayout(idempotencyKey("payout"), stepUpToken, request)
                }
            }
        } else {
            val walletId = quote.authorizationIntent["wallet_id"] as? String
                ?: error("The mobile money quote omitted its wallet")
            val amount = quote.authorizationIntent["amount"] as? String
                ?: error("The mobile money quote omitted its amount")
            val request = CreateMobileMoneyOperationRequest(walletId, quote.destinationId, amount)
            apiCalls.execute {
                if (quote.operationType == "collection") {
                    api.createMobileMoneyCollection(idempotencyKey("collection"), stepUpToken, request)
                } else {
                    api.createMobileMoneyPayout(idempotencyKey("payout"), stepUpToken, request)
                }
            }
        }
        if (operation.hasVerifiedCustomerActivityProjection()) {
            sessions.withCurrentSession(quote.sessionFence) {
                mergeOperation(operation.toUiModel())
            }
        }
        walletRefreshTrigger.refreshNow()
        if (
            activeSettlementScreens.get() > 0 &&
            !operation.status.isTerminalSettlementStatus()
        ) {
            ensureOperationPolling(quote.sessionFence, operation.id)
        }
        return operation.id
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

    private fun ensureOperationPolling(
        owner: SessionFence,
        operationId: String,
        restart: Boolean = false,
    ) {
        val reconcile = suspend { reconcileOperation(owner, operationId) }
        if (restart) {
            operationPoller.restart(owner, operationId, reconcile)
        } else {
            operationPoller.ensure(owner, operationId, reconcile)
        }
    }

    private suspend fun reconcileOperation(
        owner: SessionFence,
        operationId: String,
    ): SettlementPollResult {
        val response = apiCalls.execute { api.mobileMoneyOperation(operationId) }
        requireExactSettlementOperationId(operationId, response.id)
        val model = response.takeIf { it.hasVerifiedCustomerActivityProjection() }?.toUiModel()
        if (model != null) {
            sessions.withCurrentSession(owner) {
                mergeOperation(model)
            }
        } else {
            sessions.withCurrentSession(owner) {
                removeOperation(response.id)
            }
        }
        return if (response.status.isTerminalSettlementStatus()) {
            walletRefreshTrigger.refreshNow()
            SettlementPollResult.TERMINAL
        } else {
            SettlementPollResult.PENDING
        }
    }

    private fun publishVerification(value: MobileMoneyVerificationDto) {
        mutableVerification.value = MobileMoneyVerificationState(
            id = value.id,
            status = value.status,
            phoneNumberMasked = value.accountNumberMasked,
            accountName = value.verifiedAccountName,
            failureMessage = customerSafeMobileMoneyVerificationFailure(value.failure?.code),
        )
    }

    private fun mergeOperation(operation: MobileMoneyOperation) {
        mutableOperations.update { current ->
            val existingIndex = current.indexOfFirst { it.id == operation.id }
            if (existingIndex == -1) {
                listOf(operation) + current
            } else {
                current.toMutableList().apply { this[existingIndex] = operation }
            }
        }
    }

    private fun removeOperation(operationId: String) {
        mutableOperations.update { current -> current.filterNot { it.id == operationId } }
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
            kitUserId = kitUser?.id?.trim()?.takeIf(String::isNotEmpty),
            avatarUrl = kitUser?.avatarUrl?.trim()?.takeIf(String::isNotEmpty),
        )
    }

    private fun MobileMoneyOperationDto.toUiModel(): MobileMoneyOperation {
        val scale = currency.scale.toInt()
        val topLevelFee = customerFeeAmountForPublicContract(
            totalFees = totalFees,
            processingFee = null,
            pricingScope = pricingScope,
        )
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
            failureMessage = customerSafeMobileMoneyOperationFailure(
                failureCode = failure?.code,
                action = mobileMoneyType,
                status = status,
            ),
            feeMinor = if (outboundPricing != null) {
                customerFeeAmountForPublicContract(
                    totalFees = outboundPricing.totalFees,
                    processingFee = outboundPricing.processingFee,
                    pricingScope = outboundPricing.pricingScope,
                )?.let { DecimalMoney.toMinor(it, scale) }
            } else {
                topLevelFee?.let { DecimalMoney.toMinor(it, scale) }
            },
            netAmountMinor = outboundPricing?.recipientAmount?.let { DecimalMoney.toMinor(it, scale) }
                ?: netAmount?.let { DecimalMoney.toMinor(it, scale) },
            customerDebitMinor = outboundPricing?.customerDebit?.let { DecimalMoney.toMinor(it, scale) },
            feeMode = outboundPricing?.feeMode ?: feeMode,
        )
    }

    private companion object {
        val ACCOUNT_KINDS = setOf("own", "third_party")
        val OPERATION_ACTIONS = setOf("collection", "payout")
        val COLLECTION_FEE_MODES = setOf("inclusive", "gross_up")
        val PAYOUT_FEE_MODES = setOf("sender_absorbs", "recipient_absorbs")
        val VERIFICATION_PENDING_STATUSES = setOf("pending", "queued", "processing", "submitted")
        const val VERIFICATION_POLL_LIMIT = 30
        const val VERIFICATION_POLL_INTERVAL_MILLIS = 1_000L
    }
}

internal fun validateMobileMoneyQuote(
    quote: com.kit.wallet.data.remote.MobileMoneyQuoteDto,
    action: String,
    walletId: String,
    accountId: String,
    amount: String,
    feeMode: String,
    currency: String,
    currencyScale: Int,
    now: Instant = Instant.now(),
) {
    val quotedInput = when {
        action == "collection" -> quote.requestedAmount
        feeMode == "recipient_absorbs" -> quote.customerDebit
        else -> quote.recipientAmount
    }
    fun decimal(value: String?) = value?.let { runCatching { BigDecimal(it) }.getOrNull() }
    val customerFee = runCatching {
        customerFeeAmountForPublicContract(
            totalFees = quote.totalFees,
            processingFee = quote.processingFee,
            pricingScope = quote.pricingScope,
        )
    }.getOrNull()
    val expectedIntent = if (action == "collection") {
        mapOf(
            "action" to action, "quote_id" to quote.id, "wallet_id" to walletId,
            "mobile_money_account_id" to accountId, "network" to quote.network,
            "fee_mode" to feeMode, "requested_amount" to quote.requestedAmount,
            "provider_amount" to quote.providerAmount,
            "total_fees" to quote.totalFees, "wallet_credit" to quote.walletCredit,
            "currency" to quote.currency.code,
        ).mapValues { requireNotNull(it.value) }
    } else {
        mapOf(
            "action" to action, "quote_id" to quote.id, "wallet_id" to walletId,
            "mobile_money_account_id" to accountId, "network" to quote.network,
            "fee_mode" to feeMode, "recipient_amount" to quote.recipientAmount,
            "processing_fee" to quote.processingFee,
            "customer_debit" to quote.customerDebit, "currency" to quote.currency.code,
        ).mapValues { requireNotNull(it.value) }
    }
    val legacyIntentKeys = if (action == "collection") {
        setOf("provider_fee", "platform_fee", "rounding_adjustment")
    } else {
        setOf(
            "provider_fee", "kit_fee", "provider_fee_cap", "maximum_provider_total",
            "kit_debit", "schedule_version",
        )
    }
    val allowedIntentKeys = expectedIntent.keys + legacyIntentKeys
    val amountsReconcile = if (action == "collection") {
        val requested = decimal(quote.requestedAmount)
        val providerAmount = decimal(quote.providerAmount)
        val totalFees = decimal(customerFee)
        val walletCredit = decimal(quote.walletCredit)
        requested != null && requested.signum() > 0 && providerAmount != null &&
            providerAmount.signum() > 0 && totalFees != null && totalFees.signum() >= 0 &&
            walletCredit != null && walletCredit.signum() > 0 &&
            providerAmount.compareTo(walletCredit + totalFees) == 0 &&
            when (feeMode) {
                "inclusive" -> providerAmount.compareTo(requested) == 0
                "gross_up" -> walletCredit.compareTo(requested) == 0
                else -> false
            }
    } else {
        val recipient = decimal(quote.recipientAmount)
        val processing = decimal(customerFee)
        val customer = decimal(quote.customerDebit)
        recipient != null && processing != null && customer != null &&
            recipient.signum() > 0 && processing.signum() >= 0 &&
            customer.compareTo(
                if (feeMode == "kit_covers") recipient else recipient + processing
            ) == 0 &&
            quote.scheduleVerified == true
    }
    check(quote.action == action && quote.walletId == walletId && quote.accountId == accountId &&
        quote.feeMode == feeMode && quote.currency.code.equals(currency, ignoreCase = true) &&
        quote.currency.scale.toIntOrNull() == currencyScale &&
        quotedInput?.let(::BigDecimal)?.compareTo(BigDecimal(amount)) == 0 &&
        amountsReconcile &&
        runCatching { Instant.parse(quote.expiresAt).isAfter(now) }.getOrDefault(false) &&
        quote.stepUp.purpose == "mobile_money_$action" &&
        quote.stepUp.intent.keys.all(allowedIntentKeys::contains) &&
        expectedIntent.all { (key, value) -> quote.stepUp.intent[key] == value }
    ) { "The mobile money quote does not match this request" }
}

/**
 * Verifies the customer aggregate used by mobile-money activity before the operation reaches UI.
 * This intentionally rejects legacy principal-only rows and any institutional pricing shape.
 */
internal fun MobileMoneyOperationDto.hasVerifiedCustomerActivityProjection(): Boolean {
    val scale = currency.scale.toIntOrNull()?.takeIf { it in 0..9 } ?: return false
    fun minor(value: String?): Long? = value?.trim()?.let { candidate ->
        runCatching { DecimalMoney.toMinor(candidate, scale) }.getOrNull()
    }
    val nominal = minor(amount)?.takeIf { it > 0 } ?: return false

    return when (mobileMoneyType) {
        "collection" -> {
            if (type != "deposit" || direction != "inbound" || outboundPricing != null) return false
            if (pricingScope != "customer_totals") return false
            val customerFee = minor(totalFees)?.takeIf { it >= 0 } ?: return false
            val walletCredit = minor(netAmount)?.takeIf { it > 0 } ?: return false
            val providerAmount = runCatching {
                Math.addExact(walletCredit, customerFee)
            }.getOrNull() ?: return false
            nominal == providerAmount
        }
        "payout" -> {
            if (type !in setOf("withdrawal", "bank_transfer") || direction != "outbound") {
                return false
            }
            val pricing = outboundPricing ?: return false
            if (pricing.pricingScope != "customer_totals") return false
            if (feeMode != null && feeMode != pricing.feeMode) return false

            val recipient = minor(pricing.recipientAmount)?.takeIf { it > 0 } ?: return false
            val customerFee = minor(pricing.totalFees)?.takeIf { it >= 0 } ?: return false
            val compatibilityFee = minor(pricing.processingFee)?.takeIf { it >= 0 } ?: return false
            val customerDebit = minor(pricing.customerDebit)?.takeIf { it > 0 } ?: return false
            if (nominal != recipient || customerFee != compatibilityFee) return false

            val expectedDebit = when (pricing.feeMode) {
                "kit_covers" -> recipient
                "sender_absorbs", "recipient_absorbs" ->
                    runCatching { Math.addExact(recipient, customerFee) }.getOrNull() ?: return false
                else -> return false
            }
            if (customerDebit != expectedDebit) return false

            if (pricingScope != null || totalFees != null) {
                if (pricingScope != "customer_totals") return false
                if (minor(totalFees) != customerFee) return false
            }
            true
        }
        else -> false
    }
}

/** Persisted provider diagnostics stay server-side; mobile renders only stable customer copy. */
internal fun customerSafeMobileMoneyVerificationFailure(failureCode: String?): String? =
    failureCode?.let { "We could not verify these account details. Review them and try again." }

/**
 * Terminates account verification without ever promoting provider diagnostics to UI exceptions.
 * [MobileMoneyViewModel] displays repository exception messages, so the raw failure message must
 * be discarded at this boundary just as it is when publishing [MobileMoneyVerificationState].
 */
internal fun requireCustomerVerifiedMobileMoneyAccount(
    verification: MobileMoneyVerificationDto,
) {
    check(verification.status.equals("verified", ignoreCase = true)) {
        customerSafeMobileMoneyVerificationFailure(verification.failure?.code)
            ?: "The account is still being verified. Try again shortly."
    }
}

/** Persisted provider/ledger/settlement diagnostics must never be copied into customer activity. */
internal fun customerSafeMobileMoneyOperationFailure(
    failureCode: String?,
    action: String,
    status: String,
): String? = failureCode?.let {
    when (status) {
        "failed" -> if (action == "collection") {
            "This deposit could not be completed. No money was added to your wallet."
        } else {
            "This payment could not be completed. Check your balance before trying again."
        }
        "unknown" -> "We could not confirm this transaction yet. Check again before retrying."
        else -> "This transaction needs attention. Contact support with the reference."
    }
}
