package org.nanokvm.mobile.runtime

import org.nanokvm.protocol.NanoKvmApi
import org.nanokvm.protocol.NanoKvmApplicationVersion
import org.nanokvm.protocol.NanoKvmImage
import org.nanokvm.protocol.NanoKvmImageCatalog
import org.nanokvm.protocol.NanoKvmImageMountMode
import org.nanokvm.protocol.NanoKvmImageTransferStatus
import org.nanokvm.protocol.NanoKvmHidMode
import org.nanokvm.protocol.NanoKvmMacAddress
import org.nanokvm.protocol.NanoKvmRemoteImageUrl
import org.nanokvm.protocol.NanoKvmVirtualDevice
import org.nanokvm.protocol.NanoKvmVirtualDeviceToggleResult
import org.nanokvm.protocol.NanoKvmVirtualDevices

/** Adapter boundary which makes the domain gateway unit-testable without constructing HTTP APIs. */
internal interface NanoKvmPhase3Port {
    suspend fun hidMode(): NanoKvmHidMode
    suspend fun setHidMode(mode: NanoKvmHidMode)

    suspend fun imageCatalog(): NanoKvmPhase3PortImageCatalog
    fun sameImage(left: NanoKvmPhase3PortImage, right: NanoKvmPhase3PortImage): Boolean
    suspend fun mountImage(
        catalog: NanoKvmPhase3PortImageCatalog,
        image: NanoKvmPhase3PortImage,
        mode: NanoKvmImageMountMode,
    )
    suspend fun restorePhysicalMedia()
    suspend fun deleteImage(
        catalog: NanoKvmPhase3PortImageCatalog,
        image: NanoKvmPhase3PortImage,
    )

    suspend fun virtualDevices(): NanoKvmVirtualDevices
    suspend fun toggleVirtualDevice(device: NanoKvmVirtualDevice): NanoKvmVirtualDeviceToggleResult
    suspend fun isImageTransferEnabled(): Boolean
    suspend fun startImageTransfer(source: NanoKvmRemoteImageUrl): NanoKvmImageTransferStatus
    suspend fun imageTransferStatus(): NanoKvmImageTransferStatus

    suspend fun sendWakeOnLan(macAddress: NanoKvmMacAddress)
    suspend fun wakeOnLanHistory(): List<NanoKvmPhase3PortWakeOnLanEntry>
    suspend fun renameWakeOnLanEntry(macAddress: NanoKvmMacAddress, name: String)
    suspend fun deleteWakeOnLanEntry(macAddress: NanoKvmMacAddress)
}

internal class NanoKvmPhase3PortImage internal constructor(
    val displayName: String,
    internal val opaqueToken: Any,
) {
    init {
        require(displayName.isNotBlank()) { "Image display name must not be blank" }
    }

    override fun toString(): String = "NanoKvmPhase3PortImage(<redacted>)"
}

internal class NanoKvmPhase3PortImageCatalog internal constructor(
    val images: List<NanoKvmPhase3PortImage>,
    val mountedImage: NanoKvmPhase3PortImage?,
    val hasUnlistedMountedImage: Boolean,
    val cdRomEnabled: Boolean,
    internal val opaqueToken: Any,
) {
    init {
        require(mountedImage == null || images.any { it === mountedImage }) {
            "Mounted image must be an exact port catalog member"
        }
    }

    override fun toString(): String =
        "NanoKvmPhase3PortImageCatalog(images=${images.size}, mounted=${mountedImage != null})"
}

internal data class NanoKvmPhase3PortWakeOnLanEntry(
    val macAddress: NanoKvmMacAddress,
    val name: String?,
)

