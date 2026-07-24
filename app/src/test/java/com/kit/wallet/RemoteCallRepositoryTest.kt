package com.kit.wallet

import com.kit.wallet.data.remote.ApiCallExecutor
import com.kit.wallet.data.remote.ApiEnvelope
import com.kit.wallet.data.remote.CallDto
import com.kit.wallet.data.remote.CallPageDto
import com.kit.wallet.data.remote.CallSessionDto
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.remote.RtcCredentialsDto
import com.kit.wallet.data.repository.ContactRepository
import com.kit.wallet.data.repository.RemoteCallRepository
import com.kit.wallet.data.session.SessionStore
import com.kit.wallet.data.session.SessionTokens
import com.kit.wallet.ui.model.Contact
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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

        assertEquals(2, api.startedCalls)
        assertEquals(2, api.endedCalls)
        assertEquals(4, api.callListRequests)
        assertEquals(0, contacts.refreshRequests)
        assertEquals("Saved locally", first.name)
        assertEquals("Saved locally", second.name)
        assertEquals(RECIPIENT_PHONE, first.phone)
        assertEquals(RECIPIENT_PHONE, second.phone)
        assertEquals("2026-07-23T00:00:45Z", first.ringExpiresAt)
        assertEquals("2026-07-23T00:00:45Z", second.ringExpiresAt)
    }

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

        val proxy: KitWalletApi = Proxy.newProxyInstance(
            KitWalletApi::class.java.classLoader,
            arrayOf(KitWalletApi::class.java),
        ) { instance, method, arguments ->
            when (method.name) {
                "startCall" -> ApiEnvelope(ok = true, data = callSession(++startedCalls))
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
    }

    private fun sessionStore(): SessionStore {
        val session = MutableStateFlow<SessionTokens?>(null)
        return Proxy.newProxyInstance(
            SessionStore::class.java.classLoader,
            arrayOf(SessionStore::class.java),
        ) { instance, method, arguments ->
            when (method.name) {
                "getSession" -> session
                "toString" -> "EmptySessionStore"
                "hashCode" -> System.identityHashCode(instance)
                "equals" -> instance === arguments?.firstOrNull()
                else -> error("Unexpected session call: ${method.name}")
            }
        } as SessionStore
    }

    private companion object {
        const val RECIPIENT_ID = "550e8400-e29b-41d4-a716-446655440000"
        const val RECIPIENT_PHONE = "+256 700 000 001"

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
