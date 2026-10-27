package com.claudeauto.bridge

import android.os.Build
import com.claudeauto.PairingLink
import com.claudeauto.Settings
import com.claudeauto.backend.BackendException

/**
 * Pairs this phone with a bridge: read the bridge's certificate, make sure it's the right one,
 * pin it, then trade the one-time code for a device token that only this phone holds.
 *
 * With a scanned QR code the certificate is checked against the fingerprint in the code. When
 * the code was typed in, [confirm] shows the short certificate code for the user to compare.
 */
class Pairer(private val settings: Settings) {

    /** What was paired, for the summary on the last setup screen. */
    data class Paired(val mode: String, val pinnedCertificate: String?)

    suspend fun pair(link: PairingLink, mode: String = modeFor(link)): Paired =
        pair(link.url, link.code, mode, link.fingerprint) { true }

    suspend fun pair(url: String, code: String, mode: String, expected: String?, confirm: suspend (String) -> Boolean): Paired {
        if (code.isBlank()) throw BackendException("Enter the pairing code the bridge shows.")
        val probe = try {
            BridgeClient.probe(url)
        } catch (e: BackendException) {
            throw e
        } catch (e: Exception) {
            throw BackendException(unreachable(url, e))
        }
        val pin = if (probe.systemTrusted) null else probe.fingerprint
        if (expected != null) {
            if (!probe.fingerprint.equals(expected, ignoreCase = true)) {
                throw BackendException("That bridge's certificate doesn't match the QR code, so nothing was sent. Check you're on the right network.")
            }
        } else if (pin != null && !confirm(PinnedTls.short(pin))) {
            throw BackendException("Pairing cancelled. The certificate didn't match, so nothing was sent.")
        }
        val token = BridgeClient.pair(url, pin, code.trim(), Build.MODEL ?: "Phone")
        // The mode decides which pairing slot the address and token go in, so set it first.
        settings.mode = mode
        settings.bridgeUrl = url
        settings.certFingerprint = pin.orEmpty()
        settings.token = token
        return Paired(mode, pin?.let(PinnedTls::short))
    }

    private fun unreachable(url: String, e: Exception): String {
        val onPhone = url.contains("127.0.0.1") || url.contains("localhost")
        return if (onPhone) {
            "The bridge isn't running on this phone. In Termux, run claude-auto-bridge pair."
        } else {
            "Can't reach the bridge at $url (${e.message ?: e.javaClass.simpleName}). Is the computer on and on the same network or Tailscale?"
        }
    }

    companion object {
        fun modeFor(link: PairingLink) = if (link.isOnThisPhone) Settings.MODE_PHONE else Settings.MODE_BRIDGE
    }
}
