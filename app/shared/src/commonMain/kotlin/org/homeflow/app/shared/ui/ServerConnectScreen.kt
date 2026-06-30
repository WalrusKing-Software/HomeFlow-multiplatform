package org.homeflow.app.shared.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Server host-entry screen (D-15.4). Collects a bare hostname (e.g. `myhost.ts.net`),
 * normalises it (trim, strip leading scheme, strip trailing slash), and calls [onConnected].
 * Reachable from: (a) the SERVER-branch gate in [AppRoot] when no host is stored, and
 * (b) Settings "Connect to a server" in a Mode-A install.
 *
 * Reachability validation is deliberately absent — a bad host surfaces as the existing
 * OIDC / getMe failure path.
 */
@Composable
fun ServerConnectScreen(onConnected: (String) -> Unit) {
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Connect to your server", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Enter the hostname of your HomeFlow server.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = input,
                onValueChange = {
                    input = it
                    error = null
                },
                label = { Text("Server hostname") },
                placeholder = { Text("myhost.ts.net") },
                singleLine = true,
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    val host = normalise(input)
                    if (host.isEmpty()) {
                        error = "Please enter a hostname."
                    } else {
                        onConnected(host)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Connect")
            }
        }
    }
}

/** Trim whitespace, strip a leading `https://` or `http://` scheme, strip a trailing `/`. */
private fun normalise(raw: String): String =
    raw
        .trim()
        .removePrefix("https://")
        .removePrefix("http://")
        .trimEnd('/')
