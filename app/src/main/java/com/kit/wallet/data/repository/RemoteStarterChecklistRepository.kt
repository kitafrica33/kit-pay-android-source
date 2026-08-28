package com.kit.wallet.data.repository

import com.kit.wallet.data.remote.ApiCallExecutor
import com.kit.wallet.data.remote.KitFeature
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.remote.StarterChecklistDto
import com.kit.wallet.data.session.SessionInvalidatedException
import com.kit.wallet.data.session.SessionStore
import com.kit.wallet.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** The canonical onboarding milestone vocabulary. Anything else on the wire is unknown. */
enum class ServerStarterMilestone(val key: String) {
    VERIFY_IDENTITY("verify_identity"),
    SEND_FIRST_MESSAGE("send_first_message"),
    MAKE_FIRST_TRANSACTION("make_first_transaction"),
}

/**
 * The canonical milestone statuses. `pending` is accepted — the row parses and is carried
 * — but confirms nothing; only `completed` ever proves a milestone.
 */
enum class ServerMilestoneStatus(val wire: String) {
    COMPLETED("completed"),
    PENDING("pending"),
}

/** One validated milestone row: its status and the (nullable) completion timestamp. */
data class ServerStarterMilestoneState(
    val status: ServerMilestoneStatus,
    val completedAt: String?,
)

/**
 * A server starter checklist that survived [validatedStarterChecklist]: it names exactly
 * the account that asked — byte-for-byte, no whitespace or case repair — that account is
 * eligible, the policy version is a positive revision, and every row used a known key
 * exactly once with a known status. Consumers still re-check ownership at the moment of
 * use, because a snapshot can outlive the session it served.
 */
data class ServerStarterChecklist(
    val ownerAccountId: String,
    val eligible: Boolean,
    val policyVersion: Int,
    val milestones: Map<ServerStarterMilestone, ServerStarterMilestoneState>,
)

/**
 * Validates one parsed checklist against the account that asked for it. Any violation
 * rejects the whole response — null, nothing proven, never a salvaged subset:
 *
 *  - no signed-in account id, or `account_id` not exactly equal to it (no trimming, no
 *    case folding: identifiers are matched, not repaired);
 *  - `eligible` anything but true;
 *  - `policy_version` below 1;
 *  - a milestone key outside [ServerStarterMilestone], a key appearing twice, or a status
 *    outside [ServerMilestoneStatus] — a vocabulary this build does not fully understand
 *    is a contract it must not draw conclusions from.
 *
 * A `pending` row is valid and carried, but only `completed` confirms anything.
 */
internal fun validatedStarterChecklist(
    dto: StarterChecklistDto,
    askingAccountId: String?,
): ServerStarterChecklist? {
    val asking = askingAccountId?.takeIf(String::isNotEmpty) ?: return null
    if (dto.accountId != asking) return null
    if (!dto.eligible) return null
    if (dto.policyVersion < 1) return null
    val milestones = mutableMapOf<ServerStarterMilestone, ServerStarterMilestoneState>()
    for (row in dto.milestones) {
        val milestone = ServerStarterMilestone.entries.firstOrNull { it.key == row.key }
            ?: return null
        val status = ServerMilestoneStatus.entries.firstOrNull { it.wire == row.status }
            ?: return null
        if (milestones.put(milestone, ServerStarterMilestoneState(status, row.completedAt)) != null) {
            return null
        }
    }
    return ServerStarterChecklist(
        ownerAccountId = dto.accountId,
        eligible = dto.eligible,
        policyVersion = dto.policyVersion,
        milestones = milestones,
    )
}

/**
 * The optional server-owned starter checklist, behind the `starter_checklist` capability.
 *
 * Device-local milestone evidence is honest but narrow — it only knows what this install
 * has seen. When the backend advertises [KitFeature.STARTER_CHECKLIST] as exactly `true`,
 * this repository asks `/api/v1/onboarding/starter-checklist` for the account-wide answer.
 * Everything about it fails closed and defaults off:
 *
 *  - capability missing, null, or `false` → the onboarding route is never called and the
 *    published value is null, leaving completion to local evidence alone;
 *  - a response that fails to parse (all fields are required) or fails
 *    [validatedStarterChecklist] → null, whole response, no salvage;
 *  - the request is session-fenced at the transport, and the answer is published only if
 *    the session that asked is still the one signed in — plus it carries its owner, so the
 *    consumer re-checks ownership at the moment of use;
 *  - every session transition clears the published value before anything else runs.
 */
@Singleton
class RemoteStarterChecklistRepository @Inject constructor(
    private val api: KitWalletApi,
    private val apiCalls: ApiCallExecutor,
    private val sessions: SessionStore,
    @ApplicationScope scope: CoroutineScope,
) {
    private val mutableChecklist = MutableStateFlow<ServerStarterChecklist?>(null)
    val checklist: StateFlow<ServerStarterChecklist?> = mutableChecklist.asStateFlow()

    init {
        scope.launch {
            sessions.session.map { it?.sessionId }.distinctUntilChanged().collectLatest { sessionId ->
                // Cleared on EVERY transition, replacement included, before any refresh:
                // account A's server facts must never survive into account B's view.
                mutableChecklist.value = null
                if (sessionId != null) runCatching { refresh() }
            }
        }
    }

    /**
     * Re-reads the capability and, only if advertised, the server checklist. Throws
     * [SessionInvalidatedException] instead of publishing when the session that asked is
     * no longer the one signed in.
     */
    suspend fun refresh() {
        val asked = sessions.current() ?: throw SessionInvalidatedException()
        val advertised = apiCalls.execute { api.capabilities() }
            .features.orEmpty()[KitFeature.STARTER_CHECKLIST] == true
        if (!advertised) {
            if (sessions.current()?.sessionId == asked.sessionId) {
                mutableChecklist.value = null
            }
            return
        }
        val dto = apiCalls.execute { api.starterChecklist(asked.fence()) }
        if (sessions.current()?.sessionId != asked.sessionId) throw SessionInvalidatedException()
        mutableChecklist.value = validatedStarterChecklist(dto, asked.accountId)
    }
}
