package com.kit.wallet

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.kit.wallet.data.local.KitWalletDatabase
import com.kit.wallet.data.messaging.AndroidKeystoreMessagingRecordCipher
import com.kit.wallet.data.messaging.EncryptedMessagingRecord
import com.kit.wallet.data.messaging.RoomSecureMessagingStateStore
import com.kit.wallet.data.messaging.SecureMessagingLifecycleGuard
import com.kit.wallet.data.messaging.SecureMessagingRecordCipher
import com.kit.wallet.data.messaging.SecureMessagingSessionBinding
import com.kit.wallet.data.messaging.SecureMessagingSessionLifecycle
import com.kit.wallet.data.messaging.SecureMessagingStateEraser
import com.kit.wallet.data.session.KeystoreSessionStore
import com.kit.wallet.data.session.ProfileSetupState
import com.kit.wallet.data.session.SessionTokens
import com.kit.wallet.worker.SecureMessagingSyncScheduler
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.Lazy
import java.security.KeyStore
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * API-28 persistence evidence for the local secure-messaging reset crash fence.
 *
 * An instrumentation test cannot kill its target process without also killing its runner. These
 * tests use the closest deterministic equivalent: an [Error] bypasses the normal Exception-only
 * key/row cleanup boundary, the old scope and disk Room instance are discarded, and every
 * production object is recreated from Android Keystore, SharedPreferences and the database file.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 28, maxSdkVersion = 28)
class SecureMessagingResetCrashFenceAndroid9Test {
    @Test
    fun ten_login_logout_cycles_keep_secure_messaging_reopenable_without_sms() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "kit-repeated-session-lifecycle.db"
        cleanPersistentState(context, databaseName)

