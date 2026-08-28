package com.kit.wallet

import com.kit.wallet.data.remote.ApiCallExecutor
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.remote.StarterChecklistDto
import com.kit.wallet.data.remote.StarterChecklistMilestoneDto
import com.kit.wallet.data.remote.StarterChecklistMilestoneDtoAdapter
import com.kit.wallet.data.repository.RemoteStarterChecklistRepository
import com.kit.wallet.data.repository.ServerMilestoneStatus
import com.kit.wallet.data.repository.ServerStarterChecklist
import com.kit.wallet.data.repository.ServerStarterMilestone
import com.kit.wallet.data.repository.ServerStarterMilestoneState
import com.kit.wallet.data.repository.validatedStarterChecklist
import com.kit.wallet.data.session.SessionInvalidatedException
import com.kit.wallet.data.session.SessionStore
import com.kit.wallet.feature.home.provesForAccount
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/**
 * The server starter checklist can mark onboarding milestones done account-wide, so this
 * pins its whole trust ladder. Contract: `account_id`, `eligible`, and integer
 * `policy_version` are required; `milestones` rows carry `key`, `status`, and a
 * `completed_at` key that is present even while null. Keys are `verify_identity`,
 * `send_first_message`, `make_first_transaction`; statuses are `completed` and `pending`.
 * Ownership is exact — no whitespace or case repair — and any unknown key, duplicate key,
 * or unknown status rejects the whole response. Nothing survives a session change.
 */
class RemoteStarterChecklistRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var api: KitWalletApi
    private lateinit var apiCalls: ApiCallExecutor

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        // Mirrors the production Moshi: the milestone-row adapter must sit ahead of the
        // reflective factory wherever this DTO is decoded, or `completed_at` presence
        // would go unenforced.
        val moshi = Moshi.Builder()
            .add(StarterChecklistMilestoneDtoAdapter())
            .add(KotlinJsonAdapterFactory())
            .build()
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(KitWalletApi::class.java)
        apiCalls = ApiCallExecutor(moshi)
    }

    @After
    fun tearDown() = server.shutdown()

    // ------------------------------------------------------------------
    // Contract validation: every reason to distrust a response, whole-response
    // ------------------------------------------------------------------

    @Test
    fun `a valid response is admitted with its exact rows`() {
        assertEquals(
            ServerStarterChecklist(
                ownerAccountId = "account-a",
                eligible = true,
                policyVersion = 3,
                milestones = mapOf(
                    ServerStarterMilestone.VERIFY_IDENTITY to
                        ServerStarterMilestoneState(ServerMilestoneStatus.PENDING, null),
                    ServerStarterMilestone.SEND_FIRST_MESSAGE to
                        ServerStarterMilestoneState(
                            ServerMilestoneStatus.COMPLETED,
                            "2026-08-01T10:00:00Z",
                        ),
                    ServerStarterMilestone.MAKE_FIRST_TRANSACTION to
                        ServerStarterMilestoneState(ServerMilestoneStatus.PENDING, null),
                ),
            ),
            validatedStarterChecklist(dto(), askingAccountId = "account-a"),
        )
    }

    @Test
    fun `ownership is exact - identifiers are matched, never repaired`() {
        assertNull(validatedStarterChecklist(dto(accountId = " account-a "), "account-a"))
        assertNull(validatedStarterChecklist(dto(accountId = "Account-A"), "account-a"))
        assertNull(validatedStarterChecklist(dto(accountId = "account-b"), "account-a"))
        assertNull(validatedStarterChecklist(dto(accountId = ""), "account-a"))
        assertNull(validatedStarterChecklist(dto(), askingAccountId = null))
        assertNull(validatedStarterChecklist(dto(), askingAccountId = ""))
    }

    @Test
    fun `an ineligible account's response is rejected whole`() {
        assertNull(validatedStarterChecklist(dto(eligible = false), "account-a"))
    }

    @Test
    fun `the policy version must be a revision of at least 1`() {
        assertNull(validatedStarterChecklist(dto(policyVersion = 0), "account-a"))
        assertNull(validatedStarterChecklist(dto(policyVersion = -3), "account-a"))
        assertEquals(1, validatedStarterChecklist(dto(policyVersion = 1), "account-a")?.policyVersion)
    }

    @Test
    fun `an unknown milestone key rejects the whole response, salvaging nothing`() {
        // A vocabulary this build does not fully understand is a contract it must not
        // draw conclusions from — even the valid completed row alongside is discarded.
        val futureKey = dto(
            milestones = listOf(
                row("send_first_message", "completed", "2026-08-01T10:00:00Z"),
                row("profile_photo_set", "completed", "2026-08-02T09:00:00Z"),
            ),
        )
        assertNull(validatedStarterChecklist(futureKey, "account-a"))

        // The retired flat-boolean names are exactly such unknown keys: rejection fixtures.
        val legacyNames = dto(
            milestones = listOf(
                row("first_message_sent", "completed", "2026-08-01T10:00:00Z"),
                row("first_transaction_made", "completed", "2026-08-01T10:00:00Z"),
            ),
        )
        assertNull(validatedStarterChecklist(legacyNames, "account-a"))

        assertNull(
            validatedStarterChecklist(
                dto(milestones = listOf(row("", "completed", "2026-08-01T10:00:00Z"))),
                "account-a",
            ),
        )
    }

    @Test
    fun `a duplicated milestone key rejects the whole response, even when rows agree`() {
        val duplicated = dto(
            milestones = listOf(
                row("send_first_message", "completed", "2026-08-01T10:00:00Z"),
                row("send_first_message", "completed", "2026-08-01T10:00:00Z"),
            ),
        )
        assertNull(validatedStarterChecklist(duplicated, "account-a"))
    }

    @Test
    fun `an unknown status rejects the whole response`() {
        for (status in listOf("Completed", "COMPLETED", "done", "in_progress", "")) {
            assertNull(
                "status '$status' must reject the response",
                validatedStarterChecklist(
                    dto(milestones = listOf(row("send_first_message", status, null))),
                    "account-a",
                ),
            )
        }
    }

    @Test
    fun `an empty milestones array is valid and proves nothing`() {
        val checklist = validatedStarterChecklist(dto(milestones = emptyList()), "account-a")
        assertEquals(emptyMap<ServerStarterMilestone, ServerStarterMilestoneState>(), checklist?.milestones)
        assertFalse(checklist.provesForAccount("account-a", ServerStarterMilestone.SEND_FIRST_MESSAGE))
    }

    // ------------------------------------------------------------------
    // The consumer-side check at the moment of use
    // ------------------------------------------------------------------

    @Test
    fun `only a completed row proves a milestone, and only for its exact owner`() {
        val checklist = validatedStarterChecklist(dto(), "account-a")

        assertTrue(checklist.provesForAccount("account-a", ServerStarterMilestone.SEND_FIRST_MESSAGE))
        // pending is accepted and carried, but confirms nothing
        assertFalse(checklist.provesForAccount("account-a", ServerStarterMilestone.MAKE_FIRST_TRANSACTION))
        assertFalse(checklist.provesForAccount("account-a", ServerStarterMilestone.VERIFY_IDENTITY))
        assertFalse(checklist.provesForAccount("account-b", ServerStarterMilestone.SEND_FIRST_MESSAGE))
        assertFalse(checklist.provesForAccount("Account-A", ServerStarterMilestone.SEND_FIRST_MESSAGE))
        assertFalse(checklist.provesForAccount(null, ServerStarterMilestone.SEND_FIRST_MESSAGE))
        assertFalse(checklist.provesForAccount("", ServerStarterMilestone.SEND_FIRST_MESSAGE))
        assertFalse(
            (null as ServerStarterChecklist?)
                .provesForAccount("account-a", ServerStarterMilestone.SEND_FIRST_MESSAGE),
        )
    }

    @Test
    fun `a completed row proves its milestone even while completed_at is still null`() {
        // The contract requires the completed_at KEY; its value stays nullable. Status is
        // the authority for completion.
        val checklist = validatedStarterChecklist(
            dto(milestones = listOf(row("make_first_transaction", "completed", null))),
            "account-a",
        )
        assertTrue(checklist.provesForAccount("account-a", ServerStarterMilestone.MAKE_FIRST_TRANSACTION))
    }

    @Test
    fun `an ineligible snapshot proves nothing even if it somehow carries completions`() {
        val hostile = ServerStarterChecklist(
            ownerAccountId = "account-a",
            eligible = false,
            policyVersion = 3,
            milestones = mapOf(
                ServerStarterMilestone.SEND_FIRST_MESSAGE to
                    ServerStarterMilestoneState(ServerMilestoneStatus.COMPLETED, "2026-08-01T10:00:00Z"),
            ),
        )
        assertFalse(hostile.provesForAccount("account-a", ServerStarterMilestone.SEND_FIRST_MESSAGE))
    }

    // ------------------------------------------------------------------
    // Wire behaviour: capability gate, schema enforcement, fencing, transitions
    // ------------------------------------------------------------------

    @Test
    fun `the onboarding route is never called unless the capability is exactly true`() = runTest {
        val sessions = MutableTestSessionStore(testSession("account-a"))
        val repository = repository(sessions, inertScope())

        server.enqueue(jsonResponse(capabilitiesJson("{}")))
        repository.refresh()
        assertNull(repository.checklist.value)

        server.enqueue(jsonResponse(capabilitiesJson("""{"starter_checklist":false}""")))
        repository.refresh()
        assertNull(repository.checklist.value)

        server.enqueue(jsonResponse(capabilitiesJson("""{"starter_checklist":null}""")))
        repository.refresh()
        assertNull(repository.checklist.value)

        assertEquals(3, server.requestCount)
        repeat(3) {
            assertEquals("/api/kit-wallet/v1/capabilities", server.takeRequest().path)
        }
    }

    @Test
    fun `an advertised capability fetches, validates and owner-tags the checklist`() = runTest {
        val sessions = MutableTestSessionStore(testSession("account-a"))
        val repository = repository(sessions, inertScope())
        server.enqueue(jsonResponse(capabilitiesJson("""{"starter_checklist":true}""")))
        server.enqueue(jsonResponse(checklistJson(accountId = "account-a")))

        repository.refresh()

        val published = repository.checklist.value
        assertEquals("account-a", published?.ownerAccountId)
        assertEquals(3, published?.policyVersion)
        assertEquals(
            ServerStarterMilestoneState(ServerMilestoneStatus.COMPLETED, "2026-08-01T10:00:00Z"),
            published?.milestones?.get(ServerStarterMilestone.SEND_FIRST_MESSAGE),
        )
        server.takeRequest()
        assertEquals("/api/v1/onboarding/starter-checklist", server.takeRequest().path)
    }

    @Test
    fun `a checklist naming the wrong account is rejected at the repository too`() = runTest {
        val sessions = MutableTestSessionStore(testSession("account-a"))
        val repository = repository(sessions, inertScope())
        server.enqueue(jsonResponse(capabilitiesJson("""{"starter_checklist":true}""")))
        server.enqueue(jsonResponse(checklistJson(accountId = "account-b")))

        repository.refresh()

        assertNull(repository.checklist.value)
    }

    @Test
    fun `a response violating the required schema throws and never publishes`() = runTest {
        val sessions = MutableTestSessionStore(testSession("account-a"))
        val repository = repository(sessions, inertScope())
        val violations = listOf(
            // milestone row without the required completed_at key
            """{"ok":true,"data":{"account_id":"account-a","eligible":true,"policy_version":3,"milestones":[{"key":"send_first_message","status":"completed"}]},"meta":$META}""",
            // missing required eligible field
            """{"ok":true,"data":{"account_id":"account-a","policy_version":3,"milestones":[]},"meta":$META}""",
            // policy_version is not an integer
            """{"ok":true,"data":{"account_id":"account-a","eligible":true,"policy_version":2.5,"milestones":[]},"meta":$META}""",
            // null account_id
            """{"ok":true,"data":{"account_id":null,"eligible":true,"policy_version":3,"milestones":[]},"meta":$META}""",
        )
        violations.forEach { body ->
            server.enqueue(jsonResponse(capabilitiesJson("""{"starter_checklist":true}""")))
            server.enqueue(jsonResponse(body))

            assertTrue("schema violation must fail the fetch: $body", runCatching { repository.refresh() }.isFailure)
            assertNull(repository.checklist.value)
        }
    }

    @Test
    fun `a session replaced mid-flight refuses to publish and throws`() {
        val sessions = MutableTestSessionStore(testSession("account-a"))
        val repository = repository(sessions, inertScope())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path.orEmpty().contains("capabilities") ->
                    jsonResponse(capabilitiesJson("""{"starter_checklist":true}"""))
                request.path.orEmpty().contains("starter-checklist") -> {
                    // Account B signs in while account A's answer is still on the wire.
                    runBlocking { sessions.save(testSession("account-b")) }
                    jsonResponse(checklistJson(accountId = "account-a"))
                }
                else -> MockResponse().setResponseCode(404)
            }
        }

        assertThrows(SessionInvalidatedException::class.java) {
            runBlocking { repository.refresh() }
        }

        assertNull(repository.checklist.value)
    }

    @Test
    fun `every session transition clears the published checklist before anything else`() {
        val sessions = MutableTestSessionStore(testSession("account-a"))
        val capabilitiesCalls = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path.orEmpty().contains("capabilities") ->
                    // Advertised for account A's refresh; gone for account B's, so B's
                    // refresh can only ever confirm null — never repaint A's facts.
                    if (capabilitiesCalls.incrementAndGet() == 1) {
                        jsonResponse(capabilitiesJson("""{"starter_checklist":true}"""))
                    } else {
                        jsonResponse(capabilitiesJson("{}"))
                    }
                request.path.orEmpty().contains("starter-checklist") ->
                    jsonResponse(checklistJson(accountId = "account-a"))
                else -> MockResponse().setResponseCode(404)
            }
        }
        val scope = CoroutineScope(SupervisorJob())
        try {
            val repository = repository(sessions, scope)
            runBlocking {
                withTimeout(10_000) {
                    val published = repository.checklist.first { it != null }
                    assertEquals("account-a", published?.ownerAccountId)

                    sessions.save(testSession("account-b"))
                    repository.checklist.first { it == null }
                }
            }
            assertNull(repository.checklist.value)
        } finally {
            scope.cancel()
        }
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private fun repository(sessions: SessionStore, scope: CoroutineScope) =
        RemoteStarterChecklistRepository(api, apiCalls, sessions, scope)

    /** A scope that never runs: keeps the init collector out of direct refresh() tests. */
    private fun inertScope() = CoroutineScope(Job().apply { cancel() })

    private fun dto(
        accountId: String = "account-a",
        eligible: Boolean = true,
        policyVersion: Int = 3,
        milestones: List<StarterChecklistMilestoneDto> = listOf(
            row("verify_identity", "pending", null),
            row("send_first_message", "completed", "2026-08-01T10:00:00Z"),
            row("make_first_transaction", "pending", null),
        ),
    ) = StarterChecklistDto(
        accountId = accountId,
        eligible = eligible,
        policyVersion = policyVersion,
        milestones = milestones,
    )

    private fun row(key: String, status: String, completedAt: String?) =
        StarterChecklistMilestoneDto(key = key, status = status, completedAt = completedAt)

    private fun jsonResponse(body: String) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun capabilitiesJson(featuresJson: String) =
        """
        {"ok":true,"data":{"api_version":"v1","currency":{"code":"UGX","scale":"2"},"features":$featuresJson,"authentication":{"phone_otp":false,"firebase_phone":true}},"meta":$META}
        """.trimIndent()

    /** The canonical shape: account, eligibility, integer policy version, milestone rows. */
    private fun checklistJson(accountId: String) =
        """
        {"ok":true,"data":{"account_id":"$accountId","eligible":true,"policy_version":3,"milestones":[{"key":"verify_identity","status":"pending","completed_at":null},{"key":"send_first_message","status":"completed","completed_at":"2026-08-01T10:00:00Z"},{"key":"make_first_transaction","status":"pending","completed_at":null}]},"meta":$META}
        """.trimIndent()

    private companion object {
        const val META =
            """{"request_id":"request-checklist","api_version":"v1","server_time":"2026-08-28T12:00:00Z"}"""
    }
}
