package com.kit.wallet.data.repository

import com.kit.wallet.data.local.BeneficiaryContactDao
import com.kit.wallet.data.local.BeneficiaryContactEntity
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class BeneficiaryContactSnapshot internal constructor(
    internal val ownerScopeId: String?,
    internal val values: Map<String, String>,
)

/**
 * Display-only association between a payout destination and a contact phone identity.
 *
 * Room receives only a device-keyed HMAC of the full canonical international number. Every row is
 * owned by an authenticated cache epoch, and every write is serialized through that epoch's
 * [SessionFence]. A suffix, masked number, obsolete login, or missing key can only produce initials.
 */
@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class BeneficiaryContactDirectory @Inject constructor(
    private val dao: BeneficiaryContactDao,
    private val sessions: SessionStore,
    private val identities: BeneficiaryPhoneIdentity,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    /** Beneficiary links tagged with the exact owner under which Room produced them. */
    val snapshots: StateFlow<BeneficiaryContactSnapshot> = sessions.session.flatMapLatest { session ->
        if (session == null) {
            flowOf(BeneficiaryContactSnapshot(null, emptyMap()))
        } else {
            flow {
                emit(BeneficiaryContactSnapshot(session.cacheScopeId, emptyMap()))
                dao.observeForOwner(session.cacheScopeId).collect { stored ->
                    emit(
                        BeneficiaryContactSnapshot(
                            session.cacheScopeId,
                            stored.asSequence()
                                .filter { it.ownerScopeId == session.cacheScopeId }
                                .filter { isCanonicalBeneficiaryPhoneIdentity(it.phoneIdentity) }
                                .associate { it.beneficiaryId to it.phoneIdentity },
                        ),
                    )
                }
            }
        }
    }.stateIn(scope, SharingStarted.Eagerly, BeneficiaryContactSnapshot(null, emptyMap()))

    /** Revalidates ownership synchronously, before a session-flow collector can lag a switch. */
    fun currentLinks(
        snapshot: BeneficiaryContactSnapshot = snapshots.value,
    ): Map<String, String> = snapshot.values.takeIf {
        snapshot.ownerScopeId != null && sessions.current()?.cacheScopeId == snapshot.ownerScopeId
    }.orEmpty()

    fun phoneIdentityFor(beneficiaryId: String?): String? =
        canonicalBeneficiaryId(beneficiaryId)?.let(currentLinks()::get)

    /** Computes an in-memory comparison value; the canonical number is never returned or stored. */
    fun identityForPhone(phoneNumber: String?): String? = identities.digest(phoneNumber)

    /** Records an exact number learned by work authenticated under [expected]. */
    fun remember(expected: SessionFence, beneficiaryId: String, phoneNumber: String) {
        val id = canonicalBeneficiaryId(beneficiaryId) ?: return
        val phoneIdentity = identities.digest(phoneNumber) ?: return
        scope.launch {
            try {
                sessions.withCurrentSession(expected) { current ->
                    check(current.cacheScopeId == expected.cacheScopeId)
                    dao.put(
                        listOf(
                            BeneficiaryContactEntity(
                                ownerScopeId = current.cacheScopeId,
                                beneficiaryId = id,
                                phoneIdentity = phoneIdentity,
                                updatedAtEpochMillis = System.currentTimeMillis(),
                            ),
                        ),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The destination remains saved. Losing a display-only face is safer than letting
                // an obsolete session write into a replacement account's namespace.
            }
        }
    }

    /** Drops only links named by authenticated work from [expected]. */
    fun forget(expected: SessionFence, beneficiaryIds: Collection<String>) {
        val gone = beneficiaryIds.mapNotNull(::canonicalBeneficiaryId).distinct()
        if (gone.isEmpty()) return
        scope.launch {
            try {
                sessions.withCurrentSession(expected) { current ->
                    check(current.cacheScopeId == expected.cacheScopeId)
                    dao.forget(current.cacheScopeId, gone)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Display cache only; a later authoritative refresh can retry cleanup.
            }
        }
    }
}
