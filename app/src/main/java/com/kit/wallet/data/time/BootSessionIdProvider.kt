package com.kit.wallet.data.time

import android.content.Context
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Stable for one boot and incremented by Android after every successful reboot. */
fun interface BootSessionIdProvider {
    /** Null fails closed when Android/OEM settings cannot provide a trustworthy boot identity. */
    fun currentBootId(): Long?
}

@Singleton
class AndroidBootSessionIdProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : BootSessionIdProvider {
    override fun currentBootId(): Long? = runCatching {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT).toLong()
    }.getOrNull()?.takeIf { it >= 0L }
}
