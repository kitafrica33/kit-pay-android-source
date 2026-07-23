package com.kit.wallet.data.messaging

import com.kit.wallet.data.remote.SecureMessagingTransportValidator
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Opaque, validated resume position for the authenticated messaging event stream.
 *
 * The server cursor is encrypted at rest by [SecureMessagingStateStore]. Keeping construction
 * private prevents an arbitrary UI/network value from becoming restart authority without passing
 * the strict transport validator or the authenticated on-disk decoder.
 */
internal sealed interface SecureMessagingSyncResumePosition

private data class VerifiedSecureMessagingSyncResumePosition(
    val cursor: String,
    val previousEventId: Long?,
) : SecureMessagingSyncResumePosition

internal data class LoadedSecureMessagingSyncResumePosition(
    val position: SecureMessagingSyncResumePosition,
    val recordVersion: Long,
)

internal fun verifiedSecureMessagingSyncResumePosition(
    cursor: String,
    previousEventId: Long?,
): SecureMessagingSyncResumePosition {
    SecureMessagingTransportValidator.validateSyncRequest(
        cursor = cursor,
        limit = 1,
        previousEventId = previousEventId,
    )
    return VerifiedSecureMessagingSyncResumePosition(cursor, previousEventId)
}

internal fun requireSecureMessagingSyncResumePosition(
    position: SecureMessagingSyncResumePosition,
): Pair<String, Long?> {
    check(position is VerifiedSecureMessagingSyncResumePosition) {
        "Secure-messaging sync position was not issued by the durable cursor boundary"
    }
    SecureMessagingTransportValidator.validateSyncRequest(
        cursor = position.cursor,
        limit = 1,
        previousEventId = position.previousEventId,
    )
    return position.cursor to position.previousEventId
}

/** Encrypts and version-checks the cursor that is safe to resume after a process restart. */
@Singleton
internal class SecureMessagingSyncCursorStore @Inject constructor(
    private val stateStore: SecureMessagingStateStore,
) {
    suspend fun <T> withActivationLease(
        activation: SecureMessagingActivationCapability,
        operation: suspend SecureMessagingSyncCursorStore.() -> T,
    ): T = stateStore.withActivationLease(activation) {
        operation(this)
    }

    suspend fun load(): LoadedSecureMessagingSyncResumePosition? {
        val record = try {
            stateStore.read(NAMESPACE, RECORD_KEY)
        } catch (error: SecureMessagingStateUnavailableException) {
            throw SecureMessagingCryptographicFailureException(
                SecureMessagingQuarantineReason.STATE_UNAVAILABLE,
                "Secure-messaging sync state is unavailable",
                error,
            )
        } ?: return null
        return try {
            try {
                LoadedSecureMessagingSyncResumePosition(
                    position = SecureMessagingSyncCursorCodec.decode(record.bytes),
                    recordVersion = record.version,
                )
            } catch (error: SecureMessagingCryptographicFailureException) {
                throw error
            } catch (error: Exception) {
                throw SecureMessagingCryptographicFailureException(
                    SecureMessagingQuarantineReason.REPLAY_OR_ROLLBACK,
                    "Secure-messaging sync state is corrupt",
                    error,
                )
            }
        } finally {
            record.bytes.fill(0)
        }
    }

    suspend fun save(
        position: SecureMessagingSyncResumePosition,
        expectedVersion: Long?,
    ): Long {
        val encoded = SecureMessagingSyncCursorCodec.encode(position)
        return try {
            try {
                stateStore.write(
                    namespace = NAMESPACE,
                    recordKey = RECORD_KEY,
                    expectedVersion = expectedVersion,
                    bytes = encoded,
                ).version
            } catch (error: SecureMessagingStateUnavailableException) {
                throw SecureMessagingCryptographicFailureException(
                    SecureMessagingQuarantineReason.STATE_UNAVAILABLE,
                    "Secure-messaging sync state could not be committed",
                    error,
                )
            }
        } finally {
            encoded.fill(0)
        }
    }

    private companion object {
        const val NAMESPACE = "messaging-sync"
        const val RECORD_KEY = "cursor-v1"
    }
}

