package org.nanokvm.mobile.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import org.nanokvm.mobile.R
import org.nanokvm.mobile.runtime.AdministrationNotice
import org.nanokvm.mobile.runtime.AdministrationTailscaleCommand
import org.nanokvm.mobile.runtime.OperatorNotice
import org.nanokvm.mobile.runtime.OperatorScriptRunMode
import org.nanokvm.mobile.runtime.Phase3ImageMountMode
import org.nanokvm.mobile.runtime.Phase3Notice
import org.nanokvm.mobile.runtime.PicoClawNotice
import org.nanokvm.mobile.runtime.PicoClawProfile

/** Resource-backed rendering for the semantic low-frequency feature notices. */
@Composable
internal fun Phase3Notice.displayText(): String = when (this) {
    is Phase3Notice.ActionOutcome -> {
        val base = when (outcome) {
            Phase3Notice.Outcome.Applied -> when (action) {
                Phase3Notice.Action.StartImageTransfer ->
                    stringResource(R.string.feature_notice_phase3_outcome_transfer_requested)
                Phase3Notice.Action.SendWakePacket ->
                    stringResource(R.string.feature_notice_phase3_outcome_wake_sent)
                is Phase3Notice.Action.MountImage,
                Phase3Notice.Action.RestorePhysicalMedia,
                Phase3Notice.Action.DeleteImage,
                is Phase3Notice.Action.SetDiskEnabled,
                is Phase3Notice.Action.SetHidMode,
                is Phase3Notice.Action.SetNetworkEnabled,
                Phase3Notice.Action.RenameWakeTarget,
                Phase3Notice.Action.DeleteWakeTarget -> stringResource(
                    R.string.feature_notice_phase3_outcome_applied,
                    action.displayText(),
                )
            }
            Phase3Notice.Outcome.AlreadySatisfied ->
                stringResource(R.string.feature_notice_phase3_outcome_already_satisfied)
            Phase3Notice.Outcome.AcceptedWithoutConfirmation ->
                stringResource(R.string.feature_notice_phase3_outcome_accepted)
            Phase3Notice.Outcome.ReconciledToRequestedState ->
                stringResource(R.string.feature_notice_phase3_outcome_reconciled_requested)
            Phase3Notice.Outcome.ReconciledToDifferentState ->
                stringResource(R.string.feature_notice_phase3_outcome_reconciled_different)
            Phase3Notice.Outcome.Indeterminate ->
                stringResource(R.string.feature_notice_phase3_outcome_indeterminate)
        }
        inputRecovery.displayTextOrNull()?.let { followUp ->
            stringResource(R.string.feature_notice_with_follow_up, base, followUp)
        } ?: base
    }
    is Phase3Notice.Error -> {
        val base = when (failure) {
            Phase3Notice.Failure.SessionChanged ->
                stringResource(R.string.feature_notice_phase3_error_session_changed)
            Phase3Notice.Failure.ForeignOrStaleState ->
                stringResource(R.string.feature_notice_phase3_error_stale_state)
            Phase3Notice.Failure.ImageIsMounted ->
                stringResource(R.string.feature_notice_phase3_error_image_mounted)
            Phase3Notice.Failure.ImageTransferDisabled ->
                stringResource(R.string.feature_notice_phase3_error_transfer_disabled)
            Phase3Notice.Failure.InvalidRequest ->
                stringResource(R.string.feature_notice_phase3_error_invalid_request)
            Phase3Notice.Failure.Unsupported ->
                stringResource(R.string.feature_notice_phase3_error_unsupported)
            Phase3Notice.Failure.AuthenticationExpired ->
                stringResource(R.string.feature_notice_phase3_error_authentication_expired)
            Phase3Notice.Failure.Connection ->
                stringResource(R.string.feature_notice_phase3_error_connection)
            Phase3Notice.Failure.ServerRejected ->
                stringResource(R.string.feature_notice_phase3_error_rejected)
            Phase3Notice.Failure.InvalidResponse ->
                stringResource(R.string.feature_notice_phase3_error_invalid_response)
            Phase3Notice.Failure.Unexpected ->
                stringResource(R.string.feature_notice_phase3_error_unexpected)
        }
        inputRecovery.displayTextOrNull()?.let { followUp ->
            stringResource(R.string.feature_notice_with_follow_up, base, followUp)
        } ?: base
    }
    is Phase3Notice.Guidance -> when (reason) {
        Phase3Notice.GuidanceReason.RefreshMediaBeforeSelectingImage ->
            stringResource(R.string.feature_notice_phase3_guidance_refresh_select)
        Phase3Notice.GuidanceReason.RefreshMediaBeforeDeletingImage ->
            stringResource(R.string.feature_notice_phase3_guidance_refresh_delete)
        Phase3Notice.GuidanceReason.UnknownHidModeIsReadOnly ->
            stringResource(R.string.feature_notice_phase3_guidance_hid_read_only)
        Phase3Notice.GuidanceReason.EnterValidImageUrl ->
            stringResource(R.string.feature_notice_phase3_guidance_image_url)
        Phase3Notice.GuidanceReason.EnterValidMacAddress ->
            stringResource(R.string.feature_notice_phase3_guidance_mac)
        Phase3Notice.GuidanceReason.RefreshWakeHistoryBeforeRenaming ->
            stringResource(R.string.feature_notice_phase3_guidance_refresh_wake_rename)
        Phase3Notice.GuidanceReason.RefreshWakeHistoryBeforeDeleting ->
            stringResource(R.string.feature_notice_phase3_guidance_refresh_wake_delete)
        Phase3Notice.GuidanceReason.WaitForImageTransfer ->
            stringResource(R.string.feature_notice_phase3_guidance_wait_transfer)
        Phase3Notice.GuidanceReason.ConnectBeforeOpeningFeatures ->
            stringResource(R.string.feature_notice_phase3_guidance_connect)
        Phase3Notice.GuidanceReason.ReviewActionAfterSessionChange ->
            stringResource(R.string.feature_notice_phase3_guidance_review_session)
        Phase3Notice.GuidanceReason.SessionChangedBeforeSend ->
            stringResource(R.string.feature_notice_phase3_guidance_session_before_send)
        Phase3Notice.GuidanceReason.ActionAlreadyRunning ->
            stringResource(R.string.feature_notice_phase3_guidance_busy)
        Phase3Notice.GuidanceReason.TransferFinishedWithoutChecksum ->
            stringResource(R.string.feature_notice_phase3_guidance_transfer_finished)
        Phase3Notice.GuidanceReason.UnexpectedTransferState ->
            stringResource(R.string.feature_notice_phase3_guidance_transfer_unexpected)
    }
}

