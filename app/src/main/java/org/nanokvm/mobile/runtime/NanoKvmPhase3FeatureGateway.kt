package org.nanokvm.mobile.runtime

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.nanokvm.protocol.ApiResponseException
import org.nanokvm.protocol.AuthenticationExpiredException
import org.nanokvm.protocol.HttpResponseException
import org.nanokvm.protocol.InvalidApiResponseException
import org.nanokvm.protocol.NanoKvmImageMountMode
import org.nanokvm.protocol.NanoKvmImageTransferState
import org.nanokvm.protocol.NanoKvmHidMode
import org.nanokvm.protocol.NanoKvmMacAddress
import org.nanokvm.protocol.NanoKvmRemoteImageUrl
import org.nanokvm.protocol.NanoKvmVirtualDevice
import org.nanokvm.protocol.NanoKvmVirtualDevices

/** Exact authenticated destination captured when a low-frequency feature gateway is created. */
internal data class NanoKvmSessionBinding(
    val profileId: String,
    val authority: String,
    val sessionGeneration: Long,
) {
    init {
        require(profileId.isNotBlank()) { "Profile id must not be blank" }
        require(authority.isNotBlank()) { "Authority must not be blank" }
        require(sessionGeneration >= 0L) { "Session generation must not be negative" }
    }

    override fun toString(): String =
        "NanoKvmSessionBinding(profileId=<redacted>, authority=<redacted>, " +
            "sessionGeneration=$sessionGeneration)"
}

/** Errors safe to retain in UI state, logs, and saved-state diagnostics. */
internal data class NanoKvmPhase3Error(
    val kind: Kind,
) {
    enum class Kind {
        SESSION_CHANGED,
        FOREIGN_OR_STALE_STATE,
        IMAGE_IS_MOUNTED,
        IMAGE_TRANSFER_DISABLED,
        INVALID_REQUEST,
        UNSUPPORTED,
        AUTHENTICATION_EXPIRED,
        CONNECTION,
        SERVER_REJECTED,
        INVALID_RESPONSE,
        UNEXPECTED,
    }
}

internal sealed interface NanoKvmPhase3ReadResult<out State> {
    data class Success<State>(val state: State) : NanoKvmPhase3ReadResult<State>
    data class Failure(val error: NanoKvmPhase3Error) : NanoKvmPhase3ReadResult<Nothing>
}

/** What an authoritative readback observed after a mutation's response was ambiguous. */
internal enum class NanoKvmPhase3Observation {
    DESIRED_STATE,
    OTHER_STATE,
}

/**
 * Structured mutation result which deliberately never retains a Throwable, URL, or server path.
 * A gateway dispatches each mutation at most once. [Reconciled] is a readback, never a retry.
 */
internal sealed interface NanoKvmPhase3MutationResult<out State> {
    /** The appliance acknowledged the mutation and authoritative readback observed its result. */
    data class Applied<State>(val state: State) : NanoKvmPhase3MutationResult<State>

    /** No mutation was dispatched because authoritative state already matched the request. */
    data class AlreadySatisfied<State>(val state: State) : NanoKvmPhase3MutationResult<State>

    /** The appliance acknowledged the mutation, but refresh failed or did not show the target. */
    data class Accepted<State>(
        val state: State?,
        val refreshError: NanoKvmPhase3Error?,
    ) : NanoKvmPhase3MutationResult<State>

    /** Dispatch failed ambiguously and a separate authoritative readback completed. */
    data class Reconciled<State>(
        val state: State,
        val observation: NanoKvmPhase3Observation,
        val dispatchError: NanoKvmPhase3Error,
    ) : NanoKvmPhase3MutationResult<State>

    /** Neither the dispatch response nor a later readback establishes the final state. */
    data class Indeterminate<State>(
        val state: State?,
        val dispatchError: NanoKvmPhase3Error,
        val refreshError: NanoKvmPhase3Error?,
    ) : NanoKvmPhase3MutationResult<State>

    /** A local validation or authoritative server rejection proves no replay is appropriate. */
    data class Rejected(val error: NanoKvmPhase3Error) : NanoKvmPhase3MutationResult<Nothing>
}

internal enum class NanoKvmHidModeSelection {
    NORMAL,
    HID_ONLY,
    OTHER,
}

/** Session-bound appliance USB gadget mode; unknown future modes remain visible but read-only. */
internal data class NanoKvmHidModeSnapshot(
    val selection: NanoKvmHidModeSelection,
    val reportedMode: String?,
    internal val binding: NanoKvmSessionBinding,
)

