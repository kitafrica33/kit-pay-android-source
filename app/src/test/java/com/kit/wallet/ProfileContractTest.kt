package com.kit.wallet

import com.kit.wallet.data.auth.isPlaceholderProfileName
import com.kit.wallet.data.auth.isProvisionalProfileTag
import com.kit.wallet.data.auth.normalizeProfileName
import com.kit.wallet.data.auth.requiresProfileSetup
import com.kit.wallet.data.mapper.toEntity
import com.kit.wallet.data.mapper.toUiModel
import com.kit.wallet.data.remote.UpdateProfileRequest
import com.kit.wallet.data.remote.UpdateProfileRequestAdapter
import com.kit.wallet.data.remote.UserDto
import com.kit.wallet.data.remote.ContactDto
import com.kit.wallet.feature.settings.normalizeProfileTag
import com.kit.wallet.feature.settings.profileIdentitySubtitle
import com.kit.wallet.feature.settings.mergeProfileEditorInitialValues
import com.kit.wallet.feature.settings.ProfileEditorInitialValues
import com.kit.wallet.feature.settings.profileEditorInitialValues
import com.kit.wallet.feature.settings.profileEmailPresentation
import com.kit.wallet.feature.settings.profileValidationError
import com.kit.wallet.data.repository.toContactModel
import com.kit.wallet.ui.model.UserProfile
import com.kit.wallet.ui.model.AccountVerificationDesignation
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
    fun `exact account designations decode persist and map with their grant time`() {
        val adapter = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
            .adapter(UserDto::class.java)
        val supported = mapOf(
            "verified" to AccountVerificationDesignation.VERIFIED,
            "official" to AccountVerificationDesignation.OFFICIAL,
            "official_support" to AccountVerificationDesignation.OFFICIAL_SUPPORT,
        )

        supported.forEach { (serverValue, expected) ->
            val user = requireNotNull(
                adapter.fromJson(
                    """{"id":"user-1","name":"Amina","tag":"amina","verification":{"designation":"$serverValue","since":"2026-08-28T10:00:00Z"}}""",
                ),
            )
            val cached = user.toEntity(nowEpochMillis = 123L)
            val presented = cached.toUiModel().accountVerification

            assertEquals(serverValue, cached.verificationDesignation)
            assertEquals("2026-08-28T10:00:00Z", cached.verificationSince)
            assertEquals(expected, presented?.designation)
            assertEquals("2026-08-28T10:00:00Z", presented?.since)
        }
    }

    @Test
    fun `unknown designation casing padding kyc and display text never create a blue seal`() {
        val adapter = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
            .adapter(UserDto::class.java)

        listOf("Verified", " verified", "official-support", "official_support ", "support", "")
            .forEach { serverValue ->
                val user = requireNotNull(
                    adapter.fromJson(
                        """{"id":"user-1","name":"Official Support ✓","tag":"support-team","kyc_status":"verified","verification":{"designation":"$serverValue","since":"2026-08-28T10:00:00Z"}}""",
                    ),
                )
                val cached = user.toEntity(nowEpochMillis = 123L)

                assertNull(cached.verificationDesignation)
                assertNull(cached.verificationSince)
                assertNull(cached.toUiModel().accountVerification)
            }

        val presentationOnly = UserDto(
            id = "user-1",
            name = "Official Support ✓",
            tag = "verified",
            kycStatus = "verified",
        ).toEntity(nowEpochMillis = 123L)
        assertNull(presentationOnly.toUiModel().accountVerification)
    }

    @Test
    fun `designation alone grants badge while malformed optional grant time is discarded`() {
        val adapter = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
            .adapter(UserDto::class.java)
        val user = requireNotNull(
            adapter.fromJson(
                """{"id":"user-1","name":"Amina","tag":"amina","verification":{"designation":"verified","since":"yesterday"}}""",
            ),
        )

        val cached = user.toEntity(nowEpochMillis = 123L)

        assertEquals("verified", cached.verificationDesignation)
        assertNull(cached.verificationSince)
        assertEquals(
            AccountVerificationDesignation.VERIFIED,
            cached.toUiModel().accountVerification?.designation,
        )
    }

    @Test
    fun `contact verification payload decodes without using the contact name`() {
        val adapter = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
            .adapter(ContactDto::class.java)
        val contact = requireNotNull(
            adapter.fromJson(
                """{"id":"contact-1","name":"Ordinary name","phone":"+256700000001","is_kit_user":true,"verification":{"designation":"official_support","since":"2026-08-28T11:00:00Z"}}""",
            ),
        )
        val mapped = contact.toContactModel()
        val verification = mapped.accountVerification

        assertEquals("Ordinary name", mapped.name)
        assertEquals(AccountVerificationDesignation.OFFICIAL_SUPPORT, verification?.designation)
        assertEquals("2026-08-28T11:00:00Z", verification?.since)

        val unknown = requireNotNull(
            adapter.fromJson(
                """{"id":"contact-2","name":"Official Support ✓","phone":"+256700000002","verification":{"designation":"Official_Support","since":"2026-08-28T11:00:00Z"}}""",
            ),
        )
        assertNull(unknown.toContactModel().accountVerification)

        val unlinked = requireNotNull(
            adapter.fromJson(
                """{"id":"local-contact","name":"Ordinary name","phone":"+256700000003","is_kit_user":false,"verification":{"designation":"verified","since":"2026-08-28T11:00:00Z"}}""",
            ),
        )
        assertNull(unlinked.toContactModel().accountVerification)
    }

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
            "Enter a display name (2–120 characters).",
            profileValidationError("", "amina"),
        )
        assertEquals(
            "Choose the display name people should see.",
            profileValidationError(provisional.name, "amina"),
        )
        assertEquals(
            "Choose your own username.",
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
            "Enter a display name (2–120 characters).",
            profileValidationError("😀".repeat(121), "emoji_name"),
        )
    }

    @Test
    fun `reserved deleted and malformed tags keep profile setup gated`() {
        assertEquals(
            "This username is reserved.",
            profileValidationError("Amina", "support"),
        )
        assertEquals(
            "This username is reserved.",
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
    fun `identity verification is reachable from inside profile setup`() {
        // Verifying is now the first step of setup, so the gate that herds an unfinished account
        // back to the form must not evict it from the flow that finishes the account.
        assertFalse(shouldRequireProfileSetup(true, true, Dest.KYC))
    }

    @Test
    fun `a verified legal name makes the display name and username optional`() {
        assertNull(profileValidationError("", "", "Amina Yusuf"))
        assertNull(profileValidationError("Kit Pay User", "kit_a1b2c3d4e5", "Amina Yusuf"))
        // Optional is not unvalidated: whatever is actually typed still has to be usable.
        assertEquals(
            "This username is reserved.",
            profileValidationError("", "support", "Amina Yusuf"),
        )
        assertEquals(
            "Enter a display name (2–120 characters).",
            profileValidationError("A", "", "Amina Yusuf"),
        )
        // A legal name of whitespace is no legal name at all.
        assertEquals(
            "Enter a display name (2–120 characters).",
            profileValidationError("", "", "   "),
        )
    }

    @Test
    fun `a verified account without a username is not trapped in setup`() {
        val verified = UserDto(
            id = "user-1",
            name = null,
            tag = null,
            legalName = "Amina Yusuf",
            usernameRequired = false,
            profileSetupRequired = false,
        ).toEntity(nowEpochMillis = 123L)

        assertFalse(requiresProfileSetup(null, null, "Amina Yusuf"))
        assertFalse(verified.profileSetupRequired)
        assertFalse(verified.toUiModel().profileSetupRequired)
        // Unverified, the original rule stands: an account with no name at all is not usable.
        assertTrue(requiresProfileSetup(null, null, null))
    }

    @Test
    fun `the legal name is cached separately and never becomes the chosen name`() {
        val verified = UserDto(
            id = "user-1",
            // What the server presents when no display name was chosen: the legal name itself.
            name = "Amina Yusuf",
            tag = null,
            legalName = "Amina Yusuf",
            usernameRequired = false,
        ).toEntity(nowEpochMillis = 123L)
        val profile = verified.toUiModel()

        assertEquals("Amina Yusuf", profile.legalName)
        assertFalse(profile.usernameRequired)
        assertTrue(profile.identityVerified)
        assertEquals("Amina Yusuf", profile.displayIdentityName)
        // The verified name is not offered back as an editable display name, in setup or in
        // Settings: editing it there would silently turn a fallback into a chosen name.
        assertEquals("", profileEditorInitialValues(profile, setup = true).name)
        assertEquals("", profileEditorInitialValues(profile, setup = false).name)
    }

    @Test
    fun `a chosen display name is kept and named as the nickname it is`() {
        val profile = profile(name = "Ash", tag = "ash").copy(
            legalName = "Amina Yusuf",
            usernameRequired = false,
        )

        assertEquals("Amina Yusuf", profile.displayIdentityName)
        assertEquals("Ash", profileEditorInitialValues(profile, setup = false).name)
        assertEquals(
            "Goes by Ash • @ash • +256700000200",
            profileIdentitySubtitle(profile),
        )
        // Nothing to distinguish when the account has no verified name of its own.
        assertEquals(
            "@ash • +256700000200",
            profileIdentitySubtitle(profile(name = "Ash", tag = "ash")),
        )
    }

    @Test
    fun `an older server that reports no username flag is read from the legal name`() {
        val adapter = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
            .adapter(UserDto::class.java)
        val user = requireNotNull(
            adapter.fromJson("""{"id":"user-1","name":"Amina Yusuf","tag":"amina"}"""),
        )

        assertNull(user.legalName)
        assertNull(user.usernameRequired)
        // No verified name reported, so the username stays required rather than being assumed away.
        assertTrue(user.toEntity(nowEpochMillis = 1L).usernameRequired)
    }

    @Test
    fun `dropping a username is sent as an explicit null rather than omitted`() {
        val adapter = Moshi.Builder()
            .add(UpdateProfileRequestAdapter())
            .add(KotlinJsonAdapterFactory())
            .build()
            .adapter(UpdateProfileRequest::class.java)

        assertEquals(
            """{"tag":null}""",
            adapter.toJson(UpdateProfileRequest(clearUsername = true)),
        )
        assertEquals(
            """{"name":"Amina Yusuf","tag":"amina"}""",
            adapter.toJson(UpdateProfileRequest(name = "Amina Yusuf", tag = "amina")),
        )
        // Absent means "leave it alone". A verified user finishing setup without choosing
        // anything sends exactly this, and the server completes the profile on it.
        assertEquals("{}", adapter.toJson(UpdateProfileRequest()))
        // An explicit clear wins over a stale value rather than sending both.
        assertEquals(
            """{"tag":null}""",
            adapter.toJson(UpdateProfileRequest(tag = "amina", clearUsername = true)),
        )
    }

    @Test
    fun `verified email is informational because replacement is unsupported`() {
        val presentation = profileEmailPresentation(
            profile(name = "Amina Yusuf", tag = "amina", email = "amina@example.test", verified = true),
        )

        assertEquals("Email address", presentation.title)
        assertFalse(presentation.subtitle.contains("Verified"))
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
