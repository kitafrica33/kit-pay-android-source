package com.kit.wallet

import com.kit.wallet.data.remote.*
import com.kit.wallet.data.repository.ContactRepository
import com.kit.wallet.data.repository.ScheduledCallRepository
import com.kit.wallet.data.repository.validatedInviteConnection
import com.kit.wallet.data.session.*
import com.kit.wallet.ui.model.Contact
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ScheduledCallRepositoryTest {
    @Test fun `missing scheduling flag prevents create before any mutation`() = runBlocking {
        val fixture = Fixture().apply { features = mapOf("calls" to true) }
        assertTrue(runCatching { fixture.repository.create(request(), fixture.sessions.current().fence(), retry = true) }.isFailure)
        assertEquals(0, fixture.creates.size)
    }

    @Test fun `authenticated preview does not redeem or automatically accept invitation`() = runBlocking {
        val fixture = Fixture()
        val result = fixture.repository.preview(TOKEN)
        assertEquals(CALL, result.call?.id)
        assertEquals(0, fixture.redemptions)
        assertEquals(1, fixture.previews)
        assertTrue(fixture.observedOwners.all { it == fixture.sessions.current().fence() })
    }

    @Test fun `joining uses one capability check and a fresh authenticated preview`() = runBlocking {
        val fixture = Fixture()
        assertEquals(CALL, fixture.repository.redeem(TOKEN).callId)
        assertEquals(1, fixture.capabilityRequests)
        assertEquals(1, fixture.previews)
        assertEquals(1, fixture.redemptions)
    }

    @Test fun `account replacement during preview prevents redeem mutation`() = runBlocking {
        val fixture = Fixture()
        fixture.beforePreviewReturn = { fixture.sessions.replace() }
        assertTrue(runCatching { fixture.repository.redeem(TOKEN) }.exceptionOrNull() is SessionInvalidatedException)
        assertEquals(0, fixture.redemptions)
    }

    @Test fun `account replacement after server redemption discards old credentials`() = runBlocking {
        val fixture = Fixture()
        fixture.beforeRedeemReturn = { fixture.sessions.replace() }
        assertTrue(runCatching { fixture.repository.redeem(TOKEN) }.exceptionOrNull() is SessionInvalidatedException)
        assertEquals(1, fixture.redemptions)
    }

    @Test fun `an old route owner cannot borrow the new account's credentials`() = runBlocking {
        val fixture = Fixture()
        val expected = fixture.sessions.current().fence()
        fixture.sessions.replace()
        assertTrue(runCatching { fixture.repository.redeem(TOKEN, expected) }.exceptionOrNull() is SessionInvalidatedException)
        assertTrue(fixture.observedOwners.isEmpty())
    }

    @Test fun `uncertain create replays identical body even after due time`() = runBlocking {
        val fixture = Fixture()
        val body = request()
        val owner = fixture.sessions.current().fence()
        fixture.repository.create(body, owner, retry = true)
        fixture.repository.create(body, owner, retry = true)
        assertEquals(listOf(body, body), fixture.creates)
    }

    @Test fun `validated invitation maps hold truth and excludes own presentation`() {
        val call = call().copy(
            participantUserIds = listOf(SELF, OTHER),
            participants = listOf(CallParticipantDto(userId = OTHER, name = "Invited contact", state = "active", isHeld = true)),
            canHold = true, holdRevision = 7,
        )
        val connection = validatedInviteConnection(call, rtc(), NOW, CALL, SELF)
        assertEquals(listOf(OTHER), connection.participantUserIds)
        assertEquals("Invited contact", connection.name)
        assertTrue(connection.canHold)
        assertEquals(7L, connection.holdRevision)
        assertTrue(connection.participants.single().isHeld)
    }

    @Test fun `mismatched call expired token and malformed participant never reach RTC`() {
        assertTrue(runCatching { validatedInviteConnection(call(), rtc(), NOW, OTHER, SELF) }.isFailure)
        assertTrue(runCatching { validatedInviteConnection(call(), rtc().copy(expiresAt = NOW), NOW, CALL, SELF) }.isFailure)
        assertTrue(runCatching { validatedInviteConnection(call().copy(participantUserIds = listOf("1-1-1-1-1")), rtc(), NOW, CALL, SELF) }.isFailure)
        assertTrue(runCatching { validatedInviteConnection(call(), rtc().copy(url = "wss://secret@rtc.kit.africa"), NOW, CALL, SELF) }.isFailure)
        assertTrue(runCatching { validatedInviteConnection(call().copy(state = "ended"), rtc(), NOW, CALL, SELF) }.isFailure)
        assertTrue(runCatching { validatedInviteConnection(call().copy(participantState = "invited"), rtc(), NOW, CALL, SELF) }.isFailure)
        assertTrue(runCatching { validatedInviteConnection(call().copy(isHeld = true), rtc(), NOW, CALL, SELF) }.isFailure)
    }

    @Test fun `scheduled request and page use backend snake case contract`() {
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val requestAdapter = moshi.adapter(CreateScheduledCallRequest::class.java)
        val json = requestAdapter.toJson(request())
        assertTrue(json.contains("\"client_schedule_id\""))
        assertTrue(json.contains("\"recipient_user_ids\""))
        assertTrue(json.contains("\"starts_at\""))
        assertFalse(json.contains("clientScheduleId"))
        val pageAdapter = moshi.adapter(ScheduledCallsPageDto::class.java)
        val page = ScheduledCallsPageDto(listOf(schedule()), CursorPageDto("opaque", true, 50))
        assertEquals(page, pageAdapter.fromJson(pageAdapter.toJson(page)))
    }

    private class Fixture {
        val sessions = TestSessions()
        var features: Map<String, Boolean?> = mapOf("calls" to true, "calls_scheduling" to true, "calls_invite_links" to true)
        var beforePreviewReturn: () -> Unit = {}
        var beforeRedeemReturn: () -> Unit = {}
        var previews = 0
        var capabilityRequests = 0
        var redemptions = 0
        val creates = mutableListOf<CreateScheduledCallRequest>()
        val observedOwners = mutableListOf<SessionFence>()
        private val api = Proxy.newProxyInstance(ScheduledCallsApi::class.java.classLoader, arrayOf(ScheduledCallsApi::class.java)) { _, method, args ->
            args?.filterIsInstance<SessionFence>()?.let(observedOwners::addAll)
            when (method.name) {
                "capabilities" -> {
                    capabilityRequests++
                    ApiEnvelope(true, CapabilitiesDto(currency = CurrencyDto("UGX", "2"), features = features))
                }
                "preview" -> {
                    previews++
                    beforePreviewReturn()
                    ApiEnvelope(true, CallInvitePreviewDto("call", call = call(), expiresAt = EXPIRY, serverTime = NOW))
                }
                "redeem" -> {
                    redemptions++
                    beforeRedeemReturn()
                    ApiEnvelope(true, RedeemedCallInviteDto("call", call = call(), rtc = rtc(), serverTime = NOW))
                }
                "create" -> {
                    creates += args!![0] as CreateScheduledCallRequest
                    ApiEnvelope(true, schedule())
                }
                else -> error("Unexpected scheduled call request: ${method.name}")
            }
        } as ScheduledCallsApi
        val repository = ScheduledCallRepository(api, ApiCallExecutor(Moshi.Builder().add(KotlinJsonAdapterFactory()).build()), sessions,
            object : ContactRepository {
                override val contacts: StateFlow<List<Contact>> = MutableStateFlow(emptyList())
                override suspend fun refresh() = Unit
                override suspend fun syncDeviceContacts() = error("Must not request address-book sync")
            })
    }

    private class TestSessions : SessionStore {
        override val session = MutableStateFlow<SessionTokens?>(SessionTokens("access", "refresh", "session", accountId = SELF))
        override fun current() = requireNotNull(session.value)
        fun replace() { session.value = SessionTokens("other-access", "other-refresh", "other-session", accountId = OTHER) }
        override fun snapshot() = SessionSnapshot(0, current().fence())
        override suspend fun save(tokens: SessionTokens) { session.value = tokens }
        override suspend fun saveIfUnchanged(expected: SessionSnapshot, tokens: SessionTokens) = false
        override suspend fun updateProfileSetupState(expected: SessionFence, state: ProfileSetupState) = false
        override suspend fun <T> withCurrentSession(expected: SessionFence, block: suspend (SessionTokens) -> T): T {
            if (expected != current().fence()) throw SessionInvalidatedException()
            return block(current()).also { if (expected != current().fence()) throw SessionInvalidatedException() }
        }
        override suspend fun clearIfCurrent(expected: SessionFence) = false
        override suspend fun clear() { session.value = null }
    }

    private companion object {
        const val SELF = "550e8400-e29b-41d4-a716-446655440000"
        const val OTHER = "550e8400-e29b-41d4-a716-446655440001"
        const val CALL = "550e8400-e29b-41d4-a716-446655440002"
        const val SCHEDULE = "550e8400-e29b-41d4-a716-446655440003"
        const val CLIENT = "550e8400-e29b-41d4-a716-446655440004"
        val TOKEN = "a".repeat(64)
        const val NOW = "2026-09-07T12:00:00Z"
        const val EXPIRY = "2026-09-07T12:10:00Z"
        fun request() = CreateScheduledCallRequest(CLIENT, listOf(OTHER), "voice", "2020-01-01T12:00:00Z", "Call")
        fun schedule() = ScheduledCallDto(SCHEDULE, CLIENT, SELF, "Call", "voice", startsAt = "2020-01-01T12:00:00Z", status = "started", revision = 1, callId = CALL, serverTime = NOW)
        fun call() = CallDto(id = CALL, participantUserIds = listOf(OTHER), direction = "incoming", type = "voice", state = "active", participantState = "joined", isHeld = false, startedAt = NOW, answeredAt = NOW, serverTime = NOW)
        fun rtc() = RtcCredentialsDto("livekit", "wss://rtc.kit.africa", "room-token", "call-room", expiresAt = EXPIRY)
    }
}
