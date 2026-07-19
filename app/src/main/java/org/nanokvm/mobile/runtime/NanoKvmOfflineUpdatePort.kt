package org.nanokvm.mobile.runtime

import kotlinx.coroutines.CoroutineScope
import org.nanokvm.protocol.NanoKvmApi
import org.nanokvm.protocol.NanoKvmApplicationVersion
import org.nanokvm.protocol.NanoKvmCapability
import org.nanokvm.protocol.NanoKvmCapabilitySupport
import org.nanokvm.protocol.NanoKvmOfflineUpdatePackage
import org.nanokvm.protocol.NanoKvmOfflineUpdateStream

/** Unit-testable boundary for the one-shot protocol upload. */
internal interface NanoKvmOfflineUpdatePort {
    suspend fun upload(
        payload: NanoKvmOfflineUpdatePayload,
        onProgress: (bytesTransferred: Long, totalBytes: Long) -> Unit,
    )
}

/** Official protocol adapter. It consumes and drops the document opener before returning. */
internal class NanoKvmProtocolOfflineUpdatePort(
    private val api: NanoKvmApi,
) : NanoKvmOfflineUpdatePort {
    override suspend fun upload(
        payload: NanoKvmOfflineUpdatePayload,
        onProgress: (bytesTransferred: Long, totalBytes: Long) -> Unit,
    ) {
        val source = payload.consumeForProtocol()
        try {
            val packageFile = NanoKvmOfflineUpdatePackage.create(
                fileName = source.fileName,
                contentLength = source.contentLength,
                stream = NanoKvmOfflineUpdateStream(source::openOnce),
            )
            api.startOfflineUpdate(packageFile) { progress ->
                onProgress(progress.bytesTransferred, progress.totalBytes)
            }
        } finally {
            source.close()
            payload.close()
        }
    }
}

/** Creates an isolated gateway for one exact authenticated session generation. */
internal fun AuthenticatedNanoKvmSession.createOfflineUpdateGateway(
    sessionGeneration: Long,
    scope: CoroutineScope,
    currentBinding: () -> NanoKvmSessionBinding?,
    onAuthenticationExpired: () -> Unit,
): NanoKvmOfflineUpdateGateway {
    val binding = NanoKvmSessionBinding(profileId, authority, sessionGeneration)
    val supported = capabilities[NanoKvmCapability.OFFLINE_UPDATE] is
        NanoKvmCapabilitySupport.Supported
    return NanoKvmOfflineUpdateGateway(
        port = NanoKvmProtocolOfflineUpdatePort(client.api),
        binding = binding,
        currentBinding = currentBinding,
        scope = scope,
        destinationAuthority = authority,
        installedVersion = NanoKvmApplicationVersion.parse(vmInfo.application)?.toString(),
        supported = supported,
        onAuthenticationExpired = onAuthenticationExpired,
    )
}
