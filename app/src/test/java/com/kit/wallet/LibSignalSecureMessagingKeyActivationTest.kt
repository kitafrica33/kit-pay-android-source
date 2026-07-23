package com.kit.wallet

import com.kit.wallet.data.auth.AuthRepository
import com.kit.wallet.data.auth.DeviceIdentityProvider
import com.kit.wallet.data.auth.SecureMessagingEnrollmentResetTarget
import com.kit.wallet.data.messaging.LibSignalCompanionStateReader
import com.kit.wallet.data.messaging.LibSignalSecureMessagingCryptoEngine
import com.kit.wallet.data.messaging.LibSignalSecureMessagingKeyActivation
import com.kit.wallet.data.messaging.RealSecureMessagingInitialSyncActivation
import com.kit.wallet.data.messaging.RealSecureMessagingSyncEngine
import com.kit.wallet.data.messaging.RemoteSecureMessagingTransport
import com.kit.wallet.data.messaging.SecureMessagingActivationCoordinator
import com.kit.wallet.data.messaging.SecureMessagingActiveSessionRegistry
import com.kit.wallet.data.messaging.SecureMessagingAuthBindingResolver
import com.kit.wallet.data.messaging.SecureMessagingAuthenticationEpochChangedException
import com.kit.wallet.data.messaging.SecureMessagingEventProcessor
import com.kit.wallet.data.messaging.SecureMessagingFreshAuthenticationRequiredException
import com.kit.wallet.data.messaging.SecureMessagingKeyReconciliationException
import com.kit.wallet.data.messaging.SecureMessagingReauthenticationRequiredException
import com.kit.wallet.data.messaging.SecureMessagingLocalEnrollmentUnavailableException
import com.kit.wallet.data.messaging.SecureMessagingLifecycleGuard
import com.kit.wallet.data.messaging.SecureMessagingProjectionStore
import com.kit.wallet.data.messaging.SecureMessagingQuarantineReason
import com.kit.wallet.data.messaging.SecureMessagingRecord
import com.kit.wallet.data.messaging.SecureMessagingRecordKeyPermanentlyMissingException
import com.kit.wallet.data.messaging.SecureMessagingRecordKeyTemporarilyUnavailableException
import com.kit.wallet.data.messaging.SecureMessagingRecordPage
import com.kit.wallet.data.messaging.SecureMessagingRecordVersion
import com.kit.wallet.data.messaging.SecureMessagingRevalidationRetryException
import com.kit.wallet.data.messaging.SecureMessagingRuntimeStage
import com.kit.wallet.data.messaging.SecureMessagingSessionLifecycle
import com.kit.wallet.data.messaging.SecureMessagingSessionBinding
import com.kit.wallet.data.messaging.SecureMessagingSessionFence
import com.kit.wallet.data.messaging.SecureMessagingStateConflictException
import com.kit.wallet.data.messaging.SecureMessagingStateStore
import com.kit.wallet.data.messaging.SecureMessagingStateUnavailableException
import com.kit.wallet.data.messaging.SecureMessagingStateWrite
import com.kit.wallet.data.messaging.SecureMessagingSyncCursorStore
import com.kit.wallet.data.messaging.validateSecureMessagingNamespacePageRequest
import com.kit.wallet.data.remote.ApiCallExecutor
import com.kit.wallet.data.remote.DeviceRegistrationDto
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.remote.MessagingKeyStatusDto
import com.kit.wallet.data.remote.MessagingKeyTransparencyDto
import com.kit.wallet.data.remote.PublishMessagingKeyBundleRequest
import com.kit.wallet.data.remote.SecureMessagingWireApi
import com.kit.wallet.data.session.ProfileSetupState
import com.kit.wallet.data.session.SecureMessagingResetProofFence
import com.kit.wallet.data.session.SessionFence
import com.kit.wallet.data.session.SessionInvalidatedException
import com.kit.wallet.data.session.SessionSnapshot
import com.kit.wallet.data.session.SessionStore
import com.kit.wallet.data.session.SessionTokens
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.lang.reflect.Proxy
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class LibSignalSecureMessagingKeyActivationTest {
    private lateinit var server: MockWebServer
    private lateinit var remote: RemoteSecureMessagingTransport
    private lateinit var keyServer: KeyServerDispatcher
    private lateinit var stateStore: KeyActivationStateStore
    private lateinit var engine: LibSignalSecureMessagingCryptoEngine

    @Before
    fun setUp() {
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        keyServer = KeyServerDispatcher(moshi)
        server = MockWebServer().apply {
            dispatcher = keyServer
            start()
        }
        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
        remote = RemoteSecureMessagingTransport(
            retrofit.create(KitWalletApi::class.java),
            retrofit.create(SecureMessagingWireApi::class.java),
            ApiCallExecutor(moshi),
        )
        stateStore = KeyActivationStateStore()
        engine = LibSignalSecureMessagingCryptoEngine(stateStore)
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `initial enrollment is bounded idempotent and replenishes both inventories`() = runTest {
        val active = openKeyPreparation()
        var activation = LibSignalSecureMessagingKeyActivation(engine)

        activation.reconcile(active.session)

        val initial = keyServer.publishRequests().single()
        assertEquals(100, initial.oneTimePrekeys.size)
        assertEquals(100, initial.pqPrekeys.size)
        assertTrue(keyServer.requireStatus().enrolled == true)
        assertTrue(keyServer.requireStatus().needsReplenishment == false)
        val firstEnrollment = checkNotNull(active.session.localEnrollment(engine))
        assertNull(firstEnrollment.pendingPublication)

        activation.reconcile(active.session)
        assertEquals(1, keyServer.publishRequests().size)

        keyServer.consumeAllOneTimeInventory()
        activation.reconcile(active.session)

        val replenished = keyServer.publishRequests().last()
        assertEquals(2, keyServer.publishRequests().size)
        assertEquals(initial.registrationId, replenished.registrationId)
        assertEquals(initial.identityKey, replenished.identityKey)
        assertTrue(replenished.signedPrekey.prekeyId > initial.signedPrekey.prekeyId)
        assertTrue(
            replenished.pqLastResortPrekey.prekeyId >
                initial.pqLastResortPrekey.prekeyId,
        )
        assertTrue(keyServer.requireStatus().needsReplenishment == false)
        assertEquals(
            keyServer.requireStatus().bundleVersion,
            active.session.reconciledKeyIdentityResetTarget().bundleVersion,
        )

        // A reconstructed engine sees the same durable enrollment and does no extra publication.
        engine = LibSignalSecureMessagingCryptoEngine(stateStore)
        activation = LibSignalSecureMessagingKeyActivation(engine)
        activation.reconcile(active.session)
        assertEquals(2, keyServer.publishRequests().size)
    }

    @Test
    fun `interrupted publication retries exact durable bundle without growing local key state`() =
        runTest {
            val active = openKeyPreparation()
            keyServer.failNextPublications = 3
            var activation = LibSignalSecureMessagingKeyActivation(engine)

            assertTrue(runCatching { activation.reconcile(active.session) }.isFailure)
            val committedVersion = checkNotNull(stateStore.version(PROTOCOL_NAMESPACE, PROTOCOL_RECORD_KEY))
            val committedEnrollment = checkNotNull(active.session.localEnrollment(engine))
            val committedSignedId = committedEnrollment.signedPrekeyIds().maxOrNull()
            val committedPqId = committedEnrollment.pqLastResortPrekeyIds().maxOrNull()

            // Simulate process reconstruction between failures; pending public material is durable.
            engine = LibSignalSecureMessagingCryptoEngine(stateStore)
            activation = LibSignalSecureMessagingKeyActivation(engine)
            repeat(2) {
                assertTrue(runCatching { activation.reconcile(active.session) }.isFailure)
                assertEquals(
                    committedVersion,
                    stateStore.version(PROTOCOL_NAMESPACE, PROTOCOL_RECORD_KEY),
                )
                val enrollment = checkNotNull(active.session.localEnrollment(engine))
                assertEquals(committedSignedId, enrollment.signedPrekeyIds().maxOrNull())
                assertEquals(committedPqId, enrollment.pqLastResortPrekeyIds().maxOrNull())
            }

            activation.reconcile(active.session)

            val attempts = keyServer.publishRequests()
            assertEquals(4, attempts.size)
            assertTrue(attempts.all { it == attempts.first() })
            assertEquals(
                committedVersion + 1L,
                stateStore.version(PROTOCOL_NAMESPACE, PROTOCOL_RECORD_KEY),
            )
            assertNull(checkNotNull(active.session.localEnrollment(engine)).pendingPublication)
            assertTrue(keyServer.requireStatus().enrolled == true)
            assertTrue(keyServer.requireStatus().needsReplenishment == false)
        }

    @Test
    fun `server enrollment without its local private identity requires reauthentication`() =
        runTest {
            val active = openKeyPreparation()
            LibSignalSecureMessagingKeyActivation(engine).reconcile(active.session)
            val publicationsBeforeErasure = keyServer.publishRequests().size

            stateStore.eraseAll()
            engine = LibSignalSecureMessagingCryptoEngine(stateStore)

            val failure = runCatching {
                LibSignalSecureMessagingKeyActivation(engine).reconcile(active.session)
            }.exceptionOrNull()

            assertTrue(failure is SecureMessagingReauthenticationRequiredException)
            assertEquals(publicationsBeforeErasure, keyServer.publishRequests().size)
        }

    @Test
    fun `unavailable local private enrollment with a server bundle requires reauthentication`() =
        runTest {
            val active = openKeyPreparation()
            LibSignalSecureMessagingKeyActivation(engine).reconcile(active.session)
            val publicationsBeforeFailure = keyServer.publishRequests().size
            stateStore.readsUnavailable = true

            val failure = runCatching {
                LibSignalSecureMessagingKeyActivation(engine).reconcile(active.session)
            }.exceptionOrNull()

            assertTrue(failure is SecureMessagingReauthenticationRequiredException)
            assertTrue(failure?.cause is SecureMessagingLocalEnrollmentUnavailableException)
            assertEquals(SecureMessagingRuntimeStage.PREPARING_KEYS, active.lifecycle.snapshot().stage)
            assertEquals(publicationsBeforeFailure, keyServer.publishRequests().size)
        }

    @Test
    fun `temporarily hidden Android 9 record key retries without resetting server enrollment`() =
        runTest {
            val active = openKeyPreparation()
            LibSignalSecureMessagingKeyActivation(engine).reconcile(active.session)
            val publicationsBeforeFailure = keyServer.publishRequests().size
            val statusBeforeFailure = keyServer.requireStatus()
            stateStore.unavailableCause = SecureMessagingRecordKeyTemporarilyUnavailableException()
            stateStore.readsUnavailable = true

            val failure = runCatching {
                LibSignalSecureMessagingKeyActivation(engine).reconcile(active.session)
            }.exceptionOrNull()

            assertTrue(failure is SecureMessagingRevalidationRetryException)
            assertEquals(publicationsBeforeFailure, keyServer.publishRequests().size)
            assertEquals(statusBeforeFailure, keyServer.requireStatus())
            assertEquals(SecureMessagingRuntimeStage.PREPARING_KEYS, active.lifecycle.snapshot().stage)
        }

    @Test
    fun `lost record key completes enrolled-server reset before publishing one successor`() =
        runTest {
            var failNextCursorWrite = false
            val faultingStateStore = object : SecureMessagingStateStore by stateStore {
                override suspend fun write(
                    namespace: String,
                    recordKey: String,
                    expectedVersion: Long?,
                    bytes: ByteArray,
                ): SecureMessagingRecordVersion {
                    if (failNextCursorWrite &&
                        namespace == "messaging-sync" &&
                        recordKey == "cursor-v1"
                    ) {
                        failNextCursorWrite = false
                        throw SecureMessagingRecordKeyPermanentlyMissingException()
                    }
                    return stateStore.write(namespace, recordKey, expectedVersion, bytes)
                }
            }
            val crypto = LibSignalSecureMessagingCryptoEngine(faultingStateStore)
            val lifecycle = SecureMessagingLifecycleGuard()
            val sessionLifecycle = SecureMessagingSessionLifecycle(
                faultingStateStore,
                lifecycle,
            )
            sessionLifecycle.afterSessionSave()
            val sessionDelegate = ProofSessionStore(resetProof = null)
            val resetFences = mutableListOf<SecureMessagingSessionFence>()
            val sessions = object : SessionStore by sessionDelegate {
                override suspend fun resetSecureMessagingStateIfCurrent(
                    expected: SessionFence,
                    activationFence: SecureMessagingSessionFence,
                    allowPermanentlyUnavailableSnapshot: Boolean,
                    finalMessagingSnapshot: suspend () -> Unit,
                ): Boolean {
                    if (current()?.fence() != expected) return false
                    sessionLifecycle.resetForRecovery(
                        fence = activationFence,
                        allowPermanentlyUnavailableSnapshot =
                            allowPermanentlyUnavailableSnapshot,
                        finalSnapshot = finalMessagingSnapshot,
                    )
                    if (current()?.fence() != expected) return false
                    sessionLifecycle.afterSessionSave()
                    resetFences += activationFence
                    return true
                }
            }
            val registry = SecureMessagingActiveSessionRegistry(lifecycle)
            val processor = SecureMessagingEventProcessor(
                crypto,
                SecureMessagingProjectionStore(
                    faultingStateStore,
                    LibSignalCompanionStateReader(faultingStateStore),
                ),
                SecureMessagingSyncCursorStore(faultingStateStore),
            )
            val coordinator = SecureMessagingActivationCoordinator(
                transport = remote,
                lifecycle = lifecycle,
                sessions = registry,
                keyActivation = LibSignalSecureMessagingKeyActivation(crypto, sessions),
                initialSyncActivation = RealSecureMessagingInitialSyncActivation(processor),
            )
            var remoteResets = 0
            var remoteResetFence: SecureMessagingSessionFence? = null
            val authRepository = Proxy.newProxyInstance(
                AuthRepository::class.java.classLoader,
                arrayOf(AuthRepository::class.java),
            ) { instance, method, arguments ->
                when (method.name) {
                    "recoverMissingSecureMessagingEnrollment" -> {
                        val expected = arguments?.get(0) as SessionFence
                        val activationFence = arguments[1] as SecureMessagingSessionFence
                        val target = arguments[2] as SecureMessagingEnrollmentResetTarget
                        assertEquals(expected, sessions.current()?.fence())
                        val resultingEpoch = keyServer.resetEnrollment(target)
                        sessionDelegate.setResetProof(
                            SecureMessagingResetProofFence(
                                serverDeviceId = target.serverDeviceId,
                                previousEnrollmentEpoch = target.enrollmentEpoch,
                                resultingEnrollmentEpoch = resultingEpoch,
                                previousRegistrationId = target.registrationId,
                                previousIdentityKeySha256 = target.identityKeySha256,
                                previousBundleVersion = target.bundleVersion,
                            ),
                        )
                        remoteResetFence = activationFence
                        remoteResets++
                        Unit
                    }
                    "requireFreshAuthenticationForSecureMessagingRecovery" ->
                        error("A proved lost record key must use the exact enrollment reset")
                    "toString" -> "LostKeyRecoveryAuthRepository"
                    "hashCode" -> System.identityHashCode(instance)
                    "equals" -> instance === arguments?.firstOrNull()
                    else -> error("Unexpected auth repository call: ${method.name}")
                }
            } as AuthRepository
            val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
            val retrofit = Retrofit.Builder()
                .baseUrl(server.url("/"))
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()
            val sync = RealSecureMessagingSyncEngine(
                bindingResolver = SecureMessagingAuthBindingResolver(
                    sessions = sessions,
                    api = retrofit.create(KitWalletApi::class.java),
                    apiCalls = ApiCallExecutor(moshi),
                    deviceIdentity = object : DeviceIdentityProvider {
                        override fun registration() = DeviceRegistrationDto(
                            installationId = BINDING.installationId,
                            name = "Test phone",
                            appVersion = "1",
                            osVersion = "1",
                            model = "Test",
                        )
                    },
                ),
                activation = coordinator,
                processor = processor,
                sessions = sessions,
                sessionLifecycle = sessionLifecycle,
                authRepository = authRepository,
            )

            sync.synchronize()
            val initial = registry.requireCurrent()
            failNextCursorWrite = true

            sync.recoverPermanentlyUnavailableState(initial.fence)

            assertEquals(2, resetFences.size)
            assertTrue(resetFences[0] === initial.fence)
            assertTrue(resetFences[1] === remoteResetFence)
            assertEquals(1, remoteResets)
            assertEquals(2, keyServer.publishRequests().size)
            assertNull(sessionDelegate.current()?.messagingResetProof)
            val successor = registry.requireCurrent()
            assertTrue(successor.fence !== initial.fence)
            assertTrue(successor.fence !== remoteResetFence)
            assertEquals(SecureMessagingRuntimeStage.READY, lifecycle.snapshot().stage)
        }

    @Test
    fun `proved reset with no local state provisions at N plus one and clears proof`() = runTest {
        keyServer.setEnrollmentEpoch(RESET_RESULT_EPOCH)
        val sessions = ProofSessionStore(RESET_PROVED)
        val active = openKeyPreparation()

        LibSignalSecureMessagingKeyActivation(engine, sessions).reconcile(active.session)

        assertEquals(1, keyServer.publishRequests().size)
        assertEquals(RESET_RESULT_EPOCH, keyServer.requireStatus().enrollmentEpoch)
        assertNull(sessions.current()?.messagingResetProof)
        assertNull(checkNotNull(active.session.localEnrollment(engine)).pendingPublication)
    }

    @Test
    fun `proved reset confirms matching lost-response publication and clears proof`() = runTest {
        keyServer.setEnrollmentEpoch(RESET_RESULT_EPOCH)
        keyServer.failNextPublications = 1
        val sessions = ProofSessionStore(RESET_PROVED)
        val active = openKeyPreparation()
        val activation = LibSignalSecureMessagingKeyActivation(engine, sessions)

        assertTrue(runCatching { activation.reconcile(active.session) }.isFailure)
        assertEquals(RESET_PROVED, sessions.current()?.messagingResetProof)
        assertTrue(checkNotNull(active.session.localEnrollment(engine)).pendingPublication != null)
        keyServer.acceptLastPublication()

        activation.reconcile(active.session)

        assertEquals(1, keyServer.publishRequests().size)
        assertNull(sessions.current()?.messagingResetProof)
        assertNull(checkNotNull(active.session.localEnrollment(engine)).pendingPublication)
    }

    @Test
    fun `proved reset rejects a replacement enrollment without clearing its proof`() = runTest {
        keyServer.setEnrollmentEpoch(RESET_RESULT_EPOCH)
        val sessions = ProofSessionStore(resetProof = null)
        val active = openKeyPreparation()
        val activation = LibSignalSecureMessagingKeyActivation(engine, sessions)
        activation.reconcile(active.session)
        val publicationsBefore = keyServer.publishRequests().size
        sessions.setResetProof(RESET_PROVED)
        val current = keyServer.requireStatus()
        val replacementIdentity = "0".repeat(64)
        keyServer.replaceStatus(
            current.copy(
                identityKeySha256 = replacementIdentity,
                transparency = current.transparency?.copy(
                    identityKeySha256 = replacementIdentity,
                ),
            ),
        )

        val failure = runCatching { activation.reconcile(active.session) }.exceptionOrNull()

        assertTrue(failure is SecureMessagingFreshAuthenticationRequiredException)
        assertEquals(publicationsBefore, keyServer.publishRequests().size)
        assertEquals(RESET_PROVED, sessions.current()?.messagingResetProof)
    }

    @Test
    fun `pending T1 ignores concurrent T2 and requests only its exact pinned reset`() = runTest {
        keyServer.setEnrollmentEpoch(RESET_RESULT_EPOCH + 1L)
        val sessions = ProofSessionStore(resetProof = null)
        val active = openKeyPreparation()
        val activation = LibSignalSecureMessagingKeyActivation(engine, sessions)
        activation.reconcile(active.session)
        val publicationsBefore = keyServer.publishRequests().size
        sessions.setResetProof(RESET_PENDING)
        stateStore.readsUnavailable = true

        val failure = runCatching { activation.reconcile(active.session) }.exceptionOrNull()
            as? SecureMessagingReauthenticationRequiredException

        assertEquals(RESET_TARGET, failure?.target)
        assertEquals(publicationsBefore, keyServer.publishRequests().size)
        assertEquals(RESET_PENDING, sessions.current()?.messagingResetProof)
    }

    @Test
    fun `server identity registration and prekey mismatches require fresh authentication`() = runTest {
        val active = openKeyPreparation()
        val activation = LibSignalSecureMessagingKeyActivation(engine)
        activation.reconcile(active.session)
        val publicationsBefore = keyServer.publishRequests().size
        val valid = keyServer.requireStatus()

        suspend fun assertMismatchRequiresFreshAuthentication(
            status: MessagingKeyStatusDto,
            reason: SecureMessagingQuarantineReason,
        ) {
            keyServer.replaceStatus(status)
            val failure = runCatching { activation.reconcile(active.session) }.exceptionOrNull()
                as? SecureMessagingFreshAuthenticationRequiredException
            val mismatch = failure?.cause as? SecureMessagingKeyReconciliationException
            assertTrue(failure?.activationFence === active.fence)
            assertEquals(reason, mismatch?.quarantineReason)
            assertEquals(publicationsBefore, keyServer.publishRequests().size)
        }

        val wrongIdentity = "0".repeat(64)
        assertMismatchRequiresFreshAuthentication(
            valid.copy(
                identityKeySha256 = wrongIdentity,
                transparency = valid.transparency?.copy(identityKeySha256 = wrongIdentity),
            ),
            SecureMessagingQuarantineReason.IDENTITY_CHANGED,
        )

        val validRegistration = checkNotNull(valid.registrationId)
        val wrongRegistration = if (validRegistration == 16_380) {
            validRegistration - 1
        } else {
            validRegistration + 1
        }
        assertMismatchRequiresFreshAuthentication(
            valid.copy(registrationId = wrongRegistration),
            SecureMessagingQuarantineReason.REPLAY_OR_ROLLBACK,
        )

        assertMismatchRequiresFreshAuthentication(
            valid.copy(signedPrekeyId = checkNotNull(valid.signedPrekeyId) + 1),
            SecureMessagingQuarantineReason.REPLAY_OR_ROLLBACK,
        )

        val wrongPqId = checkNotNull(valid.pqLastResortPrekeyId) + 1
        assertMismatchRequiresFreshAuthentication(
            valid.copy(
                pqLastResortPrekeyId = wrongPqId,
                transparency = valid.transparency?.copy(pqLastResortPrekeyId = wrongPqId),
            ),
            SecureMessagingQuarantineReason.REPLAY_OR_ROLLBACK,
        )
    }

    @Test
    fun `restarted sync clears authentication instead of quarantining a changed enrollment`() =
        runTest {
            val firstProcess = openKeyPreparation()
            LibSignalSecureMessagingKeyActivation(engine).reconcile(firstProcess.session)
            val enrolled = keyServer.requireStatus()
            val replacementIdentity = "0".repeat(64)
            keyServer.replaceStatus(
                enrolled.copy(
                    identityKeySha256 = replacementIdentity,
                    transparency = enrolled.transparency?.copy(
                        identityKeySha256 = replacementIdentity,
                    ),
                ),
            )

            // Reconstruct every process-local activation object. Only durable local libsignal
            // state and the authenticated Kit session survive this simulated process death.
            engine = LibSignalSecureMessagingCryptoEngine(stateStore)
            val lifecycle = SecureMessagingLifecycleGuard()
            val sessionLifecycle = SecureMessagingSessionLifecycle(stateStore, lifecycle)
            sessionLifecycle.afterSessionSave()
            val sessions = ProofSessionStore(
                resetProof = null,
                beforeClear = sessionLifecycle::beforeSessionClear,
            )
            val registry = SecureMessagingActiveSessionRegistry(lifecycle)
            val processor = SecureMessagingEventProcessor(
                engine,
                SecureMessagingProjectionStore(
                    stateStore,
                    LibSignalCompanionStateReader(stateStore),
                ),
                SecureMessagingSyncCursorStore(stateStore),
            )
            val coordinator = SecureMessagingActivationCoordinator(
                transport = remote,
                lifecycle = lifecycle,
                sessions = registry,
                keyActivation = LibSignalSecureMessagingKeyActivation(engine, sessions),
                initialSyncActivation = RealSecureMessagingInitialSyncActivation(processor),
            )
            val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
            val retrofit = Retrofit.Builder()
                .baseUrl(server.url("/"))
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()
            val api = retrofit.create(KitWalletApi::class.java)
            var freshAuthenticationCalls = 0
            val authRepository = Proxy.newProxyInstance(
                AuthRepository::class.java.classLoader,
                arrayOf(AuthRepository::class.java),
            ) { instance, method, arguments ->
                when (method.name) {
                    "requireFreshAuthenticationForSecureMessagingRecovery" -> {
                        assertEquals(
                            BINDING.sessionEpoch,
                            (arguments?.get(0) as com.kit.wallet.data.session.SessionFence).sessionId,
                        )
                        freshAuthenticationCalls++
                        runBlocking {
                            val expected = checkNotNull(sessions.current()).fence()
                            assertTrue(sessions.clearIfCurrent(expected))
                        }
                    }
                    "toString" -> "FreshAuthenticationAuthRepository"
                    "hashCode" -> System.identityHashCode(instance)
                    "equals" -> instance === arguments?.firstOrNull()
                    else -> error("Unexpected auth repository call: ${method.name}")
                }
            } as AuthRepository
            val sync = RealSecureMessagingSyncEngine(
                bindingResolver = SecureMessagingAuthBindingResolver(
                    sessions = sessions,
                    api = api,
                    apiCalls = ApiCallExecutor(moshi),
                    deviceIdentity = object : DeviceIdentityProvider {
                        override fun registration() = DeviceRegistrationDto(
                            installationId = BINDING.installationId,
                            name = "Test phone",
                            appVersion = "1",
                            osVersion = "1",
                            model = "Test",
                        )
                    },
                ),
                activation = coordinator,
                processor = processor,
                sessions = sessions,
                sessionLifecycle = sessionLifecycle,
                authRepository = authRepository,
            )

            val failure = runCatching { sync.synchronize() }.exceptionOrNull()

            assertTrue(failure is SecureMessagingAuthenticationEpochChangedException)
            assertEquals(1, freshAuthenticationCalls)
            assertNull(sessions.current())
            assertNull(registry.currentOrNull())
            assertEquals(SecureMessagingRuntimeStage.NO_SESSION, lifecycle.snapshot().stage)
            assertNull(stateStore.version(PROTOCOL_NAMESPACE, PROTOCOL_RECORD_KEY))
        }

    private suspend fun openKeyPreparation(): ActiveRemoteSession {
        val lifecycle = SecureMessagingLifecycleGuard()
        val fence = lifecycle.beginSession(BINDING)
        val session = remote.openSession(lifecycle, fence)
        lifecycle.beginKeyPreparation(fence)
        return ActiveRemoteSession(lifecycle, fence, session)
    }

    private data class ActiveRemoteSession(
        val lifecycle: SecureMessagingLifecycleGuard,
        val fence: SecureMessagingSessionFence,
        val session: RemoteSecureMessagingTransport.Session,
    )

    private class KeyServerDispatcher(
        moshi: Moshi,
    ) : Dispatcher() {
        private val requestAdapter = moshi.adapter(PublishMessagingKeyBundleRequest::class.java)
        private val statusAdapter = moshi.adapter(MessagingKeyStatusDto::class.java)
        private val lock = Any()
        private val publications = mutableListOf<PublishMessagingKeyBundleRequest>()
        private var currentStatus: MessagingKeyStatusDto? = null
        private var enrollmentEpoch = 1L
        private var revision = 0
        private var syncRevision = 0
        var failNextPublications: Int = 0

        override fun dispatch(request: RecordedRequest): MockResponse = when {
            request.path == "/api/kit-wallet/v1/capabilities" -> jsonResponse(READY_CAPABILITIES)
            request.path == "/api/kit-wallet/v1/profile" -> jsonResponse(PROFILE)
            request.path == "/api/kit-wallet/v1/devices" -> jsonResponse(DEVICES)
            request.path?.startsWith("/api/kit-wallet/v1/messaging/sync") == true ->
                synchronized(lock) {
                    syncRevision++
                    jsonResponse(
                        """
                        {"ok":true,"data":{"events":[],"page":{
                        "next_cursor":"key_sync_$syncRevision","has_more":false,"limit":50}}}
                        """.trimIndent(),
                    )
                }
            request.path == "/api/kit-wallet/v1/messaging/keys/status" -> synchronized(lock) {
                statusResponse(currentStatus ?: unenrolledStatus())
            }
            request.path == "/api/kit-wallet/v1/messaging/keys" && request.method == "PUT" ->
                synchronized(lock) {
                    val publication = checkNotNull(requestAdapter.fromJson(request.body.readUtf8()))
                    publications += publication
                    if (failNextPublications > 0) {
                        failNextPublications--
                        return@synchronized MockResponse()
                            .setResponseCode(503)
                            .setHeader("Content-Type", "application/json")
                            .setBody(
                                """{"ok":false,"error":{"code":"TEMPORARY_FAILURE","message":"retry"}}""",
                            )
                    }
                    currentStatus = enrolledStatus(publication)
                    statusResponse(checkNotNull(currentStatus))
                }
            else -> MockResponse().setResponseCode(404)
        }

        fun publishRequests(): List<PublishMessagingKeyBundleRequest> = synchronized(lock) {
            publications.toList()
        }

        fun requireStatus(): MessagingKeyStatusDto = synchronized(lock) {
            checkNotNull(currentStatus)
        }

        fun replaceStatus(status: MessagingKeyStatusDto) = synchronized(lock) {
            currentStatus = status
        }

        fun resetEnrollment(target: SecureMessagingEnrollmentResetTarget): Long =
            synchronized(lock) {
                val status = checkNotNull(currentStatus)
                check(status.enrolled == true)
                check(status.deviceId == target.serverDeviceId)
                check(status.enrollmentEpoch == target.enrollmentEpoch)
                check(status.registrationId == target.registrationId)
                check(status.identityKeySha256 == target.identityKeySha256)
                check(status.bundleVersion == target.bundleVersion)
                enrollmentEpoch = target.enrollmentEpoch + 1L
                currentStatus = null
                enrollmentEpoch
            }

        fun setEnrollmentEpoch(epoch: Long) = synchronized(lock) {
            require(epoch > 0L)
            check(currentStatus == null) { "Set the enrollment epoch before creating status" }
            enrollmentEpoch = epoch
        }

        fun acceptLastPublication() = synchronized(lock) {
            currentStatus = enrolledStatus(publications.last())
        }

        fun consumeAllOneTimeInventory() = synchronized(lock) {
            currentStatus = checkNotNull(currentStatus).copy(
                availableOneTimePrekeys = 0,
                availableEcOneTimePrekeys = 0,
                availablePqOneTimePrekeys = 0,
                needsReplenishment = true,
            )
        }

        private fun statusResponse(status: MessagingKeyStatusDto): MockResponse =
            jsonResponse("""{"ok":true,"data":${statusAdapter.toJson(status)}}""")

        private fun enrolledStatus(
            publication: PublishMessagingKeyBundleRequest,
        ): MessagingKeyStatusDto {
            revision++
            val identityHash = digestBase64(publication.identityKey)
            val signedHash = digestBase64(publication.signedPrekey.publicKey)
            val pqHash = digestBase64(publication.pqLastResortPrekey.publicKey)
            return MessagingKeyStatusDto(
                enrolled = true,
                enrollmentEpoch = enrollmentEpoch,
                deviceId = CURRENT_DEVICE_ID,
                signalDeviceId = 1,
                protocolVersion = "v2",
                registrationId = publication.registrationId,
                identityKeySha256 = identityHash,
                signedPrekeyId = publication.signedPrekey.prekeyId,
                signedPrekeySha256 = signedHash,
                pqLastResortPrekeyId = publication.pqLastResortPrekey.prekeyId,
                pqLastResortPrekeySha256 = pqHash,
                bundleVersion = revision,
                availableOneTimePrekeys = publication.oneTimePrekeys.size,
                availableEcOneTimePrekeys = publication.oneTimePrekeys.size,
                availablePqOneTimePrekeys = publication.pqPrekeys.size,
                replenishAt = 20,
                needsReplenishment = false,
                publishedAt = TIMESTAMP,
                rotatedAt = if (revision == 1) null else TIMESTAMP,
                transparency = MessagingKeyTransparencyDto(
                    revision = revision.toString(),
                    eventType = if (revision == 1) "device.enrolled" else "signed_prekey.rotated",
                    protocolVersion = "v2",
                    eventHash = sha256("event-$revision".toByteArray()),
                    identityKeySha256 = identityHash,
                    pqLastResortPrekeyId = publication.pqLastResortPrekey.prekeyId,
                    pqLastResortPrekeySha256 = pqHash,
                    occurredAt = TIMESTAMP,
                ),
            )
        }

        private fun unenrolledStatus() = MessagingKeyStatusDto(
            enrolled = false,
            enrollmentEpoch = enrollmentEpoch,
            protocolVersion = "v2",
            availableOneTimePrekeys = 0,
            availableEcOneTimePrekeys = 0,
            availablePqOneTimePrekeys = 0,
            replenishAt = 20,
            needsReplenishment = true,
        )

        private fun digestBase64(value: String): String =
            sha256(Base64.getDecoder().decode(value))

        private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(value)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

        private fun jsonResponse(body: String) = MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(body)
    }

    private class KeyActivationStateStore : SecureMessagingStateStore {
        private data class Stored(
            val version: Long,
            val bytes: ByteArray,
            val updatedAt: Long,
        )

        private val records = mutableMapOf<Pair<String, String>, Stored>()
        private var clock = 1_000L
        var readsUnavailable = false
        var unavailableCause: Throwable = IllegalStateException("injected storage failure")

        override suspend fun read(namespace: String, recordKey: String): SecureMessagingRecord? {
            if (readsUnavailable) {
                throw SecureMessagingStateUnavailableException(
                    "injected unavailable state",
                    unavailableCause,
                )
            }
            return records[namespace to recordKey]?.let { stored ->
                SecureMessagingRecord(
                    namespace,
                    recordKey,
                    stored.version,
                    stored.bytes.copyOf(),
                    stored.updatedAt,
                )
            }
        }

        override suspend fun readNamespacePage(
            namespace: String,
            afterRecordKey: String?,
            limit: Int,
        ): SecureMessagingRecordPage {
            validateSecureMessagingNamespacePageRequest(namespace, afterRecordKey, limit)
            val candidates = records.entries
                .filter { (address, _) ->
                    address.first == namespace &&
                        (afterRecordKey == null || address.second > afterRecordKey)
                }
                .sortedBy { it.key.second }
            val selected = candidates.take(limit).map { (address, stored) ->
                SecureMessagingRecord(
                    address.first,
                    address.second,
                    stored.version,
                    stored.bytes.copyOf(),
                    stored.updatedAt,
                )
            }
            return SecureMessagingRecordPage(
                selected,
                if (candidates.size > limit) selected.last().recordKey else null,
            )
        }

        override suspend fun write(
            namespace: String,
            recordKey: String,
            expectedVersion: Long?,
            bytes: ByteArray,
        ): SecureMessagingRecordVersion = writeBatch(
            listOf(SecureMessagingStateWrite(namespace, recordKey, expectedVersion, bytes)),
        ).single()

        override suspend fun writeBatch(
            writes: List<SecureMessagingStateWrite>,
        ): List<SecureMessagingRecordVersion> {
            require(writes.isNotEmpty())
            val versions = writes.map { write ->
                val current = records[write.namespace to write.recordKey]
                when {
                    current == null && write.expectedVersion == null -> 1L
                    current != null && current.version == write.expectedVersion -> current.version + 1
                    else -> throw SecureMessagingStateConflictException("version mismatch")
                }
            }
            writes.zip(versions).forEach { (write, version) ->
                records.put(
                    write.namespace to write.recordKey,
                    Stored(version, write.copyBytes(), clock++),
                )?.bytes?.fill(0)
            }
            return writes.zip(versions).map { (write, version) ->
                SecureMessagingRecordVersion(write.namespace, write.recordKey, version)
            }
        }

        override suspend fun deleteNamespace(namespace: String) {
            val targets = records.keys.filter { it.first == namespace }
            targets.forEach { key -> records.remove(key)?.bytes?.fill(0) }
        }

        override suspend fun eraseAll() {
            records.values.forEach { it.bytes.fill(0) }
            records.clear()
        }

        fun version(namespace: String, recordKey: String): Long? =
            records[namespace to recordKey]?.version
    }

    private class ProofSessionStore(
        resetProof: SecureMessagingResetProofFence?,
        private val beforeClear: suspend () -> Unit = {},
    ) : SessionStore {
        private val mutableSession = MutableStateFlow<SessionTokens?>(
            SessionTokens(
                accessToken = "access",
                refreshToken = "refresh",
                sessionId = BINDING.sessionEpoch,
                messagingResetProof = resetProof,
            ),
        )
        private var revision = 0L

        override val session: StateFlow<SessionTokens?> = mutableSession

        override fun current(): SessionTokens? = mutableSession.value

        override fun snapshot(): SessionSnapshot = SessionSnapshot(
            revision = revision,
            fence = current()?.fence(),
        )

        override suspend fun save(tokens: SessionTokens) {
            mutableSession.value = tokens
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
            val current = current() ?: return false
            if (current.fence() != expected) return false
            save(current.copy(profileSetupState = state))
            return true
        }

        override suspend fun <T> withCurrentSession(
            expected: SessionFence,
            block: suspend (SessionTokens) -> T,
        ): T {
            val current = current() ?: throw SessionInvalidatedException()
            if (current.fence() != expected) throw SessionInvalidatedException()
            return block(current)
        }

        override suspend fun clearIfCurrent(expected: SessionFence): Boolean {
            if (current()?.fence() != expected) return false
            clear()
            return true
        }

        override suspend fun clearMessagingResetProofIfCurrent(
            expected: SessionFence,
            proof: SecureMessagingResetProofFence,
        ): Boolean {
            val current = current() ?: return false
            if (current.fence() != expected || current.messagingResetProof != proof) return false
            save(current.copy(messagingResetProof = null))
            return true
        }

        override suspend fun clear() {
            beforeClear()
            mutableSession.value = null
            revision++
        }

        fun setResetProof(proof: SecureMessagingResetProofFence) {
            mutableSession.value = checkNotNull(current()).copy(messagingResetProof = proof)
            revision++
        }
    }

    private companion object {
        const val CURRENT_USER_ID = "11111111-1111-4111-8111-111111111111"
        const val CURRENT_DEVICE_ID = "44444444-4444-4444-8444-444444444444"
        const val TIMESTAMP = "2026-07-20T08:00:00Z"
        const val PROTOCOL_NAMESPACE = "libsignal-v2"
        const val PROTOCOL_RECORD_KEY = "active-protocol-state"
        const val RESET_RESULT_EPOCH = 8L
        val RESET_TARGET = com.kit.wallet.data.auth.SecureMessagingEnrollmentResetTarget(
            serverDeviceId = CURRENT_DEVICE_ID,
            enrollmentEpoch = RESET_RESULT_EPOCH - 1L,
            registrationId = 42,
            identityKeySha256 = "1".repeat(64),
            bundleVersion = 3,
        )
        val RESET_PENDING = SecureMessagingResetProofFence(
            serverDeviceId = RESET_TARGET.serverDeviceId,
            previousEnrollmentEpoch = RESET_TARGET.enrollmentEpoch,
            previousRegistrationId = RESET_TARGET.registrationId,
            previousIdentityKeySha256 = RESET_TARGET.identityKeySha256,
            previousBundleVersion = RESET_TARGET.bundleVersion,
        )
        val RESET_PROVED = RESET_PENDING.copy(resultingEnrollmentEpoch = RESET_RESULT_EPOCH)
        val BINDING = SecureMessagingSessionBinding(
            sessionEpoch = "key-activation-epoch",
            userId = CURRENT_USER_ID,
            serverDeviceId = CURRENT_DEVICE_ID,
            installationId = "key-activation-installation",
        )
        const val READY_CAPABILITIES = """
            {"ok":true,"data":{"api_version":"v1","currency":{"code":"UGX","scale":"2"},
            "features":{"messaging":true},"authentication":{},"protocols":{"messaging":{
            "ready":true,"version":"v2","suite":"signal-pqxdh-kyber1024-double-ratchet-v2",
            "post_quantum":true}}}}
        """
        const val PROFILE = """
            {"ok":true,"data":{"id":"$CURRENT_USER_ID","name":"Kit User"}}
        """
        const val DEVICES = """
            {"ok":true,"data":{"items":[{"id":"$CURRENT_DEVICE_ID","name":"Android phone",
            "platform":"android","is_current":true,"created_at":"$TIMESTAMP",
            "last_seen_at":"$TIMESTAMP"}]}}
        """
    }
}
