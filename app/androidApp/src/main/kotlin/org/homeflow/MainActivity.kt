package org.homeflow

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.CompletableDeferred
import org.homeflow.app.shared.config.AuthConfig
import org.homeflow.app.shared.config.defaultAuthConfig
import org.homeflow.app.shared.platform.AndroidAppContext
import org.homeflow.app.shared.ui.AppRoot

/**
 * Android entry point. A [FragmentActivity] (required by `BiometricPrompt`) that sets
 * `FLAG_SECURE` for the sensitive health data, wires the AppAuth and SAF export
 * result launchers into [AndroidAppContext], and hosts the shared Compose [AppRoot].
 */
class MainActivity : FragmentActivity() {
    private var pendingAuth: CompletableDeferred<Intent?>? = null
    private var pendingExport: CompletableDeferred<Uri?>? = null

    private val authLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            pendingAuth?.complete(result.data)
            pendingAuth = null
        }

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            pendingExport?.complete(result.data?.data)
            pendingExport = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Block screenshots and the recents thumbnail (anti-screenshot, CLAUDE.md).
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )

        AndroidAppContext.initApplication(application)
        AndroidAppContext.setActivity(this)
        AndroidAppContext.authLauncher = { intent ->
            val deferred = CompletableDeferred<Intent?>()
            pendingAuth = deferred
            authLauncher.launch(intent)
            deferred.await()
        }
        AndroidAppContext.exportLauncher = { intent ->
            val deferred = CompletableDeferred<Uri?>()
            pendingExport = deferred
            exportLauncher.launch(intent)
            deferred.await()
        }

        // Debug builds target the dev stack via `adb reverse` (the emulator can't bind a
        // privileged device-side port, so it uses 8443 → host Caddy 443). Keycloak pins the
        // token issuer via KC_HOSTNAME, so the :8443 client port doesn't affect validation,
        // and Caddy's cert is for the hostname `localhost` (port-independent). Set up with:
        //   adb reverse tcp:8443 tcp:443     # app https://localhost:8443 -> host Caddy
        //   adb reverse tcp:8180 tcp:8180    # Keycloak frontend URL (login page) -> host
        // Release uses the platform default (override for a real device or the Tailscale host).
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val config: AuthConfig = if (debuggable) AuthConfig(host = "localhost:8443") else defaultAuthConfig()

        setContent {
            AppRoot(config)
        }
    }

    override fun onDestroy() {
        if (AndroidAppContext.activity === this) AndroidAppContext.setActivity(null)
        super.onDestroy()
    }
}
