package com.kit.wallet

import com.kit.wallet.data.messaging.SecureMessagingSyncEngine
import com.kit.wallet.data.realtime.ChannelAuthDto
import com.kit.wallet.data.realtime.ChannelAuthRequest
import com.kit.wallet.data.realtime.KitForegroundSource
import com.kit.wallet.data.realtime.KitForegroundSyncTrigger
import com.kit.wallet.data.realtime.KitNetworkEvent
import com.kit.wallet.data.realtime.KitNetworkSource
import com.kit.wallet.data.realtime.KitPresenceRegistry
import com.kit.wallet.data.realtime.KitRealtimeAuthApi
import com.kit.wallet.data.realtime.KitRealtimeBackoff
import com.kit.wallet.data.realtime.KitRealtimeClock
import com.kit.wallet.data.realtime.KitRealtimeCoordinator
import com.kit.wallet.data.realtime.KitRealtimeFallbackPoller
import com.kit.wallet.data.realtime.KitRealtimeNudgeSink
import com.kit.wallet.data.realtime.KitRealtimeState
import com.kit.wallet.data.realtime.KitRealtimeSuspension
import com.kit.wallet.data.realtime.KitRealtimeTransport
import com.kit.wallet.data.realtime.KitTypingRegistry
import com.kit.wallet.data.realtime.KitTypingSignaller
import com.kit.wallet.data.realtime.TypingRequest
import com.kit.wallet.data.remote.ApiCallExecutor
import com.kit.wallet.data.remote.ApiEnvelope
import com.kit.wallet.data.remote.CapabilitiesDto
import com.kit.wallet.data.remote.CurrencyDto
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.remote.ProtocolsDto
import com.kit.wallet.data.remote.RealtimeChannelsDto
import com.kit.wallet.data.remote.RealtimeProtocolDto
import com.kit.wallet.data.session.ProfileSetupState
import com.kit.wallet.data.session.SessionFence
import com.kit.wallet.data.session.SessionSnapshot
import com.kit.wallet.data.session.SessionStore
import com.kit.wallet.data.session.SessionTokens
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.lang.reflect.Proxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/**
 * The state machine, driven end to end against a fake socket.
 *
 * Everything that can move the connection — a lifecycle callback, a connectivity
 * change, a frame off the socket's reader thread, a screen becoming visible, a
 * one-second tick — arrives as one command on one channel and is applied in
 * arrival order. That is what makes the hard rows testable here: a failure
 * callback from a socket we already replaced, a subscribe racing an unsubscribe,
 * and a network change arriving mid-handshake are all just messages.
 *
 * Four invariants are load-bearing rather than incidental, and each has a test
 * that names it: **foreground only**, **bounded lifetime**, **every** transition
 * into `Live` requests a sync, and **a dropped nudge is not a lost message** —
 * nothing here is durable and nothing here advances a cursor.
 *
 * Virtual time is advanced explicitly and never by `advanceUntilIdle`: the
 * coordinator's one-second tick is an infinite loop, so the scheduler never goes
 * idle while a coordinator exists.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KitRealtimeStateMachineTest {

    // --- the happy path ---------------------------------------------------------

    @Test
    fun `foreground plus a session plus an advertisement reaches Live`() = stateMachineTest { world ->
        world.startSignedInAndForegrounded()

        assertEquals(KitRealtimeState.Connecting, world.state)
        assertEquals(SOCKET_URL, world.transport.openedUrl)

        world.socketOpened()
        assertEquals(KitRealtimeState.Handshaking, world.state)

        world.handshake()

        // Reverb authenticates the private account channel directly; this build
        // must never emit its unsupported connection-level sign-in extension.
        assertEquals(KitRealtimeState.Subscribing, world.state)
        assertEquals(
            listOf("/api/kit-wallet/v1/messaging/realtime/auth"),
            world.auth.channelAuthPaths,
        )
        assertTrue(world.transport.sent.none { it.contains("pusher:signin") })
        assertTrue(world.transport.sent.any { it.contains(USER_CHANNEL) })

        world.subscribed(USER_CHANNEL)
        assertEquals(KitRealtimeState.Live, world.state)
    }

    @Test
    fun `going Live always requests a sync, whatever the socket missed`() = stateMachineTest { world ->
        world.goLive()

        // The entire gap-recovery story in one line: whatever arrived while the
        // socket was down is in the durable log, and this is what goes and gets it.
        assertEquals(1, world.engine.syncs)

        world.frame(NUDGE_FRAME)
        assertEquals(2, world.engine.syncs)

        // Dropping and coming back requests another one, unconditionally.
        world.socketFailed()
        world.reachLiveAgain()
        assertEquals(3, world.engine.syncs)
    }

    @Test
    fun `a nudge outside Live is dropped and starts no sync`() = stateMachineTest { world ->
        world.startSignedInAndForegrounded()
        world.socketOpened()

        // Letting a nudge sync outside `Live` would add a second, ungoverned
        // background sync path that nothing in the power or network budget accounts
        // for. Nothing is lost: the cursor cannot regress.
        world.frame(NUDGE_FRAME)

        assertEquals(0, world.engine.syncs)
    }

    @Test
    fun `a ping is answered with a pong`() = stateMachineTest { world ->
        world.goLive()

        world.frame("""{"event":"pusher:ping","data":"{}"}""")

        assertTrue(world.transport.sent.any { it.contains("pusher:pong") })
    }

    // --- foreground only --------------------------------------------------------

    @Test
    fun `no socket is opened while the app is backgrounded`() = stateMachineTest { world ->
        world.session.signIn()
        world.settle()

        // No foreground service, no wake lock, no new permission, and Doze is a
        // non-event because there is nothing running to be dozed.
        assertNull(world.transport.openedUrl)
        assertEquals(KitRealtimeState.Idle, world.state)
    }

    @Test
    fun `backgrounding unsubscribes explicitly and then closes cleanly`() = stateMachineTest { world ->
        world.goLive()
        world.observe(CONVERSATION)
        world.subscribed(CONVERSATION_CHANNEL, members = setOf(SELF, PEER))

        world.transport.sent.clear()
        world.foreground.set(false)
        world.settle()

        // Explicit unsubscribes then a clean close, so peers see the dot go out in
        // under a second rather than at the server's 30-second activity timeout.
        assertTrue(world.transport.sent.any { it.contains("pusher:unsubscribe") })
        assertEquals(1, world.transport.cleanCloses)
        assertEquals(0, world.transport.cancels)
        assertEquals(KitRealtimeState.Idle, world.state)
        assertTrue(world.presence.presence.value.isEmpty())
    }

    @Test
    fun `coming back to the foreground clears the ladder and redials`() = stateMachineTest { world ->
        world.goLive()
        world.foreground.set(false)
        world.settle()

        world.transport.openedUrl = null
        world.foreground.set(true)
        world.settle()

        // A ladder built while the app was not even running predicts nothing.
        assertEquals(SOCKET_URL, world.transport.openedUrl)
        assertEquals(KitRealtimeState.Connecting, world.state)
    }

    // --- failure rows -----------------------------------------------------------

    @Test
    fun `a refused upgrade backs off and the tick redials`() = stateMachineTest { world ->
        world.startSignedInAndForegrounded()

        world.socketFailed()
        assertTrue("Expected Backoff, was ${world.state}", world.state is KitRealtimeState.Backoff)

        world.transport.openedUrl = null
        world.retryFromBackoff()

        assertEquals(SOCKET_URL, world.transport.openedUrl)
        assertEquals(KitRealtimeState.Connecting, world.state)
    }

    @Test
    fun `a 4001 protocol error suspends rather than retrying forever`() = stateMachineTest { world ->
        world.goLive()

        // 4000-4099: the app key, protocol or version is unusable. Retrying would
        // only burn battery against a connection that cannot succeed as configured.
        world.frame(protocolError(4001))

        assertEquals(KitRealtimeState.Suspended(KitRealtimeSuspension.ProtocolRejected), world.state)
        assertEquals(1, world.transport.cancels)
    }

    @Test
    fun `a 4200 protocol error reconnects at once without spending an attempt`() = stateMachineTest { world ->
        world.goLive()
        world.transport.openedUrl = null

        // The server is telling us it is going away in an orderly fashion, which is
        // not a failure and must not cost a delay.
        world.frame(protocolError(4200))

        assertEquals(KitRealtimeState.Connecting, world.state)
        assertEquals(SOCKET_URL, world.transport.openedUrl)
        assertEquals(1, world.transport.cleanCloses)
    }

    @Test
    fun `a repeated 4200 before stable Live uses bounded backoff`() = stateMachineTest { world ->
        world.goLive()

        world.frame(protocolError(4200))
        assertEquals(KitRealtimeState.Connecting, world.state)
        world.socketOpened()
        world.handshake()
        world.subscribed(USER_CHANNEL)

        val before = world.clock.now
        world.frame(protocolError(4200))

        val state = world.state as KitRealtimeState.Backoff
        assertTrue(state.retryAtElapsedMillis >= before + 1_000L)
        assertTrue(state.retryAtElapsedMillis <= before + KitRealtimeBackoff.MAX_DELAY_MILLIS)
    }

    @Test
    fun `4299 remains in the reconnect immediately range`() = stateMachineTest { world ->
        world.goLive()
        world.transport.openedUrl = null

        world.frame(protocolError(4299))

        assertEquals(KitRealtimeState.Connecting, world.state)
        assertEquals(SOCKET_URL, world.transport.openedUrl)
    }

    @Test
    fun `4300 is outside the reconnect immediately range and backs off`() = stateMachineTest { world ->
        world.goLive()
        val before = world.clock.now

        world.frame(protocolError(4300))

        val state = world.state as KitRealtimeState.Backoff
        assertTrue(state.retryAtElapsedMillis >= before + 1_000L)
    }

    @Test
    fun `a protocol error without a code backs off`() = stateMachineTest { world ->
        world.goLive()
        val before = world.clock.now

        world.frame("""{"event":"pusher:error","data":"{\"message\":\"nope\"}"}""")

        val state = world.state as KitRealtimeState.Backoff
        assertTrue(state.retryAtElapsedMillis >= before + 1_000L)
    }

    @Test
    fun `sixty seconds of stable Live restores one immediate reconnect`() = stateMachineTest { world ->
        world.goLive()
        world.frame(protocolError(4200))
        world.socketOpened()
        world.handshake()
        world.subscribed(USER_CHANNEL)

        world.clock.now += KitRealtimeBackoff.STABLE_LIVE_MILLIS
        world.transport.openedUrl = null
        world.frame(protocolError(4200))

        assertEquals(KitRealtimeState.Connecting, world.state)
        assertEquals(SOCKET_URL, world.transport.openedUrl)
    }

    @Test
    fun `network restoration restores one immediate reconnect`() = stateMachineTest { world ->
        world.goLive()
        world.frame(protocolError(4200))
        world.socketOpened()
        world.handshake()
        world.subscribed(USER_CHANNEL)

        world.network.emit(KitNetworkEvent.Available)
        world.settle()
        world.transport.openedUrl = null
        world.frame(protocolError(4200))

        assertEquals(KitRealtimeState.Connecting, world.state)
        assertEquals(SOCKET_URL, world.transport.openedUrl)
    }

    @Test
    fun `a session change restores one immediate reconnect`() = stateMachineTest { world ->
        world.goLive()
        world.frame(protocolError(4200))

        world.session.signIn(epoch = "scope-2")
        world.settle()
        world.socketOpened()
        world.handshake()
        world.subscribed(USER_CHANNEL)

        world.transport.openedUrl = null
        world.frame(protocolError(4200))

        assertEquals(KitRealtimeState.Connecting, world.state)
        assertEquals(SOCKET_URL, world.transport.openedUrl)
    }

    @Test
    fun `a queued close from the retired socket cannot tear down its replacement`() = stateMachineTest { world ->
        world.goLive()
        world.transport.openedUrl = null

        world.frameThenSocketFailedBeforeSettle(protocolError(4200))

        assertEquals(KitRealtimeState.Connecting, world.state)
        assertEquals(SOCKET_URL, world.transport.openedUrl)
        assertEquals(1, world.transport.cleanCloses)
        assertEquals(0, world.transport.cancels)
    }

    @Test
    fun `a stale successful authorization cannot write to the replacement socket`() = stateMachineTest { world ->
        val staleAuthorization = world.auth.holdNextChannelAuthorization()
        world.startSignedInAndForegrounded()
        world.socketOpened()
        world.handshake()

        world.frame(protocolError(4200))
        world.socketOpened()
        world.handshake()
        world.subscribed(USER_CHANNEL)
        val sentBeforeStaleResult = world.transport.sent.size

        world.auth.completeHeldSuccess(staleAuthorization)
        world.settle()

        assertEquals(KitRealtimeState.Live, world.state)
        assertEquals(sentBeforeStaleResult, world.transport.sent.size)
    }

    @Test
    fun `a stale failed authorization cannot suspend the replacement socket`() = stateMachineTest { world ->
        val staleAuthorization = world.auth.holdNextChannelAuthorization()
        world.startSignedInAndForegrounded()
        world.socketOpened()
        world.handshake()

        world.frame(protocolError(4200))
        world.socketOpened()
        world.handshake()
        world.subscribed(USER_CHANNEL)
        val cancelsBeforeStaleResult = world.transport.cancels

        world.auth.completeHeldFailure(staleAuthorization, status = 403)
        world.settle()

        assertEquals(KitRealtimeState.Live, world.state)
        assertEquals(cancelsBeforeStaleResult, world.transport.cancels)
    }

    @Test
    fun `a 4100 protocol error backs off with at least the one-second floor`() = stateMachineTest { world ->
        world.goLive()
        world.clock.now = 500_000L

        world.frame(protocolError(4100))

        val state = world.state as KitRealtimeState.Backoff
        assertTrue(state.retryAtElapsedMillis >= 500_000L + 1_000L)
    }

    @Test
    fun `a missed pong tears the socket down without a close handshake`() = stateMachineTest { world ->
        world.goLive()
        world.transport.sent.clear()

        // Silence past `activity_timeout - 5` asks the question...
        world.clock.now += 26_000L
        world.tick()
        assertTrue(world.transport.sent.any { it.contains("pusher:ping") })
        assertEquals(KitRealtimeState.Live, world.state)

        // ...and no answer within ten seconds ends it. The peer has already proven
        // it is not reading, so there is nothing to be gained by a close handshake.
        world.clock.now += 10_000L
        world.tick()

        assertTrue(world.state is KitRealtimeState.Backoff)
        assertEquals(1, world.transport.cancels)
        assertEquals(0, world.transport.cleanCloses)
    }

    @Test
    fun `a pong clears the deadline`() = stateMachineTest { world ->
        world.goLive()

        world.clock.now += 26_000L
        world.tick()
        world.frame("""{"event":"pusher:pong","data":"{}"}""")

        world.clock.now += 10_000L
        world.tick()

        assertEquals(KitRealtimeState.Live, world.state)
    }

    @Test
    fun `a handshake that never completes is given up on after ten seconds`() = stateMachineTest { world ->
        world.startSignedInAndForegrounded()
        world.socketOpened()

        world.clock.now += 10_000L
        world.tick()

        assertTrue(world.state is KitRealtimeState.Backoff)
    }

    @Test
    fun `losing the network backs off without spending an attempt`() = stateMachineTest { world ->
        world.goLive()
        world.clock.now = 100_000L

        world.network.emit(KitNetworkEvent.Lost)
        world.settle()

        // There was no server to fail against and nobody to be polite to, so the
        // ladder must stay on its first rung: a tunnel cannot cost a minute.
        val state = world.state as KitRealtimeState.Backoff
        assertTrue(state.retryAtElapsedMillis - 100_000L < 1_000L)
    }

    @Test
    fun `regaining the network redials immediately instead of waiting out the ladder`() = stateMachineTest { world ->
        world.goLive()

        world.socketFailed()
        repeat(5) {
            world.retryFromBackoff()
            world.socketFailed()
        }
        assertTrue(world.state is KitRealtimeState.Backoff)

        world.transport.openedUrl = null
        world.network.emit(KitNetworkEvent.Available)
        world.settle()

        // Waiting out a 60-second ladder after connectivity has already returned is
        // the single most visible way this feature can feel broken.
        assertEquals(SOCKET_URL, world.transport.openedUrl)
    }

    @Test
    fun `a network loss cannot resurrect a suspended connection`() = stateMachineTest { world ->
        world.goLive()
        world.frame(protocolError(4001))

        world.network.emit(KitNetworkEvent.Lost)
        world.settle()

        assertEquals(KitRealtimeState.Suspended(KitRealtimeSuspension.ProtocolRejected), world.state)
    }

    // --- authorization ----------------------------------------------------------

    @Test
    fun `two 401s across a refresh suspend as unauthenticated`() = stateMachineTest { world ->
        world.auth.channelAuthStatus = 401
        world.startSignedInAndForegrounded()

        world.socketOpened()
        world.handshake()
        assertTrue(world.state is KitRealtimeState.Backoff)

        // The client's own token refresh already ran inside OkHttp, so a 401 reaching
        // here is a refresh that did not help. One more whole attempt, then stop.
        world.retryFromBackoff()
        world.socketOpened()
        world.handshake()

        assertEquals(KitRealtimeState.Suspended(KitRealtimeSuspension.Unauthenticated), world.state)
    }

    @Test
    fun `a 403 on the account's own channel suspends at once`() = stateMachineTest { world ->
        world.auth.channelAuthStatus = 403
        world.startSignedInAndForegrounded()
        world.socketOpened()
        world.handshake()

        assertEquals(KitRealtimeState.Suspended(KitRealtimeSuspension.Forbidden), world.state)
    }

    @Test
    fun `a 503 from the protocol gate suspends rather than laddering`() = stateMachineTest { world ->
        world.auth.channelAuthStatus = 503
        world.startSignedInAndForegrounded()
        world.socketOpened()
        world.handshake()

        assertEquals(KitRealtimeState.Suspended(KitRealtimeSuspension.ProtocolUnavailable), world.state)
    }

    @Test
    fun `a 429 honours Retry-After`() = stateMachineTest { world ->
        world.auth.channelAuthStatus = 429
        world.auth.retryAfterSeconds = 30
        world.clock.now = 10_000L
        world.startSignedInAndForegrounded()
        world.socketOpened()
        world.handshake()

        val state = world.state as KitRealtimeState.Backoff
        assertTrue(state.retryAtElapsedMillis >= 10_000L + 30_000L)
    }

    @Test
    fun `a refused conversation channel goes dark alone and never retries`() = stateMachineTest { world ->
        world.goLive()

        world.auth.channelAuthStatus = 403
        world.observe(CONVERSATION)

        // A 403 here means blocked, not a member, opted out, or a conversation the
        // protocol does not cover — none of which change because we asked again.
        assertEquals("The account's own channel must survive", KitRealtimeState.Live, world.state)
        assertTrue(world.presence.membersOf(CONVERSATION).isEmpty())

        world.auth.channelAuthStatus = null
        val authCalls = world.auth.channelCalls
        world.coordinator.stopObservingConversation(CONVERSATION)
        world.settle()
        world.observe(CONVERSATION)

        assertEquals("A refusal is final for the session epoch", authCalls, world.auth.channelCalls)
    }

    @Test
    fun `a new session epoch clears refusals and suspensions alike`() = stateMachineTest { world ->
        world.auth.channelAuthStatus = 403
        world.startSignedInAndForegrounded()
        world.socketOpened()
        world.handshake()
        assertTrue(world.state is KitRealtimeState.Suspended)

        // Those verdicts belonged to the old credential.
        world.transport.openedUrl = null
        world.auth.channelAuthStatus = null
        world.session.signIn(epoch = "scope-2")
        world.settle()

        assertEquals(SOCKET_URL, world.transport.openedUrl)
    }

    // --- conversation channels, presence and typing ------------------------------

    @Test
    fun `observing a conversation subscribes it and seeds its roster`() = stateMachineTest { world ->
        world.goLive()

        world.observe(CONVERSATION)
        world.subscribed(CONVERSATION_CHANNEL, members = setOf(SELF, PEER))

        assertEquals(setOf(SELF, PEER), world.presence.membersOf(CONVERSATION))
    }

    @Test
    fun `leaving a conversation unsubscribes it and clears its signals`() = stateMachineTest { world ->
        world.goLive()
        world.observe(CONVERSATION)
        world.subscribed(CONVERSATION_CHANNEL, members = setOf(SELF, PEER))
        world.frame(typingFrame(PEER))
        assertEquals(setOf(PEER), world.typing.typing.value[CONVERSATION].orEmpty())

        world.transport.sent.clear()
        world.coordinator.stopObservingConversation(CONVERSATION)
        world.settle()

        assertTrue(world.transport.sent.any { it.contains("pusher:unsubscribe") })
        assertTrue(world.presence.membersOf(CONVERSATION).isEmpty())
        assertTrue(world.typing.typing.value.isEmpty())
    }

    @Test
    fun `a subscription that lands after we navigated away is given straight back`() = stateMachineTest { world ->
        world.goLive()

        world.observe(CONVERSATION)
        world.coordinator.stopObservingConversation(CONVERSATION)
        world.settle()

        world.transport.sent.clear()
        world.subscribed(CONVERSATION_CHANNEL, members = setOf(SELF, PEER))

        assertTrue(world.transport.sent.any { it.contains("pusher:unsubscribe") })
        assertTrue(world.presence.membersOf(CONVERSATION).isEmpty())
    }

    @Test
    fun `a typing frame on a channel we are not subscribed to is dropped`() = stateMachineTest { world ->
        world.goLive()

        world.frame(typingFrame(PEER))

        assertTrue(world.typing.typing.value.isEmpty())
    }

    @Test
    fun `a member leaving the channel also stops them typing`() = stateMachineTest { world ->
        world.goLive()
        world.observe(CONVERSATION)
        world.subscribed(CONVERSATION_CHANNEL, members = setOf(SELF, PEER))
        world.frame(typingFrame(PEER))
        assertEquals(setOf(PEER), world.typing.typing.value[CONVERSATION].orEmpty())

        // Somebody who has left the channel cannot still be typing in it.
        world.frame(memberRemoved(PEER))

        assertTrue(world.typing.typing.value.isEmpty())
        assertEquals(setOf(SELF), world.presence.membersOf(CONVERSATION))
    }

    @Test
    fun `conversations observed while down are subscribed on the way back into Live`() = stateMachineTest { world ->
        world.goLive()
        world.observe(CONVERSATION)
        world.subscribed(CONVERSATION_CHANNEL, members = setOf(SELF, PEER))

        world.socketFailed()
        assertTrue("An unclean drop must not freeze a dot", world.presence.presence.value.isEmpty())

        world.reachLiveAgain()
        world.subscribed(CONVERSATION_CHANNEL, members = setOf(SELF, PEER))

        assertEquals(setOf(SELF, PEER), world.presence.membersOf(CONVERSATION))
    }

    // --- bounded lifetime -------------------------------------------------------

    @Test
    fun `the connection is redialled on the advertised lifetime and holds presence over`() = stateMachineTest { world ->
        world.goLive()
        world.observe(CONVERSATION)
        world.subscribed(CONVERSATION_CHANNEL, members = setOf(SELF, PEER))

        world.transport.openedUrl = null
        world.clock.now += MAX_CONNECTION_SECONDS * 1_000L
        world.tick()

        // Both auth POSTs re-run `kit.auth` on the way back, which is how a revoked
        // session, a revoked device or a new block stops being able to watch this
        // conversation. The rosters stay put so the user does not see a flap.
        assertEquals(SOCKET_URL, world.transport.openedUrl)
        assertEquals(1, world.transport.cleanCloses)
        assertEquals(setOf(SELF, PEER), world.presence.membersOf(CONVERSATION))
    }

    @Test
    fun `a hold-over that is not replaced expires`() = stateMachineTest { world ->
        world.goLive()
        world.observe(CONVERSATION)
        world.subscribed(CONVERSATION_CHANNEL, members = setOf(SELF, PEER))

        world.clock.now += MAX_CONNECTION_SECONDS * 1_000L
        world.tick()

        world.clock.now += KitPresenceRegistry.HOLD_OVER_MILLIS
        world.tick()

        assertTrue(world.presence.presence.value.isEmpty())
    }

    // --- the advertisement ------------------------------------------------------

    @Test
    fun `no advertised block means no socket and the poller keeps the conversation`() =
        stateMachineTest(advertise = false) { world ->
            world.startSignedInAndForegrounded()

            // The server-side kill switch: no block, or one this build does not speak.
            assertNull(world.transport.openedUrl)
            assertTrue(world.state is KitRealtimeState.Backoff)
            assertEquals(
                KitRealtimeFallbackPoller.WITHOUT_REALTIME_MILLIS,
                world.coordinator.foregroundSyncIntervalMillis.value,
            )
        }

    @Test
    fun `the poller stands down while the socket is Live and returns when it is not`() = stateMachineTest { world ->
        world.goLive()

        assertNull(
            "Polling must stop once the socket is carrying the conversation",
            world.coordinator.foregroundSyncIntervalMillis.value,
        )

        world.socketFailed()

        assertEquals(
            KitRealtimeFallbackPoller.DEGRADED_FROM_MILLIS,
            world.coordinator.foregroundSyncIntervalMillis.value,
        )
    }

    @Test
    fun `typing is not armed when the server advertised presence without it`() =
        stateMachineTest(typing = false) { world ->
            world.goLive()
            world.observe(CONVERSATION)
            world.subscribed(CONVERSATION_CHANNEL, members = setOf(SELF, PEER))

            world.typingSignaller.onComposerChanged(CONVERSATION, "hello")
            world.advance(KitTypingSignaller.DEBOUNCE_MILLIS + 100L)

            assertTrue(world.auth.typingPosts.isEmpty())
        }

    @Test
    fun `typing is armed and debounced once the server advertised it`() = stateMachineTest { world ->
        world.goLive()
        world.observe(CONVERSATION)
        world.subscribed(CONVERSATION_CHANNEL, members = setOf(SELF, PEER))

        world.typingSignaller.onComposerChanged(CONVERSATION, "h")
        world.settle()
        assertTrue("A single keypress must not announce anything", world.auth.typingPosts.isEmpty())

        world.advance(KitTypingSignaller.DEBOUNCE_MILLIS + 100L)
        assertEquals(listOf("start"), world.auth.typingPosts.map { it.second })

        // Committing the message stops the bubble before the POST that carries it.
        world.typingSignaller.onMessageCommitted(CONVERSATION)
        world.settle()
        assertEquals(listOf("start", "stop"), world.auth.typingPosts.map { it.second })
    }

    @Test
    fun `losing the socket disarms typing entirely`() = stateMachineTest { world ->
        world.goLive()
        world.socketFailed()

        world.typingSignaller.onComposerChanged(CONVERSATION, "hello")
        world.advance(KitTypingSignaller.DEBOUNCE_MILLIS + 100L)

        // Without a socket we would not be rendering the peer's bubble either, so
        // the requests would buy nothing.
        assertTrue(world.auth.typingPosts.isEmpty())
    }

    @Test
    fun `signing out tears the socket down`() = stateMachineTest { world ->
        world.goLive()

        world.session.signOut()
        world.settle()

        assertEquals(KitRealtimeState.Idle, world.state)
        assertTrue(world.presence.presence.value.isEmpty())
    }

    // --- the world --------------------------------------------------------------

    private fun stateMachineTest(
        advertise: Boolean = true,
        typing: Boolean = true,
        body: (World) -> Unit,
    ) = runTest {
        val world = World(this, advertise, typing)
        try {
            body(world)
        } finally {
            // The coordinator's tick loop never ends on its own, and `runTest` will
            // not return while it is still scheduling work on the shared scheduler.
            world.shutdown()
        }
    }

    /** Everything the coordinator needs, with only the socket and the clock faked. */
    private class World(
        testScope: TestScope,
        advertise: Boolean,
        typingAdvertised: Boolean,
    ) {
        private val scheduler = testScope.testScheduler
        private val workScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))

        val clock = MutableClock()
        val transport = FakeTransport()
        val auth = FakeAuthApi()
        val foreground = FakeForegroundSource()
        val network = FakeNetworkSource()
        val session = FakeSessionStore()
        val engine = CountingSyncEngine()
        val presence = KitPresenceRegistry()
        val typing = KitTypingRegistry(presence, clock)
        val typingSignaller = KitTypingSignaller(auth, clock, workScope)

        val coordinator = KitRealtimeCoordinator(
            transport = transport,
            authApi = auth,
            walletApi = capabilitiesApi(advertise, typingAdvertised),
            // `ApiFailureEnvelope` is `generateAdapter = false`, so the reflective
            // factory is not optional here: a bare Moshi throws in the executor's
            // constructor, before a single frame is exchanged.
            apiCalls = ApiCallExecutor(Moshi.Builder().add(KotlinJsonAdapterFactory()).build()),
            sessionStore = session,
            foregroundMonitor = foreground,
            networkMonitor = network,
            presenceRegistry = presence,
            typingRegistry = typing,
            typingSignaller = typingSignaller,
            nudgeSink = KitRealtimeNudgeSink(
                KitForegroundSyncTrigger(engine, StandardTestDispatcher(scheduler)),
            ),
            fallbackPoller = KitRealtimeFallbackPoller(clock),
            clock = clock,
            scope = workScope,
        )

        val state: KitRealtimeState get() = coordinator.state.value

        init {
            coordinator.start()
            settle()
        }

        /** Drains everything runnable at this instant, without moving virtual time. */
        fun settle() {
            scheduler.runCurrent()
        }

        fun advance(millis: Long) {
            scheduler.advanceTimeBy(millis)
            settle()
        }

        /** One pass of the coordinator's own one-second tick. */
        fun tick() = advance(TICK_MILLIS + 1L)

        fun shutdown() {
            workScope.cancel()
            settle()
        }

        // --- socket callbacks, each followed by the loop that consumes them ------

        fun socketOpened() {
            transport.opened()
            settle()
        }

        fun handshake() {
            transport.established()
            settle()
        }

        fun frame(text: String) {
            transport.frame(text)
            settle()
        }

        fun frameThenSocketFailedBeforeSettle(text: String) {
            transport.frameThenFailure(text)
            settle()
        }

        fun socketFailed() {
            transport.failed()
            settle()
        }

        fun subscribed(channel: String, members: Set<String> = emptySet()) {
            transport.subscribed(channel, members)
            settle()
        }

        // --- scripted sequences -------------------------------------------------

        fun startSignedInAndForegrounded() {
            session.signIn()
            foreground.set(true)
            settle()
        }

        fun goLive() {
            startSignedInAndForegrounded()
            socketOpened()
            handshake()
            subscribed(USER_CHANNEL)
            check(state is KitRealtimeState.Live) { "Expected Live, was $state" }
        }

        fun reachLiveAgain() {
            retryFromBackoff()
            socketOpened()
            handshake()
            subscribed(USER_CHANNEL)
            check(state is KitRealtimeState.Live) { "Expected Live, was $state" }
        }

        /** Jumps the elapsed-time clock to the retry deadline and lets the tick fire. */
        fun retryFromBackoff() {
            val current = state
            check(current is KitRealtimeState.Backoff) { "Expected Backoff, was $current" }
            clock.now = current.retryAtElapsedMillis
            tick()
        }

        fun observe(conversationId: String) {
            coordinator.observeConversation(conversationId)
            settle()
        }
    }

    private class MutableClock(var now: Long = 0L) : KitRealtimeClock {
        override fun elapsedMillis(): Long = now
    }

    private class CountingSyncEngine : SecureMessagingSyncEngine {
        override val isReady: Boolean = true

        var syncs: Int = 0
            private set

        override suspend fun synchronize() {
            syncs++
        }
    }

    private class FakeForegroundSource : KitForegroundSource {
        private val state = MutableStateFlow(false)

        override val foregrounded: StateFlow<Boolean> = state

        override fun start() = Unit

        fun set(value: Boolean) {
            state.value = value
        }
    }

    private class FakeNetworkSource : KitNetworkSource {
        private val changes = MutableSharedFlow<KitNetworkEvent>(extraBufferCapacity = 8)

        override val events: SharedFlow<KitNetworkEvent> = changes.asSharedFlow()

        override fun start() = Unit

        fun emit(event: KitNetworkEvent) {
            check(changes.tryEmit(event))
        }
    }

    /**
     * The socket, reduced to what the state machine can observe about it.
     *
     * Mirrors `KitRealtimeClient` where it matters: sending, closing and cancelling
     * with no socket open are no-ops there, so counting them here would make a
     * teardown that had nothing to tear down look like a real close.
     */
    private class FakeTransport : KitRealtimeTransport {
        var openedUrl: String? = null
        val sent = mutableListOf<String>()
        var cleanCloses: Int = 0
            private set
        var cancels: Int = 0
            private set

        private var listener: KitRealtimeTransport.Listener? = null

        override fun open(url: String, listener: KitRealtimeTransport.Listener) {
            openedUrl = url
            this.listener = listener
        }

        override fun send(text: String): Boolean {
            if (listener == null) return false
            sent += text
            return true
        }

        override fun close(code: Int, reason: String) {
            if (listener == null) return
            listener = null
            cleanCloses++
        }

        override fun cancel() {
            if (listener == null) return
            listener = null
            cancels++
        }

        fun opened() = live().onOpen()

        fun frame(text: String) = live().onFrame(text)

        fun failed() = live().onFailure(IllegalStateException("socket died"))

        fun frameThenFailure(text: String) {
            val retiring = live()
            retiring.onFrame(text)
            retiring.onFailure(IllegalStateException("retiring socket died"))
        }

        fun established(socketId: String = SOCKET_ID) = frame(
            """{"event":"pusher:connection_established",""" +
                """"data":"{\"socket_id\":\"$socketId\",\"activity_timeout\":$ACTIVITY_TIMEOUT}"}""",
        )

        fun subscribed(channel: String, members: Set<String>) {
            val ids = members.joinToString(",") { "\\\"$it\\\"" }
            frame(
                """{"event":"pusher_internal:subscription_succeeded","channel":"$channel",""" +
                    """"data":"{\"presence\":{\"count\":${members.size},\"ids\":[$ids]}}"}""",
            )
        }

        private fun live(): KitRealtimeTransport.Listener =
            requireNotNull(listener) { "No socket is open" }
    }

    private class FakeAuthApi : KitRealtimeAuthApi {
        var channelAuthStatus: Int? = null
        var retryAfterSeconds: Int? = null
        var channelCalls: Int = 0
            private set
        val channelAuthPaths = mutableListOf<String>()

        /** Conversation id to `state`, in the order they were posted. */
        val typingPosts = mutableListOf<Pair<String, String>>()

        private var nextChannelAuthorization: CompletableDeferred<Response<ChannelAuthDto>>? = null

        fun holdNextChannelAuthorization(): CompletableDeferred<Response<ChannelAuthDto>> {
            check(nextChannelAuthorization == null)
            return CompletableDeferred<Response<ChannelAuthDto>>().also {
                nextChannelAuthorization = it
            }
        }

        fun completeHeldSuccess(held: CompletableDeferred<Response<ChannelAuthDto>>) {
            check(
                held.complete(
                    Response.success(
                        ChannelAuthDto(
                            auth = "stale:signature",
                            channelData = """{"user_id":"11111111-1111-4111-8111-111111111111"}""",
                        ),
                    ),
                ),
            )
        }

        fun completeHeldFailure(
            held: CompletableDeferred<Response<ChannelAuthDto>>,
            status: Int,
        ) {
            check(held.complete(errorResponse(status)))
        }

        override suspend fun authorizeChannel(
            path: String,
            body: ChannelAuthRequest,
        ): Response<ChannelAuthDto> {
            channelCalls++
            channelAuthPaths += path
            nextChannelAuthorization?.let { held ->
                nextChannelAuthorization = null
                return held.await()
            }
            channelAuthStatus?.let { return errorResponse(it) }
            return Response.success(
                ChannelAuthDto(auth = "key:signature", channelData = """{"user_id":"$SELF"}"""),
            )
        }

        override suspend fun typing(
            conversationId: String,
            socketId: String?,
            body: TypingRequest,
        ): Response<Unit> {
            typingPosts += conversationId to body.state
            return Response.success(Unit)
        }

        private fun <T> errorResponse(status: Int): Response<T> = Response.error(
            "".toResponseBody("application/json".toMediaType()),
            okhttp3.Response.Builder()
                .code(status)
                .message("error")
                .protocol(Protocol.HTTP_1_1)
                .request(
                    Request.Builder()
                        .url("https://pay.kit.africa/api/kit-wallet/v1/messaging/realtime/auth")
                        .build(),
                )
                .headers(
                    retryAfterSeconds
                        ?.let { Headers.headersOf("Retry-After", it.toString()) }
                        ?: Headers.headersOf(),
                )
                .build(),
        )
    }

    private class FakeSessionStore : SessionStore {
        private val tokens = MutableStateFlow<SessionTokens?>(null)
        private var revision = 0L

        override val session: StateFlow<SessionTokens?> = tokens

        override fun current(): SessionTokens? = tokens.value

        override fun snapshot() = SessionSnapshot(revision, tokens.value?.fence())

        override suspend fun save(tokens: SessionTokens) {
            this.tokens.value = tokens
            revision++
        }

        override suspend fun saveIfUnchanged(
            expected: SessionSnapshot,
            tokens: SessionTokens,
        ): Boolean {
            if (snapshot() != expected) return false
            save(tokens)
            return true
        }

        override suspend fun updateProfileSetupState(
            expected: SessionFence,
            state: ProfileSetupState,
        ): Boolean {
            val current = tokens.value ?: return false
            if (current.fence() != expected) return false
            save(current.copy(profileSetupState = state))
            return true
        }

        override suspend fun <T> withCurrentSession(
            expected: SessionFence,
            block: suspend (SessionTokens) -> T,
        ): T {
            val current = requireNotNull(tokens.value)
            check(current.fence() == expected)
            return block(current)
        }

        override suspend fun clearIfCurrent(expected: SessionFence): Boolean {
            if (tokens.value?.fence() != expected) return false
            clear()
            return true
        }

        override suspend fun clear() {
            tokens.value = null
            revision++
        }

        /** [epoch] is the `cacheScopeId`: the session epoch the coordinator keys on. */
        fun signIn(epoch: String = "scope-1") {
            tokens.value = SessionTokens(
                accessToken = "access-token",
                refreshToken = "refresh-token",
                sessionId = "session-1",
                accountId = SELF,
                cacheScopeId = epoch,
            )
            revision++
        }

        fun signOut() {
            tokens.value = null
            revision++
        }
    }

    private companion object {
        const val SELF = "11111111-1111-4111-8111-111111111111"
        const val PEER = "22222222-2222-4222-8222-222222222222"
        const val CONVERSATION = "c1"
        const val USER_CHANNEL = "private-kit.user.$SELF"
        const val CONVERSATION_CHANNEL = "presence-kit.conv.$CONVERSATION"
        const val SOCKET_ID = "123.456"
        const val ACTIVITY_TIMEOUT = 30
        const val MAX_CONNECTION_SECONDS = 1_800L
        const val TICK_MILLIS = 1_000L

        /** Port 443 is `wss`'s default, so the advertised port is absent from the URL. */
        const val SOCKET_URL =
            "wss://realtime.kit.africa/app/app-key?protocol=7&client=kit-android&version=1"

        const val NUDGE_FRAME =
            """{"event":"kit.sync.nudge","channel":"$USER_CHANNEL","data":{"v":1}}"""

        fun typingFrame(user: String) =
            """{"event":"kit.typing","channel":"$CONVERSATION_CHANNEL","data":{"v":1,"user":"$user"}}"""

        fun memberRemoved(user: String) =
            """{"event":"pusher_internal:member_removed","channel":"$CONVERSATION_CHANNEL",""" +
                """"data":{"user_id":"$user"}}"""

        fun protocolError(code: Int) =
            """{"event":"pusher:error","data":"{\"code\":$code,\"message\":\"nope\"}"}"""

        /**
         * `KitWalletApi` is a 105-method Retrofit interface, so it is faked with a
         * reflection proxy answering exactly the one call this makes — the approach
         * `AppCapabilitiesViewModelTest` already uses. Returning the envelope
         * directly is how a suspend function reports "completed without suspending"
         * across the JVM's `Continuation` calling convention.
         */
        fun capabilitiesApi(advertise: Boolean, typing: Boolean): KitWalletApi = Proxy.newProxyInstance(
            KitWalletApi::class.java.classLoader,
            arrayOf(KitWalletApi::class.java),
        ) { instance, method, arguments ->
            when (method.name) {
                "capabilities" -> capabilitiesEnvelope(advertise, typing)
                "toString" -> "FakeCapabilitiesApi"
                "hashCode" -> System.identityHashCode(instance)
                "equals" -> instance === arguments?.firstOrNull()
                else -> error("Unexpected API call: ${method.name}")
            }
        } as KitWalletApi

        fun capabilitiesEnvelope(advertise: Boolean, typing: Boolean) = ApiEnvelope(
            ok = true,
            data = CapabilitiesDto(
                apiVersion = "v1",
                currency = CurrencyDto(code = "UGX", scale = "2"),
                protocols = ProtocolsDto(
                    realtime = if (!advertise) {
                        null
                    } else {
                        RealtimeProtocolDto(
                            v = 1,
                            scheme = "wss",
                            host = "realtime.kit.africa",
                            port = 443,
                            path = "/app/app-key",
                            key = "app-key",
                            protocol = 7,
                            authPath = "/api/kit-wallet/v1/messaging/realtime/auth",
                            activityTimeoutSeconds = ACTIVITY_TIMEOUT,
                            maxConnectionSeconds = MAX_CONNECTION_SECONDS.toInt(),
                            channels = RealtimeChannelsDto(
                                user = "private-kit.user.{user}",
                                conversation = "presence-kit.conv.{conversation}",
                            ),
                            presence = true,
                            typing = typing,
                        )
                    },
                ),
            ),
        )
    }
}
