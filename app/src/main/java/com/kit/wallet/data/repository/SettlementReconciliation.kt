package com.kit.wallet.data.repository

import com.kit.wallet.data.remote.KitWalletApiException
import com.kit.wallet.data.session.SessionFence
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal enum class SettlementPollResult { PENDING, TERMINAL }

/** Server-owned terminal states shared by bank and mobile-money operations. */
internal fun String.isTerminalSettlementStatus(): Boolean =
    lowercase() in SETTLEMENT_TERMINAL_STATUSES

/**
 * One application-scoped poll per operation and authenticated session.
 *
 * There is deliberately no attempt cap. A provider can settle after more than a minute, after a
 * radio outage, or after the app process is recreated. Repositories repopulate this registry from
 * their authoritative operation lists on session start and foreground refresh, so process death
 * cannot turn a still-pending operation into permanently stale UI.
 */
internal class SettlementReconciliationPoller(
    private val scope: CoroutineScope,
    private val currentSession: () -> SessionFence?,
    private val canPoll: () -> Boolean = { true },
    private val wait: suspend (Long) -> Unit = { delay(it) },
) {
    private data class Key(val owner: SessionFence, val operationId: String)

    private val jobs = mutableMapOf<Key, Job>()

    fun ensure(
        owner: SessionFence,
        operationId: String,
        reconcile: suspend () -> SettlementPollResult,
    ): Job = synchronized(jobs) {
        val key = Key(owner, operationId)
        jobs[key]?.takeIf(Job::isActive)?.let { return@synchronized it }

        val job = scope.launch(start = CoroutineStart.LAZY) {
            val self = currentCoroutineContext()[Job]
            var nonterminalResponses = 0
            var consecutiveFailures = 0
            try {
                while (currentSession() == owner && canPoll()) {
                    val outcome = try {
                        reconcile()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        if (
                            currentSession() != owner ||
                            !canPoll() ||
                            !error.isRetryableSettlementReconciliationFailure()
                        ) {
                            return@launch
                        }
                        consecutiveFailures++
                        wait(settlementFailureRetryDelayMillis(consecutiveFailures))
                        continue
                    }
                    consecutiveFailures = 0
                    if (outcome == SettlementPollResult.TERMINAL) return@launch
                    nonterminalResponses++
                    wait(settlementPendingPollDelayMillis(nonterminalResponses))
                }
            } finally {
                synchronized(jobs) {
                    if (jobs[key] === self) jobs.remove(key)
                }
            }
        }
        jobs[key] = job
        job.start()
        job
    }

    fun cancelAll() {
        val active = synchronized(jobs) {
            jobs.values.toList().also { jobs.clear() }
        }
        active.forEach(Job::cancel)
    }

    fun restart(
        owner: SessionFence,
        operationId: String,
        reconcile: suspend () -> SettlementPollResult,
    ): Job {
        val key = Key(owner, operationId)
        synchronized(jobs) { jobs.remove(key) }?.cancel()
        return ensure(owner, operationId, reconcile)
    }
}

/** Rejects a malformed or confused-deputy response before it can alter another operation's UI. */
internal fun requireExactSettlementOperationId(expected: String, actual: String) {
    check(actual == expected) { "Settlement response did not match the requested operation" }
}

/** Poll quickly first, then taper without ever declaring a pending provider operation abandoned. */
internal fun settlementPendingPollDelayMillis(nonterminalResponses: Int): Long {
    require(nonterminalResponses > 0)
    return when {
        nonterminalResponses <= 40 -> 1_500L
        nonterminalResponses <= 100 -> 5_000L
        else -> 10_000L
    }
}

internal fun settlementFailureRetryDelayMillis(consecutiveFailures: Int): Long {
    require(consecutiveFailures > 0)
    val exponent = (consecutiveFailures - 1).coerceAtMost(4)
    return (1_000L shl exponent).coerceAtMost(10_000L)
}

private fun Throwable.isRetryableSettlementReconciliationFailure(): Boolean = when (this) {
    is IOException -> true
    is KitWalletApiException ->
        statusCode == null || statusCode == 408 || statusCode == 425 || statusCode == 429 ||
            statusCode >= 500
    else -> false
}

private val SETTLEMENT_TERMINAL_STATUSES = setOf(
    "completed",
    "succeeded",
    "failed",
    "reversed",
    "cancelled",
    "canceled",
)
