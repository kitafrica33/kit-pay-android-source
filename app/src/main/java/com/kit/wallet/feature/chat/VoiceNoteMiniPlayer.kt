package com.kit.wallet.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The conversation a voice-note bubble is being drawn in.
 *
 * Provided by the conversation screen rather than passed down every media composable, because only
 * the thread can resolve a sender id to "You", a member's name, or a neutral fallback — and the
 * floating bar still has to name the speaker after that thread has been left.
 */
internal val LocalVoiceNoteChatContext: ProvidableCompositionLocal<VoiceNoteChatContext> =
    staticCompositionLocalOf { VoiceNoteChatContext() }

@Composable
internal fun ProvideVoiceNoteChatContext(
    context: VoiceNoteChatContext,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalVoiceNoteChatContext provides context, content = content)
}

/**
 * The strip that appears above a chat — and above every other screen — while a voice note is
 * playing somewhere the user can no longer see it.
 *
 * It is the fallback control, so it carries everything the bubble does: who is speaking, where they
 * said it, a pause, a scrubbable position, and an X that ends the note outright. It stays away for
 * as long as the note's own bubble is on screen, because that bubble is already the control.
 */
@Composable
internal fun VoiceNoteMiniBar(
    modifier: Modifier = Modifier,
    /**
     * Opens the conversation the playing note belongs to, landing on its exact message. Only the
     * bar's naming body takes this tap; pause, stop and the scrubber keep working playback alone.
     */
    onOpenSource: ((VoiceNotePlayingNote) -> Unit)? = null,
) {
    val state by VoiceNotePlayer.state.collectAsStateWithLifecycle()
    val playing = state.playing ?: return
    if (!VoiceNoteMiniBarPolicy.isVisible(
            hasPlayback = true,
            isSourceOnScreen = state.isSourceOnScreen,
        )
    ) {
        return
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(VoiceNoteMiniBarPolicy.CONTENT_HEIGHT_DP.dp)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier
                        .weight(1f)
                        .clickable(
                            enabled = onOpenSource != null &&
                                playing.context.conversationId.isNotBlank(),
                        ) { onOpenSource?.invoke(playing) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                Icon(
                    Icons.Rounded.GraphicEq,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        playing.context.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        playing.context.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                }
                Text(
                    formatVoiceNoteTime(state.positionMillis),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(onClick = VoiceNotePlayer::toggleCurrent) {
                    Icon(
                        if (state.isPaused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                        contentDescription = if (state.isPaused) {
                            "Resume voice note"
                        } else {
                            "Pause voice note"
                        },
                    )
                }
                IconButton(onClick = VoiceNotePlayer::stop) {
                    Icon(Icons.Rounded.Close, contentDescription = "Stop voice note")
                }
            }
            VoiceNoteMiniBarScrubber(progress = state.progress)
        }
    }
}

/**
 * The bar's position line, seekable by the same two gestures the bubble's waveform takes: a tap
 * lands where it was touched, a slide moves from where the finger went down.
 */
@Composable
private fun VoiceNoteMiniBarScrubber(progress: Float) {
    val track = MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)
    val played = MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .fillMaxWidth()
            .height(14.dp)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    VoiceNotePlayer.seekToFraction(
                        VoiceNoteSeekPolicy.fractionAtX(offset.x, size.width.toFloat()),
                    )
                }
            }
            .pointerInput(Unit) {
                var start = 0f
                var travelled = 0f
                detectHorizontalDragGestures(
                    onDragStart = {
                        start = VoiceNotePlayer.state.value.progress
                        travelled = 0f
                    },
                    onHorizontalDrag = { _, delta ->
                        travelled += delta
                        VoiceNotePlayer.seekToFraction(
                            VoiceNoteSeekPolicy.scrubbedFraction(
                                start,
                                travelled,
                                size.width.toFloat(),
                            ),
                        )
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(CircleShape)
                .background(track),
        )
        Box(
            Modifier
                .fillMaxWidth(VoiceNoteSeekPolicy.clamped(progress))
                .height(3.dp)
                .clip(CircleShape)
                .background(played)
                .align(Alignment.CenterStart),
        )
    }
}
