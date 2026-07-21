package com.kit.wallet.data.repository

import com.kit.wallet.data.remote.ApiCallExecutor
import com.kit.wallet.data.remote.CommunicationBlockDto
import com.kit.wallet.data.remote.CommunicationPreferencesDto
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.remote.UpdateCommunicationPreferencesRequest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class CommunicationPreferences(
    val version: Long,
    val phoneDiscoverable: Boolean,
    val directMessageRequestsEnabled: Boolean,
    val incomingCallsEnabled: Boolean,
    val updatedAt: String?,
) {
    companion object {
        val DEFAULT_OFF = CommunicationPreferences(
            version = 0,
            phoneDiscoverable = false,
            directMessageRequestsEnabled = false,
            incomingCallsEnabled = false,
            updatedAt = null,
        )
    }
}

data class CommunicationPreferenceChanges(
    val phoneDiscoverable: Boolean? = null,
    val directMessageRequestsEnabled: Boolean? = null,
    val incomingCallsEnabled: Boolean? = null,
) {
    init {
        require(
            phoneDiscoverable != null ||
                directMessageRequestsEnabled != null ||
                incomingCallsEnabled != null,
        ) { "At least one communication preference must change" }
    }
}

data class BlockedCommunicationUser(
    val userId: String,
    val blockedAt: String?,
)

interface CommunicationPrivacyRepository {
    suspend fun preferences(): CommunicationPreferences

    suspend fun updatePreferences(
        expectedVersion: Long,
        changes: CommunicationPreferenceChanges,
    ): CommunicationPreferences

    suspend fun blockedUsers(): List<BlockedCommunicationUser>

    suspend fun block(userId: String): BlockedCommunicationUser

    suspend fun unblock(userId: String)
}

@Singleton
class RemoteCommunicationPrivacyRepository @Inject constructor(
    private val api: KitWalletApi,
    private val apiCalls: ApiCallExecutor,
) : CommunicationPrivacyRepository {
    override suspend fun preferences(): CommunicationPreferences =
        apiCalls.execute { api.communicationPreferences() }.toDomain()

    override suspend fun updatePreferences(
        expectedVersion: Long,
        changes: CommunicationPreferenceChanges,
    ): CommunicationPreferences {
        require(expectedVersion > 0) { "Refresh communication preferences before updating them" }
        return apiCalls.execute {
            api.updateCommunicationPreferences(
                UpdateCommunicationPreferencesRequest(
                    version = expectedVersion,
                    phoneDiscoverable = changes.phoneDiscoverable,
                    directMessageRequestsEnabled = changes.directMessageRequestsEnabled,
                    incomingCallsEnabled = changes.incomingCallsEnabled,
                ),
            )
        }.toDomain()
    }

    override suspend fun blockedUsers(): List<BlockedCommunicationUser> {
        val blocks = linkedMapOf<String, BlockedCommunicationUser>()
        val seenCursors = mutableSetOf<String>()
        var cursor: String? = null

        repeat(MAX_BLOCK_PAGES) {
            val page = apiCalls.execute { api.communicationBlocks(cursor = cursor) }
            page.items.orEmpty()
                .mapNotNull { block -> block.toActiveDomainOrNull() }
                .forEach { block -> blocks[block.userId] = block }

            if (page.page?.hasMore != true) return blocks.values.toList()
            val nextCursor = page.page.nextCursor?.trim().orEmpty()
            check(nextCursor.isNotEmpty() && seenCursors.add(nextCursor)) {
                "Communication block pagination returned an invalid cursor"
            }
            cursor = nextCursor
        }

        error("Communication block list exceeded the safe pagination limit")
    }

    override suspend fun block(userId: String): BlockedCommunicationUser {
        val canonicalId = canonicalPublicUserId(userId)
        val response = apiCalls.execute { api.blockCommunicationUser(canonicalId) }
        check(response.blocked == true) { "The block response did not confirm the request" }
        val block = response.toActiveDomainOrNull()
            ?: error("The block response was incomplete")
        check(block.userId == canonicalId) { "The block response identified a different user" }
        return block
    }

    override suspend fun unblock(userId: String) {
        val canonicalId = canonicalPublicUserId(userId)
        val response = apiCalls.execute { api.unblockCommunicationUser(canonicalId) }
        val responseId = response.userId?.let(::canonicalPublicUserId)
        check(responseId == canonicalId && response.blocked == false) {
            "The unblock response did not confirm the requested user"
        }
    }

    private fun CommunicationPreferencesDto.toDomain(): CommunicationPreferences {
        val responseVersion = version
        check(responseVersion != null && responseVersion > 0) {
            "Communication preferences omitted a valid version"
        }
        return CommunicationPreferences(
            version = responseVersion,
            phoneDiscoverable = phoneDiscoverable == true,
            directMessageRequestsEnabled = directMessageRequestsEnabled == true,
            incomingCallsEnabled = incomingCallsEnabled == true,
            updatedAt = updatedAt,
        )
    }

    private fun CommunicationBlockDto.toActiveDomainOrNull(): BlockedCommunicationUser? {
        if (blocked == false) return null
        val canonicalId = userId?.let { runCatching { canonicalPublicUserId(it) }.getOrNull() }
            ?: return null
        return BlockedCommunicationUser(canonicalId, blockedAt)
    }

    private companion object {
        const val MAX_BLOCK_PAGES = 20
    }
}

internal fun canonicalPublicUserId(value: String): String {
    val trimmed = value.trim()
    return runCatching { UUID.fromString(trimmed).toString() }
        .getOrElse { throw IllegalArgumentException("Choose a valid Kit Pay user", it) }
}
