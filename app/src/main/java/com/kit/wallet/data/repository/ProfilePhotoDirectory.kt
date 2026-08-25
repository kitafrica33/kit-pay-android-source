package com.kit.wallet.data.repository

import com.kit.wallet.data.local.ProfilePhotoDao
import com.kit.wallet.data.local.ProfilePhotoEntity
import com.kit.wallet.data.media.isTrustedProfileAvatarUrl
import com.kit.wallet.data.session.SessionFence
import com.kit.wallet.data.session.SessionStore
import com.kit.wallet.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ProfilePhotoSnapshot internal constructor(
    internal val ownerScopeId: String?,
    internal val values: Map<String, String>,
)

/**
 * Authenticated-owner-scoped directory of profile-photo URLs.
 *
 * Rows are selected by the current cache epoch, not merely cleared eventually on logout. Every
 * asynchronous write also carries the exact [SessionFence] of the data that produced it and runs
 * through [SessionStore.withCurrentSession]. A late contact/chat response therefore cannot put an
 * old account's face back after logout or after another account claims the phone.
 */
@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class ProfilePhotoDirectory @Inject constructor(
    private val dao: ProfilePhotoDao,
    private val sessions: SessionStore,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    /** Every trusted URL tagged with the exact owner under which Room produced it. */
    val snapshots: StateFlow<ProfilePhotoSnapshot> = sessions.session.flatMapLatest { session ->
        if (session == null) {
            flowOf(ProfilePhotoSnapshot(null, emptyMap()))
        } else {
            // Emit empty before subscribing so StateFlow never carries account A's map through the
            // hand-off while Room is preparing account B's first result.
            flow {
                emit(ProfilePhotoSnapshot(session.cacheScopeId, emptyMap()))
                dao.observeForOwner(session.cacheScopeId).collect { stored ->
                    emit(
                        ProfilePhotoSnapshot(
                            session.cacheScopeId,
                            stored.asSequence()
                                .filter { it.ownerScopeId == session.cacheScopeId }
                                .filter { isTrustedProfileAvatarUrl(it.avatarUrl) }
                                .associate { it.userId to it.avatarUrl },
                        ),
                    )
                }
            }
        }
    }.stateIn(scope, SharingStarted.Eagerly, ProfilePhotoSnapshot(null, emptyMap()))

    /** Revalidates ownership synchronously, including before the session-flow collector catches up. */
    fun currentPhotos(snapshot: ProfilePhotoSnapshot = snapshots.value): Map<String, String> =
        snapshot.values.takeIf {
            snapshot.ownerScopeId != null &&
                sessions.current()?.cacheScopeId == snapshot.ownerScopeId
        }.orEmpty()

    fun photoFor(userId: String?): String? =
        canonicalDirectoryUserId(userId)?.let(currentPhotos()::get)

    /**
     * Records what data authenticated under [expected] revealed.
     *
     * [complete] means a named user without a valid URL removed their photo. It never means that
     * users absent from [known] should be erased.
     */
    fun learn(expected: SessionFence, known: Map<String, String?>, complete: Boolean) {
        val fresh = mutableMapOf<String, String>()
        val gone = mutableListOf<String>()
        known.forEach { (rawUserId, rawUrl) ->
            val userId = canonicalDirectoryUserId(rawUserId) ?: return@forEach
            val url = rawUrl?.trim()?.takeIf(String::isNotEmpty)
            when {
                url != null && isTrustedProfileAvatarUrl(url) -> fresh[userId] = url
                complete -> gone += userId
            }
        }
        if (fresh.isEmpty() && gone.isEmpty()) return

        scope.launch {
            try {
                sessions.withCurrentSession(expected) { current ->
                    check(current.cacheScopeId == expected.cacheScopeId)
                    val now = System.currentTimeMillis()
                    if (fresh.isNotEmpty()) {
                        dao.put(
                            fresh.map { (userId, url) ->
                                ProfilePhotoEntity(
                                    ownerScopeId = current.cacheScopeId,
                                    userId = userId,
                                    avatarUrl = url,
                                    updatedAtEpochMillis = now,
                                )
                            },
                        )
                    }
                    if (gone.isNotEmpty()) dao.forget(current.cacheScopeId, gone)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Display cache only. A stale fence or failed disk write yields initials and must
                // never fail the contact/chat/profile operation that taught us about the photo.
            }
        }
    }
}

internal fun canonicalDirectoryUserId(value: String?): String? {
    val userId = value?.trim()?.lowercase()?.takeIf(String::isNotEmpty) ?: return null
    if (userId.length > 256 || userId.any(Char::isISOControl)) return null
    return userId
}
