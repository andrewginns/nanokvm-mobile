package org.nanokvm.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.nanokvm.mobile.BuildConfig
import org.nanokvm.mobile.R
import org.nanokvm.mobile.data.HostProfile
import org.nanokvm.mobile.data.ProfileInputPolicy
import org.nanokvm.mobile.data.ThemeMode
import org.nanokvm.mobile.ui.ProfileStorageIssue
import org.nanokvm.mobile.ui.profileStorageIssueMessageResource
import org.nanokvm.mobile.ui.ProfileMutationUiState

private val ProfileContentMaxWidth = 720.dp
private val EditorContentMaxWidth = 640.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesScreen(
    profiles: List<HostProfile>,
    profileCatalogResolved: Boolean,
    savedPasswordProfileIds: Set<String>,
    profileStorageIssue: ProfileStorageIssue?,
    profileStorageBusy: Boolean,
    passwordEntryProfile: HostProfile?,
    canSavePassword: Boolean,
    themeMode: ThemeMode,
    useDynamicColor: Boolean,
    dynamicColorAvailable: Boolean,
    onAdd: () -> Unit,
    onEdit: (HostProfile) -> Unit,
    onPrepareConnection: (HostProfile) -> Unit,
    onSubmitPassword: (HostProfile, CharArray, Boolean) -> Unit,
    onDismissPassword: () -> Unit,
    onRemoveSavedCredential: (String) -> Unit,
    onResetProfileStorage: () -> Unit,
    onRetryProfileStorage: () -> Unit,
    onThemeModeChange: (ThemeMode) -> Unit,
    onUseDynamicColorChange: (Boolean) -> Unit,
) {
    var showAbout by remember { mutableStateOf(false) }
    var showAppearance by remember { mutableStateOf(false) }
    var showCorruptStorageRecovery by rememberSaveable(profileStorageIssue) {
        mutableStateOf(profileStorageIssue == ProfileStorageIssue.Corrupted)
    }
    val aboutDescription = stringResource(R.string.profiles_about_content_description)
    val addDescription = stringResource(R.string.profiles_add_content_description)
    val appearanceDescription = stringResource(R.string.appearance_content_description)

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.app_name))
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                actions = {
                    IconButton(
                        onClick = { showAppearance = true },
                        modifier = Modifier.semantics { contentDescription = appearanceDescription },
                    ) {
                        Icon(Icons.Default.Palette, contentDescription = null)
                    }
                    IconButton(
                        onClick = { showAbout = true },
                        modifier = Modifier.semantics { contentDescription = aboutDescription },
                    ) {
                        Icon(Icons.Default.Info, contentDescription = null)
                    }
                },
            )
        },
        floatingActionButton = {
            if (profileCatalogResolved && profileStorageIssue == null && !profileStorageBusy) {
                FloatingActionButton(
                    onClick = onAdd,
                    modifier = Modifier.semantics { contentDescription = addDescription },
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                modifier = Modifier
                    .widthIn(max = ProfileContentMaxWidth)
                    .fillMaxWidth()
                    .fillMaxHeight(),
                contentPadding = PaddingValues(start = 16.dp, top = 10.dp, end = 16.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    SectionLabel(stringResource(R.string.profiles_connections_section))
                }
                if (!profileCatalogResolved) {
                    item { ProfilesLoadingState() }
                } else if (profileStorageIssue != null) {
                    item {
                        ProfileStorageRecoveryState(
                            issue = profileStorageIssue,
                            onReviewRecovery = {
                                showCorruptStorageRecovery = true
                            },
                        )
                    }
                } else if (profiles.isEmpty()) {
                    item { ProfilesEmptyState(onAdd = onAdd) }
                } else {
                    items(profiles, key = HostProfile::id) { profile ->
                        ProfileCard(
                            profile = profile,
                            hasSavedPassword = profile.id in savedPasswordProfileIds,
                            onEdit = { onEdit(profile) },
                            onConnect = { onPrepareConnection(profile) },
                        )
                    }
                }
                item {
                    Text(
                        stringResource(R.string.profiles_security_note),
                        modifier = Modifier.padding(top = 8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }

    passwordEntryProfile?.let { profile ->
        PasswordDialog(
            profile = profile,
            hasSavedPassword = profile.id in savedPasswordProfileIds,
            canSavePassword = canSavePassword,
            onDismiss = onDismissPassword,
            onRemoveSavedPassword = { onRemoveSavedCredential(profile.id) },
            onConnect = { password, savePassword ->
                onSubmitPassword(profile, password.toCharArray(), savePassword)
            },
        )
    }
    if (showAbout) {
        AboutDialog(onDismiss = { showAbout = false })
    }
    if (showAppearance) {
        AppearanceDialog(
            themeMode = themeMode,
            useDynamicColor = useDynamicColor,
            dynamicColorAvailable = dynamicColorAvailable,
            onThemeModeChange = onThemeModeChange,
            onUseDynamicColorChange = onUseDynamicColorChange,
            onDismiss = { showAppearance = false },
        )
    }
    profileStorageIssue?.let { issue ->
        when (issue) {
            ProfileStorageIssue.Corrupted -> if (showCorruptStorageRecovery) {
                AlertDialog(
                    onDismissRequest = {
                        if (!profileStorageBusy) showCorruptStorageRecovery = false
                    },
                    icon = { Icon(Icons.Default.DeleteOutline, contentDescription = null) },
                    title = { Text(stringResource(R.string.profile_storage_corrupted_title)) },
                    text = {
                        Text(
                            stringResource(
                                R.string.profile_storage_corrupted_message,
                                stringResource(profileStorageIssueMessageResource(issue)),
                                stringResource(R.string.profile_storage_reset_consequence),
                            ),
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = onResetProfileStorage,
                            enabled = !profileStorageBusy,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                            ),
                        ) {
                            Text(
                                stringResource(
                                    if (profileStorageBusy) {
                                        R.string.profile_storage_resetting
                                    } else {
                                        R.string.profile_storage_reset_action
                                    },
                                ),
                            )
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = { showCorruptStorageRecovery = false },
                            enabled = !profileStorageBusy,
                        ) {
                            Text(stringResource(R.string.profile_storage_keep_data_action))
                        }
                    },
                )
            }

            ProfileStorageIssue.Unavailable -> AlertDialog(
                onDismissRequest = {},
                icon = { Icon(Icons.Default.Info, contentDescription = null) },
                title = { Text(stringResource(R.string.profile_storage_unavailable_title)) },
                text = { Text(stringResource(profileStorageIssueMessageResource(issue))) },
                confirmButton = {
                    Button(onClick = onRetryProfileStorage, enabled = !profileStorageBusy) {
                        Text(
                            stringResource(
                                if (profileStorageBusy) {
                                    R.string.profile_storage_retrying
                                } else {
                                    R.string.profile_storage_retry_action
                                },
                            ),
                        )
                    }
                },
            )
        }
    }
}

@Composable
private fun AppearanceDialog(
    themeMode: ThemeMode,
    useDynamicColor: Boolean,
    dynamicColorAvailable: Boolean,
    onThemeModeChange: (ThemeMode) -> Unit,
    onUseDynamicColorChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Palette, contentDescription = null) },
        title = { Text(stringResource(R.string.appearance_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.appearance_theme_section),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                ThemeMode.entries.forEach { mode ->
                    val label = stringResource(
                        when (mode) {
                            ThemeMode.SYSTEM -> R.string.appearance_theme_system
                            ThemeMode.LIGHT -> R.string.appearance_theme_light
                            ThemeMode.DARK -> R.string.appearance_theme_dark
                        },
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(
                                selected = themeMode == mode,
                                role = Role.RadioButton,
                                onClick = { onThemeModeChange(mode) },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = themeMode == mode, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                if (dynamicColorAvailable) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .toggleable(
                                value = useDynamicColor,
                                role = Role.Switch,
                                onValueChange = onUseDynamicColorChange,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.appearance_dynamic_color_title),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                stringResource(R.string.appearance_dynamic_color_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = useDynamicColor, onCheckedChange = null)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        },
    )
}

@Composable
private fun ProfilesLoadingState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator()
        Text(
            stringResource(R.string.profiles_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ProfilesEmptyState(onAdd: () -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                modifier = Modifier.size(56.dp),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Computer,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
            Text(
                stringResource(R.string.profiles_empty_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.profiles_empty_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(onClick = onAdd) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.profiles_add_action))
            }
        }
    }
}

@Composable
private fun ProfileStorageRecoveryState(
    issue: ProfileStorageIssue,
    onReviewRecovery: () -> Unit,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(R.string.profile_storage_recovery_pending_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                stringResource(profileStorageIssueMessageResource(issue)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (issue == ProfileStorageIssue.Corrupted) {
                Text(
                    stringResource(R.string.profile_storage_recovery_pending_message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FilledTonalButton(onClick = onReviewRecovery) {
                    Text(stringResource(R.string.profile_storage_review_action))
                }
            }
        }
    }
}

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Info, contentDescription = null) },
        title = {
            Text(stringResource(R.string.about_title, BuildConfig.VERSION_NAME))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.about_description))
                Text(stringResource(R.string.about_copyright))
                Text(
                    stringResource(R.string.about_license),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.about_independence),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        },
    )
}

