package com.kit.wallet.data.messaging

import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.Buffer

/**
 * Protocol-library boundary for secure messaging.
 *
 * Transport, repositories and UI must never call a concrete cryptographic library directly. A
 * future reviewed adapter owns the library and returns ciphertext or authenticated plaintext only
 * after the matching ratchet, replay and durable-message state has committed atomically.
 */
interface SecureMessagingCryptoEngine {
    suspend fun openTransaction(
        activation: SecureMessagingActivationCapability,
    ): SecureMessagingCryptoTransaction

    /** Erases all private key, session, replay and staged-operation material for this installation. */
    suspend fun eraseAll()

    /**
     * Atomically removes every pinned identity and ratchet matching an authenticated lifecycle
     * event. A null device retires all remote devices for the affected user.
     */
    suspend fun retireRemoteDevices(
        activation: SecureMessagingActivationCapability,
        affectedUserId: String,
        affectedServerDeviceId: String?,
    )
}

/** A local authentication epoch. Tokens and other credentials must never be placed in this type. */
data class SecureMessagingSessionBinding(
    val sessionEpoch: String,
    val userId: String,
    val serverDeviceId: String,
    val installationId: String,
) {
    init {
        requireBoundedIdentifier(sessionEpoch, "session epoch")
        requireBoundedIdentifier(userId, "user ID")
        requireBoundedIdentifier(serverDeviceId, "server device ID")
        requireBoundedIdentifier(installationId, "installation ID")
    }
}

/**
 * Opaque proof for one concrete login activation. A binding is descriptive data; this capability
 * is the authority to use that binding. Only [SecureMessagingLifecycleGuard] can issue a value and
 * a later login with byte-for-byte identical binding fields receives a different capability.
 */
sealed interface SecureMessagingActivationCapability {
    val binding: SecureMessagingSessionBinding
}

private class IssuedSecureMessagingActivationCapability(
    override val binding: SecureMessagingSessionBinding,
    private val validator: (Boolean) -> Unit,
    private val quarantiner: (SecureMessagingQuarantineReason) -> Unit,
) : SecureMessagingActivationCapability {
    fun assertCurrent(readyRequired: Boolean) = validator(readyRequired)

    fun quarantine(reason: SecureMessagingQuarantineReason) = quarantiner(reason)
}

/** Identity-based provenance retained by plans, crypto transactions and durable result handles. */
internal class SecureMessagingActivationProvenance private constructor(
    private val identity: Any,
    val binding: SecureMessagingSessionBinding,
    private val validator: (Boolean) -> Unit,
    private val quarantiner: (SecureMessagingQuarantineReason) -> Unit,
) {
    fun assertCurrent(readyRequired: Boolean = false) = validator(readyRequired)

    fun quarantine(reason: SecureMessagingQuarantineReason) = quarantiner(reason)

    fun isSameActivation(other: SecureMessagingActivationProvenance): Boolean =
        identity === other.identity

    companion object {
        fun requireCurrent(
            capability: SecureMessagingActivationCapability,
            readyRequired: Boolean = false,
        ): SecureMessagingActivationProvenance {
            check(capability is IssuedSecureMessagingActivationCapability) {
                "Secure messaging activation capability was not issued by the lifecycle guard"
            }
            capability.assertCurrent(readyRequired)
            return SecureMessagingActivationProvenance(
                identity = capability,
                binding = capability.binding,
                validator = capability::assertCurrent,
                quarantiner = capability::quarantine,
            )
        }
    }
}

/** Copy-on-read secret container with explicit, idempotent zeroization. */
class SensitiveCryptoBytes private constructor(bytes: ByteArray) : AutoCloseable {
    private val lock = Any()
    private var value: ByteArray? = bytes.copyOf()

    val size: Int
        get() = synchronized(lock) {
            checkNotNull(value) { "Sensitive cryptographic bytes are closed" }.size
        }

    val isClosed: Boolean
        get() = synchronized(lock) { value == null }

    fun copyBytes(): ByteArray = synchronized(lock) {
        checkNotNull(value) { "Sensitive cryptographic bytes are closed" }.copyOf()
    }

    override fun close() {
        synchronized(lock) {
            value?.fill(0)
            value = null
        }
    }

    companion object {
        fun copyOf(bytes: ByteArray): SensitiveCryptoBytes {
            require(bytes.isNotEmpty()) { "Sensitive cryptographic bytes must not be empty" }
            return SensitiveCryptoBytes(bytes)
        }
    }
}

/** Immutable copy-on-read public-key or ciphertext bytes. */
class OpaqueCryptoBytes private constructor(bytes: ByteArray) {
    private val value = bytes.copyOf()

    val size: Int get() = value.size

    fun copyBytes(): ByteArray = value.copyOf()

    companion object {
        fun copyOf(bytes: ByteArray): OpaqueCryptoBytes {
            require(bytes.isNotEmpty()) { "Opaque cryptographic bytes must not be empty" }
            return OpaqueCryptoBytes(bytes)
        }
    }
}

data class SecureMessagingCryptoAddress(
    val userId: String,
    val serverDeviceId: String,
    val signalDeviceId: Int,
) {
    init {
        requireCanonicalUuid(userId, "crypto-address user ID")
        requireCanonicalUuid(serverDeviceId, "crypto-address server device ID")
        require(signalDeviceId in 1..127) { "Signal device ID is outside the v2 contract" }
    }
}

/** The complete, authoritative recipient set frozen for one outbound message. */
class SecureMessagingExactRecipientSet(addresses: Collection<SecureMessagingCryptoAddress>) {
    private val value = addresses.toList()

    init {
        require(value.size in 1..MAX_RECIPIENT_DEVICES) {
            "Secure messaging requires 1 to $MAX_RECIPIENT_DEVICES recipient devices"
        }
        require(value.map(SecureMessagingCryptoAddress::serverDeviceId).distinct().size == value.size) {
            "Secure messaging recipient devices must be unique"
        }
        require(value.map { it.userId to it.signalDeviceId }.distinct().size == value.size) {
            "Secure messaging Signal addresses must be unique per user"
        }
    }

    fun addresses(): List<SecureMessagingCryptoAddress> = value.toList()

    internal fun addressSet(): Set<SecureMessagingCryptoAddress> = value.toSet()
}

/** Session-less addresses selected from one exact, transaction-locked recipient snapshot. */
class SecureMessagingMissingSessionSet internal constructor(
    candidates: SecureMessagingExactRecipientSet,
    missing: Collection<SecureMessagingCryptoAddress>,
) {
    private val value = missing.toList()

    init {
        require(value.toSet().size == value.size) { "Missing-session addresses must be unique" }
        require(candidates.addressSet().containsAll(value)) {
            "Missing-session lookup returned an address outside the requested recipient set"
        }
    }

    val isEmpty: Boolean get() = value.isEmpty()

    fun addresses(): List<SecureMessagingCryptoAddress> = value.toList()
}

/**
 * Opaque instruction identifying where the trusted crypto adapter must atomically persist the
 * encrypted outbox or inbox projection. It deliberately carries no caller callback: application
 * code cannot inspect prepared ciphertext or authenticated plaintext before durable commit.
 */
sealed interface SecureMessagingCompanionStateIntent {
    companion object {
        fun outbound(
            namespace: String,
            recordKey: String,
            expectedVersion: Long? = null,
        ): SecureMessagingCompanionStateIntent = companionStateIntent(
            SecureMessagingCompanionStateDirection.OUTBOUND,
            namespace,
            recordKey,
            expectedVersion,
        )

        fun inbound(
            namespace: String,
            recordKey: String,
            expectedVersion: Long? = null,
        ): SecureMessagingCompanionStateIntent = companionStateIntent(
            SecureMessagingCompanionStateDirection.INBOUND,
            namespace,
            recordKey,
            expectedVersion,
        )
    }
}

private enum class SecureMessagingCompanionStateDirection {
    OUTBOUND,
    INBOUND,
}

private class IssuedSecureMessagingCompanionStateIntent(
    val direction: SecureMessagingCompanionStateDirection,
    val namespace: String,
    val recordKey: String,
    val expectedVersion: Long?,
) : SecureMessagingCompanionStateIntent

private fun companionStateIntent(
    direction: SecureMessagingCompanionStateDirection,
    namespace: String,
    recordKey: String,
    expectedVersion: Long?,
): SecureMessagingCompanionStateIntent {
    requireStateAddress(namespace, "companion-state namespace")
    requireStateAddress(recordKey, "companion-state record key")
    require(expectedVersion == null || expectedVersion > 0) {
        "Companion-state expected version must be positive"
    }
    return IssuedSecureMessagingCompanionStateIntent(
        direction,
        namespace,
        recordKey,
        expectedVersion,
    )
}

data class SecureMessagingProvisioningPlan(
    val ecOneTimePrekeyCount: Int,
    val pqOneTimePrekeyCount: Int,
    val identityKeyChange: Boolean = false,
) {
    init {
        require(ecOneTimePrekeyCount in 1..1_000) { "Invalid EC one-time prekey count" }
        require(pqOneTimePrekeyCount in 1..1_000) { "Invalid PQ one-time prekey count" }
    }
}

