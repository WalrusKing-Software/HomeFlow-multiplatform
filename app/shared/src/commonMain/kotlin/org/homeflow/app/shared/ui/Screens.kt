package org.homeflow.app.shared.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/** Shared centered column for the simple Phase 7 auth screens. */
@Composable
private fun CenteredColumn(content: @Composable () -> Unit) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .safeContentPadding()
                .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) { content() }
}

@Composable
fun LoginScreen(
    onLogin: () -> Unit,
    onCancel: (() -> Unit)? = null,
) {
    CenteredColumn {
        Text("HomeFlow", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Sign in with your Keycloak account.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = onLogin) { Text("Log in") }
        // Non-null only in Mode B (server-connected) — lets the user back out of server setup
        // instead of being stuck here if they picked the wrong server.
        if (onCancel != null) {
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

@Composable
fun LoadingScreen(
    message: String,
    onCancel: (() -> Unit)? = null,
) {
    CenteredColumn {
        CircularProgressIndicator()
        Text(message, style = MaterialTheme.typography.bodyMedium)
        // Non-null only in Mode B — lets the user abandon an in-flight login (e.g. a stuck
        // OIDC browser flow) instead of waiting it out.
        if (onCancel != null) {
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

@Composable
fun LockScreen(
    usesPassphrase: Boolean,
    needsEnrollment: Boolean,
    onSubmitPassphrase: (String) -> Unit,
    onBiometric: () -> Unit,
    onLogout: () -> Unit,
) {
    CenteredColumn {
        when {
            // First run (desktop): the user is CREATING their passphrase, not entering an
            // existing one. This screen is deliberately distinct from the unlock screen.
            usesPassphrase && needsEnrollment -> PassphraseSetup(onSubmit = onSubmitPassphrase)
            usesPassphrase -> PassphraseUnlock(onSubmit = onSubmitPassphrase)
            else -> {
                Text("Unlock HomeFlow", style = MaterialTheme.typography.headlineSmall)
                Text("Authenticate to access your data.", style = MaterialTheme.typography.bodyMedium)
                Button(onClick = onBiometric) { Text("Unlock") }
            }
        }
        OutlinedButton(onClick = onLogout) { Text("Log out") }
    }
}

/** First-run passphrase creation: distinct heading, guidance, and a confirmation field. */
@Composable
private fun PassphraseSetup(onSubmit: (String) -> Unit) {
    var passphrase by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val mismatch = confirm.isNotEmpty() && passphrase != confirm
    val valid = passphrase.isNotBlank() && passphrase == confirm

    Text("Create your app passphrase", style = MaterialTheme.typography.headlineSmall)
    Text(
        "Set a passphrase to keep your data safe on this device. You'll enter it each time " +
            "you open HomeFlow.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        "Keep it somewhere safe — it can't be reset, so choose something you'll remember.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = passphrase,
        onValueChange = { passphrase = it },
        label = { Text("New passphrase") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth().widthIn(max = 360.dp),
    )
    OutlinedTextField(
        value = confirm,
        onValueChange = { confirm = it },
        label = { Text("Confirm passphrase") },
        singleLine = true,
        isError = mismatch,
        supportingText = if (mismatch) ({ Text("Passphrases don't match") }) else null,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth().widthIn(max = 360.dp),
    )
    Button(onClick = { onSubmit(passphrase) }, enabled = valid) { Text("Create passphrase") }
}

/** Returning-user unlock: a single passphrase field. */
@Composable
private fun PassphraseUnlock(onSubmit: (String) -> Unit) {
    var passphrase by remember { mutableStateOf("") }

    Text("Unlock HomeFlow", style = MaterialTheme.typography.headlineSmall)
    Text("Enter your app passphrase to continue.", style = MaterialTheme.typography.bodyMedium)
    OutlinedTextField(
        value = passphrase,
        onValueChange = { passphrase = it },
        label = { Text("Passphrase") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth().widthIn(max = 360.dp),
    )
    Button(onClick = { onSubmit(passphrase) }, enabled = passphrase.isNotBlank()) { Text("Unlock") }
}

@Composable
fun ErrorScreen(
    message: String,
    onRetry: () -> Unit,
) {
    CenteredColumn {
        Text("Something went wrong", style = MaterialTheme.typography.headlineSmall)
        Text(message, style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onRetry) { Text("Try again") }
    }
}
