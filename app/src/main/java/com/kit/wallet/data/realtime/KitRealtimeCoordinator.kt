package com.kit.wallet.data.realtime

import com.kit.wallet.data.remote.ApiCallExecutor
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.session.SessionStore
import com.kit.wallet.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import retrofit2.Response

/**
 * The state machine: one socket, driven by one coroutine, with every piece of
 * mutable state confined to it.
 *
 * Everything that can move the connection — a lifecycle callback, a connectivity
 * change, a frame off the socket's reader thread, a screen becoming visible, a
 * one-second tick — arrives as a [Command] on a single channel and is applied in
 * arrival order by [run]. Nothing else touches the fields below. That is what
 * makes the hard cases tractable: a failure callback from a socket we already
 * replaced, a subscribe racing an unsubscribe, and a network change arriving
 * mid-handshake are all just messages, and none of them can interleave with each
 * other halfway through a transition.
 *
 * Four invariants are load-bearing:
 *
 * - **Foreground only.** No socket exists in the background, which is why this
 *   feature needs no foreground service, no wake lock and no new permission, and
 *   why Doze is a non-event.
 * - **Bounded lifetime.** The connection is closed and redialled on the server's
 *   advertised schedule, which re-runs `kit.auth` on the private-channel auth POST. That, and not
 *   the socket server, is what makes session revocation, device revocation and
 *   block changes take effect.
 * - **Every** transition into `Live` requests a sync, unconditionally. That single
 *   line is the entire gap-recovery story: whatever arrived while the socket was
 *   down is picked up by the durable log the moment it comes back.
 * - **A dropped nudge is not a lost message.** Nothing here is durable and nothing
 *   here advances a cursor; the socket only ever says "come and pull".
 */
/**
 * The half of the coordinator a conversation screen is allowed to see: say which
 * conversation is on screen, and read the fallback poll interval.
 *
 * Presence and typing are deliberately **not** here. They reach the UI through
 * `ChatPreview`, folded onto the projection from the registries at the repository's
 * publication edge, so the chat list and the open conversation cannot disagree about
 * who is online. A second path from here would be a second answer.
 *
 * Deliberately narrow otherwise too. A screen cannot open a socket, cannot subscribe
 * to a channel it did not observe, and cannot reach the command loop — and, because
 * this is an interface, a ViewModel test needs no state machine, transport or
 * session store to get at three members.
 */
internal interface KitConversationSignals {
    /** Milliseconds between fallback syncs for a visible conversation, or `null`. */
    val foregroundSyncIntervalMillis: StateFlow<Long?>

    /** The conversation is on screen: watch its presence and typing while it is. */
    fun observeConversation(conversationId: String)

    /** The conversation left the screen. */
    fun stopObservingConversation(conversationId: String)
}