data class SecureMessagingPublicPrekey(
    val id: Int,
    val publicKey: OpaqueCryptoBytes,
    val signature: OpaqueCryptoBytes? = null,
) {
    init {
        require(id in 0..16_777_215) { "Prekey ID is outside the v2 contract" }
    }
}

class SecureMessagingLocalPublicBundle(
    val registrationId: Int,
    val identityKey: OpaqueCryptoBytes,
    val signedPrekey: SecureMessagingPublicPrekey,
    oneTimePrekeys: List<SecureMessagingPublicPrekey>,
    pqPrekeys: List<SecureMessagingPublicPrekey>,
    val pqLastResortPrekey: SecureMessagingPublicPrekey,
) {
    private val immutableOneTimePrekeys = oneTimePrekeys.toList()
    private val immutablePqPrekeys = pqPrekeys.toList()

    val oneTimePrekeys: List<SecureMessagingPublicPrekey>
        get() = immutableOneTimePrekeys.toList()

    val pqPrekeys: List<SecureMessagingPublicPrekey>
        get() = immutablePqPrekeys.toList()

    init {
        require(registrationId in 1..16_380) { "Registration ID is outside the v2 contract" }
        require(signedPrekey.signature != null) { "A signed prekey requires a signature" }
        require(immutablePqPrekeys.all { it.signature != null }) {
            "Every PQ prekey requires a signature"
        }
        require(pqLastResortPrekey.signature != null) { "A PQ last-resort prekey requires a signature" }
        require(
            immutableOneTimePrekeys.map(SecureMessagingPublicPrekey::id).distinct().size ==
                immutableOneTimePrekeys.size,
        ) { "An EC one-time prekey bundle cannot reuse an ID" }
        val pqIds = buildList {
            addAll(immutablePqPrekeys.map(SecureMessagingPublicPrekey::id))
            add(pqLastResortPrekey.id)
        }
        require(pqIds.distinct().size == pqIds.size) { "A PQ prekey bundle cannot reuse an ID" }
    }
}

/**
 * A structurally validated remote bundle. The crypto adapter must still verify both signatures and
 * the locally pinned identity before it establishes or replaces a session.
 */
data class SecureMessagingRemoteKeyBundle(
    val address: SecureMessagingCryptoAddress,
    val registrationId: Int,
    val identityKey: OpaqueCryptoBytes,
    val signedPrekey: SecureMessagingPublicPrekey,
    val oneTimePrekey: SecureMessagingPublicPrekey?,
    val pqPrekey: SecureMessagingPublicPrekey,
) {
    init {
        require(registrationId in 1..16_380) { "Registration ID is outside the v2 contract" }
        require(signedPrekey.signature != null) { "A remote signed prekey requires a signature" }
        require(pqPrekey.signature != null) { "A remote PQ prekey requires a signature" }
    }
}

/** Non-secret metadata authenticated inside every v2 text-content object. */
internal data class SecureMessagingTextContentBinding(
    val clientMessageId: String,
    val conversationId: String,
    val rosterRevision: String,
    val sender: SecureMessagingCryptoAddress,
    val replyToMessageId: String?,
)

private data class DecodedSecureMessagingTextContent(
    val binding: SecureMessagingTextContentBinding,
    val text: String,
)

/**
 * Strict codec for the application plaintext described by `kit.messaging.content.v1`.
 *
 * The server-visible routing fields are authenticated a second time inside this object. Parsing is
 * deliberately closed-world: malformed UTF-8, duplicate/unknown members, non-integer numbers and
 * metadata substitutions are rejected before any text can reach a projection or UI.
 */
private object SecureMessagingTextContentCodec {
    fun encode(
        binding: SecureMessagingTextContentBinding,
        text: String,
        maxTextScalars: Int = MAX_STANDARD_TEXT_SCALARS,
    ): ByteArray {
        validateBinding(binding)
        validateText(text, maxTextScalars)
        val buffer = Buffer()
        val writer = JsonWriter.of(buffer).apply { serializeNulls = true }
        writer.beginObject()
        writer.name("schema").value(CONTENT_SCHEMA)
        writer.name("type").value(CONTENT_TYPE)
        writer.name("client_message_id").value(binding.clientMessageId)
        writer.name("conversation_id").value(binding.conversationId)
        writer.name("roster_revision").value(binding.rosterRevision)
        writer.name("sender_user_id").value(binding.sender.userId)
        writer.name("sender_device_id").value(binding.sender.serverDeviceId)
        writer.name("sender_signal_device_id").value(binding.sender.signalDeviceId.toLong())
        writer.name("reply_to_message_id")
        if (binding.replyToMessageId == null) {
            writer.nullValue()
        } else {
            writer.value(binding.replyToMessageId)
        }
        writer.name("text").value(text)
        writer.endObject()
        writer.close()
        return buffer.readByteArray().also {
            require(it.size <= MAX_PLAINTEXT_BYTES) { "Secure message plaintext is too large" }
        }
    }

    fun decode(
        bytes: ByteArray,
        maxTextScalars: Int = MAX_STANDARD_TEXT_SCALARS,
    ): DecodedSecureMessagingTextContent {
        require(bytes.isNotEmpty()) { "Secure message plaintext must not be empty" }
        require(bytes.size <= MAX_PLAINTEXT_BYTES) { "Secure message plaintext is too large" }
        val json = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (error: Exception) {
            throw IllegalArgumentException("Secure message content is not valid UTF-8", error)
        }

        return try {
            decodeJson(json, maxTextScalars)
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (error: Exception) {
            throw IllegalArgumentException("Secure message content is not valid v2 JSON", error)
        }
    }

    fun validateBinding(binding: SecureMessagingTextContentBinding) {
        requireCanonicalUuid(binding.clientMessageId, "content client message ID")
        requireCanonicalUuid(binding.conversationId, "content conversation ID")
        requireRosterRevision(binding.rosterRevision, "content roster revision")
        binding.replyToMessageId?.let { requireCanonicalUuid(it, "content reply target") }
    }

    private fun decodeJson(
        json: String,
        maxTextScalars: Int,
    ): DecodedSecureMessagingTextContent {
        val reader = JsonReader.of(Buffer().writeUtf8(json)).apply { isLenient = false }
        require(reader.peek() == JsonReader.Token.BEGIN_OBJECT) {
            "Secure message content must be a JSON object"
        }
        val seen = mutableSetOf<String>()
        var schema: String? = null
        var type: String? = null
        var clientMessageId: String? = null
        var conversationId: String? = null
        var rosterRevision: String? = null
        var senderUserId: String? = null
        var senderDeviceId: String? = null
        var senderSignalDeviceId: Int? = null
        var replyToMessageId: String? = null
        var text: String? = null

        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            require(seen.add(name)) { "Secure message content contains a duplicate member" }
            when (name) {
                "schema" -> schema = reader.requireString(name)
                "type" -> type = reader.requireString(name)
                "client_message_id" -> clientMessageId = reader.requireString(name)
                "conversation_id" -> conversationId = reader.requireString(name)
                "roster_revision" -> rosterRevision = reader.requireString(name)
                "sender_user_id" -> senderUserId = reader.requireString(name)
                "sender_device_id" -> senderDeviceId = reader.requireString(name)
                "sender_signal_device_id" -> senderSignalDeviceId = reader.requireInteger(name)
                "reply_to_message_id" -> replyToMessageId = reader.requireNullableString(name)
                "text" -> text = reader.requireString(name)
                else -> throw IllegalArgumentException("Secure message content contains an unknown member")
            }
        }
        reader.endObject()
        require(reader.peek() == JsonReader.Token.END_DOCUMENT) {
            "Secure message content contains trailing JSON"
        }
        reader.close()
        require(seen == CONTENT_MEMBERS) { "Secure message content is missing a required member" }
        require(schema == CONTENT_SCHEMA) { "Secure message content schema is unsupported" }
        require(type == CONTENT_TYPE) { "Secure message content type is unsupported" }
        val sender = SecureMessagingCryptoAddress(
            userId = requireNotNull(senderUserId) { "Secure message sender user ID is missing" },
            serverDeviceId = requireNotNull(senderDeviceId) { "Secure message sender device ID is missing" },
            signalDeviceId = requireNotNull(senderSignalDeviceId) {
                "Secure message sender Signal device ID is missing"
            },
        )
        val binding = SecureMessagingTextContentBinding(
            clientMessageId = requireNotNull(clientMessageId) {
                "Secure message client message ID is missing"
            },
            conversationId = requireNotNull(conversationId) {
                "Secure message conversation ID is missing"
            },
            rosterRevision = requireNotNull(rosterRevision) {
                "Secure message roster revision is missing"
            },
            sender = sender,
            replyToMessageId = replyToMessageId,
        )
        validateBinding(binding)
        val validatedText = requireNotNull(text) { "Secure message text is missing" }
        validateText(validatedText, maxTextScalars)
        return DecodedSecureMessagingTextContent(binding, validatedText)
    }

    private fun JsonReader.requireString(field: String): String {
        require(peek() == JsonReader.Token.STRING) { "Secure message $field must be a string" }
        return nextString()
    }

