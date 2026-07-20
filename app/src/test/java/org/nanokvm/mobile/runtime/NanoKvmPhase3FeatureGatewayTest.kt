package org.nanokvm.mobile.runtime

import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nanokvm.protocol.HttpResponseException
import org.nanokvm.protocol.NanoKvmImageMountMode
import org.nanokvm.protocol.NanoKvmHidMode
import org.nanokvm.protocol.NanoKvmImageTransferState
import org.nanokvm.protocol.NanoKvmImageTransferStatus
import org.nanokvm.protocol.NanoKvmMacAddress
import org.nanokvm.protocol.NanoKvmRemoteImageUrl
import org.nanokvm.protocol.NanoKvmVirtualDevice
import org.nanokvm.protocol.NanoKvmVirtualDeviceToggleResult
import org.nanokvm.protocol.NanoKvmVirtualDevices

class NanoKvmPhase3FeatureGatewayTest {
    @Test
    fun `HID mode is preflighted changed once and confirmed by readback`() = runTest {
        val current = binding()
        val port = FakePhase3Port()
        val gateway = gateway(port, current) { current }

        val result = gateway.setHidMode(NanoKvmHidModeSelection.HID_ONLY)

        assertTrue(result is NanoKvmPhase3MutationResult.Applied)
        assertEquals(NanoKvmHidModeSelection.HID_ONLY, (result as NanoKvmPhase3MutationResult.Applied).state.selection)
        assertEquals(1, port.hidModeSetCalls)
    }

    @Test
    fun `stale session rejects mutation before dispatch`() = runTest {
        val port = FakePhase3Port()
        var current = binding(generation = 3)
        val gateway = gateway(port, binding(generation = 2)) { current }
        gateway.refreshMedia()

        val result = gateway.restorePhysicalMedia()

        assertRejected(result, NanoKvmPhase3Error.Kind.SESSION_CHANGED)
        assertEquals(0, port.restoreCalls)
    }

    @Test
    fun `foreign catalog handle is rejected before dispatch`() = runTest {
        val current = binding()
        val firstPort = FakePhase3Port()
        val secondPort = FakePhase3Port()
        val first = gateway(firstPort, current) { current }
        val second = gateway(secondPort, current) { current }
        val foreignCatalog = success(first.refreshMedia())
        second.refreshMedia()

        val result = second.mountImage(
            foreignCatalog,
            foreignCatalog.images.single(),
            NanoKvmImageMountMode.MASS_STORAGE,
        )

        assertRejected(result, NanoKvmPhase3Error.Kind.FOREIGN_OR_STALE_STATE)
        assertEquals(0, secondPort.mountCalls)
    }

    @Test
    fun `delete performs fresh mounted check and never dispatches for mounted image`() = runTest {
        val current = binding()
        val port = FakePhase3Port()
        val gateway = gateway(port, current) { current }
        val catalog = success(gateway.refreshMedia())
        port.mountedId = "installer.iso"

        val result = gateway.deleteImage(catalog, catalog.images.single())

        assertRejected(result, NanoKvmPhase3Error.Kind.IMAGE_IS_MOUNTED)
        assertEquals(0, port.deleteCalls)
    }

    @Test
    fun `media preflight preserves connection failure instead of reporting session change`() = runTest {
        val current = binding()
        val port = FakePhase3Port()
        val gateway = gateway(port, current) { current }
        gateway.refreshMedia()
        port.imageReadFailure = IOException("offline /data/private.iso")

        val result = gateway.restorePhysicalMedia()

        assertRejected(result, NanoKvmPhase3Error.Kind.CONNECTION)
        assertEquals(0, port.restoreCalls)
    }

    @Test
    fun `only missing optional endpoint statuses are unsupported`() = runTest {
        val current = binding()
        val port = FakePhase3Port()
        val gateway = gateway(port, current) { current }

        listOf(404, 405, 501).forEach { status ->
            port.imageReadFailure = HttpResponseException(status)
            val result = gateway.refreshMedia() as NanoKvmPhase3ReadResult.Failure
            assertEquals(NanoKvmPhase3Error.Kind.UNSUPPORTED, result.error.kind)
        }
        port.imageReadFailure = HttpResponseException(500)
        val failure = gateway.refreshMedia() as NanoKvmPhase3ReadResult.Failure
        assertEquals(NanoKvmPhase3Error.Kind.CONNECTION, failure.error.kind)
    }

    @Test
    fun `toggle reads current state dispatches once and confirms with readback`() = runTest {
        val current = binding()
        val port = FakePhase3Port()
        val gateway = gateway(port, current) { current }

        val result = gateway.setVirtualDeviceEnabled(NanoKvmVirtualDevice.DISK, true)

        assertTrue(result is NanoKvmPhase3MutationResult.Applied<*>)
        val applied = result as NanoKvmPhase3MutationResult.Applied<*>
        assertEquals(true, (applied.state as NanoKvmVirtualDeviceSnapshot).diskEnabled)
        assertEquals(1, port.toggleCalls)
        assertEquals(2, port.virtualDeviceReads)
    }

