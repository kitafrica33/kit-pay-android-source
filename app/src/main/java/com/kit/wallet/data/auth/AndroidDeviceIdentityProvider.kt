package com.kit.wallet.data.auth

import android.content.Context
import android.os.Build
import com.kit.wallet.BuildConfig
import com.kit.wallet.data.remote.DeviceRegistrationDto
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidDeviceIdentityProvider @Inject constructor(
    @ApplicationContext context: Context,
) : DeviceIdentityProvider {
    private val preferences = context.getSharedPreferences("kit_wallet_device", Context.MODE_PRIVATE)

    override fun registration(): DeviceRegistrationDto = DeviceRegistrationDto(
        installationId = installationId(),
        name = Build.MODEL.ifBlank { "Android device" },
        appVersion = BuildConfig.VERSION_NAME,
        osVersion = Build.VERSION.RELEASE,
        model = listOf(Build.MANUFACTURER, Build.MODEL)
            .filter { it.isNotBlank() }
            .joinToString(" "),
    )

    private fun installationId(): String {
        preferences.getString(KEY_INSTALLATION_ID, null)?.let { return it }
        val created = UUID.randomUUID().toString()
        preferences.edit().putString(KEY_INSTALLATION_ID, created).commit()
        return created
    }

    private companion object {
        const val KEY_INSTALLATION_ID = "installation_id"
    }
}