@Composable
private fun Phase3Notice.Action.displayText(): String = stringResource(
    when (this) {
        is Phase3Notice.Action.MountImage -> when (mode) {
            Phase3ImageMountMode.MassStorage ->
                R.string.feature_notice_phase3_action_mount_disk
            Phase3ImageMountMode.CdRom -> R.string.feature_notice_phase3_action_mount_cdrom
        }
        Phase3Notice.Action.RestorePhysicalMedia ->
            R.string.feature_notice_phase3_action_restore_media
        Phase3Notice.Action.DeleteImage -> R.string.feature_notice_phase3_action_delete_image
        is Phase3Notice.Action.SetDiskEnabled -> if (enabled) {
            R.string.feature_notice_phase3_action_enable_disk
        } else {
            R.string.feature_notice_phase3_action_disable_disk
        }
        is Phase3Notice.Action.SetHidMode -> when (mode) {
            Phase3Notice.HidMode.Normal -> R.string.feature_notice_phase3_action_hid_normal
            Phase3Notice.HidMode.HidOnly -> R.string.feature_notice_phase3_action_hid_only
        }
        is Phase3Notice.Action.SetNetworkEnabled -> if (enabled) {
            R.string.feature_notice_phase3_action_enable_network
        } else {
            R.string.feature_notice_phase3_action_disable_network
        }
        Phase3Notice.Action.StartImageTransfer ->
            R.string.feature_notice_phase3_action_start_transfer
        Phase3Notice.Action.SendWakePacket -> R.string.feature_notice_phase3_action_send_wake
        Phase3Notice.Action.RenameWakeTarget -> R.string.feature_notice_phase3_action_rename_wake
        Phase3Notice.Action.DeleteWakeTarget -> R.string.feature_notice_phase3_action_delete_wake
    },
)

@Composable
private fun Phase3Notice.InputRecovery.displayTextOrNull(): String? = when (this) {
    Phase3Notice.InputRecovery.NotRequired -> null
    Phase3Notice.InputRecovery.Reconnected ->
        stringResource(R.string.feature_notice_phase3_input_reconnected)
    Phase3Notice.InputRecovery.ReconnectedWithPartialReadback ->
        stringResource(R.string.feature_notice_phase3_input_partial_readback)
    Phase3Notice.InputRecovery.ReconnectedWithoutReadback ->
        stringResource(R.string.feature_notice_phase3_input_no_readback)
}

