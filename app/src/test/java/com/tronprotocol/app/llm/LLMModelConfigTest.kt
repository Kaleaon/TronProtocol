package com.tronprotocol.app.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LLMModelConfigTest {

    @Test
    fun migrateLegacyConfigMarksUntrustedWhenNoArtifacts() {
        val legacy = LLMModelConfig.Builder("legacy", "/tmp/legacy")
            .markMigratedWithoutChecksums()
            .build()

        val migrated = LLMModelConfig.migrateLegacyConfig(legacy)

        assertEquals(LLMModelConfig.IntegrityStatus.UNTRUSTED_MIGRATED, migrated.integrityStatus)
        assertTrue(migrated.artifacts.isEmpty())
    }
}
