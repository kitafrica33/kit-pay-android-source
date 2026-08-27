package com.kit.wallet.data.repository

import com.kit.wallet.data.local.WalletCache
import com.kit.wallet.data.mapper.DecimalMoney
import com.kit.wallet.data.remote.ApiCallExecutor
import com.kit.wallet.data.remote.AttachBankDepositProofRequest
import com.kit.wallet.data.remote.BankDepositProofUploader
import com.kit.wallet.data.remote.BankDepositRequestDto
import com.kit.wallet.data.remote.BankFundingAccountDto
import com.kit.wallet.data.remote.CreateBankDepositRequest
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.session.SessionStore
import com.kit.wallet.di.ApplicationScope
import java.math.BigDecimal
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@Singleton
class RemoteBankDepositRepository @Inject constructor(
    private val api: KitWalletApi,
    private val apiCalls: ApiCallExecutor,
    private val walletCache: WalletCache,
    private val sessions: SessionStore,
    private val proofUploader: BankDepositProofUploader,
    @ApplicationScope scope: CoroutineScope,
) : BankDepositRepository {
    private val mutableFundingAccounts = MutableStateFlow<List<BankFundingAccount>>(emptyList())
    override val fundingAccounts: StateFlow<List<BankFundingAccount>> =
        mutableFundingAccounts.asStateFlow()

    private val mutableDeposits = MutableStateFlow<List<BankDeposit>>(emptyList())
    override val deposits: StateFlow<List<BankDeposit>> = mutableDeposits.asStateFlow()

    init {
        scope.launch {
            sessions.session.map { it?.cacheScopeId }.distinctUntilChanged().collectLatest {
                mutableFundingAccounts.value = emptyList()
                mutableDeposits.value = emptyList()
            }
        }
    }

    override suspend fun refresh() {
        val active = requireNotNull(sessions.current()) { "Sign in again to use bank deposits" }
        val fence = active.fence()
        val wallet = sessions.withCurrentSession(fence) {
            requireNotNull(walletCache.selectedWallet(active.cacheScopeId)) {
                "Choose an active Kit Pay wallet"
            }
        }
        val accounts = apiCalls.execute { api.bankFundingAccounts() }.items
            .map(BankFundingAccountDto::toDomain)
            .filter { it.active && it.currencyCode.equals(wallet.currencyCode, ignoreCase = true) }
            .sortedBy { it.label.lowercase(Locale.ROOT) }
        val deposits = apiCalls.execute { api.bankDepositRequests() }.items
            .map(BankDepositRequestDto::toDomain)
            .filter { it.walletId == wallet.uuid }
            .sortedByDescending(BankDeposit::createdAt)
        sessions.withCurrentSession(fence) {
            mutableFundingAccounts.value = accounts
            mutableDeposits.value = deposits
        }
    }

    override suspend fun create(
        fundingAccountId: String,
        amountMinor: Long,
        note: String?,
    ): BankDeposit {
        require(amountMinor > 0) { "Enter a deposit amount greater than zero" }
        val active = requireNotNull(sessions.current()) { "Sign in again to use bank deposits" }
        val fence = active.fence()
        val wallet = sessions.withCurrentSession(fence) {
            requireNotNull(walletCache.selectedWallet(active.cacheScopeId)) {
                "Choose an active Kit Pay wallet"
            }
        }
        val account = mutableFundingAccounts.value.firstOrNull {
            it.id == fundingAccountId && it.active &&
                it.currencyCode.equals(wallet.currencyCode, ignoreCase = true)
        } ?: error("Choose an available receiving account for this wallet")
        val cleanNote = note?.trim()?.takeIf(String::isNotEmpty)
        require(cleanNote == null || cleanNote.length <= 280) {
            "Keep the optional note to 280 characters or fewer"
        }
        val wireAmount = BigDecimal.valueOf(amountMinor, wallet.currencyScale)
            .stripTrailingZeros()
            .toPlainString()
        val created = apiCalls.execute {
            api.createBankDepositRequest(
                idempotencyKey = "android-bank-deposit-${UUID.randomUUID()}",
                request = CreateBankDepositRequest(
                    walletId = wallet.uuid,
                    fundingAccountId = account.id,
                    amount = wireAmount,
                    note = cleanNote,
                ),
            )
        }.toDomain()
        check(created.walletId == wallet.uuid && created.fundingAccount.id == account.id) {
            "Kit Pay returned bank instructions for a different wallet or account"
        }
        check(created.currencyCode.equals(wallet.currencyCode, ignoreCase = true) &&
            created.currencyScale == wallet.currencyScale && created.amountMinor == amountMinor) {
            "Kit Pay returned different bank-deposit currency or amount details"
        }
        check(created.status.equals("awaiting_proof", ignoreCase = true)) {
            "Kit Pay returned an invalid bank-deposit status"
        }
        sessions.withCurrentSession(fence) { upsert(created) }
        return created
    }

    override suspend fun attachProof(
        depositId: String,
        bytes: ByteArray,
        filename: String,
        mimeType: String,
    ): BankDeposit {
        val active = requireNotNull(sessions.current()) { "Sign in again to upload payment proof" }
        val fence = active.fence()
        val current = mutableDeposits.value.firstOrNull { it.id == depositId }
            ?: error("Refresh this bank deposit before uploading its receipt")
        require(current.acceptsProof()) {
            "This bank deposit is no longer accepting payment proof"
        }
        val assetId = proofUploader.upload(bytes, filename, mimeType)
        check(sessions.current()?.fence() == fence) {
            "The signed-in account changed during the receipt upload"
        }
        val updated = apiCalls.execute {
            api.attachBankDepositProof(
                depositId,
                AttachBankDepositProofRequest(assetId),
            )
        }.toDomain()
        check(updated.sameBinding(current) &&
            updated.proof?.assetId?.equals(assetId, ignoreCase = true) == true) {
            "Kit Pay could not confirm that the receipt belongs to this deposit"
        }
        sessions.withCurrentSession(fence) { upsert(updated) }
        return updated
    }

    override suspend fun refreshDeposit(depositId: String): BankDeposit {
        val active = requireNotNull(sessions.current()) { "Sign in again to refresh this deposit" }
        val fence = active.fence()
        val current = mutableDeposits.value.firstOrNull { it.id == depositId }
            ?: error("This bank deposit is unavailable")
        val updated = apiCalls.execute { api.bankDepositRequest(depositId) }.toDomain()
        check(updated.sameBinding(current)) {
            "Kit Pay returned details for a different bank deposit"
        }
        sessions.withCurrentSession(fence) { upsert(updated) }
        return updated
    }

    private fun upsert(deposit: BankDeposit) {
        mutableDeposits.value = (mutableDeposits.value.filterNot { it.id == deposit.id } + deposit)
            .sortedByDescending(BankDeposit::createdAt)
    }
}

