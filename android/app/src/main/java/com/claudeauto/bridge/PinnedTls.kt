package com.claudeauto.bridge

import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * The bridge serves HTTPS with its own self-signed certificate. Instead of trusting a
 * certificate authority, the app trusts exactly one certificate: the one whose SHA-256
 * fingerprint the user confirmed when pairing. Anything else, including a valid CA-signed
 * certificate for an impostor, is refused.
 */
object PinnedTls {

    fun fingerprint(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString(":") { "%02X".format(it) }

    /** "AB12-CD34": the first 8 hex digits, as the bridge prints them. */
    fun short(fingerprint: String): String {
        val hex = fingerprint.replace(":", "").take(8).uppercase()
        return if (hex.length < 8) hex else "${hex.take(4)}-${hex.drop(4)}"
    }

    /** Only the certificate with [fingerprint] is accepted; host names are irrelevant once pinned. */
    fun pin(builder: OkHttpClient.Builder, fingerprint: String): OkHttpClient.Builder {
        val trust = PinnedTrustManager(fingerprint)
        return builder
            .sslSocketFactory(sslContext(trust).socketFactory, trust)
            .hostnameVerifier { _, _ -> true }
    }

    /**
     * Connects once to read the certificate the bridge presents, so the user can compare
     * it with what the bridge printed. Use [systemTrusts] on the chain to tell whether a pin
     * is needed at all (a CA-signed certificate from Tailscale Serve isn't pinned, so it can renew).
     */
    fun probe(builder: OkHttpClient.Builder, onChain: (Array<X509Certificate>) -> Unit): OkHttpClient.Builder {
        val trust = CapturingTrustManager(onChain)
        return builder
            .sslSocketFactory(sslContext(trust).socketFactory, trust)
            .hostnameVerifier { _, _ -> true }
    }

    fun systemTrusts(chain: Array<X509Certificate>): Boolean = runCatching {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as java.security.KeyStore?)
        factory.trustManagers.filterIsInstance<X509TrustManager>().first().checkServerTrusted(chain, "ECDHE_ECDSA")
    }.isSuccess

    private fun sslContext(trust: X509TrustManager): SSLContext =
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }

    private class PinnedTrustManager(private val expected: String) : X509TrustManager {
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            val actual = chain.firstOrNull()?.let(::fingerprint)
            if (!actual.equals(expected, ignoreCase = true)) {
                throw CertificateException("The bridge's certificate changed (expected ${short(expected)}, got ${actual?.let(::short)}). Re-pair if you replaced it.")
            }
        }

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
            throw CertificateException("Client certificates are not used")
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /** Accepts any certificate for a single read-only /health probe and reports it. */
    private class CapturingTrustManager(private val onChain: (Array<X509Certificate>) -> Unit) : X509TrustManager {
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            onChain(chain)
        }

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
            throw CertificateException("Client certificates are not used")
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
