package com.kit.wallet.data.auth

import com.kit.wallet.data.remote.ApiCallExecutor
import com.kit.wallet.data.remote.KitWalletApi
import com.kit.wallet.data.remote.SetPaymentPinRequest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PaymentPinRepository @Inject constructor(
    private val api: KitWalletApi,
    private val apiCalls: ApiCallExecutor,
) {
    suspend fun set(pin: String, currentPin: String? = null) {
        require(pin.matches(Regex("^[0-9]{4}$"))) { "Enter a four-digit wallet PIN" }
        val status = apiCalls.execute {
            api.setPaymentPin(SetPaymentPinRequest(pin, pin, currentPin))
        }
        check(status.paymentPinSet == true) { "The wallet PIN was not enabled" }
    }
}