@Composable
internal fun AdministrationNotice.displayText(): String = when (this) {
    is AdministrationNotice.ActionOutcome -> {
        val base = when (outcome) {
            AdministrationNotice.Outcome.Applied -> stringResource(
                R.string.feature_notice_admin_outcome_applied,
                action.displayText(),
            )
            AdministrationNotice.Outcome.AlreadySatisfied ->
                stringResource(R.string.feature_notice_admin_outcome_already_satisfied)
            AdministrationNotice.Outcome.AcceptedWithoutConfirmation ->
                stringResource(R.string.feature_notice_admin_outcome_accepted)
            AdministrationNotice.Outcome.ReconciledToRequestedState ->
                stringResource(R.string.feature_notice_admin_outcome_reconciled_requested)
            AdministrationNotice.Outcome.ReconciledToDifferentState ->
                stringResource(R.string.feature_notice_admin_outcome_reconciled_different)
            AdministrationNotice.Outcome.Indeterminate ->
                stringResource(R.string.feature_notice_admin_outcome_indeterminate)
            AdministrationNotice.Outcome.DisruptiveCommandAccepted -> when (action) {
                AdministrationNotice.Action.ResetHdmi ->
                    stringResource(R.string.feature_notice_admin_outcome_reset_hdmi)
                AdministrationNotice.Action.EnableTls ->
                    stringResource(R.string.feature_notice_admin_outcome_enable_tls)
                is AdministrationNotice.Action.SetPreviewUpdates,
                AdministrationNotice.Action.StartOnlineUpdate,
                AdministrationNotice.Action.RebootAppliance,
                is AdministrationNotice.Action.SetOledSleep,
                is AdministrationNotice.Action.SetSshEnabled,
                AdministrationNotice.Action.SetHostname,
                is AdministrationNotice.Action.SetMdnsEnabled,
                AdministrationNotice.Action.SetWebTitle,
                AdministrationNotice.Action.ResetWebTitle,
                AdministrationNotice.Action.SetManualDns,
                AdministrationNotice.Action.SetDhcpDns,
                AdministrationNotice.Action.ConnectWifi,
                AdministrationNotice.Action.DisconnectWifi,
                is AdministrationNotice.Action.Tailscale,
                is AdministrationNotice.Action.SetHdmiEnabled,
                is AdministrationNotice.Action.SetMouseJiggler,
                is AdministrationNotice.Action.SetMemoryLimitEnabled,
                is AdministrationNotice.Action.SetSwapSize,
                AdministrationNotice.Action.ChangeCredentials ->
                    stringResource(R.string.feature_notice_admin_outcome_disruptive)
            }
            AdministrationNotice.Outcome.CredentialsChanged ->
                stringResource(R.string.feature_notice_admin_outcome_credentials)
        }
        followUp.displayTextOrNull()?.let { guidance ->
            stringResource(R.string.feature_notice_with_follow_up, base, guidance)
        } ?: base
    }
    is AdministrationNotice.Error -> when (failure) {
        AdministrationNotice.Failure.SessionChanged ->
            stringResource(R.string.feature_notice_admin_error_session_changed)
        AdministrationNotice.Failure.InvalidRequest ->
            stringResource(R.string.feature_notice_admin_error_invalid_request)
        AdministrationNotice.Failure.InvalidPreset ->
            stringResource(R.string.feature_notice_admin_error_invalid_preset)
        AdministrationNotice.Failure.Unsupported ->
            stringResource(R.string.feature_notice_admin_error_unsupported)
        AdministrationNotice.Failure.AuthenticationExpired ->
            stringResource(R.string.feature_notice_admin_error_authentication_expired)
        AdministrationNotice.Failure.Connection ->
            stringResource(R.string.feature_notice_admin_error_connection)
        AdministrationNotice.Failure.ServerRejected ->
            stringResource(R.string.feature_notice_admin_error_rejected)
        AdministrationNotice.Failure.InvalidResponse ->
            stringResource(R.string.feature_notice_admin_error_invalid_response)
        AdministrationNotice.Failure.ControlInvalidResponse ->
            stringResource(R.string.feature_notice_admin_error_control_invalid_response)
        AdministrationNotice.Failure.Unexpected ->
            stringResource(R.string.feature_notice_admin_error_unexpected)
    }
    is AdministrationNotice.Guidance -> when (val value = reason) {
        is AdministrationNotice.GuidanceReason.SectionsUnavailable -> pluralStringResource(
            R.plurals.feature_notice_admin_sections_unavailable,
            value.count,
            value.count,
        )
        AdministrationNotice.GuidanceReason.DestinationChangedReviewAction ->
            stringResource(R.string.feature_notice_admin_guidance_destination_review)
        AdministrationNotice.GuidanceReason.DestinationChangedBeforeSend ->
            stringResource(R.string.feature_notice_admin_guidance_destination_before_send)
        AdministrationNotice.GuidanceReason.ConnectBeforeOpeningAdministration ->
            stringResource(R.string.feature_notice_admin_guidance_connect)
        AdministrationNotice.GuidanceReason.AnotherOperationRunning ->
            stringResource(R.string.feature_notice_admin_guidance_busy)
        AdministrationNotice.GuidanceReason.UnknownMouseJigglerModeIsReadOnly ->
            stringResource(R.string.feature_notice_admin_guidance_jiggler_read_only)
        AdministrationNotice.GuidanceReason.TailscaleAuthorizationReady ->
            stringResource(R.string.feature_notice_admin_guidance_tailscale_ready)
        AdministrationNotice.GuidanceReason.TailscaleAuthorizationPageOpened ->
            stringResource(R.string.feature_notice_admin_guidance_tailscale_opened)
    }
}