        var environment: TestEnvironment? = null
        try {
            val testEnvironment = createEnvironment(context, databaseName)
            environment = testEnvironment

            repeat(10) { cycle ->
                val session = SESSION.copy(
                    accessToken = "access-token-cycle-$cycle",
                    refreshToken = "refresh-token-cycle-$cycle",
                    sessionId = "session-api28-cycle-$cycle",
                    cacheScopeId = "cache-api28-cycle-$cycle",
                    refreshReplayNonce = "22222222-2222-4222-8222-${cycle.toString().padStart(12, '0')}",
                )
                testEnvironment.sessions.save(session)

                assertEquals(session, testEnvironment.sessions.current())
                assertTrue(testEnvironment.lifecycle.stateAvailable.value)
                assertFalse(
                    sessionPreferences(context).getBoolean(RESET_PENDING_KEY, false),
                )

                val plaintext = "encrypted state for login cycle $cycle".toByteArray()
                val expected = plaintext.copyOf()
                testEnvironment.stateStore.write(
                    namespace = NAMESPACE,
                    recordKey = REOPENED_RECORD_KEY,
                    expectedVersion = null,
                    bytes = plaintext,
                )
                val restored = checkNotNull(
                    testEnvironment.stateStore.read(NAMESPACE, REOPENED_RECORD_KEY),
                )
                try {
                    assertArrayEquals(expected, restored.bytes)
                } finally {
                    expected.fill(0)
                    restored.bytes.fill(0)
                }
                assertTrue(aliasExists(MESSAGING_KEY_ALIAS))

                testEnvironment.sessions.clear()

                assertNull(testEnvironment.sessions.current())
                assertFalse(testEnvironment.lifecycle.stateAvailable.value)
                assertNull(
                    sessionPreferences(context).getString(SESSION_CREDENTIAL_KEY, null),
                )
                assertFalse(
                    sessionPreferences(context).getBoolean(RESET_PENDING_KEY, false),
                )
                assertFalse(aliasExists(MESSAGING_KEY_ALIAS))
                assertEquals(0, testEnvironment.database.secureMessagingRecordDao().count())
                assertStateStoreClosed(testEnvironment.stateStore)
            }
        } finally {
            environment?.close()
            cleanPersistentState(context, databaseName)
        }
    }

    @Test
    fun marker_committed_before_alias_delete_recovers_without_losing_login() = runBlocking {
        exerciseCrashBoundary(CrashBoundary.MARKER_BEFORE_ALIAS_DELETE)
    }

    @Test
    fun alias_deleted_before_rows_delete_recovers_without_losing_login() = runBlocking {
        exerciseCrashBoundary(CrashBoundary.ALIAS_BEFORE_ROWS_DELETE)
    }

    @Test
    fun full_erase_before_marker_clear_recovers_without_losing_login() = runBlocking {
        exerciseCrashBoundary(CrashBoundary.FULL_ERASE_BEFORE_MARKER_CLEAR)
    }

    private suspend fun exerciseCrashBoundary(boundary: CrashBoundary) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "kit-reset-crash-${boundary.name.lowercase()}.db"
        cleanPersistentState(context, databaseName)

        var environment: TestEnvironment? = null
        try {
            val first = createEnvironment(context, databaseName)
            environment = first
            first.sessions.save(SESSION)
            assertEquals(SESSION, first.sessions.current())
            assertTrue(first.lifecycle.stateAvailable.value)

            val initialPlaintext = "state before ${boundary.name}".toByteArray()
            first.stateStore.write(
                namespace = NAMESPACE,
                recordKey = INITIAL_RECORD_KEY,
                expectedVersion = null,
                bytes = initialPlaintext,
            )
            assertEquals(1, first.database.secureMessagingRecordDao().count())
            assertTrue(aliasExists(MESSAGING_KEY_ALIAS))
            assertTrue(aliasExists(SESSION_KEY_ALIAS))

            val preferences = sessionPreferences(context)
            val encryptedCredential = checkNotNull(
                preferences.getString(SESSION_CREDENTIAL_KEY, null),
            )
            val activation = first.guard.beginSession(MESSAGING_BINDING)
            first.guard.beginCapabilityCheck(activation)
            first.guard.beginKeyPreparation(activation)
            first.crashController.arm(boundary)

            val simulatedDeath = runCatching {
                first.sessions.resetSecureMessagingStateIfCurrent(
                    expected = SESSION.fence(),
                    activationFence = activation,
                    allowPermanentlyUnavailableSnapshot = false,
                    finalMessagingSnapshot = {},
                )
            }.exceptionOrNull()

            assertTrue(simulatedDeath is SimulatedProcessDeath)
            assertEquals(boundary, (simulatedDeath as SimulatedProcessDeath).boundary)
            assertEquals(SESSION, first.sessions.current())
            assertTrue(preferences.getBoolean(RESET_PENDING_KEY, false))
            assertEquals(
                encryptedCredential,
                preferences.getString(SESSION_CREDENTIAL_KEY, null),
            )
            assertTrue(aliasExists(SESSION_KEY_ALIAS))
            assertFalse(first.lifecycle.stateAvailable.value)
            assertStateStoreClosed(first.stateStore)
            assertBoundaryPersistence(first, boundary)

            // Discard every process-local object. The queued automatic retry is deliberately never
            // run, matching death immediately after the durable boundary under test.
            first.close()
            environment = null

            val recreated = createEnvironment(context, databaseName)
            environment = recreated
            assertNull(recreated.sessions.current())
            assertTrue(recreated.sessions.restorationPending.value)
            assertTrue(recreated.sessions.restorationRetryable.value)
            assertFalse(recreated.lifecycle.stateAvailable.value)
            assertTrue(preferences.getBoolean(RESET_PENDING_KEY, false))
            assertEquals(
                encryptedCredential,
                preferences.getString(SESSION_CREDENTIAL_KEY, null),
            )
            assertTrue(aliasExists(SESSION_KEY_ALIAS))
            assertStateStoreClosed(recreated.stateStore)
            assertBoundaryPersistence(recreated, boundary)

            assertTrue(recreated.sessions.retryRestore())

            assertEquals(SESSION, recreated.sessions.current())
            assertFalse(recreated.sessions.restorationPending.value)
            assertFalse(recreated.sessions.restorationRetryable.value)
            assertTrue(recreated.lifecycle.stateAvailable.value)
            assertFalse(preferences.getBoolean(RESET_PENDING_KEY, false))
            assertEquals(
                encryptedCredential,
                preferences.getString(SESSION_CREDENTIAL_KEY, null),
            )
            assertTrue(aliasExists(SESSION_KEY_ALIAS))
            assertFalse(aliasExists(MESSAGING_KEY_ALIAS))
            assertEquals(0, recreated.database.secureMessagingRecordDao().count())

            val reopenedPlaintext = "fresh state after ${boundary.name}".toByteArray()
            val expectedReopenedPlaintext = reopenedPlaintext.copyOf()
            recreated.stateStore.write(
                namespace = NAMESPACE,
                recordKey = REOPENED_RECORD_KEY,
                expectedVersion = null,
                bytes = reopenedPlaintext,
            )
            val restored = checkNotNull(
                recreated.stateStore.read(NAMESPACE, REOPENED_RECORD_KEY),
            )
            try {
                assertArrayEquals(expectedReopenedPlaintext, restored.bytes)
            } finally {
                expectedReopenedPlaintext.fill(0)
                restored.bytes.fill(0)
            }
            assertTrue(aliasExists(MESSAGING_KEY_ALIAS))
            assertEquals(1, recreated.database.secureMessagingRecordDao().count())
        } finally {
            environment?.close()
            cleanPersistentState(context, databaseName)
        }
    }

    private suspend fun assertBoundaryPersistence(
        environment: TestEnvironment,
        boundary: CrashBoundary,
    ) {
        val expectedRows = when (boundary) {
            CrashBoundary.MARKER_BEFORE_ALIAS_DELETE,
            CrashBoundary.ALIAS_BEFORE_ROWS_DELETE -> 1

            CrashBoundary.FULL_ERASE_BEFORE_MARKER_CLEAR -> 0
        }
        val expectedAlias = boundary == CrashBoundary.MARKER_BEFORE_ALIAS_DELETE
        assertEquals(expectedRows, environment.database.secureMessagingRecordDao().count())
        assertEquals(expectedAlias, aliasExists(MESSAGING_KEY_ALIAS))
    }

    private suspend fun assertStateStoreClosed(store: RoomSecureMessagingStateStore) {
        val failure = runCatching {
            store.read(NAMESPACE, INITIAL_RECORD_KEY)
        }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(
            generateSequence(checkNotNull(failure)) { it.cause }
                .filterIsInstance<IllegalStateException>()
                .any { it.message?.contains("unavailable without an active local session") == true },
        )
    }

    private fun createEnvironment(context: Context, databaseName: String): TestEnvironment {
        val database = Room.databaseBuilder(
            context,
            KitWalletDatabase::class.java,
            databaseName,
        ).allowMainThreadQueries().build()
        val crashController = CrashController()
        val realCipher = AndroidKeystoreMessagingRecordCipher(context)
        val crashCipher = CrashInjectingRecordCipher(realCipher, crashController)
        val stateStore = RoomSecureMessagingStateStore(database, crashCipher)
        val eraser = CrashInjectingStateEraser(stateStore, crashController)
        val guard = SecureMessagingLifecycleGuard()
        val lifecycle = SecureMessagingSessionLifecycle(eraser, guard)
        val dispatcher = PausedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val sessions = KeystoreSessionStore(
            context = context,
            moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build(),
            messagingLifecycle = lifecycle,
            applicationScope = scope,
            messagingSyncScheduler = UNUSED_SCHEDULER,
        )
        return TestEnvironment(
            database = database,
            stateStore = stateStore,
            guard = guard,
            lifecycle = lifecycle,
            sessions = sessions,
            crashController = crashController,
            scope = scope,
            dispatcher = dispatcher,
        )
    }

    private fun cleanPersistentState(context: Context, databaseName: String) {
        check(sessionPreferences(context).edit().clear().commit())
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        runCatching { keyStore.deleteEntry(SESSION_KEY_ALIAS) }
        runCatching { keyStore.deleteEntry(MESSAGING_KEY_ALIAS) }
        context.deleteDatabase(databaseName)
    }

    private fun sessionPreferences(context: Context) =
        context.getSharedPreferences(SESSION_PREFERENCES, Context.MODE_PRIVATE)

    private fun aliasExists(alias: String): Boolean =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.containsAlias(alias)

    private enum class CrashBoundary {
        MARKER_BEFORE_ALIAS_DELETE,
        ALIAS_BEFORE_ROWS_DELETE,
        FULL_ERASE_BEFORE_MARKER_CLEAR,
    }

    private class CrashController {
        var boundary: CrashBoundary? = null
            private set

        fun arm(boundary: CrashBoundary) {
            check(this.boundary == null)
            this.boundary = boundary
        }
    }

    private class SimulatedProcessDeath(
        val boundary: CrashBoundary,
    ) : Error("Simulated process death at $boundary")

    private class CrashInjectingRecordCipher(
        private val delegate: AndroidKeystoreMessagingRecordCipher,
        private val controller: CrashController,
    ) : SecureMessagingRecordCipher {
        override fun encrypt(
            aad: ByteArray,
            plaintext: ByteArray,
            allowKeyCreation: Boolean,
        ): EncryptedMessagingRecord = delegate.encrypt(aad, plaintext, allowKeyCreation)

        override fun decrypt(
            aad: ByteArray,
            record: EncryptedMessagingRecord,
        ): ByteArray = delegate.decrypt(aad, record)

        override fun eraseKey() {
            when (controller.boundary) {
                CrashBoundary.MARKER_BEFORE_ALIAS_DELETE ->
                    throw SimulatedProcessDeath(CrashBoundary.MARKER_BEFORE_ALIAS_DELETE)

                CrashBoundary.ALIAS_BEFORE_ROWS_DELETE -> {
                    delegate.eraseKey()
                    throw SimulatedProcessDeath(CrashBoundary.ALIAS_BEFORE_ROWS_DELETE)
                }

                null,
                CrashBoundary.FULL_ERASE_BEFORE_MARKER_CLEAR -> delegate.eraseKey()
            }
        }
    }

    private class CrashInjectingStateEraser(
        private val delegate: RoomSecureMessagingStateStore,
        private val controller: CrashController,
    ) : SecureMessagingStateEraser {
        override suspend fun eraseAll() {
            delegate.eraseAll()
            crashAfterFullEraseIfArmed()
        }

        override suspend fun eraseAllAfterFinalSnapshot(finalSnapshot: suspend () -> Unit) {
            delegate.eraseAllAfterFinalSnapshot(finalSnapshot)
            crashAfterFullEraseIfArmed()
        }

        override suspend fun allowForActiveSession() = delegate.allowForActiveSession()

        private fun crashAfterFullEraseIfArmed() {
            if (controller.boundary == CrashBoundary.FULL_ERASE_BEFORE_MARKER_CLEAR) {
                throw SimulatedProcessDeath(CrashBoundary.FULL_ERASE_BEFORE_MARKER_CLEAR)
            }
        }
    }

    /** Queues constructor-launched recovery so the test can inspect the recreated closed state. */
    private class PausedDispatcher : CoroutineDispatcher() {
        private val queued = mutableListOf<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            synchronized(queued) { queued += block }
        }

        fun clear() {
            synchronized(queued) { queued.clear() }
        }
    }

    private class TestEnvironment(
        val database: KitWalletDatabase,
        val stateStore: RoomSecureMessagingStateStore,
        val guard: SecureMessagingLifecycleGuard,
        val lifecycle: SecureMessagingSessionLifecycle,
        val sessions: KeystoreSessionStore,
        val crashController: CrashController,
        private val scope: CoroutineScope,
        private val dispatcher: PausedDispatcher,
    ) {
        fun close() {
            scope.cancel()
            dispatcher.clear()
            database.close()
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val SESSION_PREFERENCES = "kit_wallet_secure_session"
        const val SESSION_CREDENTIAL_KEY = "session_v1"
        const val RESET_PENDING_KEY = "messaging_reset_pending_v1"
        const val SESSION_KEY_ALIAS = "kit_wallet_session_aes_v1"
        const val MESSAGING_KEY_ALIAS = "kit_pay_secure_messaging_aes_v1"
        const val NAMESPACE = "crash-fence"
        const val INITIAL_RECORD_KEY = "retained"
        const val REOPENED_RECORD_KEY = "reopened"

        val SESSION = SessionTokens(
            accessToken = "access-token-retained-across-reset",
            refreshToken = "refresh-token-retained-across-reset",
            sessionId = "session-api28-crash-fence",
            accountId = "11111111-1111-4111-8111-111111111111",
            cacheScopeId = "cache-api28-crash-fence",
            profileSetupState = ProfileSetupState.COMPLETED,
            refreshReplayNonce = "22222222-2222-4222-8222-222222222222",
        )
        val MESSAGING_BINDING = SecureMessagingSessionBinding(
            sessionEpoch = SESSION.sessionId,
            userId = checkNotNull(SESSION.accountId),
            serverDeviceId = "server-device-api28",
            installationId = "installation-api28",
        )
        val UNUSED_SCHEDULER = object : Lazy<SecureMessagingSyncScheduler> {
            override fun get(): SecureMessagingSyncScheduler =
                error("The sync scheduler is outside this persistence test")
        }
    }
}
