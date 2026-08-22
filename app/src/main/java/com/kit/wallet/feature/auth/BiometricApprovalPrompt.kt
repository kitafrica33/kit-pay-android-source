package com.kit.wallet.feature.auth

import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import com.kit.wallet.data.auth.BiometricApprovalCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class BiometricApprovalViewModel @Inject constructor(
    private val coordinator: BiometricApprovalCoordinator,
) : ViewModel() {
    val request = coordinator.request
    fun approve(id: String, signature: java.security.Signature) = coordinator.approve(id, signature)
    fun cancel(id: String, message: String) = coordinator.cancel(id, message)
}

@Composable
fun BiometricApprovalPrompt(
    request: com.kit.wallet.data.auth.BiometricApprovalRequest,
    onSuccess: (String, java.security.Signature) -> Unit,
    onCancel: (String, String) -> Unit,
) {
    val activity = LocalContext.current as? FragmentActivity
    LaunchedEffect(request.id) {
        val host = activity ?: run {
            onCancel(request.id, "Biometric authentication is unavailable")
            return@LaunchedEffect
        }
        BiometricPrompt(
            host,
            ContextCompat.getMainExecutor(host),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val signature = result.cryptoObject?.signature
                    if (signature == null) onCancel(request.id, "Biometric signature was unavailable")
                    else onSuccess(request.id, signature)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onCancel(request.id, errString.toString())
                }
            },
        ).authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Approve with biometrics")
                .setSubtitle(request.reason)
                .setNegativeButtonText("Cancel")
                .build(),
            BiometricPrompt.CryptoObject(request.signature),
        )
    }
}