/** Official NanoKVM 2.4.3 REST adapter. Protocol catalog/path types never cross this boundary. */
internal class NanoKvmProtocolPhase3Port(
    private val api: NanoKvmApi,
    private val applicationVersion: NanoKvmApplicationVersion?,
) : NanoKvmPhase3Port {
    override suspend fun hidMode(): NanoKvmHidMode = api.hidMode()

    override suspend fun setHidMode(mode: NanoKvmHidMode) = api.setHidMode(mode)

    override suspend fun imageCatalog(): NanoKvmPhase3PortImageCatalog {
        val protocolCatalog = api.listImages()
        val mounted = api.mountedImage()
        val cdRom = api.cdRomState()
        val images = protocolCatalog.images.map { image ->
            NanoKvmPhase3PortImage(
                displayName = image.fileName,
                opaqueToken = ProtocolImageToken(image),
            )
        }
        val mountedImage = mounted?.let { mountedState ->
            protocolCatalog.images.indexOfFirst { it.path == mountedState.path }
                .takeIf { it >= 0 }
                ?.let(images::get)
        }
        return NanoKvmPhase3PortImageCatalog(
            images = images,
            mountedImage = mountedImage,
            hasUnlistedMountedImage = mounted != null && mountedImage == null,
            cdRomEnabled = cdRom.enabled,
            opaqueToken = ProtocolCatalogToken(protocolCatalog),
        )
    }

    override fun sameImage(
        left: NanoKvmPhase3PortImage,
        right: NanoKvmPhase3PortImage,
    ): Boolean = left.protocolImage().path == right.protocolImage().path

    override suspend fun mountImage(
        catalog: NanoKvmPhase3PortImageCatalog,
        image: NanoKvmPhase3PortImage,
        mode: NanoKvmImageMountMode,
    ) {
        api.mountImage(catalog.protocolCatalog(), image.protocolImage(), mode)
    }

    override suspend fun restorePhysicalMedia() = api.restorePhysicalMedia()

    override suspend fun deleteImage(
        catalog: NanoKvmPhase3PortImageCatalog,
        image: NanoKvmPhase3PortImage,
    ) {
        api.deleteImage(catalog.protocolCatalog(), image.protocolImage())
    }

    override suspend fun virtualDevices(): NanoKvmVirtualDevices = api.virtualDevices()

    override suspend fun toggleVirtualDevice(
        device: NanoKvmVirtualDevice,
    ): NanoKvmVirtualDeviceToggleResult = api.toggleVirtualDevice(device)

    override suspend fun isImageTransferEnabled(): Boolean = api.isImageTransferEnabled()

    override suspend fun startImageTransfer(
        source: NanoKvmRemoteImageUrl,
    ): NanoKvmImageTransferStatus = api.startImageTransfer(source)

    override suspend fun imageTransferStatus(): NanoKvmImageTransferStatus =
        api.imageTransferStatus()

    override suspend fun sendWakeOnLan(macAddress: NanoKvmMacAddress) =
        api.sendWakeOnLan(macAddress)

    override suspend fun wakeOnLanHistory(): List<NanoKvmPhase3PortWakeOnLanEntry> =
        api.wakeOnLanHistory(applicationVersion).map {
            NanoKvmPhase3PortWakeOnLanEntry(it.mac, it.name)
        }

    override suspend fun renameWakeOnLanEntry(macAddress: NanoKvmMacAddress, name: String) =
        api.renameWakeOnLanEntry(macAddress, name)

    override suspend fun deleteWakeOnLanEntry(macAddress: NanoKvmMacAddress) =
        api.deleteWakeOnLanEntry(macAddress)

    private fun NanoKvmPhase3PortImageCatalog.protocolCatalog(): NanoKvmImageCatalog =
        (opaqueToken as? ProtocolCatalogToken)?.catalog
            ?: throw IllegalArgumentException("Foreign image catalog")

    private fun NanoKvmPhase3PortImage.protocolImage(): NanoKvmImage =
        (opaqueToken as? ProtocolImageToken)?.image
            ?: throw IllegalArgumentException("Foreign image handle")

    private class ProtocolCatalogToken(val catalog: NanoKvmImageCatalog) {
        override fun toString(): String = "ProtocolCatalogToken(<redacted>)"
    }

    private class ProtocolImageToken(val image: NanoKvmImage) {
        override fun toString(): String = "ProtocolImageToken(<redacted>)"
    }
}

/**
 * Creates a Phase 3 gateway bound to this authenticated client and the backend's live generation.
 * [currentBinding] must return null as soon as command acceptance closes.
 */
internal fun AuthenticatedNanoKvmSession.createPhase3Gateway(
    sessionGeneration: Long,
    currentBinding: () -> NanoKvmSessionBinding?,
): NanoKvmPhase3FeatureGateway {
    val captured = NanoKvmSessionBinding(profileId, authority, sessionGeneration)
    return NanoKvmPhase3FeatureGateway(
        port = NanoKvmProtocolPhase3Port(
            api = client.api,
            applicationVersion = NanoKvmApplicationVersion.parse(vmInfo.application),
        ),
        binding = captured,
        currentBinding = currentBinding,
    )
}