    private fun JsonReader.requireNullableString(field: String): String? = when (peek()) {
        JsonReader.Token.NULL -> nextNull<String>()
        JsonReader.Token.STRING -> nextString()
        else -> throw IllegalArgumentException("Secure message $field must be a string or null")
    }

    private fun JsonReader.requireInteger(field: String): Int {
        require(peek() == JsonReader.Token.NUMBER) { "Secure message $field must be an integer" }
        val encoded = nextString()
        require(JSON_INTEGER.matches(encoded)) { "Secure message $field must be an integer" }
        return requireNotNull(encoded.toIntOrNull()) { "Secure message $field is outside the integer range" }
    }

    private fun validateText(text: String, maxTextScalars: Int) {
        require(maxTextScalars in 1..MAX_HISTORY_DESCRIPTOR_SCALARS) {
            "Invalid secure message text bound"
        }
        require('\u0000' !in text) { "Secure message text cannot contain NUL" }
        var scalarCount = 0
        var index = 0
        while (index < text.length) {
            val character = text[index]
            when {
                Character.isHighSurrogate(character) -> {
                    require(index + 1 < text.length && Character.isLowSurrogate(text[index + 1])) {
                        "Secure message text contains an invalid Unicode scalar"
                    }
                    index += 2
                }
                Character.isLowSurrogate(character) -> throw IllegalArgumentException(
                    "Secure message text contains an invalid Unicode scalar",
                )
                else -> index++
            }
            scalarCount++
        }
        require(scalarCount in 1..maxTextScalars) {
            "Secure message text must contain 1 to $maxTextScalars Unicode scalars"
        }
    }

    private const val CONTENT_SCHEMA = "kit.messaging.content.v1"
    private const val CONTENT_TYPE = "text"
    private const val MAX_STANDARD_TEXT_SCALARS = 8_000
    private const val MAX_HISTORY_DESCRIPTOR_SCALARS = 48 * 1024
    private val JSON_INTEGER = Regex("^-?(0|[1-9][0-9]*)$")
    private val CONTENT_MEMBERS = setOf(
        "schema",
        "type",
        "client_message_id",
        "conversation_id",
        "roster_revision",
        "sender_user_id",
        "sender_device_id",
        "sender_signal_device_id",
        "reply_to_message_id",
        "text",
    )
}

/** Encodes a recovered original using exactly the ordinary secure-message content profile. */
internal fun encodeSecureMessagingTextContent(
    binding: SecureMessagingTextContentBinding,
    text: String,
): ByteArray = SecureMessagingTextContentCodec.encode(binding, text)

sealed interface SecureMessagingSessionEstablishmentRequest {
    val conversationId: String
    val rosterRevision: String

    fun bundles(): List<SecureMessagingRemoteKeyBundle>
}

/** Exact roster-derived authority for one outbound encryption attempt. */
sealed interface SecureMessagingEncryptionPlan

private enum class SecureMessagingTextProfile(val maxScalars: Int) {
    STANDARD(8_000),
    HISTORY_DESCRIPTOR(48 * 1024),
}

class SecureMessagingEncryptionRequest private constructor(
    val plan: SecureMessagingEncryptionPlan,
    val clientMessageId: String,
    text: String,
    val replyToMessageId: String?,
    textProfile: SecureMessagingTextProfile,
) : AutoCloseable {
    constructor(
        plan: SecureMessagingEncryptionPlan,
        clientMessageId: String,
        text: String,
        replyToMessageId: String? = null,
    ) : this(plan, clientMessageId, text, replyToMessageId, SecureMessagingTextProfile.STANDARD)

    private val sensitivePlaintext: SensitiveCryptoBytes
    internal val planSnapshot = SecureMessagingCryptoWireMapper.requireEncryptionPlan(plan)

    init {
        val encoded = SecureMessagingTextContentCodec.encode(
            SecureMessagingTextContentBinding(
                clientMessageId = clientMessageId,
                conversationId = planSnapshot.conversationId,
                rosterRevision = planSnapshot.rosterRevision,
                sender = planSnapshot.sender,
                replyToMessageId = replyToMessageId,
            ),
            text,
            textProfile.maxScalars,
        )
        sensitivePlaintext = try {
            SensitiveCryptoBytes.copyOf(encoded)
        } finally {
            encoded.fill(0)
        }
    }

    fun copyPlaintextBytes(): ByteArray = sensitivePlaintext.copyBytes()

    override fun close() = sensitivePlaintext.close()

    internal companion object {
        fun history(
            plan: SecureMessagingEncryptionPlan,
            clientMessageId: String,
            descriptor: String,
        ): SecureMessagingEncryptionRequest = SecureMessagingEncryptionRequest(
            plan = plan,
            clientMessageId = clientMessageId,
            text = descriptor,
            replyToMessageId = null,
            textProfile = SecureMessagingTextProfile.HISTORY_DESCRIPTOR,
        )
    }
}

enum class SecureMessagingEnvelopeKind {
    PREKEY,
    SESSION,
}

/** Opaque, wire-validated and activation-bound incoming decryption command. */
sealed interface SecureMessagingDecryptionRequest

data class SecureMessagingPreparedEnvelope(
    val recipient: SecureMessagingCryptoAddress,
    val kind: SecureMessagingEnvelopeKind,
    val ciphertext: OpaqueCryptoBytes,
) {
    init {
        require(ciphertext.size <= MAX_CIPHERTEXT_BYTES) {
            "Prepared secure-message ciphertext is too large"
        }
    }
}

class SecureMessagingPreparedFanout(
    val conversationId: String,
    val clientMessageId: String,
    val rosterRevision: String,
    val recipients: SecureMessagingExactRecipientSet,
    envelopes: List<SecureMessagingPreparedEnvelope>,
    val replyToMessageId: String? = null,
) {
    private val immutableEnvelopes = envelopes.toList()

    init {
        requireCanonicalUuid(conversationId, "conversation ID")
        requireCanonicalUuid(clientMessageId, "client message ID")
        requireRosterRevision(rosterRevision, "roster revision")
        replyToMessageId?.let { requireCanonicalUuid(it, "reply target") }
        require(
            immutableEnvelopes.map(SecureMessagingPreparedEnvelope::recipient).toSet() ==
                recipients.addressSet(),
        ) {
            "Committed ciphertext fanout must contain exactly one envelope per recipient"
        }
        require(
            immutableEnvelopes.map { it.recipient.serverDeviceId }.distinct().size ==
                immutableEnvelopes.size,
        ) {
            "Committed ciphertext fanout contains a duplicate recipient envelope"
        }
    }

    val envelopes: List<SecureMessagingPreparedEnvelope>
        get() = immutableEnvelopes.toList()
}

class SecureMessagingAuthenticatedPlaintext private constructor(
    val messageId: String,
    val conversationId: String,
    val sender: SecureMessagingCryptoAddress,
    plaintext: ByteArray,
    private val maxTextScalars: Int,
) : AutoCloseable {
    constructor(
        messageId: String,
        conversationId: String,
        sender: SecureMessagingCryptoAddress,
        plaintext: ByteArray,
    ) : this(messageId, conversationId, sender, plaintext, STANDARD_TEXT_SCALARS)

    private val sensitivePlaintext: SensitiveCryptoBytes
    internal val contentBinding: SecureMessagingTextContentBinding

    init {
        requireCanonicalUuid(messageId, "message ID")
        requireCanonicalUuid(conversationId, "conversation ID")
        val ownedCopy = plaintext.copyOf()
        try {
            val decoded = SecureMessagingTextContentCodec.decode(ownedCopy, maxTextScalars)
            require(decoded.binding.conversationId == conversationId) {
                "Authenticated content conversation does not match its message"
            }
            require(decoded.binding.sender == sender) {
                "Authenticated content sender does not match its message"
            }
            contentBinding = decoded.binding
            sensitivePlaintext = SensitiveCryptoBytes.copyOf(ownedCopy)
        } finally {
            ownedCopy.fill(0)
        }
    }

    private val closed = AtomicBoolean(false)

    val isClosed: Boolean get() = closed.get()

    fun copyPlaintextBytes(): ByteArray = sensitivePlaintext.copyBytes()

    /** Returns a fresh immutable UI copy only after the complete authenticated frame was validated. */
    fun copyText(): String {
        val bytes = sensitivePlaintext.copyBytes()
        return try {
            SecureMessagingTextContentCodec.decode(bytes, maxTextScalars).text
        } finally {
            bytes.fill(0)
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) sensitivePlaintext.close()
    }

    internal companion object {
        private const val STANDARD_TEXT_SCALARS = 8_000
        private const val HISTORY_DESCRIPTOR_SCALARS = 48 * 1024

        fun history(
            messageId: String,
            conversationId: String,
            sender: SecureMessagingCryptoAddress,
            plaintext: ByteArray,
        ): SecureMessagingAuthenticatedPlaintext = SecureMessagingAuthenticatedPlaintext(
            messageId,
            conversationId,
            sender,
            plaintext,
            HISTORY_DESCRIPTOR_SCALARS,
        )
    }
}

sealed interface SecureMessagingCommittedResult {
    sealed interface Provisioned : SecureMessagingCommittedResult

    sealed interface SessionsEstablished : SecureMessagingCommittedResult {
        val conversationId: String
        val rosterRevision: String
        fun addresses(): List<SecureMessagingCryptoAddress>
    }

