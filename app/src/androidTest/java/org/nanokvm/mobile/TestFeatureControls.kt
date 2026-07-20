package org.nanokvm.mobile

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.nanokvm.mobile.runtime.AdministrationControls
import org.nanokvm.mobile.runtime.AdministrationMouseJigglerSelection
import org.nanokvm.mobile.runtime.AdministrationOledPreset
import org.nanokvm.mobile.runtime.AdministrationSwapPreset
import org.nanokvm.mobile.runtime.AdministrationTailscaleCommand
import org.nanokvm.mobile.runtime.ApprovedAdministrationDestination
import org.nanokvm.mobile.runtime.ApprovedOperatorDestination
import org.nanokvm.mobile.runtime.ApprovedPhase3Destination
import org.nanokvm.mobile.runtime.ApprovedPicoClawDestination
import org.nanokvm.mobile.runtime.OperatorControls
import org.nanokvm.mobile.runtime.OperatorEphemeralOutput
import org.nanokvm.mobile.runtime.OperatorScriptRunMode
import org.nanokvm.mobile.runtime.OperatorScriptUploadRequest
import org.nanokvm.mobile.runtime.OperatorSerialConfiguration
import org.nanokvm.mobile.runtime.OperatorUiState
import org.nanokvm.mobile.runtime.Phase3Controls
import org.nanokvm.mobile.runtime.Phase3HidModeSelection
import org.nanokvm.mobile.runtime.Phase3ImageMountMode
import org.nanokvm.mobile.runtime.PicoClawControls
import org.nanokvm.mobile.runtime.PicoClawModelConfigurationRequest
import org.nanokvm.mobile.runtime.PicoClawProfile
import org.nanokvm.mobile.runtime.PicoClawUiState

/** Focused androidTest stubs: production feature contracts intentionally provide no defaults. */
internal open class NoOpPhase3Controls : Phase3Controls {
    override fun setPhase3SurfaceVisible(visible: Boolean) = Unit
    override fun refreshPhase3() = Unit
    override fun mountPhase3Image(
        destination: ApprovedPhase3Destination,
        imageId: Long,
        mode: Phase3ImageMountMode,
    ) = Unit
    override fun restorePhase3PhysicalMedia(destination: ApprovedPhase3Destination) = Unit
    override fun deletePhase3Image(destination: ApprovedPhase3Destination, imageId: Long) = Unit
    override fun setPhase3HidMode(
        destination: ApprovedPhase3Destination,
        selection: Phase3HidModeSelection,
    ) = Unit
    override fun setPhase3NetworkEnabled(
        destination: ApprovedPhase3Destination,
        enabled: Boolean,
    ) = Unit
    override fun setPhase3DiskEnabled(
        destination: ApprovedPhase3Destination,
        enabled: Boolean,
    ) = Unit
    override fun startPhase3ImageTransfer(
        destination: ApprovedPhase3Destination,
        sourceUrl: String,
    ) = Unit
    override fun sendPhase3WakeOnLan(
        destination: ApprovedPhase3Destination,
        macAddress: String,
    ) = Unit
    override fun renamePhase3WakeOnLanTarget(
        destination: ApprovedPhase3Destination,
        targetId: Long,
        name: String,
    ) = Unit
    override fun deletePhase3WakeOnLanTarget(
        destination: ApprovedPhase3Destination,
        targetId: Long,
    ) = Unit
}

internal open class NoOpAdministrationControls : AdministrationControls {
    override fun setAdministrationSurfaceVisible(visible: Boolean) = Unit
    override fun refreshAdministration() = Unit
    override fun setAdministrationPreviewUpdates(
        destination: ApprovedAdministrationDestination,
        enabled: Boolean,
    ) = Unit
    override fun startAdministrationOnlineUpdate(destination: ApprovedAdministrationDestination) =
        Unit
    override fun rebootAdministrationAppliance(destination: ApprovedAdministrationDestination) =
        Unit
    override fun setAdministrationOledSleep(
        destination: ApprovedAdministrationDestination,
        preset: AdministrationOledPreset,
    ) = Unit
    override fun setAdministrationSshEnabled(
        destination: ApprovedAdministrationDestination,
        enabled: Boolean,
    ) = Unit
    override fun setAdministrationHostname(
        destination: ApprovedAdministrationDestination,
        hostname: String,
    ) = Unit
    override fun setAdministrationMdnsEnabled(
        destination: ApprovedAdministrationDestination,
        enabled: Boolean,
    ) = Unit
    override fun setAdministrationWebTitle(
        destination: ApprovedAdministrationDestination,
        title: String,
    ) = Unit
    override fun resetAdministrationWebTitle(destination: ApprovedAdministrationDestination) = Unit
    override fun setAdministrationManualDns(
        destination: ApprovedAdministrationDestination,
        servers: List<String>,
    ) = Unit
    override fun setAdministrationDhcpDns(destination: ApprovedAdministrationDestination) = Unit
    override fun connectAdministrationWifi(
        destination: ApprovedAdministrationDestination,
        ssid: String,
        password: CharArray,
    ) {
        password.fill('\u0000')
    }
    override fun disconnectAdministrationWifi(destination: ApprovedAdministrationDestination) = Unit
    override fun executeAdministrationTailscale(
        destination: ApprovedAdministrationDestination,
        command: AdministrationTailscaleCommand,
    ) = Unit
    override fun acknowledgeAdministrationNavigationOpened(
        destination: ApprovedAdministrationDestination,
        requestId: Long,
    ) = Unit
    override fun setAdministrationHdmiEnabled(
        destination: ApprovedAdministrationDestination,
        enabled: Boolean,
    ) = Unit
    override fun resetAdministrationHdmi(destination: ApprovedAdministrationDestination) = Unit
    override fun setAdministrationMouseJiggler(
        destination: ApprovedAdministrationDestination,
        selection: AdministrationMouseJigglerSelection,
    ) = Unit
    override fun setAdministrationMemoryLimitEnabled(
        destination: ApprovedAdministrationDestination,
        enabled: Boolean,
    ) = Unit
    override fun setAdministrationSwapSize(
        destination: ApprovedAdministrationDestination,
        preset: AdministrationSwapPreset,
    ) = Unit
    override fun enableAdministrationTls(destination: ApprovedAdministrationDestination) = Unit
}

