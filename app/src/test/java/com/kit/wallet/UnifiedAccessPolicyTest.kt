package com.kit.wallet

import com.kit.wallet.data.repository.KycVerificationState
import com.kit.wallet.feature.auth.SessionAssuranceUiState
import com.kit.wallet.feature.auth.shouldPresentSessionUnlockGate
import com.kit.wallet.feature.wallet.FinancialBlockReason
import com.kit.wallet.feature.wallet.FinancialIdentityState
import com.kit.wallet.feature.wallet.ScopedAccessPosture
import com.kit.wallet.feature.wallet.ScopedAccessState
import com.kit.wallet.feature.wallet.financialAccessDecision
import com.kit.wallet.feature.wallet.runFinancialAction
import com.kit.wallet.feature.wallet.scopedAccessPosture
import com.kit.wallet.navigation.Dest
import com.kit.wallet.navigation.financialRouteAccessAllowed
import com.kit.wallet.navigation.financialAccessDestination
import com.kit.wallet.navigation.financialRouteRedirect
import com.kit.wallet.navigation.isFinancialRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedAccessPolicyTest {
    @Test
    fun `server admission matrix separates communication from financial access`() {
        val rows = listOf(
            MatrixRow(
                name = "account onboarding",
                accountState = KycVerificationState.NOT_STARTED,
                communicationAllowed = true,
                basis = "account_onboarding",
                requiredAction = null,
                financialAllowed = false,
                financialReadOnly = false,
                financialAction = "identity_verification_required",
                expectSessionGate = false,
                expectMovement = false,
                blockReason = FinancialBlockReason.VERIFY_IDENTITY,
            ),
            MatrixRow(
                name = "verified full assurance",
                accountState = KycVerificationState.VERIFIED,
                communicationAllowed = true,
                basis = "full_assurance",
                requiredAction = null,
                financialAllowed = true,
                financialReadOnly = false,
                financialAction = null,
                expectSessionGate = false,
                expectMovement = true,
                blockReason = null,
            ),
            MatrixRow(
                name = "verified device check",
                accountState = KycVerificationState.VERIFIED,
                communicationAllowed = false,
                basis = "full_assurance",
                requiredAction = "verify_device_identity",
                financialAllowed = false,
                financialReadOnly = false,
                financialAction = "verify_device_identity",
                expectSessionGate = true,
                expectMovement = false,
                blockReason = FinancialBlockReason.SESSION_ASSURANCE,
            ),
            MatrixRow(
                name = "verified locked session",
                accountState = KycVerificationState.VERIFIED,
                communicationAllowed = false,
                basis = "full_assurance",
                requiredAction = "unlock_session",
                financialAllowed = false,
                financialReadOnly = false,
                financialAction = "unlock_session",
                expectSessionGate = true,
                expectMovement = false,
                blockReason = FinancialBlockReason.SESSION_ASSURANCE,
            ),
            MatrixRow(
                name = "app review",
                accountState = KycVerificationState.VERIFIED,
                communicationAllowed = true,
                basis = "app_review",
                requiredAction = null,
                financialAllowed = true,
                financialReadOnly = true,
                financialAction = null,
                expectSessionGate = false,
                expectMovement = false,
                blockReason = FinancialBlockReason.READ_ONLY,
            ),
            MatrixRow(
                name = "app review locked session",
                accountState = KycVerificationState.NOT_STARTED,
                communicationAllowed = false,
                basis = "app_review",
                requiredAction = "unlock_session",
                financialAllowed = false,
                financialReadOnly = true,
                financialAction = "unlock_session",
                expectSessionGate = true,
                expectMovement = false,
                blockReason = FinancialBlockReason.SESSION_ASSURANCE,
            ),
        )

        rows.forEach { row ->
            val scope = ScopedAccessState(
                communicationAllowed = row.communicationAllowed,
                communicationBasis = row.basis,
                communicationRequiredAction = row.requiredAction,
                financialAllowed = row.financialAllowed,
                financialReadOnly = row.financialReadOnly,
                financialBasis = row.basis,
                financialRequiredAction = row.financialAction,
            )
            val session = SessionAssuranceUiState(
                required = row.expectSessionGate,
                communicationAccessAllowed = row.communicationAllowed,
                communicationAccessBasis = row.basis,
                communicationRequiredAction = row.requiredAction,
                financialAccessAllowed = row.financialAllowed,
                financialReadOnly = row.financialReadOnly,
                financialAccessBasis = row.basis,
                financialRequiredAction = row.financialAction,
            )
            assertEquals(
                row.name,
                row.expectSessionGate,
                shouldPresentSessionUnlockGate(session, row.accountState),
            )

            val financial = financialAccessDecision(
                identity = FinancialIdentityState(row.accountState),
                scopedAccess = scope,
                legacySessionAssured = !row.expectSessionGate,
            )
            assertEquals(row.name, row.financialAllowed, financial.allowed)
            assertEquals(row.name, row.financialReadOnly, financial.readOnly)
            assertEquals(row.name, row.expectMovement, financial.moneyMovementAllowed)
            assertEquals(row.name, row.blockReason, financial.blockReason)
        }
    }

    @Test
    fun `unknown or contradictory scoped values fail closed`() {
        val malformed = mapOf(
            "financial onboarding grant" to ScopedAccessState(
                true, "account_onboarding", null,
                true, false, "account_onboarding", null,
            ),
            "read only full assurance" to ScopedAccessState(
                true, "full_assurance", null,
                true, true, "full_assurance", null,
            ),
            "writable app review" to ScopedAccessState(
                true, "app_review", null,
                true, false, "app_review", null,
            ),
            "communication only" to ScopedAccessState(
                true, "account_onboarding", null,
                null, false, null, null,
            ),
            "financial only" to ScopedAccessState(
                null, null, null,
                true, false, "full_assurance", null,
            ),
            "mismatched bases" to ScopedAccessState(
                true, "full_assurance", null,
                true, true, "app_review", null,
            ),
            "mismatched actions" to ScopedAccessState(
                false, "full_assurance", "unlock_session",
                false, false, "full_assurance", "verify_device_identity",
            ),
            "blank basis" to ScopedAccessState(
                true, " ", null,
                true, false, " ", null,
            ),
            "padded action" to ScopedAccessState(
                false, "full_assurance", " unlock_session ",
                false, false, "full_assurance", " unlock_session ",
            ),
            "unknown basis" to ScopedAccessState(
                true, "future_basis", null,
                true, false, "future_basis", null,
            ),
        )

        malformed.forEach { (name, scope) ->
            assertEquals(name, ScopedAccessPosture.INVALID, scopedAccessPosture(scope))
            assertTrue(
                name,
                shouldPresentSessionUnlockGate(
                    SessionAssuranceUiState(required = false),
                    KycVerificationState.NOT_STARTED,
                    scope,
                ),
            )
            val financial = financialAccessDecision(
                identity = FinancialIdentityState(KycVerificationState.VERIFIED),
                scopedAccess = scope,
                legacySessionAssured = true,
            )
            assertFalse(name, financial.allowed)
            assertFalse(name, financial.moneyMovementAllowed)
            assertEquals(name, FinancialBlockReason.SESSION_ASSURANCE, financial.blockReason)
        }
    }

    @Test
    fun `legacy fallback admits onboarding communication but keeps verified sessions assured`() {
        val onboarding = SessionAssuranceUiState(
            required = true,
            deviceIdentityRequired = true,
            loginUnlockRequired = false,
        )
        assertFalse(
            shouldPresentSessionUnlockGate(onboarding, KycVerificationState.NOT_STARTED),
        )
        assertTrue(
            shouldPresentSessionUnlockGate(onboarding, KycVerificationState.VERIFIED),
        )

        val verifiedLocked = financialAccessDecision(
            identity = FinancialIdentityState(KycVerificationState.VERIFIED),
            scopedAccess = ScopedAccessState(),
            legacySessionAssured = false,
        )
        assertFalse(verifiedLocked.allowed)
        assertEquals(FinancialBlockReason.SESSION_ASSURANCE, verifiedLocked.blockReason)
    }

    @Test
    fun `all wallet routes are gated while review mode opens history only`() {
        val financialRoutes = listOf(
            Dest.SEND,
            Dest.send("user-1"),
            Dest.RECEIVE,
            Dest.SCAN,
            Dest.REQUEST,
            Dest.TRANSACTIONS,
            Dest.txDetail("transaction-1"),
            Dest.BILLS,
            Dest.billPay("power"),
            Dest.AIRTIME,
            Dest.BANK,
            Dest.MOBILE_MONEY,
        )
        financialRoutes.forEach { route ->
            assertTrue(route, isFinancialRoute(route))
            assertFalse(route, financialRouteAccessAllowed(route, false, false))
            assertEquals(route, Dest.HOME, financialRouteRedirect(route, false))
        }

        assertTrue(financialRouteAccessAllowed(Dest.TRANSACTIONS, true, true))
        assertTrue(financialRouteAccessAllowed(Dest.txDetail("transaction-1"), true, true))
        financialRoutes.filterNot {
            it == Dest.TRANSACTIONS || it.startsWith("wallet/tx/")
        }.forEach { route ->
            assertFalse(route, financialRouteAccessAllowed(route, true, true))
        }
        assertTrue(financialRouteAccessAllowed(Dest.CHATS, false, false))
        assertFalse(isFinancialRoute(Dest.FINANCIAL_ACCESS))
        assertNull(financialRouteRedirect(Dest.SUPPORT, false))
    }

    @Test
    fun `blocked financial entry points use full screen destinations`() {
        assertEquals(
            Dest.KYC,
            financialAccessDestination(FinancialBlockReason.VERIFY_IDENTITY, true),
        )
        assertEquals(
            Dest.FINANCIAL_ACCESS,
            financialAccessDestination(FinancialBlockReason.VERIFY_IDENTITY, false),
        )
        assertEquals(
            Dest.FINANCIAL_ACCESS,
            financialAccessDestination(FinancialBlockReason.READ_ONLY, true),
        )
        assertNull(
            financialAccessDestination(FinancialBlockReason.SESSION_ASSURANCE, true),
        )
    }

    @Test
    fun `financial action guard invokes exactly one side of the decision`() {
        var blocked = 0
        var ran = 0
        assertFalse(runFinancialAction(false, { blocked++ }) { ran++ })
        assertEquals(1, blocked)
        assertEquals(0, ran)

        assertTrue(runFinancialAction(true, { blocked++ }) { ran++ })
        assertEquals(1, blocked)
        assertEquals(1, ran)
    }

    private data class MatrixRow(
        val name: String,
        val accountState: KycVerificationState,
        val communicationAllowed: Boolean,
        val basis: String,
        val requiredAction: String?,
        val financialAllowed: Boolean,
        val financialReadOnly: Boolean,
        val financialAction: String?,
        val expectSessionGate: Boolean,
        val expectMovement: Boolean,
        val blockReason: FinancialBlockReason?,
    )
}
