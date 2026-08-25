package com.kit.wallet.data.contacts

import android.content.Context
import com.kit.wallet.data.session.SessionFence
import com.kit.wallet.data.session.SessionStore
import com.kit.wallet.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

private const val PREFERENCES_NAME = "kit-contact-discovery"
private const val LEGACY_DEVICE_WIDE_KEY = "share_device_contacts"
private const val ACCOUNT_KEY_PREFIX = "share_device_contacts_v2."
private const val MAX_ACCOUNT_ID_UTF8_BYTES = 256

/** One exact consent generation bound to one authenticated session. */
class ContactDiscoveryAuthorization internal constructor(
    val sessionFence: SessionFence,
    internal val preferenceKey: String,
    internal val generation: Long,
)

/**
 * Account-aware consent boundary used by contact uploaders and the Settings presentation.
 *
 * A nullable/missing implementation is never permission to upload. Callers must obtain an exact
 * [ContactDiscoveryAuthorization] and re-check it around every upload boundary.
 */
interface ContactDiscoveryConsent {
    /** The current account's recorded choice. False while signed out or identity is malformed. */
    val shareDeviceContacts: StateFlow<Boolean>

    /** True only when there is a well-formed authenticated account to which a choice can belong. */
    val available: StateFlow<Boolean>

    /** Stores a choice for the current account. False means there was no writable current owner. */
    fun setShareDeviceContacts(allowed: Boolean): Boolean

    /** Captures a positive choice for [expected], or null when anything is unknown/off. */
    fun authorizationFor(expected: SessionFence): ContactDiscoveryAuthorization?

    /** Re-checks account, session and consent generation after asynchronous work. */
    fun isAuthorized(authorization: ContactDiscoveryAuthorization): Boolean
}

/**
 * Pure, synchronized account ledger. Android storage is supplied by the production wrapper; this
 * small core makes account-switch and revocation behavior independently testable.
 */
internal class AccountScopedContactDiscoveryLedger(
    private val read: (String) -> Boolean?,
    private val write: (String, Boolean) -> Boolean,
) {
    private var generation: Long = 0

    @Synchronized
    fun allowed(accountId: String?): Boolean {
        val key = contactDiscoveryPreferenceKey(accountId) ?: return false
        return read(key) == true
    }

    @Synchronized
    fun set(accountId: String?, allowed: Boolean): Boolean {
        val key = contactDiscoveryPreferenceKey(accountId) ?: return false
        if (!write(key, allowed)) return false
        generation++
        return true
    }

    @Synchronized
    fun authorization(
        accountId: String?,
        sessionFence: SessionFence,
    ): ContactDiscoveryAuthorization? {
        val key = contactDiscoveryPreferenceKey(accountId) ?: return null
        if (read(key) != true) return null
        return ContactDiscoveryAuthorization(sessionFence, key, generation)
    }

    @Synchronized
    fun isCurrent(accountId: String?, authorization: ContactDiscoveryAuthorization): Boolean {
        val key = contactDiscoveryPreferenceKey(accountId) ?: return false
        return key == authorization.preferenceKey &&
            generation == authorization.generation &&
            read(key) == true
    }
}

/**
 * Whether this account may send this phone's address book to Kit Pay for contact discovery.
 *
 * Android's READ_CONTACTS grant answers only whether the app may read the phone. It is neither
 * upload consent nor an upgrade migration for upload consent. The old build stored one device-wide
 * bit and inferred it from the permission; that value is deliberately discarded because it cannot
 * be attributed safely to any one of the accounts that may use this phone.
 */
@Singleton
class ContactDiscoveryPreferences @Inject constructor(
    @ApplicationContext context: Context,
    private val sessions: SessionStore,
    @ApplicationScope scope: CoroutineScope,
) : ContactDiscoveryConsent {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val mutation = MutableStateFlow(0L)
    private val ledger = AccountScopedContactDiscoveryLedger(
        read = { key ->
            if (preferences.contains(key)) preferences.getBoolean(key, false) else null
        },
        write = { key, value -> preferences.edit().putBoolean(key, value).commit() },
    )

    init {
        // No account can safely inherit a device-global answer. A runtime permission is also not
        // permission to upload, so there is intentionally no permission-based seeding here.
        preferences.edit().remove(LEGACY_DEVICE_WIDE_KEY).commit()
    }

    override val available: StateFlow<Boolean> = sessions.session
        .map { contactDiscoveryPreferenceKey(it?.accountId) != null }
        .stateIn(scope, SharingStarted.Eagerly, false)

    override val shareDeviceContacts: StateFlow<Boolean> = combine(
        sessions.session,
        mutation,
    ) { session, _ ->
        ledger.allowed(session?.accountId)
    }.stateIn(scope, SharingStarted.Eagerly, false)

    override fun setShareDeviceContacts(allowed: Boolean): Boolean {
        val before = sessions.current() ?: return false
        if (!ledger.set(before.accountId, allowed)) return false
        mutation.value += 1
        // The answer was stored for the account that owned the tap, but the caller must not treat
        // it as authorization for a replacement login that arrived during the disk commit.
        return sessions.current()?.fence() == before.fence()
    }

    override fun authorizationFor(expected: SessionFence): ContactDiscoveryAuthorization? {
        val current = sessions.current() ?: return null
        if (current.fence() != expected) return null
        return ledger.authorization(current.accountId, expected)
    }

    override fun isAuthorized(authorization: ContactDiscoveryAuthorization): Boolean {
        val current = sessions.current() ?: return false
        return current.fence() == authorization.sessionFence &&
            ledger.isCurrent(current.accountId, authorization)
    }
}

/** Opaque preference key: account identifiers never become SharedPreferences key material. */
internal fun contactDiscoveryPreferenceKey(accountId: String?): String? {
    val canonical = accountId?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val bytes = canonical.toByteArray(Charsets.UTF_8)
    if (bytes.size > MAX_ACCOUNT_ID_UTF8_BYTES || canonical.any(Char::isISOControl)) return null
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return ACCOUNT_KEY_PREFIX + digest.joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
}
