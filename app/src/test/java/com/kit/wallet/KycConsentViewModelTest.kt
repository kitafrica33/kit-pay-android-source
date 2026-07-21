package com.kit.wallet

import com.kit.wallet.data.repository.KycRepository
import com.kit.wallet.data.repository.KycStatus
import com.kit.wallet.feature.settings.KycViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KycConsentViewModelTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `verification cannot start without explicit consent`() = runTest {
        val repository = FakeKycRepository()
        val viewModel = KycViewModel(repository)

        viewModel.startVerification(consent = false)

        assertEquals(emptyList<Boolean>(), repository.startConsents)
        assertNull(viewModel.launchUrl.value)
        assertNotNull(viewModel.error.value)
    }

    @Test
    fun `explicit consent is transported to the repository`() = runTest {
        val repository = FakeKycRepository()
        val viewModel = KycViewModel(repository)

        viewModel.startVerification(consent = true)

        assertEquals(listOf(true), repository.startConsents)
        assertEquals(VERIFICATION_URL, viewModel.launchUrl.value)
        assertNull(viewModel.error.value)
    }

    private class FakeKycRepository : KycRepository {
        private val mutableStatus = MutableStateFlow<KycStatus?>(null)
        override val status: StateFlow<KycStatus?> = mutableStatus
        val startConsents = mutableListOf<Boolean>()

        override suspend fun refresh(): KycStatus = STATUS.also { mutableStatus.value = it }

        override suspend fun startVerification(consent: Boolean): String {
            startConsents += consent
            return VERIFICATION_URL
        }
    }

    private companion object {
        const val VERIFICATION_URL = "https://verify.didit.me/session/example_123"
        val STATUS = KycStatus(
            status = "not_started",
            caseReference = null,
            decisionCode = null,
            provider = "didit",
            providerStatus = null,
            verificationUrl = null,
            documents = emptyList(),
        )
    }
}
