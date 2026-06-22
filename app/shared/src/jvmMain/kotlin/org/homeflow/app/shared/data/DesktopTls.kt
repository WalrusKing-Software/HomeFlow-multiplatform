package org.homeflow.app.shared.data

import io.ktor.client.engine.cio.CIOEngineConfig
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Dev-only TLS escape hatch for reaching a stack fronted by Caddy's **internal CA**
 * (e.g. `https://localhost` under `make dev`), whose root the JVM truststore does not
 * know — causing "unable to find valid certification path to requested target" on the
 * token exchange.
 *
 * **Secure by default:** the extra CA is trusted *only* when `HOMEFLOW_DEV_CA_CERT`
 * (env) or `-Dhomeflow.dev.ca` (system property) points to a PEM file. With neither
 * set, the system truststore is used unchanged (production behaviour). Export Caddy's
 * root with:
 *
 * ```
 * docker compose cp caddy:/data/caddy/pki/authorities/local/root.crt ./caddy-root.crt
 * export HOMEFLOW_DEV_CA_CERT="$PWD/caddy-root.crt"
 * ```
 */
object DesktopTls {
    private val extraTrustManager: X509TrustManager? by lazy { buildTrustManager() }

    /** Applies the combined (system + dev CA) trust manager to a CIO engine, if configured. */
    fun applyTo(config: CIOEngineConfig) {
        val trust = extraTrustManager ?: return
        config.https { trustManager = trust }
    }

    private fun caPath(): String? =
        (System.getenv("HOMEFLOW_DEV_CA_CERT") ?: System.getProperty("homeflow.dev.ca"))
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun buildTrustManager(): X509TrustManager? {
        val file = caPath()?.let(::File)?.takeIf { it.isFile } ?: return null
        val certificate =
            file.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }

        val devKeyStore =
            KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setCertificateEntry("homeflow-dev-ca", certificate)
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

/** Trusts a server if **any** delegate trusts it (system CAs OR the extra dev CA). */
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
