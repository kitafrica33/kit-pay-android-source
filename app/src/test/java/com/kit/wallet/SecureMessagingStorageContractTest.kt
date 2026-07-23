package com.kit.wallet

import android.security.keystore.KeyPermanentlyInvalidatedException
import com.kit.wallet.data.local.SecureMessagingRecordEntity
import com.kit.wallet.data.messaging.messagingRecordAad
import com.kit.wallet.data.messaging.SecureMessagingSessionLifecycle
import com.kit.wallet.data.messaging.SecureMessagingSessionBinding
import com.kit.wallet.data.messaging.SecureMessagingSessionFence
import com.kit.wallet.data.messaging.SecureMessagingRuntimeStage
import com.kit.wallet.data.messaging.SecureMessagingLifecycleGate
import com.kit.wallet.data.messaging.SecureMessagingLifecycleGuard
import com.kit.wallet.data.messaging.SecureMessagingIncomingNotification
import com.kit.wallet.data.messaging.SecureMessagingIncomingNotificationSink
import com.kit.wallet.data.messaging.SecureMessagingRecordKeyTemporarilyUnavailableException
import com.kit.wallet.data.messaging.SecureMessagingRecordKeyPermanentlyMissingException
import com.kit.wallet.data.messaging.SecureMessagingRecordKeyPermanentlyUnrecoverableException
import com.kit.wallet.data.messaging.SecureMessagingRecordKeyMissState
import com.kit.wallet.data.messaging.SecureMessagingRecordKeyResolution
import com.kit.wallet.data.messaging.SecureMessagingLegacyStateUnreadableException
import com.kit.wallet.data.messaging.SecureMessagingRecordAuthenticationFailedException
import com.kit.wallet.data.messaging.SecureMessagingStateEraser
import com.kit.wallet.data.messaging.SecureMessagingStateRetryableException
import com.kit.wallet.data.messaging.SecureMessagingStateUnavailableException
import com.kit.wallet.data.messaging.SecureMessagingStateWrite
import com.kit.wallet.data.messaging.classifySecureMessagingRecordKeyAccessFailure
import com.kit.wallet.data.messaging.classifySecureMessagingRecordKeyOperationFailure
import com.kit.wallet.data.messaging.completeKeystoreCipherOperation
import com.kit.wallet.data.messaging.deleteKeystoreAliasAndVerifyAbsent
import com.kit.wallet.data.messaging.eraseMessagingKeyAndRecords
import com.kit.wallet.data.messaging.isPermanentlyMissingSecureMessagingRecordKey
import com.kit.wallet.data.messaging.isRecoverableSecureMessagingStateLoss
import com.kit.wallet.data.messaging.isRetryableSecureMessagingStateFailure
import com.kit.wallet.data.messaging.isSecureMessagingRecordAuthenticationFailure
import com.kit.wallet.data.messaging.isTransientSecureMessagingRecordKeyFailure
import com.kit.wallet.data.messaging.observeSecureMessagingRecordKeyMiss
import com.kit.wallet.data.messaging.resolveSecureMessagingRecordKey
import com.kit.wallet.data.messaging.resolveSecureMessagingRecordKeyWithCreationStatus
import com.kit.wallet.data.messaging.secureMessagingStateAccessFailure
import com.kit.wallet.data.messaging.secureMessagingKeyCreationAllowed
import com.kit.wallet.data.session.eraseMessagingThenClearSession
import java.security.InvalidKeyException
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureMessagingStorageContractTest {
    @Test
    fun `keystore erasure calls delete even when alias lookup reports absent`() {
        var deleteCalls = 0

        deleteKeystoreAliasAndVerifyAbsent(
            deleteEntry = { deleteCalls++ },
            aliasExists = { false },
        )

        assertEquals(1, deleteCalls)
    }

    @Test
    fun `keystore erasure fails closed when alias remains visible after delete`() {
        var deleteCalls = 0

        val observed = runCatching {
            deleteKeystoreAliasAndVerifyAbsent(
                deleteEntry = { deleteCalls++ },
                aliasExists = { true },
            )
        }.exceptionOrNull()

        assertEquals(1, deleteCalls)
        assertTrue(observed is IllegalStateException)
    }

    @Test
    fun `retained ciphertext never permits a replacement messaging key`() {
        var creations = 0

        val failure = runCatching {
            resolveSecureMessagingRecordKey(
                allowCreation = secureMessagingKeyCreationAllowed(
                    existingRecordCount = 1,
                    writeIndex = 0,
                ),
                loadExisting = { null },
                createNew = {
                    creations++
                    "replacement-key"
                },
            )
        }.exceptionOrNull()

        assertTrue(failure is SecureMessagingRecordKeyTemporarilyUnavailableException)
        assertTrue(isRetryableSecureMessagingStateFailure(checkNotNull(failure)))
        assertEquals(0, creations)
    }

    @Test
    fun `empty multi-record batch can bootstrap a key only for its first encryption`() {
        var creations = 0

        val firstKey = resolveSecureMessagingRecordKey(
            allowCreation = secureMessagingKeyCreationAllowed(
                existingRecordCount = 0,
                writeIndex = 0,
            ),
            loadExisting = { null },
            createNew = {
                creations++
                "initial-key"
            },
        )
        val secondFailure = runCatching {
            // Simulate an Android 9 provider hiding the alias after row one was encrypted.
            resolveSecureMessagingRecordKey(
                allowCreation = secureMessagingKeyCreationAllowed(
                    existingRecordCount = 0,
                    writeIndex = 1,
                ),
                loadExisting = { null },
                createNew = {
                    creations++
                    "replacement-key"
                },
            )
        }.exceptionOrNull()

        assertEquals("initial-key", firstKey)
        assertTrue(secondFailure is SecureMessagingRecordKeyTemporarilyUnavailableException)
        assertEquals(1, creations)
    }

    @Test
    fun `empty store retries classify a present unusable alias instead of looping forever`() {
        var creations = 0
        var unusableAliasObservations = 0
        var finalFailure: Exception? = null

        repeat(4) {
            // A failed first write leaves the database empty, so creation remains permitted on
            // every retry. The already-present alias must nevertheless remain an existing key.
            val resolution = resolveSecureMessagingRecordKeyWithCreationStatus(
                allowCreation = secureMessagingKeyCreationAllowed(
                    existingRecordCount = 0,
                    writeIndex = 0,
                ),
                loadExisting = { "present-unusable-key" },
                createNew = {
                    creations++
                    SecureMessagingRecordKeyResolution("replacement-key", createdNow = true)
                },
            )
            assertFalse(resolution.createdNow)

            finalFailure = classifySecureMessagingRecordKeyOperationFailure(
                error = InvalidKeyException("Android 9 cannot initialize the existing alias"),
                keyCreatedNow = resolution.createdNow,
                classifyExistingAliasFailure = { cause ->
                    unusableAliasObservations++
                    if (unusableAliasObservations == 4) {
                        SecureMessagingRecordKeyPermanentlyUnrecoverableException(cause)
                    } else {
                        SecureMessagingRecordKeyTemporarilyUnavailableException(cause)
                    }
                },
            )
        }

        assertEquals(0, creations)
        assertEquals(4, unusableAliasObservations)
        assertTrue(finalFailure is SecureMessagingRecordKeyPermanentlyUnrecoverableException)
    }

    @Test
    fun `empty store creation permission cannot bypass a throwing existing alias lookup`() {
        var creations = 0
        var unusableAliasObservations = 0
        var finalFailure: Throwable? = null

        repeat(4) { attempt ->
            finalFailure = runCatching {
                resolveSecureMessagingRecordKeyWithCreationStatus(
                    allowCreation = true,
                    loadExisting = {
                        throw UnrecoverableKeyException(
                            "Android 9 cannot recover the existing alias",
                        )
                    },
                    createNew = {
                        creations++
                        SecureMessagingRecordKeyResolution("replacement-key", createdNow = true)
                    },
                    classifyExistingAliasFailure = { cause ->
                        unusableAliasObservations++
                        if (unusableAliasObservations == 4) {
                            SecureMessagingRecordKeyPermanentlyUnrecoverableException(cause)
                        } else {
                            SecureMessagingRecordKeyTemporarilyUnavailableException(cause)
                        }
                    },
                )
            }.exceptionOrNull()

            if (attempt < 3) {
                assertTrue(
                    finalFailure is SecureMessagingRecordKeyTemporarilyUnavailableException,
                )
            }
        }

        assertEquals(0, creations)
        assertEquals(4, unusableAliasObservations)
        assertTrue(finalFailure is SecureMessagingRecordKeyPermanentlyUnrecoverableException)
    }

    @Test
    fun `genuinely fresh alias keeps first operation failure retryable`() {
        val resolution = resolveSecureMessagingRecordKeyWithCreationStatus(
            allowCreation = true,
            loadExisting = { null },
            createNew = {
                SecureMessagingRecordKeyResolution("fresh-key", createdNow = true)
            },
        )
        val providerFailure = InvalidKeyException("fresh Android 9 key is not ready yet")
        var existingAliasClassified = false

        val classified = classifySecureMessagingRecordKeyOperationFailure(
            error = providerFailure,
            keyCreatedNow = resolution.createdNow,
            classifyExistingAliasFailure = {
                existingAliasClassified = true
                SecureMessagingRecordKeyPermanentlyUnrecoverableException(it)
            },
        )

        assertSame(providerFailure, classified)
        assertFalse(existingAliasClassified)
    }

    @Test
    fun `transient provider failure remains retryable for a ready session`() {
        val failure = secureMessagingStateAccessFailure(
            message = "state read failed",
            cause = SecureMessagingRecordKeyTemporarilyUnavailableException(),
        )

        assertTrue(failure is SecureMessagingStateRetryableException)
        assertTrue(failure is java.io.IOException)
        assertFalse(failure is SecureMessagingStateUnavailableException)
    }

    @Test
    fun `unlocked repeated missing alias becomes recoverable instead of retrying forever`() {
        var state = SecureMessagingRecordKeyMissState()
        state = observeSecureMessagingRecordKeyMiss(state, userUnlocked = true, nowEpochMillis = 1_000L)
        state = observeSecureMessagingRecordKeyMiss(state, userUnlocked = true, nowEpochMillis = 2_000L)
        state = observeSecureMessagingRecordKeyMiss(state, userUnlocked = true, nowEpochMillis = 3_000L)
        state = observeSecureMessagingRecordKeyMiss(state, userUnlocked = true, nowEpochMillis = 4_000L)
        assertFalse(state.permanentlyMissing)

        state = observeSecureMessagingRecordKeyMiss(state, userUnlocked = true, nowEpochMillis = 16_000L)

        assertTrue(state.permanentlyMissing)
        val failure = secureMessagingStateAccessFailure(
            message = "state read failed",
            cause = SecureMessagingRecordKeyPermanentlyMissingException(),
        )
        assertTrue(failure is SecureMessagingStateUnavailableException)
        assertFalse(failure is SecureMessagingStateRetryableException)
    }

    @Test
    fun `locked device and stale missing alias observations restart transient proof`() {
        val observed = observeSecureMessagingRecordKeyMiss(
            previous = SecureMessagingRecordKeyMissState(
                consecutiveUnlockedMisses = 3,
                firstUnlockedMissAtEpochMillis = 1_000L,
            ),
            userUnlocked = false,
            nowEpochMillis = 20_000L,
        )
        assertEquals(SecureMessagingRecordKeyMissState(), observed)

        val stale = observeSecureMessagingRecordKeyMiss(
            previous = SecureMessagingRecordKeyMissState(
                consecutiveUnlockedMisses = 3,
                firstUnlockedMissAtEpochMillis = 1_000L,
            ),
            userUnlocked = true,
            nowEpochMillis = 86_402_000L,
        )
        assertEquals(1, stale.consecutiveUnlockedMisses)
        assertEquals(86_402_000L, stale.firstUnlockedMissAtEpochMillis)
        assertFalse(stale.permanentlyMissing)
    }

    @Test
    fun `permanent alias proof cannot become retryable through its provider cause`() {
        val providerFailure = ProviderException("Android 9 keystore could not recover alias")
        val transient = SecureMessagingRecordKeyTemporarilyUnavailableException(providerFailure)
        val permanent = SecureMessagingRecordKeyPermanentlyMissingException(providerFailure)

        assertTrue(isTransientSecureMessagingRecordKeyFailure(transient))
        assertTrue(isRetryableSecureMessagingStateFailure(transient))
        assertFalse(isPermanentlyMissingSecureMessagingRecordKey(transient))

        assertFalse(isTransientSecureMessagingRecordKeyFailure(permanent))
        assertFalse(isRetryableSecureMessagingStateFailure(permanent))
        assertTrue(isPermanentlyMissingSecureMessagingRecordKey(permanent))
        assertTrue(
            secureMessagingStateAccessFailure("state read failed", permanent) is
                SecureMessagingStateUnavailableException,
        )
        val invalidated = KeyPermanentlyInvalidatedException()
        assertTrue(isPermanentlyMissingSecureMessagingRecordKey(invalidated))
        assertFalse(isRetryableSecureMessagingStateFailure(invalidated))
    }

    @Test
    fun `provider errors advance missing key proof only after an independent absent alias probe`() {
        val providerFailure = ProviderException("Android 9 keystore lookup failed")
        var missingAliasObservations = 0
        var unrecoverableAliasObservations = 0
        val observeMissing: (Throwable) -> RuntimeException = { cause ->
            missingAliasObservations++
            SecureMessagingRecordKeyPermanentlyMissingException(cause)
        }
        val observeUnrecoverable: (Throwable) -> RuntimeException = { cause ->
            unrecoverableAliasObservations++
            SecureMessagingRecordKeyPermanentlyUnrecoverableException(cause)
        }

        val present = classifySecureMessagingRecordKeyAccessFailure(
            cause = providerFailure,
            userAuthenticationRequired = false,
            aliasPresent = true,
            observeMissingAlias = observeMissing,
            observeUnrecoverableAlias = observeUnrecoverable,
        )
        val unknown = classifySecureMessagingRecordKeyAccessFailure(
            cause = providerFailure,
            userAuthenticationRequired = false,
            aliasPresent = null,
            observeMissingAlias = observeMissing,
            observeUnrecoverableAlias = observeUnrecoverable,
        )
        val locked = classifySecureMessagingRecordKeyAccessFailure(
            cause = providerFailure,
            userAuthenticationRequired = true,
            aliasPresent = false,
            observeMissingAlias = observeMissing,
            observeUnrecoverableAlias = observeUnrecoverable,
        )
        val absent = classifySecureMessagingRecordKeyAccessFailure(
            cause = providerFailure,
            userAuthenticationRequired = false,
            aliasPresent = false,
            observeMissingAlias = observeMissing,
            observeUnrecoverableAlias = observeUnrecoverable,
        )

        assertTrue(present is SecureMessagingRecordKeyTemporarilyUnavailableException)
        assertTrue(unknown is SecureMessagingRecordKeyTemporarilyUnavailableException)
        assertTrue(locked is SecureMessagingRecordKeyTemporarilyUnavailableException)
        assertTrue(absent is SecureMessagingRecordKeyPermanentlyMissingException)
        assertEquals(1, missingAliasObservations)
        assertEquals(0, unrecoverableAliasObservations)
    }

    @Test
    fun `present alias advances only the narrow unlocked unrecoverable key proof`() {
        val unrecoverable = UnrecoverableKeyException("Android 9 cannot recover AES material")
        val providerFailure = ProviderException("Android 9 keystore provider unavailable")
        var unrecoverableObservations = 0
        val observeUnrecoverable: (Throwable) -> RuntimeException = { cause ->
            unrecoverableObservations++
            SecureMessagingRecordKeyPermanentlyUnrecoverableException(cause)
        }
        val neverMissing: (Throwable) -> RuntimeException = {
            error("A present alias cannot advance the missing-alias proof")
        }

        val provenUnrecoverable = classifySecureMessagingRecordKeyAccessFailure(
            cause = unrecoverable,
            userAuthenticationRequired = false,
            aliasPresent = true,
            observeMissingAlias = neverMissing,
            observeUnrecoverableAlias = observeUnrecoverable,
        )
        val wrappedInvalidKey = classifySecureMessagingRecordKeyAccessFailure(
            cause = InvalidKeyException("AndroidKeyStore could not initialize the AES handle"),
            userAuthenticationRequired = false,
            aliasPresent = true,
            observeMissingAlias = neverMissing,
            observeUnrecoverableAlias = observeUnrecoverable,
        )
        val locked = classifySecureMessagingRecordKeyAccessFailure(
            cause = unrecoverable,
            userAuthenticationRequired = true,
            aliasPresent = true,
            observeMissingAlias = neverMissing,
            observeUnrecoverableAlias = observeUnrecoverable,
        )
        val genericProviderFailure = classifySecureMessagingRecordKeyAccessFailure(
            cause = providerFailure,
            userAuthenticationRequired = false,
            aliasPresent = true,
            observeMissingAlias = neverMissing,
            observeUnrecoverableAlias = observeUnrecoverable,
        )
        val unknownAlias = classifySecureMessagingRecordKeyAccessFailure(
            cause = unrecoverable,
            userAuthenticationRequired = false,
            aliasPresent = null,
            observeMissingAlias = neverMissing,
            observeUnrecoverableAlias = observeUnrecoverable,
        )

        assertTrue(provenUnrecoverable is SecureMessagingRecordKeyPermanentlyUnrecoverableException)
        assertTrue(wrappedInvalidKey is SecureMessagingRecordKeyPermanentlyUnrecoverableException)
        assertTrue(isPermanentlyMissingSecureMessagingRecordKey(provenUnrecoverable))
        assertFalse(isRetryableSecureMessagingStateFailure(provenUnrecoverable))
        assertTrue(locked is SecureMessagingRecordKeyTemporarilyUnavailableException)
        assertTrue(genericProviderFailure is SecureMessagingRecordKeyTemporarilyUnavailableException)
        assertTrue(unknownAlias is SecureMessagingRecordKeyTemporarilyUnavailableException)
        assertEquals(2, unrecoverableObservations)
    }

    @Test
    fun `authenticated record corruption is not a retryable keystore failure`() {
        val corruption = IllegalArgumentException("bad authenticated tag")
        val failure = secureMessagingStateAccessFailure(
            message = "authenticated decryption failed",
            cause = corruption,
        )

        assertFalse(isRetryableSecureMessagingStateFailure(corruption))
        assertTrue(failure is SecureMessagingStateUnavailableException)
        assertFalse(failure is SecureMessagingStateRetryableException)
    }

    @Test
    fun `only migration-fenced unreadable legacy state authorizes recovery`() {
        val authenticationFailure = SecureMessagingRecordAuthenticationFailedException(
            AEADBadTagException("Android 9 used a replacement key"),
        )
        val unavailable = SecureMessagingStateUnavailableException(
            "authenticated decryption failed",
            authenticationFailure,
        )

        assertTrue(isSecureMessagingRecordAuthenticationFailure(unavailable))
        assertFalse(isPermanentlyMissingSecureMessagingRecordKey(unavailable))
        assertFalse(isRecoverableSecureMessagingStateLoss(unavailable))

        val legacyUnreadable = SecureMessagingLegacyStateUnreadableException(unavailable)
        assertTrue(isRecoverableSecureMessagingStateLoss(legacyUnreadable))
        assertFalse(isRetryableSecureMessagingStateFailure(legacyUnreadable))
    }

    @Test
    fun `state write consumes and wipes its retained plaintext exactly once`() {
        val callerBytes = byteArrayOf(11, 22, 33, 44)
        val write = SecureMessagingStateWrite(
            namespace = "session:one",
            recordKey = "record:one",
            expectedVersion = null,
            bytes = callerBytes,
        )
        callerBytes.fill(0)

        assertEquals(listOf<Byte>(11, 22, 33, 44), write.copyBytes().toList())
        val consumed = write.consumeBytes()
        assertEquals(listOf<Byte>(11, 22, 33, 44), consumed.toList())
        assertTrue(runCatching { write.copyBytes() }.exceptionOrNull() is IllegalStateException)
        assertTrue(runCatching { write.consumeBytes() }.exceptionOrNull() is IllegalStateException)
        consumed.fill(0)
    }

    @Test
    fun `namespace enumeration is denied outside an active lifecycle gate`() = runTest {
        val gate = SecureMessagingLifecycleGate()
        var encryptedRowsQueried = false

        val denied = runCatching {
            gate.withOperation { encryptedRowsQueried = true }
        }.exceptionOrNull()

        assertTrue(denied is IllegalStateException)
        assertFalse(encryptedRowsQueried)
        gate.open()
        gate.withOperation { encryptedRowsQueried = true }
        assertTrue(encryptedRowsQueried)
    }

    @Test
    fun `room record exposes only encrypted bytes and authenticated routing metadata`() {
        val fields = SecureMessagingRecordEntity::class.java.declaredFields.map { it.name }.toSet()
        assertTrue(fields.containsAll(setOf("namespace", "recordKey", "version", "iv", "ciphertext")))
        assertFalse(fields.any { it in setOf("plaintext", "text", "body", "privateKey", "sessionState") })
    }

    @Test
    fun `aad binds ciphertext to namespace key and monotonic version`() {
        val original = messagingRecordAad("user:one", "session:2", 7)
        assertNotEquals(original.toList(), messagingRecordAad("user:two", "session:2", 7).toList())
        assertNotEquals(original.toList(), messagingRecordAad("user:one", "session:3", 7).toList())
        assertNotEquals(original.toList(), messagingRecordAad("user:one", "session:2", 8).toList())
    }

    @Test
    fun `session lifecycle erases state on logout and before a new local session`() = runTest {
        var erasures = 0
        var activations = 0
        var notificationCancellations = 0
        val lifecycle = SecureMessagingSessionLifecycle(
            object : SecureMessagingStateEraser {
                override suspend fun eraseAll() {
                    erasures++
                }

                override suspend fun allowForActiveSession() {
                    activations++
                }
            },
            SecureMessagingLifecycleGuard(),
            object : SecureMessagingIncomingNotificationSink {
                override fun publish(notification: SecureMessagingIncomingNotification) = Unit

                override fun cancelAll() {
                    notificationCancellations++
                }
            },
        )

        lifecycle.beforeSessionSave(isSameSession = false)
        lifecycle.beforeSessionSave(isSameSession = true)
        lifecycle.afterSessionSave()
        lifecycle.beforeSessionClear()

        assertEquals(2, erasures)
        assertEquals(1, activations)
        assertEquals(2, notificationCancellations)
    }

    @Test
    fun `session replacement snapshots before its crash fence and messaging erasure`() = runTest {
        val events = mutableListOf<String>()
        val lifecycle = SecureMessagingSessionLifecycle(
            eraser = object : SecureMessagingStateEraser {
                override suspend fun eraseAll() {
                    events += "erase"
                }
            },
            lifecycle = SecureMessagingLifecycleGuard(),
        )
        lifecycle.afterSessionSave()

        lifecycle.beforeSessionSave(
            isSameSession = false,
            finalSnapshot = { events += "snapshot" },
            beforeErasure = { events += "crash-fence" },
        )

        assertEquals(listOf("snapshot", "crash-fence", "erase"), events)
        assertFalse(lifecycle.stateAvailable.value)
    }

    @Test
    fun `failed session replacement snapshot retains active messaging state`() = runTest {
        val failure = IllegalStateException("archive unavailable")
        var crashFenced = false
        var erased = false
        val lifecycle = SecureMessagingSessionLifecycle(
            eraser = object : SecureMessagingStateEraser {
                override suspend fun eraseAll() {
                    erased = true
                }
            },
            lifecycle = SecureMessagingLifecycleGuard(),
        )
        lifecycle.afterSessionSave()

        val observed = runCatching {
            lifecycle.beforeSessionSave(
                isSameSession = false,
                finalSnapshot = { throw failure },
                beforeErasure = { crashFenced = true },
            )
        }.exceptionOrNull()

        assertSame(failure, observed)
        assertFalse(crashFenced)
        assertFalse(erased)
        assertTrue(lifecycle.stateAvailable.value)
    }

    @Test
    fun `local session is removed even when messaging erasure fails`() = runTest {
        val erasureFailure = IllegalStateException("unavailable keystore")
        var sessionCleared = false
        var erasureSucceededAtClear: Boolean? = null

        val observed = runCatching {
            eraseMessagingThenClearSession(
                eraseMessaging = { throw erasureFailure },
                clearSession = { succeeded ->
                    sessionCleared = true
                    erasureSucceededAtClear = succeeded
                },
            )
        }.exceptionOrNull()

        assertTrue(sessionCleared)
        assertEquals(false, erasureSucceededAtClear)
        assertSame(erasureFailure, observed)
    }

    @Test
    fun `session cleanup completes after messaging erasure attempt`() = runTest {
        val events = mutableListOf<String>()

        eraseMessagingThenClearSession(
            eraseMessaging = { events += "erase" },
            clearSession = { succeeded -> events += "clear:$succeeded" },
        )

        assertEquals(listOf("erase", "clear:true"), events)
    }

    @Test
    fun `erase serializes with writes and rejects a queued post logout write`() = runTest {
        val gate = SecureMessagingLifecycleGate()
        val writeEntered = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        var recoverableStateExists = false

        val initiallyRejected = runCatching {
            gate.withOperation { recoverableStateExists = true }
        }.exceptionOrNull()
        assertTrue(initiallyRejected is IllegalStateException)
        gate.open()

        val inFlightWrite = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.withOperation {
                writeEntered.complete(Unit)
                releaseWrite.await()
                recoverableStateExists = true
            }
        }
        writeEntered.await()
        val erasure = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.erase { recoverableStateExists = false }
        }
        val lateWrite = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching {
                gate.withOperation { recoverableStateExists = true }
            }.exceptionOrNull()
        }

        releaseWrite.complete(Unit)
        inFlightWrite.join()
        erasure.join()

        assertFalse(recoverableStateExists)
        assertTrue(lateWrite.await() is IllegalStateException)

        gate.open()
        gate.withOperation { recoverableStateExists = true }
        assertTrue(recoverableStateExists)
    }

    @Test
    fun `final logout snapshot and erasure hold one exclusive state lease`() = runTest {
        val gate = SecureMessagingLifecycleGate()
        val snapshotEntered = CompletableDeferred<Unit>()
        val releaseSnapshot = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        gate.open()

        val erasure = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.eraseAfterFinalSnapshot(
                finalSnapshot = {
                    // The real snapshot reads through this same guarded store.
                    gate.withOperation { events += "snapshot" }
                    snapshotEntered.complete(Unit)
                    releaseSnapshot.await()
                },
                erasure = { events += "erase" },
            )
        }
        snapshotEntered.await()
        val lateCommit = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { gate.withOperation { events += "late-commit" } }.exceptionOrNull()
        }

        releaseSnapshot.complete(Unit)
        erasure.join()

        assertEquals(listOf("snapshot", "erase"), events)
        assertTrue(lateCommit.await() is IllegalStateException)
    }

    @Test
    fun `failed final logout snapshot leaves active state open for retry`() = runTest {
        val gate = SecureMessagingLifecycleGate()
        val failure = IllegalStateException("archive unavailable")
        gate.open()

        val observed = runCatching {
            gate.eraseAfterFinalSnapshot(
                finalSnapshot = { throw failure },
                erasure = { error("state must not be erased") },
            )
        }.exceptionOrNull()

        assertSame(failure, observed)
        var retried = false
        gate.withOperation { retried = true }
        assertTrue(retried)
    }

    @Test
    fun `readable recovery snapshots before crash fence and erasure`() = runTest {
        val events = mutableListOf<String>()
        val guard = SecureMessagingLifecycleGuard()
        val lifecycle = SecureMessagingSessionLifecycle(
            eraser = object : SecureMessagingStateEraser {
                override suspend fun eraseAll() {
                    events += "erase"
                }
            },
            lifecycle = guard,
        )
        lifecycle.afterSessionSave()
        val fence = openRecoveryActivation(guard)

        lifecycle.resetForRecovery(
            fence = fence,
            finalSnapshot = { events += "snapshot" },
            beforeErasure = { events += "crash-fence" },
        )

        assertEquals(listOf("snapshot", "crash-fence", "erase"), events)
        assertEquals(SecureMessagingRuntimeStage.NO_SESSION, guard.snapshot().stage)
        assertFalse(lifecycle.stateAvailable.value)
    }

    @Test
    fun `failed readable recovery snapshot retains activation and open state`() = runTest {
        val failure = IllegalStateException("archive unavailable")
        var crashFenced = false
        var erased = false
        val guard = SecureMessagingLifecycleGuard()
        val lifecycle = SecureMessagingSessionLifecycle(
            eraser = object : SecureMessagingStateEraser {
                override suspend fun eraseAll() {
                    erased = true
                }
            },
            lifecycle = guard,
        )
        lifecycle.afterSessionSave()
        val fence = openRecoveryActivation(guard)

        val observed = runCatching {
            lifecycle.resetForRecovery(
                fence = fence,
                finalSnapshot = { throw failure },
                beforeErasure = { crashFenced = true },
            )
        }.exceptionOrNull()

        assertSame(failure, observed)
        assertFalse(crashFenced)
        assertFalse(erased)
        assertTrue(lifecycle.stateAvailable.value)
        assertEquals(SecureMessagingRuntimeStage.PREPARING_KEYS, guard.snapshot().stage)
        guard.assertCurrent(fence)
    }

    @Test
    fun `proved permanent record key bypasses only its snapshot failure`() = runTest {
        val events = mutableListOf<String>()
        val guard = SecureMessagingLifecycleGuard()
        val lifecycle = SecureMessagingSessionLifecycle(
            eraser = object : SecureMessagingStateEraser {
                override suspend fun eraseAll() {
                    events += "erase"
                }
            },
            lifecycle = guard,
        )
        lifecycle.afterSessionSave()
        val fence = openRecoveryActivation(guard)

        lifecycle.resetForRecovery(
            fence = fence,
            allowPermanentlyUnavailableSnapshot = true,
            finalSnapshot = {
                events += "snapshot"
                throw SecureMessagingRecordKeyPermanentlyMissingException()
            },
            beforeErasure = { events += "crash-fence" },
        )

        assertEquals(listOf("snapshot", "crash-fence", "erase"), events)
        assertEquals(SecureMessagingRuntimeStage.NO_SESSION, guard.snapshot().stage)
    }

    @Test
    fun `migration-fenced unreadable state bypasses its final snapshot`() = runTest {
        val events = mutableListOf<String>()
        val guard = SecureMessagingLifecycleGuard()
        val lifecycle = SecureMessagingSessionLifecycle(
            eraser = object : SecureMessagingStateEraser {
                override suspend fun eraseAll() {
                    events += "erase"
                }
            },
            lifecycle = guard,
        )
        lifecycle.afterSessionSave()
        val fence = openRecoveryActivation(guard)
        val unreadable = SecureMessagingLegacyStateUnreadableException(
            SecureMessagingRecordAuthenticationFailedException(
                AEADBadTagException("legacy projection used the replaced key"),
            ),
        )

        lifecycle.resetForRecovery(
            fence = fence,
            allowPermanentlyUnavailableSnapshot = true,
            finalSnapshot = {
                events += "snapshot"
                throw unreadable
            },
            beforeErasure = { events += "crash-fence" },
        )

        assertEquals(listOf("snapshot", "crash-fence", "erase"), events)
        assertEquals(SecureMessagingRuntimeStage.NO_SESSION, guard.snapshot().stage)
    }

    @Test
    fun `stale recovery activation is rejected before snapshot or crash fence`() = runTest {
        val events = mutableListOf<String>()
        val guard = SecureMessagingLifecycleGuard()
        val lifecycle = SecureMessagingSessionLifecycle(
            eraser = object : SecureMessagingStateEraser {
                override suspend fun eraseAll() {
                    events += "erase"
                }
            },
            lifecycle = guard,
        )
        lifecycle.afterSessionSave()
        val stale = openRecoveryActivation(guard)
        guard.beginRecoveryErasure(stale)
        guard.finishErasure()
        val replacement = openRecoveryActivation(guard)

        val observed = runCatching {
            lifecycle.resetForRecovery(
                fence = stale,
                finalSnapshot = { events += "snapshot" },
                beforeErasure = { events += "crash-fence" },
            )
        }.exceptionOrNull()

        assertTrue(observed is IllegalStateException)
        assertTrue(events.isEmpty())
        assertEquals(SecureMessagingRuntimeStage.PREPARING_KEYS, guard.snapshot().stage)
        guard.assertCurrent(replacement)
    }

    @Test
    fun `record cleanup is attempted when keystore erasure fails`() = runTest {
        val keyFailure = IllegalStateException("Keystore unavailable")
        var recordsDeleted = false

        val observed = runCatching {
            eraseMessagingKeyAndRecords(
                eraseKey = { throw keyFailure },
                eraseRecords = { recordsDeleted = true },
            )
        }.exceptionOrNull()

        assertTrue(recordsDeleted)
        assertSame(keyFailure, observed)
    }

    @Test
    fun `deferred cipher operation classifies failure and clears health only after success`() {
        val providerFailure = ProviderException("deferred Android 9 key failure")
        val classified = SecureMessagingRecordKeyPermanentlyUnrecoverableException(providerFailure)
        var classifications = 0
        var successfulHealthClears = 0

        val observed = runCatching {
            completeKeystoreCipherOperation(
                operation = { throw providerFailure },
                classifyFailure = {
                    classifications++
                    classified
                },
                onSuccess = { successfulHealthClears++ },
            )
        }.exceptionOrNull()

        assertSame(classified, observed)
        assertEquals(1, classifications)
        assertEquals(0, successfulHealthClears)

        val result = completeKeystoreCipherOperation(
            operation = { "ciphertext" },
            classifyFailure = { error("successful operation cannot be classified") },
            onSuccess = { successfulHealthClears++ },
        )
        assertEquals("ciphertext", result)
        assertEquals(1, successfulHealthClears)
    }

    private fun openRecoveryActivation(
        guard: SecureMessagingLifecycleGuard,
    ): SecureMessagingSessionFence {
        val fence = guard.beginSession(
            SecureMessagingSessionBinding(
                sessionEpoch = "recovery-session",
                userId = "11111111-1111-4111-8111-111111111111",
                serverDeviceId = "22222222-2222-4222-8222-222222222222",
                installationId = "33333333-3333-4333-8333-333333333333",
            ),
        )
        guard.beginCapabilityCheck(fence)
        guard.beginKeyPreparation(fence)
        return fence
    }
}
