package com.tronprotocol.app.security

import android.content.Context
import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.verify
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class EthicalKernelVerifierTest {

    private lateinit var context: Context
    private lateinit var verifier: EthicalKernelVerifier

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        val filesDir = File(System.getProperty("java.io.tmpdir"), "test_files")
        filesDir.mkdirs()
        every { context.filesDir } returns filesDir

        mockkStatic(Log::class)
        every { Log.e(any(), any()) } returns 0

        verifier = EthicalKernelVerifier(context)
    }

    @Test
    fun testPartnerAuthorizedUpdate_withEmptyToken_doesNotLogToken() {
        val result = verifier.partnerAuthorizedUpdate(emptyList(), emptyList(), "")

        assert(!result)
        verify { Log.e("EthicalKernelVerifier", "Partner update rejected: unauthorized") }
        verify(exactly = 0) { Log.e("EthicalKernelVerifier", match { it.contains("token", ignoreCase = true) }) }
    }
}
