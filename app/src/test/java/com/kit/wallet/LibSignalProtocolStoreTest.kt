package com.kit.wallet

import com.kit.wallet.data.messaging.LibSignalProtocolStore
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.ReusedBaseKeyException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord

class LibSignalProtocolStoreTest {
    @Test
    fun `plaintext transaction snapshot round trips local identity and registration`() {
        val original = LibSignalProtocolStore.create()
        val identity = original.identityKeyPair.serialize()
        val registrationId = original.getLocalRegistrationId()

        val restored = LibSignalProtocolStore.deserialize(original.serialize())

        assertArrayEquals(identity, restored.identityKeyPair.serialize())
        assertEquals(registrationId, restored.getLocalRegistrationId())
        assertArrayEquals(original.serialize(), restored.serialize())
    }

    @Test
    fun `pinned remote identity rejects a replacement after state reload`() {
        val original = LibSignalProtocolStore.create()
        val address = SignalProtocolAddress(UUID.randomUUID().toString(), 2)
        val first = IdentityKeyPair.generate().publicKey
        val replacement = IdentityKeyPair.generate().publicKey
        assertNotEquals(first, replacement)

        assertTrue(
            original.isTrustedIdentity(address, first, IdentityKeyStore.Direction.RECEIVING),
        )
        assertEquals(
            IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED,
            original.saveIdentity(address, first),
        )

        val restored = LibSignalProtocolStore.deserialize(original.serialize())
        assertTrue(restored.isTrustedIdentity(address, first, IdentityKeyStore.Direction.SENDING))
        assertFalse(
            restored.isTrustedIdentity(address, replacement, IdentityKeyStore.Direction.RECEIVING),
        )
        assertArrayEquals(first.serialize(), restored.getIdentity(address)?.serialize())
    }

    @Test
    fun `one-time PQ prekey is removed when libsignal consumes it`() {
        val store = LibSignalProtocolStore.create()
        val signed = store.storeSignedPrekeyForTest(10)
        store.storeKyberForTest(id = 20, lastResort = false)

        store.markKyberPreKeyUsed(20, signed, ECKeyPair.generate().publicKey)

        assertFalse(store.containsKyberPreKey(20))
        assertFalse(LibSignalProtocolStore.deserialize(store.serialize()).containsKyberPreKey(20))
    }

    @Test
    fun `last-resort PQ replay marker survives process restart`() {
        val store = LibSignalProtocolStore.create()
        val signed = store.storeSignedPrekeyForTest(30)
        store.storeKyberForTest(id = 40, lastResort = true)
        val firstBaseKey = ECKeyPair.generate().publicKey
        val secondBaseKey = ECKeyPair.generate().publicKey

        store.markKyberPreKeyUsed(40, signed, firstBaseKey)
        val restored = LibSignalProtocolStore.deserialize(store.serialize())

        assertTrue(restored.containsKyberPreKey(40))
        assertThrows(ReusedBaseKeyException::class.java) {
            restored.markKyberPreKeyUsed(40, signed, firstBaseKey)
        }
        restored.markKyberPreKeyUsed(40, signed, secondBaseKey)
        assertTrue(restored.containsKyberPreKey(40))
    }

    @Test
    fun `PQ use rejects a missing signed prekey before mutating state`() {
        val store = LibSignalProtocolStore.create()
        store.storeKyberForTest(id = 50, lastResort = true)

        assertThrows(InvalidKeyIdException::class.java) {
            store.markKyberPreKeyUsed(50, 999, ECKeyPair.generate().publicKey)
        }

        assertTrue(store.containsKyberPreKey(50))
        LibSignalProtocolStore.deserialize(store.serialize()).close()
    }

    @Test
    fun `subdevice lookup follows libsignal convention and excludes primary device one`() {
        val store = LibSignalProtocolStore.create()
        val userId = UUID.randomUUID().toString()
        store.storeSession(SignalProtocolAddress(userId, 1), SessionRecord())
        store.storeSession(SignalProtocolAddress(userId, 2), SessionRecord())

        assertEquals(listOf(2), store.getSubDeviceSessions(userId))
        val restored = LibSignalProtocolStore.deserialize(store.serialize())
        assertEquals(listOf(2), restored.getSubDeviceSessions(userId))
        assertFalse(restored.loadSession(SignalProtocolAddress(userId, 2)).hasSenderChain(1.0))
        restored.close()
    }

    @Test
    fun `closed transaction copy cannot release plaintext protocol state`() {
        val store = LibSignalProtocolStore.create()
        store.close()
        store.close()

        assertThrows(IllegalStateException::class.java) { store.serialize() }
        assertThrows(IllegalStateException::class.java) { store.identityKeyPair }
    }

    @Test
    fun `state decoder rejects trailing or truncated records`() {
        val encoded = LibSignalProtocolStore.create().serialize()
        assertThrows(IllegalArgumentException::class.java) {
            LibSignalProtocolStore.deserialize(encoded + byteArrayOf(1))
        }
        assertThrows(Exception::class.java) {
            LibSignalProtocolStore.deserialize(encoded.copyOf(encoded.size - 1))
        }
    }

    private fun LibSignalProtocolStore.storeSignedPrekeyForTest(id: Int): Int {
        val keyPair = ECKeyPair.generate()
        val signature = identityKeyPair.privateKey.calculateSignature(keyPair.publicKey.serialize())
        storeSignedPreKey(
            id,
            SignedPreKeyRecord(id, System.currentTimeMillis(), keyPair, signature),
        )
        return id
    }

    private fun LibSignalProtocolStore.storeKyberForTest(id: Int, lastResort: Boolean) {
        val keyPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val signature = identityKeyPair.privateKey.calculateSignature(keyPair.publicKey.serialize())
        storeKyberPreKey(
            id,
            KyberPreKeyRecord(id, System.currentTimeMillis(), keyPair, signature),
        )
        if (lastResort) markLastResortKyberPreKey(id)
    }
}
