package com.kit.wallet.feature.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlinx.coroutines.launch

/**
 * Pure math for dragging a message aside to answer it.
 *
 * Either direction works, and deliberately so: which hand someone holds their phone in decides
 * which way is comfortable, and a bubble on the right of the screen has less room to travel right.
 * The maths is the same in both, so the signs are carried rather than assumed.
 */
internal object SwipeToReply {

    /** How far a bubble is allowed to travel before it stops following the finger honestly. */
    const val MAX_TRAVEL_DP = 68

    /** The distance at which letting go answers the message. */
    const val TRIGGER_DP = 52

    /** What fraction of further dragging still moves the bubble once it is at full travel. */
    private const val OVERSHOOT_RATE = 0.18f

    /** The hard stop, as a multiple of full travel, so a long drag never leaves the row. */
    private const val MAX_OVERSHOOT = 1.2f

    /**
     * Where the bubble sits for a drag of [dragPx], signed the way the finger went.
     *
     * Past [maxPx] the bubble keeps moving, but only barely: the drag stops feeling like it is
     * getting anywhere, which is the whole message — this is as far as it goes.
     */
    fun travel(dragPx: Float, maxPx: Float): Float {
        if (maxPx <= 0f) return 0f
        val magnitude = abs(dragPx)
        val eased = if (magnitude <= maxPx) {
            magnitude
        } else {
            maxPx + (magnitude - maxPx) * OVERSHOOT_RATE
        }
        return sign(dragPx) * min(eased, maxPx * MAX_OVERSHOOT)
    }

    /** Whether letting go here answers the message. */
    fun shouldReply(travelPx: Float, triggerPx: Float): Boolean =
        triggerPx > 0f && abs(travelPx) >= triggerPx

    /**
     * How far along the gesture is, for the arrow that fades and grows in behind the bubble.
     *
     * Reaching 1 is the same event as [shouldReply] becoming true, so what the arrow shows and
     * what releasing does can never disagree.
     */
    fun progress(travelPx: Float, triggerPx: Float): Float =
        if (triggerPx <= 0f) 0f else (abs(travelPx) / triggerPx).coerceIn(0f, 1f)
}

/**
 * Wraps one message row so dragging it sideways answers it.
 *
 * The gesture only claims horizontal movement, so the thread still scrolls normally under the same
 * finger, and the bubble's own tap and long-press are untouched. Releasing always springs the row
 * back: the answer is a thing that happens in the composer, not a bubble left sitting off-centre.
 */
@Composable
internal fun SwipeToReplyRow(
    enabled: Boolean,
    onReply: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (!enabled) {
        Box(modifier) { content() }
        return
    }
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val currentOnReply by rememberUpdatedState(onReply)
    val maxPx = with(density) { SwipeToReply.MAX_TRAVEL_DP.dp.toPx() }
    val triggerPx = with(density) { SwipeToReply.TRIGGER_DP.dp.toPx() }
    val offset = remember { Animatable(0f) }
    // Raw finger distance, kept apart from the eased offset so that easing is applied to the
    // gesture once rather than compounding on itself every frame.
    val dragged = remember { floatArrayOf(0f) }
    val armed = remember { booleanArrayOf(false) }
    val travel = offset.value
    val progress = SwipeToReply.progress(travel, triggerPx)

    Box(modifier) {
        if (progress > 0f) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier
                    .align(if (travel > 0f) Alignment.CenterStart else Alignment.CenterEnd)
                    .padding(horizontal = 10.dp)
                    // The arrow arrives with the drag and is unmistakably complete at the point
                    // where letting go would answer.
                    .alpha(progress)
                    .scale(0.7f + 0.3f * progress),
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.Reply,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(7.dp).size(18.dp),
                )
            }
        }
        Box(
            Modifier
                .fillMaxHeight()
                .offset { IntOffset(travel.roundToInt(), 0) }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            dragged[0] = 0f
                            armed[0] = false
                        },
                        onHorizontalDrag = { change, amount ->
                            change.consume()
                            dragged[0] += amount
                            val next = SwipeToReply.travel(dragged[0], maxPx)
                            // Crossing the line is the moment worth feeling, and only the moment:
                            // buzzing on every frame past it would turn an answer into a rattle.
                            if (!armed[0] && SwipeToReply.shouldReply(next, triggerPx)) {
                                armed[0] = true
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            } else if (armed[0] && !SwipeToReply.shouldReply(next, triggerPx)) {
                                armed[0] = false
                            }
                            scope.launch { offset.snapTo(next) }
                        },
                        onDragEnd = {
                            val reply = SwipeToReply.shouldReply(offset.value, triggerPx)
                            dragged[0] = 0f
                            armed[0] = false
                            scope.launch { offset.animateTo(0f) }
                            if (reply) currentOnReply()
                        },
                        onDragCancel = {
                            dragged[0] = 0f
                            armed[0] = false
                            scope.launch { offset.animateTo(0f) }
                        },
                    )
                },
        ) {
            content()
        }
    }
}
