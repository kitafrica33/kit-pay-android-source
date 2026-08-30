package com.kit.wallet.feature.wallet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kit.wallet.data.repository.KycRepository
import com.kit.wallet.data.repository.KycStatus
import com.kit.wallet.data.repository.KycVerificationState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * The account-level identity decision used by every wallet surface.
 *
 * Device assurance is deliberately not folded into this value. A restricted login is enforced by
 * the session gate, while this policy answers the narrower question: may this account use money
 * services at all? Only the backend's explicit verified account state opens that boundary. A null,
 * new, or unknown response therefore fails closed.
 */
data class FinancialIdentityState(
    val accountState: KycVerificationState = KycVerificationState.UNKNOWN,
) {
    val moneyMovementAllowed: Boolean
        get() = accountState == KycVerificationState.VERIFIED
}

enum class FinancialBlockReason {
    VERIFY_IDENTITY,
    READ_ONLY,
    SESSION_ASSURANCE,
}

/**
 * One indivisible caller-scoped policy snapshot. The backend always emits the communication and
 * financial objects together for an authenticated caller, so a partial or internally inconsistent
 * pair is not safe to interpret field-by-field.
 */
data class ScopedAccessState(
    val communicationAllowed: Boolean? = null,
    val communicationBasis: String? = null,
    val communicationRequiredAction: String? = null,
    val financialAllowed: Boolean? = null,
    val financialReadOnly: Boolean = false,
    val financialBasis: String? = null,
    val financialRequiredAction: String? = null,
) {
    val hasAnyScopedValue: Boolean
        get() = communicationAllowed != null ||
            communicationBasis != null ||
            communicationRequiredAction != null ||
            financialAllowed != null ||
            financialReadOnly ||
            financialBasis != null ||
            financialRequiredAction != null
}

internal enum class ScopedAccessPosture {
    ABSENT,
    ACCOUNT_ONBOARDING,
    FULL_ASSURANCE,
    APP_REVIEW,
    VERIFY_DEVICE_IDENTITY,
    UNLOCK_SESSION,
    INVALID,
}

/** Exact, fail-closed validation of the backend's complete scoped-access tuple. */
internal fun scopedAccessPosture(scope: ScopedAccessState): ScopedAccessPosture {
    if (!scope.hasAnyScopedValue) return ScopedAccessPosture.ABSENT
    if (scope.communicationAllowed == null || scope.financialAllowed == null) {
        return ScopedAccessPosture.INVALID
    }
    val blockedAssuranceBasisIsCoherent =
        scope.communicationBasis == scope.financialBasis &&
            (scope.communicationBasis == "full_assurance" && !scope.financialReadOnly ||
                scope.communicationBasis == "app_review" && scope.financialReadOnly)
    return when {
        scope.communicationAllowed &&
            scope.communicationBasis == "account_onboarding" &&
            scope.communicationRequiredAction == null &&
            !scope.financialAllowed &&
            !scope.financialReadOnly &&
            scope.financialBasis == "account_onboarding" &&
            scope.financialRequiredAction == "identity_verification_required" ->
            ScopedAccessPosture.ACCOUNT_ONBOARDING

        scope.communicationAllowed &&
            scope.communicationBasis == "full_assurance" &&
            scope.communicationRequiredAction == null &&
            scope.financialAllowed &&
            !scope.financialReadOnly &&
            scope.financialBasis == "full_assurance" &&
            scope.financialRequiredAction == null -> ScopedAccessPosture.FULL_ASSURANCE

        scope.communicationAllowed &&
            scope.communicationBasis == "app_review" &&
            scope.communicationRequiredAction == null &&
            scope.financialAllowed &&
            scope.financialReadOnly &&
            scope.financialBasis == "app_review" &&
            scope.financialRequiredAction == null -> ScopedAccessPosture.APP_REVIEW

        !scope.communicationAllowed &&
            blockedAssuranceBasisIsCoherent &&
            scope.communicationRequiredAction == "verify_device_identity" &&
            !scope.financialAllowed &&
            scope.financialRequiredAction == "verify_device_identity" ->
            ScopedAccessPosture.VERIFY_DEVICE_IDENTITY

        !scope.communicationAllowed &&
            blockedAssuranceBasisIsCoherent &&
            scope.communicationRequiredAction == "unlock_session" &&
            !scope.financialAllowed &&
            scope.financialRequiredAction == "unlock_session" ->
            ScopedAccessPosture.UNLOCK_SESSION

        else -> ScopedAccessPosture.INVALID
    }
}

data class FinancialAccessDecision(
    val allowed: Boolean,
    val readOnly: Boolean,
    val blockReason: FinancialBlockReason?,
) {
    val moneyMovementAllowed: Boolean get() = allowed && !readOnly
}

internal fun financialIdentityState(status: KycStatus?): FinancialIdentityState =
    FinancialIdentityState(status?.accountState ?: KycVerificationState.UNKNOWN)

/**
 * Prefer the scoped server decision when present; production servers predating that contract fall
 * back to the fenced account KYC status. An explicit read-only grant is kept distinct so App
 * Review can inspect its synthetic wallet without acquiring any money-moving action.
 */
internal fun financialAccessDecision(
    identity: FinancialIdentityState,
    scopedAccess: ScopedAccessState,
    legacySessionAssured: Boolean,
): FinancialAccessDecision = when (scopedAccessPosture(scopedAccess)) {
    ScopedAccessPosture.ACCOUNT_ONBOARDING ->
        FinancialAccessDecision(false, false, FinancialBlockReason.VERIFY_IDENTITY)
    ScopedAccessPosture.FULL_ASSURANCE -> FinancialAccessDecision(true, false, null)
    ScopedAccessPosture.APP_REVIEW ->
        FinancialAccessDecision(true, true, FinancialBlockReason.READ_ONLY)
    ScopedAccessPosture.VERIFY_DEVICE_IDENTITY,
    ScopedAccessPosture.UNLOCK_SESSION,
    -> FinancialAccessDecision(
        false,
        scopedAccess.financialReadOnly,
        FinancialBlockReason.SESSION_ASSURANCE,
    )
    ScopedAccessPosture.INVALID ->
        FinancialAccessDecision(false, false, FinancialBlockReason.SESSION_ASSURANCE)
    ScopedAccessPosture.ABSENT -> if (identity.moneyMovementAllowed && legacySessionAssured) {
        FinancialAccessDecision(true, false, null)
    } else if (identity.moneyMovementAllowed) {
        FinancialAccessDecision(false, false, FinancialBlockReason.SESSION_ASSURANCE)
    } else {
        FinancialAccessDecision(false, false, FinancialBlockReason.VERIFY_IDENTITY)
    }
}

/**
 * One guard for every user-initiated financial action, including actions embedded in chat.
 *
 * Returning whether [action] ran makes non-Unit callers (for example the group-payment composer)
 * fail closed without inventing a successful result. The blocked callback is invoked exactly once.
 */
internal fun runFinancialAction(
    moneyMovementAllowed: Boolean,
    onVerificationRequired: () -> Unit,
    action: () -> Unit,
): Boolean {
    if (!moneyMovementAllowed) {
        onVerificationRequired()
        return false
    }
    action()
    return true
}

@HiltViewModel
class FinancialIdentityViewModel @Inject constructor(
    kycRepository: KycRepository,
) : ViewModel() {
    val state: StateFlow<FinancialIdentityState> = kycRepository.status
        .map(::financialIdentityState)
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            FinancialIdentityState(),
        )
}
