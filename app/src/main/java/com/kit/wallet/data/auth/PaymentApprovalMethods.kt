package com.kit.wallet.data.auth

import com.kit.wallet.data.session.SessionStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether this device can approve a payment with biometrics right now.
 *
 * Three separate things must be true at the same moment, and any of them can stop being true while
 * the app is in the background:
 *
 *  * the phone must have a usable strong biometric enrolled;
 *  * this account's signing key must still be in the Keystore — enrolling a new fingerprint
 *    permanently invalidates it, and [BiometricPaymentApprover.availableFor] is what discovers
 *    that and clears the dead enrollment;
 *  * the last authoritative assurance response must explicitly advertise the enrolled device
 *    key. The payment's own step-up challenge is still the final authority, because enrollment
 *    can be removed between rendering the button and tapping it.
 *
 * Anything less and the wallet PIN is the only thing that can actually authorize a payment, so the
 * app asks for the PIN rather than raising a prompt that could never be satisfied. Unknown server
 * state fails closed instead of guessing that biometric approval is available.
 */
@Singleton
class PaymentApprovalMethods @Inject constructor(
    private val sessions: SessionStore,
    private val keys: BiometricSigningKey,
    private val approvals: BiometricPaymentApprover,
) {
    /**
     * True when biometric approval should be offered first for the signed-in account.
     *
     * Touches the Keystore, so callers should treat it as blocking and keep it off the main thread.
     */
    fun biometricsAvailable(): Boolean {
        val session = sessions.current() ?: return false
        val accountId = session.accountId ?: return false
        if (!runCatching { keys.isAvailable() }.getOrDefault(false)) return false
        if (!runCatching { approvals.availableFor(accountId) }.getOrDefault(false)) return false
        return serverAcceptsBiometrics(session.cachedAssurance?.loginUnlockMethods.orEmpty())
    }
}

/**
 * Whether the cached assurance leaves biometric approval open.
 *
 * An empty list means the server has not said yet — a session cached before assurance was ever
 * fetched, or a service too old to have the endpoint — so it must fail closed. The exact payment
 * challenge is checked again by PaymentAuthorizer at tap time.
 */
internal fun serverAcceptsBiometrics(methods: List<String>): Boolean =
    methods.any { it.equals("biometric_signature", ignoreCase = true) }
