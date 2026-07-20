package org.nanokvm.mobile.runtime

/**
 * Presentation-independent notices for the low-frequency appliance controls.
 *
 * These types deliberately contain no arbitrary [String] or [Throwable] payload. Appliance data
 * belongs in the bounded state fields that render it, while notices describe only app-authored
 * actions, outcomes, errors, and recovery guidance. This keeps localization exhaustive and avoids
 * accidentally exposing response bodies, URLs, paths, credentials, or exception messages.
 */
sealed interface Phase3Notice {
    val kind: Phase3NoticeKind

    enum class HidMode { Normal, HidOnly }

    sealed interface Action {
        data class MountImage(val mode: Phase3ImageMountMode) : Action
        data object RestorePhysicalMedia : Action
        data object DeleteImage : Action
        data class SetDiskEnabled(val enabled: Boolean) : Action
        data class SetHidMode(val mode: HidMode) : Action

        data class SetNetworkEnabled(val enabled: Boolean) : Action
        data object StartImageTransfer : Action
        data object SendWakePacket : Action
        data object RenameWakeTarget : Action
        data object DeleteWakeTarget : Action
    }

    enum class Outcome {
        Applied,
        AlreadySatisfied,
        AcceptedWithoutConfirmation,
        ReconciledToRequestedState,
        ReconciledToDifferentState,
        Indeterminate,
    }

    enum class InputRecovery {
        NotRequired,
        Reconnected,
        ReconnectedWithPartialReadback,
        ReconnectedWithoutReadback,
    }

    enum class Failure {
        SessionChanged,
        ForeignOrStaleState,
        ImageIsMounted,
        ImageTransferDisabled,
        InvalidRequest,
        Unsupported,
        AuthenticationExpired,
        Connection,
        ServerRejected,
        InvalidResponse,
        Unexpected,
    }

    sealed interface GuidanceReason {
        data object RefreshMediaBeforeSelectingImage : GuidanceReason
        data object RefreshMediaBeforeDeletingImage : GuidanceReason
        data object UnknownHidModeIsReadOnly : GuidanceReason
        data object EnterValidImageUrl : GuidanceReason
        data object EnterValidMacAddress : GuidanceReason
        data object RefreshWakeHistoryBeforeRenaming : GuidanceReason
        data object RefreshWakeHistoryBeforeDeleting : GuidanceReason
        data object WaitForImageTransfer : GuidanceReason
        data object ConnectBeforeOpeningFeatures : GuidanceReason
        data object ReviewActionAfterSessionChange : GuidanceReason
        data object SessionChangedBeforeSend : GuidanceReason
        data object ActionAlreadyRunning : GuidanceReason
        data object TransferFinishedWithoutChecksum : GuidanceReason
        data object UnexpectedTransferState : GuidanceReason
    }

    data class ActionOutcome(
        val action: Action,
        val outcome: Outcome,
        val inputRecovery: InputRecovery = InputRecovery.NotRequired,
    ) : Phase3Notice {
        override val kind: Phase3NoticeKind = when (outcome) {
            Outcome.Applied,
            Outcome.AlreadySatisfied -> Phase3NoticeKind.Applied
            Outcome.ReconciledToRequestedState,
            Outcome.ReconciledToDifferentState -> Phase3NoticeKind.Reconciled
            Outcome.AcceptedWithoutConfirmation,
            Outcome.Indeterminate -> Phase3NoticeKind.Indeterminate
        }
    }

    data class Error(
        val failure: Failure,
        val inputRecovery: InputRecovery = InputRecovery.NotRequired,
    ) : Phase3Notice {
        override val kind: Phase3NoticeKind = Phase3NoticeKind.Rejected
    }