    sealed interface Encrypted : SecureMessagingCommittedResult

    sealed interface Decrypted : SecureMessagingCommittedResult, AutoCloseable {
        val messageId: String
        val conversationId: String
        val sender: SecureMessagingCryptoAddress
        val isClosed: Boolean
        fun copyPlaintextBytes(): ByteArray
        fun copyText(): String
    }
}

internal data class SecureMessagingCommittedProvisioning(
    val publicBundle: SecureMessagingLocalPublicBundle,
    val provenance: SecureMessagingActivationProvenance,
    val identityKeyChange: Boolean,
)

private class DurablyCommittedProvisioning(
    val value: SecureMessagingCommittedProvisioning,
) : SecureMessagingCommittedResult.Provisioned

internal fun requireDurablyCommittedProvisioning(
    result: SecureMessagingCommittedResult.Provisioned,
): SecureMessagingCommittedProvisioning {
    check(result is DurablyCommittedProvisioning) {
        "Key publication was not released by a durable crypto transaction"
    }
    result.value.provenance.assertCurrent()
    return result.value
}

internal data class SecureMessagingCommittedFanout(
    val fanout: SecureMessagingPreparedFanout,
    val plan: SecureMessagingEncryptionPlanSnapshot,
    val provenance: SecureMessagingActivationProvenance,
)

private class DurablyCommittedFanout(
    val value: SecureMessagingCommittedFanout,
) : SecureMessagingCommittedResult.Encrypted

internal fun requireDurablyCommittedFanout(
    result: SecureMessagingCommittedResult.Encrypted,
): SecureMessagingCommittedFanout {
    check(result is DurablyCommittedFanout) {
        "Encrypted fanout was not released by a durable crypto transaction"
    }
    result.value.provenance.assertCurrent()
    return result.value
}

private class DurablyCommittedSessions(
    override val conversationId: String,
    override val rosterRevision: String,
    addresses: Collection<SecureMessagingCryptoAddress>,
    val provenance: SecureMessagingActivationProvenance,
) : SecureMessagingCommittedResult.SessionsEstablished {
    private val immutableAddresses = addresses.toList()

    override fun addresses(): List<SecureMessagingCryptoAddress> = immutableAddresses.toList()
}

internal fun requireDurablyCommittedSessions(
    result: SecureMessagingCommittedResult.SessionsEstablished,
): SecureMessagingCommittedResult.SessionsEstablished {
    check(result is DurablyCommittedSessions) {
        "Session result was not released by a durable crypto transaction"
    }
    result.provenance.assertCurrent()
    return result
}

private class DurablyCommittedDecryption(
    private val plaintext: SecureMessagingAuthenticatedPlaintext,
    val provenance: SecureMessagingActivationProvenance,
) : SecureMessagingCommittedResult.Decrypted {
    override val messageId: String get() = plaintext.messageId
    override val conversationId: String get() = plaintext.conversationId
    override val sender: SecureMessagingCryptoAddress get() = plaintext.sender
    override val isClosed: Boolean get() = plaintext.isClosed

    override fun copyPlaintextBytes(): ByteArray {
        provenance.assertCurrent()
        return plaintext.copyPlaintextBytes()
    }

    override fun copyText(): String {
        provenance.assertCurrent()
        return plaintext.copyText()
    }

    override fun close() = plaintext.close()
}

internal fun requireDurablyCommittedDecryption(
    result: SecureMessagingCommittedResult.Decrypted,
): SecureMessagingCommittedResult.Decrypted {
    check(result is DurablyCommittedDecryption) {
        "Authenticated plaintext was not released by a durable crypto transaction"
    }
    result.provenance.assertCurrent()
    return result
}

enum class SecureMessagingCryptoTransactionState {
    OPEN,
    STAGED,
    COMMITTING,
    COMMITTED,
    ABORTING,
    ABORTED,
    FAULTED,
}

enum class SecureMessagingCryptoOperation {
    PROVISION,
    ESTABLISH_SESSIONS,
    ENCRYPT,
    DECRYPT,
}

/**
 * Exactly one operation may be staged. No public bundle, ciphertext or plaintext is released by a
 * stage call; [commit] is the sole output point after the implementation durably commits all state.
 */
interface SecureMessagingCryptoTransaction {
    val binding: SecureMessagingSessionBinding
    val state: StateFlow<SecureMessagingCryptoTransactionState>

    /**
     * Locks the candidate addresses for this transaction and returns only those without a usable
     * session. The returned set contains identifiers only—never session records or key bytes.
     */
    suspend fun missingSessions(
        plan: SecureMessagingEncryptionPlan,
    ): SecureMessagingMissingSessionSet

    suspend fun stageProvisioning(plan: SecureMessagingProvisioningPlan)

    suspend fun stageSessionEstablishment(request: SecureMessagingSessionEstablishmentRequest)

    suspend fun stageEncryption(
        request: SecureMessagingEncryptionRequest,
        companionStateIntent: SecureMessagingCompanionStateIntent,
    )

    suspend fun stageDecryption(
        request: SecureMessagingDecryptionRequest,
        companionStateIntent: SecureMessagingCompanionStateIntent,
    )

    suspend fun commit(): SecureMessagingCommittedResult

    /** Idempotently rolls back and zeroizes an open or staged transaction. */
    suspend fun abort()
}

/**
 * State guard for a future crypto adapter. Durable writes performed by [commitPrepared] must be one
 * all-or-nothing transaction. [abortStaged] and [wipeStagedSecrets] must never publish an output.
 */
