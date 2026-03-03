package com.tronprotocol.app.security

import java.net.InetAddress
import java.net.URL
import java.net.UnknownHostException
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * Network Security Validator for preventing SSRF (Server-Side Request Forgery).
 *
 * Validates URLs and IP addresses to ensure they don't point to internal services,
 * loopback addresses, or other restricted network ranges.
 */
object NetworkSecurityValidator {

    private val ALLOWED_PROTOCOLS = setOf("http", "https")
    private val MIN_TLS_PROTOCOLS = setOf("TLSV1.2", "TLSV1.3")

    /**
     * Checks if a URL is safe to connect to.
     *
     * @param url The URL to validate.
     * @return true if the URL is considered safe, false otherwise.
     */
    fun isSafeUrl(url: URL): Boolean {
        // 1. Check protocol
        if (!ALLOWED_PROTOCOLS.contains(url.protocol.lowercase())) {
            return false
        }

        // 2. Check hostname and IP address
        val host = url.host ?: return false

        return try {
            val addresses = InetAddress.getAllByName(host)
            addresses.all { isSafeAddress(it) }
        } catch (e: UnknownHostException) {
            // If we can't resolve the host, we shouldn't connect to it
            false
        } catch (e: SecurityException) {
            false
        }
    }

    /**
     * Checks if an InetAddress is safe (not loopback, private, link-local, or multicast).
     */
    fun isSafeAddress(address: InetAddress): Boolean {
        return when {
            address.isLoopbackAddress -> false
            address.isAnyLocalAddress -> false
            address.isLinkLocalAddress -> false
            address.isSiteLocalAddress -> false
            address.isMulticastAddress -> false
            // Additional check for private IP ranges if not covered by isSiteLocalAddress
            isPrivateIp(address) -> false
            else -> true
        }
    }

    /**
     * Validates that a negotiated transport protocol is not downgraded below TLS 1.2.
     */
    fun isAcceptedTlsProtocol(protocol: String?): Boolean {
        val normalized = protocol?.uppercase() ?: return false
        return normalized in MIN_TLS_PROTOCOLS
    }

    /**
     * Validates certificate pinning via SHA-256 fingerprint matching.
     */
    fun isPinnedCertificate(host: String, certificateDer: ByteArray, pinsByHost: Map<String, Set<String>>): Boolean {
        val pins = pinsByHost[host.lowercase()] ?: return false
        val fingerprint = sha256Hex(certificateDer)
        return pins.contains(fingerprint)
    }

    /**
     * Performs basic parsing and validity checks for received X.509 certificates.
     */
    fun hasWellFormedCertificates(certificatesDer: List<ByteArray>): Boolean {
        if (certificatesDer.isEmpty()) return false

        return try {
            val certificateFactory = CertificateFactory.getInstance("X.509")
            certificatesDer.all { der ->
                val cert = certificateFactory.generateCertificate(der.inputStream()) as? X509Certificate
                    ?: return false
                cert.checkValidity()
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Manual check for private IP ranges (RFC 1918 and others).
     */
    private fun isPrivateIp(address: InetAddress): Boolean {
        val bytes = address.address
        if (bytes.size == 4) { // IPv4
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF

            return when (b0) {
                10 -> true // 10.0.0.0/8
                172 -> b1 in 16..31 // 172.16.0.0/12
                192 -> b1 == 168 // 192.168.0.0/16
                100 -> b1 in 64..127 // 100.64.0.0/10 (CGNAT)
                169 -> b1 == 254 // 169.254.0.0/16 (Link-Local)
                else -> false
            }
        } else if (bytes.size == 16) { // IPv6
            // Most IPv6 special ranges are covered by InetAddress built-in methods,
            // but we add specific ones for complete coverage.

            // fc00::/7 (Unique Local Address)
            val b0 = bytes[0].toInt() and 0xFF
            if ((b0 and 0xFE) == 0xFC) return true

            // Other ranges (link-local, site-local, loopback) are covered
            // by InetAddress methods used in isSafeAddress
            return false
        }
        return false
    }

    private fun sha256Hex(input: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(input)
            .joinToString("") { "%02x".format(it) }
    }
}
