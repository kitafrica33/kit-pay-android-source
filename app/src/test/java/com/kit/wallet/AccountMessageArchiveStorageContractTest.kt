package com.kit.wallet

import com.kit.wallet.data.local.AccountMessageArchiveEntity
import com.kit.wallet.data.messaging.AccountMessageArchiveKeyUnavailableException
import com.kit.wallet.data.messaging.AccountMessageArchiveBootstrapKeyValidationException
import com.kit.wallet.data.messaging.AccountMessageArchiveKeyPermanentlyMissingException
import com.kit.wallet.data.messaging.AccountMessageArchiveKeyPermanentlyUnrecoverableException
import com.kit.wallet.data.messaging.AccountMessageArchiveKeyResolution
import com.kit.wallet.data.messaging.AccountMessageArchiveOwner
import com.kit.wallet.data.messaging.AccountMessageArchiveStorageFormat
import com.kit.wallet.data.messaging.AccountMessageArchiveUnavailableException
import com.kit.wallet.data.messaging.EncryptedAccountMessageArchiveRecord
import com.kit.wallet.data.messaging.MAX_ACCOUNT_MESSAGE_ARCHIVE_RECORD_BYTES
import com.kit.wallet.data.messaging.SecureMessagingRecordKeyMissState
import com.kit.wallet.data.messaging.accountMessageArchiveAad
import com.kit.wallet.data.messaging.accountMessageArchiveKeyAlias
import com.kit.wallet.data.messaging.accountMessageArchiveKeyCreationAllowed
import com.kit.wallet.data.messaging.accountMessageArchiveStorageFormat
import com.kit.wallet.data.messaging.classifyAccountMessageArchiveKeyAccessFailure
import com.kit.wallet.data.messaging.classifyAccountMessageArchiveKeyOperationFailure
import com.kit.wallet.data.messaging.decryptAccountMessageArchiveRecordEnvelope
import com.kit.wallet.data.messaging.decryptLegacyAccountMessageArchiveRecord
import com.kit.wallet.data.messaging.encryptAccountMessageArchiveRecordEnvelope
import com.kit.wallet.data.messaging.hasPermanentlyMissingAccountMessageArchiveKey
import com.kit.wallet.data.messaging.isRetryableAccountMessageArchiveKeyFailure
import com.kit.wallet.data.messaging.observeSecureMessagingRecordKeyMiss
import com.kit.wallet.data.messaging.resolveAccountMessageArchiveKey
import com.kit.wallet.data.messaging.resolveAccountMessageArchiveKeyWithCreationStatus
import com.kit.wallet.data.messaging.validateBootstrapAccountMessageArchiveKeyRoundTrip
import java.lang.reflect.Modifier
import java.security.InvalidKeyException
import java.security.ProviderException
import java.security.SecureRandom
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AccountMessageArchiveStorageContractTest {
    @Test
    fun `hybrid archive envelope round trips the maximum row without large keystore input`() {
        val aad = accountMessageArchiveAad(OWNER_A, "message:maximum", 1L)

        listOf(65_536, 77_248, MAX_ACCOUNT_MESSAGE_ARCHIVE_RECORD_BYTES).forEach { size ->
            val plaintext = ByteArray(size) { index -> (index * 31 + size).toByte() }
            val generatedDek = ByteArray(32) { index -> (index + size).toByte() }
            val generatedDataIv = ByteArray(12) { index -> (index + 7).toByte() }
            val keystorePlaintextSizes = mutableListOf<Int>()
            val keystoreCiphertextSizes = mutableListOf<Int>()

            val encrypted = encryptAccountMessageArchiveRecordEnvelope(
                aad = aad,
                plaintext = plaintext,
                createDek = { generatedDek },
                createDataIv = { generatedDataIv },
                wrapDek = { wrapAad, dek ->
                    keystorePlaintextSizes += dek.size
                    testWrapArchiveDek(wrapAad, dek)
                },
            )

            assertEquals(
                AccountMessageArchiveStorageFormat.DEK_ENVELOPE_V1,
                accountMessageArchiveStorageFormat(encrypted),
            )
            assertEquals(32, encrypted.iv.size)
            assertEquals(size + 64, encrypted.ciphertext.size)
            assertEquals(listOf(32), keystorePlaintextSizes)
            assertTrue(generatedDek.all { it == 0.toByte() })
            assertTrue(generatedDataIv.all { it == 0.toByte() })

            var recoveredDek: ByteArray? = null
            val decrypted = decryptAccountMessageArchiveRecordEnvelope(
                aad = aad,
                record = encrypted,
                unwrapDek = { wrapAad, wrappedDek ->
                    keystoreCiphertextSizes += wrappedDek.ciphertext.size
                    testUnwrapArchiveDek(wrapAad, wrappedDek).also { recoveredDek = it }
                },
            )
            try {
                assertArrayEquals(plaintext, decrypted)
                assertEquals(listOf(48), keystoreCiphertextSizes)
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
    fun `archive envelope rejects an over-limit row before generating key material`() {
        val aad = accountMessageArchiveAad(OWNER_A, "message:oversized", 1L)
        val oversized = ByteArray(MAX_ACCOUNT_MESSAGE_ARCHIVE_RECORD_BYTES + 1)
        var generated = false

        val observed = runCatching {
            encryptAccountMessageArchiveRecordEnvelope(
                aad = aad,
                plaintext = oversized,
                createDek = {
                    generated = true
                    ByteArray(32)
                },
                createDataIv = { ByteArray(12) },
                wrapDek = ::testWrapArchiveDek,
            )
        }.exceptionOrNull()

        assertTrue(observed is IllegalArgumentException)
        assertFalse(generated)
        oversized.fill(0)
        aad.fill(0)
    }

    @Test
    fun `legacy archive rows with twelve byte IVs remain directly decryptable`() {
        val aad = accountMessageArchiveAad(OWNER_A, "message:legacy", 7L)
        val plaintext = ByteArray(1_024) { index -> (index * 17).toByte() }
        val legacy = testEncryptLegacyArchiveRecord(aad, plaintext)

        assertEquals(
            AccountMessageArchiveStorageFormat.LEGACY_DIRECT_V1,
            accountMessageArchiveStorageFormat(legacy),
        )
        val decrypted = decryptLegacyAccountMessageArchiveRecord(
            aad = aad,
            record = legacy,
            decryptDirect = ::testDecryptLegacyArchiveRecord,
        )

        assertArrayEquals(plaintext, decrypted)
        plaintext.fill(0)
        decrypted.fill(0)
        legacy.iv.fill(0)
        legacy.ciphertext.fill(0)
        aad.fill(0)
    }

    @Test
    fun `archive envelope authenticates routing wrapped key and full payload`() {
        val aad = accountMessageArchiveAad(OWNER_A, "message:authenticated", 3L)
        val plaintext = ByteArray(2_048) { it.toByte() }
        val encrypted = testEncryptArchiveEnvelope(aad, plaintext)
        val corruptions = listOf(
            aad.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() } to encrypted,
            aad to encrypted.copy(
                ciphertext = encrypted.ciphertext.copyOf().also {
                    it[0] = (it[0] + 1).toByte()
                },
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
                    decryptAccountMessageArchiveRecordEnvelope(
                        corruptAad,
                        corruptRecord,
                        ::testUnwrapArchiveDek,
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
    fun `archive AAD is deterministic and binds every routing and version field`() {
        val baseline = accountMessageArchiveAad(OWNER_A, "message:one", 7L)

        assertArrayEquals(baseline, accountMessageArchiveAad(OWNER_A, "message:one", 7L))
        assertNotEquals(
            baseline.toList(),
            accountMessageArchiveAad(OWNER_B, "message:one", 7L).toList(),
        )
        assertNotEquals(
            baseline.toList(),
            accountMessageArchiveAad(OWNER_A_OTHER_INSTALLATION, "message:one", 7L).toList(),
        )
        assertNotEquals(
            baseline.toList(),
            accountMessageArchiveAad(OWNER_A, "message:two", 7L).toList(),
        )
        assertNotEquals(
            baseline.toList(),
            accountMessageArchiveAad(OWNER_A, "message:one", 8L).toList(),
        )
        assertNotEquals(
            accountMessageArchiveAad(OWNER_A, "a:b", 7L).toList(),
            accountMessageArchiveAad(OWNER_A, "a", 7L).toList(),
        )
    }

    @Test
    fun `archive owner and record addresses are canonical and bounded`() {
        AccountMessageArchiveOwner(ACCOUNT_A, INSTALLATION_A)

        assertFails<IllegalArgumentException> {
            AccountMessageArchiveOwner(ACCOUNT_A, INSTALLATION_A.uppercase())
        }
        assertFails<IllegalArgumentException> {
            AccountMessageArchiveOwner(ACCOUNT_A, "not-an-installation")
        }
        assertFails<IllegalArgumentException> {
            accountMessageArchiveAad(OWNER_A, "contains spaces", 1L)
        }
        assertFails<IllegalArgumentException> {
            accountMessageArchiveAad(OWNER_A, "message:one", 0L)
        }
    }

    @Test
    fun `archive aliases are opaque deterministic and independently owner scoped`() {
        val alias = accountMessageArchiveKeyAlias(OWNER_A)

        assertEquals(alias, accountMessageArchiveKeyAlias(OWNER_A))
        assertNotEquals(alias, accountMessageArchiveKeyAlias(OWNER_B))
        assertNotEquals(alias, accountMessageArchiveKeyAlias(OWNER_A_OTHER_INSTALLATION))
        assertFalse(alias.contains(ACCOUNT_A))
        assertFalse(alias.contains(INSTALLATION_A))
        assertTrue(alias.startsWith("kit_pay_account_message_archive_aes_v1_"))
        assertTrue(alias.length < 128)
    }

    @Test
    fun `retained owner ciphertext never permits a replacement archive key`() {
        var creations = 0

        val failure = runCatching {
            resolveAccountMessageArchiveKey(
                allowCreation = accountMessageArchiveKeyCreationAllowed(
                    existingOwnerRecordCount = 1,
                ),
                loadExisting = { null },
                createNew = {
                    creations++
                    "replacement-key"
                },
            )
        }.exceptionOrNull()

        assertTrue(failure is AccountMessageArchiveKeyUnavailableException)
        assertEquals(0, creations)
    }

    @Test
    fun `empty owner archive can bootstrap once and existing alias always wins`() {
        var creations = 0
        val created = resolveAccountMessageArchiveKey(
            allowCreation = accountMessageArchiveKeyCreationAllowed(0),
            loadExisting = { null },
            createNew = {
                creations++
                "new-key"
            },
        )
        val existing = resolveAccountMessageArchiveKey(
            allowCreation = false,
            loadExisting = { "existing-key" },
            createNew = {
                creations++
                "unexpected-key"
            },
        )

        assertEquals("new-key", created)
        assertEquals("existing-key", existing)
        assertEquals(1, creations)
        assertFails<IllegalArgumentException> {
            accountMessageArchiveKeyCreationAllowed(-1)
        }
    }

    @Test
    fun `empty owner archive classifies an existing unusable alias without replacing it`() {
        var creations = 0
        var observed = SecureMessagingRecordKeyMissState()
        var finalFailure: Exception? = null
        val observationTimes = listOf(1_000L, 6_000L, 11_000L, 16_000L)

        observationTimes.forEachIndexed { index, now ->
            val resolution = resolveAccountMessageArchiveKeyWithCreationStatus(
                allowCreation = true,
                loadExisting = { "present-unusable-key" },
                createNew = {
                    creations++
                    AccountMessageArchiveKeyResolution("replacement-key", createdNow = true)
                },
            )
            assertFalse(resolution.createdNow)

            finalFailure = classifyAccountMessageArchiveKeyOperationFailure(
                error = InvalidKeyException("Android 9 cannot initialize the archive alias"),
                keyCreatedNow = resolution.createdNow,
                classifyExistingAliasFailure = { cause ->
                    observed = observeSecureMessagingRecordKeyMiss(
                        previous = observed,
                        userUnlocked = true,
                        nowEpochMillis = now,
                    )
                    if (observed.permanentlyMissing) {
                        AccountMessageArchiveKeyPermanentlyUnrecoverableException(cause)
                    } else {
                        AccountMessageArchiveKeyUnavailableException(cause)
                    }
                },
            )
            if (index < observationTimes.lastIndex) {
                assertTrue(finalFailure is AccountMessageArchiveKeyUnavailableException)
            }
        }

        assertEquals(0, creations)
        assertTrue(finalFailure is AccountMessageArchiveKeyPermanentlyUnrecoverableException)
    }

    @Test
    fun `empty owner creation permission cannot bypass a throwing existing alias lookup`() {
        var creations = 0
        var observed = SecureMessagingRecordKeyMissState()
        var finalFailure: Throwable? = null
        val observationTimes = listOf(1_000L, 6_000L, 11_000L, 16_000L)

        observationTimes.forEachIndexed { index, now ->
            finalFailure = runCatching {
                resolveAccountMessageArchiveKeyWithCreationStatus(
                    allowCreation = true,
                    loadExisting = {
                        throw UnrecoverableKeyException(
                            "Android 9 cannot recover the existing archive alias",
                        )
                    },
                    createNew = {
                        creations++
                        AccountMessageArchiveKeyResolution("replacement-key", createdNow = true)
                    },
                    classifyExistingAliasFailure = { cause ->
                        observed = observeSecureMessagingRecordKeyMiss(
                            previous = observed,
                            userUnlocked = true,
                            nowEpochMillis = now,
                        )
                        if (observed.permanentlyMissing) {
                            AccountMessageArchiveKeyPermanentlyUnrecoverableException(cause)
                        } else {
                            AccountMessageArchiveKeyUnavailableException(cause)
                        }
                    },
                )
            }.exceptionOrNull()
            if (index < observationTimes.lastIndex) {
                assertTrue(finalFailure is AccountMessageArchiveKeyUnavailableException)
            }
        }

        assertEquals(0, creations)
        assertTrue(finalFailure is AccountMessageArchiveKeyPermanentlyUnrecoverableException)
    }

    @Test
    fun `genuinely fresh archive key keeps its first provider failure retryable`() {
        val resolution = resolveAccountMessageArchiveKeyWithCreationStatus(
            allowCreation = true,
            loadExisting = { null },
            createNew = {
                AccountMessageArchiveKeyResolution("fresh-key", createdNow = true)
            },
        )
        val providerFailure = InvalidKeyException("fresh Android 9 archive key is not ready")
        var existingAliasClassified = false

        val classified = classifyAccountMessageArchiveKeyOperationFailure(
            error = providerFailure,
            keyCreatedNow = resolution.createdNow,
            classifyExistingAliasFailure = {
                existingAliasClassified = true
                AccountMessageArchiveKeyPermanentlyUnrecoverableException(it)
            },
        )

        assertSame(providerFailure, classified)
        assertFalse(existingAliasClassified)
    }

    @Test
    fun `empty owner rejects a null independent alias reload before commit`() {
        val events = mutableListOf<String>()

        val failure = runCatching {
            validateBootstrapAccountMessageArchiveKeyRoundTrip(
                resolution = AccountMessageArchiveKeyResolution(Any(), createdNow = true),
                bootstrapProbeRequired = true,
                expectedDek = ByteArray(32) { (it + 1).toByte() },
                reloadBootstrapKey = {
                    events += "reload"
                    null
                },
                authenticatedUnwrap = {
                    events += "authenticate"
                    error("A missing alias cannot authenticate")
                },
                eraseUncommittedBootstrapKey = { events += "erase-exact-alias" },
            )
            events += "commit"
        }.exceptionOrNull()

        assertTrue(failure is AccountMessageArchiveBootstrapKeyValidationException)
        assertEquals(listOf("reload", "erase-exact-alias"), events)
    }

    @Test
    fun `empty owner rejects a wrong reloaded DEK and wipes it before commit`() {
        val expected = ByteArray(32) { (it + 3).toByte() }
        val wrong = ByteArray(32) { (it + 4).toByte() }
        val events = mutableListOf<String>()

        val failure = runCatching {
            validateBootstrapAccountMessageArchiveKeyRoundTrip(
                resolution = AccountMessageArchiveKeyResolution(Any(), createdNow = true),
                bootstrapProbeRequired = true,
                expectedDek = expected,
                reloadBootstrapKey = {
                    events += "reload"
                    Any()
                },
                authenticatedUnwrap = {
                    events += "authenticate"
                    wrong
                },
                eraseUncommittedBootstrapKey = { events += "erase-exact-alias" },
            )
            events += "commit"
        }.exceptionOrNull()

        assertTrue(failure is AccountMessageArchiveBootstrapKeyValidationException)
        assertEquals(listOf("reload", "authenticate", "erase-exact-alias"), events)
        assertTrue(wrong.all { it == 0.toByte() })
        assertTrue(expected.indices.all { expected[it] == (it + 3).toByte() })
    }

    @Test
    fun `empty owner authentication failure erases exact alias before commit`() {
        val authenticationFailure = AEADBadTagException(
            "Reloaded Android 9 archive alias cannot authenticate the wrapped DEK",
        )
        val events = mutableListOf<String>()

        val failure = runCatching {
            validateBootstrapAccountMessageArchiveKeyRoundTrip(
                resolution = AccountMessageArchiveKeyResolution(Any(), createdNow = true),
                bootstrapProbeRequired = true,
                expectedDek = ByteArray(32) { (it + 5).toByte() },
                reloadBootstrapKey = {
                    events += "reload"
                    Any()
                },
                authenticatedUnwrap = {
                    events += "authenticate"
                    throw authenticationFailure
                },
                eraseUncommittedBootstrapKey = { events += "erase-exact-alias" },
            )
            events += "commit"
        }.exceptionOrNull()

        assertTrue(failure is AccountMessageArchiveBootstrapKeyValidationException)
        assertSame(authenticationFailure, failure?.cause)
        assertEquals(listOf("reload", "authenticate", "erase-exact-alias"), events)
    }

    @Test
    fun `bootstrap cleanup failure is suppressed without permitting commit`() {
        val authenticationFailure = AEADBadTagException("bootstrap authentication failed")
        val cleanupFailure = IllegalStateException("exact archive alias deletion failed")
        val events = mutableListOf<String>()

        val failure = runCatching {
            validateBootstrapAccountMessageArchiveKeyRoundTrip(
                resolution = AccountMessageArchiveKeyResolution(Any(), createdNow = false),
                bootstrapProbeRequired = true,
                expectedDek = ByteArray(32) { (it + 6).toByte() },
                reloadBootstrapKey = {
                    events += "reload"
                    Any()
                },
                authenticatedUnwrap = {
                    events += "authenticate"
                    throw authenticationFailure
                },
                eraseUncommittedBootstrapKey = {
                    events += "erase-exact-alias"
                    throw cleanupFailure
                },
            )
            events += "commit"
        }.exceptionOrNull()

        assertTrue(failure is AccountMessageArchiveBootstrapKeyValidationException)
        assertSame(authenticationFailure, failure?.cause)
        assertEquals(listOf(cleanupFailure), failure?.suppressed?.toList())
        assertEquals(listOf("reload", "authenticate", "erase-exact-alias"), events)
    }

    @Test
    fun `every empty owner first write authenticates an existing alias before commit`() {
        val generatedHandle = Any()
        val independentlyReloadedHandle = Any()
        val expected = ByteArray(32) { (it + 7).toByte() }
        val authenticated = expected.copyOf()
        val events = mutableListOf<String>()

        val proven = validateBootstrapAccountMessageArchiveKeyRoundTrip(
            resolution = AccountMessageArchiveKeyResolution(
                generatedHandle,
                // Empty-owner proof is required even for an alias left by an earlier attempt.
                createdNow = false,
            ),
            bootstrapProbeRequired = true,
            expectedDek = expected,
            reloadBootstrapKey = {
                events += "reload"
                independentlyReloadedHandle
            },
            authenticatedUnwrap = { key ->
                assertSame(independentlyReloadedHandle, key)
                events += "authenticate"
                authenticated
            },
            eraseUncommittedBootstrapKey = { events += "erase-exact-alias" },
        )
        events += "commit"

        assertSame(independentlyReloadedHandle, proven)
        assertEquals(listOf("reload", "authenticate", "commit"), events)
        assertTrue(authenticated.all { it == 0.toByte() })
        assertTrue(expected.indices.all { expected[it] == (it + 7).toByte() })
    }

    @Test
    fun `archive alias abandonment requires explicit permanent missing key proof`() {
        val transient = AccountMessageArchiveUnavailableException(
            "archive read failed",
            AccountMessageArchiveKeyUnavailableException(),
        )
        val permanent = AccountMessageArchiveUnavailableException(
            "archive read failed",
            AccountMessageArchiveKeyPermanentlyMissingException(),
        )
        val permanentlyUnrecoverable = AccountMessageArchiveUnavailableException(
            "archive read failed",
            AccountMessageArchiveKeyPermanentlyUnrecoverableException(),
        )

        assertFalse(transient.hasPermanentlyMissingAccountMessageArchiveKey())
        assertTrue(permanent.hasPermanentlyMissingAccountMessageArchiveKey())
        assertTrue(permanentlyUnrecoverable.hasPermanentlyMissingAccountMessageArchiveKey())
    }

    @Test
    fun `archive provider failures require corroborating alias state before advancing loss proof`() {
        var missingObservations = 0
        var unrecoverableObservations = 0
        val observeMissing: (Throwable) -> RuntimeException = { cause ->
            missingObservations++
            AccountMessageArchiveKeyPermanentlyMissingException(cause)
        }
        val observeUnrecoverable: (Throwable) -> RuntimeException = { cause ->
            unrecoverableObservations++
            AccountMessageArchiveKeyPermanentlyUnrecoverableException(cause)
        }

        val absent = classifyAccountMessageArchiveKeyAccessFailure(
            cause = ProviderException("archive alias lookup failed"),
            userAuthenticationRequired = false,
            aliasPresent = false,
            observeMissingAlias = observeMissing,
            observeUnrecoverableAlias = observeUnrecoverable,
        )
        assertTrue(absent is AccountMessageArchiveKeyPermanentlyMissingException)
        assertEquals(1, missingObservations)
        assertEquals(0, unrecoverableObservations)

        val unusable = classifyAccountMessageArchiveKeyAccessFailure(
            cause = ProviderException(
                "archive Cipher initialization failed",
                InvalidKeyException("archive alias cannot initialize Cipher"),
            ),
            userAuthenticationRequired = false,
            aliasPresent = true,
            observeMissingAlias = observeMissing,
            observeUnrecoverableAlias = observeUnrecoverable,
        )
        assertTrue(unusable is AccountMessageArchiveKeyPermanentlyUnrecoverableException)
        assertEquals(1, missingObservations)
        assertEquals(1, unrecoverableObservations)

        val uncorroborated = classifyAccountMessageArchiveKeyAccessFailure(
            cause = ProviderException("temporary provider outage"),
            userAuthenticationRequired = false,
            aliasPresent = null,
            observeMissingAlias = observeMissing,
            observeUnrecoverableAlias = observeUnrecoverable,
        )
        assertTrue(uncorroborated is AccountMessageArchiveKeyUnavailableException)
        assertEquals(1, missingObservations)
        assertEquals(1, unrecoverableObservations)

        val locked = classifyAccountMessageArchiveKeyAccessFailure(
            cause = InvalidKeyException("authentication required"),
            userAuthenticationRequired = true,
            aliasPresent = false,
            observeMissingAlias = observeMissing,
            observeUnrecoverableAlias = observeUnrecoverable,
        )
        assertTrue(locked is AccountMessageArchiveKeyUnavailableException)
        assertEquals(1, missingObservations)
        assertEquals(1, unrecoverableObservations)
    }

    @Test
    fun `archive operation classifier excludes authenticated ciphertext corruption`() {
        assertTrue(ProviderException("deferred Keystore failure").isRetryableAccountMessageArchiveKeyFailure())
        assertTrue(InvalidKeyException("alias cannot be used").isRetryableAccountMessageArchiveKeyFailure())
        assertFalse(AEADBadTagException("ciphertext authentication failed").isRetryableAccountMessageArchiveKeyFailure())
    }

    @Test
    fun `present but unusable archive alias needs bounded observations before abandonment`() {
        var observed = SecureMessagingRecordKeyMissState()
        val observations = listOf(1_000L, 6_000L, 11_000L, 16_000L)

        observations.forEachIndexed { index, now ->
            val failure = classifyAccountMessageArchiveKeyAccessFailure(
                cause = InvalidKeyException("archive Cipher init failed"),
                userAuthenticationRequired = false,
                aliasPresent = true,
                observeMissingAlias = {
                    throw AssertionError("Present alias was classified as missing")
                },
                observeUnrecoverableAlias = { cause ->
                    observed = observeSecureMessagingRecordKeyMiss(observed, true, now)
                    if (observed.permanentlyMissing) {
                        AccountMessageArchiveKeyPermanentlyUnrecoverableException(cause)
                    } else {
                        AccountMessageArchiveKeyUnavailableException(cause)
                    }
                },
            )
            if (index < observations.lastIndex) {
                assertTrue(failure is AccountMessageArchiveKeyUnavailableException)
                assertFalse(failure.hasPermanentlyMissingAccountMessageArchiveKey())
            } else {
                assertTrue(failure is AccountMessageArchiveKeyPermanentlyUnrecoverableException)
                assertTrue(failure.hasPermanentlyMissingAccountMessageArchiveKey())
            }
        }
    }

    @Test
    fun `archive missing alias needs four unlocked observations spanning fifteen seconds`() {
        var observed = SecureMessagingRecordKeyMissState()
        listOf(1_000L, 6_000L, 11_000L).forEach { now ->
            observed = observeSecureMessagingRecordKeyMiss(observed, true, now)
            assertFalse(observed.permanentlyMissing)
        }
        observed = observeSecureMessagingRecordKeyMiss(observed, true, 16_000L)
        assertTrue(observed.permanentlyMissing)

        assertEquals(
            SecureMessagingRecordKeyMissState(),
            observeSecureMessagingRecordKeyMiss(observed, userUnlocked = false, nowEpochMillis = 17_000L),
        )
    }

    @Test
    fun `Room archive entity exposes only encrypted payload and authenticated routing metadata`() {
        val fields = AccountMessageArchiveEntity::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }
            .mapTo(mutableSetOf()) { it.name }

        assertEquals(
            setOf(
                "ownerAccountId",
                "installationId",
                "recordKey",
                "version",
                "iv",
                "ciphertext",
                "updatedAtEpochMillis",
            ),
            fields,
        )
        listOf(
            "text",
            "body",
            "plaintext",
            "mediaKey",
            "payment",
            "sender",
            "ratchet",
            "prekey",
        ).forEach { forbidden ->
            assertFalse(fields.any { it.equals(forbidden, ignoreCase = true) })
        }
    }

    private fun testEncryptArchiveEnvelope(
        aad: ByteArray,
        plaintext: ByteArray,
    ): EncryptedAccountMessageArchiveRecord = encryptAccountMessageArchiveRecordEnvelope(
        aad = aad,
        plaintext = plaintext,
        createDek = { ByteArray(32).also(TEST_RANDOM::nextBytes) },
        createDataIv = { ByteArray(12).also(TEST_RANDOM::nextBytes) },
        wrapDek = ::testWrapArchiveDek,
    )

    private fun testWrapArchiveDek(
        aad: ByteArray,
        dek: ByteArray,
    ): EncryptedAccountMessageArchiveRecord {
        val iv = ByteArray(12).also(TEST_RANDOM::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(TEST_KEK_BYTES, "AES"),
            GCMParameterSpec(128, iv),
        )
        cipher.updateAAD(aad)
        return EncryptedAccountMessageArchiveRecord(iv, cipher.doFinal(dek))
    }

    private fun testUnwrapArchiveDek(
        aad: ByteArray,
        wrapped: EncryptedAccountMessageArchiveRecord,
    ): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
        init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(TEST_KEK_BYTES, "AES"),
            GCMParameterSpec(128, wrapped.iv),
        )
        updateAAD(aad)
        doFinal(wrapped.ciphertext)
    }

    private fun testEncryptLegacyArchiveRecord(
        aad: ByteArray,
        plaintext: ByteArray,
    ): EncryptedAccountMessageArchiveRecord {
        val iv = ByteArray(12).also(TEST_RANDOM::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(TEST_KEK_BYTES, "AES"),
            GCMParameterSpec(128, iv),
        )
        cipher.updateAAD(aad)
        return EncryptedAccountMessageArchiveRecord(iv, cipher.doFinal(plaintext))
    }

    private fun testDecryptLegacyArchiveRecord(
        aad: ByteArray,
        record: EncryptedAccountMessageArchiveRecord,
    ): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
        init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(TEST_KEK_BYTES, "AES"),
            GCMParameterSpec(128, record.iv),
        )
        updateAAD(aad)
        doFinal(record.ciphertext)
    }

    private inline fun <reified T : Throwable> assertFails(block: () -> Unit) {
        val error = runCatching(block).exceptionOrNull()
        if (error !is T) {
            fail("Expected ${T::class.java.simpleName}, observed ${error?.javaClass?.simpleName}")
        }
    }

    private companion object {
        const val ACCOUNT_A = "11111111-1111-4111-8111-111111111111"
        const val ACCOUNT_B = "22222222-2222-4222-8222-222222222222"
        const val INSTALLATION_A = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val INSTALLATION_B = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        val OWNER_A = AccountMessageArchiveOwner(ACCOUNT_A, INSTALLATION_A)
        val OWNER_B = AccountMessageArchiveOwner(ACCOUNT_B, INSTALLATION_A)
        val OWNER_A_OTHER_INSTALLATION = AccountMessageArchiveOwner(ACCOUNT_A, INSTALLATION_B)
        val TEST_RANDOM = SecureRandom()
        val TEST_KEK_BYTES = ByteArray(32) { index -> (index * 13 + 5).toByte() }
    }
}