internal open class NoOpOperatorControls : OperatorControls {
    override val operatorState: StateFlow<OperatorUiState> = MutableStateFlow(OperatorUiState())
    override val operatorOutput: SharedFlow<OperatorEphemeralOutput> = MutableSharedFlow(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override fun setOperatorSurfaceVisible(visible: Boolean) = Unit
    override fun refreshOperatorScripts() = Unit
    override fun enterOperatorTerminal(destination: ApprovedOperatorDestination) = Unit
    override fun closeOperatorTerminal(destination: ApprovedOperatorDestination) = Unit
    override fun sendOperatorTerminalInput(
        destination: ApprovedOperatorDestination,
        text: String,
    ) = Unit
    override fun resizeOperatorTerminal(
        destination: ApprovedOperatorDestination,
        rows: Int,
        columns: Int,
    ) = Unit
    override fun startOperatorSerial(
        destination: ApprovedOperatorDestination,
        configuration: OperatorSerialConfiguration,
    ) = Unit
    override fun exitOperatorSerial(destination: ApprovedOperatorDestination) = Unit
    override fun uploadOperatorScript(
        destination: ApprovedOperatorDestination,
        request: OperatorScriptUploadRequest,
    ) = Unit
    override fun runOperatorScript(
        destination: ApprovedOperatorDestination,
        scriptId: Long,
        mode: OperatorScriptRunMode,
    ) = Unit
    override fun deleteOperatorScript(
        destination: ApprovedOperatorDestination,
        scriptId: Long,
    ) = Unit
}

internal open class NoOpPicoClawControls : PicoClawControls {
    override val picoClawState: StateFlow<PicoClawUiState> = MutableStateFlow(PicoClawUiState())
    override fun setPicoClawSurfaceVisible(visible: Boolean) = Unit
    override fun enterPicoClaw(destination: ApprovedPicoClawDestination) = Unit
    override fun refreshPicoClaw(destination: ApprovedPicoClawDestination) = Unit
    override fun installPicoClawRuntime(destination: ApprovedPicoClawDestination) = Unit
    override fun startPicoClawRuntime(destination: ApprovedPicoClawDestination) = Unit
    override fun stopPicoClawRuntime(destination: ApprovedPicoClawDestination) = Unit
    override fun uninstallPicoClawRuntime(destination: ApprovedPicoClawDestination) = Unit
    override fun setPicoClawProfile(
        destination: ApprovedPicoClawDestination,
        profile: PicoClawProfile,
    ) = Unit
    override fun configurePicoClawModel(
        destination: ApprovedPicoClawDestination,
        request: PicoClawModelConfigurationRequest,
    ) = Unit
    override fun refreshPicoClawHistories(destination: ApprovedPicoClawDestination) = Unit
    override fun loadPicoClawHistory(
        destination: ApprovedPicoClawDestination,
        historyId: Long,
    ) = Unit
    override fun deletePicoClawHistory(
        destination: ApprovedPicoClawDestination,
        historyId: Long,
    ) = Unit
    override fun openPicoClawChat(destination: ApprovedPicoClawDestination) = Unit
    override fun sendPicoClawChatMessage(
        destination: ApprovedPicoClawDestination,
        content: String,
    ) = Unit
    override fun cancelPicoClawChat(destination: ApprovedPicoClawDestination) = Unit
    override fun closeAndReleasePicoClaw(destination: ApprovedPicoClawDestination) = Unit
}