    @Test
    fun `ambiguous toggle failure is reconciled but never replayed`() = runTest {
        val current = binding()
        val port = FakePhase3Port().apply { failToggleAfterApplying = true }
        val gateway = gateway(port, current) { current }

        val result = gateway.setVirtualDeviceEnabled(NanoKvmVirtualDevice.DISK, true)

        assertTrue(result is NanoKvmPhase3MutationResult.Reconciled<*>)
        val reconciled = result as NanoKvmPhase3MutationResult.Reconciled<*>
        assertEquals(NanoKvmPhase3Observation.DESIRED_STATE, reconciled.observation)
        assertEquals(NanoKvmPhase3Error.Kind.CONNECTION, reconciled.dispatchError.kind)
        assertEquals(1, port.toggleCalls)
        assertEquals(2, port.virtualDeviceReads)
    }

    @Test
    fun `WOL entry from superseded snapshot cannot be renamed`() = runTest {
        val current = binding()
        val port = FakePhase3Port().apply {
            wolEntries += NanoKvmPhase3PortWakeOnLanEntry(
                NanoKvmMacAddress.parse("001122334455"),
                "server",
            )
        }
        val gateway = gateway(port, current) { current }
        val old = success(gateway.refreshWakeOnLan())
        gateway.refreshWakeOnLan()

        val result = gateway.renameWakeOnLanTarget(old, old.targets.single(), "host")

        assertRejected(result, NanoKvmPhase3Error.Kind.FOREIGN_OR_STALE_STATE)
        assertEquals(0, port.renameCalls)
    }

    @Test
    fun `structured errors and handles do not render authority paths or causes`() {
        val binding = binding()
        val image = NanoKvmMediaImage(
            displayName = "secret.iso",
            binding = binding,
            portImage = NanoKvmPhase3PortImage(
                "secret.iso",
                NanoKvmPhase3PortImageIdentity(
                    NanoKvmPhase3PortImageIdentityScope(),
                    "/data/secret.iso",
                ),
            ),
        )

        assertEquals(false, binding.toString().contains("192.168"))
        assertEquals(false, image.toString().contains("secret"))
        assertEquals(false, image.portImage.identity.toString().contains("secret"))
        assertEquals(
            "NanoKvmPhase3Error(kind=CONNECTION)",
            NanoKvmPhase3Error(NanoKvmPhase3Error.Kind.CONNECTION).toString(),
        )
    }

    @Test
    fun `media port catalog requires its exact typed member`() {
        val scope = NanoKvmPhase3PortImageIdentityScope()
        val member = NanoKvmPhase3PortImage(
            "installer.iso",
            NanoKvmPhase3PortImageIdentity(scope, "/data/installer.iso"),
        )
        val lookalike = NanoKvmPhase3PortImage(
            "installer.iso",
            NanoKvmPhase3PortImageIdentity(scope, "/data/installer.iso"),
        )
        val foreign = NanoKvmPhase3PortImage(
            "installer.iso",
            NanoKvmPhase3PortImageIdentity(
                NanoKvmPhase3PortImageIdentityScope(),
                "/data/installer.iso",
            ),
        )
        val catalog = NanoKvmPhase3PortImageCatalog(
            images = listOf(member),
            mountedImage = null,
            hasUnlistedMountedImage = false,
            cdRomEnabled = false,
        )
        assertTrue(member.identity.sameAs(lookalike.identity))
        assertEquals(false, member.identity.sameAs(foreign.identity))

        val failure = assertThrows(IllegalArgumentException::class.java) {
            catalog.requireExactMember(lookalike)
        }

        assertEquals(
            "Image must be an exact member of the supplied port catalog",
            failure.message,
        )
    }

    private fun binding(generation: Long = 1L) = NanoKvmSessionBinding(
        profileId = "office",
        authority = "192.0.2.250",
        sessionGeneration = generation,
    )

    private fun gateway(
        port: NanoKvmPhase3Port,
        binding: NanoKvmSessionBinding,
        current: () -> NanoKvmSessionBinding?,
    ) = NanoKvmPhase3FeatureGateway(port, binding, current)

    private fun <State> success(result: NanoKvmPhase3ReadResult<State>): State =
        (result as NanoKvmPhase3ReadResult.Success<State>).state

    private fun assertRejected(
        result: NanoKvmPhase3MutationResult<*>,
        kind: NanoKvmPhase3Error.Kind,
    ) {
        assertTrue(result is NanoKvmPhase3MutationResult.Rejected)
        assertEquals(kind, (result as NanoKvmPhase3MutationResult.Rejected).error.kind)
    }
}