private object SecureMessagingSyncCursorCodec {
    fun encode(position: SecureMessagingSyncResumePosition): ByteArray {
        val (cursor, previousEventId) = requireSecureMessagingSyncResumePosition(position)
        val cursorBytes = cursor.toByteArray(Charsets.UTF_8)
        require(cursorBytes.size in 1..MAX_CURSOR_BYTES) { "Invalid persisted messaging cursor" }
        val output = WipingCursorOutputStream(MAX_RECORD_BYTES)
        val data = DataOutputStream(output)
        try {
            data.write(MAGIC)
            data.writeInt(SCHEMA_VERSION)
            data.writeInt(cursorBytes.size)
            data.write(cursorBytes)
            data.writeBoolean(previousEventId != null)
            previousEventId?.let(data::writeLong)
            data.flush()
            return output.toOwnedByteArray().also {
                require(it.size <= MAX_RECORD_BYTES) { "Persisted messaging cursor is too large" }
            }
        } finally {
            cursorBytes.fill(0)
            output.close()
        }
    }

    fun decode(bytes: ByteArray): SecureMessagingSyncResumePosition {
        require(bytes.size in MIN_RECORD_BYTES..MAX_RECORD_BYTES) {
            "Invalid persisted messaging cursor size"
        }
        val owned = bytes.copyOf()
        val input = ByteArrayInputStream(owned)
        try {
            return DataInputStream(input).use { data ->
                val magic = ByteArray(MAGIC.size).also(data::readFully)
                require(magic.contentEquals(MAGIC)) { "Invalid persisted messaging cursor header" }
                require(data.readInt() == SCHEMA_VERSION) {
                    "Unsupported persisted messaging cursor schema"
                }
                val cursorSize = data.readInt()
                require(cursorSize in 1..MAX_CURSOR_BYTES) { "Invalid persisted messaging cursor" }
                val cursorBytes = ByteArray(cursorSize).also(data::readFully)
                val cursor = try {
                    Charsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(cursorBytes))
                        .toString()
                } finally {
                    cursorBytes.fill(0)
                }
                val hasEventId = when (val flag = data.readUnsignedByte()) {
                    0 -> false
                    1 -> true
                    else -> throw IllegalArgumentException(
                        "Invalid persisted messaging cursor event marker: $flag",
                    )
                }
                val previousEventId = if (hasEventId) data.readLong() else null
                require(input.available() == 0) {
                    "Persisted messaging cursor contains trailing bytes"
                }
                verifiedSecureMessagingSyncResumePosition(cursor, previousEventId)
            }
        } finally {
            owned.fill(0)
        }
    }

    private val MAGIC = byteArrayOf(0x4b, 0x49, 0x54, 0x53, 0x59, 0x4e, 0x43)
    private const val SCHEMA_VERSION = 1
    private const val MAX_CURSOR_BYTES = 2_048
    private const val MIN_RECORD_BYTES = 7 + Int.SIZE_BYTES + Int.SIZE_BYTES + 1 + 1
    private const val MAX_RECORD_BYTES =
        7 + Int.SIZE_BYTES + Int.SIZE_BYTES + MAX_CURSOR_BYTES + 1 + Long.SIZE_BYTES
}

private class WipingCursorOutputStream(
    private val maximumBytes: Int,
) : ByteArrayOutputStream() {
    override fun write(value: Int) {
        require(count < maximumBytes) { "Persisted messaging cursor is too large" }
        super.write(value)
    }

    override fun write(value: ByteArray, offset: Int, length: Int) {
        require(length >= 0 && count.toLong() + length <= maximumBytes) {
            "Persisted messaging cursor is too large"
        }
        super.write(value, offset, length)
    }

    fun toOwnedByteArray(): ByteArray = buf.copyOf(count)

    override fun close() {
        buf.fill(0)
        count = 0
        super.close()
    }
}
