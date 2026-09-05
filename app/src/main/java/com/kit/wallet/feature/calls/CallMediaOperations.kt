package com.kit.wallet.feature.calls

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Main-thread owner of one call's asynchronous capture changes and their teardown fence. */
internal class CallMediaOperations {
    private var active: Job? = null
    private var generation = 0
    private var retired = false

    val isActive: Boolean get() = active?.isActive == true

    fun open() {
        check(active == null || active?.isCompleted == true)
        retired = false
        generation++
    }

    fun launch(scope: CoroutineScope, change: suspend (isCurrent: () -> Boolean) -> Unit): Boolean {
        if (retired || isActive) return false
        val owner = generation
        val job = scope.launch(start = CoroutineStart.LAZY) {
            change { !retired && generation == owner }
        }
        active = job
        job.start()
        return true
    }

    /** Callers disconnect immediately, then join this job before the final disconnect/release. */
    fun retire(): Job? {
        retired = true
        generation++
        return active?.also { it.cancel() }
    }
}