abstract class FailClosedSecureMessagingCryptoTransaction(
    activation: SecureMessagingActivationCapability,
) : SecureMessagingCryptoTransaction {
    private val activationProvenance =
        SecureMessagingActivationProvenance.requireCurrent(activation)
    final override val binding: SecureMessagingSessionBinding = activationProvenance.binding
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(SecureMessagingCryptoTransactionState.OPEN)
    private var stagedOperation: SecureMessagingCryptoOperation? = null
    private var companionStateIntent: SecureMessagingCompanionStateIntent? = null
    private var coveragePlan: SecureMessagingEncryptionPlanSnapshot? = null
    private var coveragePlanIdentity: SecureMessagingEncryptionPlan? = null
    private var coverageRecipients: Set<SecureMessagingCryptoAddress>? = null
    private var missingSessionAddresses: Set<SecureMessagingCryptoAddress>? = null
    private var stagedExpectation: StagedResultExpectation? = null
    private var preparedPayload: PreparedPayload? = null
    private var durableCommitCompleted = false
    private var cleanupPending = false

    final override val state: StateFlow<SecureMessagingCryptoTransactionState> =
        mutableState.asStateFlow()

    final override suspend fun missingSessions(
        plan: SecureMessagingEncryptionPlan,
    ): SecureMessagingMissingSessionSet = mutex.withLock {
        activationProvenance.assertCurrent()
        check(mutableState.value == SecureMessagingCryptoTransactionState.OPEN) {
            "Session coverage can be queried only before a crypto operation is staged"
        }
        check(coverageRecipients == null) {
            "Session coverage can be queried only once per crypto transaction"
        }
        val issuedPlan = SecureMessagingCryptoWireMapper.requireEncryptionPlan(plan)
        check(activationProvenance.isSameActivation(issuedPlan.provenance)) {
            "Encryption plan belongs to another authentication activation"
        }
        check(issuedPlan.sender.userId == binding.userId) {
            "Encryption-plan sender user does not match the active session"
        }
        check(issuedPlan.sender.serverDeviceId == binding.serverDeviceId) {
            "Encryption-plan sender device does not match the active session"
        }
        val candidates = issuedPlan.recipients.addresses()
        check(candidates.none { it.serverDeviceId == binding.serverDeviceId }) {
            "The current local device cannot be an outbound envelope recipient"
        }
        val missing = try {
            findMissingSessionAddresses(plan, candidates)
        } catch (error: Throwable) {
            rollbackAfterFailure(error)
            quarantineIfRequired(error)
            throw error
        }
        val result = try {
            activationProvenance.assertCurrent()
            SecureMessagingMissingSessionSet(issuedPlan.recipients, missing)
        } catch (error: Throwable) {
            rollbackAfterFailure(error)
            quarantineIfRequired(error)
            throw error
        }
        coveragePlan = issuedPlan
        coveragePlanIdentity = plan
        coverageRecipients = candidates.toSet()
        missingSessionAddresses = result.addresses().toSet()
        result
    }

    final override suspend fun stageProvisioning(plan: SecureMessagingProvisioningPlan) {
        stage(SecureMessagingCryptoOperation.PROVISION) {
            stageProvisioningMaterial(plan)
            stagedExpectation = StagedResultExpectation.Provisioning(plan)
        }
    }

    final override suspend fun stageSessionEstablishment(
        request: SecureMessagingSessionEstablishmentRequest,
    ) {
        stage(SecureMessagingCryptoOperation.ESTABLISH_SESSIONS) {
            val issued = SecureMessagingCryptoWireMapper.requireSessionEstablishment(request)
            check(activationProvenance.isSameActivation(issued.provenance)) {
                "Session-establishment claims belong to another authentication activation"
            }
            val plan = checkNotNull(coveragePlan) {
                "An authoritative encryption plan must precede key consumption"
            }
            check(issued.plan.isSamePlan(plan)) {
                "Session-establishment claims do not use the transaction's exact roster plan"
            }
            check(
                issued.conversationId == plan.conversationId &&
                    issued.rosterRevision == plan.rosterRevision,
            ) { "Session-establishment claims do not match the frozen encryption plan" }
            val expected = checkNotNull(missingSessionAddresses) {
                "Session coverage must be queried before consuming remote key bundles"
            }
            check(issued.bundles().map(SecureMessagingRemoteKeyBundle::address).toSet() == expected) {
                "Remote key bundles must exactly match the transaction's missing-session set"
            }
            stageSessionMaterial(request)
            stagedExpectation = StagedResultExpectation.SessionEstablishment(
                conversationId = issued.conversationId,
                rosterRevision = issued.rosterRevision,
                addresses = issued.bundles().map(SecureMessagingRemoteKeyBundle::address).toSet(),
            )
        }
    }

    final override suspend fun stageEncryption(
        request: SecureMessagingEncryptionRequest,
        companionStateIntent: SecureMessagingCompanionStateIntent,
    ) {
        stage(SecureMessagingCryptoOperation.ENCRYPT) {
            val plan = checkNotNull(coveragePlan) {
                "An authoritative encryption plan must precede encryption"
            }
            check(request.plan === coveragePlanIdentity) {
                "Encryption request does not use the transaction's exact roster plan"
            }
            check(activationProvenance.isSameActivation(request.planSnapshot.provenance)) {
                "Encryption request belongs to another authentication activation"
            }
            check(request.planSnapshot.isSamePlan(plan)) {
                "Encryption request changed after the roster plan was frozen"
            }
            val covered = checkNotNull(coverageRecipients) {
                "Session coverage must be queried before encryption"
            }
            check(plan.recipients.addressSet() == covered) {
                "Encryption recipients changed after the session-coverage snapshot"
            }
            check(missingSessionAddresses?.isEmpty() == true) {
                "Encryption cannot proceed while a recipient session is missing"
            }
            requireCompanionStateIntent(
                companionStateIntent,
                SecureMessagingCompanionStateDirection.OUTBOUND,
            )
            stageEncryptionMaterial(request)
            stagedExpectation = StagedResultExpectation.Encryption(
                plan = plan,
                clientMessageId = request.clientMessageId,
                replyToMessageId = request.replyToMessageId,
            )
            this.companionStateIntent = companionStateIntent
        }
    }

    final override suspend fun stageDecryption(
        request: SecureMessagingDecryptionRequest,
        companionStateIntent: SecureMessagingCompanionStateIntent,
    ) {
        stage(SecureMessagingCryptoOperation.DECRYPT) {
            val issued = SecureMessagingCryptoWireMapper.requireDecryptionRequest(request)
            check(activationProvenance.isSameActivation(issued.provenance)) {
                "Decryption request belongs to another authentication activation"
            }
            requireCompanionStateIntent(
                companionStateIntent,
                SecureMessagingCompanionStateDirection.INBOUND,
            )
            stageDecryptionMaterial(request)
            stagedExpectation = StagedResultExpectation.Decryption(
                messageId = issued.messageId,
                contentBinding = SecureMessagingTextContentBinding(
                    clientMessageId = issued.clientMessageId,
                    conversationId = issued.conversationId,
                    rosterRevision = issued.rosterRevision,
                    sender = issued.sender,
                    replyToMessageId = issued.replyToMessageId,
                ),
            )
            this.companionStateIntent = companionStateIntent
        }
    }

    final override suspend fun commit(): SecureMessagingCommittedResult = mutex.withLock {
        check(mutableState.value == SecureMessagingCryptoTransactionState.STAGED) {
            "A secure messaging crypto transaction must stage exactly one operation before commit"
        }
        val operation = checkNotNull(stagedOperation)
        mutableState.value = SecureMessagingCryptoTransactionState.COMMITTING

        var released = false
        try {
            activationProvenance.assertCurrent()
            val preparedResult = prepareStaged(operation, companionStateIntent)
            activationProvenance.assertCurrent()
            val payload = requirePreparedPayload(preparedResult)
            requireMatchingResult(operation, payload)
            if (payload is PreparedPayload.Provisioning) {
                SecureMessagingCryptoWireMapper.preflightProvisioning(payload.publicBundle)
            }
            if (operation in COMPANION_STATE_OPERATIONS) {
                checkNotNull(companionStateIntent) { "A message operation requires atomic companion state" }
            } else {
                check(companionStateIntent == null) { "A key operation cannot stage message companion state" }
            }
            commitPrepared(operation, preparedResult)
            durableCommitCompleted = true
            activationProvenance.assertCurrent()
            wipeStagedSecrets()
            cleanupPending = false
            val committed = releaseCommittedResult(payload)
            preparedPayload = null
            activePreparedHandle = null
            clearStagedReferences()
            mutableState.value = SecureMessagingCryptoTransactionState.COMMITTED
            released = true
            committed
        } catch (error: Throwable) {
            if (durableCommitCompleted) {
                cleanupAfterDurableCommitFailure(error)
            } else {
                rollbackAfterFailure(error)
            }
            quarantineIfRequired(error)
            throw error
        } finally {
            if (!released) {
                discardPreparedPayload()
            }
        }
    }

    final override suspend fun abort() {
        mutex.withLock {
            val startingState = mutableState.value
            when (startingState) {
                SecureMessagingCryptoTransactionState.COMMITTED,
                SecureMessagingCryptoTransactionState.ABORTED,
                -> return@withLock

                SecureMessagingCryptoTransactionState.FAULTED -> if (!cleanupPending) {
                    return@withLock
                }

                SecureMessagingCryptoTransactionState.OPEN,
                SecureMessagingCryptoTransactionState.STAGED,
                -> Unit

                SecureMessagingCryptoTransactionState.COMMITTING,
                SecureMessagingCryptoTransactionState.ABORTING,
                -> error("Secure messaging crypto transaction has an invalid concurrent lifecycle")
            }

            mutableState.value = SecureMessagingCryptoTransactionState.ABORTING
            var failure: Throwable? = null
            withContext(NonCancellable) {
                if (!durableCommitCompleted) {
                    try {
                        abortStaged()
                    } catch (error: Throwable) {
                        failure = error
                    }
                }
                try {
                    wipeStagedSecrets()
                } catch (error: Throwable) {
                    failure?.addSuppressed(error) ?: run { failure = error }
                }
            }
            clearStagedReferences()
            cleanupPending = failure != null
            mutableState.value = if (failure != null) {
                SecureMessagingCryptoTransactionState.FAULTED
            } else if (durableCommitCompleted || startingState == SecureMessagingCryptoTransactionState.FAULTED) {
                // A failed commit never becomes releasable merely because a later wipe succeeded.
                SecureMessagingCryptoTransactionState.FAULTED
            } else {
                SecureMessagingCryptoTransactionState.ABORTED
            }
            failure?.let { throw it }
        }
    }

    protected abstract suspend fun stageProvisioningMaterial(plan: SecureMessagingProvisioningPlan)

    protected abstract suspend fun stageSessionMaterial(
        request: SecureMessagingSessionEstablishmentRequest,
    )

    /** Must lock all candidates in stable address order until commit or abort completes. */
    protected abstract suspend fun findMissingSessionAddresses(
        plan: SecureMessagingEncryptionPlan,
        candidates: List<SecureMessagingCryptoAddress>,
    ): Collection<SecureMessagingCryptoAddress>

    protected abstract suspend fun stageEncryptionMaterial(request: SecureMessagingEncryptionRequest)

    protected abstract suspend fun stageDecryptionMaterial(request: SecureMessagingDecryptionRequest)

    /**
     * Prepares the operation result in memory without publishing it or durably advancing state.
     * [commitPrepared] is the only method allowed to make the prepared crypto mutation durable.
     */
    protected abstract suspend fun prepareStaged(
        operation: SecureMessagingCryptoOperation,
        companionStateIntent: SecureMessagingCompanionStateIntent?,
    ): PreparedCommit

    /**
     * Atomically commits crypto records and every materialized companion record. Throwing from any
     * write must roll back the whole commit; [preparedResult] is released only after this succeeds.
     */
    protected abstract suspend fun commitPrepared(
        operation: SecureMessagingCryptoOperation,
        preparedResult: PreparedCommit,
    )

    /** Must tolerate retry after a prior partial cleanup failure and must never publish output. */
    protected abstract suspend fun abortStaged()

    /** Must be idempotent so failed zeroization can be retried without reviving staged state. */
    protected abstract fun wipeStagedSecrets()

    /**
     * Resolves an opaque destination only inside the trusted adapter. The caller never receives a
     * prepared crypto result and cannot provide code that runs between prepare and durable commit.
     */
    protected fun companionStateDestination(
        intent: SecureMessagingCompanionStateIntent,
    ): CompanionStateDestination {
        val issued = requireCompanionStateIntent(intent, expectedDirection = null)
        return CompanionStateDestination(
            namespace = issued.namespace,
            recordKey = issued.recordKey,
            expectedVersion = issued.expectedVersion,
        )
    }

    protected fun preparedProvisioning(
        publicBundle: SecureMessagingLocalPublicBundle,
    ): PreparedCommit = registerPreparedPayload(
        PreparedPayload.Provisioning(snapshotPublicBundle(publicBundle)),
    )

    protected fun preparedSessionsEstablished(
        conversationId: String,
        rosterRevision: String,
        addresses: Collection<SecureMessagingCryptoAddress>,
    ): PreparedCommit = registerPreparedPayload(
        PreparedPayload.SessionsEstablished(conversationId, rosterRevision, addresses.toList()),
    )

    protected fun preparedEncryption(
        fanout: SecureMessagingPreparedFanout,
    ): PreparedCommit = registerPreparedPayload(
        PreparedPayload.Encryption(snapshotFanout(fanout)),
    )

    protected fun preparedDecryption(
        messageId: String,
        conversationId: String,
        sender: SecureMessagingCryptoAddress,
        plaintext: ByteArray,
        isHistoryBackfill: Boolean = false,
    ): PreparedCommit = registerPreparedPayload(
        PreparedPayload.Decryption(
            if (isHistoryBackfill) {
                SecureMessagingAuthenticatedPlaintext.history(
                    messageId = messageId,
                    conversationId = conversationId,
                    sender = sender,
                    plaintext = plaintext,
                )
            } else {
                SecureMessagingAuthenticatedPlaintext(
                    messageId = messageId,
                    conversationId = conversationId,
                    sender = sender,
                    plaintext = plaintext,
                )
            },
        ),
    )

    private suspend fun stage(
        operation: SecureMessagingCryptoOperation,
        block: suspend () -> Unit,
    ) = mutex.withLock {
        check(mutableState.value == SecureMessagingCryptoTransactionState.OPEN) {
            "A secure messaging crypto transaction may stage exactly one operation"
        }
        try {
            activationProvenance.assertCurrent()
            block()
            activationProvenance.assertCurrent()
            stagedOperation = operation
            mutableState.value = SecureMessagingCryptoTransactionState.STAGED
        } catch (error: Throwable) {
            rollbackAfterFailure(error)
            quarantineIfRequired(error)
            throw error
        }
    }

    private suspend fun rollbackAfterFailure(primary: Throwable) {
        var cleanupFailure: Throwable? = null
        withContext(NonCancellable) {
            try {
                abortStaged()
            } catch (error: Throwable) {
                cleanupFailure = error
            }
            try {
                wipeStagedSecrets()
            } catch (error: Throwable) {
                cleanupFailure?.addSuppressed(error) ?: run { cleanupFailure = error }
            }
        }
        cleanupFailure?.let(primary::addSuppressed)
        cleanupPending = cleanupFailure != null
        clearStagedReferences()
        mutableState.value = SecureMessagingCryptoTransactionState.FAULTED
    }

    private suspend fun cleanupAfterDurableCommitFailure(primary: Throwable) {
        var cleanupFailure: Throwable? = null
        withContext(NonCancellable) {
            try {
                wipeStagedSecrets()
            } catch (error: Throwable) {
                cleanupFailure = error
            }
        }
        cleanupFailure?.let(primary::addSuppressed)
        cleanupPending = cleanupFailure != null
        clearStagedReferences()
        mutableState.value = SecureMessagingCryptoTransactionState.FAULTED
    }

    private fun requireMatchingResult(
        operation: SecureMessagingCryptoOperation,
        result: PreparedPayload,
    ) {
        val expectation = checkNotNull(stagedExpectation) {
            "Crypto transaction omitted its staged-result expectation"
        }
        val matches = when {
            operation == SecureMessagingCryptoOperation.PROVISION &&
                expectation is StagedResultExpectation.Provisioning &&
                result is PreparedPayload.Provisioning ->
                result.publicBundle.oneTimePrekeys.size == expectation.plan.ecOneTimePrekeyCount &&
                    result.publicBundle.pqPrekeys.size == expectation.plan.pqOneTimePrekeyCount

            operation == SecureMessagingCryptoOperation.ESTABLISH_SESSIONS &&
                expectation is StagedResultExpectation.SessionEstablishment &&
                result is PreparedPayload.SessionsEstablished ->
                result.conversationId == expectation.conversationId &&
                    result.rosterRevision == expectation.rosterRevision &&
                    result.addresses.size == expectation.addresses.size &&
                    result.addresses.toSet() == expectation.addresses

            operation == SecureMessagingCryptoOperation.ENCRYPT &&
                expectation is StagedResultExpectation.Encryption &&
                result is PreparedPayload.Encryption ->
                result.fanout.conversationId == expectation.plan.conversationId &&
                result.fanout.clientMessageId == expectation.clientMessageId &&
                    result.fanout.rosterRevision == expectation.plan.rosterRevision &&
                    result.fanout.recipients.addressSet() == expectation.plan.recipients.addressSet() &&
                    result.fanout.replyToMessageId == expectation.replyToMessageId

            operation == SecureMessagingCryptoOperation.DECRYPT &&
                expectation is StagedResultExpectation.Decryption &&
                result is PreparedPayload.Decryption ->
                result.plaintext.messageId == expectation.messageId &&
                    result.plaintext.contentBinding == expectation.contentBinding

            else -> false
        }
        check(matches) { "Crypto transaction prepared a result that does not match its staged request" }
    }

    private fun clearStagedReferences() {
        stagedOperation = null
        stagedExpectation = null
        companionStateIntent = null
        coveragePlan = null
        coveragePlanIdentity = null
        coverageRecipients = null
        missingSessionAddresses = null
    }

    private fun quarantineIfRequired(error: Throwable) {
        if (error !is SecureMessagingCryptographicFailureException) return
        runCatching { activationProvenance.quarantine(error.quarantineReason) }
            .exceptionOrNull()
            ?.let(error::addSuppressed)
    }

    private sealed interface StagedResultExpectation {
        data class Provisioning(val plan: SecureMessagingProvisioningPlan) : StagedResultExpectation

        data class SessionEstablishment(
            val conversationId: String,
            val rosterRevision: String,
            val addresses: Set<SecureMessagingCryptoAddress>,
        ) : StagedResultExpectation

        data class Encryption(
            val plan: SecureMessagingEncryptionPlanSnapshot,
            val clientMessageId: String,
            val replyToMessageId: String?,
        ) : StagedResultExpectation

        data class Decryption(
            val messageId: String,
            val contentBinding: SecureMessagingTextContentBinding,
        ) : StagedResultExpectation
    }

    private companion object {
        val COMPANION_STATE_OPERATIONS = setOf(
            SecureMessagingCryptoOperation.ENCRYPT,
            SecureMessagingCryptoOperation.DECRYPT,
        )
    }

    protected class PreparedCommit private constructor() {
        companion object {
            fun create(): PreparedCommit = PreparedCommit()
        }
    }

    protected class CompanionStateDestination internal constructor(
        val namespace: String,
        val recordKey: String,
        val expectedVersion: Long?,
    )

    private sealed interface PreparedPayload : AutoCloseable {
        override fun close() = Unit

        data class Provisioning(
            val publicBundle: SecureMessagingLocalPublicBundle,
        ) : PreparedPayload

        data class SessionsEstablished(
            val conversationId: String,
            val rosterRevision: String,
            val addresses: List<SecureMessagingCryptoAddress>,
        ) : PreparedPayload {
            init {
                requireCanonicalUuid(conversationId, "session-establishment conversation ID")
                requireRosterRevision(rosterRevision, "session-establishment roster revision")
                require(addresses.isNotEmpty()) { "Session establishment requires an address" }
                require(addresses.distinct().size == addresses.size) {
                    "Session-establishment addresses must be unique"
                }
            }
        }

        data class Encryption(val fanout: SecureMessagingPreparedFanout) : PreparedPayload

        class Decryption(val plaintext: SecureMessagingAuthenticatedPlaintext) : PreparedPayload {
            override fun close() = plaintext.close()
        }
    }

    private fun registerPreparedPayload(payload: PreparedPayload): PreparedCommit {
        check(mutableState.value == SecureMessagingCryptoTransactionState.COMMITTING) {
            "A prepared crypto handle can be created only during commit"
        }
        check(preparedPayload == null) { "A crypto transaction may prepare exactly one result" }
        val handle = PreparedCommit.create()
        preparedPayload = payload
        activePreparedHandle = handle
        return handle
    }

    private fun requirePreparedPayload(handle: PreparedCommit): PreparedPayload =
        checkNotNull(preparedPayload) {
            "Prepared crypto handle was not issued by this transaction"
        }.also {
            check(handle === activePreparedHandle) {
                "Prepared crypto handle belongs to another transaction"
            }
        }

    private var activePreparedHandle: PreparedCommit? = null

    private fun discardPreparedPayload() {
        preparedPayload?.close()
        preparedPayload = null
        activePreparedHandle = null
    }

    private fun releaseCommittedResult(payload: PreparedPayload): SecureMessagingCommittedResult =
        when (payload) {
            is PreparedPayload.Provisioning -> {
                val expectation = stagedExpectation as? StagedResultExpectation.Provisioning
                    ?: error("Provisioning commit lost its staged expectation")
                DurablyCommittedProvisioning(
                    SecureMessagingCommittedProvisioning(
                        publicBundle = payload.publicBundle,
                        provenance = activationProvenance,
                        identityKeyChange = expectation.plan.identityKeyChange,
                    ),
                )
            }

            is PreparedPayload.SessionsEstablished -> DurablyCommittedSessions(
                conversationId = payload.conversationId,
                rosterRevision = payload.rosterRevision,
                addresses = payload.addresses,
                provenance = activationProvenance,
            )

            is PreparedPayload.Encryption -> {
                val plan = (stagedExpectation as? StagedResultExpectation.Encryption)?.plan
                    ?: error("Encryption commit lost its roster plan")
                check(activationProvenance.isSameActivation(plan.provenance)) {
                    "Committed encryption plan belongs to another activation"
                }
                DurablyCommittedFanout(
                    SecureMessagingCommittedFanout(
                        fanout = payload.fanout,
                        plan = plan.snapshot(),
                        provenance = activationProvenance,
                    ),
                )
            }

            is PreparedPayload.Decryption -> DurablyCommittedDecryption(
                plaintext = payload.plaintext,
                provenance = activationProvenance,
            )
        }
}

