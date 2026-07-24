package com.kit.wallet

import android.security.keystore.KeyPermanentlyInvalidatedException
import com.kit.wallet.data.local.SecureMessagingRecordEntity
import com.kit.wallet.data.messaging.EncryptedMessagingRecord
import com.kit.wallet.data.messaging.MAX_ATOMIC_BATCH_PLAINTEXT_BYTES
import com.kit.wallet.data.messaging.MAX_ATOMIC_WRITES
import com.kit.wallet.data.messaging.MAX_SECURE_MESSAGING_RECORD_PLAINTEXT_BYTES
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
import com.kit.wallet.data.messaging.SecureMessagingRecord
import com.kit.wallet.data.messaging.SecureMessagingRecordAtRestProvenance
import com.kit.wallet.data.messaging.SecureMessagingRecordStorageFormat
import com.kit.wallet.data.messaging.SecureMessagingBootstrapRecordKeyValidationException
import com.kit.wallet.data.messaging.SecureMessagingAuthenticatedAndroid9LegacyTruncationProof
import com.kit.wallet.data.messaging.SecureMessagingCryptographicFailureException
import com.kit.wallet.data.messaging.SecureMessagingLegacyStateUnreadableException
import com.kit.wallet.data.messaging.SecureMessagingQuarantineReason
import com.kit.wallet.data.messaging.SecureMessagingRecordAuthenticationFailedException
import com.kit.wallet.data.messaging.SecureMessagingInitialEnrollmentAuthenticationFailedException
import com.kit.wallet.data.messaging.SecureMessagingLegacyInitialEnrollmentUnreadableException
import com.kit.wallet.data.messaging.SecureMessagingStateEraser
import com.kit.wallet.data.messaging.SecureMessagingStateRetryableException
import com.kit.wallet.data.messaging.SecureMessagingStateUnavailableException
import com.kit.wallet.data.messaging.SecureMessagingStateWrite
import com.kit.wallet.data.messaging.classifySecureMessagingRecordKeyAccessFailure
import com.kit.wallet.data.messaging.classifySecureMessagingRecordKeyOperationFailure
import com.kit.wallet.data.messaging.classifySecureMessagingStoredRecordFailure
import com.kit.wallet.data.messaging.classifySecureMessagingDecodedRecordFailure
import com.kit.wallet.data.messaging.completeKeystoreCipherOperation
import com.kit.wallet.data.messaging.deleteKeystoreAliasAndVerifyAbsent
import com.kit.wallet.data.messaging.decryptSecureMessagingRecordEnvelope
import com.kit.wallet.data.messaging.decryptSecureMessagingLegacyRecord
import com.kit.wallet.data.messaging.encryptSecureMessagingRecordEnvelope
import com.kit.wallet.data.messaging.eraseMessagingKeyAndRecords
import com.kit.wallet.data.messaging.isPermanentlyMissingSecureMessagingRecordKey
import com.kit.wallet.data.messaging.isRecoverableSecureMessagingStateLoss
import com.kit.wallet.data.messaging.isRetryableSecureMessagingStateFailure
import com.kit.wallet.data.messaging.isSecureMessagingRecordAuthenticationFailure
import com.kit.wallet.data.messaging.isTransientSecureMessagingRecordKeyFailure
import com.kit.wallet.data.messaging.isLegacyConfirmedSecureMessagingEnrollmentUnreadableFailure
import com.kit.wallet.data.messaging.maximumSecureMessagingRecordPlaintextBytes
import com.kit.wallet.data.messaging.observeSecureMessagingRecordKeyMiss
import com.kit.wallet.data.messaging.resolveSecureMessagingRecordKey
import com.kit.wallet.data.messaging.resolveSecureMessagingRecordKeyWithCreationStatus
import com.kit.wallet.data.messaging.secureMessagingStateAccessFailure
import com.kit.wallet.data.messaging.secureMessagingKeyCreationAllowed
import com.kit.wallet.data.messaging.secureMessagingRecordStorageFormat
import com.kit.wallet.data.messaging.secureMessagingRecordAtRestProvenance
import com.kit.wallet.data.messaging.validateBootstrapSecureMessagingRecordKeyRoundTrip
import com.kit.wallet.data.messaging.validateSecureMessagingAtomicWriteBounds
import com.kit.wallet.data.messaging.withSecureMessagingBootstrapKeyCleanup
import com.kit.wallet.data.session.eraseMessagingThenClearSession
import java.io.EOFException
import java.security.AlgorithmParameters
import java.security.InvalidKeyException
import java.security.Key
import java.security.Provider
import java.security.ProviderException
import java.security.SecureRandom
import java.security.Security
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.CipherSpi
import javax.crypto.IllegalBlockSizeException
import javax.crypto.ShortBufferException
import java.security.spec.AlgorithmParameterSpec
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
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
    fun `fresh key is retained only after a separately reloaded handle authenticates ciphertext`() {
        val generatedHandle = Any()
        val reloadedHandle = Any()
        val expected = ByteArray(32) { it.toByte() }
        val decrypted = expected.copyOf()
        val events = mutableListOf<String>()

        val proven = withSecureMessagingBootstrapKeyCleanup(
            cleanupRequired = true,
            eraseUncommittedBootstrapKey = { events += "erase" },
        ) {
            validateBootstrapSecureMessagingRecordKeyRoundTrip(
                resolution = SecureMessagingRecordKeyResolution(
                    key = generatedHandle,
                    createdNow = true,
                ),
                bootstrapProbeRequired = true,
                expectedDek = expected,
                reloadBootstrapKey = {
                    events += "reload"
                    reloadedHandle
                },
                authenticatedUnwrap = { key ->
                    assertSame(reloadedHandle, key)
                    events += "authenticate"
                    decrypted
                },
            )
        }

        assertSame(reloadedHandle, proven)
        assertEquals(listOf("reload", "authenticate"), events)
        assertTrue(decrypted.all { it == 0.toByte() })
        assertTrue(expected.indices.all { expected[it] == it.toByte() })
    }

    @Test
    fun `retained-state key skips the empty-store bootstrap probe`() {
        val existingHandle = Any()

        val proven = validateBootstrapSecureMessagingRecordKeyRoundTrip(
            resolution = SecureMessagingRecordKeyResolution(
                key = existingHandle,
                createdNow = false,
            ),
            bootstrapProbeRequired = false,
            expectedDek = ByteArray(32) { 1 },
            reloadBootstrapKey = { error("Retained-state key unexpectedly reloaded") },
            authenticatedUnwrap = { error("Existing key unexpectedly probed") },
        )

        assertSame(existingHandle, proven)
    }

    @Test
    fun `retained-state encryption failure never erases its alias`() {
        val providerFailure = ProviderException("Android 9 temporarily rejected the key")
        var eraseCalls = 0

        val observed = runCatching {
            withSecureMessagingBootstrapKeyCleanup(
                cleanupRequired = false,
                eraseUncommittedBootstrapKey = { eraseCalls++ },
            ) {
                throw providerFailure
            }
        }.exceptionOrNull()

        assertSame(providerFailure, observed)
        assertEquals(0, eraseCalls)
    }

    @Test
    fun `empty store reprobes an existing alias left after failed bootstrap cleanup`() {
        val leftoverAlias = Any()
        val firstReloadedHandle = Any()
        val secondReloadedHandle = Any()
        val expected = ByteArray(32) { (it + 4).toByte() }
        val cleanupFailure = IllegalStateException("Android 9 retained the alias after delete")
        val events = mutableListOf<String>()

        val firstFailure = runCatching {
            withSecureMessagingBootstrapKeyCleanup(
                cleanupRequired = true,
                eraseUncommittedBootstrapKey = {
                    events += "erase-1"
                    throw cleanupFailure
                },
            ) {
                validateBootstrapSecureMessagingRecordKeyRoundTrip(
                    resolution = SecureMessagingRecordKeyResolution(
                        key = leftoverAlias,
                        createdNow = false,
                    ),
                    bootstrapProbeRequired = true,
                    expectedDek = expected,
                    reloadBootstrapKey = {
                        events += "reload-1"
                        firstReloadedHandle
                    },
                    authenticatedUnwrap = {
                        events += "authenticate-1"
                        throw AEADBadTagException("leftover alias still cannot authenticate")
                    },
                )
                events += "commit-1"
            }
        }.exceptionOrNull()

        assertTrue(firstFailure is SecureMessagingBootstrapRecordKeyValidationException)
        assertEquals(listOf(cleanupFailure), firstFailure?.suppressed?.toList())
        assertEquals(listOf("reload-1", "authenticate-1", "erase-1"), events)

        val secondValidationPlaintext = expected.copyOf()
        val proven = validateBootstrapSecureMessagingRecordKeyRoundTrip(
            resolution = SecureMessagingRecordKeyResolution(
                key = leftoverAlias,
                createdNow = false,
            ),
            bootstrapProbeRequired = true,
            expectedDek = expected,
            reloadBootstrapKey = {
                events += "reload-2"
                secondReloadedHandle
            },
            authenticatedUnwrap = {
                events += "authenticate-2"
                secondValidationPlaintext
            },
        )

        assertSame(secondReloadedHandle, proven)
        assertEquals(
            listOf(
                "reload-1",
                "authenticate-1",
                "erase-1",
                "reload-2",
                "authenticate-2",
            ),
            events,
        )
        assertTrue(secondValidationPlaintext.all { it == 0.toByte() })
    }

    @Test
    fun `fresh key authentication failure erases alias and prevents commit`() {
        val authenticationFailure = AEADBadTagException(
            "Reloaded Android 9 alias cannot authenticate bootstrap ciphertext",
        )
        val events = mutableListOf<String>()

        val observed = runCatching {
            withSecureMessagingBootstrapKeyCleanup(
                cleanupRequired = true,
                eraseUncommittedBootstrapKey = { events += "erase" },
            ) {
                validateBootstrapSecureMessagingRecordKeyRoundTrip(
                    resolution = SecureMessagingRecordKeyResolution(Any(), createdNow = true),
                    bootstrapProbeRequired = true,
                    expectedDek = ByteArray(32) { (it + 1).toByte() },
                    reloadBootstrapKey = {
                        events += "reload"
                        Any()
                    },
                    authenticatedUnwrap = {
                        events += "authenticate"
                        throw authenticationFailure
                    },
                )
                events += "clear-retry-evidence"
                events += "commit"
            }
        }.exceptionOrNull()

        assertTrue(observed is SecureMessagingBootstrapRecordKeyValidationException)
        assertSame(authenticationFailure, observed?.cause)
        assertEquals(listOf("reload", "authenticate", "erase"), events)
        assertTrue(isRetryableSecureMessagingStateFailure(checkNotNull(observed)))
        assertTrue(isTransientSecureMessagingRecordKeyFailure(observed))
    }

    @Test
    fun `fresh key roundtrip mismatch wipes plaintext and erases uncommitted alias`() {
        val validationPlaintext = ByteArray(32) { 9 }
        var eraseCalls = 0

        val observed = runCatching {
            withSecureMessagingBootstrapKeyCleanup(
                cleanupRequired = true,
                eraseUncommittedBootstrapKey = { eraseCalls++ },
            ) {
                validateBootstrapSecureMessagingRecordKeyRoundTrip(
                    resolution = SecureMessagingRecordKeyResolution(Any(), createdNow = true),
                    bootstrapProbeRequired = true,
                    expectedDek = ByteArray(32) { (it + 1).toByte() },
                    reloadBootstrapKey = { Any() },
                    authenticatedUnwrap = { validationPlaintext },
                )
            }
        }.exceptionOrNull()

        assertTrue(observed is SecureMessagingBootstrapRecordKeyValidationException)
        assertEquals(1, eraseCalls)
        assertTrue(validationPlaintext.all { it == 0.toByte() })
    }

    @Test
    fun `hybrid envelope round trips Android 9 truncation sizes without large keystore input`() {
        val aad = messagingRecordAad("libsignal-v2", "active-protocol-state", 1L)

        listOf(65_536, 77_248, 131_072, 667_068).forEach { size ->
            val plaintext = ByteArray(size) { index -> (index * 31).toByte() }
            val generatedDek = ByteArray(32) { index -> (index + size).toByte() }
            val generatedDataIv = ByteArray(12) { index -> (index + 7).toByte() }
            val keystorePlaintextSizes = mutableListOf<Int>()
            val keystoreCiphertextSizes = mutableListOf<Int>()

            val encrypted = encryptSecureMessagingRecordEnvelope(
                aad = aad,
                plaintext = plaintext,
                createDek = { generatedDek },
                createDataIv = { generatedDataIv },
                wrapDek = { wrapAad, dek ->
                    keystorePlaintextSizes += dek.size
                    testWrapDek(wrapAad, dek).also {
                        keystoreCiphertextSizes += it.ciphertext.size
                    }
                },
                validateWrappedDek = { wrapAad, wrapped, expectedDek ->
                    val recovered = testUnwrapDek(wrapAad, wrapped)
                    try {
                        assertArrayEquals(expectedDek, recovered)
                    } finally {
                        recovered.fill(0)
                    }
                },
            )

            assertEquals(SecureMessagingRecordStorageFormat.DEK_ENVELOPE_V1,
                secureMessagingRecordStorageFormat(encrypted))
            assertEquals(
                SecureMessagingRecordAtRestProvenance.DEK_ENVELOPE_V1,
                secureMessagingRecordAtRestProvenance(encrypted.iv),
            )
            assertEquals(32, encrypted.iv.size)
            assertEquals(size + 64, encrypted.ciphertext.size)
            assertEquals(listOf(32), keystorePlaintextSizes)
            assertEquals(listOf(48), keystoreCiphertextSizes)
            assertTrue(generatedDek.all { it == 0.toByte() })
            assertTrue(generatedDataIv.all { it == 0.toByte() })

            var recoveredDek: ByteArray? = null
            val decrypted = decryptSecureMessagingRecordEnvelope(
                aad = aad,
                record = encrypted,
                unwrapDek = { wrapAad, wrapped ->
                    testUnwrapDek(wrapAad, wrapped).also { recoveredDek = it }
                },
            )
            try {
                assertArrayEquals(plaintext, decrypted)
                assertEquals(32, recoveredDek?.size)
                assertTrue(checkNotNull(recoveredDek).all { it == 0.toByte() })
            } finally {
                plaintext.fill(0)
                decrypted.fill(0)
                encrypted.iv.fill(0)
                encrypted.ciphertext.fill(0)
            }
        }
        aad.fill(0)
    }

    @Test
    fun `software GCM selects a usable provider only after seeing its in-memory key`() {
        val provider = RejectingSecretKeySpecAesGcmProvider(
            "RejectSecretKeySpec-${System.nanoTime()}",
        )
        assertEquals(1, Security.insertProviderAt(provider, 1))
        val aad = messagingRecordAad("libsignal-v2", "active-protocol-state", 1L)
        val plaintext = byteArrayOf(1, 2, 3)
        try {
            val encrypted = encryptSecureMessagingRecordEnvelope(
                aad = aad,
                plaintext = plaintext,
                createDek = { ByteArray(32) { 7 } },
                createDataIv = { ByteArray(12) { 8 } },
                wrapDek = { _, _ ->
                    EncryptedMessagingRecord(
                        iv = ByteArray(12),
                        ciphertext = ByteArray(48),
                    )
                },
            )
            try {
                assertEquals(
                    SecureMessagingRecordStorageFormat.DEK_ENVELOPE_V1,
                    secureMessagingRecordStorageFormat(encrypted),
                )
            } finally {
                encrypted.iv.fill(0)
                encrypted.ciphertext.fill(0)
            }
        } finally {
            Security.removeProvider(provider.name)
            plaintext.fill(0)
            aad.fill(0)
        }
    }

    @Test
    fun `legacy twelve byte IV record remains directly decryptable`() {
        val aad = messagingRecordAad("libsignal-v2", "active-protocol-state", 2L)
        val plaintext = "legacy-direct-record".toByteArray()
        val legacy = testEncryptLegacy(aad, plaintext)

        assertEquals(
            SecureMessagingRecordStorageFormat.LEGACY_DIRECT_V1,
            secureMessagingRecordStorageFormat(legacy),
        )
        assertEquals(
            SecureMessagingRecordAtRestProvenance.LEGACY_DIRECT_V1,
            secureMessagingRecordAtRestProvenance(legacy.iv),
        )
        assertEquals(
            SecureMessagingRecordAtRestProvenance.UNSPECIFIED,
            secureMessagingRecordAtRestProvenance(ByteArray(13)),
        )
        val decrypted = decryptSecureMessagingLegacyRecord(
            aad = aad,
            record = legacy,
            decryptDirect = ::testDecryptLegacy,
        )

        assertArrayEquals(plaintext, decrypted)
        plaintext.fill(0)
        decrypted.fill(0)
        legacy.iv.fill(0)
        legacy.ciphertext.fill(0)
        aad.fill(0)
    }

    @Test
    fun `legacy direct reads retain the code twenty two ceiling without expanding new writes`() {
        assertEquals(
            65 * 1024 * 1024,
            maximumSecureMessagingRecordPlaintextBytes(
                SecureMessagingRecordStorageFormat.LEGACY_DIRECT_V1,
            ),
        )
        assertEquals(
            MAX_SECURE_MESSAGING_RECORD_PLAINTEXT_BYTES,
            maximumSecureMessagingRecordPlaintextBytes(
                SecureMessagingRecordStorageFormat.DEK_ENVELOPE_V1,
            ),
        )
        assertTrue(
            maximumSecureMessagingRecordPlaintextBytes(
                SecureMessagingRecordStorageFormat.LEGACY_DIRECT_V1,
            ) > MAX_SECURE_MESSAGING_RECORD_PLAINTEXT_BYTES,
        )
    }

    @Test
    fun `hybrid envelope rejects unknown formats lengths and authenticated corruption`() {
        val aad = messagingRecordAad("libsignal-v2", "active-protocol-state", 1L)
        val plaintext = ByteArray(1_024) { it.toByte() }
        val encrypted = testEncryptEnvelope(aad, plaintext)

        listOf(0, 11, 13, 31, 33).forEach { ivSize ->
            val malformed = EncryptedMessagingRecord(
                iv = ByteArray(ivSize),
                ciphertext = encrypted.ciphertext.copyOf(),
            )
            assertTrue(
                runCatching { secureMessagingRecordStorageFormat(malformed) }
                    .exceptionOrNull() is IllegalArgumentException,
            )
        }

        val badMarker = encrypted.copy(iv = encrypted.iv.copyOf().also { it[0] = 0 })
        val shortEnvelope = encrypted.copy(ciphertext = encrypted.ciphertext.copyOf(64))
        assertTrue(runCatching { secureMessagingRecordStorageFormat(badMarker) }.isFailure)
        assertTrue(runCatching { secureMessagingRecordStorageFormat(shortEnvelope) }.isFailure)

        val corruptions = listOf(
            aad.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() } to encrypted,
            aad to encrypted.copy(
                ciphertext = encrypted.ciphertext.copyOf().also { it[0] = (it[0] + 1).toByte() },
            ),
            aad to encrypted.copy(
                ciphertext = encrypted.ciphertext.copyOf().also {
                    it[48] = (it[48] + 1).toByte()
                },
            ),
            aad to encrypted.copy(
                iv = encrypted.iv.copyOf().also { it[20] = (it[20] + 1).toByte() },
            ),
        )
        corruptions.forEach { (corruptAad, corruptRecord) ->
            assertTrue(
                runCatching {
                    decryptSecureMessagingRecordEnvelope(
                        aad = corruptAad,
                        record = corruptRecord,
                        unwrapDek = ::testUnwrapDek,
                    )
                }.isFailure,
            )
            if (corruptAad !== aad) corruptAad.fill(0)
            if (corruptRecord !== encrypted) {
                corruptRecord.iv.fill(0)
                corruptRecord.ciphertext.fill(0)
            }
        }

        plaintext.fill(0)
        encrypted.iv.fill(0)
        encrypted.ciphertext.fill(0)
        aad.fill(0)
    }

    @Test
    fun `hybrid envelope wipes generated and recovered DEKs on failure`() {
        val aad = messagingRecordAad("libsignal-v2", "active-protocol-state", 1L)
        val generatedDek = ByteArray(32) { 7 }
        val generatedIv = ByteArray(12) { 8 }
        val wrapFailure = IllegalStateException("keystore wrap failed")

        val observed = runCatching {
            encryptSecureMessagingRecordEnvelope(
                aad = aad,
                plaintext = byteArrayOf(1, 2, 3),
                createDek = { generatedDek },
                createDataIv = { generatedIv },
                wrapDek = { _, _ -> throw wrapFailure },
            )
        }.exceptionOrNull()

        assertSame(wrapFailure, observed)
        assertTrue(generatedDek.all { it == 0.toByte() })
        assertTrue(generatedIv.all { it == 0.toByte() })

        val valid = testEncryptEnvelope(aad, byteArrayOf(4, 5, 6))
        val wrongLengthDek = ByteArray(31) { 9 }
        assertTrue(
            runCatching {
                decryptSecureMessagingRecordEnvelope(aad, valid) { _, _ -> wrongLengthDek }
            }.isFailure,
        )
        assertTrue(wrongLengthDek.all { it == 0.toByte() })
        valid.iv.fill(0)
        valid.ciphertext.fill(0)
        aad.fill(0)
    }

    @Test
    fun `hybrid envelope wipes its DEK when data IV creation fails`() {
        val aad = messagingRecordAad("libsignal-v2", "active-protocol-state", 1L)
        val generatedDek = ByteArray(32) { 7 }
        val dataIvFailure = ProviderException("secure random could not produce an IV")

        val observed = runCatching {
            encryptSecureMessagingRecordEnvelope(
                aad = aad,
                plaintext = byteArrayOf(1),
                createDek = { generatedDek },
                createDataIv = { throw dataIvFailure },
                wrapDek = { _, _ -> error("Wrapping must not start without an IV") },
            )
        }.exceptionOrNull()

        assertSame(dataIvFailure, observed)
        assertTrue(generatedDek.all { it == 0.toByte() })
        aad.fill(0)
    }

    @Test
    fun `hybrid envelope wipes its DEK and invalid data IV before rejecting them`() {
        val aad = messagingRecordAad("libsignal-v2", "active-protocol-state", 1L)
        val generatedDek = ByteArray(32) { 7 }
        val invalidDataIv = ByteArray(11) { 8 }

        val observed = runCatching {
            encryptSecureMessagingRecordEnvelope(
                aad = aad,
                plaintext = byteArrayOf(1),
                createDek = { generatedDek },
                createDataIv = { invalidDataIv },
                wrapDek = { _, _ -> error("Wrapping must not accept an invalid IV") },
            )
        }.exceptionOrNull()

        assertTrue(observed is IllegalArgumentException)
        assertTrue(generatedDek.all { it == 0.toByte() })
        assertTrue(invalidDataIv.all { it == 0.toByte() })
        aad.fill(0)
    }

    @Test
    fun `bootstrap wrap failure erases its uncommitted alias exactly once`() {
        val aad = messagingRecordAad("libsignal-v2", "active-protocol-state", 1L)
        val generatedDek = ByteArray(32) { 7 }
        val generatedIv = ByteArray(12) { 8 }
        val wrapFailure = ProviderException("Android 9 rejected the wrapping operation")
        var eraseCalls = 0

        val observed = runCatching {
            withSecureMessagingBootstrapKeyCleanup(
                cleanupRequired = true,
                eraseUncommittedBootstrapKey = { eraseCalls++ },
            ) {
                encryptSecureMessagingRecordEnvelope(
                    aad = aad,
                    plaintext = byteArrayOf(1, 2, 3),
                    createDek = { generatedDek },
                    createDataIv = { generatedIv },
                    wrapDek = { _, _ -> throw wrapFailure },
                )
            }
        }.exceptionOrNull()

        assertTrue(observed is SecureMessagingBootstrapRecordKeyValidationException)
        assertSame(wrapFailure, observed?.cause)
        assertEquals(1, eraseCalls)
        assertTrue(generatedDek.all { it == 0.toByte() })
        assertTrue(generatedIv.all { it == 0.toByte() })
        aad.fill(0)
    }

    @Test
    fun `state write accepts fifteen hundred thirty six KiB and rejects larger records`() {
        assertTrue(
            runCatching { SecureMessagingStateWrite("namespace", "record", null, byteArrayOf()) }
                .isFailure,
        )
        val maximum = ByteArray(MAX_SECURE_MESSAGING_RECORD_PLAINTEXT_BYTES)
        val accepted = SecureMessagingStateWrite("namespace", "record", null, maximum)
        assertEquals(MAX_SECURE_MESSAGING_RECORD_PLAINTEXT_BYTES, accepted.byteSize)
        accepted.wipeBytes()
        maximum.fill(0)

        val oversized = ByteArray(MAX_SECURE_MESSAGING_RECORD_PLAINTEXT_BYTES + 1)
        try {
            assertTrue(
                runCatching {
                    SecureMessagingStateWrite("namespace", "record", null, oversized)
                }.isFailure,
            )
        } finally {
            oversized.fill(0)
        }
    }

    @Test
    fun `atomic write bounds accept eight MiB and reject one byte more`() {
        validateSecureMessagingAtomicWriteBounds(
            writeCount = 8,
            totalPlaintextBytes = MAX_ATOMIC_BATCH_PLAINTEXT_BYTES,
        )
        validateSecureMessagingAtomicWriteBounds(
            writeCount = MAX_ATOMIC_WRITES,
            totalPlaintextBytes = MAX_ATOMIC_WRITES.toLong(),
        )

        assertTrue(
            runCatching {
                validateSecureMessagingAtomicWriteBounds(
                    writeCount = 9,
                    totalPlaintextBytes = MAX_ATOMIC_BATCH_PLAINTEXT_BYTES + 1,
                )
            }.exceptionOrNull() is IllegalArgumentException,
        )
        assertTrue(
            runCatching {
                validateSecureMessagingAtomicWriteBounds(
                    writeCount = MAX_ATOMIC_WRITES + 1,
                    totalPlaintextBytes = MAX_ATOMIC_WRITES.toLong() + 1,
                )
            }.exceptionOrNull() is IllegalArgumentException,
        )
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
    fun `authenticated tag failure dominates its nested retryable provider cause`() {
        val provider = ProviderException("Android 9 MAC verification failed")
        val badTag = AEADBadTagException("Signature or MAC verification failed").apply {
            initCause(provider)
        }
        val authenticationFailure = SecureMessagingRecordAuthenticationFailedException(
            SecureMessagingRecordKeyTemporarilyUnavailableException(badTag),
        )

        assertFalse(isRetryableSecureMessagingStateFailure(authenticationFailure))
        assertFalse(isTransientSecureMessagingRecordKeyFailure(authenticationFailure))
        assertTrue(
            secureMessagingStateAccessFailure(
                "authenticated decryption failed",
                authenticationFailure,
            ) is SecureMessagingStateUnavailableException,
        )
    }

    @Test
    fun `only unconfirmed version one protocol authentication failure receives recovery marker`() {
        val authenticationFailure = SecureMessagingStateUnavailableException(
            "authenticated decryption failed",
            SecureMessagingRecordAuthenticationFailedException(
                AEADBadTagException("Signature or MAC verification failed"),
            ),
        )

        val initial = classifySecureMessagingStoredRecordFailure(
            namespace = "libsignal-v2",
            recordKey = "active-protocol-state",
            version = 1L,
            error = authenticationFailure,
        )
        val confirmed = classifySecureMessagingStoredRecordFailure(
            namespace = "libsignal-v2",
            recordKey = "active-protocol-state",
            version = 2L,
            error = authenticationFailure,
        )
        val ordinaryProjection = classifySecureMessagingStoredRecordFailure(
            namespace = "projection-metadata-v1",
            recordKey = "active-protocol-state",
            version = 1L,
            error = authenticationFailure,
        )

        assertTrue(
            generateSequence(initial as Throwable?) { it.cause }
                .any { it is SecureMessagingInitialEnrollmentAuthenticationFailedException },
        )
        assertFalse(isRecoverableSecureMessagingStateLoss(initial))
        assertSame(authenticationFailure, confirmed)
        assertFalse(isRecoverableSecureMessagingStateLoss(confirmed))
        assertSame(authenticationFailure, ordinaryProjection)
        assertFalse(isRecoverableSecureMessagingStateLoss(ordinaryProjection))
    }

    @Test
    fun `legacy confirmed decrypt failures cannot assert remote reset authority`() {
        val providerFailure = SecureMessagingStateUnavailableException(
            "Android 9 provider could not reopen the legacy row",
            ProviderException("legacy direct GCM operation failed"),
        )
        val retryableProviderFailure = SecureMessagingStateRetryableException(
            "Android 9 provider temporarily hid the legacy key",
            ProviderException("legacy direct GCM operation should be retried"),
        )
        val authenticationFailure = SecureMessagingStateUnavailableException(
            "Legacy row failed authenticated decryption",
            SecureMessagingRecordAuthenticationFailedException(
                AEADBadTagException("Signature or MAC verification failed"),
            ),
        )
        val malformedFormat = IllegalArgumentException("Unknown secure messaging record format")

        val legacyInitial = classifySecureMessagingStoredRecordFailure(
            namespace = "libsignal-v2",
            recordKey = "active-protocol-state",
            version = 1L,
            atRestProvenance = SecureMessagingRecordAtRestProvenance.LEGACY_DIRECT_V1,
            error = providerFailure,
        )
        val envelopeInitial = classifySecureMessagingStoredRecordFailure(
            namespace = "libsignal-v2",
            recordKey = "active-protocol-state",
            version = 1L,
            atRestProvenance = SecureMessagingRecordAtRestProvenance.DEK_ENVELOPE_V1,
            error = providerFailure,
        )
        val legacyConfirmed = classifySecureMessagingStoredRecordFailure(
            namespace = "libsignal-v2",
            recordKey = "active-protocol-state",
            version = 2L,
            atRestProvenance = SecureMessagingRecordAtRestProvenance.LEGACY_DIRECT_V1,
            error = providerFailure,
        )
        val retryableLegacyConfirmed = classifySecureMessagingStoredRecordFailure(
            namespace = "libsignal-v2",
            recordKey = "active-protocol-state",
            version = 2L,
            atRestProvenance = SecureMessagingRecordAtRestProvenance.LEGACY_DIRECT_V1,
            error = retryableProviderFailure,
        )
        val authenticationFailedLegacyConfirmed = classifySecureMessagingStoredRecordFailure(
            namespace = "libsignal-v2",
            recordKey = "active-protocol-state",
            version = 2L,
            atRestProvenance = SecureMessagingRecordAtRestProvenance.LEGACY_DIRECT_V1,
            error = authenticationFailure,
        )
        val malformedLegacyConfirmed = classifySecureMessagingStoredRecordFailure(
            namespace = "libsignal-v2",
            recordKey = "active-protocol-state",
            version = 2L,
            atRestProvenance = SecureMessagingRecordAtRestProvenance.LEGACY_DIRECT_V1,
            error = malformedFormat,
        )
        val envelopeConfirmed = classifySecureMessagingStoredRecordFailure(
            namespace = "libsignal-v2",
            recordKey = "active-protocol-state",
            version = 2L,
            atRestProvenance = SecureMessagingRecordAtRestProvenance.DEK_ENVELOPE_V1,
            error = providerFailure,
        )
        val unspecifiedConfirmed = classifySecureMessagingStoredRecordFailure(
            namespace = "libsignal-v2",
            recordKey = "active-protocol-state",
            version = 2L,
            error = providerFailure,
        )
        val nonProtocolLegacy = classifySecureMessagingStoredRecordFailure(
            namespace = "projection-metadata-v1",
            recordKey = "active-protocol-state",
            version = 2L,
            atRestProvenance = SecureMessagingRecordAtRestProvenance.LEGACY_DIRECT_V1,
            error = providerFailure,
        )

        assertTrue(
            generateSequence(legacyInitial as Throwable?) { it.cause }
                .any { it is SecureMessagingLegacyInitialEnrollmentUnreadableException },
        )
        assertSame(providerFailure, envelopeInitial)
        assertSame(providerFailure, legacyConfirmed)
        assertSame(retryableProviderFailure, retryableLegacyConfirmed)
        assertSame(authenticationFailure, authenticationFailedLegacyConfirmed)
        assertSame(malformedFormat, malformedLegacyConfirmed)
        assertSame(providerFailure, envelopeConfirmed)
        assertSame(providerFailure, unspecifiedConfirmed)
        assertSame(providerFailure, nonProtocolLegacy)
        assertFalse(isRecoverableSecureMessagingStateLoss(legacyInitial))
        assertFalse(isRecoverableSecureMessagingStateLoss(legacyConfirmed))
        assertFalse(isLegacyConfirmedSecureMessagingEnrollmentUnreadableFailure(legacyConfirmed))
        assertFalse(isLegacyConfirmedSecureMessagingEnrollmentUnreadableFailure(legacyInitial))
    }

    @Test
    fun `post decrypt classifier rejects metadata and arbitrary truncation-shaped garbage`() {
        val structuralFailure = SecureMessagingCryptographicFailureException(
            SecureMessagingQuarantineReason.REPLAY_OR_ROLLBACK,
            "Authenticated protocol state was structurally truncated",
            EOFException("truncated bound libsignal state"),
        )
        fun record(
            version: Long = 2L,
            namespace: String = "libsignal-v2",
            provenance: SecureMessagingRecordAtRestProvenance,
            bytes: ByteArray = ByteArray(65_536) { 0x5a },
        ) = SecureMessagingRecord(
            namespace = namespace,
            recordKey = "active-protocol-state",
            version = version,
            bytes = bytes,
            updatedAtEpochMillis = 1L,
            atRestProvenance = provenance,
        )

        val arbitraryLegacy = record(
            provenance = SecureMessagingRecordAtRestProvenance.LEGACY_DIRECT_V1,
        )
        val arbitraryProof = SecureMessagingAuthenticatedAndroid9LegacyTruncationProof.validate(
            arbitraryLegacy.bytes,
            structuralFailure,
        )
        val confirmedLegacy = classifySecureMessagingDecodedRecordFailure(
            arbitraryLegacy,
            structuralFailure,
            arbitraryProof,
        )
        val versionOneLegacy = classifySecureMessagingDecodedRecordFailure(
            record(
                version = 1L,
                provenance = SecureMessagingRecordAtRestProvenance.LEGACY_DIRECT_V1,
            ),
            structuralFailure,
        )
        val envelope = classifySecureMessagingDecodedRecordFailure(
            record(provenance = SecureMessagingRecordAtRestProvenance.DEK_ENVELOPE_V1),
            structuralFailure,
        )
        val unspecified = classifySecureMessagingDecodedRecordFailure(
            record(provenance = SecureMessagingRecordAtRestProvenance.UNSPECIFIED),
            structuralFailure,
        )
        val nonProtocol = classifySecureMessagingDecodedRecordFailure(
            record(
                namespace = "projection-metadata-v1",
                provenance = SecureMessagingRecordAtRestProvenance.LEGACY_DIRECT_V1,
            ),
            structuralFailure,
        )

        assertTrue(arbitraryProof == null)
        assertSame(structuralFailure, confirmedLegacy)
        assertFalse(isLegacyConfirmedSecureMessagingEnrollmentUnreadableFailure(confirmedLegacy))
        assertFalse(isLegacyConfirmedSecureMessagingEnrollmentUnreadableFailure(versionOneLegacy))
        assertSame(structuralFailure, versionOneLegacy)
        assertSame(structuralFailure, envelope)
        assertSame(structuralFailure, unspecified)
        assertSame(structuralFailure, nonProtocol)
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

    private fun testEncryptEnvelope(
        aad: ByteArray,
        plaintext: ByteArray,
    ): EncryptedMessagingRecord = encryptSecureMessagingRecordEnvelope(
        aad = aad,
        plaintext = plaintext,
        createDek = { ByteArray(32).also(TEST_RANDOM::nextBytes) },
        createDataIv = { ByteArray(12).also(TEST_RANDOM::nextBytes) },
        wrapDek = ::testWrapDek,
    )

    private fun testWrapDek(aad: ByteArray, dek: ByteArray): EncryptedMessagingRecord {
        val iv = ByteArray(12).also(TEST_RANDOM::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(TEST_KEK_BYTES, "AES"),
            GCMParameterSpec(128, iv),
        )
        cipher.updateAAD(aad)
        return EncryptedMessagingRecord(iv, cipher.doFinal(dek))
    }

    private fun testUnwrapDek(
        aad: ByteArray,
        wrapped: EncryptedMessagingRecord,
    ): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
        init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(TEST_KEK_BYTES, "AES"),
            GCMParameterSpec(128, wrapped.iv),
        )
        updateAAD(aad)
        doFinal(wrapped.ciphertext)
    }

    private fun testEncryptLegacy(
        aad: ByteArray,
        plaintext: ByteArray,
    ): EncryptedMessagingRecord {
        val iv = ByteArray(12).also(TEST_RANDOM::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(TEST_KEK_BYTES, "AES"),
            GCMParameterSpec(128, iv),
        )
        cipher.updateAAD(aad)
        return EncryptedMessagingRecord(iv, cipher.doFinal(plaintext))
    }

    private fun testDecryptLegacy(
        aad: ByteArray,
        record: EncryptedMessagingRecord,
    ): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
        init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(TEST_KEK_BYTES, "AES"),
            GCMParameterSpec(128, record.iv),
        )
        updateAAD(aad)
        doFinal(record.ciphertext)
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

    /**
     * Models an OEM workaround provider that advertises AES/GCM globally but cannot accept an
     * ordinary in-memory key. JCA can skip it only while provider choice remains delayed to init.
     */
    private class RejectingSecretKeySpecAesGcmProvider(
        providerName: String,
    ) : Provider(providerName, 1.0, "Rejects ordinary AES keys") {
        init {
            putService(
                object : Service(
                    this@RejectingSecretKeySpecAesGcmProvider,
                    "Cipher",
                    "AES/GCM/NoPadding",
                    RejectingSecretKeySpecAesGcmCipherSpi::class.java.name,
                    emptyList(),
                    emptyMap(),
                ) {
                    override fun supportsParameter(parameter: Any?): Boolean =
                        parameter !is SecretKeySpec

                    override fun newInstance(constructorParameter: Any?): Any =
                        RejectingSecretKeySpecAesGcmCipherSpi()
                },
            )
        }
    }

    private class RejectingSecretKeySpecAesGcmCipherSpi : CipherSpi() {
        override fun engineSetMode(mode: String?) = Unit
        override fun engineSetPadding(padding: String?) = Unit
        override fun engineGetBlockSize(): Int = 16
        override fun engineGetOutputSize(inputLen: Int): Int = inputLen + 16
        override fun engineGetIV(): ByteArray = ByteArray(12)
        override fun engineGetParameters(): AlgorithmParameters? = null

        override fun engineInit(opmode: Int, key: Key?, random: SecureRandom?) {
            throw InvalidKeyException("This provider rejects ordinary in-memory AES keys")
        }

        override fun engineInit(
            opmode: Int,
            key: Key?,
            params: AlgorithmParameterSpec?,
            random: SecureRandom?,
        ) {
            throw InvalidKeyException("This provider rejects ordinary in-memory AES keys")
        }

        override fun engineInit(
            opmode: Int,
            key: Key?,
            params: AlgorithmParameters?,
            random: SecureRandom?,
        ) {
            throw InvalidKeyException("This provider rejects ordinary in-memory AES keys")
        }

        override fun engineUpdate(input: ByteArray?, inputOffset: Int, inputLen: Int): ByteArray =
            throw IllegalStateException("Rejecting provider must not process payload data")

        @Throws(ShortBufferException::class)
        override fun engineUpdate(
            input: ByteArray?,
            inputOffset: Int,
            inputLen: Int,
            output: ByteArray?,
            outputOffset: Int,
        ): Int = throw IllegalStateException("Rejecting provider must not process payload data")

        @Throws(IllegalBlockSizeException::class, BadPaddingException::class)
        override fun engineDoFinal(input: ByteArray?, inputOffset: Int, inputLen: Int): ByteArray =
            throw IllegalStateException("Rejecting provider must not process payload data")

        @Throws(ShortBufferException::class, IllegalBlockSizeException::class, BadPaddingException::class)
        override fun engineDoFinal(
            input: ByteArray?,
            inputOffset: Int,
            inputLen: Int,
            output: ByteArray?,
            outputOffset: Int,
        ): Int = throw IllegalStateException("Rejecting provider must not process payload data")
    }

    private companion object {
        val TEST_RANDOM = SecureRandom()
        val TEST_KEK_BYTES = ByteArray(32) { index -> (index * 13 + 5).toByte() }
    }
}
