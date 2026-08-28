package com.kit.wallet.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kit.wallet.data.repository.ChatRepository
import com.kit.wallet.data.repository.ContactRepository
import com.kit.wallet.data.repository.KycRepository
import com.kit.wallet.data.repository.RemoteStarterChecklistRepository
import com.kit.wallet.data.repository.ServerMilestoneStatus
import com.kit.wallet.data.repository.ServerStarterChecklist
import com.kit.wallet.data.repository.ServerStarterMilestone
import com.kit.wallet.data.repository.UserRepository
import com.kit.wallet.data.repository.WalletRepository
import com.kit.wallet.data.repository.WalletSyncRepository
import com.kit.wallet.data.session.SessionStore
import com.kit.wallet.ui.model.Contact
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    userRepo: UserRepository,
    walletRepo: WalletRepository,
    contactRepo: ContactRepository,
    chatRepo: ChatRepository,
    kycRepo: KycRepository,
    sessions: SessionStore,
    private val milestones: StarterMilestones,
    serverChecklist: RemoteStarterChecklistRepository,
    private val walletSync: WalletSyncRepository,
) : ViewModel() {

    val profile = userRepo.profile
    val balanceMinor = walletRepo.balanceMinor

    /**
     * Whether this account has ever moved money, and ever sent a message. The synced
     * caches are the live evidence, but both are session-scoped and cleared on logout and
     * the wallet cache is page-replaced, so each observation is recorded as a durable
     * account-bound milestone the moment it is made — monotonic, never unset. Every piece
     * of evidence carries its owning account from the emission that produced it and is
     * revalidated against the signed-in account at the moment of recording, so an account
     * switch racing a stale cache emission can never mark the new account. Where the
     * backend advertises the `starter_checklist` capability, its validated account-wide
     * answer joins the local evidence under an exact-ownership check. Fail closed
     * everywhere: no session, no owner, no cache, no milestone → false.
     */
    private val firstTransactionMade = combine(
        walletRepo.accountTransactions,
        sessions.session,
        milestones.revision,
        serverChecklist.checklist,
    ) { owned, session, _, server ->
        val accountId = session?.accountId
        val live = StarterChecklistPolicy.ownedEvidenceQualifies(
            evidenceOwnerAccountId = owned.ownerAccountId,
            currentAccountId = accountId,
            qualifies = owned.transactions.any(StarterChecklistPolicy::countsAsFirstTransaction),
        )
        val serverFact =
            server.provesForAccount(accountId, ServerStarterMilestone.MAKE_FIRST_TRANSACTION)
        if (live || serverFact) milestones.record(StarterMilestone.FIRST_TRANSACTION, accountId)
        live || serverFact || milestones.recorded(StarterMilestone.FIRST_TRANSACTION, accountId)
    }

    private val firstMessageSent = combine(
        chatRepo.sentMessageEvidence,
        sessions.session,
        milestones.revision,
        serverChecklist.checklist,
    ) { evidence, session, _, server ->
        val accountId = session?.accountId
        val live = StarterChecklistPolicy.ownedEvidenceQualifies(
            evidenceOwnerAccountId = evidence.ownerAccountId,
            currentAccountId = accountId,
            qualifies = evidence.value,
        )
        val serverFact =
            server.provesForAccount(accountId, ServerStarterMilestone.SEND_FIRST_MESSAGE)
        if (live || serverFact) milestones.record(StarterMilestone.FIRST_MESSAGE, accountId)
        live || serverFact || milestones.recorded(StarterMilestone.FIRST_MESSAGE, accountId)
    }

    /**
     * The new-account starter checklist, derived from real state only: the live KYC status
     * — the same source the verification screen refreshes, with the profile label only as
     * the offline fallback — the durable chat store's own first-message evidence, and the
     * recorded first-transaction fact above. Every source fails closed — data that has not
     * loaded leaves its step incomplete — and the initial value renders immediately, so
     * home never waits on any of it.
     */
    internal val starterChecklist = combine(
        kycRepo.status,
        userRepo.profile,
        firstMessageSent,
        firstTransactionMade,
    ) { liveKyc, profile, hasSentMessage, firstTransaction ->
        StarterChecklistPolicy.checklist(
            liveKyc = liveKyc,
            profileKycLabel = profile.kycLabel,
            hasSentMessage = hasSentMessage,
            firstTransactionMade = firstTransaction,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        StarterChecklistPolicy.checklist(
            liveKyc = null,
            profileKycLabel = null,
            hasSentMessage = false,
            firstTransactionMade = false,
        ),
    )

    private val _refreshing = MutableStateFlow(false)
    val refreshing = _refreshing.asStateFlow()

    val recentTransactions = walletRepo.transactions
        .map { it.take(5) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val favorites = contactRepo.contacts
        // Every displayed favorite must be able to reach the preselected Send amount screen.
        // Invite-only or wallet-less contacts remain available in the full recipient picker.
        .map(::sendableHomeFavorites)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var visiblePollJob: kotlinx.coroutines.Job? = null

    fun refresh() {
        if (_refreshing.value) return
        viewModelScope.launch {
            _refreshing.value = true
            try {
                walletSync.refresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Offline or transient server errors keep showing the durable cached balance.
            } finally {
                _refreshing.value = false
            }
        }
    }

    /**
     * The backend has no wallet push or realtime channel, so an incoming credit is only visible
     * on the next poll. While the home screen is on screen we poll quietly so received money
     * appears without a manual gesture; the loop stops as soon as the screen leaves composition.
     */
    fun setHomeVisible(visible: Boolean) {
        if (!visible) {
            visiblePollJob?.cancel()
            visiblePollJob = null
            return
        }
        if (visiblePollJob?.isActive == true) return
        visiblePollJob = viewModelScope.launch {
            while (true) {
                delay(VISIBLE_POLL_INTERVAL_MILLIS)
                try {
                    walletSync.refresh()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Retry on the next tick; the cached balance stays authoritative offline.
                }
            }
        }
    }

    private companion object {
        const val VISIBLE_POLL_INTERVAL_MILLIS = 20_000L
    }
}

internal fun sendableHomeFavorites(contacts: List<Contact>): List<Contact> = contacts.filter {
    it.favorite && it.id.isNotBlank() && it.isKitUser && !it.receivingWalletId.isNullOrBlank()
}

/**
 * Whether the validated server checklist proves [milestone] for exactly the signed-in
 * account — byte-for-byte identifier equality, no repair. Null — capability off, fetch
 * failed, response rejected, or session just changed — proves nothing; so does another
 * owner, an ineligible account, or any status short of exactly completed. `pending` rows
 * are carried but confirm nothing. [ServerStarterMilestone.VERIFY_IDENTITY] is
 * deliberately never consumed here: identity truth stays with the fenced KYC pipeline,
 * and this checklist can neither complete nor un-complete that step.
 */
internal fun ServerStarterChecklist?.provesForAccount(
    currentAccountId: String?,
    milestone: ServerStarterMilestone,
): Boolean = this != null &&
    eligible &&
    !currentAccountId.isNullOrEmpty() &&
    ownerAccountId == currentAccountId &&
    milestones[milestone]?.status == ServerMilestoneStatus.COMPLETED
