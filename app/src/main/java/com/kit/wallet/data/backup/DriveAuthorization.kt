package com.kit.wallet.data.backup

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Asking Google for permission to write to the user's private app folder.
 *
 * This used to be a hand-rolled OAuth 2.0 authorization-code flow through the system browser, which
 * would have kept Play Services off the classpath entirely. That is no longer a flow Google
 * accepts: custom URI scheme redirects were withdrawn for Android OAuth client types, and an
 * authorization request carrying one is answered with `invalid_request` before the user ever sees a
 * consent screen. Loopback redirects are deprecated for the same client types and the manual
 * copy/paste option is gone, so the Authorization API is what is left.
 *
 * The trade is not all bad. There is no redirect for another app to register and race for, no
 * authorization code in transit, and no refresh token for Kit Pay to store — Play Services holds
 * the grant and mints a short-lived access token on demand, including for the overnight backup,
 * long after the user has closed the app. Kit Pay's own credential is its signing certificate,
 * which is why none of this is configured at build time.
 *
 * What Kit Pay asks for is one scope and no identity. It never learns which Google account was
 * chosen, never sees an email address, and cannot read anything in Drive except the file it wrote.
 */
@Singleton
class DriveAuthorizer @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * False on a device with no Play Services — a Huawei phone, a de-Googled ROM, some tablets.
     * The screen says so plainly rather than offering a button that cannot work.
     */
    val available: Boolean
        get() = GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS

    /**
     * Asks for an access token, without UI where Google will allow it.
     *
     * The first call for a user returns [DriveGrant.ConsentRequired] and the caller has to show the
     * screen Google supplied. Afterwards this resolves silently, which is what makes a scheduled
     * backup possible at all.
     */
    suspend fun authorize(): DriveGrant {
        if (!available) throw MessageBackupException(NO_PLAY_SERVICES)
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(SCOPE_APP_DATA)))
            .build()
        val result = Identity.getAuthorizationClient(context)
            .authorize(request)
            .awaitResult()
        return result.asGrant()
    }

    /** Reads the token out of whatever the consent screen returned. */
    fun readConsent(data: Intent?): String {
        val result = try {
            Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data)
        } catch (failure: Exception) {
            throw MessageBackupException(CONSENT_DECLINED, requiresSignIn = true, cause = failure)
        }
        return result.accessToken?.takeIf(String::isNotEmpty)
            ?: throw MessageBackupException(CONSENT_DECLINED, requiresSignIn = true)
    }

    private fun AuthorizationResult.asGrant(): DriveGrant {
        // hasResolution() is Google saying it will not decide without the user. It is set on a
        // first grant and again after the user revokes access from their Google account page, so
        // the same branch covers both.
        if (hasResolution()) {
            val consent = pendingIntent
                ?: throw GoogleAuthorizationException(SIGN_IN_AGAIN, requiresSignIn = true)
            return DriveGrant.ConsentRequired(consent)
        }
        val token = accessToken?.takeIf(String::isNotEmpty)
            ?: throw GoogleAuthorizationException(SIGN_IN_AGAIN, requiresSignIn = true)
        return DriveGrant.Granted(token)
    }

    /**
     * Play Services speaks in [Task]s. Bridged by hand rather than by pulling in the coroutines
     * interop artifact, which would put a second component through the attested dependency graph
     * for the sake of one call site.
     */
    private suspend fun <T> Task<T>.awaitResult(): T =
        suspendCancellableCoroutine<Result<T>> { waiting ->
            addOnSuccessListener { value ->
                waiting.resume(
                    if (value == null) {
                        Result.failure(IOException(SIGN_IN_AGAIN))
                    } else {
                        Result.success(value)
                    },
                )
            }
            addOnFailureListener { failure -> waiting.resume(Result.failure(failure)) }
            addOnCanceledListener { waiting.cancel() }
        }.getOrElse { failure ->
            // Play Services fails this call when it is out of date, when the device has no network
            // and no cached grant, and when the app's signing certificate is not registered against
            // an OAuth client. None of those are distinguishable from here, and all of them are
            // resolved by the user going through consent again.
            throw GoogleAuthorizationException(SIGN_IN_AGAIN, requiresSignIn = true)
                .apply { initCause(failure) }
        }

    internal companion object {
        /**
         * The narrowest scope Google offers: a private folder only this app can see. Kit Pay cannot
         * read the user's documents or photos, and nothing here appears in their Drive.
         */
        const val SCOPE_APP_DATA = "https://www.googleapis.com/auth/drive.appdata"

        const val NO_PLAY_SERVICES = "This phone does not have the Google services Drive backup needs"
        const val CONSENT_DECLINED = "Google did not grant Kit Pay access to Drive"
        const val SIGN_IN_AGAIN = "Google Drive needs you to sign in again"
    }
}

/** What came back from asking Google for the app-folder scope. */
sealed interface DriveGrant {
    data class Granted(val accessToken: String) : DriveGrant

    /** Google needs the user to approve, and supplied the screen to ask them with. */
    data class ConsentRequired(val consent: PendingIntent) : DriveGrant
}

/** Google said no, and said why. Distinct from a network failure, which is worth retrying. */
class GoogleAuthorizationException(
    message: String,
    /** True when the grant is gone for good and the user has to approve again. */
    val requiresSignIn: Boolean,
) : IOException(message)
