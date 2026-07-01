package org.homeflow.app.shared.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.homeflow.app.shared.data.ProbeResult
import org.homeflow.app.shared.data.probeServer

/** The transient state of the host-entry + reachability step. */
private sealed interface ConnectState {
    data object Idle : ConnectState

    data object Checking : ConnectState

    data class Checked(
        val host: String,
        val result: ProbeResult,
    ) : ConnectState
}

/**
 * Server host-entry screen (D-15.4). Collects a bare hostname (e.g. `myhost.ts.net`),
 * normalises it (trim, strip leading scheme, strip trailing slash), runs an advisory
 * reachability check against `GET /api/v1/version`, and calls [onConnected].
 *
 * Reachable in two places: (a) the SERVER-branch gate in [AppRoot] when no host is stored
 * (there [onBack] is supplied so the user can return to the mode chooser instead of being
 * trapped), and (b) Settings "Connect to a server" in a Mode-A install.
 *
 * Reachability is **advisory only** — an [ProbeResult.Unreachable] host still offers
 * "Connect anyway", because LAN/Tailscale hosts can block the probe while still serving the
 * real OIDC/API traffic.
 */
@Composable
fun ServerConnectScreen(
    onConnected: (String) -> Unit,
    onBack: (() -> Unit)? = null,
    backLabel: String = "Back to setup",
    clientVersion: String = "unknown",
) {
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var state by remember { mutableStateOf<ConnectState>(ConnectState.Idle) }
    val scope = rememberCoroutineScope()

    fun connect() {
        val host = normalise(input)
        if (host.isEmpty()) {
            error = "Please enter a hostname."
            return
        }
        error = null
        state = ConnectState.Checking
        scope.launch {
            val result = probeServer(host, clientVersion)
            when (result) {
                is ProbeResult.Reachable -> onConnected(host)
                else -> state = ConnectState.Checked(host, result)
            }
        }
    }

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
                "Enter the hostname of your running HomeFlow server. Don't have one yet? " +
                    "Go back and choose \"Use this device only\".",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = input,
                onValueChange = {
                    input = it
                    error = null
                    state = ConnectState.Idle
                },
                label = { Text("Server hostname") },
                placeholder = { Text("myhost.ts.net") },
                singleLine = true,
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
                enabled = state !is ConnectState.Checking,
                modifier = Modifier.fillMaxWidth(),
            )

            StatusRow(state)

            Button(
                onClick = { connect() },
                enabled = state !is ConnectState.Checking,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state is ConnectState.Checking) {
                    CircularProgressIndicator(Modifier.padding(end = 8.dp).size(16.dp), strokeWidth = 2.dp)
                }
                Text("Connect")
            }

            // Unreachable is advisory — let the user proceed anyway.
            (state as? ConnectState.Checked)?.let { checked ->
                if (checked.result is ProbeResult.Unreachable) {
                    OutlinedButton(
                        onClick = { onConnected(checked.host) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Connect anyway")
                    }
                }
            }

            if (onBack != null) {
                TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                    Text(backLabel)
                }
            }
        }
    }
}

/** Inline reachability feedback under the input. */
@Composable
private fun StatusRow(state: ConnectState) {
    when (state) {
        is ConnectState.Idle -> Unit
        is ConnectState.Checking ->
            Text(
                "Checking that server…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        is ConnectState.Checked ->
            when (val r = state.result) {
                // Reachable auto-proceeds via onConnected, so it isn't rendered here.
                is ProbeResult.Reachable -> Unit
                is ProbeResult.Incompatible ->
                    Text(
                        "This app is too old for that server. Please update to version " +
                            "${r.minClient} or newer.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                is ProbeResult.Unreachable ->
                    Text(
                        "Couldn't reach that server. Check the hostname and that the server is " +
                            "running — or connect anyway if you're sure.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
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
