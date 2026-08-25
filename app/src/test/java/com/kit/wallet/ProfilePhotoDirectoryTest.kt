package com.kit.wallet

import com.kit.wallet.data.local.ProfilePhotoDao
import com.kit.wallet.data.local.ProfilePhotoEntity
import com.kit.wallet.data.repository.ProfilePhotoDirectory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProfilePhotoDirectoryTest {
    private val apiHost = BuildConfig.KIT_WALLET_BASE_URL.toHttpUrl().host
    private val graceUrl = "https://$apiHost/profile/grace/avatar/one"
    private val graceNewUrl = "https://$apiHost/profile/grace/avatar/two"
    private val okelloUrl = "https://$apiHost/profile/okello/avatar/one"

    @Test fun `current owner's photos are available before a network call`() = runTest {
        val session = testSession("account-a")
        val dao = FakeProfilePhotoDao(
            photo(session.cacheScopeId, "grace", graceUrl),
            photo(session.cacheScopeId, "okello", okelloUrl),
        )
        val directory = ProfilePhotoDirectory(
            dao,
            MutableTestSessionStore(session),
            backgroundScope,
        )
        runCurrent()

        assertEquals(graceUrl, directory.photoFor("grace"))
        assertEquals(okelloUrl, directory.photoFor("okello"))
    }

    @Test fun `rows are isolated across account A and B`() = runTest {
        val accountA = testSession("account-a")
        val accountB = testSession("account-b")
        val sessions = MutableTestSessionStore(accountA)
        val dao = FakeProfilePhotoDao(
            photo(accountA.cacheScopeId, "same-user", graceUrl),
            photo(accountB.cacheScopeId, "same-user", okelloUrl),
        )
        val directory = ProfilePhotoDirectory(dao, sessions, backgroundScope)
        runCurrent()
        assertEquals(graceUrl, directory.photoFor("same-user"))

        sessions.save(accountB)
        runCurrent()

        assertEquals(okelloUrl, directory.photoFor("same-user"))
        assertNull(directory.photoFor("grace"))
    }

    @Test fun `stored photo pointing off the api is not served`() = runTest {
        val session = testSession("account-a")
        val dao = FakeProfilePhotoDao(
            photo(session.cacheScopeId, "grace", "https://tracker.example.net/x"),
        )
        val directory = ProfilePhotoDirectory(dao, MutableTestSessionStore(session), backgroundScope)
        runCurrent()

        assertNull(directory.photoFor("grace"))
    }

    @Test fun `a glimpse adds and corrects but never forgets`() = runTest {
        val session = testSession("account-a")
        val dao = FakeProfilePhotoDao(photo(session.cacheScopeId, "grace", graceUrl))
        val directory = ProfilePhotoDirectory(dao, MutableTestSessionStore(session), backgroundScope)
        runCurrent()

        directory.learn(
            session.fence(),
            mapOf("grace" to graceNewUrl, "okello" to okelloUrl),
            complete = false,
        )
        runCurrent()
        assertEquals(graceNewUrl, directory.photoFor("grace"))
        assertEquals(okelloUrl, directory.photoFor("okello"))

        directory.learn(session.fence(), mapOf("grace" to graceNewUrl), complete = false)
        runCurrent()
        assertEquals(okelloUrl, directory.photoFor("okello"))
    }

    @Test fun `a complete result forgets a named photo its owner removed`() = runTest {
        val session = testSession("account-a")
        val dao = FakeProfilePhotoDao(
            photo(session.cacheScopeId, "grace", graceUrl),
            photo(session.cacheScopeId, "okello", okelloUrl),
        )
        val directory = ProfilePhotoDirectory(dao, MutableTestSessionStore(session), backgroundScope)
        runCurrent()

        directory.learn(
            session.fence(),
            mapOf("grace" to graceUrl, "okello" to null),
            complete = true,
        )
        runCurrent()

        assertEquals(graceUrl, directory.photoFor("grace"))
        assertNull(directory.photoFor("okello"))
    }

    @Test fun `hostile urls and malformed user ids are never remembered`() = runTest {
        val session = testSession("account-a")
        val directory = ProfilePhotoDirectory(
            FakeProfilePhotoDao(),
            MutableTestSessionStore(session),
            backgroundScope,
        )
        runCurrent()

        directory.learn(
            session.fence(),
            mapOf(
                "grace" to "https://tracker.example.net/beacon.gif",
                "bad\nuser" to graceUrl,
                " " to graceUrl,
            ),
            complete = false,
        )
        runCurrent()

        assertNull(directory.photoFor("grace"))
        assertNull(directory.photoFor("bad\nuser"))
    }

    @Test fun `a user id is canonicalized within its owner`() = runTest {
        val session = testSession("account-a")
        val directory = ProfilePhotoDirectory(
            FakeProfilePhotoDao(),
            MutableTestSessionStore(session),
            backgroundScope,
        )
        runCurrent()

        directory.learn(session.fence(), mapOf("  GRACE  " to graceUrl), complete = false)
        runCurrent()

        assertEquals(graceUrl, directory.photoFor("grace"))
        assertEquals(graceUrl, directory.photoFor(" Grace "))
        assertNull(directory.photoFor(null))
        assertNull(directory.photoFor("   "))
    }

    @Test fun `logout fences a queued asynchronous write`() = runTest {
        val session = testSession("account-a")
        val sessions = MutableTestSessionStore(session)
        val dao = FakeProfilePhotoDao()
        val directory = ProfilePhotoDirectory(dao, sessions, backgroundScope)
        runCurrent()

        directory.learn(session.fence(), mapOf("grace" to graceUrl), complete = false)
        sessions.clear()
        runCurrent()

        assertTrue(dao.rowsFor(session.cacheScopeId).isEmpty())
        assertNull(directory.photoFor("grace"))
    }

    @Test fun `clearing rows clears the current directory`() = runTest {
        val session = testSession("account-a")
        val dao = FakeProfilePhotoDao(photo(session.cacheScopeId, "grace", graceUrl))
        val directory = ProfilePhotoDirectory(dao, MutableTestSessionStore(session), backgroundScope)
        runCurrent()
        assertEquals(graceUrl, directory.photoFor("grace"))

        dao.clear()
        runCurrent()

        assertNull(directory.photoFor("grace"))
    }

    private fun photo(owner: String, userId: String, url: String) = ProfilePhotoEntity(
        ownerScopeId = owner,
        userId = userId,
        avatarUrl = url,
        updatedAtEpochMillis = 1L,
    )
}

internal class FakeProfilePhotoDao(vararg stored: ProfilePhotoEntity) : ProfilePhotoDao {
    private val rows = MutableStateFlow(stored.associateBy { it.ownerScopeId to it.userId })

    override fun observeForOwner(ownerScopeId: String): Flow<List<ProfilePhotoEntity>> =
        rows.map { values -> values.values.filter { it.ownerScopeId == ownerScopeId } }

    override suspend fun put(photos: List<ProfilePhotoEntity>) {
        rows.value = rows.value + photos.associateBy { it.ownerScopeId to it.userId }
    }

    override suspend fun forget(ownerScopeId: String, userIds: List<String>) {
        val keys = userIds.mapTo(mutableSetOf()) { ownerScopeId to it }
        rows.value = rows.value - keys
    }

    override suspend fun clear() {
        rows.value = emptyMap()
    }

    fun rowsFor(ownerScopeId: String): List<ProfilePhotoEntity> =
        rows.value.values.filter { it.ownerScopeId == ownerScopeId }
}
