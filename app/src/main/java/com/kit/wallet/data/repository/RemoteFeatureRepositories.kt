package com.kit.wallet.data.repository

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.kit.wallet.data.mapper.toBankInstitution
import com.kit.wallet.data.contacts.ContactDiscoveryAuthorization
import com.kit.wallet.data.contacts.ContactDiscoveryConsent
import com.kit.wallet.data.remote.ApiCallExecutor
import com.kit.wallet.data.mapper.DecimalMoney
import com.kit.wallet.data.local.WalletCache
import com.kit.wallet.data.remote.CreateBankBeneficiaryRequest
import com.kit.wallet.data.remote.CreateBankVerificationRequest
import com.kit.wallet.data.remote.CreateBankingOperationRequest
import com.kit.wallet.data.remote.CreateBankingOutboundQuoteRequest
import com.kit.wallet.data.remote.CreateQuotedBankingOperationRequest
import com.kit.wallet.data.remote.BankingOutboundQuoteDto
import com.kit.wallet.data.remote.ContactDto
import com.kit.wallet.data.remote.ContactSyncRequest
import com.kit.wallet.data.remote.BeginContactSyncRequest
import com.kit.wallet.data.remote.ContactSyncSessionDto
import com.kit.wallet.data.remote.DeviceContactDto
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.remote.KitWalletApiException
import com.kit.wallet.data.remote.CallSessionDto
import com.kit.wallet.data.remote.StartCallRequest
import com.kit.wallet.data.remote.EndCallRequest
import com.kit.wallet.data.remote.ProviderProductDto
import com.kit.wallet.data.session.SessionStore
import com.kit.wallet.data.session.SessionFence
import com.kit.wallet.data.session.SessionInvalidatedException
import com.kit.wallet.di.ApplicationScope
import com.kit.wallet.ui.model.BillProvider
import com.kit.wallet.ui.model.Beneficiary
import com.kit.wallet.ui.model.BankInstitution
import com.kit.wallet.ui.model.BankCapability
import com.kit.wallet.ui.model.BankOperationKind
import com.kit.wallet.ui.model.CallDirection
import com.kit.wallet.ui.model.CallEntry
import com.kit.wallet.ui.model.AccountVerification
import com.kit.wallet.ui.model.Contact
import com.kit.wallet.ui.model.Transaction
import com.kit.wallet.ui.model.TxStatus
import com.kit.wallet.ui.model.TxType
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

private const val MAX_CONTACTS = 50_000
private const val LEGACY_CONTACT_SYNC_LIMIT = 1_000
private const val CONTACT_PAGE_LIMIT = 500
private const val MAX_CONTACT_PAGES = MAX_CONTACTS / CONTACT_PAGE_LIMIT
private const val MAX_CONTACT_PHONE_BYTES = 32
private const val MIN_CONTACT_PHONE_DIGITS = 7
private const val MAX_CONTACT_PHONE_DIGITS = 15
private const val MAX_CONTACT_NAME_CODE_POINTS = 160

internal data class DeviceContactSyncCandidate(
    val phone: String?,
    val name: String?,
    val favorite: Boolean,
)

/** Maps the server-owned contact identity without deriving a badge from any presentation field. */
internal fun ContactDto.toContactModel(localName: String? = null): Contact {
    val savedName = localName?.takeIf(String::isNotBlank)
    return Contact(
        id = id,
        name = savedName ?: name,
        phone = phone,
        receivingWalletId = receivingWalletId,
        isKitUser = isKitUser == true,
        favorite = favorite == true,
        status = status ?: if (isKitUser == true) "On Kit Pay" else "",
        registeredName = name,
        savedInDevice = savedName != null,
        avatarUrl = avatarUrl?.takeIf(String::isNotBlank),
        accountVerification = if (isKitUser == true) {
            AccountVerification.fromServerValues(
                designation = verification?.designation,
                since = verification?.since,
            )
        } else {
            null
        },
    )
}

/**
 * Converts an address-book snapshot into the bounded, phone-only shape accepted by contact sync.
 * Invalid rows are omitted individually so a service code or malformed OEM contact cannot prevent
 * later valid contacts from being uploaded. Phone digits are never truncated: an overlong value is
 * rejected instead of being turned into a different person's number.
 */
internal fun sanitizeDeviceContactsForSync(
    candidates: Sequence<DeviceContactSyncCandidate>,
    limit: Int = MAX_CONTACTS,
): List<DeviceContactDto> {
    require(limit >= 0) { "Contact limit cannot be negative" }
    val effectiveLimit = limit.coerceAtMost(MAX_CONTACTS)
    if (effectiveLimit == 0) return emptyList()

    val seenNumbers = mutableSetOf<String>()
    val sanitizedContacts = ArrayList<DeviceContactDto>(effectiveLimit)
    for (candidate in candidates) {
        val phone = sanitizeDeviceContactPhone(candidate.phone) ?: continue
        val numberKey = phone.filter { it in '0'..'9' }
        if (!seenNumbers.add(numberKey)) continue

        sanitizedContacts.add(
            DeviceContactDto(
                phone = phone,
                name = sanitizeDeviceContactName(candidate.name, fallback = phone),
                favorite = candidate.favorite,
            ),
        )
        if (sanitizedContacts.size == effectiveLimit) break
    }
    return sanitizedContacts
}

