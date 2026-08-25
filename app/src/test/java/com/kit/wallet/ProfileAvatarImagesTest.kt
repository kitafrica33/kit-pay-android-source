package com.kit.wallet

import com.kit.wallet.data.media.isTrustedProfileAvatarUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileAvatarImagesTest {
    private val apiHost = BuildConfig.KIT_WALLET_BASE_URL.toHttpUrl().host

    @Test
    fun `a photo served by the api is fetched`() {
        assertTrue(
            isTrustedProfileAvatarUrl(
                "https://$apiHost/profile/8f14e45f-ceea-467a-9a5c-1f1b0f26c3e2/" +
                    "avatar/2b1f9c00-4d6e-4b8a-9c1d-77b2e0a4c9d1",
            ),
        )
        // A deployment that fronts photos from a subdomain of the API is the same operator.
        assertTrue(isTrustedProfileAvatarUrl("https://media.$apiHost/avatar.jpg"))
    }

    @Test
    fun `a photo url pointing anywhere else is not fetched`() {
        // An avatar URL is an instruction to make a request, and this is the whole point of the
        // check: a tampered response must not be able to make every install call a stranger's host.
        assertFalse(isTrustedProfileAvatarUrl("https://tracker.example.net/beacon.gif"))
        // Suffix matching without the dot would have accepted this one.
        assertFalse(isTrustedProfileAvatarUrl("https://evil-$apiHost/avatar.jpg"))
        assertFalse(isTrustedProfileAvatarUrl("https://$apiHost.example.net/avatar.jpg"))
    }

    @Test
    fun `a photo is never fetched over plaintext`() {
        assertFalse(isTrustedProfileAvatarUrl("http://$apiHost/avatar.jpg"))
    }

    @Test
    fun `an absent or unparseable url simply means no photo`() {
        // Not an error state: the initials underneath are already a complete avatar.
        listOf(null, "", "   ", "not a url", "javascript:alert(1)", "file:///etc/passwd").forEach {
            assertFalse("accepted $it", isTrustedProfileAvatarUrl(it))
        }
    }
}
