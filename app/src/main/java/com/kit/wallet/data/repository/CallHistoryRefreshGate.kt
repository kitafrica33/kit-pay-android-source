package com.kit.wallet.data.repository

import java.util.concurrent.atomic.AtomicLong

/**
 * Stops an older call-history walk from overwriting a newer one's result.
 *
 * The history is paginated to exhaustion, so a refresh is several round trips and two of
 * them can easily overlap — a background refresh started by answering a call, and a
 * pull-to-refresh a moment later. Whichever *finishes* last would otherwise win, and the
 * slower walk is very often the older one, which would leave the list showing a snapshot
 * that predates the call the user just had.
 *
 * Cancelling the previous job is not enough on its own: cancellation is cooperative, and
 * a walk that has already collected its last page can still reach the assignment. So the
 * result is admitted by token instead. The rule is only ever "is this still the newest
 * walk", never a comparison of timestamps inside the data.
 *
 * The two callers genuinely run on different threads — the background walk is launched on
 * the application scope's IO dispatcher while an explicit refresh runs on whichever thread
 * the caller is on — so the counter is atomic. A plain `Long` here would let two walks
 * read the same value and both believe they were newest, or let one publish against a
 * token the other never saw, which is precisely the overwrite this exists to prevent.
 *
 * Pure Kotlin, so the ordering rule is pinned by a unit test rather than by a race.
 */
internal class CallHistoryRefreshGate {
    private val issued = AtomicLong(0)

    /** Claims the right to publish, superseding every walk started before this one. */
    fun begin(): Long = issued.incrementAndGet()

    /** Whether the walk holding [token] is still the newest one. */
    fun admits(token: Long): Boolean = token == issued.get()
}