internal fun validateContactSync(
    sync: ContactSyncSessionDto,
    clientSyncId: String,
    contactCount: Int,
    expectedStatus: String,
) {
    check(runCatching { UUID.fromString(sync.id) }.isSuccess) {
        "The contact sync server returned an invalid session identifier"
    }
    check(sync.clientSyncId.equals(clientSyncId, ignoreCase = true)) {
        "The contact sync server returned a different client identifier"
    }
    check(sync.snapshotScope == "full" && sync.generation > 0 && sync.status == expectedStatus) {
        "The contact sync server returned an invalid session state"
    }
    check(sync.chunkSize in 1..CONTACT_PAGE_LIMIT && sync.totalContactCount == contactCount) {
        "The contact sync server returned invalid snapshot bounds"
    }
    val expectedChunks = (contactCount + sync.chunkSize - 1) / sync.chunkSize
    check(sync.totalChunkCount == expectedChunks) {
        "The contact sync server returned an invalid chunk count"
    }
    check(sync.receivedContactCount in 0..contactCount) {
        "The contact sync server returned an invalid received count"
    }
    check(sync.receivedChunkCount in 0..sync.totalChunkCount) {
        "The contact sync server returned an invalid received chunk count"
    }
    check(sync.acceptedContactCount in 0..sync.receivedContactCount) {
        "The contact sync server returned an invalid accepted contact count"
    }
    check(sync.missingChunkIndexes.all { it in 0 until sync.totalChunkCount }) {
        "The contact sync server returned invalid missing chunks"
    }
}

/** Compatibility name retained for contact-sync callers and tests. */
internal fun normalizedContactPhone(rawPhone: String?): String? = canonicalContactPhone(rawPhone)

private fun sanitizeDeviceContactPhone(rawPhone: String?): String? {
    val raw = rawPhone?.trim().orEmpty()
    if (raw.isEmpty() || raw.toByteArray(Charsets.UTF_8).size > MAX_CONTACT_PHONE_BYTES) {
        return null
    }

    val sanitized = normalizedContactPhone(raw) ?: return null
    val digits = sanitized.removePrefix("+")
    if (digits.length !in MIN_CONTACT_PHONE_DIGITS..MAX_CONTACT_PHONE_DIGITS) return null
    if (digits.all { it == '0' }) return null
    return sanitized
}

private fun sanitizeDeviceContactName(rawName: String?, fallback: String): String {
    val trimmed = rawName?.trim().orEmpty()
    if (trimmed.isEmpty()) return fallback
    val codePointCount = trimmed.codePointCount(0, trimmed.length)
    if (codePointCount <= MAX_CONTACT_NAME_CODE_POINTS) return trimmed
    val endIndex = trimmed.offsetByCodePoints(0, MAX_CONTACT_NAME_CODE_POINTS)
    return trimmed.substring(0, endIndex).trimEnd().ifEmpty { fallback }
}

