package com.kit.wallet.data.repository

import com.kit.wallet.BuildConfig
import com.kit.wallet.data.remote.ApiCallExecutor
import com.kit.wallet.data.remote.CreateKycSessionRequest
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.remote.KycStatusDto
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
    sessions: SessionStore,
    @ApplicationScope scope: CoroutineScope,
) : KycRepository {
    private val mutableStatus = MutableStateFlow<KycStatus?>(null)
    override val status: StateFlow<KycStatus?> = mutableStatus.asStateFlow()

    init {
        scope.launch {
            sessions.session.map { it?.sessionId }.distinctUntilChanged().collectLatest { sessionId ->
                if (sessionId == null) mutableStatus.value = null
                else runCatching { refresh() }
            }
        }
    }

    override suspend fun refresh(): KycStatus = apiCalls.execute { api.kycStatus() }
        .toUiModel()
        .also { mutableStatus.value = it }

    override suspend fun startVerification(consent: Boolean): String {
        require(consent) { "Explicit identity-verification consent is required" }
        val response = apiCalls.execute {
            api.createKycSession(
                CreateKycSessionRequest(
                    consent = consent,
                    privacyNoticeVersion = BuildConfig.KIT_PRIVACY_NOTICE_VERSION,
                ),
            )
        }
        val mapped = response.toUiModel()
        mutableStatus.value = mapped
        return requireNotNull(mapped.verificationUrl?.takeIf(::isTrustedDiditVerificationUrl)) {
            "Didit did not provide a secure verification link"
        }
    }

    private fun KycStatusDto.toUiModel() = KycStatus(
        status = status.lowercase(),
        caseReference = case?.reference,
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
