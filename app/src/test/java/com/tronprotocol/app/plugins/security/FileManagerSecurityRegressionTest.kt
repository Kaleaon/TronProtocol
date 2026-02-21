package com.tronprotocol.app.plugins.security

import com.tronprotocol.app.plugins.FileManagerPlugin
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class FileManagerSecurityRegressionTest {

    private lateinit var plugin: FileManagerPlugin

    @Before
    fun setUp() {
        plugin = FileManagerPlugin()
        plugin.initialize(RuntimeEnvironment.getApplication())
    }

    @Test
    fun blocksPathTraversalPayloads() {
        val direct = plugin.execute("read|${SecurityMaliciousPayloadFixtures.DIRECT_TRAVERSAL}")
        val encoded = plugin.execute("read|${SecurityMaliciousPayloadFixtures.ENCODED_TRAVERSAL}")

        assertFalse(direct.isSuccess)
        assertTrue(direct.errorMessage!!.contains("Access denied: path traversal detected"))
        assertFalse(encoded.isSuccess)
        assertTrue(encoded.errorMessage!!.contains("Access denied: path traversal detected"))
    }

    @Test
    fun blocksUnauthorizedWriteToProtectedPaths() {
        val result = plugin.execute("write|/proc/tron-security-test.txt|payload")

        assertFalse(result.isSuccess)
        assertTrue(result.errorMessage!!.contains("Access denied: write to protected path"))
    }
}
