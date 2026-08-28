package com.kit.wallet.feature.auth

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.AutofillNode
import androidx.compose.ui.autofill.AutofillType
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalAutofill
import androidx.compose.ui.platform.LocalAutofillTree
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kit.wallet.data.auth.AuthChallengeKind
import com.kit.wallet.data.auth.normalizeMfaFactorCode
import com.kit.wallet.data.demo.DemoData
import com.kit.wallet.ui.components.KitGreenButton
import com.kit.wallet.ui.components.KitKeypad
import com.kit.wallet.ui.security.SecureScreen
import com.kit.wallet.ui.theme.KitWalletTheme
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay

private const val OTP_LENGTH = 6

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun OtpScreen(
    destination: String,
    loading: Boolean,
    error: String?,
    notice: String?,
    resendSupported: Boolean,
    challengeId: String?,
    challengeKind: AuthChallengeKind?,
    resendNotBeforeElapsedRealtimeMillis: Long?,
    challengeExpiresAtElapsedRealtimeMillis: Long?,
    authenticatorChallenge: Boolean,
    onBack: () -> Unit,
    onVerify: (String) -> Unit,
    onResend: () -> Unit,
    onChallengeUnavailable: (String?) -> Unit,
) {
    SecureScreen()
    // Verification can adopt a signed-in session before its response reaches this screen. Keep
    // both toolbar and system Back from replacing that side-effecting request mid-flight.
    BackHandler {
        if (!loading) onBack()
    }
    // A route can stay composed while resend or MFA replaces the server challenge. Never carry
    // entered/submitted proof material across either an ID or a challenge-kind boundary.
    var code by remember(challengeId, challengeKind) { mutableStateOf("") }
    var recoveryCode by remember(challengeId, challengeKind) { mutableStateOf("") }
    var submittedCode by remember(challengeId, challengeKind) { mutableStateOf("") }
    var useRecoveryCode by remember(challengeId, challengeKind) { mutableStateOf(false) }
    var resendSeconds by remember(
        challengeId,
        challengeKind,
        resendNotBeforeElapsedRealtimeMillis,
    ) {
        mutableLongStateOf(
            resendSecondsRemaining(
                resendNotBeforeElapsedRealtimeMillis,
                SystemClock.elapsedRealtime(),
            ),
        )
    }
    var expirySeconds by remember(
        challengeId,
        challengeKind,
        challengeExpiresAtElapsedRealtimeMillis,
    ) {
        mutableLongStateOf(
            challengeSecondsRemaining(
                challengeExpiresAtElapsedRealtimeMillis,
                SystemClock.elapsedRealtime(),
            ),
        )
    }
    val challengeMetadataUsable = !challengeId.isNullOrBlank() &&
        challengeKind != null && challengeExpiresAtElapsedRealtimeMillis != null
    val challengeUsable = challengeMetadataUsable && expirySeconds > 0L

    LaunchedEffect(challengeId, challengeKind, resendNotBeforeElapsedRealtimeMillis) {
        while (resendSeconds > 0L) {
            delay(250L)
            resendSeconds = resendSecondsRemaining(
                resendNotBeforeElapsedRealtimeMillis,
                SystemClock.elapsedRealtime(),
            )
        }
    }

    LaunchedEffect(challengeId, challengeKind, challengeExpiresAtElapsedRealtimeMillis) {
        if (!challengeMetadataUsable || expirySeconds == 0L) {
            onChallengeUnavailable(challengeId)
            return@LaunchedEffect
        }
        while (expirySeconds > 0L) {
            delay(250L)
            expirySeconds = challengeSecondsRemaining(
                challengeExpiresAtElapsedRealtimeMillis,
                SystemClock.elapsedRealtime(),
            )
        }
        onChallengeUnavailable(challengeId)
    }

    LaunchedEffect(code, useRecoveryCode, challengeUsable) {
        if (challengeUsable && !useRecoveryCode && code.length == OTP_LENGTH &&
            code != submittedCode
        ) {
            submittedCode = code
            onVerify(code)
        }
    }
    LaunchedEffect(error) {
        if (error != null) {
            code = ""
            recoveryCode = ""
            submittedCode = ""
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {},
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !loading) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        // Two regions: everything above the keypad scrolls when a compact phone, a large
        // font scale, or the recovery keyboard leaves it short of room, while the keypad
        // itself stays pinned and whole at the bottom. Nothing clips; the top gives way.
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
            Spacer(Modifier.height(12.dp))
            Text(
                when {
                    useRecoveryCode -> "Enter a recovery code"
                    authenticatorChallenge -> "Authenticator code"
                    else -> "Enter the code"
                },
                style = MaterialTheme.typography.headlineLarge,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    useRecoveryCode -> "Use one of the single-use codes you saved when you enabled two-step verification."
                    authenticatorChallenge -> "Enter the current 6-digit code from your authenticator app."
                    else -> "Enter the verification code sent to $destination."
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (challengeUsable) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Verification expires in ${formatChallengeCountdown(expirySeconds)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(36.dp))

            if (useRecoveryCode) {
                OutlinedTextField(
                    value = recoveryCode,
                    onValueChange = { value ->
                        recoveryCode = value
                            .uppercase()
                            .filter { it.isLetterOrDigit() || it == '-' || it == ' ' }
                            .take(64)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Recovery code") },
                    supportingText = { Text("A recovery code works once.") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    enabled = challengeUsable && !loading,
                    singleLine = true,
                )
                Spacer(Modifier.height(16.dp))
                KitGreenButton(
                    text = "Continue",
                    loading = loading,
                    enabled = challengeUsable && normalizeMfaFactorCode(recoveryCode) != null,
                    onClick = { onVerify(recoveryCode) },
                )
                TextButton(
                    onClick = {
                        recoveryCode = ""
                        useRecoveryCode = false
                    },
                    enabled = challengeUsable && !loading,
                ) {
                    Text("Use an authenticator code")
                }
            } else {
                val autofill = LocalAutofill.current
                val autofillTree = LocalAutofillTree.current
                val focusRequester = remember { FocusRequester() }
                // On this Compose version the autofill tree is the only channel through which
                // the platform can offer an SMS one-time code, so the field advertises itself
                // explicitly. A fill goes through the same digit normalization as typing, and
                // the node is remade with the challenge so a fill can never land on a later one.
                val autofillNode = remember(challengeId, challengeKind) {
                    AutofillNode(
                        autofillTypes = listOf(AutofillType.SmsOtpCode),
                        onFill = { filled ->
                            code = normalizedOtpDigits(filled, OTP_LENGTH)
                        },
                    )
                }
                autofillTree += autofillNode
                LaunchedEffect(challengeUsable) {
                    if (challengeUsable) {
                        // One frame so the field is laid out — and its autofill bounds are
                        // known — before focus asks the platform to offer a code for it.
                        withFrameNanos { }
                        focusRequester.requestFocus()
                    }
                }
                // A real focusable text field: SMS autofill, paste, and hardware keyboards
                // (digits, backspace, Ctrl+V) all land here, while the interceptor keeps the
                // soft keyboard away in favour of the pinned keypad below. The six boxes are
                // its decoration, not the input surface.
                InterceptPlatformTextInput(
                    interceptor = { _, _ -> awaitCancellation() },
                ) {
                    BasicTextField(
                        value = TextFieldValue(code, selection = TextRange(code.length)),
                        onValueChange = { value ->
                            code = normalizedOtpDigits(value.text, OTP_LENGTH)
                        },
                        modifier = Modifier
                            .widthIn(max = 360.dp)
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .onGloballyPositioned {
                                autofillNode.boundingBox = it.boundsInWindow()
                            }
                            .onFocusChanged { state ->
                                autofill?.apply {
                                    // The platform API refuses a request before layout has
                                    // reported bounds; a focus that early simply asks later.
                                    if (autofillNode.boundingBox == null) return@apply
                                    if (state.isFocused) requestAutofillForNode(autofillNode)
                                    else cancelAutofillForNode(autofillNode)
                                }
                            }
                            .semantics {
                                contentDescription = "Verification code"
                                stateDescription =
                                    "${code.length} of $OTP_LENGTH digits entered"
                            }
                            .testTag("otp-code-field"),
                        enabled = challengeUsable && !loading,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done,
                        ),
                        singleLine = true,
                        decorationBox = { innerTextField ->
                            Box {
                                // The functional field is composed but not drawn; the boxes
                                // are its visible form. Cleared semantics keep TalkBack on
                                // the field's own description instead of six empty boxes.
                                Box(Modifier.matchParentSize().alpha(0f)) { innerTextField() }
                                // Width flexes with the screen and height with the font
                                // scale: six boxes always fit a compact phone, and a large
                                // accessibility font grows the box instead of being clipped.
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clearAndSetSemantics { },
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    repeat(OTP_LENGTH) { index ->
                                        val filled = index < code.length
                                        val isNext = index == code.length
                                        Box(
                                            Modifier
                                                .weight(1f)
                                                .heightIn(min = 56.dp)
                                                .background(
                                                    MaterialTheme.colorScheme.surfaceContainerHigh,
                                                    MaterialTheme.shapes.small,
                                                )
                                                .border(
                                                    width = if (isNext) 2.dp else 0.dp,
                                                    color = if (isNext) {
                                                        MaterialTheme.colorScheme.secondary
                                                    } else {
                                                        MaterialTheme.colorScheme.surfaceContainerHigh
                                                    },
                                                    shape = MaterialTheme.shapes.small,
                                                ),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            if (filled) {
                                                Text(
                                                    code[index].toString(),
                                                    style = MaterialTheme.typography.headlineSmall,
                                                    modifier = Modifier.padding(
                                                        horizontal = 2.dp,
                                                        vertical = 8.dp,
                                                    ),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        },
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            if (!challengeUsable) {
                Text(
                    "This verification request is no longer available.",
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (loading && !useRecoveryCode) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                )
            } else if (error != null) {
                Text(
                    error,
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        // Assertive: the failure also cleared the entered code, and someone
                        // not looking at the screen needs to know before re-typing.
                        .semantics { liveRegion = LiveRegionMode.Assertive },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (notice != null) {
                Text(
                    notice,
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (!useRecoveryCode && authenticatorChallenge) {
                TextButton(
                    onClick = {
                        code = ""
                        submittedCode = ""
                        useRecoveryCode = true
                    },
                    enabled = challengeUsable && !loading,
                ) {
                    Text("Use a recovery code")
                }
            } else if (!useRecoveryCode && resendSupported) {
                TextButton(
                    onClick = onResend,
                    enabled = challengeUsable && !loading &&
                        resendNotBeforeElapsedRealtimeMillis != null &&
                        resendSeconds == 0L,
                ) {
                    Text(
                        when {
                            resendNotBeforeElapsedRealtimeMillis == null ->
                                "Resend temporarily unavailable"
                            resendSeconds == 0L -> "Resend code"
                            else -> "Resend code in ${formatResendCountdown(resendSeconds)}"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Fixed spacing, not a weighted spacer: this inner column scrolls, and weights
            // are meaningless under the unbounded height a scroll container measures with.
            Spacer(Modifier.height(16.dp))
            }
            if (!useRecoveryCode && challengeUsable) {
                KitKeypad(
                    onKey = { if (!loading) code = normalizedOtpDigits(code + it, OTP_LENGTH) },
                    onBackspace = { if (!loading) code = code.dropLast(1) },
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun OtpPreview() {
    KitWalletTheme {
        OtpScreen(
            destination = DemoData.USER_PHONE,
            loading = false,
            error = null,
            notice = "This code stays the same until it expires.",
            resendSupported = true,
            challengeId = "preview-challenge",
            challengeKind = AuthChallengeKind.PHONE_OTP,
            resendNotBeforeElapsedRealtimeMillis = null,
            challengeExpiresAtElapsedRealtimeMillis =
                SystemClock.elapsedRealtime() + 5 * 60_000L,
            authenticatorChallenge = false,
            onBack = {},
            onVerify = {},
            onResend = {},
            onChallengeUnavailable = {},
        )
    }
}

internal fun formatResendCountdown(seconds: Long): String {
    val safeSeconds = seconds.coerceAtLeast(0L)
    return "%d:%02d".format(safeSeconds / 60L, safeSeconds % 60L)
}

internal fun challengeSecondsRemaining(
    expiresAtElapsedRealtimeMillis: Long?,
    nowElapsedRealtimeMillis: Long,
): Long {
    val remainingMillis = (expiresAtElapsedRealtimeMillis ?: nowElapsedRealtimeMillis) -
        nowElapsedRealtimeMillis
    return if (remainingMillis <= 0L) 0L else (remainingMillis + 999L) / 1_000L
}

internal fun formatChallengeCountdown(seconds: Long): String = formatResendCountdown(seconds)

/**
 * Reduces any entered text to at most [maxLength] ASCII digits, mapping every Unicode
 * decimal digit to its `0`–`9` value. SMS autofill and paste can deliver localized digits
 * (Arabic-Indic `٤`, Devanagari `४`, fullwidth `４`, …); storing those raw would render in
 * the boxes yet never match the challenge the server issued, so each accepted character is
 * normalized to the digit it denotes and everything else is dropped.
 */
internal fun normalizedOtpDigits(raw: String, maxLength: Int): String = buildString {
    for (ch in raw) {
        if (length == maxLength) break
        val digit = ch.digitToIntOrNull() ?: continue
        append('0' + digit)
    }
}
