package com.kit.wallet.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * The filled/empty dots above a PIN keypad.
 *
 * Deliberately the only thing on screen that reflects the digits entered: the PIN itself is never
 * rendered, never placed in a text field, and so is never offered to a keyboard, a clipboard or
 * saved instance state.
 */
@Composable
fun KitPinDots(
    filled: Int,
    modifier: Modifier = Modifier,
    length: Int = 4,
    error: Boolean = false,
) {
    val activeColor = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary
    Row(
        modifier = modifier.semantics {
            contentDescription = "$filled of $length digits entered"
        },
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(length) { index ->
            val on = index < filled
            val color by animateColorAsState(
                targetValue = if (on) activeColor else MaterialTheme.colorScheme.surfaceContainerHighest,
                label = "pinDotColor",
            )
            val size by animateDpAsState(
                targetValue = if (on) 18.dp else 14.dp,
                animationSpec = spring(),
                label = "pinDotSize",
            )
            Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(size)
                        .background(color, CircleShape)
                        .border(
                            width = if (on) 0.dp else 1.dp,
                            color = if (on) Color.Transparent else MaterialTheme.colorScheme.outlineVariant,
                            shape = CircleShape,
                        ),
                )
            }
        }
    }
}

/**
 * A full branded PIN screen body: heading, dots and keypad, with slots above and below.
 *
 * Shared by wallet unlock, PIN setup and payment approval so that entering a PIN looks and behaves
 * the same everywhere in the app, instead of being a text field in one place and a keypad in
 * another. The caller owns the digits and decides what happens when they are complete.
 */
@Composable
fun KitPinEntry(
    title: String,
    subtitle: String,
    pin: String,
    onPin: (String) -> Unit,
    modifier: Modifier = Modifier,
    length: Int = 4,
    busy: Boolean = false,
    error: String? = null,
    header: (@Composable () -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        header?.invoke()
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
        Spacer(Modifier.height(28.dp))
        KitPinDots(filled = pin.length, length = length, error = error != null)
        Spacer(Modifier.height(12.dp))
        // Reserved whether or not there is an error, so the keypad never jumps under a finger
        // that is halfway through a PIN.
        Box(
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .padding(horizontal = 32.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (error != null) {
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        KitKeypad(
            onKey = { digit -> if (!busy && pin.length < length) onPin(pin + digit) },
            onBackspace = { if (!busy && pin.isNotEmpty()) onPin(pin.dropLast(1)) },
        )
        footer?.let {
            Spacer(Modifier.height(12.dp))
            it()
        }
    }
}
