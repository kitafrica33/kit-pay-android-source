package com.kit.wallet

import com.kit.wallet.feature.settings.KIT_PRIVACY_POLICY_URL
import com.kit.wallet.feature.settings.KIT_ACCOUNT_DELETION_URL
import com.kit.wallet.feature.settings.accountDeletionPresentation
import com.kit.wallet.feature.settings.isTrustedKitAccountDeletionUrl
import com.kit.wallet.feature.settings.isTrustedKitPrivacyPolicyUrl
import com.kit.wallet.feature.legal.isTrustedKitReleaseSourceUrl
import com.kit.wallet.feature.legal.openSourceLicencePresentation
import com.kit.wallet.feature.settings.privacyPolicyPresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsPolicyLinkTest {
    @Test
    fun `settings privacy row presents the canonical public policy`() {
        val presentation = privacyPolicyPresentation()

        assertEquals("Privacy policy", presentation.title)
        assertEquals("Learn how Kit Pay handles your data", presentation.subtitle)
        assertEquals(KIT_PRIVACY_POLICY_URL, presentation.url)
        assertTrue(isTrustedKitPrivacyPolicyUrl(presentation.url))
    }

    @Test
    fun `privacy link rejects downgraded or ambiguous destinations`() {
        assertFalse(isTrustedKitPrivacyPolicyUrl("http://pay.kit.africa/privacy"))
        assertFalse(isTrustedKitPrivacyPolicyUrl("https://pay.kit.africa.evil.test/privacy"))
        assertFalse(isTrustedKitPrivacyPolicyUrl("https://user@pay.kit.africa/privacy"))
        assertFalse(isTrustedKitPrivacyPolicyUrl("https://pay.kit.africa/privacy?next=evil"))
        assertFalse(isTrustedKitPrivacyPolicyUrl("https://pay.kit.africa/privacy#other"))
    }

    @Test
    fun `open source notice identifies licence warranty and exact release source`() {
        val presentation = openSourceLicencePresentation(versionName = "0.2.0", versionCode = 11)

        assertTrue(presentation.notice.contains("Copyright (C) 2026 KIT POS UGANDA LIMITED"))
        assertTrue(presentation.notice.contains("AGPL version 3 only"))
        assertTrue(presentation.notice.contains("without any warranty"))
        assertEquals(
            "https://github.com/kitafrica33/kit-pay-android-source/releases/tag/v0.2.0-code11",
            presentation.sourceUrl,
        )
        assertTrue(isTrustedKitReleaseSourceUrl(presentation.sourceUrl))
    }

    @Test
    fun `release source link rejects downgraded or ambiguous destinations`() {
        assertFalse(
            isTrustedKitReleaseSourceUrl(
                "http://github.com/kitafrica33/kit-pay-android-source/releases/tag/v0.2.0-code11",
            ),
        )
        assertFalse(
            isTrustedKitReleaseSourceUrl(
                "https://github.com.evil.test/kitafrica33/kit-pay-android-source/releases/tag/v0.2.0-code11",
            ),
        )
        assertFalse(
            isTrustedKitReleaseSourceUrl(
                "https://github.com/kitafrica33/kit-pay-android-source/releases/tag/v0.2.0-code11?next=evil",
            ),
        )
        assertFalse(
            isTrustedKitReleaseSourceUrl(
                "https://github.com/kitafrica33/kit-pay-android-source/releases/tag/latest",
            ),
        )
    }

    @Test
    fun `delete account stays visible with protected and public fallback presentations`() {
        val protected = accountDeletionPresentation(inAppAvailable = true)
        val fallback = accountDeletionPresentation(inAppAvailable = false)

        assertEquals("Delete account", protected.title)
        assertTrue(protected.usesProtectedInAppFlow)
        assertTrue(protected.subtitle.contains("in-app"))
        assertFalse(fallback.usesProtectedInAppFlow)
        assertTrue(fallback.subtitle.contains("support-assisted"))
        assertEquals(KIT_ACCOUNT_DELETION_URL, fallback.publicUrl)
        assertTrue(isTrustedKitAccountDeletionUrl(fallback.publicUrl))
    }

    @Test
    fun `delete account fallback rejects downgraded or ambiguous destinations`() {
        assertFalse(isTrustedKitAccountDeletionUrl("http://pay.kit.africa/account-deletion"))
        assertFalse(isTrustedKitAccountDeletionUrl("https://pay.kit.africa.evil.test/account-deletion"))
        assertFalse(isTrustedKitAccountDeletionUrl("https://user@pay.kit.africa/account-deletion"))
        assertFalse(isTrustedKitAccountDeletionUrl("https://pay.kit.africa/account-deletion?next=evil"))
        assertFalse(isTrustedKitAccountDeletionUrl("https://pay.kit.africa/account-deletion#other"))
    }
}