    data class Guidance(val reason: GuidanceReason) : Phase3Notice {
        override val kind: Phase3NoticeKind = when (reason) {
            GuidanceReason.TransferFinishedWithoutChecksum -> Phase3NoticeKind.Information
            GuidanceReason.UnexpectedTransferState -> Phase3NoticeKind.Indeterminate
            GuidanceReason.RefreshMediaBeforeSelectingImage,
            GuidanceReason.RefreshMediaBeforeDeletingImage,
            GuidanceReason.UnknownHidModeIsReadOnly,
            GuidanceReason.EnterValidImageUrl,
            GuidanceReason.EnterValidMacAddress,
            GuidanceReason.RefreshWakeHistoryBeforeRenaming,
            GuidanceReason.RefreshWakeHistoryBeforeDeleting,
            GuidanceReason.WaitForImageTransfer,
            GuidanceReason.ConnectBeforeOpeningFeatures,
            GuidanceReason.ReviewActionAfterSessionChange,
            GuidanceReason.SessionChangedBeforeSend,
            GuidanceReason.ActionAlreadyRunning -> Phase3NoticeKind.Rejected
        }
    }
}

enum class Phase3NoticeKind { Information, Applied, Reconciled, Indeterminate, Rejected }

sealed interface AdministrationNotice {
    val kind: AdministrationNoticeKind

    enum class MouseJigglerMode { Off, Relative, Absolute }

    sealed interface Action {
        data class SetPreviewUpdates(val enabled: Boolean) : Action
        data object StartOnlineUpdate : Action
        data object RebootAppliance : Action
        data class SetOledSleep(val preset: AdministrationOledPreset) : Action
        data class SetSshEnabled(val enabled: Boolean) : Action
        data object SetHostname : Action
        data class SetMdnsEnabled(val enabled: Boolean) : Action
        data object SetWebTitle : Action
        data object ResetWebTitle : Action
        data object SetManualDns : Action
        data object SetDhcpDns : Action
        data object ConnectWifi : Action
        data object DisconnectWifi : Action
        data class Tailscale(val command: AdministrationTailscaleCommand) : Action
        data class SetHdmiEnabled(val enabled: Boolean) : Action
        data object ResetHdmi : Action
        data class SetMouseJiggler(val mode: MouseJigglerMode) : Action

        data class SetMemoryLimitEnabled(val enabled: Boolean) : Action
        data class SetSwapSize(val preset: AdministrationSwapPreset) : Action
        data object EnableTls : Action
        data object ChangeCredentials : Action
    }

    enum class Outcome {
        Applied,
        AlreadySatisfied,
        AcceptedWithoutConfirmation,
        ReconciledToRequestedState,
        ReconciledToDifferentState,
        Indeterminate,
        DisruptiveCommandAccepted,
        CredentialsChanged,
    }

    enum class FollowUp {
        None,
        RefreshAuthoritativeState,
        ReviewAuthoritativeState,
        ReconnectAndRefresh,
        RediscoverAndReconnect,
        WaitForRebootAndReconnect,
        ClearSavedCredentialAndEndSession,
        VerifyNewCredentialsAfterReconnect,
        RefreshBeforeRepeating,
        ReconnectAndVerifyBeforeRepeating,
    }

    enum class Failure {
        SessionChanged,
        InvalidRequest,
        InvalidPreset,
        Unsupported,
        AuthenticationExpired,
        Connection,
        ServerRejected,
        InvalidResponse,
        ControlInvalidResponse,
        Unexpected,
    }

    sealed interface GuidanceReason {
        data class SectionsUnavailable(val count: Int) : GuidanceReason {
            init {
                require(count > 0) { "Unavailable administration section count must be positive" }
            }
        }

        data object DestinationChangedReviewAction : GuidanceReason
        data object DestinationChangedBeforeSend : GuidanceReason
        data object ConnectBeforeOpeningAdministration : GuidanceReason
        data object AnotherOperationRunning : GuidanceReason
        data object UnknownMouseJigglerModeIsReadOnly : GuidanceReason
        data object TailscaleAuthorizationReady : GuidanceReason
        data object TailscaleAuthorizationPageOpened : GuidanceReason
    }