@Composable
private fun ProfileCard(
    profile: HostProfile,
    hasSavedPassword: Boolean,
    onEdit: () -> Unit,
    onConnect: () -> Unit,
) {
    val editDescription = stringResource(R.string.profile_edit_content_description, profile.name)
    val securityStatus = when {
        !profile.useHttps -> stringResource(R.string.profile_http_upgrade_required)
        profile.trustedCertificateSha256 != null -> stringResource(R.string.profile_https_certificate_pinned)
        else -> stringResource(R.string.profile_https_certificate_checked)
    }
    val securityColor = when {
        !profile.useHttps -> MaterialTheme.colorScheme.error
        profile.trustedCertificateSha256 != null -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            leadingContent = {
                Surface(
                    modifier = Modifier.size(48.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Computer,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            },
            headlineContent = { Text(profile.name) },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(profile.baseUrl)
                    if (hasSavedPassword) {
                        Text(
                            stringResource(R.string.profile_saved_password_status),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            },
            trailingContent = {
                IconButton(
                    onClick = onEdit,
                    modifier = Modifier.semantics { contentDescription = editDescription },
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null)
                }
            },
        )
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 10.dp, end = 12.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                if (profile.trustedCertificateSha256 != null) Icons.Default.Lock else Icons.Default.LockOpen,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = securityColor,
            )
            Text(
                securityStatus,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = securityColor,
            )
            FilledTonalButton(onClick = if (profile.useHttps) onConnect else onEdit) {
                if (!profile.useHttps) {
                    Text(stringResource(R.string.profile_upgrade_action))
                } else if (hasSavedPassword) {
                    Icon(
                        Icons.Default.Fingerprint,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.profile_unlock_action))
                } else {
                    Text(stringResource(R.string.profile_connect_action))
                }
            }
        }
    }
}

