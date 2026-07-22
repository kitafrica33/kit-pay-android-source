package com.kit.wallet.data.repository

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.kit.wallet.data.mapper.toBankInstitution
import com.kit.wallet.data.remote.ApiCallExecutor
import com.kit.wallet.data.mapper.DecimalMoney
import com.kit.wallet.data.local.WalletCache
import com.kit.wallet.data.remote.CreateBankBeneficiaryRequest
import com.kit.wallet.data.remote.CreateBankVerificationRequest
import com.kit.wallet.data.remote.CreateBankingOperationRequest
import com.kit.wallet.data.remote.ContactDto
import com.kit.wallet.data.remote.ContactSyncRequest
import com.kit.wallet.data.remote.DeviceContactDto
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.remote.CallSessionDto
import com.kit.wallet.data.remote.StartCallRequest
import com.kit.wallet.data.remote.EndCallRequest
import com.kit.wallet.data.remote.ProviderProductDto
import com.kit.wallet.data.session.SessionStore
import com.kit.wallet.di.ApplicationScope
import com.kit.wallet.ui.model.BillProvider
import com.kit.wallet.ui.model.Beneficiary
import com.kit.wallet.ui.model.BankInstitution
import com.kit.wallet.ui.model.BankCapability
import com.kit.wallet.ui.model.BankOperationKind
import com.kit.wallet.ui.model.CallDirection
import com.kit.wallet.ui.model.CallEntry
import com.kit.wallet.ui.model.Contact
import com.kit.wallet.ui.model.Transaction
import com.kit.wallet.ui.model.TxStatus
import com.kit.wallet.ui.model.TxType
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
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
import kotlinx.coroutines.delay

