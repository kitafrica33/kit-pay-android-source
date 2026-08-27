package com.kit.wallet

import com.kit.wallet.data.repository.CallHistoryRefreshGate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that stops an older call-history walk from publishing over a newer one.
 *
 * Answering a call now kicks off a background history refresh, so two walks genuinely
 * overlap — and they run on different threads, one on the application scope's IO
 * dispatcher and one wherever an explicit refresh was asked for.
 */
class CallHistoryRefreshGateTest {
    @Test
    fun `a lone walk publishes`() {
        val gate = CallHistoryRefreshGate()

        assertTrue(gate.admits(gate.begin()))
    }

    @Test
    fun `a walk started later supersedes one already running`() {
        val gate = CallHistoryRefreshGate()

        val answerRefresh = gate.begin()
        val pullToRefresh = gate.begin()

        assertFalse(gate.admits(answerRefresh))
        assertTrue(gate.admits(pullToRefresh))
    }

    @Test
    fun `an older walk finishing last cannot overwrite the newer snapshot`() {
        // The out-of-order case the gate exists for: the newer walk is short and lands
        // first, the older one is still paging and lands afterwards. Admission is by
        // generation, so finishing order does not decide who wins.
        val gate = CallHistoryRefreshGate()
        val published = mutableListOf<String>()

        val slowOlder = gate.begin()
        val fastNewer = gate.begin()

        if (gate.admits(fastNewer)) published += "newer"
        if (gate.admits(slowOlder)) published += "older"

        assertEquals(listOf("newer"), published)
    }

    @Test
    fun `admission stays with the newest walk however many times it is asked`() {
        val gate = CallHistoryRefreshGate()

        val first = gate.begin()
        val second = gate.begin()

        assertTrue(gate.admits(second))
        assertTrue(gate.admits(second))
        assertFalse(gate.admits(first))
        assertFalse(gate.admits(first))
    }

    @Test
    fun `a token from another gate is never admitted`() {
        // Each repository owns its own gate; a bare Long must not be portable between them.
        val gate = CallHistoryRefreshGate()
        val other = CallHistoryRefreshGate()

        other.begin()
        val mine = gate.begin()
        other.begin()

        assertTrue(gate.admits(mine))
        assertFalse(gate.admits(other.begin()))
    }

    @Test
    fun `concurrent walks each receive a distinct generation`() {
        // The lost-update case. With a plain counter two threads can read the same value
        // and both believe they are newest, which is exactly the double-publish the gate
        // is meant to rule out.
        val gate = CallHistoryRefreshGate()
        val threads = 8
        val perThread = 2_000
        val issued = ConcurrentHashMap.newKeySet<Long>()
        val barrier = CyclicBarrier(threads)
        val pool = Executors.newFixedThreadPool(threads)

        try {
            val done = CountDownLatch(threads)
            repeat(threads) {
                pool.execute {
                    barrier.await()
                    repeat(perThread) { issued += gate.begin() }
                    done.countDown()
                }
            }
            assertTrue(done.await(30, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }

        assertEquals(threads * perThread, issued.size)
        assertEquals((threads * perThread).toLong(), issued.maxOrNull() ?: 0L)
    }

    @Test
    fun `exactly one of a set of concurrently issued walks is admitted`() {
        val gate = CallHistoryRefreshGate()
        val threads = 8
        val barrier = CyclicBarrier(threads)
        val pool = Executors.newFixedThreadPool(threads)
        val tokens = ConcurrentHashMap.newKeySet<Long>()

        try {
            val done = CountDownLatch(threads)
            repeat(threads) {
                pool.execute {
                    barrier.await()
                    tokens += gate.begin()
                    done.countDown()
                }
            }
            assertTrue(done.await(30, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }

        assertEquals(threads, tokens.size)
        assertEquals(1, tokens.count(gate::admits))
    }

    @Test
    fun `a walk under way is never admitted while newer ones keep starting`() {
        // Admission is read on one thread while generations are claimed on another, which
        // is the shape of the real overlap: a background walk finishing its last page while
        // the user pulls to refresh. The stale token must never briefly read as admitted.
        val gate = CallHistoryRefreshGate()
        val stale = gate.begin()
        gate.begin()
        val stopped = AtomicBoolean(false)
        val admissions = AtomicInteger(0)
        val issuer = Thread {
            while (!stopped.get()) gate.begin()
        }
        val reader = Thread {
            repeat(200_000) { if (gate.admits(stale)) admissions.incrementAndGet() }
        }

        issuer.start()
        reader.start()
        reader.join(30_000)
        stopped.set(true)
        issuer.join(30_000)

        assertEquals(0, admissions.get())
    }
}
