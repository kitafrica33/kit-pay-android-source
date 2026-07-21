package com.kit.wallet.data.auth

private val SIX_DIGIT_CODE = Regex("^[0-9]{6}$")
private val RECOVERY_CODE = Regex("^[A-F0-9]{20}$")

internal fun normalizeSixDigitCode(raw: String): String? =
    raw.trim().takeIf { it.matches(SIX_DIGIT_CODE) }

internal fun normalizeMfaFactorCode(raw: String): String? {
    normalizeSixDigitCode(raw)?.let { return it }

    val normalized = raw
        .trim()
        .uppercase()
        .filter { it.isLetterOrDigit() }

    return normalized.takeIf { it.matches(RECOVERY_CODE) }
}
