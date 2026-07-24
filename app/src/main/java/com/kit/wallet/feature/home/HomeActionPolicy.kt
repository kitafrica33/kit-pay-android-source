package com.kit.wallet.feature.home

import com.kit.wallet.navigation.AppCapabilities
import com.kit.wallet.navigation.Dest

/** Every service-backed action presented by the home dashboard. */
internal enum class HomeAction(
    val displayName: String,
    val guardedRoute: String?,
) {
    NOTIFICATIONS("Notifications", null),
    SCAN_QR("Scan QR", Dest.SCAN),
    SEND_MONEY("Send money", Dest.SEND),
    RECEIVE_MONEY("Receive money", Dest.RECEIVE),
    REQUEST_MONEY("Request money", Dest.REQUEST),
    VERIFY_IDENTITY("Identity verification", Dest.KYC),
    PAY_BILLS("Bill payments", Dest.BILLS),
    BUY_AIRTIME("Airtime", Dest.AIRTIME),
    BANK("Bank services", Dest.BANK),
    MOBILE_MONEY("Mobile money", Dest.MOBILE_MONEY),
    FAVORITE_SEND("Send to a favorite", Dest.SEND),
    ALL_TRANSACTIONS("Transaction history", Dest.TRANSACTIONS),
    TRANSACTION_DETAIL("Transaction details", Dest.TX_DETAIL),
    ;

    val testTag: String
        get() = "home-action-${name.lowercase().replace('_', '-')}"
}

internal data class HomeActionAccess(
    val available: Boolean,
    val unavailableMessage: String,
)

/**
 * Resolves dashboard discovery separately from route access. The button remains discoverable and
 * tappable while the server/client capability is off; a later capability refresh activates the
 * existing route without changing the dashboard UI.
 */
internal fun AppCapabilities.homeActionAccess(action: HomeAction): HomeActionAccess =
    HomeActionAccess(
        // A null route is a deliberately discoverable client placeholder. It must never become
        // available just because an unrelated server capability happens to be enabled.
        available = action.guardedRoute?.let(::routeUsable) == true,
        unavailableMessage = "Coming soon: ${action.displayName}.",
    )
