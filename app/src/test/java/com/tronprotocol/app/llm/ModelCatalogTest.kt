package com.tronprotocol.app.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogTest {

    @Test
    fun catalogHasModels() {
        assertTrue("Catalog should have at least 5 models", ModelCatalog.entries.size >= 5)
    }

    @Test
    fun allEntriesHaveUniqueIds() {
        val ids = ModelCatalog.entries.map { it.id }
        assertEquals("All model IDs should be unique", ids.size, ids.distinct().size)
    }

    @Test
    fun allEntriesHaveValidSizes() {
        for (entry in ModelCatalog.entries) {
            assertTrue("${entry.id} should have positive size", entry.sizeBytes > 0)
            assertTrue("${entry.id} size MB should be positive", entry.sizeMb > 0)
        }
    }

    @Test
    fun allEntriesHaveRamRequirements() {
        for (entry in ModelCatalog.entries) {
            assertTrue("${entry.id} minRam should be positive",
                entry.ramRequirement.minRamMb > 0)
            assertTrue("${entry.id} recommendedRam >= minRam",
                entry.ramRequirement.recommendedRamMb >= entry.ramRequirement.minRamMb)
        }
    }

    @Test
    fun allEntriesHaveDownloadUrls() {
        for (entry in ModelCatalog.entries) {
            assertTrue("${entry.id} should have URL starting with https://",
                entry.downloadUrl.startsWith("https://"))
        }
    }

    @Test
    fun findByIdReturnsCorrectEntry() {
        val entry = ModelCatalog.findById("qwen2.5-1.5b-instruct-q4")
        assertNotNull("Should find Qwen2.5-1.5B", entry)
        assertEquals("Qwen2.5-1.5B-Instruct", entry!!.name)
        assertEquals("Qwen", entry.family)
    }

    @Test
    fun findByIdReturnsNullForUnknown() {
        assertNull(ModelCatalog.findById("nonexistent-model"))
    }

    @Test
    fun byFamilyFiltersCorrectly() {
        val qwenModels = ModelCatalog.byFamily("Qwen")
        assertTrue("Should have Qwen models", qwenModels.isNotEmpty())
        assertTrue("All should be Qwen", qwenModels.all { it.family == "Qwen" })
    }

    @Test
    fun byFamilyIsCaseInsensitive() {
        val lower = ModelCatalog.byFamily("qwen")
        val upper = ModelCatalog.byFamily("QWEN")
        assertEquals("Case insensitive should return same results", lower.size, upper.size)
    }

    @Test
    fun fittingInRamFiltersByMinRam() {
        val smallRam = ModelCatalog.fittingInRam(2048)
        val largeRam = ModelCatalog.fittingInRam(16384)
        assertTrue("More RAM should fit >= same models",
            largeRam.size >= smallRam.size)

        val allFit = ModelCatalog.fittingInRam(2048)
        assertTrue("All returned models should have minRam <= 2048",
            allFit.all { it.ramRequirement.minRamMb <= 2048 })
    }

    @Test
    fun recommendForDevicePrefersLargerModels() {
        val rec8gb = ModelCatalog.recommendForDevice(8192)
        val rec2gb = ModelCatalog.recommendForDevice(2048)
        if (rec8gb != null && rec2gb != null) {
            assertTrue("8GB recommendation should be >= 2GB recommendation size",
                rec8gb.sizeBytes >= rec2gb.sizeBytes)
        }
    }

    @Test
    fun recommendForDeviceReturnsNullForInsufficientRam() {
        val rec = ModelCatalog.recommendForDevice(100) // 100MB - too small
        assertNull("Should return null for insufficient RAM", rec)
    }

    @Test
    fun sortedForDevicePutsCompatibleFirst() {
        val sorted = ModelCatalog.sortedForDevice(3072) // 3GB
        if (sorted.size > 1) {
            val firstIncompat = sorted.indexOfFirst { it.ramRequirement.minRamMb > 3072 }
            if (firstIncompat > 0) {
                // All before firstIncompat should be compatible
                for (i in 0 until firstIncompat) {
                    assertTrue("Compatible models should come first",
                        sorted[i].ramRequirement.minRamMb <= 3072)
                }
            }
        }
    }

    @Test
    fun familiesListIsNotEmpty() {
        assertTrue("Should have at least 3 families", ModelCatalog.families.size >= 3)
    }

    @Test
    fun byFormatReturnsMnnModels() {
        val mnn = ModelCatalog.byFormat("mnn")
        assertTrue("Should have MNN models", mnn.isNotEmpty())
        assertTrue("All MNN entries should be mnn format", mnn.all { it.format == "mnn" })
    }

    @Test
    fun byFormatReturnsGgufModels() {
        val gguf = ModelCatalog.byFormat("gguf")
        assertTrue("Should have GGUF models", gguf.isNotEmpty())
        assertTrue("All GGUF entries should be gguf format", gguf.all { it.format == "gguf" })
    }

    @Test
    fun catalogHasBothFormats() {
        val formats = ModelCatalog.entries.map { it.format }.distinct()
        assertTrue("Catalog should contain mnn format", "mnn" in formats)
        assertTrue("Catalog should contain gguf format", "gguf" in formats)
    }

    @Test
    fun ggufEntriesHaveEmptyModelFiles() {
        val gguf = ModelCatalog.byFormat("gguf")
        for (entry in gguf) {
            assertTrue("GGUF entry ${entry.id} should have empty modelFiles",
                entry.modelFiles.isEmpty())
            assertTrue("GGUF entry ${entry.id} isGguf should be true", entry.isGguf)
        }
    }

    @Test
    fun recommendForDeviceWithFormatPreference() {
        val mnnRec = ModelCatalog.recommendForDevice(8192, "mnn")
        val ggufRec = ModelCatalog.recommendForDevice(8192, "gguf")
        if (mnnRec != null) assertTrue("MNN recommendation should be mnn", mnnRec.isMnn)
        if (ggufRec != null) assertTrue("GGUF recommendation should be gguf", ggufRec.isGguf)
    }

    @Test
    fun localDirectoryNameIsSafe() {
        for (entry in ModelCatalog.entries) {
            val dir = entry.localDirectoryName
            assertTrue("Directory name should not contain spaces: $dir",
                !dir.contains(' '))
            assertTrue("Directory name should not be empty", dir.isNotEmpty())
        }
    }

    @Test
    fun contextWindowsArePositive() {
        for (entry in ModelCatalog.entries) {
            assertTrue("${entry.id} context window should be positive",
                entry.contextWindow > 0)
        }
    }
}
