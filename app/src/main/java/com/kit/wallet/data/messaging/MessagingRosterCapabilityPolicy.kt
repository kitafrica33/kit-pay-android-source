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
 * In [everyPeerSupports] this device is excluded because it is the sender: it obviously supports
 * what it is about to write, and a roster snapshot can lag its own enrollment.
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

    /**
     * KITMEDIA2 §6: self-attestation plus unanimity — the sender's own roster row must exist and
     * attest the capability too.
     *
     * The album gate runs before uploads, so it has to predict the server's unanimous admission
     * check exactly, and that check includes the sender's row. A roster snapshot that lags this
     * device's own attestation means the server would refuse the send only after every attachment
     * had already been uploaded; reading the lag as "not yet" costs one retry cycle instead.
     * Reactions and corrections upload nothing, so they keep the sender-excluded reading above.
     */
    fun everyDeviceSupports(
        roster: ValidatedMessagingDeviceRoster,
        capability: String,
        currentDeviceId: String,
    ): Boolean {
        val devices = roster.devices()
        return devices.any { it.deviceId == currentDeviceId } &&
            devices.all { it.supportsClientCapability(capability) }
    }
}
