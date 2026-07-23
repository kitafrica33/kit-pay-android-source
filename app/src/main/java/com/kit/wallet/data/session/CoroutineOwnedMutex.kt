package com.kit.wallet.data.session

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A coroutine-reentrant mutex whose ownership survives only lock contexts entered through this
 * class. Nested [CoroutineOwnedMutex] sections register their child `withContext` Job with each
 * inherited lease already owned by the entering Job. Ordinary `launch`/`async` children inherit
 * the context element but not an ownership registration, so they cannot bypass mutual exclusion.
 */
internal class CoroutineOwnedMutex {
    private val mutex = Mutex()
    private val leaseKey: CoroutineContext.Key<Lease> = object : CoroutineContext.Key<Lease> {}

    suspend fun <T> withLock(action: suspend () -> T): T {
        val current = currentCoroutineContext()
        if (current[leaseKey]?.isOwnedBy(current[Job]) == true) return action()

        return mutex.withLock {
            val enteringJob = current[Job]
            val acquiredLease = Lease()
            val outcome = withContext(acquiredLease) {
                val leaseContext = currentCoroutineContext()
                val ownerJob = checkNotNull(leaseContext[Job]) {
                    "A coroutine-owned mutex requires an active Job"
                }
                val registered = ArrayList<JobBoundLease>()
                try {
                    leaseContext.fold(registered) { leases, element ->
                        if (
                            element is JobBoundLease &&
                            (element === acquiredLease || element.isOwnedBy(enteringJob))
                        ) {
                            element.register(ownerJob)
                            leases += element
                        }
                        leases
                    }
                    try {
                        Result.success(action())
                    } catch (error: Throwable) {
                        // Rethrow outside withContext so coroutine stack-trace recovery cannot
                        // replace the exact storage/archive failure with a copied exception.
                        Result.failure(error)
                    }
                } finally {
                    registered.asReversed().forEach { it.unregister(ownerJob) }
                }
            }
            outcome.getOrThrow()
        }
    }

    private inner class Lease :
        AbstractCoroutineContextElement(leaseKey),
        JobBoundLease {
        private val ownerJobs = mutableSetOf<Job>()

        @Synchronized
        override fun register(job: Job) {
            check(ownerJobs.add(job)) { "Coroutine lock ownership was registered twice" }
        }

        @Synchronized
        override fun unregister(job: Job) {
            check(ownerJobs.remove(job)) { "Coroutine lock ownership was not registered" }
        }

        @Synchronized
        override fun isOwnedBy(job: Job?): Boolean = job != null && job in ownerJobs
    }
}

private interface JobBoundLease : CoroutineContext.Element {
    fun register(job: Job)
    fun unregister(job: Job)
    fun isOwnedBy(job: Job?): Boolean
}
