package com.kit.wallet

import com.kit.wallet.data.auth.isPlaceholderProfileName
import com.kit.wallet.data.auth.isProvisionalProfileTag
import com.kit.wallet.data.auth.normalizeProfileName
import com.kit.wallet.data.auth.requiresProfileSetup
import com.kit.wallet.data.mapper.toEntity
import com.kit.wallet.data.mapper.toUiModel
import com.kit.wallet.data.remote.UserDto
import com.kit.wallet.feature.settings.normalizeProfileTag
import com.kit.wallet.feature.settings.mergeProfileEditorInitialValues
import com.kit.wallet.feature.settings.ProfileEditorInitialValues
import com.kit.wallet.feature.settings.profileEditorInitialValues
import com.kit.wallet.feature.settings.profileEmailPresentation
import com.kit.wallet.feature.settings.profileValidationError
import com.kit.wallet.ui.model.UserProfile
import com.kit.wallet.ui.model.formatKitTag
import com.kit.wallet.navigation.Dest
import com.kit.wallet.navigation.shouldRequireProfileSetup
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileContractTest {
    @Test
    fun `explicit null user flags parse and normalize safely`() {
        val adapter = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
            .adapter(UserDto::class.java)

        val user = requireNotNull(
            adapter.fromJson(
                """{"id":"user-1","name":"Amina","email":"amina@example.test","tag":"amina","payment_pin_set":null,"mfa_enabled":null,"email_verified":null,"phone_verified":null,"profile_setup_required":null}""",
            ),
        )
        val cached = user.toEntity(nowEpochMillis = 123L)

        assertNull(user.mfaEnabled)
        assertFalse(cached.emailVerified)
        assertEquals("amina@example.test", cached.email)
        assertFalse(cached.profileSetupRequired)
    }

    @Test
    fun `explicit null legacy profile name reaches mandatory setup safely`() {
        val adapter = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
            .adapter(UserDto::class.java)
        val user = requireNotNull(
            adapter.fromJson(
                """{"id":"legacy-user","name":null,"tag":null,"profile_setup_required":null}""",
            ),
        )

        val cached = user.toEntity(nowEpochMillis = 123L)

        assertEquals("Kit Pay User", cached.name)
        assertTrue(cached.profileSetupRequired)
        assertTrue(cached.toUiModel().profileSetupRequired)
    }

    @Test
    fun `server profile completion requirement is persisted even with non-placeholder identity`() {
        val cached = UserDto(
            id = "user-1",
            name = "Amina Yusuf",
            tag = "amina",
            profileSetupRequired = true,
        ).toEntity(nowEpochMillis = 123L)

        assertTrue(cached.profileSetupRequired)
        assertTrue(cached.toUiModel().profileSetupRequired)
    }

    @Test
    fun `cached provisional tag still requires setup when an old flag was false`() {
        val cached = UserDto(
            id = "user-1",
            name = "Amina Yusuf",
            tag = "kit_a1b2c3d4e5",
            profileSetupRequired = false,
        ).toEntity(nowEpochMillis = 123L)

        assertTrue(cached.profileSetupRequired)
        assertTrue(cached.toUiModel().profileSetupRequired)
    }

    @Test
    fun `setup clears server placeholders but settings edit preserves saved identity`() {
        val provisional = profile(name = "Kit Wallet User", tag = "kit_a1b2c3d4e5")

        val setup = profileEditorInitialValues(provisional, setup = true)
        val settings = profileEditorInitialValues(provisional, setup = false)

        assertEquals("", setup.name)
        assertEquals("", setup.tag)
        assertEquals("Kit Wallet User", settings.name)
        assertEquals("kit_a1b2c3d4e5", settings.tag)
        assertTrue(isPlaceholderProfileName(provisional.name))
        assertTrue(isPlaceholderProfileName("Kit Pay User"))
        assertTrue(isProvisionalProfileTag(provisional.tag))
        assertTrue(requiresProfileSetup(provisional.name, provisional.tag))
        assertEquals(
            "Enter a username / display name (2–120 characters).",
            profileValidationError("", "amina"),
        )
        assertEquals(
            "Choose the username / display name people should see.",
            profileValidationError(provisional.name, "amina"),
        )
        assertEquals(
            "Choose your own Kit Pay tag.",
            profileValidationError("Amina", provisional.tag),
        )
    }

    @Test
    fun `real profile identity is retained and normalized`() {
        val profile = profile(name = "Amina Yusuf", tag = "@Amina_01")
        val initial = profileEditorInitialValues(profile, setup = true)

        assertEquals("Amina Yusuf", initial.name)
        assertEquals("amina_01", initial.tag)
        assertEquals("amina_01", normalizeProfileTag("\u00a0@amina_01\u3000"))
        assertEquals("Amina Yusuf", normalizeProfileName("  Amina\n\tYusuf  "))
        assertEquals(
            "Amina Yusuf",
            normalizeProfileName("\u00a0Amina\u2003\u0085Yusuf\u3000"),
        )
        assertNull(profileValidationError(initial.name, initial.tag))
        assertFalse(requiresProfileSetup(initial.name, initial.tag))
        assertEquals("@amina_01", formatKitTag("@@amina_01"))
    }

    @Test
    fun `display name length uses Unicode characters like the backend`() {
        assertNull(profileValidationError("😀".repeat(120), "emoji_name"))
        assertEquals(
            "Enter a username / display name (2–120 characters).",
            profileValidationError("😀".repeat(121), "emoji_name"),
        )
    }

    @Test
    fun `reserved deleted and malformed tags keep profile setup gated`() {
        assertEquals(
            "This Kit Pay tag is reserved.",
            profileValidationError("Amina", "support"),
        )
        assertEquals(
            "This Kit Pay tag is reserved.",
            profileValidationError("Amina", "deleted_user"),
        )
        assertTrue(requiresProfileSetup("Amina", "kit_pay"))
        assertTrue(requiresProfileSetup("Amina", "deleted_123"))
        assertTrue(requiresProfileSetup("Amina", "bad-tag"))
        assertFalse(requiresProfileSetup("Amina Yusuf", "amina_01"))
    }

    @Test
    fun `late cached profile only initializes untouched editor fields`() {
        val merged = mergeProfileEditorInitialValues(
            current = ProfileEditorInitialValues(name = "User typing", tag = ""),
            profile = profile(name = "Cached Name", tag = "cached_tag"),
            setup = true,
            nameEdited = true,
            tagEdited = false,
        )

        assertEquals("User typing", merged.name)
        assertEquals("cached_tag", merged.tag)
    }

    @Test
    fun `restored signed in session is gated until required profile setup completes`() {
        assertTrue(shouldRequireProfileSetup(true, true, Dest.HOME))
        assertFalse(shouldRequireProfileSetup(true, true, Dest.PROFILE_SETUP))
        assertFalse(shouldRequireProfileSetup(false, true, Dest.HOME))
        assertFalse(shouldRequireProfileSetup(true, false, Dest.HOME))
    }

    @Test
    fun `verified email is informational because replacement is unsupported`() {
        val presentation = profileEmailPresentation(
            profile(name = "Amina Yusuf", tag = "amina", email = "amina@example.test", verified = true),
        )

        assertEquals("Email address", presentation.title)
        assertTrue(presentation.subtitle.contains("Verified"))
        assertTrue(presentation.subtitle.contains("not yet supported"))
        assertFalse(presentation.canAttach)
    }

    @Test
    fun `email attachment stays visible but unavailable behind the mail capability`() {
        val presentation = profileEmailPresentation(
            profile(name = "Amina Yusuf", tag = "amina"),
            attachmentAvailable = false,
        )

        assertEquals("Add email address", presentation.title)
        assertEquals("Email verification is temporarily unavailable", presentation.subtitle)
        assertFalse(presentation.canAttach)
    }

    private fun profile(
        name: String,
        tag: String,
        email: String? = null,
        verified: Boolean = false,
    ) = UserProfile(
        name = name,
        phone = "+256700000200",
        tag = tag,
        kycLabel = "KYC not started",
        email = email,
        emailVerified = verified,
    )
}
