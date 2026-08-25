package com.kit.wallet.feature.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kit.wallet.ui.model.MessageReaction

/**
 * The one-tap reactions offered on the message long-press menu and in the media gallery.
 *
 * This exact order is a confirmed product decision, not an implementation default, so it is
 * pinned by a test. ✅ sits second deliberately: Kit Pay conversations carry payment cards, so a
 * check mark doubles as a lightweight "done / confirmed / paid" acknowledgement and earns a
 * first-class slot rather than living behind the full picker. Changing the list also changes the
 * picker's "Frequently used" group, which is seeded from it.
 */
internal val QUICK_REACTIONS: List<String> = listOf("👍", "✅", "❤️", "😂", "😮", "🙏")

/** The full picker's emoji, grouped the way people look for them. */
internal val REACTION_PICKER_GROUPS: List<Pair<String, List<String>>> = listOf(
    "Frequently used" to QUICK_REACTIONS,
    "Smileys" to listOf(
        "😀", "😃", "😄", "😁", "😆", "😅", "🤣", "😊", "😇", "🙂",
        "😉", "😌", "😍", "🥰", "😘", "😗", "😋", "😛", "🤪", "🤨",
        "🧐", "🤓", "😎", "🥳", "😏", "😒", "😞", "😔", "😟", "😕",
        "🙁", "😣", "😖", "😫", "😩", "🥺", "😢", "😭", "😤", "😠",
        "😡", "🤬", "🤯", "😳", "🥵", "🥶", "😱", "😨", "😰", "😥",
        "🤗", "🤔", "🤭", "🤫", "😬", "🙄", "😴", "🤤", "😪", "😵",
        "🤐", "🥴", "🤢", "🤮", "🤧", "😷", "🤒", "🤕", "🤠", "😈",
    ),
    "Gestures" to listOf(
        "👎", "👌", "🤌", "✌️", "🤞", "🤟", "🤘", "🤙", "👈", "👉",
        "👆", "👇", "☝️", "✋", "🤚", "🖐️", "🖖", "👋", "🤝", "✍️",
        "💪", "🦾", "👏", "🙌", "👐", "🤲", "🫶", "🤦", "🤷", "💅",
        "👀", "🫡", "🫰",
    ),
    "Hearts" to listOf(
        "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍", "🤎", "💔", "❣️",
        "💕", "💞", "💓", "💗", "💖", "💘", "💝", "💟", "♥️",
    ),
    "Money and work" to listOf(
        "💸", "💵", "💰", "💳", "🧾", "🏦", "📈", "📉", "📊", "🤑",
        "💎", "🪙", "🛒", "🎁", "📦", "🚚", "🏷️", "🔖", "📝", "📅",
        "⏰", "⏳", "📌", "📎", "🔑", "🗝️", "🔒", "🔓", "🛡️", "⚖️",
    ),
    "Food and drink" to listOf(
        "🍜", "🍕", "🍔", "🍟", "🌭", "🥪", "🌮", "🍿", "🍗", "🍖",
        "🥘", "🍲", "🥗", "🍚", "🍛", "🍞", "🥐", "🧀", "🥚", "🍳",
        "🍎", "🍌", "🍇", "🍉", "🍓", "🥭", "🍍", "🥥", "🥑", "🍫",
        "🍪", "🎂", "🍰", "🍦", "☕", "🍵", "🥤", "🧃", "🍺", "🥂",
    ),
    "Activity and travel" to listOf(
        "⚽", "🏀", "🏈", "🎾", "🏐", "🎱", "🏓", "🏸", "🥅", "🏆",
        "🥇", "🎯", "🎮", "🎲", "🎧", "🎵", "🎤", "🎬", "📷", "🎉",
        "🎊", "🎈", "✈️", "🚗", "🚕", "🚌", "🏍️", "🚲", "🛵", "🚉",
        "⛽", "🏠", "🏢", "🏥", "🏫", "⛱️", "🌍", "🗺️", "🧳", "🛎️",
    ),
    "Objects and symbols" to listOf(
        "📱", "💻", "⌨️", "🖥️", "🖨️", "☎️", "📞", "📧", "✉️", "📨",
        "🔋", "🔌", "💡", "🔦", "🧯", "🧰", "🔧", "🔨", "⚙️", "🧪",
        "💊", "🩺", "📚", "📖", "✏️", "🖊️", "🎓", "🏅", "🔥", "✨",
        "⭐", "🌟", "💫", "⚡", "☀️", "🌙", "☁️", "🌧️", "🌈", "❄️",
        "❌", "⚠️", "❓", "❗", "💯", "🔔", "🔕", "♻️", "🆗", "🚀",
    ),
)

