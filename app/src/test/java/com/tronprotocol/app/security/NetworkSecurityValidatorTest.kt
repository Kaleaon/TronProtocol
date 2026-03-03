package com.tronprotocol.app.security

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.URL
import java.security.MessageDigest
import java.util.Base64

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

    @Test
    fun testTlsProtocolRejectsDowngrade() {
        assertTrue(NetworkSecurityValidator.isAcceptedTlsProtocol("TLSv1.2"))
        assertTrue(NetworkSecurityValidator.isAcceptedTlsProtocol("tlsv1.3"))

        assertFalse(NetworkSecurityValidator.isAcceptedTlsProtocol("TLSv1"))
        assertFalse(NetworkSecurityValidator.isAcceptedTlsProtocol("TLSv1.1"))
        assertFalse(NetworkSecurityValidator.isAcceptedTlsProtocol("SSLv3"))
        assertFalse(NetworkSecurityValidator.isAcceptedTlsProtocol(null))
    }

    @Test
    fun testPinnedCertificateValidation() {
        val certBytes = "test-certificate".toByteArray()
        val validPin = MessageDigest.getInstance("SHA-256")
            .digest(certBytes)
            .joinToString("") { "%02x".format(it) }

        assertTrue(
            NetworkSecurityValidator.isPinnedCertificate(
                host = "api.example.com",
                certificateDer = certBytes,
                pinsByHost = mapOf("api.example.com" to setOf(validPin))
            )
        )

        assertFalse(
            NetworkSecurityValidator.isPinnedCertificate(
                host = "api.example.com",
                certificateDer = certBytes,
                pinsByHost = mapOf("api.example.com" to setOf("deadbeef"))
            )
        )

        assertFalse(
            NetworkSecurityValidator.isPinnedCertificate(
                host = "missing.example.com",
                certificateDer = certBytes,
                pinsByHost = mapOf("api.example.com" to setOf(validPin))
            )
        )
    }

    @Test
    fun testMalformedCertificateHandling() {
        val validDer = Base64.getDecoder().decode(
            "MIIDGTCCAgGgAwIBAgIUcm9y+N9DeMcXfVVv+F8gTW3nI68wDQYJKoZIhvcNAQELBQAwHDEaMBgGA1UEAwwRdHJvbnByb3RvY29sLnRlc3QwHhcNMjYwMzAzMDQ1NzA4WhcNMzYwMjI5MDQ1NzA4WjAcMRowGAYDVQQDDBF0cm9ucHJvdG9jb2wudGVzdDCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBAJJyKxk+nUfOlFX1yVTO/MMWHqOusAtUIHUWpnk7Xo4O+/KX33+gR7xkCR3yyzMLfCKD8rpMHtVBEKJYpaBjnyqi5GnhSryy6qpwWAZDWnzuOS6mQ6cCVfHg3ujvgL1LSGYAXFw5nHQ1FZRDbipQqiSbyuvhZ9WuxSTIE/n1XBUWLkdcYZDhWvXBQHcZl7DAPHlVnCXD1OKBziRquHlM+5uFYiFGewJ44BPuOnC03R4dyOmp9Ne19pOVJdkHfRey522VsDnXQ5064kOKebcnBStDOhLiLaMFyYcz1zZzBKhTWX1rBvkcezpUoSptMsB8TNvt0hKb0CKw5hFuIO658QsCAwEAAaNTMFEwHQYDVR0OBBYEFOYd8R8eV6ubHYGtZb8PvdUpwfz9MB8GA1UdIwQYMBaAFOYd8R8eV6ubHYGtZb8PvdUpwfz9MA8GA1UdEwEB/wQFMAMBAf8wDQYJKoZIhvcNAQELBQADggEBABzyR7EAeWBIcQhKY5AvKwmEMVbIxWztiFsGEiTKzczr2KHdP5ElNWBkod7ku9ThT8G1fQRMUNA6RXh3p+ygBU4HYxwO2KDxcNINPRRJhO58GUp0e6gg2FTaEtR9hVzzevo+x7ompnu4/KiJXJ5H/+gvmN0GyTdO2xKIpzRg47KhjStv1Lx0fdDYZSo9jZbudQSghFIw/CQGJEfecnmENjk4OWO3KJm7x72qapjk2rFBL21pQiW0Sptppmm+XYC6AdzzQxjSXedfEfVnRPoPyM6whBc/RgcBbnMEr58eXcubQqqEdLt+71jubZewvGGp9DcYE2WfYYtNnEWFelnEie0="
        )
        val malformedDer = "not-a-certificate".toByteArray()

        assertTrue(NetworkSecurityValidator.hasWellFormedCertificates(listOf(validDer)))
        assertFalse(NetworkSecurityValidator.hasWellFormedCertificates(listOf(malformedDer)))
        assertFalse(NetworkSecurityValidator.hasWellFormedCertificates(emptyList()))
    }

}
