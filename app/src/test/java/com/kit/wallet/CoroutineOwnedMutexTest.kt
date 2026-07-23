package com.kit.wallet

import com.kit.wallet.data.session.CoroutineOwnedMutex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CoroutineOwnedMutexTest {
    @Test
    fun `nested owned mutex context can reenter its outer lock`() = runTest {
        val outer = CoroutineOwnedMutex()
        val inner = CoroutineOwnedMutex()
        var reentered = false

        withTimeout(1_000L) {
            outer.withLock {
                inner.withLock {
                    outer.withLock { reentered = true }
                }
            }
        }

        assertTrue(reentered)
    }

    @Test
    fun `launched child does not inherit mutex ownership`() = runTest {
        val mutex = CoroutineOwnedMutex()
        val entered = CompletableDeferred<Unit>()

        mutex.withLock {
            val child = async(start = CoroutineStart.UNDISPATCHED) {
                mutex.withLock { entered.complete(Unit) }
            }
            assertFalse(entered.isCompleted)
            child.cancelAndJoin()
        }
    }

    @Test
    fun `launched child cannot launder ownership through another owned mutex`() = runTest {
        val outer = CoroutineOwnedMutex()
        val inner = CoroutineOwnedMutex()
        val reentered = CompletableDeferred<Unit>()

        val child = outer.withLock {
            val launched = async(start = CoroutineStart.UNDISPATCHED) {
                inner.withLock {
                    outer.withLock { reentered.complete(Unit) }
                }
            }
            assertFalse(reentered.isCompleted)
            launched
        }

        child.await()
        assertTrue(reentered.isCompleted)
    }

    @Test
    fun `nested lock contexts preserve the exact failure`() = runTest {
        val outer = CoroutineOwnedMutex()
        val inner = CoroutineOwnedMutex()
        val failure = IllegalStateException("archive unavailable")

        val observed = runCatching {
            outer.withLock {
                inner.withLock { throw failure }
            }
        }.exceptionOrNull()

        assertSame(failure, observed)
    }
}
