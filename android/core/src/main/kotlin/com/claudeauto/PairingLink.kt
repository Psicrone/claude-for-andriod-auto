package com.claudeauto

import java.net.URI
import java.net.URLDecoder

/**
 * What the bridge's pairing QR code holds: claudeauto://pair?url=…&code=…&fp=…
 *
 * [fingerprint] is the bridge certificate's full SHA-256 in the "AB:CD:…" form the app pins.
 * Because the QR code came off the bridge's own screen, the phone can pin it without asking the
 * user to compare codes. It's null when the bridge sits behind a proxy with a public certificate.
 */
data class PairingLink(val url: String, val code: String, val fingerprint: String?) {

    /** The bridge runs on this phone (in Termux), not on a computer. */
    val isOnThisPhone: Boolean
        get() = runCatching { URI(url).host }.getOrNull()?.let { it == "127.0.0.1" || it == "localhost" || it == "[::1]" } == true

    companion object {
        private val HEX = Regex("^[0-9A-Fa-f]{64}$")

        /** Parses a scanned QR code or an opened link, or returns null if it isn't a Claude Auto pairing link. */
        fun parse(text: String): PairingLink? {
            val uri = runCatching { URI(text.trim()) }.getOrNull() ?: return null
            if (!uri.scheme.equals("claudeauto", ignoreCase = true)) return null
            // claudeauto://pair?… has "pair" as its host; claudeauto:pair?… has it as the path.
            val target = uri.host ?: uri.schemeSpecificPart.substringBefore('?').trim('/')
            if (target != "pair") return null
            val params = (uri.rawQuery ?: uri.schemeSpecificPart.substringAfter('?', "")).split('&')
                .mapNotNull { part ->
                    val eq = part.indexOf('=')
                    if (eq <= 0) null else part.substring(0, eq) to URLDecoder.decode(part.substring(eq + 1), "UTF-8")
                }
                .toMap()
            val url = params["url"]?.trim()?.trimEnd('/') ?: return null
            val code = params["code"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (!url.startsWith("https://", ignoreCase = true)) return null
            val fp = params["fp"]?.let { if (HEX.matches(it)) it.uppercase().chunked(2).joinToString(":") else return null }
            return PairingLink(url, code, fp)
        }
    }
}
