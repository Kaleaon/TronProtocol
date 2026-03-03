package com.tronprotocol.app.llm

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.verify
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ModelDownloadManagerTest {

    private lateinit var context: Context
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var downloadManager: ModelDownloadManager

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        sharedPreferences = mockk(relaxed = true)
        editor = mockk(relaxed = true)

        every { context.applicationContext } returns context
        every { context.getSharedPreferences(any(), any()) } returns sharedPreferences
        every { sharedPreferences.edit() } returns editor
        every { editor.putString(any(), any()) } returns editor
        every { editor.remove(any()) } returns editor

        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0

        downloadManager = ModelDownloadManager(context)
    }

    @Test
    fun testSetHuggingFaceToken_doesNotLogToken() {
        val secretToken = "hf_SUPER_SECRET_TOKEN_12345"
        downloadManager.setHuggingFaceToken(secretToken)

        verify { editor.putString("huggingface_token", secretToken) }
        verify(exactly = 0) { Log.d("ModelDownloadManager", match { it.contains(secretToken) || it.contains(secretToken.take(8)) }) }
        verify { Log.d("ModelDownloadManager", "HF token set") }
    }

    @Test
    fun testSetHuggingFaceToken_clearsToken() {
        downloadManager.setHuggingFaceToken("")

        verify { editor.remove("huggingface_token") }
        verify { Log.d("ModelDownloadManager", "HF token cleared") }
    }
    @Test
    fun rollingBackStateIsNotTerminal() {
        val progress = ModelDownloadManager.DownloadProgress(
            modelId = "test",
            state = ModelDownloadManager.DownloadState.ROLLING_BACK,
            downloadedBytes = 500,
            totalBytes = 1000,
            speedBytesPerSec = 0,
            progressFraction = 0.5f
        )
        assertFalse("ROLLING_BACK should not be terminal", progress.isTerminal)
    }

}
