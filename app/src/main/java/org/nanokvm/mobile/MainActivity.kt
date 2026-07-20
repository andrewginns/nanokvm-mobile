package org.nanokvm.mobile

import android.content.ClipDescription
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.text.Spanned
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import org.nanokvm.mobile.security.CredentialAuthenticationCoordinator
import org.nanokvm.mobile.security.DeviceCredentialAuthenticator
import org.nanokvm.mobile.ui.AppViewModel
import org.nanokvm.mobile.ui.NanoKvmApp
import org.nanokvm.mobile.ui.ShareNotice
import org.nanokvm.mobile.clipboard.ClipboardPayloadAnalyzer
import org.nanokvm.mobile.clipboard.ClipboardPayloadAnalysis
import org.nanokvm.mobile.clipboard.ClipboardReadResult
import org.nanokvm.mobile.platform.AndroidClipboardGateway

class MainActivity : FragmentActivity() {
    private var credentialAuthenticator: DeviceCredentialAuthenticator? = null
    private var appViewModel: AppViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setRecentsScreenshotEnabled(false)
        }
        if (!BuildConfig.DEBUG) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }

        val container = (application as NanoKvmApplication).container
        val savedCredentialStore = container.savedCredentialStore
        val authenticationCoordinator =
            ViewModelProvider(this)[CredentialAuthenticationCoordinator::class.java]
        val authenticator = DeviceCredentialAuthenticator(
            activity = this,
            coordinator = authenticationCoordinator,
        )
        credentialAuthenticator = authenticator
        val factory = AppViewModel.Factory(
            profilesRepository = container.profileRepository,
            appSettingsStore = container.appSettingsRepository,
            backendProvider = container.backendFactory::create,
            savedCredentialStore = savedCredentialStore,
            credentialResults = authenticationCoordinator.results,
            localNetworkAccess = container.localNetworkAccess,
        )
        val viewModel = ViewModelProvider(this, factory)[AppViewModel::class.java]
        appViewModel = viewModel
        if (intent.action == Intent.ACTION_SEND) {
            if (savedInstanceState == null) {
                receiveSharedText(intent, viewModel)
            } else {
                discardSharedTextIntent(intent)
            }
        }

        setContent {
            NanoKvmApp(viewModel, authenticator, container.clipboardGateway)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_SEND) {
            appViewModel?.let { receiveSharedText(intent, it) } ?: discardSharedTextIntent(intent)
        } else {
            setIntent(intent)
        }
    }

    override fun onStop() {
        if (!isChangingConfigurations) {
            appViewModel?.clearSensitiveWorkForBackground()
            appViewModel?.setForeground(false)
        }
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        appViewModel?.setForeground(true)
    }

    override fun onDestroy() {
        credentialAuthenticator?.hostDestroyed(
            isFinishing = isFinishing,
            isChangingConfigurations = isChangingConfigurations,
        )
        credentialAuthenticator = null
        appViewModel = null
        super.onDestroy()
    }

    private fun receiveSharedText(intent: Intent, viewModel: AppViewModel) {
        if (intent.action != Intent.ACTION_SEND) return
        try {
            if (!intent.type.equals(MIME_TEXT_PLAIN, ignoreCase = true)) {
                viewModel.reportShareNotice(ShareNotice.PlainTextOnly)
                return
            }

            val clipboardResult = intent.clipData?.let { clip ->
                AndroidClipboardGateway { clip }.readDirectPlainText()
            }
            val payloadAnalysis = when (clipboardResult) {
                is ClipboardReadResult.Available -> ClipboardPayloadAnalysis.Accepted(
                    clipboardResult.payload,
                )
                null -> {
                    val directText = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
                    if (directText == null || directText is Spanned || directText.isEmpty()) {
                        viewModel.reportShareNotice(ShareNotice.PlainTextOnly)
                        return
                    }
                    ClipboardPayloadAnalyzer.analyzeDirectPlainTextAtIngress(
                        text = directText,
                        isSensitive = intent.hasSensitiveTextMarker(),
                    )
                }
                else -> {
                    viewModel.reportShareNotice(ShareNotice.PlainTextOnly)
                    return
                }
            }
            when (payloadAnalysis) {
                is ClipboardPayloadAnalysis.Accepted -> {
                    viewModel.receiveSharedPlainText(payloadAnalysis.payload)
                }
                ClipboardPayloadAnalysis.TooLarge -> {
                    viewModel.reportShareNotice(ShareNotice.TooLarge)
                }
            }
        } finally {
            discardSharedTextIntent(intent)
        }
    }

    /** Drops both payload references and the Activity's launch-intent reference after one attempt. */
    private fun discardSharedTextIntent(sharedIntent: Intent) {
        sharedIntent.clipData = null
        sharedIntent.replaceExtras(Bundle())
        sharedIntent.action = null
        sharedIntent.type = null
        sharedIntent.data = null
        sharedIntent.selector = null
        setIntent(Intent())
    }

    private fun Intent.hasSensitiveTextMarker(): Boolean {
        val key = if (Build.VERSION.SDK_INT >= 33) {
            ClipDescription.EXTRA_IS_SENSITIVE
        } else {
            LEGACY_SENSITIVE_EXTRA
        }
        return extras?.getBoolean(key, false) == true
    }

    private companion object {
        const val MIME_TEXT_PLAIN = "text/plain"
        const val LEGACY_SENSITIVE_EXTRA = "android.content.extra.IS_SENSITIVE"
    }
}