@Composable
private fun AdministrationNotice.Action.displayText(): String = stringResource(
    when (this) {
        is AdministrationNotice.Action.SetPreviewUpdates -> if (enabled) {
            R.string.feature_notice_admin_action_enable_preview
        } else {
            R.string.feature_notice_admin_action_disable_preview
        }
        AdministrationNotice.Action.StartOnlineUpdate ->
            R.string.feature_notice_admin_action_update
        AdministrationNotice.Action.RebootAppliance ->
            R.string.feature_notice_admin_action_reboot
        is AdministrationNotice.Action.SetOledSleep -> R.string.feature_notice_admin_action_oled
        is AdministrationNotice.Action.SetSshEnabled -> if (enabled) {
            R.string.feature_notice_admin_action_enable_ssh
        } else {
            R.string.feature_notice_admin_action_disable_ssh
        }
        AdministrationNotice.Action.SetHostname -> R.string.feature_notice_admin_action_hostname
        is AdministrationNotice.Action.SetMdnsEnabled -> if (enabled) {
            R.string.feature_notice_admin_action_enable_mdns
        } else {
            R.string.feature_notice_admin_action_disable_mdns
        }
        AdministrationNotice.Action.SetWebTitle -> R.string.feature_notice_admin_action_web_title
        AdministrationNotice.Action.ResetWebTitle ->
            R.string.feature_notice_admin_action_reset_web_title
        AdministrationNotice.Action.SetManualDns ->
            R.string.feature_notice_admin_action_manual_dns
        AdministrationNotice.Action.SetDhcpDns -> R.string.feature_notice_admin_action_dhcp_dns
        AdministrationNotice.Action.ConnectWifi ->
            R.string.feature_notice_admin_action_connect_wifi
        AdministrationNotice.Action.DisconnectWifi ->
            R.string.feature_notice_admin_action_disconnect_wifi
        is AdministrationNotice.Action.Tailscale -> when (command) {
            AdministrationTailscaleCommand.Install ->
                R.string.feature_notice_admin_action_tailscale_install
            AdministrationTailscaleCommand.Uninstall ->
                R.string.feature_notice_admin_action_tailscale_uninstall
            AdministrationTailscaleCommand.Start ->
                R.string.feature_notice_admin_action_tailscale_start
            AdministrationTailscaleCommand.Stop ->
                R.string.feature_notice_admin_action_tailscale_stop
            AdministrationTailscaleCommand.Restart ->
                R.string.feature_notice_admin_action_tailscale_restart
            AdministrationTailscaleCommand.Up ->
                R.string.feature_notice_admin_action_tailscale_up
            AdministrationTailscaleCommand.Down ->
                R.string.feature_notice_admin_action_tailscale_down
            AdministrationTailscaleCommand.Login ->
                R.string.feature_notice_admin_action_tailscale_login
            AdministrationTailscaleCommand.Logout ->
                R.string.feature_notice_admin_action_tailscale_logout
        }
        is AdministrationNotice.Action.SetHdmiEnabled -> if (enabled) {
            R.string.feature_notice_admin_action_enable_hdmi
        } else {
            R.string.feature_notice_admin_action_disable_hdmi
        }
        AdministrationNotice.Action.ResetHdmi -> R.string.feature_notice_admin_action_reset_hdmi
        is AdministrationNotice.Action.SetMouseJiggler ->
            R.string.feature_notice_admin_action_mouse_jiggler
        is AdministrationNotice.Action.SetMemoryLimitEnabled -> if (enabled) {
            R.string.feature_notice_admin_action_enable_memory
        } else {
            R.string.feature_notice_admin_action_disable_memory
        }
        is AdministrationNotice.Action.SetSwapSize -> R.string.feature_notice_admin_action_swap
        AdministrationNotice.Action.EnableTls -> R.string.feature_notice_admin_action_enable_tls
        AdministrationNotice.Action.ChangeCredentials ->
            R.string.feature_notice_admin_action_credentials
    },
)

