package com.kit.wallet

import com.kit.wallet.data.notifications.*
import com.kit.wallet.data.remote.ApiCallExecutor
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.remote.KitWalletApiException
import com.kit.wallet.data.remote.SessionHeaderInterceptor
import com.kit.wallet.data.session.SessionFence
import com.kit.wallet.data.session.SessionInvalidatedException
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class NotificationRecoveryTest {
    private val sessions = MutableTestSessionStore(testSession(ACCOUNT))
    private val store = MemoryReceipts()
    private val seen = mutableListOf<String>()
    private lateinit var server: MockWebServer
    private lateinit var api: KitWalletApi
    private lateinit var calls: ApiCallExecutor
    private val sink = object : NotificationInboxAlertSink {
        override suspend fun recoverAlert(owner: SessionFence, envelope: PushEnvelope): Boolean {
            val key = checkNotNull(envelope.data["notification_id"])
            return NotificationAlertDelivery(sessions, store).deliver(
                owner, key, { true }, { false }, display = { seen.add(key) },
            )
        }
    }

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        calls = ApiCallExecutor(moshi)
        api = Retrofit.Builder().baseUrl(server.url("/"))
            .client(OkHttpClient.Builder().addInterceptor(SessionHeaderInterceptor(sessions)).build())
            .addConverterFactory(MoshiConverterFactory.create(moshi)).build().create(KitWalletApi::class.java)
    }

    @After fun tearDown() { server.shutdown() }

    private fun recovery(alertSink: NotificationInboxAlertSink = sink) =
        NotificationInboxRecovery(api, calls, sessions, store, alertSink)

    @Test fun `older continuation survives restart while each pass first checks the newest head`() = runTest {
        server.enqueue(page(1, "older-1"))
        server.enqueue(page(2, "older-2"))
        server.enqueue(page(3, "older-3"))
        server.enqueue(page(4, "older-4"))
        assertTrue(recovery().recover())
        assertEquals("older-4", store.cursor(ACCOUNT))
        server.enqueue(page(5, "new-head-continuation"))
        server.enqueue(page(6, null))
        assertFalse(recovery().recover())
        assertNull(store.cursor(ACCOUNT))
        assertEquals((1..6).map(::id), seen)
        val requests = (1..6).map { server.takeRequest() }
        assertFalse(requests[0].path!!.contains("cursor="))
        assertFalse(requests[4].path!!.contains("cursor="))
        assertTrue(requests[5].path!!.contains("cursor=older-4"))
        assertTrue(requests.all { it.path!!.contains("unread_only=true") })
        assertTrue(requests.all { it.getHeader("Authorization") == "Bearer access-$ACCOUNT" })
    }

    @Test fun `completed scans revisit old created notifications without alerting receipts again`() = runTest {
        server.enqueue(page(1, null))
        assertFalse(recovery().recover())
        server.enqueue(page(1, "delayed-notification"))
        server.enqueue(page(2, null))
        assertFalse(recovery().recover())
        assertEquals(listOf(id(1), id(2)), seen)
    }

    @Test fun `failed older page keeps the last committed cursor for a retry`() = runTest {
        server.enqueue(page(1, "older"))
        server.enqueue(MockResponse().setResponseCode(503))
        try {
            recovery().recover()
            fail("Expected transient failure")
        } catch (error: KitWalletApiException) {
            assertTrue(error.isTransientPushRegistrationFailure())
        }
        assertEquals("older", store.cursor(ACCOUNT))
        server.enqueue(page(1, "older"))
        server.enqueue(page(2, null))
        assertFalse(recovery().recover())
        assertEquals(listOf(id(1), id(2)), seen)
    }

    @Test fun `logout during authenticated fetch cannot publish or advance a cursor`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                runBlocking { sessions.clear() }
                return page(1, "older")
            }
        }
        try {
            recovery().recover()
            fail("Expected changed session")
        } catch (_: SessionInvalidatedException) { }
        assertTrue(seen.isEmpty())
        assertNull(store.cursor(ACCOUNT))
    }

    @Test fun `a repeated server cursor fails without looping or erasing continuation`() = runTest {
        server.enqueue(page(1, "same"))
        server.enqueue(page(2, "same"))
        try {
            recovery().recover()
            fail("Expected rejected cursor")
        } catch (_: IllegalStateException) { }
        assertEquals(2, server.requestCount)
        assertEquals("same", store.cursor(ACCOUNT))
    }

    @Test fun `a muted channel does not starve other older alerts`() = runTest {
        server.enqueue(page(1, "older"))
        server.enqueue(page(2, null))
        val visited = mutableListOf<String>()
        val selective = object : NotificationInboxAlertSink {
            override suspend fun recoverAlert(owner: SessionFence, envelope: PushEnvelope): Boolean {
                visited += checkNotNull(envelope.data["notification_id"])
                return visited.last() != id(1)
            }
        }
        assertFalse(recovery(selective).recover())
        assertEquals(listOf(id(1), id(2)), visited)
    }

    @Test fun `malformed head pagination cannot publish or erase a valid older checkpoint`() = runTest {
        val badMetadata = listOf(
            "", ",\"meta\":null", ",\"meta\":{}", ",\"meta\":{\"has_more\":null}",
            ",\"meta\":{\"has_more\":true}",
            ",\"meta\":{\"has_more\":true,\"next_cursor\":\"\"}",
            ",\"meta\":{\"has_more\":true,\"next_cursor\":\"${"x".repeat(2049)}\"}",
            ",\"meta\":{\"has_more\":false,\"next_cursor\":\"older\"}",
            ",\"meta\":{\"has_more\":false,\"next_cursor\":\"\"}",
        )
        badMetadata.forEachIndexed { index, meta ->
            store.saveCursor(ACCOUNT, "known-older")
            server.enqueue(json("""{"ok":true,"data":{"items":[${row(1)}]}$meta}"""))
            try {
                recovery().recover()
                fail("Expected malformed metadata $index to be rejected")
            } catch (_: IllegalStateException) { }
            assertTrue(seen.isEmpty())
            assertEquals("known-older", store.cursor(ACCOUNT))
            assertEquals(index + 1, server.requestCount)
        }
    }

    @Test fun `oversized or missing item pages are rejected before publication`() = runTest {
        val bodies = listOf(
            """{"ok":true,"data":{"items":[${(1..101).joinToString(",", transform = ::row)}]},"meta":{"has_more":false,"next_cursor":null}}""",
            """{"ok":true,"data":{},"meta":{"has_more":false,"next_cursor":null}}""",
            """{"ok":true,"data":{"items":null},"meta":{"has_more":false,"next_cursor":null}}""",
        )
        bodies.forEach { body ->
            store.saveCursor(ACCOUNT, "known-older")
            server.enqueue(json(body))
            try {
                recovery().recover()
                fail("Expected invalid items to be rejected")
            } catch (_: Exception) { }
            assertTrue(seen.isEmpty())
            assertEquals("known-older", store.cursor(ACCOUNT))
        }
    }

    @Test fun `malformed older page leaves its checkpoint and never publishes that page`() = runTest {
        store.saveCursor(ACCOUNT, "known-older")
        server.enqueue(page(1, "new-head"))
        server.enqueue(json("""{"ok":true,"data":{"items":[${row(2)}]},"meta":{"has_more":false,"next_cursor":"contradictory"}}"""))
        try {
            recovery().recover()
            fail("Expected malformed older page")
        } catch (_: IllegalStateException) { }
        assertEquals(listOf(id(1)), seen)
        assertEquals("known-older", store.cursor(ACCOUNT))
    }

    @Test fun `corrupt saved continuations restart at head and are never sent`() = runTest {
        listOf("", " ", "x".repeat(2049)).forEach { corrupt ->
            store.saveCursor(ACCOUNT, corrupt)
            server.enqueue(page(1, null))
            assertFalse(recovery().recover())
            assertFalse(server.takeRequest().path!!.contains("cursor="))
            assertNull(store.cursor(ACCOUNT))
        }
        assertEquals(listOf(id(1)), seen)
    }

    @Test fun `a complete requested page and maximum length continuation are accepted`() = runTest {
        val cursor = "x".repeat(2048)
        server.enqueue(json("""{"ok":true,"data":{"items":[${(1..100).joinToString(",", transform = ::row)}]},"meta":{"has_more":true,"next_cursor":"$cursor"}}"""))
        server.enqueue(page(101, null))
        assertFalse(recovery().recover())
        assertEquals(101, seen.size)
        server.takeRequest()
        assertTrue(server.takeRequest().path!!.contains("cursor=$cursor"))
    }

    @Test fun `encrypted wake and silent call rows cannot become display alerts`() {
        val base = NotificationInboxItem(id(1), "activity", silent = false)
        assertNotNull(base.alertEnvelope())
        assertNull(base.copy(type = "messaging.sync").alertEnvelope())
        assertNull(base.copy(type = "message_available").alertEnvelope())
        assertNull(base.copy(data = mapOf("scope" to "messaging")).alertEnvelope())
        assertNull(base.copy(type = "call.ringing").alertEnvelope())
        assertNull(base.copy(type = "call.ended").alertEnvelope())
        assertNull(base.copy(silent = true).alertEnvelope())
        assertNull(base.copy(readAt = "2026-09-05T00:00:00Z").alertEnvelope())
        val missed = base.copy(type = "call.missed", data = mapOf("missed_call_alert" to true))
        assertEquals("true", missed.alertEnvelope()!!.data["missed_call_alert"])
    }

    @Test fun `missed callee alert rejects caller sync wrong recipient and malformed call ids`() {
        val owner = sessions.current()!!.fence()
        val payload = missedPayload()
        assertNotNull(MissedCallAlert.fromData(payload, owner))
        assertNull(MissedCallAlert.fromData(payload - "missed_call_alert", owner))
        assertNull(MissedCallAlert.fromData(payload + ("recipient_user_id" to OTHER_ACCOUNT), owner))
        assertNull(MissedCallAlert.fromData(payload + ("state" to "ended"), owner))
        assertNull(MissedCallAlert.fromData(payload + ("call_id" to "1-2-3-4-5"), owner))
        assertEquals("kitwallet://calls/history", CALL_HISTORY_NOTIFICATION_LINK)
    }

    @Test fun `missed push and inbox share an account call receipt across relogin`() = runTest {
        val alert = MissedCallAlert.fromData(missedPayload(), sessions.current()!!.fence())!!
        var displays = 0
        suspend fun deliver() = NotificationAlertDelivery(sessions, store).deliver(
            sessions.current()!!.fence(), alert.identity, { true }, { false }, display = { displays++ },
        )
        assertTrue(deliver())
        sessions.save(testSession(ACCOUNT, "new-session", "new-cache"))
        assertTrue(deliver())
        assertEquals(1, displays)
        sessions.save(testSession(OTHER_ACCOUNT))
        assertTrue(deliver())
        assertEquals(2, displays)
    }

    @Test fun `active automatic FCM alert is receipted without a second alert`() = runTest {
        val delivery = NotificationAlertDelivery(sessions, store)
        delivery.deliver(sessions.current()!!.fence(), id(1), { true }, { true }, display = { fail("Duplicate") })
        delivery.deliver(sessions.current()!!.fence(), id(1), { true }, { false }, display = { fail("Re-alert") })
        assertTrue(store.delivered(ACCOUNT, id(1)))
    }

    @Test fun `disabled or failed publication remains unreceipted for later recovery`() = runTest {
        val delivery = NotificationAlertDelivery(sessions, store)
        val owner = sessions.current()!!.fence()
        assertFalse(delivery.deliver(owner, id(1), { false }, { false }, display = { fail("Disabled") }))
        assertFalse(store.delivered(ACCOUNT, id(1)))
        try {
            delivery.deliver(owner, id(1), { true }, { false }, display = { error("Notification failed") })
            fail("Expected failed notification")
        } catch (_: IllegalStateException) { }
        assertFalse(store.delivered(ACCOUNT, id(1)))
        sessions.clear()
        try {
            delivery.deliver(owner, id(1), { true }, { false }, display = { fail("Old account") })
            fail("Expected retired account")
        } catch (_: SessionInvalidatedException) { }
    }

    @Test fun `login foreground and validated reconnect recover while background and token refresh do not repeat`() {
        val owner = sessions.current()!!.fence()
        val backgroundOffline = NotificationRecoveryConditions(owner, false, false)
        assertTrue(backgroundOffline.shouldRecoverAfter(null))
        assertFalse(backgroundOffline.shouldRecoverAfter(backgroundOffline))
        val online = backgroundOffline.copy(online = true)
        assertTrue(online.shouldRecoverAfter(backgroundOffline))
        assertFalse(backgroundOffline.shouldRecoverAfter(online))
        assertTrue(online.copy(foreground = true).shouldRecoverAfter(online))
        assertFalse(online.copy(owner = null).shouldRecoverAfter(online))
    }

    @Test fun `large offline backlog preserves the newest alerts and room for live calls`() {
        val active = (1..MAX_ACTIVE_RECOVERED_ALERTS).map {
            ActiveRecoveredAlert(id(it), 0, it.toLong())
        }
        val older = planRecoveredAlertQuota(active, id(88), 0)
        assertFalse(older.display)
        assertTrue(older.cancel.isEmpty())
        val newer = planRecoveredAlertQuota(active, id(89), 100)
        assertTrue(newer.display)
        assertEquals(listOf(active.first()), newer.cancel)
        val tied = planRecoveredAlertQuota(active, id(90), active.first().occurredAt)
        assertFalse(tied.display)
        assertTrue(tied.cancel.isEmpty())
        assertTrue(MAX_ACTIVE_RECOVERED_ALERTS < 18)
    }

    @Test fun `authenticated settlement hints reconcile despite disabled duplicate or already visible alerts`() = runTest {
        val delivery = NotificationAlertDelivery(sessions, store)
        val owner = sessions.current()!!.fence()
        var reconciliations = 0
        repeat(3) { attempt ->
            delivery.deliver(
                owner, id(1), { attempt != 0 }, { attempt == 1 },
                onAuthenticatedHint = { reconciliations++ },
                display = { fail("Muted, already displayed, or receipted") },
            )
        }
        assertEquals(3, reconciliations)
        assertTrue(store.delivered(ACCOUNT, id(1)))
    }

    @Test fun `wrong explicit recipient rejects lifecycle admission before ring side effects`() {
        val owner = sessions.current()!!.fence()
        assertTrue(explicitPushRecipientMatches(missedPayload(), owner))
        assertFalse(explicitPushRecipientMatches(missedPayload(), owner.copy(accountId = OTHER_ACCOUNT)))
        assertFalse(explicitPushRecipientMatches(missedPayload(), null))
        assertFalse(explicitPushRecipientMatches(mapOf("recipient_user_id" to "invalid"), owner))
        assertTrue(explicitPushRecipientMatches(mapOf("type" to "call.ended"), owner))
    }

    private fun missedPayload() = mapOf(
        "type" to "call.missed", "state" to "missed", "call_id" to id(77),
        "missed_call_alert" to "true", "recipient_user_id" to ACCOUNT,
    )

    private fun page(value: Int, next: String?) = MockResponse().setHeader("Content-Type", "application/json")
        .setBody("""{"ok":true,"data":{"items":[{"id":"${id(value)}","type":"activity","title":"Update","silent":false}]},"meta":{"has_more":${next != null},"next_cursor":${next?.let { "\"$it\"" } ?: "null"}}}""")

    private fun row(value: Int) = """{"id":"${id(value)}","type":"activity","silent":false}"""
    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private class MemoryReceipts : NotificationRecoveryStore {
        private val cursors = mutableMapOf<String, String>()
        private val receipts = mutableSetOf<Pair<String, String>>()
        override fun cursor(accountId: String) = cursors[accountId]
        override fun saveCursor(accountId: String, cursor: String?) {
            if (cursor == null) cursors.remove(accountId) else cursors[accountId] = cursor
        }
        override fun delivered(accountId: String, identity: String) = accountId to identity in receipts
        override fun recordDelivery(accountId: String, identity: String) { receipts += accountId to identity }
    }

    companion object {
        private const val ACCOUNT = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        private const val OTHER_ACCOUNT = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        private fun id(value: Int) = "00000000-0000-4000-8000-" + value.toString().padStart(12, '0')
    }
}
