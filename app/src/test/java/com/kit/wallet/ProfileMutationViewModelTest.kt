package com.kit.wallet

import com.kit.wallet.data.auth.AuthRepository
import com.kit.wallet.data.repository.ProfileEmailChallenge
import com.kit.wallet.data.repository.UserRepository
import com.kit.wallet.feature.settings.SettingsViewModel
import com.kit.wallet.ui.model.UserProfile
import java.lang.reflect.Proxy
import java.time.Clock
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileMutationViewModelTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `profile save and completion cannot race an avatar upload`() = runTest {
        val repository = BlockingUserRepository()
        val viewModel = SettingsViewModel(repository, unusedAuthRepository(), Clock.systemUTC())

        viewModel.attachAvatar(byteArrayOf(1, 2, 3))
        repository.avatarStarted.await()
        assertTrue(viewModel.editorState.value.uploadingAvatar)

        var saved = false
        viewModel.saveProfile("Amina Yusuf", "amina", onSaved = { saved = true })
        assertEquals(0, repository.profileUpdateCount)
        assertFalse(saved)

        repository.allowAvatarToFinish.complete(Unit)
        advanceUntilIdle()
        assertFalse(viewModel.editorState.value.uploadingAvatar)

        viewModel.saveProfile("Amina Yusuf", "amina", onSaved = { saved = true })
        advanceUntilIdle()
        assertEquals(1, repository.profileUpdateCount)
        assertTrue(saved)
    }

    private class BlockingUserRepository : UserRepository {
        override val profile: StateFlow<UserProfile> = MutableStateFlow(
            UserProfile(
                name = "Amina Yusuf",
                phone = "+256700000001",
                tag = "amina",
                kycLabel = "Not verified",
            ),
        )
        val avatarStarted = CompletableDeferred<Unit>()
        val allowAvatarToFinish = CompletableDeferred<Unit>()
        var profileUpdateCount = 0

        override suspend fun refreshProfile() = Unit

        override suspend fun updateProfile(name: String, tag: String) {
            profileUpdateCount += 1
        }

        override suspend fun attachAvatar(jpegBytes: ByteArray) {
            avatarStarted.complete(Unit)
            allowAvatarToFinish.await()
        }

        override suspend fun requestEmailAttachment(email: String): ProfileEmailChallenge =
            error("Unused")

        override suspend fun verifyEmailAttachment(challengeId: String, code: String) = Unit
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
}