/** UI-safe image handle. It carries no server path and is valid only in its exact catalog. */
internal class NanoKvmMediaImage internal constructor(
    val displayName: String,
    internal val binding: NanoKvmSessionBinding,
    internal val portImage: NanoKvmPhase3PortImage,
) {
    override fun toString(): String = "NanoKvmMediaImage(displayName=<redacted>)"
}

/** Session-bound authoritative media snapshot with identity-scoped opaque handles. */
internal class NanoKvmMediaCatalog internal constructor(
    val images: List<NanoKvmMediaImage>,
    val mountedImage: NanoKvmMediaImage?,
    val hasUnlistedMountedImage: Boolean,
    val cdRomEnabled: Boolean,
    internal val binding: NanoKvmSessionBinding,
    internal val portCatalog: NanoKvmPhase3PortImageCatalog,
) {
    init {
        require(mountedImage == null || images.any { it === mountedImage }) {
            "Mounted image must be an exact member of the media catalog"
        }
    }

    override fun toString(): String =
        "NanoKvmMediaCatalog(images=${images.size}, mounted=${mountedImage != null}, " +
            "unlistedMounted=$hasUnlistedMountedImage, cdRomEnabled=$cdRomEnabled)"
}

/** Session-bound virtual USB device state. */
internal data class NanoKvmVirtualDeviceSnapshot(
    val networkEnabled: Boolean,
    val mediaEnabled: Boolean,
    val diskEnabled: Boolean,
    internal val binding: NanoKvmSessionBinding,
)

internal enum class NanoKvmTransferPhase {
    IDLE,
    IN_PROGRESS,
    OTHER,
}

/** Image-transfer state intentionally omits the source URL/path returned by the appliance. */
internal data class NanoKvmImageTransferSnapshot(
    val enabled: Boolean,
    val phase: NanoKvmTransferPhase,
    val percentage: Double?,
    internal val binding: NanoKvmSessionBinding,
)

/** Exact WOL history entry; rename/delete require its originating snapshot by identity. */
internal class NanoKvmWakeOnLanTarget internal constructor(
    val macAddress: NanoKvmMacAddress,
    val name: String?,
    internal val binding: NanoKvmSessionBinding,
) {
    override fun toString(): String =
        "NanoKvmWakeOnLanTarget(macAddress=<redacted>, name=<redacted>)"
}

internal class NanoKvmWakeOnLanSnapshot internal constructor(
    val targets: List<NanoKvmWakeOnLanTarget>,
    internal val binding: NanoKvmSessionBinding,
) {
    override fun toString(): String = "NanoKvmWakeOnLanSnapshot(targets=${targets.size})"
}

/**
 * Session-safe virtual-media and Wake-on-LAN domain gateway.
 *
 * The supplied [currentBinding] must read the backend's live destination. Every mutation checks
 * it immediately before dispatch; completing a read after a destination change is also discarded.
 */
