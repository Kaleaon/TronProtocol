package com.tronprotocol.app.security

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.URL

class NetworkSecurityValidatorTest {

    @Test
    fun testIsSafeUrl_PublicHttp() {
        // Note: isSafeUrl performs DNS resolution, so we use a mockable host if possible,
        // but for now we'll assume example.com is resolvable and safe.
        assertTrue(NetworkSecurityValidator.isSafeUrl(URL("http://example.com")))
        assertTrue(NetworkSecurityValidator.isSafeUrl(URL("https://google.com")))
    }

    @Test
    fun testIsSafeUrl_InvalidProtocol() {
        assertFalse(NetworkSecurityValidator.isSafeUrl(URL("ftp://example.com")))
        assertFalse(NetworkSecurityValidator.isSafeUrl(URL("file:///etc/passwd")))
    }

    @Test
    fun testIsSafeUrl_Localhost() {
        assertFalse(NetworkSecurityValidator.isSafeUrl(URL("http://localhost")))
        assertFalse(NetworkSecurityValidator.isSafeUrl(URL("http://127.0.0.1")))
        assertFalse(NetworkSecurityValidator.isSafeUrl(URL("http://[::1]")))
    }

    @Test
    fun testIsSafeUrl_PrivateIps() {
        assertFalse(NetworkSecurityValidator.isSafeUrl(URL("http://192.168.1.1")))
        assertFalse(NetworkSecurityValidator.isSafeUrl(URL("http://10.0.0.1")))
        assertFalse(NetworkSecurityValidator.isSafeUrl(URL("http://172.16.0.1")))
        assertFalse(NetworkSecurityValidator.isSafeUrl(URL("http://100.64.0.1")))
    }

    @Test
    fun testIsSafeAddress_IPv4() {
        // Public
        assertTrue(NetworkSecurityValidator.isSafeAddress(InetAddress.getByName("8.8.8.8")))

        // Loopback
        assertFalse(NetworkSecurityValidator.isSafeAddress(InetAddress.getByName("127.0.0.1")))

        // Private RFC 1918
        assertFalse(NetworkSecurityValidator.isSafeAddress(InetAddress.getByName("10.255.255.255")))
        assertFalse(NetworkSecurityValidator.isSafeAddress(InetAddress.getByName("172.16.0.0")))
        assertFalse(NetworkSecurityValidator.isSafeAddress(InetAddress.getByName("172.31.255.255")))
        assertFalse(NetworkSecurityValidator.isSafeAddress(InetAddress.getByName("192.168.0.0")))

        // CGNAT
        assertFalse(NetworkSecurityValidator.isSafeAddress(InetAddress.getByName("100.64.0.0")))
        assertFalse(NetworkSecurityValidator.isSafeAddress(InetAddress.getByName("100.127.255.255")))

        // Link-local
        assertFalse(NetworkSecurityValidator.isSafeAddress(InetAddress.getByName("169.254.1.1")))
    }

    @Test
    fun testIsSafeAddress_IPv6() {
        // Public
        assertTrue(NetworkSecurityValidator.isSafeAddress(InetAddress.getByName("2001:4860:4860::8888")))

        // Loopback
        assertFalse(NetworkSecurityValidator.isSafeAddress(InetAddress.getByName("::1")))

        // Link-local
        assertFalse(NetworkSecurityValidator.isSafeAddress(InetAddress.getByName("fe80::1")))

        // Unique Local Address (ULA)
        assertFalse(NetworkSecurityValidator.isSafeAddress(InetAddress.getByName("fc00::1")))
        assertFalse(NetworkSecurityValidator.isSafeAddress(InetAddress.getByName("fdff::ffff")))
    }
}
