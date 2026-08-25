package com.kit.wallet

import com.kit.wallet.data.remote.KitWalletApiException
import com.kit.wallet.data.repository.AbuseReportAttemptStore
import com.kit.wallet.data.repository.AbuseReportReason
import com.kit.wallet.data.repository.AbuseReportReceipt
import com.kit.wallet.data.repository.AbuseReportRequest
import com.kit.wallet.data.repository.AbuseReportTargetType
import com.kit.wallet.data.repository.AbuseReportingRepository
import com.kit.wallet.data.session.SessionInvalidatedException
import com.kit.wallet.feature.chat.AbuseReportViewModel
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AbuseReportViewModelTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `uncertain retry reuses persisted idempotency key and success clears it`() = runTest {
        val reports = RecordingReports(
            outcomes = ArrayDeque(
                listOf(
                    Result.failure(KitWalletApiException("HTTP_503", "unavailable", statusCode = 503)),
                    Result.success(receipt()),
                ),
            ),
        )
        val attempts = RecordingAttempts()
        val viewModel = AbuseReportViewModel(reports, attempts)

        viewModel.submit(request(), reportingAvailable = true)
        assertEquals("Kit Pay could not submit this report. Please try again.", viewModel.state.value.error)
        assertFalse(viewModel.state.value.submitting)

        viewModel.submit(request(), reportingAvailable = true)

        assertEquals(2, reports.keys.size)
        assertEquals(reports.keys[0], reports.keys[1])
        assertEquals(REPORT, viewModel.state.value.receipt?.id)
        assertEquals(1, attempts.completed.size)
    }

    @Test
    fun `editing a failed report gets a different replay key`() = runTest {
        val reports = RecordingReports(
            outcomes = ArrayDeque(
                listOf(
                    Result.failure(IllegalStateException("first failed")),
                    Result.failure(IllegalStateException("second failed")),
                ),
            ),
        )
        val viewModel = AbuseReportViewModel(reports, RecordingAttempts())

        viewModel.submit(request(), reportingAvailable = true)
        viewModel.submit(request().copy(reporterNote = "different"), reportingAvailable = true)

        assertEquals(2, reports.keys.size)
        assertNotEquals(reports.keys[0], reports.keys[1])
    }

    @Test
    fun `disabled capability refuses locally without calling the safety endpoint`() = runTest {
        val reports = RecordingReports(ArrayDeque(listOf(Result.success(receipt()))))
        val viewModel = AbuseReportViewModel(reports, RecordingAttempts())

        viewModel.submit(request(), reportingAvailable = false)

        assertTrue(viewModel.state.value.error.orEmpty().contains("temporarily unavailable"))
        assertTrue(reports.keys.isEmpty())
        assertNull(viewModel.state.value.receipt)
    }

    @Test
    fun `rate limit and unavailable target use safe actionable copy`() = runTest {
        val reports = RecordingReports(
            ArrayDeque(
                listOf(
                    Result.failure(
                        KitWalletApiException("RATE_LIMITED", "raw", statusCode = 429),
                    ),
                    Result.failure(
                        KitWalletApiException("REPORT_TARGET_UNAVAILABLE", "raw", statusCode = 404),
                    ),
                ),
            ),
        )
        val viewModel = AbuseReportViewModel(reports, RecordingAttempts())

        viewModel.submit(request(), reportingAvailable = true)
        assertTrue(viewModel.state.value.error.orEmpty().startsWith("Too many reports"))
        viewModel.submit(request().copy(reason = AbuseReportReason.SPAM), reportingAvailable = true)
        assertTrue(viewModel.state.value.error.orEmpty().contains("no longer available"))
    }

    @Test
    fun `session replacement asks the reporter to sign in again`() = runTest {
        val reports = RecordingReports(
            ArrayDeque(listOf(Result.failure(SessionInvalidatedException()))),
        )
        val viewModel = AbuseReportViewModel(reports, RecordingAttempts())

        viewModel.submit(request(), reportingAvailable = true)

        assertEquals("Sign in again before submitting this report.", viewModel.state.value.error)
    }

    private class RecordingReports(
        private val outcomes: ArrayDeque<Result<AbuseReportReceipt>>,
    ) : AbuseReportingRepository {
        val keys = mutableListOf<String>()

        override suspend fun submit(
            request: AbuseReportRequest,
            idempotencyKey: String,
        ): AbuseReportReceipt {
            keys += idempotencyKey
            return outcomes.removeFirst().getOrThrow()
        }
    }

    private class RecordingAttempts : AbuseReportAttemptStore {
        private val keys = mutableMapOf<Pair<String, String>, String>()
        val completed = mutableListOf<String>()

        override fun keyFor(accountId: String, requestFingerprint: String): String =
            keys.getOrPut(accountId to requestFingerprint) {
                "android-abuse-report-test-${keys.size.toString().padStart(16, '0')}"
            }

        override fun complete(
            accountId: String,
            requestFingerprint: String,
            idempotencyKey: String,
        ) {
            completed += idempotencyKey
            keys.remove(accountId to requestFingerprint)
        }
    }

    private fun request() = AbuseReportRequest(
        reporterUserId = CURRENT,
        targetType = AbuseReportTargetType.MESSAGE,
        reportedUserId = REPORTED,
        conversationId = CONVERSATION,
        messageId = MESSAGE,
        reason = AbuseReportReason.HARASSMENT_OR_BULLYING,
        reporterNote = "context",
        selectedMessages = emptyList(),
    )

    private fun receipt() = AbuseReportReceipt(
        id = REPORT,
        targetType = AbuseReportTargetType.MESSAGE,
        reason = AbuseReportReason.HARASSMENT_OR_BULLYING,
        conversationId = CONVERSATION,
        messageId = MESSAGE,
        selectedMessageCount = 0,
        submittedAt = Instant.parse("2026-08-25T12:00:00Z"),
    )

    private companion object {
        const val CURRENT = "11111111-1111-4111-8111-111111111111"
        const val REPORTED = "22222222-2222-4222-8222-222222222222"
        const val CONVERSATION = "55555555-5555-4555-8555-555555555555"
        const val MESSAGE = "66666666-6666-4666-8666-666666666666"
        const val REPORT = "77777777-7777-4777-8777-777777777777"
    }
}