@Composable
private fun AdministrationNotice.FollowUp.displayTextOrNull(): String? = when (this) {
    AdministrationNotice.FollowUp.None -> null
    AdministrationNotice.FollowUp.RefreshAuthoritativeState ->
        stringResource(R.string.feature_notice_admin_follow_up_refresh)
    AdministrationNotice.FollowUp.ReviewAuthoritativeState ->
        stringResource(R.string.feature_notice_admin_follow_up_review)
    AdministrationNotice.FollowUp.ReconnectAndRefresh ->
        stringResource(R.string.feature_notice_admin_follow_up_reconnect)
    AdministrationNotice.FollowUp.RediscoverAndReconnect ->
        stringResource(R.string.feature_notice_admin_follow_up_rediscover)
    AdministrationNotice.FollowUp.WaitForRebootAndReconnect ->
        stringResource(R.string.feature_notice_admin_follow_up_reboot)
    AdministrationNotice.FollowUp.ClearSavedCredentialAndEndSession ->
        stringResource(R.string.feature_notice_admin_follow_up_clear_credential)
    AdministrationNotice.FollowUp.VerifyNewCredentialsAfterReconnect ->
        stringResource(R.string.feature_notice_admin_follow_up_verify_credentials)
    AdministrationNotice.FollowUp.RefreshBeforeRepeating ->
        stringResource(R.string.feature_notice_admin_follow_up_refresh_before_repeat)
    AdministrationNotice.FollowUp.ReconnectAndVerifyBeforeRepeating ->
        stringResource(R.string.feature_notice_admin_follow_up_reconnect_verify)
}

@Composable
internal fun OperatorNotice.displayText(): String = when (this) {
    is OperatorNotice.ActionOutcome -> {
        val base = when (outcome) {
            OperatorNotice.Outcome.Dispatched -> when (action) {
                OperatorNotice.Action.EnterTerminal ->
                    stringResource(R.string.feature_notice_operator_outcome_enter_terminal)
                OperatorNotice.Action.ResizeTerminal ->
                    stringResource(R.string.feature_notice_operator_outcome_resize)
                OperatorNotice.Action.StartSerial ->
                    stringResource(R.string.feature_notice_operator_outcome_start_serial)
                OperatorNotice.Action.ExitSerial ->
                    stringResource(R.string.feature_notice_operator_outcome_exit_serial)
                OperatorNotice.Action.CloseTerminal,
                OperatorNotice.Action.SendTerminalInput,
                OperatorNotice.Action.UploadScript,
                is OperatorNotice.Action.RunScript,
                OperatorNotice.Action.DeleteScript -> stringResource(
                    R.string.feature_notice_operator_outcome_dispatched,
                    action.displayText(),
                )
            }
            OperatorNotice.Outcome.Completed -> stringResource(
                R.string.feature_notice_operator_outcome_completed,
                action.displayText(),
            )
            OperatorNotice.Outcome.ReconciledAbsent ->
                stringResource(R.string.feature_notice_operator_outcome_reconciled_absent)
            OperatorNotice.Outcome.ReconciledPresent ->
                stringResource(R.string.feature_notice_operator_outcome_reconciled_present)
            OperatorNotice.Outcome.Indeterminate -> stringResource(
                R.string.feature_notice_operator_outcome_indeterminate,
                action.displayText(),
            )
        }
        OperatorNotice.Warning.entries.filter(warnings::contains).fold(base) { message, warning ->
            stringResource(
                R.string.feature_notice_with_follow_up,
                message,
                warning.displayText(),
            )
        }
    }
    is OperatorNotice.Error -> {
        val base = when (failure) {
            OperatorNotice.Failure.SessionChanged ->
                stringResource(R.string.feature_notice_operator_error_session_changed)
            OperatorNotice.Failure.NotForeground ->
                stringResource(R.string.feature_notice_operator_error_foreground)
            OperatorNotice.Failure.ElevatedApprovalRequired ->
                stringResource(R.string.feature_notice_operator_error_approval)
            OperatorNotice.Failure.AlreadyActive ->
                stringResource(R.string.feature_notice_operator_error_active)
            OperatorNotice.Failure.NotConnected ->
                stringResource(R.string.feature_notice_operator_error_not_connected)
            OperatorNotice.Failure.ForeignOrStaleState ->
                stringResource(R.string.feature_notice_operator_error_stale)
            OperatorNotice.Failure.InvalidRequest ->
                stringResource(R.string.feature_notice_operator_error_invalid_request)
            OperatorNotice.Failure.AuthenticationExpired ->
                stringResource(R.string.feature_notice_operator_error_authentication_expired)
            OperatorNotice.Failure.Connection ->
                stringResource(R.string.feature_notice_operator_error_connection)
            OperatorNotice.Failure.ServerRejected ->
                stringResource(R.string.feature_notice_operator_error_rejected)
            OperatorNotice.Failure.InvalidResponse ->
                stringResource(R.string.feature_notice_operator_error_invalid_response)
            OperatorNotice.Failure.Unexpected ->
                stringResource(R.string.feature_notice_operator_error_unexpected)
        }
        OperatorNotice.Warning.entries.filter(warnings::contains).fold(base) { message, warning ->
            stringResource(
                R.string.feature_notice_with_follow_up,
                message,
                warning.displayText(),
            )
        }
    }
    is OperatorNotice.Guidance -> when (reason) {
        OperatorNotice.GuidanceReason.TerminalClosedNeedsFreshConfirmation ->
            stringResource(R.string.feature_notice_operator_guidance_terminal_closed)
        OperatorNotice.GuidanceReason.InvalidTerminalDimensions ->
            stringResource(R.string.feature_notice_operator_guidance_dimensions)
        OperatorNotice.GuidanceReason.RefreshScriptCatalog ->
            stringResource(R.string.feature_notice_operator_guidance_refresh_scripts)
        OperatorNotice.GuidanceReason.ConnectBeforeOpeningTools ->
            stringResource(R.string.feature_notice_operator_guidance_connect)
        OperatorNotice.GuidanceReason.SessionNoLongerCurrent ->
            stringResource(R.string.feature_notice_operator_guidance_session)
        OperatorNotice.GuidanceReason.DestinationChangedReviewAction ->
            stringResource(R.string.feature_notice_operator_guidance_destination_review)
        OperatorNotice.GuidanceReason.DestinationChangedBeforeSend ->
            stringResource(R.string.feature_notice_operator_guidance_destination_before_send)
        OperatorNotice.GuidanceReason.AnotherActionRunning ->
            stringResource(R.string.feature_notice_operator_guidance_busy)
    }
}