    data class ActionOutcome(
        val action: Action,
        val outcome: Outcome,
        val followUp: FollowUp = FollowUp.None,
    ) : AdministrationNotice {
        override val kind: AdministrationNoticeKind = when (outcome) {
            Outcome.Applied,
            Outcome.AlreadySatisfied,
            Outcome.DisruptiveCommandAccepted,
            Outcome.CredentialsChanged -> AdministrationNoticeKind.Applied
            Outcome.ReconciledToRequestedState,
            Outcome.ReconciledToDifferentState -> AdministrationNoticeKind.Reconciled
            Outcome.AcceptedWithoutConfirmation,
            Outcome.Indeterminate -> AdministrationNoticeKind.Indeterminate
        }
    }

    data class Error(val failure: Failure) : AdministrationNotice {
        override val kind: AdministrationNoticeKind = AdministrationNoticeKind.Rejected
    }

    data class Guidance(val reason: GuidanceReason) : AdministrationNotice {
        override val kind: AdministrationNoticeKind = when (reason) {
            GuidanceReason.TailscaleAuthorizationReady,
            GuidanceReason.TailscaleAuthorizationPageOpened ->
                AdministrationNoticeKind.Information
            is GuidanceReason.SectionsUnavailable,
            GuidanceReason.DestinationChangedReviewAction,
            GuidanceReason.DestinationChangedBeforeSend,
            GuidanceReason.ConnectBeforeOpeningAdministration,
            GuidanceReason.AnotherOperationRunning,
            GuidanceReason.UnknownMouseJigglerModeIsReadOnly ->
                AdministrationNoticeKind.Rejected
        }
    }
}

sealed interface OperatorNotice {
    val kind: OperatorNoticeKind

    sealed interface Action {
        data object EnterTerminal : Action
        data object CloseTerminal : Action
        data object SendTerminalInput : Action
        data object ResizeTerminal : Action
        data object StartSerial : Action
        data object ExitSerial : Action
        data object UploadScript : Action
        data class RunScript(val mode: OperatorScriptRunMode) : Action
        data object DeleteScript : Action
    }

    enum class Outcome {
        Dispatched,
        Completed,
        ReconciledAbsent,
        ReconciledPresent,
        Indeterminate,
    }

    enum class Warning {
        ForegroundCancellationDoesNotStopProcess,
        BackgroundHasNoStatusOrCancellation,
    }

    enum class Failure {
        SessionChanged,
        NotForeground,
        ElevatedApprovalRequired,
        AlreadyActive,
        NotConnected,
        ForeignOrStaleState,
        InvalidRequest,
        AuthenticationExpired,
        Connection,
        ServerRejected,
        InvalidResponse,
        Unexpected,
    }

    sealed interface GuidanceReason {
        data object TerminalClosedNeedsFreshConfirmation : GuidanceReason
        data object InvalidTerminalDimensions : GuidanceReason
        data object RefreshScriptCatalog : GuidanceReason
        data object ConnectBeforeOpeningTools : GuidanceReason
        data object SessionNoLongerCurrent : GuidanceReason
        data object DestinationChangedReviewAction : GuidanceReason
        data object DestinationChangedBeforeSend : GuidanceReason
        data object AnotherActionRunning : GuidanceReason
    }

    data class ActionOutcome(
        val action: Action,
        val outcome: Outcome,
        val warnings: Set<Warning> = emptySet(),
    ) : OperatorNotice {
        override val kind: OperatorNoticeKind = when (outcome) {
            Outcome.Dispatched -> OperatorNoticeKind.Information
            Outcome.Completed -> OperatorNoticeKind.Applied
            Outcome.ReconciledAbsent -> OperatorNoticeKind.Reconciled
            Outcome.ReconciledPresent,
            Outcome.Indeterminate -> OperatorNoticeKind.Indeterminate
        }
    }

    data class Error(
        val failure: Failure,
        val warnings: Set<Warning> = emptySet(),
    ) : OperatorNotice {
        override val kind: OperatorNoticeKind = OperatorNoticeKind.Rejected
    }

