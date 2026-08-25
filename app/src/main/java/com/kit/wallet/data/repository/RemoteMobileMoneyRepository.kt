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
import com.kit.wallet.data.session.SessionStore
import com.kit.wallet.di.ApplicationScope
import com.kit.wallet.ui.model.MobileMoneyAccount
import com.kit.wallet.ui.model.MobileMoneyNetwork
import com.kit.wallet.ui.model.MobileMoneyOperation
import com.kit.wallet.ui.model.MobileMoneyVerificationState
import java.math.BigDecimal
import java.time.Instant
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
    private val beneficiaryContacts: BeneficiaryContactDirectory,
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

        check(verification.status.equals("verified", ignoreCase = true)) {
            verification.failure?.message
                ?: "The account is still being verified. Try again shortly."
        }
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
            val fees = if (action == "collection") quote.totalFees else quote.processingFee
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
        mergeOperation(operation.toUiModel())
        walletRefreshTrigger.refreshNow()
        scope.launch { pollOperation(operation.id) }
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
            kitUserId = kitUser?.id?.trim()?.takeIf(String::isNotEmpty),
            avatarUrl = kitUser?.avatarUrl?.trim()?.takeIf(String::isNotEmpty),
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
            feeMinor = outboundPricing?.processingFee?.let { DecimalMoney.toMinor(it, scale) }
                ?: totalFees?.let { DecimalMoney.toMinor(it, scale) },
            netAmountMinor = outboundPricing?.recipientAmount?.let { DecimalMoney.toMinor(it, scale) }
                ?: netAmount?.let { DecimalMoney.toMinor(it, scale) },
            customerDebitMinor = outboundPricing?.customerDebit?.let { DecimalMoney.toMinor(it, scale) },
            feeMode = outboundPricing?.feeMode ?: feeMode,
            providerFeeEstimated = providerFeeEstimated,
        )
    }

    private companion object {
        val ACCOUNT_KINDS = setOf("own", "third_party")
        val OPERATION_ACTIONS = setOf("collection", "payout")
        val COLLECTION_FEE_MODES = setOf("inclusive", "gross_up")
        val PAYOUT_FEE_MODES = setOf("sender_absorbs", "recipient_absorbs")
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
    val expectedIntent = if (action == "collection") {
        mapOf(
            "action" to action, "quote_id" to quote.id, "wallet_id" to walletId,
            "mobile_money_account_id" to accountId, "network" to quote.network,
            "fee_mode" to feeMode, "requested_amount" to quote.requestedAmount,
            "provider_amount" to quote.providerAmount, "provider_fee" to quote.providerFee,
            "platform_fee" to quote.platformFee, "rounding_adjustment" to quote.roundingAdjustment,
            "total_fees" to quote.totalFees, "wallet_credit" to quote.walletCredit,
            "currency" to quote.currency.code,
        ).mapValues { requireNotNull(it.value) }
    } else {
        mapOf(
            "action" to action, "quote_id" to quote.id, "wallet_id" to walletId,
            "mobile_money_account_id" to accountId, "network" to quote.network,
            "fee_mode" to feeMode, "recipient_amount" to quote.recipientAmount,
            "processing_fee" to quote.processingFee, "provider_fee" to quote.providerFee,
            "kit_fee" to quote.kitFee, "provider_fee_cap" to quote.providerFeeCap,
            "maximum_provider_total" to quote.maximumProviderTotal,
            "customer_debit" to quote.customerDebit, "kit_debit" to quote.kitDebit,
            "schedule_version" to quote.scheduleVersion, "currency" to quote.currency.code,
        ).mapValues { requireNotNull(it.value) }
    }
    val amountsReconcile = if (action == "collection") {
        val providerAmount = decimal(quote.providerAmount)
        val totalFees = decimal(quote.totalFees)
        val walletCredit = decimal(quote.walletCredit)
        providerAmount != null && totalFees != null && walletCredit != null &&
            providerAmount.compareTo(walletCredit + totalFees) == 0
    } else {
        val recipient = decimal(quote.recipientAmount)
        val processing = decimal(quote.processingFee)
        val provider = decimal(quote.providerFee)
        val kitFee = decimal(quote.kitFee)
        val providerCap = decimal(quote.providerFeeCap)
        val maximumTotal = decimal(quote.maximumProviderTotal)
        val customer = decimal(quote.customerDebit)
        val kitDebit = decimal(quote.kitDebit)
        recipient != null && processing != null && provider != null && kitFee != null &&
            providerCap != null && maximumTotal != null && customer != null && kitDebit != null &&
            processing.compareTo(provider + kitFee) == 0 && providerCap.compareTo(provider) == 0 &&
            maximumTotal.compareTo(recipient + providerCap) == 0 &&
            customer.compareTo(recipient + processing) == 0 && kitDebit.signum() == 0 &&
            quote.scheduleVerified == true
    }
    check(quote.action == action && quote.walletId == walletId && quote.accountId == accountId &&
        quote.feeMode == feeMode && quote.currency.code.equals(currency, ignoreCase = true) &&
        quote.currency.scale.toIntOrNull() == currencyScale &&
        quotedInput?.let(::BigDecimal)?.compareTo(BigDecimal(amount)) == 0 &&
        amountsReconcile &&
        runCatching { Instant.parse(quote.expiresAt).isAfter(now) }.getOrDefault(false) &&
        quote.stepUp.purpose == "mobile_money_$action" &&
        quote.stepUp.intent == expectedIntent
    ) { "The mobile money quote does not match this request" }
}
