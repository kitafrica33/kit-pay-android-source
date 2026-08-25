package com.kit.wallet

import com.kit.wallet.data.local.BeneficiaryContactDao
import com.kit.wallet.data.local.BeneficiaryContactEntity
import com.kit.wallet.data.repository.BeneficiaryContactDirectory
import com.kit.wallet.data.repository.KeyedBeneficiaryPhoneIdentity
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BeneficiaryContactDirectoryTest {
    private val identities = KeyedBeneficiaryPhoneIdentity {
        SecretKeySpec(ByteArray(32) { 0x5a.toByte() }, "HmacSHA256")
    }

    @Test
    fun `Room receives a keyed full-number digest and never raw digits`() = runTest {
        val session = testSession("account-a")
        val dao = FakeBeneficiaryContactDao()
        val directory = BeneficiaryContactDirectory(
            dao,
            MutableTestSessionStore(session),
            identities,
            backgroundScope,
        )
        runCurrent()

        directory.remember(session.fence(), "beneficiary-1", "+256 712 345 678")
        runCurrent()

        val row = dao.rowsFor(session.cacheScopeId).single()
        assertEquals("beneficiary-1", row.beneficiaryId)
        assertEquals(64, row.phoneIdentity.length)
        assertTrue(row.phoneIdentity != "712345678")
        assertTrue(!row.phoneIdentity.contains("256712345678"))
        assertEquals(row.phoneIdentity, directory.phoneIdentityFor("beneficiary-1"))
    }

    @Test
    fun `same beneficiary id cannot expose account A link to account B`() = runTest {
        val accountA = testSession("account-a")
        val accountB = testSession("account-b")
        val identityA = requireNotNull(identities.digest("+256700000001"))
        val identityB = requireNotNull(identities.digest("+254700000001"))
        val dao = FakeBeneficiaryContactDao(
            link(accountA.cacheScopeId, "same-beneficiary", identityA),
            link(accountB.cacheScopeId, "same-beneficiary", identityB),
        )
        val sessions = MutableTestSessionStore(accountA)
        val directory = BeneficiaryContactDirectory(dao, sessions, identities, backgroundScope)
        runCurrent()
        assertEquals(identityA, directory.phoneIdentityFor("same-beneficiary"))

        sessions.save(accountB)
        runCurrent()

        assertEquals(identityB, directory.phoneIdentityFor("same-beneficiary"))
    }

    @Test
    fun `logout and account replacement fence queued writes`() = runTest {
        val accountA = testSession("account-a")
        val accountB = testSession("account-b")
        val sessions = MutableTestSessionStore(accountA)
        val dao = FakeBeneficiaryContactDao()
        val directory = BeneficiaryContactDirectory(dao, sessions, identities, backgroundScope)
        runCurrent()

        directory.remember(accountA.fence(), "beneficiary-a", "+256700000001")
        sessions.save(accountB)
        runCurrent()

        assertTrue(dao.rowsFor(accountA.cacheScopeId).isEmpty())
        assertTrue(dao.rowsFor(accountB.cacheScopeId).isEmpty())
        assertNull(directory.phoneIdentityFor("beneficiary-a"))
    }

    @Test
    fun `malformed id or phone is dropped before an async write`() = runTest {
        val session = testSession("account-a")
        val dao = FakeBeneficiaryContactDao()
        val directory = BeneficiaryContactDirectory(
            dao,
            MutableTestSessionStore(session),
            identities,
            backgroundScope,
        )

        directory.remember(session.fence(), "bad/id", "+256700000001")
        directory.remember(session.fence(), "beneficiary-1", "*****0001")
        directory.remember(session.fence(), " ", "+256700000001")
        runCurrent()

        assertTrue(dao.rowsFor(session.cacheScopeId).isEmpty())
    }

    private fun link(owner: String, id: String, identity: String) = BeneficiaryContactEntity(
        ownerScopeId = owner,
        beneficiaryId = id,
        phoneIdentity = identity,
        updatedAtEpochMillis = 1L,
    )
}

private class FakeBeneficiaryContactDao(
    vararg initial: BeneficiaryContactEntity,
) : BeneficiaryContactDao {
    private val rows = MutableStateFlow(initial.associateBy { it.ownerScopeId to it.beneficiaryId })

    override fun observeForOwner(ownerScopeId: String): Flow<List<BeneficiaryContactEntity>> =
        rows.map { stored -> stored.values.filter { it.ownerScopeId == ownerScopeId } }

    override suspend fun put(links: List<BeneficiaryContactEntity>) {
        rows.value = rows.value + links.associateBy { it.ownerScopeId to it.beneficiaryId }
    }

    override suspend fun forget(ownerScopeId: String, beneficiaryIds: List<String>) {
        rows.value = rows.value - beneficiaryIds.mapTo(mutableSetOf()) { ownerScopeId to it }
    }

    override suspend fun clear() {
        rows.value = emptyMap()
    }

    fun rowsFor(ownerScopeId: String): List<BeneficiaryContactEntity> =
        rows.value.values.filter { it.ownerScopeId == ownerScopeId }
}