@Composable
private fun OperatorNotice.Action.displayText(): String = stringResource(
    when (this) {
        OperatorNotice.Action.EnterTerminal -> R.string.feature_notice_operator_action_enter_terminal
        OperatorNotice.Action.CloseTerminal -> R.string.feature_notice_operator_action_close_terminal
        OperatorNotice.Action.SendTerminalInput -> R.string.feature_notice_operator_action_input
        OperatorNotice.Action.ResizeTerminal -> R.string.feature_notice_operator_action_resize
        OperatorNotice.Action.StartSerial -> R.string.feature_notice_operator_action_start_serial
        OperatorNotice.Action.ExitSerial -> R.string.feature_notice_operator_action_exit_serial
        OperatorNotice.Action.UploadScript -> R.string.feature_notice_operator_action_upload_script
        is OperatorNotice.Action.RunScript -> when (mode) {
            OperatorScriptRunMode.Foreground ->
                R.string.feature_notice_operator_action_run_foreground
            OperatorScriptRunMode.Background ->
                R.string.feature_notice_operator_action_run_background
        }
        OperatorNotice.Action.DeleteScript -> R.string.feature_notice_operator_action_delete_script
    },
)

@Composable
private fun OperatorNotice.Warning.displayText(): String = stringResource(
    when (this) {
        OperatorNotice.Warning.ForegroundCancellationDoesNotStopProcess ->
            R.string.feature_notice_operator_warning_foreground_cancel
        OperatorNotice.Warning.BackgroundHasNoStatusOrCancellation ->
            R.string.feature_notice_operator_warning_background_status
    },
)

