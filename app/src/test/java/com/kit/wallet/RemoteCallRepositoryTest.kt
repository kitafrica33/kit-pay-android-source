package com.kit.wallet

import com.kit.wallet.data.remote.ApiCallExecutor
import com.kit.wallet.data.remote.ApiEnvelope
import com.kit.wallet.data.remote.CallDto
import com.kit.wallet.data.remote.CallPageDto
import com.kit.wallet.data.remote.CallParticipantDto
import com.kit.wallet.data.remote.AccountVerificationDto
import com.kit.wallet.data.remote.CallSessionDto
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.remote.RtcCredentialsDto
import com.kit.wallet.data.remote.StartCallRequest
import com.kit.wallet.data.repository.ContactRepository
import com.kit.wallet.data.repository.RemoteCallRepository
import com.kit.wallet.data.session.SessionStore
import com.kit.wallet.data.session.SessionTokens
import com.kit.wallet.data.session.SessionFence
import com.kit.wallet.data.session.SessionSnapshot
import com.kit.wallet.data.session.ProfileSetupState
import com.kit.wallet.ui.model.Contact
import com.kit.wallet.ui.model.AccountVerificationDesignation
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteCallRepositoryTest {
    @Test
    fun `consecutive outgoing calls reuse loaded contacts without refreshing address book`() = runTest {
        val api = RecordingCallApi()
        val contacts = RecordingContactRepository()
        val repository = RemoteCallRepository(
            api = api.proxy,
            apiCalls = ApiCallExecutor(
                Moshi.Builder().add(KotlinJsonAdapterFactory()).build(),
            ),
            contacts = contacts,
            sessions = sessionStore(),
            scope = backgroundScope,
        )

        val first = repository.start(RECIPIENT_ID, video = false)
        repository.end(first.callId)
        val second = repository.start(RECIPIENT_ID, video = true)
        repository.end(second.callId)

        runCurrent()

        assertEquals(2, api.startedCalls)
        assertEquals(2, api.endedCalls)
        // Two awaited walks from ending the calls, and one background walk: the redial
        // cancels and replaces the walk its predecessor started rather than stacking them.
        assertEquals(3, api.callListRequests)
        assertEquals(0, contacts.refreshRequests)
        assertEquals("Saved locally", first.name)
        assertEquals("Saved locally", second.name)
        assertEquals(RECIPIENT_PHONE, first.phone)
        assertEquals(RECIPIENT_PHONE, second.phone)
        assertEquals("2026-07-23T00:00:45Z", first.ringExpiresAt)
        assertEquals("2026-07-23T00:00:45Z", second.ringExpiresAt)
        assertEquals(2, api.clientCallIds.distinct().size)
    }

    @Test
    fun `replayed call attempt keeps the caller supplied idempotency identity`() = runTest {
        val api = RecordingCallApi()
        val repository = RemoteCallRepository(
            api = api.proxy,
            apiCalls = ApiCallExecutor(Moshi.Builder().add(KotlinJsonAdapterFactory()).build()),
            contacts = RecordingContactRepository(),
            sessions = sessionStore(),
            scope = backgroundScope,
        )

        val clientCallId = "8d17fded-b512-4c2c-88cd-700657ca39f4"
        repository.start(RECIPIENT_ID, video = false, clientCallId = clientCallId)
        repository.start(RECIPIENT_ID, video = false, clientCallId = clientCallId)
        repository.cancelAttempt(clientCallId)

        assertEquals(listOf(clientCallId, clientCallId), api.clientCallIds)
        assertEquals(listOf(clientCallId), api.cancelledClientCallIds)
    }

    @Test
    fun `placing a call hands back room credentials before the history walk starts`() = runTest {
        // The call log is paginated to exhaustion, so awaiting it here put several round
        // trips between the tap and the first audio packet. Nothing on the call screen
        // reads the log, so the walk runs behind the connection instead of in front of it.
        val api = RecordingCallApi()
        val repository = repository(api)

        repository.start(RECIPIENT_ID, video = false)

        assertEquals(0, api.callListRequests)

        runCurrent()

        assertEquals(1, api.callListRequests)
    }

    @Test
    fun `answering hands back room credentials before the history walk starts`() = runTest {
        val api = RecordingCallApi()
        val repository = repository(api)

        repository.accept(INCOMING_CALL_ID)

        assertEquals(0, api.callListRequests)

        runCurrent()

        assertEquals(1, api.callListRequests)
    }

    @Test
    fun `the accept response carries the server's answer instant to the call screen`() = runTest {
        // Both halves of the anchor come from the same response, measured on one clock, so
        // the timer the answering device shows never inherits the phone's own drift.
        val api = RecordingCallApi()
        val repository = repository(api)

        val connection = repository.accept(INCOMING_CALL_ID)

        assertEquals(INCOMING_CALL_ID, connection.callId)
        assertEquals(ANSWERED_AT, connection.answeredAt)
        assertEquals(SERVER_TIME, connection.serverTime)
    }

    @Test
    fun `call connection keeps structured first sighting metadata beside legacy ids`() = runTest {
        val api = RecordingCallApi().apply {
            structuredParticipants = listOf(
                CallParticipantDto(
                    userId = RECIPIENT_ID.uppercase(),
                    name = "Registered name",
                    avatarUrl = "https://pay.kit.africa/media/a1",
                    verification = AccountVerificationDto(
                        "official_support",
                        "2026-08-29T10:11:12Z",
                    ),
                ),
            )
        }
        val repository = repository(api)

        val connection = repository.start(RECIPIENT_ID, video = false)

        // The address-book alias is presentation only. Photo and badge remain bound to the exact
        // structured participant ID returned with the call.
        assertEquals("Saved locally", connection.name)
        assertEquals(listOf(RECIPIENT_ID), connection.participantUserIds)
        assertEquals(RECIPIENT_ID, connection.participants.single().userId)
        assertEquals("https://pay.kit.africa/media/a1", connection.avatarUrl)
        assertEquals(
            AccountVerificationDesignation.OFFICIAL_SUPPORT,
            connection.accountVerification?.designation,
        )
    }

    @Test
    fun `answer rejects credentials returned for a different call`() = runTest {
        val api = RecordingCallApi().apply {
            acceptedCallIdOverride = "019f8c6f-cc57-720c-9a55-0000000000ee"
        }
        val repository = repository(api)

        val result = runCatching { repository.accept(INCOMING_CALL_ID) }

        assertTrue(result.isFailure)
        assertEquals(0, api.callListRequests)
    }

    @Test
    fun `a call that has not been answered carries no anchor`() = runTest {
        val api = RecordingCallApi()
        val repository = repository(api)

        val connection = repository.start(RECIPIENT_ID, video = false)

        assertNull(connection.answeredAt)
        assertNull(connection.serverTime)
    }

    private fun kotlinx.coroutines.test.TestScope.repository(api: RecordingCallApi) =
        RemoteCallRepository(
            api = api.proxy,
            apiCalls = ApiCallExecutor(Moshi.Builder().add(KotlinJsonAdapterFactory()).build()),
            contacts = RecordingContactRepository(),
            sessions = sessionStore(),
            scope = backgroundScope,
        )

    private class RecordingContactRepository : ContactRepository {
        override val contacts: StateFlow<List<Contact>> = MutableStateFlow(
            listOf(
                Contact(
                    id = RECIPIENT_ID,
                    name = "Saved locally",
                    phone = RECIPIENT_PHONE,
                    registeredName = "Registered name",
                    savedInDevice = true,
                ),
            ),
        )
        var refreshRequests = 0
            private set

        override suspend fun refresh() {
            refreshRequests += 1
        }

        override suspend fun syncDeviceContacts() = error("Outgoing calls must not sync contacts")
    }

    private class RecordingCallApi {
        var startedCalls = 0
            private set
        var endedCalls = 0
            private set
        var callListRequests = 0
            private set
        var acceptedCalls = 0
            private set
        var acceptedCallIdOverride: String? = null
        var structuredParticipants: List<CallParticipantDto?>? = null
        val clientCallIds = mutableListOf<String>()
        val cancelledClientCallIds = mutableListOf<String>()

        val proxy: KitWalletApi = Proxy.newProxyInstance(
            KitWalletApi::class.java.classLoader,
            arrayOf(KitWalletApi::class.java),
        ) { instance, method, arguments ->
            when (method.name) {
                "startCall" -> {
                    clientCallIds += (arguments?.first() as StartCallRequest).clientCallId.orEmpty()
                    ApiEnvelope(ok = true, data = withStructuredParticipants(callSession(++startedCalls)))
                }
                "cancelCallAttempt" -> {
                    val id = arguments?.first() as String
                    cancelledClientCallIds += id
                    ApiEnvelope(
                        ok = true,
                        data = com.kit.wallet.data.remote.CancelCallAttemptDto(id, true),
                    )
                }
                "acceptCall" -> {
                    acceptedCalls += 1
                    ApiEnvelope(
                        ok = true,
                        data = withStructuredParticipants(
                            answeredSession(acceptedCallIdOverride ?: INCOMING_CALL_ID),
                        ),
                    )
                }
                "endCall" -> ApiEnvelope(
                    ok = true,
                    data = callSession(++endedCalls).call.copy(state = "ended"),
                )
                "calls" -> {
                    callListRequests += 1
                    ApiEnvelope(ok = true, data = CallPageDto(items = emptyList()))
                }
                "toString" -> "RecordingCallApi"
                "hashCode" -> System.identityHashCode(instance)
                "equals" -> instance === arguments?.firstOrNull()
                else -> error("Unexpected API call: ${method.name}")
            }
        } as KitWalletApi

        private fun withStructuredParticipants(session: CallSessionDto): CallSessionDto =
            session.copy(call = session.call.copy(participants = structuredParticipants))
    }

    private fun sessionStore(): SessionStore {
        val tokens = SessionTokens("access", "refresh", "session")
        return object : SessionStore {
            override val session: StateFlow<SessionTokens?> = MutableStateFlow(null)
            override fun current(): SessionTokens = tokens
            override fun snapshot() = SessionSnapshot(0, tokens.fence())
            override suspend fun save(tokens: SessionTokens) = Unit
            override suspend fun saveIfUnchanged(
                expected: SessionSnapshot,
                tokens: SessionTokens,
            ) = true
            override suspend fun updateProfileSetupState(
                expected: SessionFence,
                state: ProfileSetupState,
            ) = true
            override suspend fun <T> withCurrentSession(
                expected: SessionFence,
                block: suspend (SessionTokens) -> T,
            ): T {
                check(expected == tokens.fence())
                return block(tokens)
            }
            override suspend fun clearIfCurrent(expected: SessionFence) = false
            override suspend fun clear() = Unit
        }
    }

    private companion object {
        const val RECIPIENT_ID = "550e8400-e29b-41d4-a716-446655440000"
        const val RECIPIENT_PHONE = "+256 700 000 001"
        const val INCOMING_CALL_ID = "019f8c6f-cc57-720c-9a55-0000000000ff"
        const val ANSWERED_AT = "2026-07-23T00:00:10Z"
        const val SERVER_TIME = "2026-07-23T00:00:11Z"

        fun answeredSession(callId: String = INCOMING_CALL_ID) = CallSessionDto(
            call = CallDto(
                id = callId,
                name = "Registered name",
                participantUserIds = listOf(RECIPIENT_ID),
                direction = "incoming",
                type = "voice",
                video = false,
                state = "active",
                startedAt = "2026-07-23T00:00:00Z",
                answeredAt = ANSWERED_AT,
                ringExpiresAt = "2026-07-23T00:00:45Z",
            ),
            rtc = RtcCredentialsDto(
                provider = "livekit",
                url = "wss://rtc.pay.kit.africa",
                token = "accepted-token",
                room = "accepted-room",
                expiresAt = "2026-07-23T00:05:00Z",
            ),
            serverTime = SERVER_TIME,
        )

        fun callSession(sequence: Int) = CallSessionDto(
            call = CallDto(
                id = "019f8c6f-cc57-720c-9a55-${sequence.toString().padStart(12, '0')}",
                name = "Registered name",
                participantUserIds = listOf(RECIPIENT_ID),
                direction = "outgoing",
                type = if (sequence == 1) "voice" else "video",
                video = sequence != 1,
                state = "ringing",
                startedAt = "2026-07-23T00:00:00Z",
                ringExpiresAt = "2026-07-23T00:00:45Z",
            ),
            rtc = RtcCredentialsDto(
                provider = "livekit",
                url = "wss://rtc.pay.kit.africa",
                token = "test-token-$sequence",
                room = "test-room-$sequence",
                expiresAt = "2026-07-23T00:05:00Z",
            ),
        )
    }
}
