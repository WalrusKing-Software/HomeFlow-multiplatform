package org.homeflow.app.shared.data

import io.ktor.client.engine.cio.CIOEngineConfig
import org.homeflow.app.shared.config.DesktopServerConfigStore
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * TLS trust for reaching a self-hosted stack fronted by Caddy's **internal CA** (a LAN
 * `homeflow.lan`, or `https://localhost` under `make dev`), whose root the JVM truststore
 * does not know — otherwise the probe/token exchange fails with "unable to find valid
 * certification path to requested target".
 *
 * The extra CA is resolved, in order, from:
 * 1. `HOMEFLOW_DEV_CA_CERT` (env) — dev/scripted use,
 * 2. `-Dhomeflow.dev.ca` (system property) — dev/scripted use,
 * 3. the CA the user selected in-app (persisted by [DesktopServerConfigStore]) — the
 *    supported path for end users on a private-CA LAN server.
 *
 * With none set, the system truststore is used unchanged (production/public-cert behaviour).
 * The resolved CA is added *alongside* the system roots, so publicly-trusted servers keep
 * working. [invalidate] forces a rebuild after the user changes their selection.
 */
object DesktopTls {
    private var loaded = false
    private var cachedPath: String? = null
    private var cachedTrust: X509TrustManager? = null

    /** Applies the combined (system + selected CA) trust manager to a CIO engine, if configured. */
    fun applyTo(config: CIOEngineConfig) {
        val trust = currentTrustManager() ?: return
        config.https { trustManager = trust }
    }

    /**
     * Forget the cached trust manager so the next client rebuilds from the current CA source.
     * Call after the user selects or clears a certificate — the persisted path may be unchanged
     * (same target file) while its contents differ, so path comparison alone is insufficient.
     */
    @Synchronized
    fun invalidate() {
        loaded = false
        cachedPath = null
        cachedTrust = null
    }

    @Synchronized
    private fun currentTrustManager(): X509TrustManager? {
        val path = caPath()
        if (!loaded || path != cachedPath) {
            cachedPath = path
            cachedTrust = path?.let(::buildTrustManager)
            loaded = true
        }
        return cachedTrust
    }

    private fun caPath(): String? =
        (
            System.getenv("HOMEFLOW_DEV_CA_CERT")
                ?: System.getProperty("homeflow.dev.ca")
                ?: DesktopServerConfigStore().loadCaCertPath()
        )
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun buildTrustManager(path: String): X509TrustManager? {
        val file = File(path).takeIf { it.isFile } ?: return null
        val certificate =
            file.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }

        val devKeyStore =
            KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setCertificateEntry("homeflow-server-ca", certificate)
            }

        val managers =
            (defaultTrustManagers() + trustManagersFor(devKeyStore))
                .filterIsInstance<X509TrustManager>()
        return CombinedX509TrustManager(managers)
    }

    private fun defaultTrustManagers() = trustManagersFor(null)

    private fun trustManagersFor(keyStore: KeyStore?) =
        TrustManagerFactory
            .getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(keyStore) }
            .trustManagers
            .toList()
}

/** Trusts a server if **any** delegate trusts it (system CAs OR the extra selected CA). */
private class CombinedX509TrustManager(
    private val delegates: List<X509TrustManager>,
) : X509TrustManager {
    override fun checkServerTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
    ) {
        var last: CertificateException? = null
        for (delegate in delegates) {
            try {
                delegate.checkServerTrusted(chain, authType)
                return
            } catch (e: CertificateException) {
                last = e
            }
        }
        throw last ?: CertificateException("No trust managers configured")
    }

    override fun checkClientTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
    ) = delegates.first().checkClientTrusted(chain, authType)

    override fun getAcceptedIssuers(): Array<X509Certificate> =
        delegates.flatMap { it.acceptedIssuers.toList() }.toTypedArray()
}