@Singleton
class RemoteContactRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: KitWalletApi,
    private val apiCalls: ApiCallExecutor,
    sessions: SessionStore,
    @ApplicationScope scope: CoroutineScope,
) : ContactRepository {
    private val mutableContacts = MutableStateFlow<List<Contact>>(emptyList())
    override val contacts: StateFlow<List<Contact>> = mutableContacts.asStateFlow()

    init {
        scope.launch {
            sessions.session.map { it?.sessionId }.distinctUntilChanged().collectLatest { sessionId ->
                if (sessionId == null) mutableContacts.value = emptyList()
                else runCatching { refresh() }
            }
        }
    }

    override suspend fun refresh() {
        val deviceNames = deviceContactNames()
        val registered = apiCalls.execute { api.contacts() }.items.orEmpty()
            .map { it.toUiModel(deviceNames) }
        mutableContacts.value = withLocalOnlyDeviceContacts(registered, deviceNames)
    }

    /**
     * WhatsApp-style full address book: device contacts that are not yet known to Kit Pay are
     * appended locally as invitable rows. This is a read-only, on-device merge — nothing is
     * uploaded until the user completes the explicit contact-sync disclosure flow.
     */
    private fun withLocalOnlyDeviceContacts(
        registered: List<Contact>,
        deviceNames: Map<String, String>,
    ): List<Contact> {
        if (deviceNames.isEmpty()) return registered
        val knownNumbers = registered.mapTo(mutableSetOf()) { contactNumberKey(it.phone) }
        val deviceNumbers = deviceContactNumbers()
        val localOnly = deviceNames.mapNotNull { (key, name) ->
            val phone = deviceNumbers[key] ?: return@mapNotNull null
            if (key.isEmpty() || key in knownNumbers) return@mapNotNull null
            Contact(
                id = "device:$key",
                name = name,
                phone = phone,
                isKitUser = false,
                favorite = false,
                status = "",
                receivingWalletId = null,
                registeredName = null,
                savedInDevice = true,
            )
        }
        return registered + localOnly
    }

    /** Best-effort display phone numbers keyed like [deviceContactNames]; empty without permission. */
    private fun deviceContactNumbers(): Map<String, String> {
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return emptyMap()
        }
        val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER)
        return buildMap {
            runCatching {
                context.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    projection,
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    val numberIndex = cursor.getColumnIndexOrThrow(projection[0])
                    while (cursor.moveToNext() && size < MAX_CONTACTS) {
                        val phone = cursor.getString(numberIndex)?.trim().orEmpty()
                        val key = contactNumberKey(phone)
                        if (phone.isNotEmpty() && key.isNotEmpty()) putIfAbsent(key, phone)
                    }
                }
            }
        }
    }

    override suspend fun searchByKitTag(query: String): List<Contact> {
        val tag = query.trim().removePrefix("@").trim()
        if (tag.length < 2) return emptyList()
        return apiCalls.execute {
            api.search(query = tag, types = listOf("users"), limit = 25)
        }.items.orEmpty()
            .filter { it.type == "users" }
            .map { result ->
                Contact(
                    id = result.id,
                    name = result.title?.takeIf(String::isNotBlank) ?: "Kit Pay member",
                    phone = "",
                    isKitUser = true,
                    favorite = false,
                    status = result.subtitle.orEmpty(),
                    receivingWalletId = null,
                    registeredName = result.title,
                    savedInDevice = false,
                )
            }
    }

    override suspend fun syncDeviceContacts() {
        check(
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
                PackageManager.PERMISSION_GRANTED,
        ) { "Contacts permission is required before synchronization" }

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.STARRED,
        )
        val localContacts = buildList {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY + " COLLATE NOCASE ASC",
            )?.use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow(projection[0])
                val numberIndex = cursor.getColumnIndexOrThrow(projection[1])
                val starredIndex = cursor.getColumnIndexOrThrow(projection[2])
                val seen = mutableSetOf<String>()
                while (cursor.moveToNext() && size < MAX_CONTACTS) {
                    val name = cursor.getString(nameIndex)?.trim().orEmpty()
                    val phone = cursor.getString(numberIndex)?.trim().orEmpty()
                    val key = phone.filter(Char::isDigit)
                    if (name.isNotEmpty() && phone.isNotEmpty() && key.isNotEmpty() && seen.add(key)) {
                        add(DeviceContactDto(phone, name, cursor.getInt(starredIndex) == 1))
                    }
                }
            }
        }
        val deviceNames = localContacts.associate { contactNumberKey(it.phone) to it.name }
        val registered = apiCalls.execute {
            api.syncContacts(ContactSyncRequest(localContacts))
        }.items.orEmpty().map { it.toUiModel(deviceNames) }
        mutableContacts.value = withLocalOnlyDeviceContacts(registered, deviceNames)
    }

    /**
     * Best-effort address-book display names keyed by normalized phone number. Returns an empty map
     * when Contacts access has not been granted, so registered names remain the fallback.
     */
    private fun deviceContactNames(): Map<String, String> {
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return emptyMap()
        }
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        return buildMap {
            runCatching {
                context.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    projection,
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    val nameIndex = cursor.getColumnIndexOrThrow(projection[0])
                    val numberIndex = cursor.getColumnIndexOrThrow(projection[1])
                    while (cursor.moveToNext() && size < MAX_CONTACTS) {
                        val name = cursor.getString(nameIndex)?.trim().orEmpty()
                        val key = contactNumberKey(cursor.getString(numberIndex).orEmpty())
                        if (name.isNotEmpty() && key.isNotEmpty()) putIfAbsent(key, name)
                    }
                }
            }
        }
    }

    private fun ContactDto.toUiModel(deviceNames: Map<String, String>): Contact {
        val registeredName = name
        val localName = deviceNames[contactNumberKey(phone)]?.takeIf(String::isNotBlank)
        return Contact(
            id = id,
            name = localName ?: registeredName,
            phone = phone,
            receivingWalletId = receivingWalletId,
            isKitUser = isKitUser == true,
            favorite = favorite == true,
            status = status ?: if (isKitUser == true) "On Kit Pay" else "",
            registeredName = registeredName,
            savedInDevice = localName != null,
        )
    }

    /** Matches address-book and server numbers by their trailing national digits (e.g. +256/0 forms). */
    private fun contactNumberKey(raw: String): String = raw.filter(Char::isDigit).takeLast(9)

    private companion object {
        const val MAX_CONTACTS = 1_000
    }
}

