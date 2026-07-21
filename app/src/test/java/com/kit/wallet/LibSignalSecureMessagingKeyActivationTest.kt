package com.kit.wallet

import com.kit.wallet.data.messaging.LibSignalSecureMessagingCryptoEngine
import com.kit.wallet.data.messaging.LibSignalSecureMessagingKeyActivation
import com.kit.wallet.data.messaging.RemoteSecureMessagingTransport
import com.kit.wallet.data.messaging.SecureMessagingKeyReconciliationException
import com.kit.wallet.data.messaging.SecureMessagingLifecycleGuard
import com.kit.wallet.data.messaging.SecureMessagingQuarantineReason
import com.kit.wallet.data.messaging.SecureMessagingRecord
import com.kit.wallet.data.messaging.SecureMessagingRecordPage
import com.kit.wallet.data.messaging.SecureMessagingRecordVersion
import com.kit.wallet.data.messaging.SecureMessagingSessionBinding
import com.kit.wallet.data.messaging.SecureMessagingSessionFence
import com.kit.wallet.data.messaging.SecureMessagingStateConflictException
import com.kit.wallet.data.messaging.SecureMessagingStateStore
import com.kit.wallet.data.messaging.SecureMessagingStateWrite
import com.kit.wallet.data.messaging.validateSecureMessagingNamespacePageRequest
import com.kit.wallet.data.remote.ApiCallExecutor
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.remote.MessagingKeyStatusDto
import com.kit.wallet.data.remote.MessagingKeyTransparencyDto
import com.kit.wallet.data.remote.PublishMessagingKeyBundleRequest
import com.kit.wallet.data.remote.SecureMessagingWireApi
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
        assertNotNull(firstEnrollment.pendingPublication)

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
            assertEquals(committedVersion, stateStore.version(PROTOCOL_NAMESPACE, PROTOCOL_RECORD_KEY))
            assertTrue(keyServer.requireStatus().enrolled == true)
            assertTrue(keyServer.requireStatus().needsReplenishment == false)
        }

    @Test
    fun `server identity and registration mismatches never overwrite local enrollment`() = runTest {
        val active = openKeyPreparation()
        val activation = LibSignalSecureMessagingKeyActivation(engine)
        activation.reconcile(active.session)
        val publicationsBefore = keyServer.publishRequests().size
        val valid = keyServer.requireStatus()

        val wrongIdentity = "0".repeat(64)
        keyServer.replaceStatus(
            valid.copy(
                identityKeySha256 = wrongIdentity,
                transparency = valid.transparency?.copy(identityKeySha256 = wrongIdentity),
            ),
        )
        val identityFailure = runCatching { activation.reconcile(active.session) }.exceptionOrNull()
            as? SecureMessagingKeyReconciliationException
        assertEquals(SecureMessagingQuarantineReason.IDENTITY_CHANGED, identityFailure?.quarantineReason)
        assertEquals(publicationsBefore, keyServer.publishRequests().size)

        val validRegistration = checkNotNull(valid.registrationId)
        val wrongRegistration = if (validRegistration == 16_380) {
            validRegistration - 1
        } else {
            validRegistration + 1
        }
        keyServer.replaceStatus(valid.copy(registrationId = wrongRegistration))
        val registrationFailure = runCatching { activation.reconcile(active.session) }.exceptionOrNull()
            as? SecureMessagingKeyReconciliationException
        assertEquals(
            SecureMessagingQuarantineReason.REPLAY_OR_ROLLBACK,
            registrationFailure?.quarantineReason,
        )
        assertEquals(publicationsBefore, keyServer.publishRequests().size)
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
        private var revision = 0
        var failNextPublications: Int = 0

        override fun dispatch(request: RecordedRequest): MockResponse = when {
            request.path == "/api/kit-wallet/v1/capabilities" -> jsonResponse(READY_CAPABILITIES)
            request.path == "/api/kit-wallet/v1/profile" -> jsonResponse(PROFILE)
            request.path == "/api/kit-wallet/v1/devices" -> jsonResponse(DEVICES)
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

        override suspend fun read(namespace: String, recordKey: String): SecureMessagingRecord? =
            records[namespace to recordKey]?.let { stored ->
                SecureMessagingRecord(
                    namespace,
                    recordKey,
                    stored.version,
                    stored.bytes.copyOf(),
                    stored.updatedAt,
                )
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

    private companion object {
        const val CURRENT_USER_ID = "11111111-1111-4111-8111-111111111111"
        const val CURRENT_DEVICE_ID = "44444444-4444-4444-8444-444444444444"
        const val TIMESTAMP = "2026-07-20T08:00:00Z"
        const val PROTOCOL_NAMESPACE = "libsignal-v2"
        const val PROTOCOL_RECORD_KEY = "active-protocol-state"
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
