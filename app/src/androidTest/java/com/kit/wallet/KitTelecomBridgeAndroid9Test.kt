package com.kit.wallet

import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.kit.wallet.feature.calls.KitCallConnectionService
import com.kit.wallet.feature.calls.KitTelecomBridge
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.P, maxSdkVersion = Build.VERSION_CODES.P)
class KitTelecomBridgeAndroid9Test {
    @Test
    @Suppress("DEPRECATION")
    fun connectionServiceOnlyAndroid9DeviceRegistersSelfManagedPhoneAccount() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val packageManager = context.packageManager
        assumeFalse(packageManager.hasSystemFeature(PackageManager.FEATURE_TELECOM))
        assumeTrue(packageManager.hasSystemFeature(PackageManager.FEATURE_CONNECTION_SERVICE))

        val telecom = context.getSystemService(TelecomManager::class.java)
        val accountHandle = PhoneAccountHandle(
            ComponentName(context, KitCallConnectionService::class.java),
            "kit-pay-self-managed-v1",
        )
        telecom.unregisterPhoneAccount(accountHandle)

        assertTrue(KitTelecomBridge(context).registerPhoneAccount())
        val account = checkNotNull(telecom.getPhoneAccount(accountHandle))
        assertTrue(account.hasCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED))
        assertTrue(account.extras.getBoolean(PhoneAccount.EXTRA_LOG_SELF_MANAGED_CALLS))
    }
}
