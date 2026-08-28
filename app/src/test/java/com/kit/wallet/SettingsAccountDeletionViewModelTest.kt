package com.kit.wallet

import com.kit.wallet.data.auth.AccountDeletionPreflight
import com.kit.wallet.data.auth.AuthRepository
import com.kit.wallet.data.repository.ProfileEmailChallenge
import com.kit.wallet.data.repository.UserRepository
import com.kit.wallet.feature.home.StarterMilestone
import com.kit.wallet.feature.home.StarterMilestones
import com.kit.wallet.feature.settings.SettingsViewModel
import com.kit.wallet.ui.model.UserProfile
import java.lang.reflect.Proxy
import java.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * An accepted account deletion tears the account down, so the durable starter markers for
 * exactly the deleted account must go with it — and only then. A rejected request, or one
 * stopped by the local confirmation/PIN gates, must leave every marker in place.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsAccountDeletionViewModelTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val prefs = FakeSharedPreferences()
    private val milestones = StarterMilestones(
        prefsProvider = { prefs },
        ioDispatcher = Dispatchers.Unconfined,
    )

    @Test
    fun `an accepted deletion clears exactly the deleted account's starter markers`() = runTest {
        milestones.record(StarterMilestone.FIRST_MESSAGE, "account-a")
        milestones.record(StarterMilestone.FIRST_TRANSACTION, "account-a")
        milestones.record(StarterMilestone.FIRST_TRANSACTION, "account-b")
        val auth = DeletionAuthRepository()
        auth.deletionResults += Result.success(Unit)
        val viewModel = viewModel(auth)

        viewModel.beginAccountDeletion()
        advanceUntilIdle()
        viewModel.requestAccountDeletion("DELETE MY ACCOUNT", "1234")
        advanceUntilIdle()

        assertEquals(listOf("DELETE MY ACCOUNT" to "1234"), auth.deletionRequests)
        assertFalse(milestones.recorded(StarterMilestone.FIRST_MESSAGE, "account-a"))
        assertFalse(milestones.recorded(StarterMilestone.FIRST_TRANSACTION, "account-a"))
        assertTrue(milestones.recorded(StarterMilestone.FIRST_TRANSACTION, "account-b"))
    }

    @Test
    fun `a rejected deletion keeps every marker and surfaces the failure`() = runTest {
        milestones.record(StarterMilestone.FIRST_MESSAGE, "account-a")
        val auth = DeletionAuthRepository()
        auth.deletionResults += Result.failure(IllegalStateException("wallet balance must be zero"))
        val viewModel = viewModel(auth)

        viewModel.beginAccountDeletion()
        advanceUntilIdle()
        viewModel.requestAccountDeletion("DELETE MY ACCOUNT", "1234")
        advanceUntilIdle()

        assertEquals(1, auth.deletionRequests.size)
        assertTrue(milestones.recorded(StarterMilestone.FIRST_MESSAGE, "account-a"))
        val state = viewModel.deletionState.value
        assertFalse(state.submitting)
        assertEquals("wallet balance must be zero", state.error)
    }

    @Test
    fun `a wrong confirmation or pin never reaches the server and keeps the markers`() = runTest {
        milestones.record(StarterMilestone.FIRST_MESSAGE, "account-a")
        val auth = DeletionAuthRepository()
        val viewModel = viewModel(auth)

        viewModel.beginAccountDeletion()
        advanceUntilIdle()
        viewModel.requestAccountDeletion("delete my account", "1234")
        assertNotNull(viewModel.deletionState.value.error)
        viewModel.requestAccountDeletion("DELETE MY ACCOUNT", "12a4")
        advanceUntilIdle()

        assertTrue(auth.deletionRequests.isEmpty())
        assertNotNull(viewModel.deletionState.value.error)
        assertTrue(milestones.recorded(StarterMilestone.FIRST_MESSAGE, "account-a"))
    }

    private fun viewModel(auth: AuthRepository) = SettingsViewModel(
        ProfileOnlyUserRepository(),
        auth,
        MutableTestSessionStore(testSession("account-a")),
        milestones,
        Clock.systemUTC(),
    )

    /** Answers the deletion preflight and scripted request results; everything else errors. */
    private class DeletionAuthRepository : AuthRepository by unusedAuthRepository() {
        val deletionResults = mutableListOf<Result<Unit>>()
        val deletionRequests = mutableListOf<Pair<String, String>>()

        override suspend fun accountDeletionPreflight(): AccountDeletionPreflight =
            AccountDeletionPreflight(
                purpose = "Close this Kit Pay account",
                intent = emptyMap(),
                confirmationText = "DELETE MY ACCOUNT",
                publicUrl = "https://kit.example/account-deletion",
                deletedCategories = listOf("Profile and contacts"),
                retainedCategories = listOf("Regulatory transaction records"),
                closureRequirements = emptyList(),
            )

        override suspend fun requestAccountDeletion(
            preflight: AccountDeletionPreflight,
            confirmation: String,
            paymentPin: String,
        ) {
            deletionRequests += confirmation to paymentPin
            deletionResults.removeAt(0).getOrThrow()
        }
    }

    private class ProfileOnlyUserRepository : UserRepository {
        override val profile: StateFlow<UserProfile> = MutableStateFlow(
            UserProfile(
                name = "Amina Yusuf",
                phone = "+256700000001",
                tag = "amina",
                kycLabel = "Not verified",
            ),
        )

        override suspend fun refreshProfile() = Unit

        override suspend fun updateProfile(name: String, tag: String) = error("Unused")

        override suspend fun requestEmailAttachment(email: String): ProfileEmailChallenge =
            error("Unused")

        override suspend fun verifyEmailAttachment(challengeId: String, code: String) =
            error("Unused")
    }
}

@Suppress("UNCHECKED_CAST")
private fun unusedAuthRepository(): AuthRepository = Proxy.newProxyInstance(
    AuthRepository::class.java.classLoader,
    arrayOf(AuthRepository::class.java),
) { instance, method, arguments ->
    when (method.name) {
        "toString" -> "UnusedAuthRepository"
        "hashCode" -> System.identityHashCode(instance)
        "equals" -> instance === arguments?.firstOrNull()
        else -> error("Unexpected AuthRepository call: ${method.name}")
    }
} as AuthRepository