@Singleton
class RemoteContactRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: KitWalletApi,
    private val apiCalls: ApiCallExecutor,
    private val sessions: SessionStore,
    @ApplicationScope scope: CoroutineScope,
    private val profilePhotos: ProfilePhotoDirectory? = null,
    private val discovery: ContactDiscoveryConsent? = null,
) : ContactRepository {
    private val mutableContacts = MutableStateFlow<List<Contact>>(emptyList())
    override val contacts: StateFlow<List<Contact>> = mutableContacts.asStateFlow()

    init {
        scope.launch {
            sessions.session.map { it?.fence() }.distinctUntilChanged().collectLatest { fence ->
                if (fence == null) mutableContacts.value = emptyList()
                else runCatching { refresh() }
            }
        }
    }

    override suspend fun refresh() {
        val active = sessions.current() ?: run {
            mutableContacts.value = emptyList()
            return
        }
        val fence = active.fence()
        val authorization = discovery?.authorizationFor(fence)
        // Two separate answers are needed before an address book leaves this phone: the Android
        // permission, and the person's own standing choice to be findable from other people's
        // contacts. With both, keep the server contact graph current on login and contacts-screen
        // entry so a newly saved Kit member is not left as a permanently local, invite-only row.
        // With only the permission, the address book is still read — names on this screen come
        // from it — but nothing is uploaded.
        if (contactDiscoveryUploadAllowed(hasContactPermission(), authorization)) {
            syncDeviceContacts(fence, requireNotNull(authorization))
            return
        }
        val deviceNames = deviceContactNames()
        val registered = loadAllContacts(fence)
            .map { it.toUiModel(deviceNames) }
        publish(fence, registered, deviceNames)
    }

    /**
     * Publishes a freshly loaded address book, and reconciles the remembered photos against it.
     *
     * The contact list carries `avatar_url` for every Kit member this account knows, which makes it
     * the whole story: a member listed without one has taken theirs down, and the remembered row
     * goes with it rather than resurrecting a face its owner removed. Screens that have no contact
     * list to consult — a chat list on a cold start, a search result — read the directory instead.
     */
    private suspend fun publish(
        fence: SessionFence,
        registered: List<Contact>,
        deviceNames: Map<String, String>,
    ) {
        val seen = registered.asSequence()
            .filter { it.isKitUser && it.id.isNotBlank() }
            .associate { it.id to it.avatarUrl }
        sessions.withCurrentSession(fence) {
            profilePhotos?.learn(fence, seen, complete = true)
            mutableContacts.value = withLocalOnlyDeviceContacts(registered, deviceNames)
        }
    }

    override suspend fun resolveForMessaging(contact: Contact): Contact? {
        if (contact.isKitUser) return contact
        refresh()
        val key = contactNumberKey(contact.phone)
        return mutableContacts.value.singleOrNull { candidate ->
            candidate.isKitUser && contactNumberKey(candidate.phone) == key
        }
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
        val knownById = mutableContacts.value
            .filter { it.isKitUser && it.id.isNotBlank() }
            .associateBy { it.id.lowercase() }
        return apiCalls.execute {
            api.search(query = tag, types = listOf("users"), limit = 25)
        }.items.orEmpty()
            .filter { it.type == "users" }
            .map { result ->
                val known = knownById[result.id.lowercase()]
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
                    // Search results carry no photo of their own, but a member who is already in
                    // this account's address book has one on the device: show it rather than
                    // showing the same person as initials here and as a face one screen over.
                    avatarUrl = known?.avatarUrl ?: profilePhotos?.photoFor(result.id),
                    // Global search does not yet carry designation metadata. Reuse it only when
                    // this exact public ID is already in the authenticated contact directory;
                    // never infer a seal from the result title, subtitle or username query.
                    accountVerification = known?.accountVerification,
                )
            }
    }

    override suspend fun syncDeviceContacts() {
        val active = requireNotNull(sessions.current()) {
            "Sign in again before synchronizing contacts"
        }
        val fence = active.fence()
        val authorization = discovery?.authorizationFor(fence)
            ?: error("Contact discovery consent is unavailable or turned off")
        syncDeviceContacts(fence, authorization)
    }

    private suspend fun syncDeviceContacts(
        fence: SessionFence,
        authorization: ContactDiscoveryAuthorization,
    ) {
        requireUploadAuthorized(authorization)

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.STARRED,
        )
        val localContacts = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY + " COLLATE NOCASE ASC",
        )?.use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow(projection[0])
            val numberIndex = cursor.getColumnIndexOrThrow(projection[1])
            val starredIndex = cursor.getColumnIndexOrThrow(projection[2])
            val candidates: Sequence<DeviceContactSyncCandidate> = generateSequence {
                if (!cursor.moveToNext()) {
                    null
                } else {
                    DeviceContactSyncCandidate(
                        phone = cursor.getString(numberIndex),
                        name = cursor.getString(nameIndex),
                        favorite = cursor.getInt(starredIndex) == 1,
                    )
                }
            }
            sanitizeDeviceContactsForSync(candidates)
        }.orEmpty()
        requireUploadAuthorized(authorization)
        val deviceNames = localContacts.associate { contactNumberKey(it.phone) to it.name }
        val registered = synchronizeContacts(localContacts, fence, authorization)
            .map { it.toUiModel(deviceNames) }
        requireUploadAuthorized(authorization)
        publish(fence, registered, deviceNames)
    }

    private suspend fun synchronizeContacts(
        localContacts: List<DeviceContactDto>,
        fence: SessionFence,
        authorization: ContactDiscoveryAuthorization,
    ): List<ContactDto> {
        val clientSyncId = UUID.randomUUID().toString().lowercase()
        val opened = try {
            requireUploadAuthorized(authorization)
            apiCalls.execute {
                api.startContactSync(
                    BeginContactSyncRequest(clientSyncId, localContacts.size, "full"),
                )
            }.sync
        } catch (error: KitWalletApiException) {
            if (error.statusCode != 404) throw error
            check(localContacts.size <= LEGACY_CONTACT_SYNC_LIMIT) {
                "This Kit Pay service cannot safely sync more than $LEGACY_CONTACT_SYNC_LIMIT contacts yet"
            }
            requireUploadAuthorized(authorization)
            val legacy = apiCalls.execute {
                api.syncContacts(ContactSyncRequest(localContacts))
            }.items.orEmpty()
            requireUploadAuthorized(authorization)
            return legacy
        }
        requireUploadAuthorized(authorization)
        validateContactSync(opened, clientSyncId, localContacts.size, expectedStatus = "open")
        localContacts.chunked(opened.chunkSize).forEachIndexed { index, chunk ->
            requireUploadAuthorized(authorization)
            val uploaded = apiCalls.execute {
                api.uploadContactSyncChunk(opened.id, index, ContactSyncRequest(chunk))
            }
            requireUploadAuthorized(authorization)
            validateContactSync(uploaded.sync, clientSyncId, localContacts.size, "open")
            check(uploaded.chunk.index == index && uploaded.chunk.inputCount == chunk.size) {
                "The contact sync server returned an invalid chunk receipt"
            }
            check(uploaded.chunk.acceptedCount in 0..uploaded.chunk.inputCount) {
                "The contact sync server returned an invalid accepted count"
            }
        }
        requireUploadAuthorized(authorization)
        val finalized = apiCalls.execute { api.finalizeContactSync(opened.id) }.sync
        requireUploadAuthorized(authorization)
        validateContactSync(finalized, clientSyncId, localContacts.size, "finalized")
        check(finalized.storedContactCount != null && finalized.missingChunkIndexes.isEmpty()) {
            "The contact sync server did not finalize the complete address book"
        }
        return loadAllContacts(fence, authorization)
    }

    private suspend fun loadAllContacts(
        fence: SessionFence,
        authorization: ContactDiscoveryAuthorization? = null,
    ): List<ContactDto> {
        val contacts = ArrayList<ContactDto>()
        val seenCursors = mutableSetOf<String>()
        var cursor: String? = null
        repeat(MAX_CONTACT_PAGES) {
            requireCurrent(fence, authorization)
            val response = apiCalls.execute { api.contacts(cursor, CONTACT_PAGE_LIMIT) }
            requireCurrent(fence, authorization)
            val items = response.items.orEmpty()
            check(items.size <= CONTACT_PAGE_LIMIT && contacts.size + items.size <= MAX_CONTACTS) {
                "The contact service returned too many contacts"
            }
            contacts += items
            val page = response.page ?: return contacts
            val hasMore = page.hasMore == true
            if (!hasMore) return contacts
            val next = page.nextCursor?.trim().orEmpty()
            check(next.isNotEmpty() && next.length <= 2_048 && seenCursors.add(next)) {
                "The contact service returned an invalid continuation"
            }
            cursor = next
        }
        error("The contact service exceeded the supported pagination bound")
    }

    /**
     * Best-effort address-book display names keyed by normalized phone number. Returns an empty map
     * when Contacts access has not been granted, so registered names remain the fallback.
     */
    private fun deviceContactNames(): Map<String, String> {
        if (!hasContactPermission()) {
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
        val localName = deviceNames[contactNumberKey(phone)]?.takeIf(String::isNotBlank)
        return toContactModel(localName)
    }

    /** Matches Uganda local/international forms without conflating foreign numbers by suffix. */
    private fun contactNumberKey(raw: String): String =
        normalizedContactPhone(raw) ?: "raw:${raw.filter(Char::isDigit)}"

    private fun hasContactPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    private fun requireUploadAuthorized(authorization: ContactDiscoveryAuthorization) {
        check(
            contactDiscoveryUploadAllowed(
                permissionGranted = hasContactPermission(),
                authorization = authorization.takeIf { discovery?.isAuthorized(it) == true },
            ),
        ) { "Contact permission or account-scoped upload consent changed" }
    }

    private fun requireCurrent(
        fence: SessionFence,
        authorization: ContactDiscoveryAuthorization?,
    ) {
        check(sessions.current()?.fence() == fence) {
            "The authenticated contact session changed"
        }
        if (authorization != null) requireUploadAuthorized(authorization)
    }
}

