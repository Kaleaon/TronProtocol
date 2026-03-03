package com.tronprotocol.app.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import com.tronprotocol.app.llm.backend.BackendType
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class ModelIntegrityVerifierTest {

    private val verifier = ModelIntegrityVerifier()

    @Test
    fun verifyModelPassesWhenAllChecksumsMatch() {
        val modelDir = createModelDirectory()
        val config = trustedConfig(modelDir)

        val result = verifier.verifyModel(config)

        assertTrue(result.success)
        assertEquals(LLMModelConfig.REQUIRED_MODEL_ARTIFACTS.size, result.verifiedArtifacts)
    }

    @Test
    fun verifyModelFailsWhenArtifactIsCorrupted() {
        val modelDir = createModelDirectory()
        val config = trustedConfig(modelDir)
        File(modelDir, "llm.mnn.weight").appendText("tampered")

        val result = verifier.verifyModel(config)

        assertFalse(result.success)
        assertEquals(ModelIntegrityVerifier.FailureReason.CHECKSUM_MISMATCH, result.failureReason)
        assertEquals("llm.mnn.weight", result.failedArtifact)
    }

    @Test
    fun verifyModelFailsWhenArtifactMissing() {
        val modelDir = createModelDirectory()
        val config = trustedConfig(modelDir)
        File(modelDir, "tokenizer.txt").delete()

        val result = verifier.verifyModel(config)

        assertFalse(result.success)
        assertEquals(ModelIntegrityVerifier.FailureReason.MISSING_FILE, result.failureReason)
        assertEquals("tokenizer.txt", result.failedArtifact)
    }

    @Test
    fun verifyModelFailsForMigratedLegacyConfig() {
        val modelDir = createModelDirectory()
        val migrated = LLMModelConfig.Builder("legacy", modelDir.absolutePath)
            .markMigratedWithoutChecksums()
            .build()

        val result = verifier.verifyModel(migrated)

        assertFalse(result.success)
        assertEquals(ModelIntegrityVerifier.FailureReason.UNTRUSTED_MODEL, result.failureReason)
    }


    @Test
    fun verifyModelFailsWhenManifestSignatureMissing() {
        val modelDir = createModelDirectory()
        val builder = LLMModelConfig.Builder("test-model", modelDir.absolutePath)
            .setManifestSignature(
                LLMModelConfig.SignatureMetadata(
                    algorithm = "",
                    signer = "unit-test",
                    signature = "unsigned"
                )
            )
        LLMModelConfig.REQUIRED_MODEL_ARTIFACTS.forEach { artifact ->
            val file = File(modelDir, artifact)
            builder.addArtifact(file.name, sha256(file))
        }

        val result = verifier.verifyModel(builder.build())

        assertFalse(result.success)
        assertEquals(ModelIntegrityVerifier.FailureReason.INVALID_SIGNED_METADATA, result.failureReason)
    }

    private fun trustedConfig(modelDir: File): LLMModelConfig {
        val builder = LLMModelConfig.Builder("test-model", modelDir.absolutePath)
            .setManifestSignature(
                LLMModelConfig.SignatureMetadata(
                    algorithm = "ed25519",
                    signer = "unit-test",
                    signature = "signed-metadata"
                )
            )
            .setCompatibility(
                backend = BackendType.MNN,
                quantization = "Q4",
                minRamMb = 1024L
            )
        LLMModelConfig.REQUIRED_MODEL_ARTIFACTS.forEach { artifact ->
            val file = File(modelDir, artifact)
            builder.addArtifact(file.name, sha256(file))
        }
        return builder.build()
    }

    private fun createModelDirectory(): File {
        val base = File(System.getProperty("java.io.tmpdir"), "model-${UUID.randomUUID()}")
        base.mkdirs()
        LLMModelConfig.REQUIRED_MODEL_ARTIFACTS.forEachIndexed { index, name ->
            File(base, name).writeText("artifact-$name-$index")
        }
        return base
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(file.readBytes())
        return digest.digest().joinToString(separator = "") { "%02x".format(it) }
    }
}