@Singleton
class ProviderCatalogRepository @Inject constructor(
    private val api: KitWalletApi,
    private val apiCalls: ApiCallExecutor,
    sessions: SessionStore,
    @ApplicationScope scope: CoroutineScope,
) : BillsRepository {
    private val products = MutableStateFlow<List<ProviderProductDto>>(emptyList())
    private val mutableProviders = MutableStateFlow<List<BillProvider>>(emptyList())
    override val providers: StateFlow<List<BillProvider>> = mutableProviders.asStateFlow()
    private val mutableAirtimeProducts = MutableStateFlow<List<BillProvider>>(emptyList())
    override val airtimeProducts: StateFlow<List<BillProvider>> = mutableAirtimeProducts.asStateFlow()

    init {
        scope.launch {
            sessions.session.map { it?.sessionId }.distinctUntilChanged().collectLatest { sessionId ->
                if (sessionId == null) {
                    products.value = emptyList()
                    mutableProviders.value = emptyList()
                    mutableAirtimeProducts.value = emptyList()
                } else runCatching { refresh() }
            }
        }
    }

    override suspend fun refresh() {
        products.value = apiCalls.execute { api.providerCatalog() }.items.orEmpty()
        mutableProviders.value = products.value
            .filter { it.serviceType == "bill" }
            .map { product ->
                BillProvider(
                    id = product.id,
                    name = product.name,
                    category = product.category.name,
                    accountHint = accountHint(product.category.code),
                )
            }
        mutableAirtimeProducts.value = products.value
            .filter { it.serviceType == "airtime" }
            .map { product ->
                BillProvider(
                    id = product.id,
                    name = product.name,
                    category = product.category.name,
                    accountHint = "Phone number",
                )
            }
    }

    override fun provider(id: String): BillProvider? = providers.value.find { it.id == id }

    override fun airtimeProduct(id: String): BillProvider? =
        airtimeProducts.value.find { it.id == id }

    fun product(id: String): ProviderProductDto? = products.value.find { it.id == id }

    private fun accountHint(category: String): String = when (category.lowercase()) {
        "electricity", "power", "utilities" -> "Meter or account number"
        "television", "tv" -> "Decoder or smartcard number"
        "water" -> "Customer account number"
        else -> "Account number"
    }
}

@Singleton
class RemoteCallRepository @Inject constructor(
    private val api: KitWalletApi,
    private val apiCalls: ApiCallExecutor,
    sessions: SessionStore,
    @ApplicationScope scope: CoroutineScope,
) : CallRepository {
    private val mutableCalls = MutableStateFlow<List<CallEntry>>(emptyList())
    override val calls: StateFlow<List<CallEntry>> = mutableCalls.asStateFlow()

    init {
        scope.launch {
            sessions.session.map { it?.sessionId }.distinctUntilChanged().collectLatest { sessionId ->
                if (sessionId == null) mutableCalls.value = emptyList()
                else runCatching { refresh() }
            }
        }
    }

    override suspend fun refresh() {
        mutableCalls.value = apiCalls.execute { api.calls() }.items.orEmpty().map { call ->
            CallEntry(
                id = call.id,
                name = call.name.toCallDisplayName(),
                time = formatTime(call.startedAt),
                direction = when (call.direction.lowercase()) {
                    "outgoing" -> CallDirection.OUTGOING
                    "missed" -> CallDirection.MISSED
                    else -> CallDirection.INCOMING
                },
                video = call.video == true || call.type == "video",
                participantUserIds = call.participantUserIds.orEmpty(),
            )
        }
    }

    override suspend fun incoming(callId: String): IncomingCallDetails {
        val call = apiCalls.execute { api.call(callId) }
        check(call.id == callId) { "The call lookup returned an unexpected call" }
        return IncomingCallDetails(
            callId = call.id,
            name = call.name.toCallDisplayName(),
            video = call.video == true || call.type == "video",
            direction = call.direction,
            state = call.state,
            ringExpiresAt = call.ringExpiresAt,
        ).requireAnswerable()
    }

    override suspend fun start(
        recipientUserId: String,
        video: Boolean,
        conversationId: String?,
    ): CallConnection {
        require(recipientUserId.isNotBlank()) { "Choose a Kit Pay contact to call" }
        val session = apiCalls.execute {
            api.startCall(
                StartCallRequest(
                    recipientUserIds = listOf(recipientUserId),
                    type = if (video) "video" else "voice",
                    conversationId = conversationId,
                ),
            )
        }
        refresh()
        return session.toConnection()
    }

    override suspend fun accept(callId: String): CallConnection {
        val session = apiCalls.execute { api.acceptCall(callId) }
        refresh()
        return session.toConnection()
    }

    override suspend fun decline(callId: String) {
        apiCalls.execute { api.declineCall(callId) }
        refresh()
    }

    override suspend fun end(callId: String, reason: String) {
        apiCalls.execute { api.endCall(callId, EndCallRequest(reason)) }
        refresh()
    }

    private fun CallSessionDto.toConnection(): CallConnection {
        check(rtc.provider.equals("livekit", ignoreCase = true)) {
            "This version of Kit Pay cannot use the configured call provider"
        }
        check(rtc.url.startsWith("wss://")) { "The call server did not provide a secure WebSocket URL" }
        check(rtc.token.isNotBlank()) { "The call server did not provide a room token" }
        return CallConnection(
            callId = call.id,
            name = call.name.toCallDisplayName(),
            video = call.video == true || call.type == "video",
            provider = rtc.provider,
            url = rtc.url,
            token = rtc.token,
            room = rtc.room,
        )
    }

    private fun formatTime(value: String): String = runCatching {
        Instant.parse(value).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))
    }.getOrDefault(value)
}

