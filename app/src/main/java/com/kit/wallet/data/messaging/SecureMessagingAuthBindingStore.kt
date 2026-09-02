package com.kit.wallet.data.messaging

import com.kit.wallet.data.session.SessionFence
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import javax.inject.Inject
import javax.inject.Singleton

/** The source is retained only so a successful activation can finish an offline migration. */
internal data class PersistedSecureMessagingAuthBinding(
    val binding: SecureMessagingSessionBinding,
    val requiresMigration: Boolean,
)

internal interface SecureMessagingAuthBindingPersistence {
    /**
     * Reads metadata only while the caller holds the exact authenticated-session owner. An
     * invalid record is deliberately equivalent to a miss, so live authentication remains the
     * only fallback.
     */
    suspend fun read(
        expectedOwner: SessionFence,
        expectedInstallationId: String,
    ): PersistedSecureMessagingAuthBinding?

    /** Writes metadata only after the caller has completed a live secure-messaging activation. */
    suspend fun persist(
        expectedOwner: SessionFence,
        binding: SecureMessagingSessionBinding,
    )
}

internal object NoOpSecureMessagingAuthBindingPersistence :
    SecureMessagingAuthBindingPersistence {
    override suspend fun read(
        expectedOwner: SessionFence,
        expectedInstallationId: String,
    ): PersistedSecureMessagingAuthBinding? = null

    override suspend fun persist(
        expectedOwner: SessionFence,
        binding: SecureMessagingSessionBinding,
    ) = Unit
}

/**
 * Encrypted, descriptive bootstrap metadata for opening local messaging state in a fresh process.
 *
 * This never stores an activation capability. The lifecycle guard still issues a new process-local
 * capability, and the remote transport still performs its uncached profile/device checks before it
 * can publish an exchange-ready session.
 */
@Singleton
internal class SecureMessagingAuthBindingStore @Inject constructor(
    private val stateStore: SecureMessagingStateStore,
) : SecureMessagingAuthBindingPersistence {
    override suspend fun read(
        expectedOwner: SessionFence,
        expectedInstallationId: String,
    ): PersistedSecureMessagingAuthBinding? = stateStore.withStateLease {
        val exact = stateStore.read(BINDING_NAMESPACE, BINDING_RECORD_KEY)
        if (exact != null) {
            return@withStateLease try {
                AuthBindingCodec.decode(exact.bytes)
                    ?.takeIf { stored ->
                        stored.owner == expectedOwner &&
                            reusableFor(
                                owner = expectedOwner,
                                binding = stored.binding,
                                installationId = expectedInstallationId,
                            )
                    }
                    ?.let { stored ->
                        PersistedSecureMessagingAuthBinding(
                            binding = stored.binding,
                            requiresMigration = false,
                        )
                    }
            } finally {
                exact.bytes.fill(0)
            }
        }

        // Code-56 and earlier already authenticated this header as part of the encrypted
        // libsignal state. It lacks cacheScopeId, so it is accepted only when no newer exact-owner
        // record exists and its epoch, optional account, and installation all match the caller.
        val legacy = stateStore.read(LEGACY_PROTOCOL_NAMESPACE, LEGACY_PROTOCOL_RECORD_KEY)
            ?: return@withStateLease null
        try {
            LegacyProtocolBindingCodec.decode(legacy.bytes)
                ?.takeIf { binding ->
                    reusableFor(expectedOwner, binding, expectedInstallationId)
                }
                ?.let { binding ->
                    PersistedSecureMessagingAuthBinding(
                        binding = binding,
                        requiresMigration = true,
                    )
                }
        } finally {
            legacy.bytes.fill(0)
        }
    }

    override suspend fun persist(
        expectedOwner: SessionFence,
        binding: SecureMessagingSessionBinding,
    ) = stateStore.withStateLease {
        require(reusableFor(expectedOwner, binding, binding.installationId)) {
            "Secure-messaging binding does not belong to its authenticated owner"
        }
        val encoded = AuthBindingCodec.encode(StoredAuthBinding(expectedOwner, binding))
        try {
            repeat(MAX_WRITE_ATTEMPTS) { attempt ->
                val current = stateStore.read(BINDING_NAMESPACE, BINDING_RECORD_KEY)
                try {
                    val alreadyCurrent = current?.let { record ->
                        AuthBindingCodec.decode(record.bytes) ==
                            StoredAuthBinding(expectedOwner, binding)
                    } == true
                    if (alreadyCurrent) return@withStateLease
                    try {
                        stateStore.write(
                            namespace = BINDING_NAMESPACE,
                            recordKey = BINDING_RECORD_KEY,
                            expectedVersion = current?.version,
                            bytes = encoded,
                        )
                        return@withStateLease
                    } catch (conflict: SecureMessagingStateConflictException) {
                        if (attempt == MAX_WRITE_ATTEMPTS - 1) throw conflict
                    }
                } finally {
                    current?.bytes?.fill(0)
                }
            }
            error("Secure-messaging binding persistence exhausted its retry bound")
        } finally {
            encoded.fill(0)
        }
    }

    private fun reusableFor(
        owner: SessionFence,
        binding: SecureMessagingSessionBinding,
        installationId: String,
    ): Boolean = binding.sessionEpoch == owner.sessionId &&
        binding.installationId == installationId &&
        (owner.accountId == null || owner.accountId == binding.userId) &&
        UUID_PATTERN.matches(binding.userId) &&
        UUID_PATTERN.matches(binding.serverDeviceId)

    private companion object {
        const val BINDING_NAMESPACE = "secure-messaging-auth-binding-v1"
        const val BINDING_RECORD_KEY = "active-owner"
        const val LEGACY_PROTOCOL_NAMESPACE = "libsignal-v2"
        const val LEGACY_PROTOCOL_RECORD_KEY = "active-protocol-state"
        const val MAX_WRITE_ATTEMPTS = 2
        val UUID_PATTERN = Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
        )
    }
}

