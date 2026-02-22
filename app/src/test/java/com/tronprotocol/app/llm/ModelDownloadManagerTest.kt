package com.tronprotocol.app.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelDownloadManagerTest {

    @Test
    fun downloadStateIdleIsNotTerminal() {
        val progress = ModelDownloadManager.DownloadProgress(
            modelId = "test",
            state = ModelDownloadManager.DownloadState.IDLE,
            downloadedBytes = 0,
            totalBytes = 1000,
            speedBytesPerSec = 0,
            progressFraction = 0f
        )
        assertFalse("IDLE should not be terminal", progress.isTerminal)
    }

    @Test
    fun downloadStateCompletedIsTerminal() {
        val progress = ModelDownloadManager.DownloadProgress(
            modelId = "test",
            state = ModelDownloadManager.DownloadState.COMPLETED,
            downloadedBytes = 1000,
            totalBytes = 1000,
            speedBytesPerSec = 0,
            progressFraction = 1.0f
        )
        assertTrue("COMPLETED should be terminal", progress.isTerminal)
    }

    @Test
    fun downloadStateErrorIsTerminal() {
        val progress = ModelDownloadManager.DownloadProgress(
            modelId = "test",
            state = ModelDownloadManager.DownloadState.ERROR,
            downloadedBytes = 500,
            totalBytes = 1000,
            speedBytesPerSec = 0,
            progressFraction = 0.5f,
            errorMessage = "Network error"
        )
        assertTrue("ERROR should be terminal", progress.isTerminal)
    }

    @Test
    fun downloadStateCancelledIsTerminal() {
        val progress = ModelDownloadManager.DownloadProgress(
            modelId = "test",
            state = ModelDownloadManager.DownloadState.CANCELLED,
            downloadedBytes = 0,
            totalBytes = 1000,
            speedBytesPerSec = 0,
            progressFraction = 0f
        )
        assertTrue("CANCELLED should be terminal", progress.isTerminal)
    }

    @Test
    fun downloadProgressPercentCalculation() {
        val progress = ModelDownloadManager.DownloadProgress(
            modelId = "test",
            state = ModelDownloadManager.DownloadState.DOWNLOADING,
            downloadedBytes = 500,
            totalBytes = 1000,
            speedBytesPerSec = 100,
            progressFraction = 0.5f
        )
        assertEquals("50% progress", 50, progress.progressPercent)
    }

    @Test
    fun downloadProgressPercentZero() {
        val progress = ModelDownloadManager.DownloadProgress(
            modelId = "test",
            state = ModelDownloadManager.DownloadState.QUEUED,
            downloadedBytes = 0,
            totalBytes = 1000,
            speedBytesPerSec = 0,
            progressFraction = 0f
        )
        assertEquals("0% progress", 0, progress.progressPercent)
    }

    @Test
    fun downloadProgressPercentFull() {
        val progress = ModelDownloadManager.DownloadProgress(
            modelId = "test",
            state = ModelDownloadManager.DownloadState.COMPLETED,
            downloadedBytes = 1000,
            totalBytes = 1000,
            speedBytesPerSec = 0,
            progressFraction = 1.0f
        )
        assertEquals("100% progress", 100, progress.progressPercent)
    }

    @Test
    fun downloadingStateIsNotTerminal() {
        val progress = ModelDownloadManager.DownloadProgress(
            modelId = "test",
            state = ModelDownloadManager.DownloadState.DOWNLOADING,
            downloadedBytes = 500,
            totalBytes = 1000,
            speedBytesPerSec = 100,
            progressFraction = 0.5f
        )
        assertFalse("DOWNLOADING should not be terminal", progress.isTerminal)
    }

    @Test
    fun extractingStateIsNotTerminal() {
        val progress = ModelDownloadManager.DownloadProgress(
            modelId = "test",
            state = ModelDownloadManager.DownloadState.EXTRACTING,
            downloadedBytes = 1000,
            totalBytes = 1000,
            speedBytesPerSec = 0,
            progressFraction = 0.95f
        )
        assertFalse("EXTRACTING should not be terminal", progress.isTerminal)
    }

    @Test
    fun pausedStateIsNotTerminal() {
        val progress = ModelDownloadManager.DownloadProgress(
            modelId = "test",
            state = ModelDownloadManager.DownloadState.PAUSED,
            downloadedBytes = 300,
            totalBytes = 1000,
            speedBytesPerSec = 0,
            progressFraction = 0.3f
        )
        assertFalse("PAUSED should not be terminal", progress.isTerminal)
    }
}