enum class SecureMessagingRuntimeStage {
    NO_SESSION,
    ACTIVATING,
    CHECKING_CAPABILITIES,
    PREPARING_KEYS,
    SYNCING_ROSTER,
    READY,
    QUARANTINED,
    ERASING,
}

enum class SecureMessagingQuarantineReason {
    MALFORMED_WIRE_DATA,
    SIGNATURE_FAILURE,
    IDENTITY_CHANGED,
    REPLAY_OR_ROLLBACK,
    STATE_UNAVAILABLE,
    CURRENT_DEVICE_REVOKED,
    INTERNAL_INVARIANT,
}

/**
 * A permanent cryptographic or authenticated-state failure for the current activation.
 *
 * Throwing this type from inside the crypto boundary synchronously quarantines the matching
 * lifecycle generation. Callers can therefore never accidentally keep using a session after an
 * identity substitution, signature failure, replay, or authenticated local-state corruption.
 */
internal class SecureMessagingCryptographicFailureException(
    val quarantineReason: SecureMessagingQuarantineReason,
    message: String,
    cause: Throwable? = null,
) : SecurityException(message, cause)

data class SecureMessagingRuntimeSnapshot(
    val stage: SecureMessagingRuntimeStage,
    val binding: SecureMessagingSessionBinding? = null,
    val quarantineReason: SecureMessagingQuarantineReason? = null,
)