@Composable
internal fun PicoClawNotice.displayText(): String = when (this) {
    is PicoClawNotice.ActionOutcome -> when (outcome) {
        PicoClawNotice.Outcome.EnteredAndProbed ->
            stringResource(R.string.feature_notice_picoclaw_outcome_entered)
        PicoClawNotice.Outcome.Applied -> when (action) {
            PicoClawNotice.Action.ConfigureModel ->
                stringResource(R.string.feature_notice_picoclaw_outcome_model_applied)
            PicoClawNotice.Action.EnterFeature,
            PicoClawNotice.Action.RefreshRuntime,
            PicoClawNotice.Action.InstallRuntime,
            PicoClawNotice.Action.StartRuntime,
            PicoClawNotice.Action.StopRuntime,
            PicoClawNotice.Action.UninstallRuntime,
            is PicoClawNotice.Action.SetAgentProfile,
            PicoClawNotice.Action.RefreshHistories,
            PicoClawNotice.Action.OpenHistory,
            PicoClawNotice.Action.DeleteHistory,
            PicoClawNotice.Action.OpenChat,
            PicoClawNotice.Action.SendChatMessage,
            PicoClawNotice.Action.CancelChat,
            PicoClawNotice.Action.CloseAndRelease -> stringResource(
                R.string.feature_notice_picoclaw_outcome_applied,
                action.displayText(),
            )
        }
        PicoClawNotice.Outcome.AlreadySatisfied ->
            stringResource(R.string.feature_notice_picoclaw_outcome_already)
        PicoClawNotice.Outcome.AcceptedWithoutConfirmation ->
            stringResource(R.string.feature_notice_picoclaw_outcome_accepted)
        PicoClawNotice.Outcome.ReconciledToRequestedState ->
            stringResource(R.string.feature_notice_picoclaw_outcome_reconciled_requested)
        PicoClawNotice.Outcome.ReconciledToDifferentState ->
            stringResource(R.string.feature_notice_picoclaw_outcome_reconciled_different)
        PicoClawNotice.Outcome.Indeterminate ->
            stringResource(R.string.feature_notice_picoclaw_outcome_indeterminate)
        PicoClawNotice.Outcome.HistoryDeleted ->
            stringResource(R.string.feature_notice_picoclaw_outcome_history_deleted)
        PicoClawNotice.Outcome.HistoryAbsentAfterLostResponse ->
            stringResource(R.string.feature_notice_picoclaw_outcome_history_absent)
        PicoClawNotice.Outcome.HistoryPresentAfterLostResponse ->
            stringResource(R.string.feature_notice_picoclaw_outcome_history_present)
        PicoClawNotice.Outcome.HistoryOutcomeUnknown ->
            stringResource(R.string.feature_notice_picoclaw_outcome_history_unknown)
        PicoClawNotice.Outcome.HistoryAcceptedWithoutRefresh ->
            stringResource(R.string.feature_notice_picoclaw_outcome_history_no_refresh)
        PicoClawNotice.Outcome.Dispatched -> when (action) {
            PicoClawNotice.Action.OpenChat ->
                stringResource(R.string.feature_notice_picoclaw_outcome_open_chat)
            PicoClawNotice.Action.SendChatMessage ->
                stringResource(R.string.feature_notice_picoclaw_outcome_send_chat)
            PicoClawNotice.Action.CancelChat ->
                stringResource(R.string.feature_notice_picoclaw_outcome_cancel_chat)
            PicoClawNotice.Action.EnterFeature,
            PicoClawNotice.Action.RefreshRuntime,
            PicoClawNotice.Action.InstallRuntime,
            PicoClawNotice.Action.StartRuntime,
            PicoClawNotice.Action.StopRuntime,
            PicoClawNotice.Action.UninstallRuntime,
            is PicoClawNotice.Action.SetAgentProfile,
            PicoClawNotice.Action.ConfigureModel,
            PicoClawNotice.Action.RefreshHistories,
            PicoClawNotice.Action.OpenHistory,
            PicoClawNotice.Action.DeleteHistory,
            PicoClawNotice.Action.CloseAndRelease -> stringResource(
                R.string.feature_notice_picoclaw_outcome_dispatched,
                action.displayText(),
            )
        }
        PicoClawNotice.Outcome.Released ->
            stringResource(R.string.feature_notice_picoclaw_outcome_released)
        PicoClawNotice.Outcome.HeldByOtherSession ->
            stringResource(R.string.feature_notice_picoclaw_outcome_held_other)
        PicoClawNotice.Outcome.ReleaseUnconfirmed ->
            stringResource(R.string.feature_notice_picoclaw_outcome_release_unknown)
    }
    is PicoClawNotice.Error -> when (failure) {
        PicoClawNotice.Failure.SessionChanged ->
            stringResource(R.string.feature_notice_picoclaw_error_session_changed)
        PicoClawNotice.Failure.FeatureEntryRequired ->
            stringResource(R.string.feature_notice_picoclaw_error_entry)
        PicoClawNotice.Failure.ApprovalRequired ->
            stringResource(R.string.feature_notice_picoclaw_error_approval)
        PicoClawNotice.Failure.AlreadyActive ->
            stringResource(R.string.feature_notice_picoclaw_error_active)
        PicoClawNotice.Failure.NotConnected ->
            stringResource(R.string.feature_notice_picoclaw_error_not_connected)
        PicoClawNotice.Failure.ForeignOrStaleState ->
            stringResource(R.string.feature_notice_picoclaw_error_stale)
        PicoClawNotice.Failure.InvalidRequest ->
            stringResource(R.string.feature_notice_picoclaw_error_invalid_request)
        PicoClawNotice.Failure.AuthenticationExpired ->
            stringResource(R.string.feature_notice_picoclaw_error_authentication_expired)
        PicoClawNotice.Failure.Connection ->
            stringResource(R.string.feature_notice_picoclaw_error_connection)
        PicoClawNotice.Failure.ServerRejected ->
            stringResource(R.string.feature_notice_picoclaw_error_rejected)
        PicoClawNotice.Failure.InvalidResponse ->
            stringResource(R.string.feature_notice_picoclaw_error_invalid_response)
        PicoClawNotice.Failure.ProviderOrRuntime ->
            stringResource(R.string.feature_notice_picoclaw_error_provider)
        PicoClawNotice.Failure.Unexpected ->
            stringResource(R.string.feature_notice_picoclaw_error_unexpected)
    }
    is PicoClawNotice.Guidance -> when (reason) {
        PicoClawNotice.GuidanceReason.EnterValidModelConfiguration ->
            stringResource(R.string.feature_notice_picoclaw_guidance_model)
        PicoClawNotice.GuidanceReason.RefreshHistoryBeforeOpening ->
            stringResource(R.string.feature_notice_picoclaw_guidance_history_open)
        PicoClawNotice.GuidanceReason.RefreshHistoryBeforeDeleting ->
            stringResource(R.string.feature_notice_picoclaw_guidance_history_delete)
        PicoClawNotice.GuidanceReason.ConnectBeforeOpeningFeature ->
            stringResource(R.string.feature_notice_picoclaw_guidance_connect)
        PicoClawNotice.GuidanceReason.DestinationChangedBeforeAction ->
            stringResource(R.string.feature_notice_picoclaw_guidance_destination_before_action)
        PicoClawNotice.GuidanceReason.AnotherActionRunning ->
            stringResource(R.string.feature_notice_picoclaw_guidance_busy)
        PicoClawNotice.GuidanceReason.UnsupportedApplicationVersion ->
            stringResource(R.string.feature_notice_picoclaw_guidance_version)
        PicoClawNotice.GuidanceReason.DestinationChangedReviewAction ->
            stringResource(R.string.feature_notice_picoclaw_guidance_destination_review)
    }
}