/** Every emoji the picker can produce, for validation and tests. */
internal val REACTION_PICKER_EMOJI: List<String> =
    REACTION_PICKER_GROUPS.flatMap { (_, emoji) -> emoji }.distinct()

/**
 * The reaction chips under a bubble. Tapping one adds or removes this account's own reaction;
 * long-pressing shows who reacted.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MessageReactionChips(
    reactions: List<MessageReaction>,
    onToggle: (String) -> Unit,
    onShowReactors: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (reactions.isEmpty()) return
    Row(
        // A chip appearing, changing count or leaving resizes the row rather than snapping, so an
        // arriving reaction reads as a change to the bubble instead of a new element beside it.
        modifier
            .horizontalScroll(rememberScrollState())
            .animateContentSize(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        reactions.forEach { reaction ->
            Surface(
                shape = CircleShape,
                color = if (reaction.fromMe) {
                    MaterialTheme.colorScheme.secondary.copy(alpha = 0.18f)
                } else {
                    MaterialTheme.colorScheme.surface
                },
                shadowElevation = 1.dp,
                modifier = Modifier
                    .combinedClickable(
                        onClick = { onToggle(reaction.emoji) },
                        onLongClick = onShowReactors,
                    )
                    .semantics { contentDescription = reaction.accessibilityLabel() },
            ) {
                Row(
                    Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(reaction.emoji, style = MaterialTheme.typography.bodySmall)
                    if (reaction.count > 1) {
                        Spacer(Modifier.width(3.dp))
                        Text(
                            reaction.count.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** How a reaction chip is announced; the emoji alone tells a screen reader nothing useful. */
internal fun MessageReaction.accessibilityLabel(): String {
    val verb = if (fromMe) "Tap to remove your reaction" else "Tap to react"
    return "$emoji reacted by ${reactorNames.joinToString(", ")}. $verb"
}

/** The one-tap palette shown at the top of a message's long-press menu. */
@Composable
internal fun QuickReactionPalette(
    selected: Set<String>,
    onPick: (String) -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        QUICK_REACTIONS.forEach { emoji ->
            val isSelected = emoji in selected
            EmojiTarget(
                emoji = emoji,
                selected = isSelected,
                label = if (isSelected) "Remove the $emoji reaction" else "React with $emoji",
                onClick = { onPick(emoji) },
            )
        }
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .clickable(onClick = onMore)
                .semantics { contentDescription = "More reactions" },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "＋",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The full picker, opened from the palette's `＋`. */
@Composable
internal fun ReactionPickerDialog(
    selected: Set<String>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Add a reaction") },
        text = {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 44.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp),
            ) {
                REACTION_PICKER_GROUPS.forEach { (heading, emoji) ->
                    item(
                        key = "heading:$heading",
                        span = { GridItemSpan(maxLineSpan) },
                    ) {
                        Text(
                            heading,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                        )
                    }
                    items(emoji, key = { "$heading:$it" }) { value ->
                        EmojiTarget(
                            emoji = value,
                            selected = value in selected,
                            label = "React with $value",
                            onClick = { onPick(value) },
                        )
                    }
                }
            }
        },
    )
}

/** Who reacted, and with what. Reached by long-pressing a chip. */
@Composable
internal fun ReactionReactorsDialog(
    reactions: List<MessageReaction>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Reactions") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                reactions.forEach { reaction ->
                    reaction.reactorNames.forEach { name ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(reaction.emoji, style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.width(12.dp))
                            Text(name, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        },
    )
}

/** One round emoji tap target, shared by the quick palette and the full picker. */
@Composable
private fun EmojiTarget(
    emoji: String,
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.secondary.copy(alpha = 0.22f)
                } else {
                    Color.Transparent
                },
            )
            .clickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(emoji, style = MaterialTheme.typography.titleMedium)
    }
}
