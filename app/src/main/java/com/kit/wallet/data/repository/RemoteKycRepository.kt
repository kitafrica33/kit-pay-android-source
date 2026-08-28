package com.kit.wallet.data.repository

import com.kit.wallet.BuildConfig
import com.kit.wallet.data.remote.ApiCallExecutor
import com.kit.wallet.data.remote.ApiEnvelope
import com.kit.wallet.data.remote.CreateKycSessionRequest
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.remote.KycStatusDto
import com.kit.wallet.data.session.SessionInvalidatedException
import com.kit.wallet.data.session.SessionStore
import com.kit.wallet.di.ApplicationScope
import java.net.URI
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
class RemoteKycRepository @Inject constructor(
    private val api: KitWalletApi,
    private val apiCalls: ApiCallExecutor,
    private val sessions: SessionStore,
    @ApplicationScope scope: CoroutineScope,
) : KycRepository {
    private val mutableStatus = MutableStateFlow<KycStatus?>(null)
    override val status: StateFlow<KycStatus?> = mutableStatus.asStateFlow()

    init {
        scope.launch {
            sessions.session.map { it?.sessionId }.distinctUntilChanged().collectLatest { sessionId ->
                // Cleared on EVERY session transition, replacement included, before any
                // refresh runs: account B must never read account A's verification while
                // its own refresh is still in flight — or after it failed. A stale status
                // fails closed to "unknown", never open to somebody else's identity.
                mutableStatus.value = null
                if (sessionId != null) runCatching { refresh() }
            }
        }
    }

    override suspend fun refresh(): KycStatus =
        fencedToCurrentSession { api.kycStatus() }

    override suspend fun startVerification(consent: Boolean): String {
        require(consent) { "Explicit identity-verification consent is required" }
        val mapped = fencedToCurrentSession {
            api.createKycSession(
                CreateKycSessionRequest(
                    consent = consent,
                    privacyNoticeVersion = BuildConfig.KIT_PRIVACY_NOTICE_VERSION,
                ),
            )
        }
        return requireNotNull(mapped.verificationUrl?.takeIf(::isTrustedDiditVerificationUrl)) {
            "Didit did not provide a secure verification link"
        }
    }

    /**
     * Runs the call under the session signed in when it started and publishes the mapped
     * status only if that exact session is still signed in when the answer lands. The KYC
     * endpoints are not fence-tagged at the transport, so without this an answer asked for
     * under account A could arrive after a switch and publish A's identity into B's view.
     * A switch mid-flight fails closed: nothing is published and the caller gets the same
     * [SessionInvalidatedException] the executor uses for fenced requests.
     */
    private suspend fun fencedToCurrentSession(
        call: suspend () -> ApiEnvelope<KycStatusDto>,
    ): KycStatus {
        val askedUnder = sessions.current()?.sessionId ?: throw SessionInvalidatedException()
        val mapped = apiCalls.execute(call).toUiModel()
        if (sessions.current()?.sessionId != askedUnder) throw SessionInvalidatedException()
        mutableStatus.value = mapped
        return mapped
    }

    private fun KycStatusDto.toUiModel() = KycStatus(
        // Trimmed as well as lowered: a status is compared against a fixed vocabulary, and a
        // stray space is not a reason to tell someone their identity is unverified.
        status = status.trim().lowercase(),
        accountStatus = accountStatus?.trim()?.lowercase()?.takeIf(String::isNotEmpty),
        deviceCheckRequired = deviceVerification?.required == true,
        caseReference = case?.reference,
        caseStatus = case?.status?.trim()?.lowercase()?.takeIf(String::isNotEmpty),
        decisionCode = case?.decisionCode,
        provider = providerSession?.provider,
        providerStatus = providerSession?.status,
        verificationUrl = providerSession?.verificationUrl?.takeIf(::isTrustedDiditVerificationUrl),
        documents = documents.orEmpty().map {
            KycDocument(
                type = it.type,
                issuingCountry = it.issuingCountry,
                status = it.status,
                reasonCodes = it.reasonCodes.orEmpty(),
            )
        },
    )

}

internal fun isTrustedDiditVerificationUrl(value: String): Boolean = runCatching {
    val uri = URI(value)
    val path = uri.rawPath.orEmpty()
        uri.scheme.equals("https", ignoreCase = true) &&
        uri.host.equals(DIDIT_VERIFICATION_HOST, ignoreCase = true) &&
        uri.userInfo == null &&
        (uri.port == -1 || uri.port == 443) &&
        uri.rawQuery == null &&
        uri.rawFragment == null &&
        DIDIT_VERIFICATION_PATH.matches(path)
}.getOrDefault(false)

private const val DIDIT_VERIFICATION_HOST = "verify.didit.me"

private val DIDIT_VERIFICATION_PATH =
    Regex("""^/(?:[A-Za-z]{2,3}(?:-[A-Za-z]{2})?/)?session/[A-Za-z0-9_-]{6,256}/?$""")
