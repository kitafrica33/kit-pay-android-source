package com.kit.wallet.data.messaging

import com.kit.wallet.data.remote.ValidatedMessagingDeviceRoster

/**
 * Whether every other device in a conversation has told the server it understands a descriptor.
 *
 * Reactions and corrections both ride the ordinary encrypted message body as reserved-prefix
 * descriptors, so a peer that predates one would render it as a chat bubble full of protocol
 * text. The decision is therefore unanimous and fail-closed: one device that has not attested the
 * capability withholds the feature from the whole conversation, whether that is a direct chat with
 * a single stale phone or a group of thirty where one member never updated.
 *
 * This device is excluded because it is the sender: it obviously supports what it is about to
 * write, and a roster snapshot can lag its own enrollment.
 *
 * Kept apart from the transport so the rule can be exercised against real validated rosters —
 * mixed-version direct chats and mixed-version groups alike — without standing up a session.
 * iOS decides the same question in `MessagingRosterCapabilityPolicy`.
 */
internal object MessagingRosterCapabilityPolicy {
    fun everyPeerSupports(
        roster: ValidatedMessagingDeviceRoster,
        capability: String,
        currentDeviceId: String,
    ): Boolean = roster.devices().all { device ->
        device.deviceId == currentDeviceId || device.supportsClientCapability(capability)
    }
}