class SecureMessagingSessionFence internal constructor(
    internal val binding: SecureMessagingSessionBinding,
    internal val activationIdentity: Any,
)

/**
 * Small fail-closed orchestration state machine. Call [assertCurrent] immediately before and after
 * every network suspension; a stale session fence can never become valid for a later login.
 */
@Singleton
class SecureMessagingLifecycleGuard @Inject constructor() {
    private val lock = Any()
    private var current = SecureMessagingRuntimeSnapshot(SecureMessagingRuntimeStage.NO_SESSION)
    private val mutableRuntime = MutableStateFlow(current)
    private val readinessInvalidationListeners = linkedSetOf<() -> Unit>()
    private var currentActivationIdentity: Any? = null
    private var currentCapability: IssuedSecureMessagingActivationCapability? = null

    /** Authoritative, process-local activation state for repositories and background sync. */
    val runtime: StateFlow<SecureMessagingRuntimeSnapshot> = mutableRuntime.asStateFlow()

    fun snapshot(): SecureMessagingRuntimeSnapshot = synchronized(lock) { current.copy() }

    fun beginSession(binding: SecureMessagingSessionBinding): SecureMessagingSessionFence {
        val result = synchronized(lock) {
            check(current.stage == SecureMessagingRuntimeStage.NO_SESSION) {
                "Secure messaging must erase the previous epoch before activation"
            }
            val activationIdentity = Any()
            val fence = SecureMessagingSessionFence(binding, activationIdentity)
            val capability = IssuedSecureMessagingActivationCapability(
                binding = binding,
                validator = { readyRequired ->
                    synchronized(lock) {
                        assertCurrentIdentityLocked(activationIdentity, binding, readyRequired)
                    }
                },
                quarantiner = { reason -> quarantine(fence, reason) },
            )
            currentActivationIdentity = activationIdentity
            currentCapability = capability
            setCurrentLocked(
                SecureMessagingRuntimeSnapshot(
                    stage = SecureMessagingRuntimeStage.ACTIVATING,
                    binding = binding,
                ),
            )
            fence to readinessInvalidationListeners.toList()
        }
        notifyReadinessInvalidated(result.second)
        return result.first
    }

    fun activationCapability(
        fence: SecureMessagingSessionFence,
        readyRequired: Boolean = false,
    ): SecureMessagingActivationCapability = synchronized(lock) {
        assertCurrentLocked(fence, readyRequired)
        checkNotNull(currentCapability) { "Secure messaging activation capability is unavailable" }
    }

    fun beginCapabilityCheck(fence: SecureMessagingSessionFence) {
        transition(
            fence,
            setOf(
                SecureMessagingRuntimeStage.ACTIVATING,
                // A network failure after the transition must be retryable with the same
                // activation identity; reopening a later login still cannot revive this fence.
                SecureMessagingRuntimeStage.CHECKING_CAPABILITIES,
            ),
            SecureMessagingRuntimeStage.CHECKING_CAPABILITIES,
        )
    }

    fun beginKeyPreparation(fence: SecureMessagingSessionFence) {
        transition(
            fence,
            setOf(
                SecureMessagingRuntimeStage.CHECKING_CAPABILITIES,
                SecureMessagingRuntimeStage.PREPARING_KEYS,
                SecureMessagingRuntimeStage.READY,
            ),
            SecureMessagingRuntimeStage.PREPARING_KEYS,
        )
    }

    fun beginRosterSync(fence: SecureMessagingSessionFence) {
        transition(
            fence,
            setOf(
                SecureMessagingRuntimeStage.PREPARING_KEYS,
                SecureMessagingRuntimeStage.SYNCING_ROSTER,
                SecureMessagingRuntimeStage.READY,
            ),
            SecureMessagingRuntimeStage.SYNCING_ROSTER,
        )
    }

    /**
     * Releases message exchange only after the activation coordinator has completed key
     * provisioning, publication, roster validation, session recovery and initial encrypted sync.
     */
    internal fun finishActivation(fence: SecureMessagingSessionFence) {
        transition(
            fence,
            setOf(
                SecureMessagingRuntimeStage.SYNCING_ROSTER,
                SecureMessagingRuntimeStage.READY,
            ),
            SecureMessagingRuntimeStage.READY,
        )
    }

    fun quarantine(
        fence: SecureMessagingSessionFence,
        reason: SecureMessagingQuarantineReason,
    ) {
        val listeners = synchronized(lock) {
            check(currentActivationIdentity === fence.activationIdentity) {
                "Secure messaging activation generation changed"
            }
            check(current.binding == fence.binding) { "Secure messaging session epoch changed" }
            if (current.stage == SecureMessagingRuntimeStage.QUARANTINED) {
                check(current.quarantineReason == reason) {
                    "Secure messaging activation was quarantined for another reason"
                }
                return@synchronized emptyList()
            }
            assertCurrentLocked(fence, readyRequired = false)
            setCurrentLocked(
                SecureMessagingRuntimeSnapshot(
                    stage = SecureMessagingRuntimeStage.QUARANTINED,
                    binding = fence.binding,
                    quarantineReason = reason,
                ),
            )
            readinessInvalidationListeners.toList()
        }
        notifyReadinessInvalidated(listeners)
    }

    fun beginErasure(): SecureMessagingSessionBinding? {
        val result = synchronized(lock) {
            check(current.stage != SecureMessagingRuntimeStage.ERASING) {
                "Secure messaging erasure is already in progress"
            }
            val binding = current.binding
            setCurrentLocked(
                SecureMessagingRuntimeSnapshot(
                    stage = SecureMessagingRuntimeStage.ERASING,
                    binding = binding,
                ),
            )
            binding to readinessInvalidationListeners.toList()
        }
        notifyReadinessInvalidated(result.second)
        return result.first
    }

    /**
     * Starts erasure only for the exact activation that requested enrollment recovery.
     * [persistErasureFence] runs while the lifecycle lock still proves that exact activation;
     * if it fails, the runtime stage and capability remain unchanged.
     */
    internal fun beginRecoveryErasure(
        fence: SecureMessagingSessionFence,
        persistErasureFence: () -> Unit = {},
    ) {
        val listeners = synchronized(lock) {
            assertRecoveryErasureCurrentLocked(fence)
            persistErasureFence()
            setCurrentLocked(
                SecureMessagingRuntimeSnapshot(
                    stage = SecureMessagingRuntimeStage.ERASING,
                    binding = current.binding,
                ),
            )
            readinessInvalidationListeners.toList()
        }
        notifyReadinessInvalidated(listeners)
    }

