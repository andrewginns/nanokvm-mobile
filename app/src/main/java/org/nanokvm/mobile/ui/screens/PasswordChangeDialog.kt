package org.nanokvm.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import org.nanokvm.mobile.R

/**
 * Consequence review for the session-ending account mutation. Secret text exists only in this
 * transient composition and is converted to an owned mutable array at the dispatch boundary.
 */
@Composable
internal fun PasswordChangeDialog(
    destinationLabel: String,
    currentUsername: String,
    protectedCredentialAvailable: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (username: String, password: CharArray, saveProtectedCredential: Boolean) -> Unit,
) {
    var username by remember(currentUsername) { mutableStateOf(currentUsername) }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var saveProtectedCredential by remember(protectedCredentialAvailable) {
        mutableStateOf(false)
    }

    fun clearTransientSecrets() {
        password = ""
        confirmation = ""
    }

    DisposableEffect(Unit) {
        onDispose(::clearTransientSecrets)
    }

    val usernameValid = username.isNotEmpty() &&
        username == username.trim() &&
        username.encodeToByteArray().size <= MAX_ACCOUNT_BYTES &&
        username.none(Char::isISOControl) &&
        username.none { it in INVALID_ACCOUNT_CHARACTERS }
    val passwordValid = password.isNotEmpty() &&
        password.length <= MAX_ACCOUNT_CHARS &&
        password.encodeToByteArray().size <= MAX_ACCOUNT_BYTES &&
        password.none { it in INVALID_ACCOUNT_CHARACTERS }
    val matches = password == confirmation
    val canSubmit = usernameValid && passwordValid && matches

    AlertDialog(
        modifier = Modifier.fillMaxWidth().testTag("password-change-dialog"),
        onDismissRequest = {
            clearTransientSecrets()
            onDismiss()
        },
        title = { Text(stringResource(R.string.password_change_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.password_change_destination, destinationLabel),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(R.string.password_change_consequence),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(stringResource(R.string.password_change_username)) },
                    singleLine = true,
                    isError = username.isNotEmpty() && !usernameValid,
                    keyboardOptions = KeyboardOptions(
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("password-change-username"),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.password_change_new_password)) },
                    singleLine = true,
                    isError = password.isNotEmpty() && !passwordValid,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("password-change-password"),
                )
                OutlinedTextField(
                    value = confirmation,
                    onValueChange = { confirmation = it },
                    label = { Text(stringResource(R.string.password_change_confirm_password)) },
                    singleLine = true,
                    isError = confirmation.isNotEmpty() && !matches,
                    supportingText = if (confirmation.isNotEmpty() && !matches) {
                        { Text(stringResource(R.string.password_change_mismatch)) }
                    } else {
                        null
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("password-change-confirmation"),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Checkbox(
                        checked = saveProtectedCredential,
                        onCheckedChange = { saveProtectedCredential = it },
                        enabled = protectedCredentialAvailable,
                        modifier = Modifier.testTag("password-change-save"),
                    )
                    Text(stringResource(R.string.password_change_save_with_android))
                }
                if (saveProtectedCredential) {
                    Text(
                        stringResource(R.string.password_change_authentication_required),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSubmit,
                modifier = Modifier.testTag("password-change-confirm"),
                onClick = {
                    val ownedPassword = password.toCharArray()
                    clearTransientSecrets()
                    onSubmit(username, ownedPassword, saveProtectedCredential)
                },
            ) {
                Text(stringResource(R.string.password_change_confirm_action))
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    clearTransientSecrets()
                    onDismiss()
                },
            ) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}

private const val MAX_ACCOUNT_CHARS = 256
private const val MAX_ACCOUNT_BYTES = 256
private val INVALID_ACCOUNT_CHARACTERS = setOf('\'', '"', '\\', '/')
