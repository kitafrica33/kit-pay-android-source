package com.kit.wallet.feature.chat

import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.kit.wallet.feature.chat.camera.CameraPullGesture
import kotlinx.coroutines.CompletableDeferred

internal data class ConversationCameraPull(
    val modifier: Modifier,
    val revealPx: Float,
    val pastThreshold: Boolean,
)

/** Observes pointer completion without consuming it; LazyColumn still owns vertical scrolling. */
@Composable
internal fun rememberConversationCameraPull(
    chatId: String,
    listState: LazyListState,
    enabled: Boolean,
    onUserScroll: () -> Unit,
    onReachedBottom: () -> Unit,
    onOpenCamera: () -> Unit,
): ConversationCameraPull {
    val density = LocalDensity.current
    val maximumPx = with(density) { 160.dp.toPx() }
    val thresholdPx = with(density) { 120.dp.toPx() }
    val gesture = remember(chatId, maximumPx, thresholdPx) { CameraPullGesture(maximumPx, thresholdPx) }
    var reveal by remember(chatId) { mutableFloatStateOf(0f) }
    val currentEnabled by rememberUpdatedState(enabled)
    val currentOpen by rememberUpdatedState(onOpenCamera)
    val currentUserScroll by rememberUpdatedState(onUserScroll)
    val currentReachedBottom by rememberUpdatedState(onReachedBottom)
    val haptics = LocalHapticFeedback.current
    val release = remember(chatId) { CameraPullRelease() }

    // Keyboard, inset, new-message and card-height changes cannot count as additional pulling.
    LaunchedEffect(listState, gesture, enabled) {
        var previous: Triple<IntSize, Int, List<Pair<Int, Int>>>? = null
        snapshotFlow {
            val info = listState.layoutInfo
            Triple(info.viewportSize, info.totalItemsCount, info.visibleItemsInfo.map { it.index to it.size })
        }.collect { geometry ->
            if (!enabled || (previous != null && previous != geometry)) {
                gesture.cancel()
                reveal = 0f
                release.current?.complete(false)
                release.current = null
            }
            previous = geometry
        }
    }

    val connection = remember(listState, gesture, release) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                if (shouldReleaseOpeningBottomAnchor(userInput = true, verticalDelta = available.y)) currentUserScroll()
                if (!currentEnabled) return Offset.Zero
                val consumed = gesture.collapse(available.y)
                reveal = gesture.revealPx
                return Offset(0f, consumed)
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                val atBottom = !listState.canScrollForward
                if (atBottom) currentReachedBottom()
                val used = gesture.pull(available.y, userInput = true, atBottom = atBottom)
                reveal = gesture.revealPx
                if (gesture.takeThresholdHaptic()) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                return Offset(0f, used)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                val distance = reveal
                if (distance <= 0f) return Velocity.Zero
                val signal = release.current
                val completed = signal?.await() == true
                val open = gesture.release(completed && currentEnabled && release.current === signal)
                try {
                    animate(distance, 0f) { value, _ ->
                        if (release.current === signal) reveal = value
                    }
                    if (open && currentEnabled && release.current === signal) currentOpen()
                } finally {
                    if (release.current === signal) reveal = 0f
                }
                return available
            }
        }
    }

    val modifier = Modifier.pointerInput(chatId, enabled, listState, gesture) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val signal = CompletableDeferred<Boolean>()
            release.current = signal
            gesture.begin(
                atBottom = !listState.canScrollForward && !listState.isScrollInProgress &&
                    listState.layoutInfo.totalItemsCount > 0,
                enabled = enabled,
            )
            reveal = 0f
            try {
                while (true) {
                    // Compose represents ACTION_CANCEL as already-consumed up changes.
                    // Observe Initial before the list consumes a real finger-up of its own.
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.count { it.pressed } > 1) {
                        signal.complete(false)
                        gesture.cancel()
                        reveal = 0f
                        break
                    }
                    if (event.changes.none { it.pressed }) {
                        val completed = event.changes.any { it.changedToUp() }
                        signal.complete(completed)
                        if (!completed) {
                            gesture.cancel()
                            reveal = 0f
                        }
                        break
                    }
                }
            } finally {
                // ACTION_CANCEL and disposal never open the camera, even above the threshold.
                if (!signal.isCompleted) {
                    signal.complete(false)
                    gesture.cancel()
                    reveal = 0f
                }
            }
        }
    }.nestedScroll(connection)
    return ConversationCameraPull(modifier, reveal, reveal >= thresholdPx)
}

private class CameraPullRelease {
    var current: CompletableDeferred<Boolean>? = null
}
