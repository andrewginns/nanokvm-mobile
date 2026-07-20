package org.nanokvm.mobile.security

import android.os.Build
import androidx.annotation.StringRes
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import org.nanokvm.mobile.R

/** UI-facing seam for the Android system authentication prompt. */
interface CredentialAuthenticator {
    val canProtectPasswords: Boolean
    fun authenticate(request: CredentialPromptRequest)
    fun cancel()
}

@StringRes
internal fun credentialPromptTitleResource(kind: CredentialPromptKind): Int = when (kind) {
    CredentialPromptKind.Unlock -> R.string.credential_prompt_unlock_title
    CredentialPromptKind.Save -> R.string.credential_prompt_save_title
}

@StringRes
internal fun credentialPromptSubtitleResource(kind: CredentialPromptKind): Int = when (kind) {
    CredentialPromptKind.Unlock -> R.string.credential_prompt_unlock_subtitle
    CredentialPromptKind.Save -> R.string.credential_prompt_save_subtitle
}

@StringRes
internal fun credentialPromptNegativeButtonResource(kind: CredentialPromptKind): Int = when (kind) {
    CredentialPromptKind.Unlock -> R.string.credential_prompt_use_password
    CredentialPromptKind.Save -> R.string.credential_prompt_not_now
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
                    CredentialPromptFailure.DeviceProtectionUnavailable,
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
                activity.getString(credentialPromptTitleResource(request.kind)),
            )
            .setSubtitle(
                activity.getString(
                    credentialPromptSubtitleResource(request.kind),
                    request.profileName,
                ),
            )
            .setAllowedAuthenticators(allowedAuthenticators)
            .setConfirmationRequired(false)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            builder.setNegativeButtonText(
                activity.getString(credentialPromptNegativeButtonResource(request.kind)),
            )
        }
        try {
            biometricPrompt.authenticate(builder.build())
        } catch (_: Throwable) {
            coordinator.cancel(hostToken, CredentialPromptFailure.AuthenticationStartFailed)
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
            val failure = if (isUserCancellation(errorCode)) {
                null
            } else {
                CredentialPromptFailure.AuthenticationFailed
            }
            coordinator.cancel(hostToken, failure)
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