internal class NanoKvmPhase3FeatureGateway internal constructor(
    private val port: NanoKvmPhase3Port,
    val binding: NanoKvmSessionBinding,
    private val currentBinding: () -> NanoKvmSessionBinding?,
) {
    private val operationMutex = Mutex()
    private var latestMediaCatalog: NanoKvmMediaCatalog? = null
    private var latestWakeOnLanSnapshot: NanoKvmWakeOnLanSnapshot? = null

    suspend fun refreshHidMode(): NanoKvmPhase3ReadResult<NanoKvmHidModeSnapshot> =
        operationMutex.withLock { refreshHidModeLocked() }

    suspend fun setHidMode(
        selection: NanoKvmHidModeSelection,
    ): NanoKvmPhase3MutationResult<NanoKvmHidModeSnapshot> = operationMutex.withLock {
        if (selection == NanoKvmHidModeSelection.OTHER) {
            return@withLock rejected(NanoKvmPhase3Error.Kind.INVALID_REQUEST)
        }
        requireCurrentBinding()?.let { return@withLock rejected(it) }
        val preflight = when (val read = refreshHidModeLocked()) {
            is NanoKvmPhase3ReadResult.Success -> read.state
            is NanoKvmPhase3ReadResult.Failure ->
                return@withLock NanoKvmPhase3MutationResult.Rejected(read.error)
        }
        if (preflight.selection == selection) {
            return@withLock NanoKvmPhase3MutationResult.AlreadySatisfied(preflight)
        }
        requireCurrentBinding()?.let { return@withLock rejected(it) }

        try {
            port.setHidMode(selection.toProtocolHidMode())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (isDefiniteRejection(error)) {
                return@withLock NanoKvmPhase3MutationResult.Rejected(error.safeError())
            }
            return@withLock when (val refreshed = refreshHidModeLocked()) {
                is NanoKvmPhase3ReadResult.Success -> NanoKvmPhase3MutationResult.Reconciled(
                    state = refreshed.state,
                    observation = observation(refreshed.state.selection == selection),
                    dispatchError = error.safeError(),
                )
                is NanoKvmPhase3ReadResult.Failure -> NanoKvmPhase3MutationResult.Indeterminate(
                    state = null,
                    dispatchError = error.safeError(),
                    refreshError = refreshed.error,
                )
            }
        }

        when (val refreshed = refreshHidModeLocked()) {
            is NanoKvmPhase3ReadResult.Success -> if (refreshed.state.selection == selection) {
                NanoKvmPhase3MutationResult.Applied(refreshed.state)
            } else {
                NanoKvmPhase3MutationResult.Accepted(refreshed.state, refreshError = null)
            }
            is NanoKvmPhase3ReadResult.Failure ->
                NanoKvmPhase3MutationResult.Accepted(null, refreshed.error)
        }
    }

    suspend fun refreshMedia(): NanoKvmPhase3ReadResult<NanoKvmMediaCatalog> =
        operationMutex.withLock { refreshMediaLocked() }

    suspend fun mountImage(
        catalog: NanoKvmMediaCatalog,
        image: NanoKvmMediaImage,
        mode: NanoKvmImageMountMode,
    ): NanoKvmPhase3MutationResult<NanoKvmMediaCatalog> = operationMutex.withLock {
        validateMediaHandle(catalog, image)?.let { return@withLock rejected(it) }
        val preflight = when (val read = refreshMediaLocked()) {
            is NanoKvmPhase3ReadResult.Success -> read.state
            is NanoKvmPhase3ReadResult.Failure ->
                return@withLock NanoKvmPhase3MutationResult.Rejected(read.error)
        }
        val currentImage = preflight.images.firstOrNull {
            port.sameImage(it.portImage, image.portImage)
        } ?: return@withLock rejected(NanoKvmPhase3Error.Kind.FOREIGN_OR_STALE_STATE)
        if (preflight.mountedImage?.let {
                port.sameImage(it.portImage, currentImage.portImage)
            } == true && preflight.cdRomEnabled == (mode == NanoKvmImageMountMode.CD_ROM)
        ) {
            return@withLock NanoKvmPhase3MutationResult.AlreadySatisfied(preflight)
        }
        requireCurrentBinding()?.let { return@withLock rejected(it) }

        try {
            port.mountImage(preflight.portCatalog, currentImage.portImage, mode)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            return@withLock reconcileMediaFailure(error) { refreshed ->
                val mounted = refreshed.mountedImage
                mounted != null &&
                    port.sameImage(mounted.portImage, currentImage.portImage) &&
                    refreshed.cdRomEnabled == (mode == NanoKvmImageMountMode.CD_ROM)
            }
        }

        mediaResultAfterAcknowledgement { refreshed ->
            val mounted = refreshed.mountedImage
            mounted != null &&
                port.sameImage(mounted.portImage, currentImage.portImage) &&
                refreshed.cdRomEnabled == (mode == NanoKvmImageMountMode.CD_ROM)
        }
    }

    suspend fun restorePhysicalMedia(): NanoKvmPhase3MutationResult<NanoKvmMediaCatalog> =
        operationMutex.withLock {
            requireCurrentBinding()?.let { return@withLock rejected(it) }
            val preflight = when (val read = refreshMediaLocked()) {
                is NanoKvmPhase3ReadResult.Success -> read.state
                is NanoKvmPhase3ReadResult.Failure ->
                    return@withLock NanoKvmPhase3MutationResult.Rejected(read.error)
            }
            if (preflight.mountedImage == null && !preflight.hasUnlistedMountedImage) {
                return@withLock NanoKvmPhase3MutationResult.AlreadySatisfied(preflight)
            }
            requireCurrentBinding()?.let { return@withLock rejected(it) }

            try {
                port.restorePhysicalMedia()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                return@withLock reconcileMediaFailure(error) {
                    it.mountedImage == null && !it.hasUnlistedMountedImage
                }
            }
            mediaResultAfterAcknowledgement {
                it.mountedImage == null && !it.hasUnlistedMountedImage
            }
        }

    suspend fun deleteImage(
        catalog: NanoKvmMediaCatalog,
        image: NanoKvmMediaImage,
    ): NanoKvmPhase3MutationResult<NanoKvmMediaCatalog> = operationMutex.withLock {
        validateMediaHandle(catalog, image)?.let { return@withLock rejected(it) }

        // Always refresh immediately before deletion: a previously-unmounted image may now be live.
        val preflight = when (val read = refreshMediaLocked()) {
            is NanoKvmPhase3ReadResult.Success -> read.state
            is NanoKvmPhase3ReadResult.Failure ->
                return@withLock NanoKvmPhase3MutationResult.Rejected(read.error)
        }
        val currentImage = preflight.images.firstOrNull {
            port.sameImage(it.portImage, image.portImage)
        } ?: return@withLock rejected(NanoKvmPhase3Error.Kind.FOREIGN_OR_STALE_STATE)
        if (preflight.mountedImage?.let {
                port.sameImage(it.portImage, currentImage.portImage)
            } == true
        ) {
            return@withLock rejected(NanoKvmPhase3Error.Kind.IMAGE_IS_MOUNTED)
        }
        requireCurrentBinding()?.let { return@withLock rejected(it) }

        try {
            port.deleteImage(preflight.portCatalog, currentImage.portImage)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            return@withLock reconcileMediaFailure(error) { refreshed ->
                refreshed.images.none { port.sameImage(it.portImage, currentImage.portImage) }
            }
        }
        mediaResultAfterAcknowledgement { refreshed ->
            refreshed.images.none { port.sameImage(it.portImage, currentImage.portImage) }
        }
    }

    suspend fun refreshVirtualDevices():
        NanoKvmPhase3ReadResult<NanoKvmVirtualDeviceSnapshot> = operationMutex.withLock {
            refreshVirtualDevicesLocked()
        }

    suspend fun setVirtualDeviceEnabled(
        device: NanoKvmVirtualDevice,
        enabled: Boolean,
    ): NanoKvmPhase3MutationResult<NanoKvmVirtualDeviceSnapshot> = operationMutex.withLock {
        requireCurrentBinding()?.let { return@withLock rejected(it) }
        val preflight = when (val read = refreshVirtualDevicesLocked()) {
            is NanoKvmPhase3ReadResult.Success -> read.state
            is NanoKvmPhase3ReadResult.Failure -> return@withLock NanoKvmPhase3MutationResult.Rejected(read.error)
        }
        if (preflight.isEnabled(device) == enabled) {
            return@withLock NanoKvmPhase3MutationResult.AlreadySatisfied(preflight)
        }
        requireCurrentBinding()?.let { return@withLock rejected(it) }

        try {
            port.toggleVirtualDevice(device)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            return@withLock reconcileVirtualDeviceFailure(error, device, enabled)
        }

        when (val refreshed = refreshVirtualDevicesLocked()) {
            is NanoKvmPhase3ReadResult.Success -> if (refreshed.state.isEnabled(device) == enabled) {
                NanoKvmPhase3MutationResult.Applied(refreshed.state)
            } else {
                NanoKvmPhase3MutationResult.Accepted(refreshed.state, refreshError = null)
            }
            is NanoKvmPhase3ReadResult.Failure ->
                NanoKvmPhase3MutationResult.Accepted(null, refreshed.error)
        }
    }

    suspend fun refreshImageTransfer():
        NanoKvmPhase3ReadResult<NanoKvmImageTransferSnapshot> = operationMutex.withLock {
            refreshImageTransferLocked()
        }

    suspend fun startImageTransfer(
        source: NanoKvmRemoteImageUrl,
    ): NanoKvmPhase3MutationResult<NanoKvmImageTransferSnapshot> = operationMutex.withLock {
        requireCurrentBinding()?.let { return@withLock rejected(it) }
        val preflight = when (val read = refreshImageTransferLocked()) {
            is NanoKvmPhase3ReadResult.Success -> read.state
            is NanoKvmPhase3ReadResult.Failure -> return@withLock NanoKvmPhase3MutationResult.Rejected(read.error)
        }
        if (!preflight.enabled) {
            return@withLock rejected(NanoKvmPhase3Error.Kind.IMAGE_TRANSFER_DISABLED)
        }
        requireCurrentBinding()?.let { return@withLock rejected(it) }

        try {
            port.startImageTransfer(source)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            return@withLock reconcileImageTransferFailure(error)
        }
        when (val refreshed = refreshImageTransferLocked()) {
            is NanoKvmPhase3ReadResult.Success -> if (
                refreshed.state.phase == NanoKvmTransferPhase.IN_PROGRESS
            ) {
                NanoKvmPhase3MutationResult.Applied(refreshed.state)
            } else {
                NanoKvmPhase3MutationResult.Accepted(refreshed.state, refreshError = null)
            }
            is NanoKvmPhase3ReadResult.Failure ->
                NanoKvmPhase3MutationResult.Accepted(null, refreshed.error)
        }
    }

    suspend fun refreshWakeOnLan(): NanoKvmPhase3ReadResult<NanoKvmWakeOnLanSnapshot> =
        operationMutex.withLock { refreshWakeOnLanLocked() }

    suspend fun sendWakeOnLan(
        macAddress: NanoKvmMacAddress,
    ): NanoKvmPhase3MutationResult<NanoKvmWakeOnLanSnapshot> = operationMutex.withLock {
        requireCurrentBinding()?.let { return@withLock rejected(it) }
        try {
            port.sendWakeOnLan(macAddress)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            return@withLock reconcileWakeOnLanFailure(error) { snapshot ->
                snapshot.targets.any { it.macAddress == macAddress }
            }
        }

        // History is reconciliation metadata only; it cannot prove magic-packet delivery.
        when (val refreshed = refreshWakeOnLanLocked()) {
            is NanoKvmPhase3ReadResult.Success ->
                NanoKvmPhase3MutationResult.Accepted(refreshed.state, refreshError = null)
            is NanoKvmPhase3ReadResult.Failure ->
                NanoKvmPhase3MutationResult.Accepted(null, refreshed.error)
        }
    }

    suspend fun renameWakeOnLanTarget(
        snapshot: NanoKvmWakeOnLanSnapshot,
        target: NanoKvmWakeOnLanTarget,
        name: String,
    ): NanoKvmPhase3MutationResult<NanoKvmWakeOnLanSnapshot> = operationMutex.withLock {
        validateWakeOnLanHandle(snapshot, target)?.let { return@withLock rejected(it) }
        val preflight = when (val read = refreshWakeOnLanLocked()) {
            is NanoKvmPhase3ReadResult.Success -> read.state
            is NanoKvmPhase3ReadResult.Failure ->
                return@withLock NanoKvmPhase3MutationResult.Rejected(read.error)
        }
        val currentTarget = preflight.targets.firstOrNull {
            it.macAddress == target.macAddress
        } ?: return@withLock rejected(NanoKvmPhase3Error.Kind.FOREIGN_OR_STALE_STATE)
        requireCurrentBinding()?.let { return@withLock rejected(it) }

        try {
            port.renameWakeOnLanEntry(currentTarget.macAddress, name)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (isDefiniteRejection(error)) return@withLock NanoKvmPhase3MutationResult.Rejected(error.safeError())
            return@withLock reconcileWakeOnLanFailure(error) { refreshed ->
                refreshed.targets.any {
                    it.macAddress == currentTarget.macAddress && it.name == name.trim()
                }
            }
        }
        wakeOnLanResultAfterAcknowledgement { refreshed ->
            refreshed.targets.any {
                it.macAddress == currentTarget.macAddress && it.name == name.trim()
            }
        }
    }

    suspend fun deleteWakeOnLanTarget(
        snapshot: NanoKvmWakeOnLanSnapshot,
        target: NanoKvmWakeOnLanTarget,
    ): NanoKvmPhase3MutationResult<NanoKvmWakeOnLanSnapshot> = operationMutex.withLock {
        validateWakeOnLanHandle(snapshot, target)?.let { return@withLock rejected(it) }
        val preflight = when (val read = refreshWakeOnLanLocked()) {
            is NanoKvmPhase3ReadResult.Success -> read.state
            is NanoKvmPhase3ReadResult.Failure ->
                return@withLock NanoKvmPhase3MutationResult.Rejected(read.error)
        }
        val currentTarget = preflight.targets.firstOrNull {
            it.macAddress == target.macAddress
        } ?: return@withLock rejected(NanoKvmPhase3Error.Kind.FOREIGN_OR_STALE_STATE)
        requireCurrentBinding()?.let { return@withLock rejected(it) }

        try {
            port.deleteWakeOnLanEntry(currentTarget.macAddress)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            return@withLock reconcileWakeOnLanFailure(error) { refreshed ->
                refreshed.targets.none { it.macAddress == currentTarget.macAddress }
            }
        }
        wakeOnLanResultAfterAcknowledgement { refreshed ->
            refreshed.targets.none { it.macAddress == currentTarget.macAddress }
        }
    }

    private suspend fun refreshMediaLocked(): NanoKvmPhase3ReadResult<NanoKvmMediaCatalog> {
        requireCurrentBinding()?.let { return NanoKvmPhase3ReadResult.Failure(it) }
        return try {
            val portCatalog = port.imageCatalog()
            requireCurrentBinding()?.let { return NanoKvmPhase3ReadResult.Failure(it) }
            val handles = portCatalog.images.map {
                NanoKvmMediaImage(it.displayName, binding, it)
            }
            val mounted = portCatalog.mountedImage?.let { mountedPortImage ->
                handles.firstOrNull { it.portImage === mountedPortImage }
            }
            val catalog = NanoKvmMediaCatalog(
                images = handles,
                mountedImage = mounted,
                hasUnlistedMountedImage = portCatalog.hasUnlistedMountedImage,
                cdRomEnabled = portCatalog.cdRomEnabled,
                binding = binding,
                portCatalog = portCatalog,
            )
            latestMediaCatalog = catalog
            NanoKvmPhase3ReadResult.Success(catalog)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            NanoKvmPhase3ReadResult.Failure(error.safeError())
        }
    }

    private suspend fun refreshHidModeLocked(): NanoKvmPhase3ReadResult<NanoKvmHidModeSnapshot> {
        requireCurrentBinding()?.let { return NanoKvmPhase3ReadResult.Failure(it) }
        return try {
            val mode = port.hidMode()
            requireCurrentBinding()?.let { return NanoKvmPhase3ReadResult.Failure(it) }
            NanoKvmPhase3ReadResult.Success(
                NanoKvmHidModeSnapshot(
                    selection = mode.toSelection(),
                    reportedMode = (mode as? NanoKvmHidMode.Other)?.wireValue,
                    binding = binding,
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            NanoKvmPhase3ReadResult.Failure(error.safeError())
        }
    }

    private suspend fun refreshVirtualDevicesLocked():
        NanoKvmPhase3ReadResult<NanoKvmVirtualDeviceSnapshot> {
        requireCurrentBinding()?.let { return NanoKvmPhase3ReadResult.Failure(it) }
        return try {
            val state = port.virtualDevices()
            requireCurrentBinding()?.let { return NanoKvmPhase3ReadResult.Failure(it) }
            NanoKvmPhase3ReadResult.Success(state.toSnapshot())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            NanoKvmPhase3ReadResult.Failure(error.safeError())
        }
    }

    private suspend fun refreshImageTransferLocked():
        NanoKvmPhase3ReadResult<NanoKvmImageTransferSnapshot> {
        requireCurrentBinding()?.let { return NanoKvmPhase3ReadResult.Failure(it) }
        return try {
            val enabled = port.isImageTransferEnabled()
            val status = port.imageTransferStatus()
            requireCurrentBinding()?.let { return NanoKvmPhase3ReadResult.Failure(it) }
            NanoKvmPhase3ReadResult.Success(
                NanoKvmImageTransferSnapshot(
                    enabled = enabled,
                    phase = when (status.state) {
                        NanoKvmImageTransferState.Idle -> NanoKvmTransferPhase.IDLE
                        NanoKvmImageTransferState.InProgress -> NanoKvmTransferPhase.IN_PROGRESS
                        is NanoKvmImageTransferState.Other -> NanoKvmTransferPhase.OTHER
                    },
                    percentage = status.percentage,
                    binding = binding,
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            NanoKvmPhase3ReadResult.Failure(error.safeError())
        }
    }

    private suspend fun refreshWakeOnLanLocked():
        NanoKvmPhase3ReadResult<NanoKvmWakeOnLanSnapshot> {
        requireCurrentBinding()?.let { return NanoKvmPhase3ReadResult.Failure(it) }
        return try {
            val entries = port.wakeOnLanHistory()
            requireCurrentBinding()?.let { return NanoKvmPhase3ReadResult.Failure(it) }
            val snapshot = NanoKvmWakeOnLanSnapshot(
                entries.map { NanoKvmWakeOnLanTarget(it.macAddress, it.name, binding) },
                binding,
            )
            latestWakeOnLanSnapshot = snapshot
            NanoKvmPhase3ReadResult.Success(snapshot)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            NanoKvmPhase3ReadResult.Failure(error.safeError())
        }
    }

    private fun validateMediaHandle(
        catalog: NanoKvmMediaCatalog,
        image: NanoKvmMediaImage,
    ): NanoKvmPhase3Error? {
        requireCurrentBinding()?.let { return it }
        return if (
            catalog.binding != binding ||
            image.binding != binding ||
            catalog !== latestMediaCatalog ||
            catalog.images.none { it === image }
        ) {
            NanoKvmPhase3Error(NanoKvmPhase3Error.Kind.FOREIGN_OR_STALE_STATE)
        } else {
            null
        }
    }

    private fun validateWakeOnLanHandle(
        snapshot: NanoKvmWakeOnLanSnapshot,
        target: NanoKvmWakeOnLanTarget,
    ): NanoKvmPhase3Error? {
        requireCurrentBinding()?.let { return it }
        return if (
            snapshot.binding != binding ||
            target.binding != binding ||
            snapshot !== latestWakeOnLanSnapshot ||
            snapshot.targets.none { it === target }
        ) {
            NanoKvmPhase3Error(NanoKvmPhase3Error.Kind.FOREIGN_OR_STALE_STATE)
        } else {
            null
        }
    }

    private fun requireCurrentBinding(): NanoKvmPhase3Error? =
        if (currentBinding() == binding) null else {
            NanoKvmPhase3Error(NanoKvmPhase3Error.Kind.SESSION_CHANGED)
        }

    private suspend fun mediaResultAfterAcknowledgement(
        isDesired: (NanoKvmMediaCatalog) -> Boolean,
    ): NanoKvmPhase3MutationResult<NanoKvmMediaCatalog> =
        when (val refreshed = refreshMediaLocked()) {
            is NanoKvmPhase3ReadResult.Success -> if (isDesired(refreshed.state)) {
                NanoKvmPhase3MutationResult.Applied(refreshed.state)
            } else {
                NanoKvmPhase3MutationResult.Accepted(refreshed.state, refreshError = null)
            }
            is NanoKvmPhase3ReadResult.Failure ->
                NanoKvmPhase3MutationResult.Accepted(null, refreshed.error)
        }

    private suspend fun wakeOnLanResultAfterAcknowledgement(
        isDesired: (NanoKvmWakeOnLanSnapshot) -> Boolean,
    ): NanoKvmPhase3MutationResult<NanoKvmWakeOnLanSnapshot> =
        when (val refreshed = refreshWakeOnLanLocked()) {
            is NanoKvmPhase3ReadResult.Success -> if (isDesired(refreshed.state)) {
                NanoKvmPhase3MutationResult.Applied(refreshed.state)
            } else {
                NanoKvmPhase3MutationResult.Accepted(refreshed.state, refreshError = null)
            }
            is NanoKvmPhase3ReadResult.Failure ->
                NanoKvmPhase3MutationResult.Accepted(null, refreshed.error)
        }

    private suspend fun reconcileMediaFailure(
        error: Throwable,
        isDesired: (NanoKvmMediaCatalog) -> Boolean,
    ): NanoKvmPhase3MutationResult<NanoKvmMediaCatalog> {
        if (isDefiniteRejection(error)) return NanoKvmPhase3MutationResult.Rejected(error.safeError())
        return when (val refreshed = refreshMediaLocked()) {
            is NanoKvmPhase3ReadResult.Success -> NanoKvmPhase3MutationResult.Reconciled(
                state = refreshed.state,
                observation = observation(isDesired(refreshed.state)),
                dispatchError = error.safeError(),
            )
            is NanoKvmPhase3ReadResult.Failure -> NanoKvmPhase3MutationResult.Indeterminate(
                state = null,
                dispatchError = error.safeError(),
                refreshError = refreshed.error,
            )
        }
    }

    private suspend fun reconcileVirtualDeviceFailure(
        error: Throwable,
        device: NanoKvmVirtualDevice,
        enabled: Boolean,
    ): NanoKvmPhase3MutationResult<NanoKvmVirtualDeviceSnapshot> {
        if (isDefiniteRejection(error)) return NanoKvmPhase3MutationResult.Rejected(error.safeError())
        return when (val refreshed = refreshVirtualDevicesLocked()) {
            is NanoKvmPhase3ReadResult.Success -> NanoKvmPhase3MutationResult.Reconciled(
                state = refreshed.state,
                observation = observation(refreshed.state.isEnabled(device) == enabled),
                dispatchError = error.safeError(),
            )
            is NanoKvmPhase3ReadResult.Failure -> NanoKvmPhase3MutationResult.Indeterminate(
                state = null,
                dispatchError = error.safeError(),
                refreshError = refreshed.error,
            )
        }
    }

    private suspend fun reconcileImageTransferFailure(
        error: Throwable,
    ): NanoKvmPhase3MutationResult<NanoKvmImageTransferSnapshot> {
        if (isDefiniteRejection(error)) return NanoKvmPhase3MutationResult.Rejected(error.safeError())
        return when (val refreshed = refreshImageTransferLocked()) {
            is NanoKvmPhase3ReadResult.Success -> NanoKvmPhase3MutationResult.Reconciled(
                state = refreshed.state,
                observation = observation(
                    refreshed.state.phase == NanoKvmTransferPhase.IN_PROGRESS,
                ),
                dispatchError = error.safeError(),
            )
            is NanoKvmPhase3ReadResult.Failure -> NanoKvmPhase3MutationResult.Indeterminate(
                state = null,
                dispatchError = error.safeError(),
                refreshError = refreshed.error,
            )
        }
    }

    private suspend fun reconcileWakeOnLanFailure(
        error: Throwable,
        isDesired: (NanoKvmWakeOnLanSnapshot) -> Boolean,
    ): NanoKvmPhase3MutationResult<NanoKvmWakeOnLanSnapshot> {
        if (isDefiniteRejection(error)) return NanoKvmPhase3MutationResult.Rejected(error.safeError())
        return when (val refreshed = refreshWakeOnLanLocked()) {
            is NanoKvmPhase3ReadResult.Success -> NanoKvmPhase3MutationResult.Reconciled(
                state = refreshed.state,
                observation = observation(isDesired(refreshed.state)),
                dispatchError = error.safeError(),
            )
            is NanoKvmPhase3ReadResult.Failure -> NanoKvmPhase3MutationResult.Indeterminate(
                state = null,
                dispatchError = error.safeError(),
                refreshError = refreshed.error,
            )
        }
    }

    private fun NanoKvmVirtualDevices.toSnapshot() = NanoKvmVirtualDeviceSnapshot(
        networkEnabled = network,
        mediaEnabled = media,
        diskEnabled = disk,
        binding = binding,
    )

    private fun NanoKvmHidMode.toSelection(): NanoKvmHidModeSelection = when (this) {
        NanoKvmHidMode.Normal -> NanoKvmHidModeSelection.NORMAL
        NanoKvmHidMode.HidOnly -> NanoKvmHidModeSelection.HID_ONLY
        is NanoKvmHidMode.Other -> NanoKvmHidModeSelection.OTHER
    }

    private fun NanoKvmHidModeSelection.toProtocolHidMode(): NanoKvmHidMode = when (this) {
        NanoKvmHidModeSelection.NORMAL -> NanoKvmHidMode.Normal
        NanoKvmHidModeSelection.HID_ONLY -> NanoKvmHidMode.HidOnly
        NanoKvmHidModeSelection.OTHER -> error("Unknown HID mode is read-only")
    }

    private fun NanoKvmVirtualDeviceSnapshot.isEnabled(device: NanoKvmVirtualDevice): Boolean =
        when (device) {
            NanoKvmVirtualDevice.DISK -> diskEnabled
            NanoKvmVirtualDevice.NETWORK -> networkEnabled
        }

    private fun observation(desired: Boolean): NanoKvmPhase3Observation =
        if (desired) NanoKvmPhase3Observation.DESIRED_STATE else {
            NanoKvmPhase3Observation.OTHER_STATE
        }

    private fun rejected(kind: NanoKvmPhase3Error.Kind): NanoKvmPhase3MutationResult.Rejected =
        NanoKvmPhase3MutationResult.Rejected(NanoKvmPhase3Error(kind))

    private fun rejected(error: NanoKvmPhase3Error): NanoKvmPhase3MutationResult.Rejected =
        NanoKvmPhase3MutationResult.Rejected(error)
}

private fun Throwable.safeError(): NanoKvmPhase3Error = NanoKvmPhase3Error(
    when (this) {
        is IllegalArgumentException -> NanoKvmPhase3Error.Kind.INVALID_REQUEST
        is AuthenticationExpiredException -> NanoKvmPhase3Error.Kind.AUTHENTICATION_EXPIRED
        is ApiResponseException -> NanoKvmPhase3Error.Kind.SERVER_REJECTED
        is InvalidApiResponseException -> NanoKvmPhase3Error.Kind.INVALID_RESPONSE
        is HttpResponseException -> if (statusCode.isUnsupportedOptionalEndpoint()) {
            NanoKvmPhase3Error.Kind.UNSUPPORTED
        } else {
            NanoKvmPhase3Error.Kind.CONNECTION
        }
        is IOException -> NanoKvmPhase3Error.Kind.CONNECTION
        else -> NanoKvmPhase3Error.Kind.UNEXPECTED
    },
)

private fun isDefiniteRejection(error: Throwable): Boolean =
    error is IllegalArgumentException ||
        error is AuthenticationExpiredException ||
        error is ApiResponseException ||
        (error is HttpResponseException && error.statusCode.isUnsupportedOptionalEndpoint())

private fun Int.isUnsupportedOptionalEndpoint(): Boolean = this == 404 || this == 405 || this == 501