    data class Guidance(val reason: GuidanceReason) : OperatorNotice {
        override val kind: OperatorNoticeKind = when (reason) {
            GuidanceReason.TerminalClosedNeedsFreshConfirmation ->
                OperatorNoticeKind.Information
            GuidanceReason.InvalidTerminalDimensions,
            GuidanceReason.RefreshScriptCatalog,
            GuidanceReason.ConnectBeforeOpeningTools,
            GuidanceReason.SessionNoLongerCurrent,
            GuidanceReason.DestinationChangedReviewAction,
            GuidanceReason.DestinationChangedBeforeSend,
            GuidanceReason.AnotherActionRunning -> OperatorNoticeKind.Rejected
        }
    }
}

sealed interface PicoClawNotice {
    val kind: PicoClawNoticeKind

    sealed interface Action {
        data object EnterFeature : Action
        data object RefreshRuntime : Action
        data object InstallRuntime : Action
        data object StartRuntime : Action
        data object StopRuntime : Action
        data object UninstallRuntime : Action
        data class SetAgentProfile(val profile: PicoClawProfile) : Action
        data object ConfigureModel : Action
        data object RefreshHistories : Action
        data object OpenHistory : Action
        data object DeleteHistory : Action
        data object OpenChat : Action
        data object SendChatMessage : Action
        data object CancelChat : Action
        data object CloseAndRelease : Action
    }

    enum class Outcome {
        EnteredAndProbed,
        Applied,
        AlreadySatisfied,
        AcceptedWithoutConfirmation,
        ReconciledToRequestedState,
        ReconciledToDifferentState,
        Indeterminate,
        HistoryDeleted,
        HistoryAbsentAfterLostResponse,
        HistoryPresentAfterLostResponse,
        HistoryOutcomeUnknown,
        HistoryAcceptedWithoutRefresh,
        Dispatched,
        Released,
        HeldByOtherSession,
        ReleaseUnconfirmed,
    }

    enum class Failure {
        SessionChanged,
        FeatureEntryRequired,
        ApprovalRequired,
        AlreadyActive,
        NotConnected,
        ForeignOrStaleState,
        InvalidRequest,
        AuthenticationExpired,
        Connection,
        ServerRejected,
        InvalidResponse,
        ProviderOrRuntime,
        Unexpected,
    }

    sealed interface GuidanceReason {
        data object EnterValidModelConfiguration : GuidanceReason
        data object RefreshHistoryBeforeOpening : GuidanceReason
        data object RefreshHistoryBeforeDeleting : GuidanceReason
        data object ConnectBeforeOpeningFeature : GuidanceReason
        data object DestinationChangedBeforeAction : GuidanceReason
        data object AnotherActionRunning : GuidanceReason
        data object UnsupportedApplicationVersion : GuidanceReason
        data object DestinationChangedReviewAction : GuidanceReason
    }

    data class ActionOutcome(
        val action: Action,
        val outcome: Outcome,
    ) : PicoClawNotice {
        override val kind: PicoClawNoticeKind = when (outcome) {
            Outcome.EnteredAndProbed,
            Outcome.AlreadySatisfied,
            Outcome.Dispatched -> PicoClawNoticeKind.Information
            Outcome.Applied,
            Outcome.HistoryDeleted,
            Outcome.Released -> PicoClawNoticeKind.Applied
            Outcome.ReconciledToRequestedState,
            Outcome.ReconciledToDifferentState,
            Outcome.HistoryAbsentAfterLostResponse,
            Outcome.HistoryPresentAfterLostResponse -> PicoClawNoticeKind.Reconciled
            Outcome.AcceptedWithoutConfirmation,
            Outcome.Indeterminate,
            Outcome.HistoryOutcomeUnknown,
            Outcome.HistoryAcceptedWithoutRefresh,
            Outcome.HeldByOtherSession,
            Outcome.ReleaseUnconfirmed -> PicoClawNoticeKind.Indeterminate
        }
    }

    data class Error(val failure: Failure) : PicoClawNotice {
        override val kind: PicoClawNoticeKind = PicoClawNoticeKind.Rejected
    }

    data class Guidance(val reason: GuidanceReason) : PicoClawNotice {
        override val kind: PicoClawNoticeKind = PicoClawNoticeKind.Rejected
    }
}