private class FakePhase3Port : NanoKvmPhase3Port {
    var hidMode: NanoKvmHidMode = NanoKvmHidMode.Normal
    var hidModeSetCalls = 0
    var mountedId: String? = null
    var cdRomEnabled = false
    var restoreCalls = 0
    var mountCalls = 0
    var deleteCalls = 0
    var toggleCalls = 0
    var virtualDeviceReads = 0
    var renameCalls = 0
    var failToggleAfterApplying = false
    var imageReadFailure: Throwable? = null
    var diskEnabled = false
    var networkEnabled = false
    val wolEntries = mutableListOf<NanoKvmPhase3PortWakeOnLanEntry>()
    private val imageIds = mutableListOf("installer.iso")
    private val imageIdentityScope = NanoKvmPhase3PortImageIdentityScope()
    private var latestImageMembers = emptyMap<NanoKvmPhase3PortImage, String>()

    override suspend fun hidMode(): NanoKvmHidMode = hidMode

    override suspend fun setHidMode(mode: NanoKvmHidMode) {
        hidModeSetCalls++
        hidMode = mode
    }

    override suspend fun imageCatalog(): NanoKvmPhase3PortImageCatalog {
        imageReadFailure?.let { throw it }
        val members = imageIds.map { id ->
            NanoKvmPhase3PortImage(
                id,
                NanoKvmPhase3PortImageIdentity(imageIdentityScope, id),
            ) to id
        }
        val images = members.map { it.first }
        latestImageMembers = members.toMap()
        val mounted = mountedId?.let { id ->
            members.firstOrNull { it.second == id }?.first
        }
        return NanoKvmPhase3PortImageCatalog(
            images = images,
            mountedImage = mounted,
            hasUnlistedMountedImage = mountedId != null && mounted == null,
            cdRomEnabled = cdRomEnabled,
        )
    }

    override fun sameImage(
        left: NanoKvmPhase3PortImage,
        right: NanoKvmPhase3PortImage,
    ): Boolean = left.identity.sameAs(right.identity)

    override suspend fun mountImage(
        catalog: NanoKvmPhase3PortImageCatalog,
        image: NanoKvmPhase3PortImage,
        mode: NanoKvmImageMountMode,
    ) {
        catalog.requireExactMember(image)
        mountCalls++
        mountedId = requireNotNull(latestImageMembers[image])
        cdRomEnabled = mode == NanoKvmImageMountMode.CD_ROM
    }

    override suspend fun restorePhysicalMedia() {
        restoreCalls++
        mountedId = null
    }

    override suspend fun deleteImage(
        catalog: NanoKvmPhase3PortImageCatalog,
        image: NanoKvmPhase3PortImage,
    ) {
        catalog.requireExactMember(image)
        deleteCalls++
        imageIds.remove(requireNotNull(latestImageMembers[image]))
    }

    override suspend fun virtualDevices(): NanoKvmVirtualDevices {
        virtualDeviceReads++
        return NanoKvmVirtualDevices(
            network = networkEnabled,
            media = true,
            disk = diskEnabled,
        )
    }

    override suspend fun toggleVirtualDevice(
        device: NanoKvmVirtualDevice,
    ): NanoKvmVirtualDeviceToggleResult {
        toggleCalls++
        val enabled = when (device) {
            NanoKvmVirtualDevice.DISK -> (!diskEnabled).also { diskEnabled = it }
            NanoKvmVirtualDevice.NETWORK -> (!networkEnabled).also { networkEnabled = it }
        }
        if (failToggleAfterApplying) throw IOException("response lost /data/private.iso")
        return NanoKvmVirtualDeviceToggleResult(device, enabled)
    }

    override suspend fun isImageTransferEnabled(): Boolean = true

    override suspend fun startImageTransfer(
        source: NanoKvmRemoteImageUrl,
    ): NanoKvmImageTransferStatus = transferStatus(NanoKvmImageTransferState.InProgress)

    override suspend fun imageTransferStatus(): NanoKvmImageTransferStatus =
        transferStatus(NanoKvmImageTransferState.Idle)

    override suspend fun sendWakeOnLan(macAddress: NanoKvmMacAddress) = Unit

    override suspend fun wakeOnLanHistory(): List<NanoKvmPhase3PortWakeOnLanEntry> =
        wolEntries.toList()

    override suspend fun renameWakeOnLanEntry(macAddress: NanoKvmMacAddress, name: String) {
        renameCalls++
        val index = wolEntries.indexOfFirst { it.macAddress == macAddress }
        if (index >= 0) wolEntries[index] = NanoKvmPhase3PortWakeOnLanEntry(macAddress, name.trim())
    }

    override suspend fun deleteWakeOnLanEntry(macAddress: NanoKvmMacAddress) {
        wolEntries.removeAll { it.macAddress == macAddress }
    }

    private fun transferStatus(state: NanoKvmImageTransferState) = NanoKvmImageTransferStatus(
        state = state,
        source = "",
        percentageText = "",
        percentage = null,
    )
}