@Composable
private fun PicoClawNotice.Action.displayText(): String = stringResource(
    when (this) {
        PicoClawNotice.Action.EnterFeature -> R.string.feature_notice_picoclaw_action_enter
        PicoClawNotice.Action.RefreshRuntime -> R.string.feature_notice_picoclaw_action_refresh
        PicoClawNotice.Action.InstallRuntime -> R.string.feature_notice_picoclaw_action_install
        PicoClawNotice.Action.StartRuntime -> R.string.feature_notice_picoclaw_action_start
        PicoClawNotice.Action.StopRuntime -> R.string.feature_notice_picoclaw_action_stop
        PicoClawNotice.Action.UninstallRuntime -> R.string.feature_notice_picoclaw_action_uninstall
        is PicoClawNotice.Action.SetAgentProfile -> when (profile) {
            PicoClawProfile.Default -> R.string.feature_notice_picoclaw_action_profile_default
            PicoClawProfile.Kvm -> R.string.feature_notice_picoclaw_action_profile_kvm
        }
        PicoClawNotice.Action.ConfigureModel -> R.string.feature_notice_picoclaw_action_model
        PicoClawNotice.Action.RefreshHistories ->
            R.string.feature_notice_picoclaw_action_refresh_history
        PicoClawNotice.Action.OpenHistory -> R.string.feature_notice_picoclaw_action_open_history
        PicoClawNotice.Action.DeleteHistory -> R.string.feature_notice_picoclaw_action_delete_history
        PicoClawNotice.Action.OpenChat -> R.string.feature_notice_picoclaw_action_open_chat
        PicoClawNotice.Action.SendChatMessage -> R.string.feature_notice_picoclaw_action_send_chat
        PicoClawNotice.Action.CancelChat -> R.string.feature_notice_picoclaw_action_cancel_chat
        PicoClawNotice.Action.CloseAndRelease -> R.string.feature_notice_picoclaw_action_release
    },
)
