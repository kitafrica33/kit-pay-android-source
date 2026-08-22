package com.kit.wallet.data.auth

import java.security.Signature
import android.security.keystore.KeyPermanentlyInvalidatedException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class BiometricApprovalRequest(
    val id: String,
    val signature: Signature,
    val reason: String,
)

interface BiometricPaymentApprover {
    fun availableFor(accountId: String): Boolean
    suspend fun sign(accountId: String, payload: String, reason: String): String
}

@Singleton
class BiometricApprovalCoordinator @Inject constructor(
    private val keys: BiometricSigningKey,
) : BiometricPaymentApprover {
    private data class Active(
        val request: BiometricApprovalRequest,
        val payload: String,
        val result: CompletableDeferred<String>,
    )

    private val mutex = Mutex()
    private val mutableRequest = MutableStateFlow<BiometricApprovalRequest?>(null)
    val request = mutableRequest.asStateFlow()
    private var active: Active? = null

    override fun availableFor(accountId: String): Boolean {
        if (!keys.hasKey(accountId)) return false
        return try {
            // Initializing the cipher is where Android reports a biometric-set change for a key
            // configured with invalidation. Remove that unusable enrollment so PIN can be used.
            keys.signature(accountId)
            true
        } catch (_: KeyPermanentlyInvalidatedException) {
            keys.remove(accountId)
            false
        }
    }

    override suspend fun sign(accountId: String, payload: String, reason: String): String = mutex.withLock {
        val pending = Active(
            BiometricApprovalRequest(UUID.randomUUID().toString(), keys.signature(accountId), reason),
            payload,
            CompletableDeferred(),
        )
        active = pending
        mutableRequest.value = pending.request
        try {
            pending.result.await()
        } finally {
            if (active === pending) {
                active = null
                mutableRequest.value = null
            }
        }
    }

    fun approve(id: String, authenticatedSignature: Signature) {
        val pending = active?.takeIf { it.request.id == id } ?: return
        runCatching { keys.sign(authenticatedSignature, pending.payload) }
            .onSuccess(pending.result::complete)
            .onFailure(pending.result::completeExceptionally)
    }

    fun cancel(id: String, message: String) {
        val pending = active?.takeIf { it.request.id == id } ?: return
        pending.result.completeExceptionally(CancellationException(message))
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BiometricApprovalModule {
    @Binds abstract fun bindBiometricPaymentApprover(
        coordinator: BiometricApprovalCoordinator,
    ): BiometricPaymentApprover

    @Binds abstract fun bindBiometricKeyLifecycle(
        key: BiometricSigningKey,
    ): BiometricKeyLifecycle
}