@Composable
private fun PasswordDialog(
    profile: HostProfile,
    hasSavedPassword: Boolean,
    canSavePassword: Boolean,
    onDismiss: () -> Unit,
    onRemoveSavedPassword: () -> Unit,
    onConnect: (String, Boolean) -> Unit,
) {
    var password by remember(profile.id) { mutableStateOf("") }
    var savePassword by remember(profile.id) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Lock, contentDescription = null) },
        title = { Text(stringResource(R.string.password_dialog_title, profile.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.password_dialog_account, profile.username, profile.authority),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.password_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                if (canSavePassword) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .toggleable(
                                value = savePassword,
                                role = Role.Checkbox,
                                onValueChange = { savePassword = it },
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = savePassword, onCheckedChange = null)
                        Text(
                            stringResource(
                                if (hasSavedPassword) {
                                    R.string.password_replace_saved_choice
                                } else {
                                    R.string.password_save_securely_choice
                                },
                            ),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                } else {
                    Text(
                        stringResource(R.string.password_session_only_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (hasSavedPassword) {
                    TextButton(onClick = onRemoveSavedPassword) {
                        Text(stringResource(R.string.password_remove_saved_action))
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConnect(password, savePassword) }, enabled = password.isNotEmpty()) {
                Text(
                    stringResource(
                        if (savePassword) R.string.password_save_connect_action else R.string.profile_connect_action,
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditorScreen(
    initial: HostProfile,
    isNew: Boolean,
    mutation: ProfileMutationUiState = ProfileMutationUiState.Idle,
    hasSavedPassword: Boolean,
    onSave: (HostProfile) -> Unit,
    onDelete: ((HostProfile) -> Unit)?,
    onRemoveSavedPassword: (String) -> Unit,
    onForgetCertificate: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var name by rememberSaveable(initial.id) { mutableStateOf(initial.name) }
    var host by rememberSaveable(initial.id) { mutableStateOf(initial.host) }
    var port by rememberSaveable(initial.id) {
        mutableStateOf(if (!initial.useHttps && initial.port == 80) "443" else initial.port.toString())
    }
    var username by rememberSaveable(initial.id) { mutableStateOf(initial.username) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmRemovePassword by remember { mutableStateOf(false) }
    val portValue = port.toIntOrNull()
    val prospectiveProfile = initial.copy(
        name = name.trim(),
        host = host,
        port = portValue ?: initial.port,
        useHttps = true,
        username = username.trim(),
    )
    val valid = portValue != null && ProfileInputPolicy.isValid(prospectiveProfile)
    val mutationInProgress = mutation != ProfileMutationUiState.Idle

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (isNew) R.string.profile_editor_add_title else R.string.profile_editor_edit_title,
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                modifier = Modifier
                    .testTag("profile-editor-list")
                    .widthIn(max = EditorContentMaxWidth)
                    .fillMaxWidth()
                    .fillMaxHeight(),
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    EditorSection(title = stringResource(R.string.profile_editor_connection_section)) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = ProfileInputPolicy.boundName(it) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.profile_name_label)) },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = host,
                            onValueChange = {
                                host = ProfileInputPolicy.boundHost(it.trim())
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.profile_host_label)) },
                            placeholder = { Text(stringResource(R.string.profile_host_placeholder)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        )
                        OutlinedTextField(
                            value = port,
                            onValueChange = { port = it.filter(Char::isDigit).take(5) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.profile_port_label)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            isError = port.isNotEmpty() && portValue !in 1..65535,
                            supportingText = if (port.isNotEmpty() && portValue !in 1..65535) {
                                { Text(stringResource(R.string.profile_port_error)) }
                            } else {
                                null
                            },
                        )
                    }
                }
                item {
                    EditorSection(title = stringResource(R.string.profile_editor_authentication_section)) {
                        OutlinedTextField(
                            value = username,
                            onValueChange = {
                                username = ProfileInputPolicy.boundUsername(it)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.profile_username_label)) },
                            singleLine = true,
                        )
                        if (hasSavedPassword) {
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                shape = MaterialTheme.shapes.medium,
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Fingerprint, contentDescription = null)
                                        Spacer(Modifier.width(10.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                stringResource(R.string.profile_saved_password_title),
                                                style = MaterialTheme.typography.titleSmall,
                                            )
                                            Text(
                                                stringResource(R.string.profile_saved_password_description),
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                        }
                                    }
                                    TextButton(onClick = { confirmRemovePassword = true }) {
                                        Text(stringResource(R.string.password_remove_saved_action))
                                    }
                                }
                            }
                        } else {
                            Text(
                                stringResource(R.string.profile_password_saved_after_login_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                item {
                    EditorSection(title = stringResource(R.string.profile_editor_trust_section)) {
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    stringResource(
                                        if (initial.useHttps) {
                                            R.string.profile_https_required_title
                                        } else {
                                            R.string.profile_upgrade_https_title
                                        },
                                    ),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    stringResource(
                                        if (initial.useHttps) {
                                            R.string.profile_https_required_description
                                        } else {
                                            R.string.profile_upgrade_https_description
                                        },
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        initial.trustedCertificateSha256?.let { fingerprint ->
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    stringResource(R.string.profile_pinned_certificate_title),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                SelectionContainer {
                                    Text(
                                        fingerprint,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                TextButton(
                                    onClick = { onForgetCertificate(initial.id) },
                                ) {
                                    Text(stringResource(R.string.profile_forget_certificate_action))
                                }
                            }
                        }
                    }
                }
                item {
                    Button(
                        onClick = {
                            onSave(
                                prospectiveProfile,
                            )
                        },
                        enabled = valid && !mutationInProgress,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp),
                    ) {
                        if (mutation is ProfileMutationUiState.Saving) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.profile_saving_action))
                        } else {
                            Text(stringResource(R.string.profile_save_action))
                        }
                    }
                }
                if (onDelete != null) {
                    item {
                        EditorSection(title = stringResource(R.string.profile_editor_destructive_section)) {
                            Text(
                                stringResource(R.string.profile_delete_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedButton(
                                onClick = { confirmDelete = true },
                                enabled = !mutationInProgress,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 52.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error,
                                ),
                            ) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.profile_delete_action))
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = {
                if (mutation !is ProfileMutationUiState.Deleting) confirmDelete = false
            },
            title = { Text(stringResource(R.string.profile_delete_dialog_title, initial.name)) },
            text = { Text(stringResource(R.string.profile_delete_dialog_message)) },
            confirmButton = {
                Button(
                    onClick = { onDelete(initial) },
                    enabled = !mutationInProgress,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    if (mutation is ProfileMutationUiState.Deleting) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.profile_deleting_action))
                    } else {
                        Text(stringResource(R.string.common_delete))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
    if (confirmRemovePassword && hasSavedPassword) {
        AlertDialog(
            onDismissRequest = { confirmRemovePassword = false },
            title = { Text(stringResource(R.string.password_remove_dialog_title)) },
            text = { Text(stringResource(R.string.password_remove_dialog_message)) },
            confirmButton = {
                Button(
                    onClick = {
                        confirmRemovePassword = false
                        onRemoveSavedPassword(initial.id)
                    },
                ) {
                    Text(stringResource(R.string.common_remove))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemovePassword = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun EditorSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(title)
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}