    /** Non-mutating preflight used before a recovery snapshot enters any account archive. */
    internal fun assertRecoveryErasureCurrent(fence: SecureMessagingSessionFence) =
        synchronized(lock) {
            assertRecoveryErasureCurrentLocked(fence)
        }

    fun finishErasure() {
        val listeners = synchronized(lock) {
            check(current.stage == SecureMessagingRuntimeStage.ERASING) {
                "Secure messaging erasure did not start"
            }
            currentActivationIdentity = null
            currentCapability = null
            setCurrentLocked(SecureMessagingRuntimeSnapshot(SecureMessagingRuntimeStage.NO_SESSION))
            readinessInvalidationListeners.toList()
        }
        notifyReadinessInvalidated(listeners)
    }

    /**
     * Registers a synchronous fail-closed hook. It is invoked whenever message exchange is not
     * ready, including logout, replacement, quarantine and temporary key/roster maintenance.
     */
    internal fun addReadinessInvalidationListener(listener: () -> Unit) {
        val invokeImmediately = synchronized(lock) {
            readinessInvalidationListeners += listener
            current.stage != SecureMessagingRuntimeStage.READY
        }
        if (invokeImmediately) listener()
    }

    fun assertCurrent(fence: SecureMessagingSessionFence, readyRequired: Boolean = false) = synchronized(lock) {
        assertCurrentLocked(fence, readyRequired)
    }

    /** Includes a quarantined generation that still exclusively owns fenced recovery. */
    internal fun ownsGeneration(fence: SecureMessagingSessionFence): Boolean = synchronized(lock) {
        currentActivationIdentity === fence.activationIdentity &&
            current.binding == fence.binding &&
            current.stage !in setOf(
                SecureMessagingRuntimeStage.NO_SESSION,
                SecureMessagingRuntimeStage.ERASING,
            )
    }

    fun assertCurrent(
        capability: SecureMessagingActivationCapability,
        readyRequired: Boolean = false,
    ) {
        SecureMessagingActivationProvenance.requireCurrent(capability, readyRequired)
    }

    /**
     * Runs one non-suspending publication while the exact activation remains READY. Lifecycle
     * transitions cannot interleave with the callback, so observers can never see a projection
     * published after logout/quarantine has begun.
     */
    internal fun runIfCurrentAndReady(
        fence: SecureMessagingSessionFence,
        publication: () -> Unit,
    ): Boolean = synchronized(lock) {
        val currentAndReady = runCatching {
            assertCurrentLocked(fence, readyRequired = true)
        }.isSuccess
        if (!currentAndReady) return@synchronized false
        publication()
        true
    }

    private fun transition(
        fence: SecureMessagingSessionFence,
        from: Set<SecureMessagingRuntimeStage>,
        to: SecureMessagingRuntimeStage,
    ) {
        val listeners = synchronized(lock) {
            assertCurrentLocked(fence, readyRequired = false)
            check(current.stage in from) {
                "Illegal secure messaging lifecycle transition from ${current.stage} to $to"
            }
            setCurrentLocked(SecureMessagingRuntimeSnapshot(stage = to, binding = fence.binding))
            readinessInvalidationListeners.toList()
        }
        notifyReadinessInvalidated(listeners)
    }

    private fun setCurrentLocked(snapshot: SecureMessagingRuntimeSnapshot) {
        current = snapshot
        mutableRuntime.value = snapshot
    }

    private fun notifyReadinessInvalidated(listeners: List<() -> Unit>) {
        listeners.forEach { listener ->
            // Invalidation is best-effort notification only. The generation fence remains the
            // authority even if a presentation observer has been torn down unexpectedly.
            runCatching { listener() }
        }
    }

    private fun assertCurrentLocked(
        fence: SecureMessagingSessionFence,
        readyRequired: Boolean,
    ) {
        check(currentActivationIdentity === fence.activationIdentity) {
            "Secure messaging activation generation changed"
        }
        check(current.binding == fence.binding) { "Secure messaging session epoch changed" }
        check(
            current.stage !in setOf(
                SecureMessagingRuntimeStage.NO_SESSION,
                SecureMessagingRuntimeStage.QUARANTINED,
                SecureMessagingRuntimeStage.ERASING,
            ),
        ) { "Secure messaging session is not active" }
        if (readyRequired) {
            check(current.stage == SecureMessagingRuntimeStage.READY) {
                "Secure messaging is not ready for message exchange"
            }
        }
    }

    private fun assertRecoveryErasureCurrentLocked(fence: SecureMessagingSessionFence) {
        check(currentActivationIdentity === fence.activationIdentity) {
            "Secure messaging recovery activation generation changed"
        }
        check(current.binding == fence.binding) {
            "Secure messaging recovery session epoch changed"
        }
        check(
            current.stage in setOf(
                SecureMessagingRuntimeStage.PREPARING_KEYS,
                SecureMessagingRuntimeStage.QUARANTINED,
            ),
        ) { "Secure messaging recovery cannot erase from ${current.stage}" }
    }

    private fun assertCurrentIdentityLocked(
        activationIdentity: Any,
        binding: SecureMessagingSessionBinding,
        readyRequired: Boolean,
    ) {
        check(currentActivationIdentity === activationIdentity) {
            "Secure messaging activation generation changed"
        }
        check(current.binding == binding) { "Secure messaging session epoch changed" }
        check(
            current.stage !in setOf(
                SecureMessagingRuntimeStage.NO_SESSION,
                SecureMessagingRuntimeStage.QUARANTINED,
                SecureMessagingRuntimeStage.ERASING,
            ),
        ) { "Secure messaging session is not active" }
        if (readyRequired) {
            check(current.stage == SecureMessagingRuntimeStage.READY) {
                "Secure messaging is not ready for message exchange"
            }
        }
    }
}

private fun requireCompanionStateIntent(
    intent: SecureMessagingCompanionStateIntent,
    expectedDirection: SecureMessagingCompanionStateDirection?,
): IssuedSecureMessagingCompanionStateIntent {
    check(intent is IssuedSecureMessagingCompanionStateIntent) {
        "Companion-state intent was not issued by the crypto boundary"
    }
    expectedDirection?.let {
        check(intent.direction == it) { "Companion-state intent has the wrong direction" }
    }
    return intent
}

private fun snapshotPublicBundle(
    bundle: SecureMessagingLocalPublicBundle,
): SecureMessagingLocalPublicBundle = SecureMessagingLocalPublicBundle(
    registrationId = bundle.registrationId,
    identityKey = snapshotOpaqueBytes(bundle.identityKey),
    signedPrekey = snapshotPublicPrekey(bundle.signedPrekey),
    oneTimePrekeys = bundle.oneTimePrekeys.map(::snapshotPublicPrekey),
    pqPrekeys = bundle.pqPrekeys.map(::snapshotPublicPrekey),
    pqLastResortPrekey = snapshotPublicPrekey(bundle.pqLastResortPrekey),
)

private fun snapshotPublicPrekey(prekey: SecureMessagingPublicPrekey): SecureMessagingPublicPrekey =
    SecureMessagingPublicPrekey(
        id = prekey.id,
        publicKey = snapshotOpaqueBytes(prekey.publicKey),
        signature = prekey.signature?.let(::snapshotOpaqueBytes),
    )

private fun snapshotFanout(fanout: SecureMessagingPreparedFanout): SecureMessagingPreparedFanout =
    SecureMessagingPreparedFanout(
        conversationId = fanout.conversationId,
        clientMessageId = fanout.clientMessageId,
        rosterRevision = fanout.rosterRevision,
        recipients = SecureMessagingExactRecipientSet(fanout.recipients.addresses()),
        envelopes = fanout.envelopes.map { envelope ->
            SecureMessagingPreparedEnvelope(
                recipient = envelope.recipient,
                kind = envelope.kind,
                ciphertext = snapshotOpaqueBytes(envelope.ciphertext),
            )
        },
        replyToMessageId = fanout.replyToMessageId,
    )

private fun snapshotOpaqueBytes(bytes: OpaqueCryptoBytes): OpaqueCryptoBytes =
    OpaqueCryptoBytes.copyOf(bytes.copyBytes())

private fun requireBoundedIdentifier(value: String, field: String) {
    require(value.isNotBlank() && value.length <= MAX_IDENTIFIER_LENGTH) { "Invalid $field" }
}

private fun requireCanonicalUuid(value: String, field: String) {
    require(UUID_PATTERN.matches(value)) { "Invalid $field" }
}

private fun requireRosterRevision(value: String, field: String) {
    require(ROSTER_REVISION.matches(value)) { "Invalid $field" }
}

private fun requireStateAddress(value: String, field: String) {
    require(STATE_ADDRESS.matches(value)) { "Invalid $field" }
}

private const val MAX_IDENTIFIER_LENGTH = 256
private const val MAX_PLAINTEXT_BYTES = 64 * 1024
private const val MAX_CIPHERTEXT_BYTES = 1_500_000
private const val MAX_RECIPIENT_DEVICES = 99
private val SHA256_HEX = Regex("^[a-f0-9]{64}$")
private val UUID_PATTERN = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
private val ROSTER_REVISION = Regex("^v1:sha256:[a-f0-9]{64}$")
private val STATE_ADDRESS = Regex("^[A-Za-z0-9._:@-]{1,160}$")