@Singleton
class RemoteBankingRepository @Inject constructor(
    private val api: KitWalletApi,
    private val apiCalls: ApiCallExecutor,
    private val walletCache: WalletCache,
    private val paymentAuthorizer: PaymentAuthorizer,
    private val sessions: SessionStore,
    @ApplicationScope scope: CoroutineScope,
) : BankingRepository {
    private val mutableBanks = MutableStateFlow<List<BankInstitution>>(emptyList())
    override val banks: StateFlow<List<BankInstitution>> = mutableBanks.asStateFlow()

    private val mutableBeneficiaries = MutableStateFlow<List<Beneficiary>>(emptyList())
    override val beneficiaries: StateFlow<List<Beneficiary>> = mutableBeneficiaries.asStateFlow()

    private val mutableOperations = MutableStateFlow<List<Transaction>>(emptyList())
    override val operations: StateFlow<List<Transaction>> = mutableOperations.asStateFlow()

    init {
        scope.launch {
            sessions.session.map { it?.cacheScopeId }.distinctUntilChanged().collectLatest { owner ->
                mutableBanks.value = emptyList()
                mutableBeneficiaries.value = emptyList()
                mutableOperations.value = emptyList()
                if (owner != null) runCatching { refresh() }
            }
        }
    }

    override suspend fun refresh() {
        val active = sessions.current() ?: return
        val fence = active.fence()
        val bankItems = apiCalls.execute { api.banks() }.items.orEmpty()
        val bankIds = bankItems.mapTo(mutableSetOf()) { it.id }
        val beneficiaries = apiCalls.execute { api.bankBeneficiaries() }.items
            .filter { it.bank.id in bankIds }
        val mappedBanks = bankItems.map { it.toBankInstitution() }
        val mappedBeneficiaries = beneficiaries.map { beneficiary ->
            Beneficiary(
                id = beneficiary.id,
                name = beneficiary.accountName?.takeIf(String::isNotBlank) ?: beneficiary.label,
                bank = beneficiary.bank.name,
                accountMasked = beneficiary.accountNumberMasked,
                verified = beneficiary.status == "active",
                kind = beneficiary.kind,
                bankId = beneficiary.bank.id,
            )
        }
        val beneficiaryNames = beneficiaries.associate { it.id to it.label }
        val mappedOperations = apiCalls.execute { api.bankingOperations() }.items
            .filter { it.bankId in bankIds }
            .map { operation ->
                val scale = operation.currency.scale.toInt()
                val amountMinor = DecimalMoney.toMinor(operation.amount, scale)
                val incoming = operation.direction.lowercase() in
                    setOf("credit", "incoming", "in") || operation.type == "deposit"
                Transaction(
                    id = operation.id,
                    counterparty = operation.beneficiaryId
                        ?.let(beneficiaryNames::get)
                        ?: "Bank transfer",
                    note = null,
                    amountMinor = if (incoming) amountMinor else -amountMinor,
                    time = operation.createdAt?.let(::formatBankingTime) ?: "Pending",
                    dateGroup = "Banking",
                    type = if (incoming) TxType.BANK_IN else TxType.BANK_OUT,
                    status = when (operation.status) {
                        "completed", "succeeded" -> TxStatus.COMPLETED
                        "failed", "reversed" -> TxStatus.FAILED
                        else -> TxStatus.PENDING
                    },
                    reference = operation.reference,
                )
            }
        sessions.withCurrentSession(fence) {
            mutableBanks.value = mappedBanks
            mutableBeneficiaries.value = mappedBeneficiaries
            mutableOperations.value = mappedOperations
        }
    }

    override suspend fun addBeneficiary(
        bankId: String,
        accountNumber: String,
        label: String,
        kind: String,
    ) {
        require(accountNumber.isNotBlank()) { "Enter the bank account number" }
        require(label.isNotBlank()) { "Enter a name for this account" }
        val bank = mutableBanks.value.firstOrNull { it.id == bankId }
        require(bank?.supports(BankCapability.ACCOUNT_VERIFICATION) == true) {
            "Account verification is unavailable for this bank"
        }
        val key = "android-bank-verify-${java.util.UUID.randomUUID()}"
        var verification = apiCalls.execute {
            api.createBankVerification(key, CreateBankVerificationRequest(bankId, accountNumber))
        }
        repeat(VERIFICATION_POLLS) {
            if (verification.status != "pending") return@repeat
            delay(VERIFICATION_POLL_MILLIS)
            verification = apiCalls.execute { api.bankVerification(verification.id) }
        }
        check(verification.status == "verified") {
            "The bank account is still being verified. Try again shortly."
        }
        apiCalls.execute {
            api.createBankBeneficiary(
                "android-bank-beneficiary-${java.util.UUID.randomUUID()}",
                CreateBankBeneficiaryRequest(verification.id, kind, label),
            )
        }
        refresh()
    }

    override suspend fun createOperation(
        type: String,
        beneficiaryId: String,
        amountMinor: Long,
        paymentPin: String,
    ) {
        val operation = requireNotNull(BankOperationKind.fromApiType(type)) {
            "Unsupported bank operation"
        }
        val beneficiary = mutableBeneficiaries.value.firstOrNull { it.id == beneficiaryId }
        require(beneficiary?.verified == true) { "Select a verified bank beneficiary" }
        if (operation.requiresOwnAccount) {
            require(beneficiary.kind == "own") { "Select a verified account that belongs to you" }
        }
        val bank = beneficiary.bankId?.let { bankId ->
            mutableBanks.value.firstOrNull { it.id == bankId }
        }
        require(bank?.supports(operation.capability) == true) {
            "This bank does not support the selected operation"
        }
        require(amountMinor > 0) { "Enter a positive amount" }
        val active = requireNotNull(sessions.current()) { "Sign in again to access this wallet" }
        val wallet = sessions.withCurrentSession(active.fence()) { current ->
            requireNotNull(walletCache.selectedWallet(current.cacheScopeId)) {
                "No active wallet is selected"
            }
        }
        val amount = DecimalMoney.fromMinor(amountMinor, wallet.currencyScale)
        val intent = linkedMapOf<String, Any?>(
            "operation_type" to operation.apiType,
            "wallet_id" to wallet.uuid,
            "beneficiary_id" to beneficiaryId,
            "amount" to amount,
        )
        val token = paymentAuthorizer.authorize("bank_transfer", intent, paymentPin)
        val request = CreateBankingOperationRequest(wallet.uuid, beneficiaryId, amount)
        val key = "android-bank-operation-${java.util.UUID.randomUUID()}"
        apiCalls.execute {
            when (operation) {
                BankOperationKind.DEPOSIT -> api.createBankDeposit(key, token, request)
                BankOperationKind.WITHDRAWAL -> api.createBankWithdrawal(key, token, request)
                BankOperationKind.TRANSFER -> api.createBankTransfer(key, token, request)
            }
        }
        refresh()
    }

    private fun formatBankingTime(value: String): String = runCatching {
        Instant.parse(value).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))
    }.getOrDefault(value)

    private companion object {
        const val VERIFICATION_POLLS = 10
        const val VERIFICATION_POLL_MILLIS = 750L
    }
}

internal fun String?.toCallDisplayName(): String =
    this?.trim()?.takeIf(String::isNotBlank) ?: "Kit Pay contact"