/** Missing consent and revoked Android permission are both a closed upload gate. */
internal fun contactDiscoveryUploadAllowed(
    permissionGranted: Boolean,
    authorization: ContactDiscoveryAuthorization?,
): Boolean = permissionGranted && authorization != null

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
    private val contacts: ContactRepository,
    private val sessions: SessionStore,
    @ApplicationScope private val scope: CoroutineScope,
    private val profilePhotos: ProfilePhotoDirectory? = null,
) : CallRepository {
    private val mutableCalls = MutableStateFlow<List<CallEntry>>(emptyList())
    override val calls: StateFlow<List<CallEntry>> = mutableCalls.asStateFlow()
    private val historyRefresh = AtomicReference<Job?>(null)
    private val historyGate = CallHistoryRefreshGate()

    init {
        scope.launch {
            sessions.session.map { it?.sessionId }.distinctUntilChanged().collectLatest { sessionId ->
                if (sessionId == null) mutableCalls.value = emptyList()
                else runCatching { refresh() }
            }
        }
    }

    override suspend fun refresh() {
        refreshContactPresentation()
        refreshCallList()
    }

    /**
     * Brings the call log up to date without making anyone wait for it.
     *
     * The log is paginated to exhaustion, so on an account with real history it is several
     * round trips. Answering and placing calls used to await that before handing back the
     * room credentials, which put the whole history walk between the tap and the first
     * audio packet. Nothing on the call screen reads this list, so it runs on the
     * application scope instead, cancel-and-replace so a redial does not stack walks.
     */
    private fun refreshHistoryInBackground() {
        // The generation is claimed here, on the thread that asked for the refresh, rather
        // than inside the coroutine. Two background walks dispatched at once could otherwise
        // begin in the opposite order to the calls that requested them, handing the older
        // walk the newer token — the exact inversion the gate exists to prevent.
        val token = historyGate.begin()
        historyRefresh.getAndSet(scope.launch { runCatching { refreshCallList(token) } })?.cancel()
    }

    /**
     * [token] defaults to a generation claimed at the call site, which is what every direct
     * caller wants; the background walk claims its own before launching and passes it in.
     */
    private suspend fun refreshCallList(token: Long = historyGate.begin()) {
        val active = sessions.current() ?: return
        val fence = active.fence()
        val pages = CallHistoryPageAccumulator()
        var complete = false
        while (!complete) {
            val page = apiCalls.execute {
                api.calls(
                    cursor = pages.nextCursor,
                    limit = CallHistoryPageAccumulator.PAGE_LIMIT,
                )
            }
            complete = pages.append(page)
        }
        val learnedPhotos = mutableMapOf<String, String?>()
        val mapped = pages.calls.map { call ->
            val startedAt = runCatching { Instant.parse(call.startedAt) }.getOrNull()
            val answeredAt = call.answeredAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
            val endedAt = call.endedAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
            val durationSeconds = if (answeredAt != null && endedAt != null) {
                java.time.Duration.between(answeredAt, endedAt).seconds.coerceAtLeast(0)
            } else {
                0
            }
            val participants = call.toCallParticipantIdentities()
            participants.forEach { participant ->
                participant.avatarUrl?.let { learnedPhotos[participant.userId] = it }
            }
            val participantIds = participants.map(CallParticipantIdentity::userId)
            val presentation = resolveCallPresentation(
                serverName = call.name,
                participantUserIds = participantIds,
                contacts = contacts.contacts.value,
                participants = participants,
            )
            CallEntry(
                id = call.id,
                name = presentation.name,
                time = formatTime(call.startedAt),
                direction = when (call.direction.lowercase()) {
                    "outgoing" -> CallDirection.OUTGOING
                    "missed" -> CallDirection.MISSED
                    else -> CallDirection.INCOMING
                },
                video = call.video == true || call.type == "video",
                participantUserIds = participantIds,
                conversationId = call.conversationId,
                startedAtEpochMillis = startedAt?.toEpochMilli() ?: 0,
                durationSeconds = durationSeconds,
                answered = answeredAt != null,
                avatarUrl = presentation.avatarUrl,
                accountVerification = presentation.accountVerification,
            )
        }
        if (!historyGate.admits(token)) return
        sessions.withCurrentSession(fence) {
            mutableCalls.value = mapped
            profilePhotos?.learn(fence, learnedPhotos, complete = false)
        }
    }

    override suspend fun incoming(callId: String): IncomingCallDetails {
        val fence = sessions.current()?.fence() ?: throw SessionInvalidatedException()
        val call = apiCalls.execute { api.call(callId) }
        check(call.id == callId) { "The call lookup returned an unexpected call" }
        val participants = call.toCallParticipantIdentities()
        val participantIds = participants.map(CallParticipantIdentity::userId)
        val presentation = resolveCallPresentation(
            call.name,
            participantIds,
            contacts.contacts.value,
            participants,
        )
        return sessions.withCurrentSession(fence) {
            rememberParticipantPhotos(fence, participants)
            IncomingCallDetails(
                callId = call.id,
                name = presentation.name,
                phone = presentation.phone,
                participantUserIds = participantIds,
                participants = participants,
                avatarUrl = presentation.avatarUrl,
                accountVerification = presentation.accountVerification,
                video = call.video == true || call.type == "video",
                direction = call.direction,
                state = call.state,
                ringExpiresAt = call.ringExpiresAt,
            ).requireAnswerable()
        }
    }

    override suspend fun start(
        recipientUserId: String,
        video: Boolean,
        conversationId: String?,
    ): CallConnection = start(
        recipientUserId = recipientUserId,
        video = video,
        conversationId = conversationId,
        clientCallId = UUID.randomUUID().toString(),
    )

    override suspend fun start(
        recipientUserId: String,
        video: Boolean,
        conversationId: String?,
        clientCallId: String,
    ): CallConnection {
        val fence = sessions.current()?.fence() ?: throw SessionInvalidatedException()
        require(recipientUserId.isNotBlank()) { "Choose a Kit Pay contact to call" }
        require(runCatching { UUID.fromString(clientCallId) }.isSuccess) {
            "The call attempt identifier is invalid"
        }
        val session = apiCalls.execute {
            api.startCall(
                StartCallRequest(
                    recipientUserIds = listOf(recipientUserId),
                    type = if (video) "video" else "voice",
                    conversationId = conversationId,
                    clientCallId = clientCallId.lowercase(),
                ),
            )
        }
        // The recipient came from the already-loaded contact graph. Refreshing contacts here
        // uploads the entire address book through the separately throttled /contacts/sync route
        // for every redial, even though starting calls themselves is intentionally unthrottled.
        // Reuse the current presentation and leave explicit contact/history refreshes responsible
        // for discovering address-book changes.
        refreshHistoryInBackground()
        return sessions.withCurrentSession(fence) {
            session.toConnection(listOf(recipientUserId), fence)
        }
    }

    override suspend fun invite(callId: String, recipientUserIds: List<String>) {
        require(recipientUserIds.isNotEmpty()) { "Choose at least one Kit Pay contact to add" }
        apiCalls.execute {
            api.inviteToCall(
                callId,
                com.kit.wallet.data.remote.InviteCallRequest(recipientUserIds),
            )
        }
        refreshCallList()
    }

    override suspend fun cancelAttempt(clientCallId: String) {
        val canonical = runCatching { UUID.fromString(clientCallId).toString() }.getOrNull()
            ?: error("The call attempt identifier is invalid")
        val result = apiCalls.execute { api.cancelCallAttempt(canonical) }
        check(result.cancelled && result.clientCallId.equals(canonical, ignoreCase = true)) {
            "The call attempt was not cancelled"
        }
    }

    override suspend fun accept(callId: String): CallConnection {
        val fence = sessions.current()?.fence() ?: throw SessionInvalidatedException()
        val session = apiCalls.execute { api.acceptCall(callId) }
        check(session.call.id == callId) {
            "The call answer returned credentials for an unexpected call"
        }
        refreshHistoryInBackground()
        return sessions.withCurrentSession(fence) { session.toConnection(owner = fence) }
    }

    override suspend fun decline(callId: String) {
        apiCalls.execute { api.declineCall(callId) }
        runCatching { refreshCallList() }
    }

    override suspend fun end(callId: String, reason: String) {
        apiCalls.execute { api.endCall(callId, EndCallRequest(reason)) }
        runCatching { refreshCallList() }
    }

    private fun CallSessionDto.toConnection(
        recipientHints: List<String> = emptyList(),
        owner: SessionFence,
    ): CallConnection {
        check(rtc.provider.equals("livekit", ignoreCase = true)) {
            "This version of Kit Pay cannot use the configured call provider"
        }
        check(rtc.url.startsWith("wss://")) { "The call server did not provide a secure WebSocket URL" }
        check(rtc.token.isNotBlank()) { "The call server did not provide a room token" }
        val participants = call.toCallParticipantIdentities(recipientHints)
        val participantIds = participants.map(CallParticipantIdentity::userId)
        val presentation = resolveCallPresentation(
            call.name,
            participantIds,
            contacts.contacts.value,
            participants,
        )
        rememberParticipantPhotos(owner, participants)
        return CallConnection(
            callId = call.id,
            name = presentation.name,
            phone = presentation.phone,
            participantUserIds = participantIds,
            participants = participants,
            avatarUrl = presentation.avatarUrl,
            accountVerification = presentation.accountVerification,
            video = call.video == true || call.type == "video",
            provider = rtc.provider,
            url = rtc.url,
            token = rtc.token,
            room = rtc.room,
            ringExpiresAt = call.ringExpiresAt,
            answeredAt = call.answeredAt,
            serverTime = serverTime,
            conversationId = call.conversationId?.trim()?.takeIf(String::isNotEmpty),
        )
    }

    private fun rememberParticipantPhotos(
        owner: SessionFence,
        participants: List<CallParticipantIdentity>,
    ) {
        val known = participants.mapNotNull { participant ->
            participant.avatarUrl?.let { participant.userId to it }
        }.toMap()
        if (known.isNotEmpty()) profilePhotos?.learn(owner, known, complete = false)
    }

    /** Refresh address-book presentation only for an explicit call-history refresh, not call I/O. */
    private suspend fun refreshContactPresentation() {
        runCatching { contacts.refresh() }
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
                // A bank account number is nothing a phone's address book can be matched against,
                // so the server saying who owns it is the only identity this row can ever carry.
                kitUserId = beneficiary.kitUser?.id?.trim()?.takeIf(String::isNotEmpty),
                avatarUrl = beneficiary.kitUser?.avatarUrl?.trim()?.takeIf(String::isNotEmpty),
            )
        }
        val beneficiaryNames = beneficiaries.associate { it.id to it.label }
        val mappedOperations = apiCalls.execute { api.bankingOperations() }.items
            .filter { it.bankId in bankIds }
            .map { operation ->
                val scale = operation.currency.scale.toInt()
                val amountMinor = DecimalMoney.toMinor(operation.amount, scale)
                val pricing = operation.outboundPricing
                val feeMinor = pricing?.processingFee?.let { DecimalMoney.toMinor(it, scale) }
                    ?: operation.totalFees?.let { DecimalMoney.toMinor(it, scale) }
                val recipientMinor = pricing?.recipientAmount?.let { DecimalMoney.toMinor(it, scale) }
                    ?: operation.netAmount?.let { DecimalMoney.toMinor(it, scale) }
                val debitMinor = pricing?.customerDebit?.let { DecimalMoney.toMinor(it, scale) }
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
                    currencyCode = operation.currency.code.uppercase(),
                    currencyScale = scale,
                    feeMinor = feeMinor,
                    recipientAmountMinor = recipientMinor,
                    customerDebitMinor = debitMinor,
                    feeMode = pricing?.feeMode ?: operation.feeMode,
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
        feeMode: String,
    ) {
        submitOperation(previewOperation(type, beneficiaryId, amountMinor, feeMode), paymentPin)
    }

    override suspend fun previewOperation(
        type: String,
        beneficiaryId: String,
        amountMinor: Long,
        feeMode: String,
    ): FinancialOperationQuote {
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
        require(
            operation == BankOperationKind.DEPOSIT ||
                feeMode in setOf("sender_absorbs", "recipient_absorbs"),
        ) { "Choose how bank transfer fees are paid" }
        val active = requireNotNull(sessions.current()) { "Sign in again to access this wallet" }
        val wallet = sessions.withCurrentSession(active.fence()) { current ->
            requireNotNull(walletCache.selectedWallet(current.cacheScopeId)) {
                "No active wallet is selected"
            }
        }
        val amount = DecimalMoney.fromMinor(amountMinor, wallet.currencyScale)
        val legacyIntent = linkedMapOf<String, Any?>(
            "operation_type" to operation.apiType,
            "wallet_id" to wallet.uuid,
            "beneficiary_id" to beneficiaryId,
            "amount" to amount,
        )
        if (operation == BankOperationKind.DEPOSIT) {
            return FinancialOperationQuote(
                quoteId = null,
                operationType = operation.apiType,
                destinationId = beneficiaryId,
                amountMinor = amountMinor,
                recipientAmountMinor = amountMinor,
                feesMinor = 0,
                customerDebitMinor = amountMinor,
                currencyCode = wallet.currencyCode,
                currencyScale = wallet.currencyScale,
                feeMode = feeMode,
                expiresAt = null,
                feesKnown = false,
                authorizationPurpose = "bank_transfer",
                authorizationIntent = legacyIntent,
                sessionFence = active.fence(),
            )
        } else {
            val quote = try {
                apiCalls.execute {
                    val request = CreateBankingOutboundQuoteRequest(
                        wallet.uuid,
                        beneficiaryId,
                        amount,
                        feeMode,
                    )
                    if (operation == BankOperationKind.WITHDRAWAL) {
                        api.createBankWithdrawalQuote(request)
                    } else {
                        api.createBankTransferQuote(request)
                    }
                }
            } catch (error: KitWalletApiException) {
                if (error.statusCode != 404) throw error
                null
            }
            return if (quote == null) {
                FinancialOperationQuote(
                    quoteId = null,
                    operationType = operation.apiType,
                    destinationId = beneficiaryId,
                    amountMinor = amountMinor,
                    recipientAmountMinor = amountMinor,
                    feesMinor = 0,
                    customerDebitMinor = amountMinor,
                    currencyCode = wallet.currencyCode,
                    currencyScale = wallet.currencyScale,
                    feeMode = feeMode,
                    expiresAt = null,
                    feesKnown = false,
                    authorizationPurpose = "bank_transfer",
                    authorizationIntent = legacyIntent,
                    sessionFence = active.fence(),
                )
            } else {
                validateBankingOutboundQuote(
                    quote, operation, wallet.uuid, beneficiaryId, requireNotNull(beneficiary.bankId),
                    amount, feeMode, wallet.currencyCode, wallet.currencyScale,
                )
                FinancialOperationQuote(
                    quoteId = quote.id,
                    operationType = operation.apiType,
                    destinationId = beneficiaryId,
                    amountMinor = amountMinor,
                    recipientAmountMinor = DecimalMoney.toMinor(quote.recipientAmount, wallet.currencyScale),
                    feesMinor = DecimalMoney.toMinor(quote.processingFee, wallet.currencyScale),
                    customerDebitMinor = DecimalMoney.toMinor(quote.customerDebit, wallet.currencyScale),
                    currencyCode = wallet.currencyCode,
                    currencyScale = wallet.currencyScale,
                    feeMode = feeMode,
                    expiresAt = quote.expiresAt,
                    feesKnown = true,
                    authorizationPurpose = quote.stepUp.purpose,
                    authorizationIntent = quote.stepUp.intent.mapValues { it.value as Any? },
                    sessionFence = active.fence(),
                )
            }
        }
    }

    override suspend fun submitOperation(
        quote: FinancialOperationQuote,
        paymentPin: String,
    ): String {
        val operation = requireNotNull(BankOperationKind.fromApiType(quote.operationType)) {
            "The bank quote is invalid"
        }
        check(sessions.current()?.fence() == quote.sessionFence) {
            "The signed-in account changed after this quote was created"
        }
        quote.expiresAt?.let { expiresAt ->
            check(runCatching { Instant.parse(expiresAt).isAfter(Instant.now()) }.getOrDefault(false)) {
                "This bank quote has expired. Review a new quote."
            }
        }
        val token = paymentAuthorizer.authorize(
            quote.authorizationPurpose,
            quote.authorizationIntent,
            paymentPin,
        )
        val key = "android-bank-operation-${java.util.UUID.randomUUID()}"
        val created = if (quote.quoteId != null) {
            val request = CreateQuotedBankingOperationRequest(quote.quoteId)
            apiCalls.execute {
                if (operation == BankOperationKind.WITHDRAWAL) {
                    api.createQuotedBankWithdrawal(key, token, request)
                } else {
                    api.createQuotedBankTransfer(key, token, request)
                }
            }
        } else {
            val walletId = quote.authorizationIntent["wallet_id"] as? String
                ?: error("The bank quote omitted its wallet")
            val amount = quote.authorizationIntent["amount"] as? String
                ?: error("The bank quote omitted its amount")
            val request = CreateBankingOperationRequest(walletId, quote.destinationId, amount)
            apiCalls.execute {
                when (operation) {
                    BankOperationKind.DEPOSIT -> api.createBankDeposit(key, token, request)
                    BankOperationKind.WITHDRAWAL -> api.createBankWithdrawal(key, token, request)
                    BankOperationKind.TRANSFER -> api.createBankTransfer(key, token, request)
                }
            }
        }
        refresh()
        return created.id
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

internal fun validateBankingOutboundQuote(
    quote: BankingOutboundQuoteDto,
    operation: BankOperationKind,
    walletId: String,
    beneficiaryId: String,
    bankId: String,
    amount: String,
    feeMode: String,
    currency: String,
    currencyScale: Int,
    now: Instant = Instant.now(),
) {
    val expectedAction = if (operation == BankOperationKind.TRANSFER) "transfer" else "withdrawal"
    val enteredAmount = if (feeMode == "recipient_absorbs") quote.customerDebit else quote.recipientAmount
    fun decimal(value: String) = runCatching { java.math.BigDecimal(value) }.getOrNull()
    val recipient = decimal(quote.recipientAmount)
    val processing = decimal(quote.processingFee)
    val provider = decimal(quote.providerFee)
    val kitFee = decimal(quote.kitFee)
    val providerCap = decimal(quote.providerFeeCap)
    val maximumProviderTotal = decimal(quote.maximumProviderTotal)
    val customer = decimal(quote.customerDebit)
    val kitDebit = decimal(quote.kitDebit)
    val expectedIntent = mapOf(
        "action" to expectedAction,
        "operation_type" to operation.apiType,
        "quote_id" to quote.id,
        "wallet_id" to walletId,
        "beneficiary_id" to beneficiaryId,
        "bank_id" to bankId,
        "bank_code" to quote.bank.code,
        "fee_mode" to feeMode,
        "recipient_amount" to quote.recipientAmount,
        "processing_fee" to quote.processingFee,
        "provider_fee" to quote.providerFee,
        "kit_fee" to quote.kitFee,
        "provider_fee_cap" to quote.providerFeeCap,
        "maximum_provider_total" to quote.maximumProviderTotal,
        "customer_debit" to quote.customerDebit,
        "kit_debit" to quote.kitDebit,
        "schedule_version" to quote.scheduleVersion,
        "currency" to quote.currency.code,
    )
    check(
        quote.action == expectedAction && quote.operationType == operation.apiType &&
            quote.walletId == walletId && quote.beneficiaryId == beneficiaryId &&
            quote.bank.id == bankId && quote.feeMode == feeMode && quote.scheduleVerified &&
            quote.currency.code.equals(currency, ignoreCase = true) &&
            quote.currency.scale.toIntOrNull() == currencyScale &&
            recipient != null && decimal(enteredAmount)?.compareTo(java.math.BigDecimal(amount)) == 0 &&
            processing != null && provider != null && kitFee != null && providerCap != null &&
            maximumProviderTotal != null && customer != null && kitDebit != null &&
            recipient.signum() > 0 && provider.signum() >= 0 && kitFee.signum() >= 0 &&
            processing.compareTo(provider + kitFee) == 0 && providerCap.compareTo(provider) == 0 &&
            maximumProviderTotal.compareTo(recipient + providerCap) == 0 &&
            customer.compareTo(recipient + processing) == 0 && kitDebit.signum() == 0 &&
            runCatching { Instant.parse(quote.expiresAt).isAfter(now) }.getOrDefault(false) &&
            quote.stepUp.purpose == "bank_transfer" && quote.stepUp.intent == expectedIntent
    ) { "The bank transfer quote does not match this request" }
}