@Singleton
internal class KitRealtimeCoordinator @Inject constructor(
    private val transport: KitRealtimeTransport,
    private val authApi: KitRealtimeAuthApi,
    private val walletApi: KitWalletApi,
    private val apiCalls: ApiCallExecutor,
    private val sessionStore: SessionStore,
    private val foregroundMonitor: KitForegroundSource,
    private val networkMonitor: KitNetworkSource,
    private val presenceRegistry: KitPresenceRegistry,
    private val typingRegistry: KitTypingRegistry,
    private val typingSignaller: KitTypingSignaller,
    private val nudgeSink: KitRealtimeNudgeSink,
    private val fallbackPoller: KitRealtimeFallbackPoller,
    private val clock: KitRealtimeClock,
    @ApplicationScope private val scope: CoroutineScope,
) : KitConversationSignals {
    private sealed interface Command {
        data class Foregrounded(val value: Boolean) : Command

        data class SessionChanged(val epoch: String?, val userPublicId: String?) : Command

        data object NetworkAvailable : Command

        data object NetworkLost : Command

        data class SocketOpened(val generation: Long) : Command

        data class SocketFrame(val generation: Long, val text: String) : Command

        data class SocketGone(val generation: Long) : Command

        data class Handshake(
            val generation: Long,
            val socketId: String,
            val activityTimeoutSeconds: Int,
        ) : Command

        data class Authorized(
            val generation: Long,
            val channel: String,
            val auth: String,
            val channelData: String?,
        ) : Command

        data class AuthorizationFailed(
            val generation: Long,
            val channel: String,
            val status: Int?,
            val retryAfterMillis: Long?,
        ) : Command

        data class Advertised(val config: KitRealtimeConfig?) : Command

        data class Observe(val conversationId: String, val watching: Boolean) : Command

        data object Tick : Command
    }

    private val commands = Channel<Command>(Channel.UNLIMITED)

    private val connectionState = MutableStateFlow<KitRealtimeState>(KitRealtimeState.Idle)

    val state: StateFlow<KitRealtimeState> = connectionState.asStateFlow()

    override val foregroundSyncIntervalMillis: StateFlow<Long?> get() = fallbackPoller.intervalMillis

    // --- state confined to `run` ------------------------------------------------

    private var foregrounded: Boolean = false
    private var sessionEpoch: String? = null
    private var userPublicId: String? = null
    private var config: KitRealtimeConfig? = null
    private var advertisementRequested: Boolean = false

    private var socketId: String? = null
    private var userChannel: String? = null
    private var connectedAtMillis: Long = 0L
    private var lastInboundMillis: Long = 0L
    private var pingSentAtMillis: Long? = null
    private var authRetries: Int = 0

    /** conversation channel name → conversation id, for frames we are willing to read. */
    private val subscribed = mutableMapOf<String, String>()
    private val desired = mutableSetOf<String>()
    private val pending = mutableSetOf<String>()

    /**
     * Conversation channels the server refused. Dropped for the whole session epoch
     * and never retried: a 403 here means blocked, not a member, opted out or a
     * conversation the protocol does not cover, and none of those change because we
     * asked again. Presence and typing simply never render for them.
     */
    private val refused = mutableSetOf<String>()

    private var holdOverUntilMillis: Long? = null
    private var started: Boolean = false
    private var socketGeneration: Long = 0L
    private var activeSocketGeneration: Long? = null

    /**
     * Pusher permits one immediate reconnect for 4200-4299. A server repeatedly
     * issuing the same instruction before a connection stabilises is an outage,
     * not permission for an unbounded zero-delay loop, so later instructions use
     * the ordinary capped ladder until new network/session information arrives or
     * a connection remains Live for a full stability window.
     */
    private var immediateReconnectUsed: Boolean = false

    private val backoff = KitRealtimeBackoff(clock)

    private fun socketListener(generation: Long) = object : KitRealtimeTransport.Listener {
        override fun onOpen() {
            commands.trySend(Command.SocketOpened(generation))
        }

        override fun onFrame(text: String) {
            commands.trySend(Command.SocketFrame(generation, text))
        }

        override fun onClosed(code: Int, reason: String) {
            commands.trySend(Command.SocketGone(generation))
        }

        override fun onFailure(error: Throwable) {
            commands.trySend(Command.SocketGone(generation))
        }
    }

    /**
     * Registers the monitors and starts the loop. Opens no socket: that waits on
     * the app being foregrounded, a session existing, and the server advertising
     * the transport at all.
     */
    fun start() {
        if (started) return
        started = true

        scope.launch { run() }

        foregroundMonitor.start()
        networkMonitor.start()

        scope.launch {
            foregroundMonitor.foregrounded.collect { commands.send(Command.Foregrounded(it)) }
        }
        scope.launch {
            sessionStore.session
                .map { it?.cacheScopeId to it?.accountId }
                .distinctUntilChanged()
                .collect { (epoch, publicId) -> commands.send(Command.SessionChanged(epoch, publicId)) }
        }
        scope.launch {
            networkMonitor.events.collect {
                commands.send(
                    when (it) {
                        KitNetworkEvent.Available -> Command.NetworkAvailable
                        KitNetworkEvent.Lost -> Command.NetworkLost
                    },
                )
            }
        }
        scope.launch {
            while (true) {
                delay(TICK_MILLIS)
                commands.send(Command.Tick)
            }
        }
    }

    override fun observeConversation(conversationId: String) {
        commands.trySend(Command.Observe(conversationId, watching = true))
    }

    override fun stopObservingConversation(conversationId: String) {
        commands.trySend(Command.Observe(conversationId, watching = false))
    }

    // --- the loop ---------------------------------------------------------------

    private suspend fun run() {
        for (command in commands) {
            runCatching { apply(command) }
        }
    }

    private suspend fun apply(command: Command) {
        when (command) {
            is Command.Foregrounded -> {
                foregrounded = command.value
                if (foregrounded) {
                    // Coming back is new information; a ladder built while the app
                    // was not even running is not predictive of anything.
                    backoff.reset()
                    evaluate()
                } else {
                    // Explicit unsubscribes then a clean close, so peers see the dot
                    // go out in under a second rather than at the server's timeout.
                    teardown(clean = true)
                    publish(KitRealtimeState.Idle)
                }
            }

            is Command.SessionChanged -> {
                val changed = command.epoch != sessionEpoch
                sessionEpoch = command.epoch
                userPublicId = command.userPublicId
                presenceRegistry.selfPublicId = command.userPublicId
                if (changed) {
                    // A new epoch clears everything the old one was suspended or
                    // refused for: those verdicts belonged to the old credential.
                    refused.clear()
                    config = null
                    advertisementRequested = false
                    authRetries = 0
                    immediateReconnectUsed = false
                    backoff.reset()
                    teardown(clean = true)
                    publish(KitRealtimeState.Idle)
                }
                evaluate()
            }

            Command.NetworkAvailable -> {
                // Reset rather than merely retry: a new default network is a reason
                // to believe the previous failures no longer apply, and waiting out
                // a 60-second ladder after connectivity returns is the single most
                // visible way this feature can feel broken.
                backoff.reset()
                immediateReconnectUsed = false
                if (connectionState.value is KitRealtimeState.Backoff) publish(KitRealtimeState.Idle)
                evaluate()
            }

            Command.NetworkLost -> {
                if (connectionState.value is KitRealtimeState.Suspended) return
                teardown(clean = false)
                // Deliberately does not spend an attempt: there was no server to
                // fail against, and a tunnel must not cost a minute of waiting.
                enterBackoff(backoff.delayWithoutSpendingAnAttempt())
            }

            is Command.SocketOpened -> {
                if (!isCurrentSocket(command.generation)) return
                lastInboundMillis = clock.elapsedMillis()
                connectedAtMillis = lastInboundMillis
                publish(KitRealtimeState.Handshaking)
            }

            is Command.SocketFrame -> {
                if (!isCurrentSocket(command.generation)) return
                onFrame(command.generation, command.text)
            }

            is Command.SocketGone -> {
                if (!isCurrentSocket(command.generation)) return
                if (connectionState.value is KitRealtimeState.Suspended) return
                teardown(clean = false)
                enterBackoff(backoff.nextDelayMillis())
            }

            is Command.Handshake -> {
                if (!isCurrentSocket(command.generation)) return
                onHandshake(command)
            }

            is Command.Authorized -> {
                if (!isCurrentSocket(command.generation)) return
                transport.send(
                    KitPusherCodec.encodeSubscribe(command.channel, command.auth, command.channelData),
                )
            }

            is Command.AuthorizationFailed -> {
                if (!isCurrentSocket(command.generation)) return
                onAuthorizationFailed(command)
            }

            is Command.Advertised -> {
                advertisementRequested = false
                config = command.config
                fallbackPoller.update(connectionState.value, command.config != null)
                if (command.config == null) {
                    // No block, or one this build does not speak: the server-side
                    // kill switch. Back off rather than hammering /capabilities.
                    enterBackoff(backoff.nextDelayMillis(ADVERTISEMENT_RETRY_MILLIS))
                } else {
                    evaluate()
                }
            }

            is Command.Observe -> onObserve(command)

            Command.Tick -> onTick()
        }
    }

    private fun evaluate() {
        if (!foregrounded || sessionEpoch == null || userPublicId == null) return
        when (connectionState.value) {
            is KitRealtimeState.Idle -> Unit
            else -> return
        }

        val current = config
        if (current == null) {
            requestAdvertisement()
            return
        }

        socketGeneration = Math.incrementExact(socketGeneration)
        val generation = socketGeneration
        activeSocketGeneration = generation
        publish(KitRealtimeState.Connecting)
        transport.open(current.socketUrl, socketListener(generation))
    }

    private fun requestAdvertisement() {
        if (advertisementRequested) return
        advertisementRequested = true
        scope.launch {
            val advertised = runCatching {
                apiCalls.execute { walletApi.capabilities() }.protocols?.realtime
            }.getOrNull()
            commands.send(Command.Advertised(KitRealtimeConfig.from(advertised)))
        }
    }

    private fun onHandshake(command: Command.Handshake) {
        if (connectionState.value !is KitRealtimeState.Handshaking) return
        socketId = command.socketId
        publish(KitRealtimeState.Subscribing)

        // Reverb implements authenticated private/presence subscriptions but
        // not the Pusher connection-level `pusher:signin` extension. Authorize and
        // subscribe the account's private nudge channel directly. Session changes
        // tear this socket down, and the bounded connection lifetime periodically
        // re-runs this authenticated request so revocations still take effect.
        subscribeUserChannel()
    }

    private fun subscribeUserChannel() {
        val current = config ?: return
        val publicId = userPublicId ?: return
        val channel = current.userChannel(publicId)
        userChannel = channel
        authorize(channel, current.authPath)
    }

    private fun authorize(channel: String, path: String) {
        val socket = socketId ?: return
        val generation = activeSocketGeneration ?: return
        scope.launch {
            val response = runCatching {
                authApi.authorizeChannel(path, ChannelAuthRequest(socket, channel))
            }.getOrNull()

            val body = response?.body()
            if (response != null && response.isSuccessful && body?.auth != null) {
                commands.send(Command.Authorized(generation, channel, body.auth, body.channelData))
            } else {
                commands.send(
                    Command.AuthorizationFailed(
                        generation = generation,
                        channel = channel,
                        status = response?.code(),
                        retryAfterMillis = response?.retryAfterMillis(),
                    ),
                )
            }
        }
    }

    private fun onAuthorizationFailed(command: Command.AuthorizationFailed) {
        val onUserPath = command.channel == userChannel

        if (!onUserPath) {
            // A conversation channel. Everything else keeps working: the account's
            // own nudge channel stays subscribed, messages keep arriving, and only
            // presence and typing for this one conversation go dark.
            pending.remove(command.channel)
            refused += command.channel
            subscribed[command.channel]?.let { forgetConversation(it) }
            subscribed.remove(command.channel)
            return
        }

        when (command.status) {
            // The client's own token refresh already ran inside OkHttp, so a 401
            // reaching here is a refresh that did not help. One more whole attempt,
            // then stop: a retry storm against an unusable credential helps nobody.
            HTTP_UNAUTHORIZED -> {
                authRetries++
                teardown(clean = false)
                if (authRetries >= MAX_AUTH_RETRIES) {
                    suspendConnection(KitRealtimeSuspension.Unauthenticated)
                } else {
                    enterBackoff(backoff.nextDelayMillis())
                }
            }

            HTTP_FORBIDDEN -> {
                teardown(clean = false)
                suspendConnection(KitRealtimeSuspension.Forbidden)
            }

            // Mirrors how the sync worker treats a protocol-gate 503: the feature is
            // off for this account, and asking again on a ladder is a retry storm.
            HTTP_UNAVAILABLE -> {
                teardown(clean = false)
                suspendConnection(KitRealtimeSuspension.ProtocolUnavailable)
            }

            HTTP_TOO_MANY_REQUESTS -> {
                teardown(clean = false)
                enterBackoff(backoff.nextDelayMillis(command.retryAfterMillis ?: 0L))
            }

            else -> {
                teardown(clean = false)
                enterBackoff(backoff.nextDelayMillis())
            }
        }
    }

    private fun onObserve(command: Command.Observe) {
        val current = config
        if (command.watching) {
            desired += command.conversationId
            if (current?.presenceEnabled != true) return
            subscribeConversation(command.conversationId)
        } else {
            desired -= command.conversationId
            typingSignaller.onConversationClosed(command.conversationId)
            forgetConversation(command.conversationId)

            val channel = current?.conversationChannel(command.conversationId) ?: return
            pending.remove(channel)
            if (subscribed.remove(channel) != null) {
                // Explicit rather than implicit: leaving the channel is what makes
                // the peer's dot go out immediately instead of after the server's
                // 30-second activity timeout.
                transport.send(KitPusherCodec.encodeUnsubscribe(channel))
            }
        }
    }

    private fun subscribeConversation(conversationId: String) {
        val current = config ?: return
        if (connectionState.value !is KitRealtimeState.Live) return

        val channel = current.conversationChannel(conversationId)
        if (channel in refused || channel in subscribed || channel in pending) return

        pending += channel
        authorize(channel, current.authPath)
    }

    private fun onFrame(generation: Long, text: String) {
        lastInboundMillis = clock.elapsedMillis()
        val frame = KitPusherCodec.decode(text) ?: return

        when (frame) {
            is KitRealtimeFrame.Established ->
                commands.trySend(Command.Handshake(generation, frame.socketId, frame.activityTimeoutSeconds))

            KitRealtimeFrame.Ping -> transport.send(KitPusherCodec.encodePong())

            KitRealtimeFrame.Pong -> pingSentAtMillis = null

            is KitRealtimeFrame.Failure -> onProtocolError(frame)

            is KitRealtimeFrame.SubscriptionSucceeded -> onSubscribed(frame)

            is KitRealtimeFrame.SubscriptionFailed -> {
                commands.trySend(
                    Command.AuthorizationFailed(
                        generation,
                        frame.channel,
                        frame.status,
                        retryAfterMillis = null,
                    ),
                )
            }

            is KitRealtimeFrame.MemberAdded -> subscribed[frame.channel]?.let {
                presenceRegistry.onMemberAdded(it, frame.user)
            }

            is KitRealtimeFrame.MemberRemoved -> subscribed[frame.channel]?.let {
                // Somebody who has left the channel cannot still be typing in it —
                // and the stop has to go first. The typing registry only accepts a
                // frame from somebody in the conversation's current presence roster,
                // so removing them first would have this stop rejected and leave the
                // bubble up for the rest of its six-second expiry, showing a peer as
                // offline and typing at the same time.
                typingRegistry.onTypingFrame(
                    KitRealtimeFrame.Typing(frame.channel, frame.user, active = false),
                    it,
                )
                presenceRegistry.onMemberRemoved(it, frame.user)
            }

            KitRealtimeFrame.SyncNudge -> nudgeSink.onNudge()

            // Dropped unless it arrived on a conversation channel we are subscribed
            // to right now. The registry then applies the roster and self checks.
            is KitRealtimeFrame.Typing -> subscribed[frame.channel]?.let {
                typingRegistry.onTypingFrame(frame, it)
            }
        }
    }

    private fun onSubscribed(frame: KitRealtimeFrame.SubscriptionSucceeded) {
        if (frame.channel == userChannel) {
            authRetries = 0
            goLive()
            return
        }

        val current = config ?: return
        val conversationId = current.conversationIdOf(frame.channel) ?: return
        if (conversationId !in desired) {
            // Subscribed to something we have since navigated away from.
            transport.send(KitPusherCodec.encodeUnsubscribe(frame.channel))
            return
        }

        pending.remove(frame.channel)
        subscribed[frame.channel] = conversationId
        presenceRegistry.onRoster(conversationId, frame.members)
    }

    private fun goLive() {
        publish(KitRealtimeState.Live)
        backoff.onLive()
        holdOverUntilMillis = null
        pingSentAtMillis = null
        nudgeSink.open()
        socketId?.let { if (config?.typingEnabled == true) typingSignaller.arm(it) }

        // Unconditional, on every transition into Live. Whatever arrived while the
        // socket was down is in the durable log, and this is what goes and gets it.
        nudgeSink.onNudge()

        if (config?.presenceEnabled == true) desired.forEach(::subscribeConversation)
    }

    private fun onProtocolError(frame: KitRealtimeFrame.Failure) {
        when (KitRealtimeErrorPolicy.actionFor(frame.code)) {
            KitRealtimeErrorAction.Suspend -> {
                teardown(clean = false)
                suspendConnection(KitRealtimeSuspension.ProtocolRejected)
            }

            KitRealtimeErrorAction.Backoff -> {
                teardown(clean = false)
                enterBackoff(backoff.nextDelayMillis(KitRealtimeBackoff.BASE_DELAY_MILLIS))
            }

            // Honour the first orderly reconnect instruction immediately. Repeating
            // it before a stable Live period is a server loop, so it joins the same
            // bounded ladder as other retryable protocol failures.
            KitRealtimeErrorAction.ReconnectImmediately -> {
                teardown(clean = true)
                if (!immediateReconnectUsed) {
                    immediateReconnectUsed = true
                    publish(KitRealtimeState.Idle)
                    evaluate()
                } else {
                    enterBackoff(backoff.nextDelayMillis(KitRealtimeBackoff.BASE_DELAY_MILLIS))
                }
            }
        }
    }

    private fun onTick() {
        val now = clock.elapsedMillis()

        typingRegistry.prune()

        holdOverUntilMillis?.let { until ->
            if (now >= until) {
                holdOverUntilMillis = null
                presenceRegistry.expireHoldOver()
            }
        }

        when (val current = connectionState.value) {
            is KitRealtimeState.Backoff -> if (now >= current.retryAtElapsedMillis) {
                publish(KitRealtimeState.Idle)
                evaluate()
            }

            is KitRealtimeState.Live -> {
                val advertised = config
                if (advertised != null &&
                    now - connectedAtMillis >= advertised.maxConnectionSeconds * 1_000L
                ) {
                    // The bounded lifetime. The private-channel auth POST re-runs `kit.auth` on the
                    // way back, which is how a revoked session, a revoked device or
                    // a new block stops being able to watch this conversation.
                    reconnectCleanly()
                    return
                }
                heartbeat(now)
            }

            is KitRealtimeState.Handshaking,
            is KitRealtimeState.Subscribing,
            -> if (now - connectedAtMillis >= HANDSHAKE_DEADLINE_MILLIS) {
                teardown(clean = false)
                enterBackoff(backoff.nextDelayMillis())
            }

            else -> Unit
        }
    }

    private fun heartbeat(now: Long) {
        val advertised = config ?: return
        val sentAt = pingSentAtMillis

        if (sentAt != null) {
            if (now - sentAt >= PONG_DEADLINE_MILLIS) {
                // No close handshake: the peer has already proven it is not reading.
                teardown(clean = false)
                enterBackoff(backoff.nextDelayMillis())
            }
            return
        }

        val silence = now - lastInboundMillis
        val threshold = (advertised.activityTimeoutSeconds - PING_LEAD_SECONDS).coerceAtLeast(5) * 1_000L
        if (silence < threshold) return

        pingSentAtMillis = now
        transport.send(KitPusherCodec.encodePing())
    }

    private fun reconnectCleanly() {
        // The rosters stay on screen across our own reconnect, so a connection the
        // user did not ask for does not blink every peer offline and back again.
        presenceRegistry.beginHoldOver()
        holdOverUntilMillis = clock.elapsedMillis() + KitPresenceRegistry.HOLD_OVER_MILLIS
        teardown(clean = true, keepPresence = true)
        publish(KitRealtimeState.Idle)
        evaluate()
    }

    private fun teardown(clean: Boolean, keepPresence: Boolean = false) {
        // Invalidate the coordinator generation before touching OkHttp. A callback
        // already queued by the retiring socket then cannot tear down its successor.
        activeSocketGeneration = null
        nudgeSink.close()
        typingSignaller.disarm()
        typingRegistry.clear()

        if (connectionState.value is KitRealtimeState.Live && backoff.onLeftLive()) {
            immediateReconnectUsed = false
        }

        if (clean) {
            subscribed.keys.forEach { transport.send(KitPusherCodec.encodeUnsubscribe(it)) }
            transport.close(KitRealtimeTransport.CLOSE_NORMAL, "")
        } else {
            transport.cancel()
        }

        if (!keepPresence) {
            // An unclean drop is exactly when the server's `member_removed` can lag
            // by up to a minute, so a frozen dot would be wrong for as long as it
            // was most visible.
            presenceRegistry.onHardDrop()
            holdOverUntilMillis = null
        }

        subscribed.clear()
        pending.clear()
        socketId = null
        userChannel = null
        pingSentAtMillis = null
    }

    private fun isCurrentSocket(generation: Long): Boolean =
        activeSocketGeneration == generation

    private fun forgetConversation(conversationId: String) {
        presenceRegistry.forget(conversationId)
        typingRegistry.forget(conversationId)
    }

    private fun enterBackoff(delayMillis: Long) {
        publish(KitRealtimeState.Backoff(clock.elapsedMillis() + delayMillis))
    }

    private fun suspendConnection(reason: KitRealtimeSuspension) {
        publish(KitRealtimeState.Suspended(reason))
    }

    private fun publish(next: KitRealtimeState) {
        connectionState.value = next
        fallbackPoller.update(next, config != null)
    }

    private fun Response<*>.retryAfterMillis(): Long? =
        headers()["Retry-After"]?.trim()?.toLongOrNull()?.times(1_000L)

    companion object {
        private const val TICK_MILLIS = 1_000L
        private const val HANDSHAKE_DEADLINE_MILLIS = 10_000L
        private const val PONG_DEADLINE_MILLIS = 10_000L

        /** `activity_timeout − 5`: ask before the server decides we are gone. */
        private const val PING_LEAD_SECONDS = 5

        /** A `/capabilities` that says "no socket" is not worth asking again quickly. */
        private const val ADVERTISEMENT_RETRY_MILLIS = 60_000L

        private const val MAX_AUTH_RETRIES = 2

        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val HTTP_UNAVAILABLE = 503
    }
}
