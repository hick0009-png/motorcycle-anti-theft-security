package com.example.motorcycleantitheftsensor.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.motorcycleantitheftsensor.ui.ProtectionAppActions
import com.example.motorcycleantitheftsensor.ui.ProtectionUiState
import com.example.motorcycleantitheftsensor.ui.AuthenticatorSetupDetails
import com.example.motorcycleantitheftsensor.ui.formatProtectionTimestamp
import com.example.motorcycleantitheftsensor.ui.friendlyPermissionName
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    state: ProtectionUiState,
    actions: ProtectionAppActions,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    var replacementToken by rememberSaveable { mutableStateOf("") }
    var smsDestination by rememberSaveable { mutableStateOf("") }
    var smsKey by rememberSaveable { mutableStateOf("") }
    var sensitivityDraft by rememberSaveable { mutableIntStateOf(state.settings.sensitivity) }
    var authenticatorSetup by remember { mutableStateOf<AuthenticatorSetupDetails?>(null) }
    var authenticatorSecretRevealed by remember { mutableStateOf(false) }
    var verificationCode by remember { mutableStateOf("") }
    var authenticatorError by remember { mutableStateOf<String?>(null) }
    var cancelAuthenticatorRequest by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun clearAuthenticatorUi() {
        val cancelRequest = cancelAuthenticatorRequest
        cancelAuthenticatorRequest = null
        if (cancelRequest != null) cancelRequest() else actions.cancelAuthenticatorSetup()
        authenticatorSetup = null
        authenticatorSecretRevealed = false
        verificationCode = ""
        authenticatorError = null
    }

    DisposableEffect(Unit) {
        onDispose(::clearAuthenticatorUi)
    }
    DisposableEffect(authenticatorSetup, view) {
        val window = if (authenticatorSetup == null) null else view.context.findActivity()?.window
        val wasSecure = window != null && window.attributes.flags
            .and(WindowManager.LayoutParams.FLAG_SECURE) != 0
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            if (window != null && !wasSecure) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    LaunchedEffect(state.settings.sensitivity) {
        sensitivityDraft = state.settings.sensitivity
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "settings-header") {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() },
            )
        }

        item(key = "telegram-settings") {
            SettingsCard(title = "Telegram") {
                Text(if (state.settings.tokenConfigured) "Token configured" else "Token not configured")
                Text(
                    when {
                        state.settings.pairedOwnerCount > 0 ->
                            "Paired owners: ${state.settings.pairedOwnerCount}"
                        state.settings.pairingCode != null ->
                            "Pairing code: ${state.settings.pairingCode}"
                        else -> "No paired owners"
                    },
                )
                OutlinedTextField(
                    value = replacementToken,
                    onValueChange = { replacementToken = it },
                    label = {
                        Text(if (state.settings.tokenConfigured) "Replace bot token" else "Bot token")
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        val newToken = replacementToken
                        actions.replaceBotToken(newToken)
                        replacementToken = ""
                    },
                    enabled = replacementToken.isNotBlank() && !state.operationInFlight,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) {
                    Text("Save bot token")
                }
            }
        }

        item(key = "permissions") {
            SettingsCard(title = "Permissions") {
                if (state.settings.missingPermissions.isEmpty()) {
                    Text("All required permissions granted")
                } else {
                    Text("Some protection features need these permissions.")
                    state.settings.missingPermissions.sorted().forEach { permission ->
                        Text("Missing: ${friendlyPermissionName(permission)}")
                    }
                    Button(
                        onClick = actions.requestPermissions,
                        enabled = !state.operationInFlight,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                    ) {
                        Text("Review permissions")
                    }
                }
            }
        }

        item(key = "sensitivity") {
            SettingsCard(title = "Sensitivity") {
                Text("Sensitivity: $sensitivityDraft (1-10)")
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .semantics(mergeDescendants = true) {
                            contentDescription =
                                "Protection sensitivity, $sensitivityDraft out of 10"
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Slider(
                        value = sensitivityDraft.toFloat(),
                        onValueChange = { sensitivityDraft = it.roundToInt().coerceIn(1, 10) },
                        onValueChangeFinished = { actions.changeSensitivity(sensitivityDraft) },
                        valueRange = 1f..10f,
                        steps = 8,
                        enabled = !state.operationInFlight,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        item(key = "sms-settings") {
            SettingsCard(title = "SMS fallback") {
                Text(
                    if (state.settings.smsFallbackConfigured) {
                        "SMS fallback configured"
                    } else {
                        "SMS fallback not configured"
                    },
                )
                OutlinedTextField(
                    value = smsDestination,
                    onValueChange = { smsDestination = it },
                    label = { Text("Replacement SMS destination") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = smsKey,
                    onValueChange = { smsKey = it },
                    label = { Text("Replacement encryption key") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        val destination = smsDestination
                        val key = smsKey
                        actions.configureSmsFallback(destination, key)
                        smsDestination = ""
                        smsKey = ""
                    },
                    enabled = smsDestination.isNotBlank() && smsKey.isNotBlank() &&
                        !state.operationInFlight,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) {
                    Text("Save SMS fallback")
                }
            }
        }

        item(key = "authenticator") {
            SettingsCard(title = "Authenticator") {
                Text(
                    if (state.settings.authenticatorConfigured) {
                        "Authenticator configured"
                    } else {
                        "Authenticator not configured"
                    },
                )
                Button(
                    onClick = {
                        cancelAuthenticatorRequest?.invoke()
                        cancelAuthenticatorRequest = null
                        verificationCode = ""
                        authenticatorError = null
                        cancelAuthenticatorRequest = actions.beginAuthenticatorSetup { setup ->
                            cancelAuthenticatorRequest = null
                            authenticatorSetup = setup
                            authenticatorSecretRevealed = false
                            if (setup == null) {
                                authenticatorError = "Unable to start authenticator setup"
                            }
                        }
                    },
                    enabled = !state.operationInFlight,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) {
                    Text(
                        if (state.settings.authenticatorConfigured) {
                            "Replace authenticator"
                        } else {
                            "Set up authenticator"
                        },
                    )
                }
                authenticatorError?.let { Text(it) }
            }
        }

        item(key = "diagnostics") {
            SettingsCard(title = "Diagnostics") {
                DiagnosticRow(
                    "Protection state",
                    state.protection.state.name.lowercase().replace('_', ' '),
                )
                DiagnosticRow(
                    "Service",
                    if (state.protection.serviceRunning) "Running" else "Stopped",
                )
                DiagnosticRow(
                    "Telegram",
                    if (state.protection.telegramReachable) "Reachable" else "Unreachable",
                )
                DiagnosticRow(
                    "Last transition",
                    formatProtectionTimestamp(state.protection.lastTransitionAtMs),
                )
                DiagnosticRow(
                    "Battery",
                    state.protection.batteryLevelPercent?.let { "$it%" } ?: "Unavailable",
                )
            }
        }
    }

    authenticatorSetup?.let { setup ->
        AlertDialog(
            onDismissRequest = ::clearAuthenticatorUi,
            title = { Text("Authenticator setup") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Enter this secret in your authenticator:")
                    Text(
                        text = if (authenticatorSecretRevealed) setup.secret else "••••••••",
                        modifier = Modifier
                            .testTag(AUTHENTICATOR_SECRET_TAG)
                            .clearAndSetSemantics {
                                contentDescription = if (authenticatorSecretRevealed) {
                                    "Authenticator secret: ${setup.secret}"
                                } else {
                                    "Authenticator secret hidden"
                                }
                            },
                    )
                    TextButton(
                        onClick = {
                            authenticatorSecretRevealed = !authenticatorSecretRevealed
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(if (authenticatorSecretRevealed) "Hide secret" else "Reveal secret")
                    }
                    OutlinedTextField(
                        value = verificationCode,
                        onValueChange = { verificationCode = it },
                        label = { Text("Verification code") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    authenticatorError?.let { Text(it) }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        cancelAuthenticatorRequest?.invoke()
                        cancelAuthenticatorRequest = actions.verifyAuthenticator(verificationCode) { verified ->
                            cancelAuthenticatorRequest = null
                            if (verified) {
                                clearAuthenticatorUi()
                            } else {
                                authenticatorError = "Verification code not accepted"
                            }
                        }
                    },
                    enabled = verificationCode.isNotBlank() && !state.operationInFlight,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("Verify")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = ::clearAuthenticatorUi,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("Cancel")
                }
            },
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private const val AUTHENTICATOR_SECRET_TAG = "authenticator_secret"

@Composable
private fun SettingsCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            content()
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Text(value)
    }
}