private data class StoredAuthBinding(
    val owner: SessionFence,
    val binding: SecureMessagingSessionBinding,
)

private object AuthBindingCodec {
    fun encode(value: StoredAuthBinding): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.write(MAGIC)
            data.writeInt(SCHEMA)
            data.writeString(value.owner.sessionId)
            data.writeString(value.owner.cacheScopeId)
            data.writeBoolean(value.owner.accountId != null)
            value.owner.accountId?.let(data::writeString)
            data.writeString(value.binding.sessionEpoch)
            data.writeString(value.binding.userId)
            data.writeString(value.binding.serverDeviceId)
            data.writeString(value.binding.installationId)
        }
        return output.toByteArray().also { encoded ->
            require(encoded.size <= MAX_RECORD_BYTES) {
                "Secure-messaging auth binding is too large"
            }
        }
    }

    fun decode(bytes: ByteArray): StoredAuthBinding? = decodeOrNull {
        require(bytes.size in MIN_RECORD_BYTES..MAX_RECORD_BYTES) {
            "Invalid secure-messaging auth binding size"
        }
        val input = ByteArrayInputStream(bytes)
        DataInputStream(input).use { data ->
            require(data.readExact(MAGIC.size).contentEquals(MAGIC)) {
                "Invalid secure-messaging auth binding header"
            }
            require(data.readInt() == SCHEMA) {
                "Unsupported secure-messaging auth binding schema"
            }
            val owner = SessionFence(
                sessionId = data.readString(),
                cacheScopeId = data.readString(),
                accountId = if (data.readStrictBoolean()) data.readString() else null,
            )
            val binding = SecureMessagingSessionBinding(
                sessionEpoch = data.readString(),
                userId = data.readString(),
                serverDeviceId = data.readString(),
                installationId = data.readString(),
            )
            require(input.available() == 0) {
                "Secure-messaging auth binding contains trailing bytes"
            }
            StoredAuthBinding(owner, binding)
        }
    }

    private val MAGIC = byteArrayOf(0x4b, 0x49, 0x54, 0x53, 0x4d, 0x42, 0x31)
    private const val SCHEMA = 1
    private const val MIN_RECORD_BYTES = 32
    private const val MAX_RECORD_BYTES = 8 * 1024
}

/** Reads only the authenticated owner prefix of the existing BoundLibSignalStateCodec format. */
private object LegacyProtocolBindingCodec {
    fun decode(bytes: ByteArray): SecureMessagingSessionBinding? = decodeOrNull {
        val input = ByteArrayInputStream(bytes)
        DataInputStream(input).use { data ->
            require(data.readExact(MAGIC.size).contentEquals(MAGIC)) {
                "Invalid legacy secure-messaging binding header"
            }
            require(data.readInt() in MIN_SCHEMA..MAX_SCHEMA) {
                "Unsupported legacy secure-messaging binding schema"
            }
            SecureMessagingSessionBinding(
                sessionEpoch = data.readString(),
                userId = data.readString(),
                serverDeviceId = data.readString(),
                installationId = data.readString(),
            )
        }
    }

    // BoundLibSignalStateCodec constants, duplicated here to keep the migration decoder unable to
    // construct or mutate executable protocol state.
    private val MAGIC = byteArrayOf(0x4b, 0x49, 0x54, 0x4c, 0x53, 0x42, 0x32)
    private const val MIN_SCHEMA = 1
    private const val MAX_SCHEMA = 2
}

private inline fun <T> decodeOrNull(block: () -> T): T? = try {
    block()
} catch (_: Exception) {
    null
}

private fun DataOutputStream.writeString(value: String) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    try {
        require(bytes.isNotEmpty() && bytes.size <= MAX_BINDING_STRING_BYTES) {
            "Invalid secure-messaging auth binding field"
        }
        writeInt(bytes.size)
        write(bytes)
    } finally {
        bytes.fill(0)
    }
}

private fun DataInputStream.readString(): String {
    val size = readInt()
    require(size in 1..MAX_BINDING_STRING_BYTES) {
        "Invalid secure-messaging auth binding field size"
    }
    val bytes = readExact(size)
    return try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } finally {
        bytes.fill(0)
    }
}

private fun DataInputStream.readExact(size: Int): ByteArray = ByteArray(size).also(::readFully)

private fun DataInputStream.readStrictBoolean(): Boolean = when (readUnsignedByte()) {
    0 -> false
    1 -> true
    else -> throw IllegalArgumentException("Invalid secure-messaging auth binding boolean")
}

private const val MAX_BINDING_STRING_BYTES = 512
