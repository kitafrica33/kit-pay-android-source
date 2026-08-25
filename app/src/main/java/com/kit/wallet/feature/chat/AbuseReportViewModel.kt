package com.kit.wallet.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kit.wallet.data.remote.KitWalletApiException
import com.kit.wallet.data.repository.AbuseReportAttemptStore
import com.kit.wallet.data.repository.AbuseReportReceipt
import com.kit.wallet.data.repository.AbuseReportRequest
import com.kit.wallet.data.repository.AbuseReportingRepository
import com.kit.wallet.data.session.SessionInvalidatedException
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AbuseReportUiState(
    val submitting: Boolean = false,
    val error: String? = null,
    val receipt: AbuseReportReceipt? = null,
)

@HiltViewModel
class AbuseReportViewModel @Inject internal constructor(
    private val reports: AbuseReportingRepository,
    private val attempts: AbuseReportAttemptStore,
) : ViewModel() {
    private val mutableState = MutableStateFlow(AbuseReportUiState())
    val state = mutableState.asStateFlow()

    fun submit(request: AbuseReportRequest, reportingAvailable: Boolean) {
        if (mutableState.value.submitting || mutableState.value.receipt != null) return
        if (!reportingAvailable) {
            mutableState.value = AbuseReportUiState(
                error = "Reporting is temporarily unavailable. Please try again later.",
            )
            return
        }
        viewModelScope.launch {
            mutableState.value = AbuseReportUiState(submitting = true)
            val fingerprint = request.fingerprint()
            var idempotencyKey: String? = null
            try {
                idempotencyKey = attempts.keyFor(request.reporterUserId, fingerprint)
                val receipt = reports.submit(request, idempotencyKey)
                attempts.complete(request.reporterUserId, fingerprint, idempotencyKey)
                mutableState.value = AbuseReportUiState(receipt = receipt)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // The persisted key is deliberately retained for every uncertain or rejected
                // result. Retrying the same request is safe; editing it receives a new key.
                mutableState.value = AbuseReportUiState(error = error.userFacingReportError())
            }
        }
    }

    /** Clears screen state without discarding a replay key from an uncertain network attempt. */
    fun clearPresentation() {
        if (!mutableState.value.submitting) mutableState.value = AbuseReportUiState()
    }
}

internal fun Throwable.userFacingReportError(): String {
    val apiError = generateSequence(this) { it.cause }
        .filterIsInstance<KitWalletApiException>()
        .firstOrNull()
    return when {
        this is SessionInvalidatedException -> "Sign in again before submitting this report."
        apiError?.statusCode == 401 -> "Sign in again before submitting this report."
        apiError?.statusCode == 429 || apiError?.code?.contains("RATE_LIMIT", true) == true ->
            "Too many reports were submitted. Please wait a moment and try again."
        apiError?.code == "REPORT_TARGET_UNAVAILABLE" ->
            "This account or conversation is no longer available to report."
        apiError?.code == "VALIDATION_FAILED" ->
            "Review the report details and try again."
        else -> "Kit Pay could not submit this report. Please try again."
    }
}
