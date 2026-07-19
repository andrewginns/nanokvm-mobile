package org.nanokvm.mobile.security

import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/** UI-facing seam for the Android system authentication prompt. */
interface CredentialAuthenticator {
    val canProtectPasswords: Boolean
    fun authenticate(request: CredentialPromptRequest)
    fun cancel()
}

/**
 * Hosts BiometricPrompt while all durable operation state remains in AppViewModel. The retained
 * coordinator stores only a request ID, so recreation cannot retain plaintext or stale UI code.
 */
class DeviceCredentialAuthenticator internal constructor(
    private val activity: FragmentActivity,
    private val coordinator: CredentialAuthenticationCoordinator,
) : CredentialAuthenticator {
    private val hostToken = coordinator.reserveHostToken()
    private val allowedAuthenticators = allowedAuthenticators()
    private val biometricPrompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        PromptCallback(coordinator, hostToken),
    ).also { coordinator.activateHost(hostToken) }

    override val canProtectPasswords: Boolean
        get() = BiometricManager.from(activity).canAuthenticate(allowedAuthenticators) ==
            BiometricManager.BIOMETRIC_SUCCESS

    override fun authenticate(request: CredentialPromptRequest) {
        if (!canProtectPasswords) {
            when (coordinator.begin(hostToken, request)) {
                PromptBeginResult.Started -> coordinator.cancel(
                    hostToken,
                    "Set up a screen lock or strong biometric before saving or unlocking a password.",
                )
                else -> Unit
            }
            return
        }
        when (coordinator.begin(hostToken, request)) {
            PromptBeginResult.Started -> startPrompt(request)
            // AndroidX BiometricPrompt reconnects its Fragment and callback after recreation.
            PromptBeginResult.AlreadyActive -> Unit
            PromptBeginResult.Busy -> Unit
            PromptBeginResult.StaleHost -> Unit
        }
    }

    /** Composition disposal during configuration change must not cancel AndroidX prompt reattachment. */
    override fun cancel() = Unit

    internal fun hostDestroyed(isFinishing: Boolean, isChangingConfigurations: Boolean) {
        if (isChangingConfigurations || !isFinishing) return
        coordinator.cancelAll()
        biometricPrompt.cancelAuthentication()
    }

    private fun startPrompt(request: CredentialPromptRequest) {
        val builder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(
                if (request.kind == CredentialPromptKind.Unlock) {
                    "Unlock saved NanoKVM password"
                } else {
                    "Protect NanoKVM password"
                },
            )
            .setSubtitle(
                if (request.kind == CredentialPromptKind.Unlock) {
                    "Authenticate to connect to ${request.profileName}"
                } else {
                    "Authenticate before saving the password for ${request.profileName}"
                },
            )
            .setAllowedAuthenticators(allowedAuthenticators)
            .setConfirmationRequired(false)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            builder.setNegativeButtonText(
                if (request.kind == CredentialPromptKind.Unlock) "Use password instead" else "Not now",
            )
        }
        try {
            biometricPrompt.authenticate(builder.build())
        } catch (_: Throwable) {
            coordinator.cancel(hostToken, "Android could not start device authentication.")
        }
    }

    private class PromptCallback(
        private val coordinator: CredentialAuthenticationCoordinator,
        private val hostToken: Long,
    ) : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            val requestId = currentRequestId() ?: return
            coordinator.complete(hostToken, CredentialPromptResult.Authenticated(requestId))
        }

        override fun onAuthenticationError(errorCode: Int, errorString: CharSequence) {
            val message = if (isUserCancellation(errorCode)) {
                null
            } else {
                "Device authentication failed. Enter the NanoKVM password instead."
            }
            coordinator.cancel(hostToken, message)
        }

        private fun currentRequestId(): Long? = coordinator.pendingRequestId(hostToken)

        private fun isUserCancellation(errorCode: Int): Boolean =
            errorCode == BiometricPrompt.ERROR_CANCELED ||
                errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON
    }

    private companion object {
        fun allowedAuthenticators(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        }
    }
}
