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
fun LoginScreen(onLogin: () -> Unit) {
    CenteredColumn {
        Text("HomeFlow", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Sign in with your Keycloak account.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = onLogin) { Text("Log in") }
    }
}

@Composable
fun LoadingScreen(message: String) {
    CenteredColumn {
        CircularProgressIndicator()
        Text(message, style = MaterialTheme.typography.bodyMedium)
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
        Text(
            if (needsEnrollment) "Set an app passphrase" else "Unlock HomeFlow",
            style = MaterialTheme.typography.headlineSmall,
        )
        if (usesPassphrase) {
            var passphrase by remember { mutableStateOf("") }
            Text(
                if (needsEnrollment) {
                    "Protects your stored session on this device."
                } else {
                    "Enter your app passphrase to continue."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = passphrase,
                onValueChange = { passphrase = it },
                label = { Text("Passphrase") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().widthIn(max = 360.dp),
            )
            Button(
                onClick = { onSubmitPassphrase(passphrase) },
                enabled = passphrase.isNotBlank(),
            ) { Text(if (needsEnrollment) "Set passphrase" else "Unlock") }
        } else {
            Text(
                "Authenticate to access your data.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onBiometric) { Text("Unlock") }
        }
        OutlinedButton(onClick = onLogout) { Text("Log out") }
    }
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
