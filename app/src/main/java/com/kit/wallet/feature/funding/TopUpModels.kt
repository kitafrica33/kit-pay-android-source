package com.kit.wallet.feature.funding

import com.kit.wallet.ui.model.AccountVerification

/** Where topped-up money is pulled from. */
enum class TopUpChannel {
    MOBILE_MONEY,
    BANK,
    ;

    val label: String
        get() = when (this) {
            MOBILE_MONEY -> "Mobile money"
            BANK -> "Bank account"
        }
}

/**
 * One account the wallet can be topped up from.
 *
 * Only accounts that belong to the signed-in person can appear here — you cannot pull money out of
 * somebody else's mobile money wallet — so this list is deliberately narrower than the beneficiary
 * lists the cash-out and transfer screens draw from.
 */
data class TopUpSource(
    val id: String,
    val channel: TopUpChannel,
    val title: String,
    val detail: String,
    val currencyCode: String,
    val currencyScale: Int,
    /** The face to draw beside the row, when this device can establish one honestly. */
    val avatarUrl: String? = null,
    /** The signed-in owner's server designation, when the source is linked to that account. */
    val accountVerification: AccountVerification? = null,
)

/** How far along a top-up is. */
sealed interface TopUpStage {
    /** Picking the account the money comes from, or adding one. */
    data object ChooseSource : TopUpStage

    /** A quote is on screen, waiting for approval. */
    data object Review : TopUpStage

    /** Approved and submitted; the money has not reached the wallet yet. */
    data object Waiting : TopUpStage

    /** The wallet now covers the payment that was blocked. */
    data object Funded : TopUpStage

    /**
     * Submitted, but still moving when we stopped waiting.
     *
     * Deliberately distinct from a failure: the money has not gone anywhere wrong, and telling
     * somebody their top-up failed while their provider is still processing it would be a lie that
     * invites them to send it twice.
     */
    data object StillMoving : TopUpStage
}
