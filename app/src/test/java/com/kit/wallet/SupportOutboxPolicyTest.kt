package com.kit.wallet

import com.kit.wallet.data.remote.KitWalletApiException
import com.kit.wallet.data.support.isDefinitiveSupportRejection
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The retry taxonomy behind the durable outbox (docs/support-client.md O3/O4):
 * only a definitive endpoint answer may stop retries of an idempotent draft,
 * because for every other failure the server may have committed the write and
 * only a same-key replay can find out.
 */
class SupportOutboxPolicyTest {
    private fun failure(
        statusCode: Int?,
        connectivity: Boolean = false,
        code: String = "TEST_FAILURE",
    ) = KitWalletApiException(
        code = code,
        message = "test",
        statusCode = statusCode,
        connectivity = connectivity,
    )

    @Test
    fun `validation and state rejections are definitive`() {
        listOf(400, 404, 409, 410, 413, 422).forEach { status ->
            assertTrue("HTTP $status", isDefinitiveSupportRejection(failure(status)))
        }
    }

    @Test
    fun `connectivity failures never count even when a status is attached`() {
        assertFalse(isDefinitiveSupportRejection(failure(null, connectivity = true)))
        // Defensive: classification wins over any status a wrapper might carry.
        assertFalse(isDefinitiveSupportRejection(failure(422, connectivity = true)))
    }

    @Test
    fun `a failure without a status code is not an endpoint answer`() {
        assertFalse(isDefinitiveSupportRejection(failure(null)))
    }

    @Test
    fun `auth timing assurance and throttle boundaries keep the draft pending`() {
        // 401/403 are session boundaries, 408 never confirms receipt, 425/428
        // are try-again-with-state, 429 is explicit throttling.
        listOf(401, 403, 408, 425, 428, 429).forEach { status ->
            assertFalse("HTTP $status", isDefinitiveSupportRejection(failure(status)))
        }
    }

    @Test
    fun `server-side failures keep the draft pending under the same key`() {
        listOf(500, 502, 503, 504).forEach { status ->
            assertFalse("HTTP $status", isDefinitiveSupportRejection(failure(status)))
        }
    }

    @Test
    fun `statuses outside the 4xx range are never definitive`() {
        listOf(200, 300, 399).forEach { status ->
            assertFalse("HTTP $status", isDefinitiveSupportRejection(failure(status)))
        }
    }
}