internal fun isValidBankDepositReference(value: String): Boolean {
    val groups = value.split('-')
    val joined = groups.joinToString("")
    return value == value.uppercase(Locale.ROOT) && groups.size == 4 &&
        groups.all { it.length == 4 && partIsAsciiAlphanumeric(it) } &&
        joined.any { it in 'A'..'Z' } && joined.any { it in '0'..'9' }
}

private fun partIsAsciiAlphanumeric(value: String): Boolean =
    value.all { it in 'A'..'Z' || it in '0'..'9' }

private fun BankFundingAccountDto.toDomain(): BankFundingAccount {
    check(runCatching { UUID.fromString(id) }.isSuccess) { "Invalid receiving-account identifier" }
    check(accountNumber.isNotBlank()) { "The receiving account number is missing" }
    return BankFundingAccount(
        id = id,
        label = label,
        bankId = bank.id,
        bankName = bank.name,
        accountName = accountName,
        accountNumber = accountNumber,
        accountNumberMasked = accountNumberMasked,
        branchName = branchName,
        branchCode = branchCode,
        swiftCode = swiftCode,
        instructions = instructions,
        currencyCode = currency.uppercase(Locale.ROOT),
        active = status.equals("active", ignoreCase = true),
    )
}

private fun BankDepositRequestDto.toDomain(): BankDeposit {
    check(runCatching { UUID.fromString(id) }.isSuccess) { "Invalid bank-deposit identifier" }
    check(isValidBankDepositReference(reference)) { "Invalid bank-deposit reference" }
    val scale = currency.scale.toInt()
    return BankDeposit(
        id = id,
        reference = reference,
        walletId = walletId,
        amountMinor = DecimalMoney.toMinor(amount, scale),
        currencyCode = currency.code.uppercase(Locale.ROOT),
        currencyScale = scale,
        status = status,
        fundingAccount = fundingAccount.toDomain(),
        proof = proof?.let {
            BankDepositProof(
                assetId = it.assetId,
                filename = it.filename,
                status = it.status,
                scanStatus = it.scanStatus,
                mimeType = it.mimeType,
                byteSize = it.byteSize,
            )
        },
        bankTransactionReference = bankTransactionReference,
        customerNote = customerNote,
        rejectionReason = rejection?.reason,
        expiresAt = expiresAt,
        createdAt = createdAt,
        completedAt = completedAt,
    )
}

private fun BankDeposit.sameBinding(other: BankDeposit): Boolean =
    id == other.id && reference == other.reference && walletId == other.walletId &&
        amountMinor == other.amountMinor && currencyCode == other.currencyCode &&
        currencyScale == other.currencyScale && fundingAccount.id == other.fundingAccount.id
