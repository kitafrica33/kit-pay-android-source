package com.kit.wallet.feature.referrals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kit.wallet.data.referrals.ReferralOverview
import com.kit.wallet.data.referrals.ReferralRepository
import com.kit.wallet.data.remote.KitWalletApiException
import com.kit.wallet.data.remote.isKitConnectivityError
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val GENERIC_REFERRAL_ERROR =
    "Referrals aren't available right now. Try again later."

private fun referralErrorMessage(error: Exception): String = when {
    error.isKitConnectivityError() -> "You're offline. Try again when you're back."
    error is KitWalletApiException -> error.message ?: GENERIC_REFERRAL_ERROR
    else -> error.message ?: GENERIC_REFERRAL_ERROR
}

data class ReferralUiState(
    val loading: Boolean = true,
    val overview: ReferralOverview? = null,
    /** The overview could not be loaded at all. */
    val error: String? = null,
    val mintingCode: Boolean = false,
    /** Getting a share code failed; the rest of the screen stays. */
    val codeError: String? = null,
)

@HiltViewModel
class ReferralViewModel @Inject constructor(
    private val referrals: ReferralRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ReferralUiState())
    val state: StateFlow<ReferralUiState> = mutableState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(loading = true, error = null)
            try {
                mutableState.value = ReferralUiState(
                    loading = false,
                    overview = referrals.overview(),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.value = mutableState.value.copy(
                    loading = false,
                    error = referralErrorMessage(error),
                )
            }
        }
    }

    /**
     * Asks the server for this account's single share code. Idempotent by
     * contract, so a retry after a lost response returns the same code.
     */
    fun requestCode() {
        if (mutableState.value.mintingCode) return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(mintingCode = true, codeError = null)
            try {
                val code = referrals.ensureCode()
                val current = mutableState.value
                mutableState.value = current.copy(
                    mintingCode = false,
                    overview = current.overview?.copy(code = code),
                )
                // The overview also carries the code from now on; refresh so the
                // rest of the screen (totals, program) is equally fresh.
                if (current.overview == null) refresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.value = mutableState.value.copy(
                    mintingCode = false,
                    codeError = referralErrorMessage(error),
                )
            }
        }
    }
}
