package com.tronprotocol.app.drift

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ImmutableCommitLogTest {

    private lateinit var context: Context
    private lateinit var log: ImmutableCommitLog

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        log = ImmutableCommitLog(context)
    }

    @Test
    fun append_increasesSize() {
        assertEquals("Fresh log should have size 0", 0, log.size())
        log.append("First entry", "interaction")
        assertEquals("Size should be 1 after one append", 1, log.size())
        log.append("Second entry", "interaction")
        assertEquals("Size should be 2 after two appends", 2, log.size())
    }

    @Test
    fun append_returnsNonNullEntryId() {
        val hash = log.append("Test content", "interaction")
        assertNotNull("Append should return a non-null hash", hash)
        assertTrue("Append should return a non-empty hash", hash.isNotEmpty())
    }

    @Test
    fun getContent_retrievesAppendedEntry() {
        log.append("My important content", "memory_write")
        val content = log.getContent(0)
        assertEquals("Retrieved content should match appended content", "My important content", content)
    }

    @Test
    fun getContent_returnsNullForUnknownIndex() {
        val content = log.getContent(999)
        assertNull("getContent should return null for unknown index", content)
    }

    @Test
    fun getContent_returnsEntriesInOrder() {
        log.append("First", "interaction")
        log.append("Second", "interaction")
        log.append("Third", "interaction")

        assertEquals("First", log.getContent(0))
        assertEquals("Second", log.getContent(1))
        assertEquals("Third", log.getContent(2))
    }

    @Test
    fun verifyChain_returnsTrueForValidLog() {
        log.append("Entry one", "interaction")
        log.append("Entry two", "memory_write")
        log.append("Entry three", "session_close")

        assertTrue("Chain integrity should be valid for a properly constructed log", log.verifyChain())
    }

    @Test
    fun hashChain_linksEntries() {
        val hash1 = log.append("First entry", "interaction")
        val hash2 = log.append("Second entry", "interaction")

        // Each entry's chain hash should be different since it incorporates the previous hash
        assertNotEquals("Sequential entries should produce different chain hashes", hash1, hash2)

        // The latest hash should match the second entry's hash
        assertEquals("Latest hash should match the last appended entry's hash", hash2, log.getLatestHash())
    }

    @Test
    fun emptyLog_hasSizeZero() {
        assertEquals("Empty log should have size 0", 0, log.size())
        assertTrue("Empty log should have valid chain integrity", log.verifyChain())
    }
}
