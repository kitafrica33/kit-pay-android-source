package com.kit.wallet.data.repository

/**
 * What an identity-verification status actually means for the person holding the phone.
 *
 * The server has three separate status vocabularies — the account's own standing, the per-device
 * check, and the compliance case — and they overlap without matching. Screens used to test raw
 * strings against small hand-written sets, so a word that appeared in one vocabulary but not in
 * the set being tested fell through to "not started" and a verified user was offered a fresh
 * identity check. This type exists so that decision is made once, from the full vocabulary, and
 * so that a word nobody anticipated resolves to [UNKNOWN] rather than to a false accusation.
 */
enum class KycVerificationState {
    /** Identity proven; nothing to ask for. */
    VERIFIED,

    /** Submitted and being assessed, whether automatically or by a reviewer. */
    IN_REVIEW,

    /** A check has finished badly, or more information is needed. The user must act. */
    ACTION_NEEDED,

    /** Nothing has been submitted yet. */
    NOT_STARTED,

    /**
     * A status this build does not recognise.
     *
     * Never treated as unverified: an unknown word is a gap in this app's vocabulary, not evidence
     * about the user, and the one thing it must not do is send a verified person back through
     * verification.
     */
    UNKNOWN,
}

/**
 * Reads any of the server's identity statuses, whichever vocabulary it came from.
 *
 * Case and surrounding whitespace are meaningless here — the same value has arrived as `verified`,
 * `Verified` and `In Review` from different layers — so both are normalised away before matching.
 */
fun kycVerificationStateOf(raw: String?): KycVerificationState {
    // The display labels the app builds for itself ("KYC pending") are read back here as readily
    // as the server's own words, so a value can pass through a profile row and still be understood.
    val value = raw?.trim()?.lowercase()?.replace(' ', '_')?.removePrefix("kyc_")
        ?: return KycVerificationState.UNKNOWN
    if (value.isEmpty()) return KycVerificationState.UNKNOWN
    return when (value) {
        // Account standing, device check, compliance case and Didit all agree on these.
        "verified", "approved", "kyc_verified", "not_required", "completed", "success" ->
            KycVerificationState.VERIFIED
        // `review` is the device check's word for it and `in_review` the case's; the two used to
        // be tested separately, which is exactly how one of them went unrecognised.
        "pending", "in_review", "review", "submitted", "processing", "in_progress",
        "resubmitted", "awaiting_user",
        -> KycVerificationState.IN_REVIEW
        "rejected", "declined", "failed", "expired", "abandoned", "needs_information",
        "needs_attention",
        -> KycVerificationState.ACTION_NEEDED
        "unverified", "required", "none", "draft", "not_started" ->
            KycVerificationState.NOT_STARTED
        else -> KycVerificationState.UNKNOWN
    }
}
