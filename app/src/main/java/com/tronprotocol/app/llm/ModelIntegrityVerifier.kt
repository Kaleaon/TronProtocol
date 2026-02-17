package com.tronprotocol.app.llm

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

class ModelIntegrityVerifier {

    enum class FailureReason {
        UNTRUSTED_MODEL,
        MISSING_METADATA,
        MISSING_FILE,
        CHECKSUM_MISMATCH,
        IO_ERROR
    }

    data class VerificationResult(
        val success: Boolean,
        val failureReason: FailureReason? = null,
        val message: String,
        val verifiedArtifacts: Int = 0,
        val failedArtifact: String? = null
    )

    fun verifyModel(config: LLMModelConfig): VerificationResult {
        if (config.integrityStatus == LLMModelConfig.IntegrityStatus.UNTRUSTED_MIGRATED) {
            return VerificationResult(
                success = false,
                failureReason = FailureReason.UNTRUSTED_MODEL,
                message = "Model is migrated legacy config and remains untrusted until checksum metadata is provided"
            )
        }

        if (config.artifacts.isEmpty()) {
            return VerificationResult(
                success = false,
                failureReason = FailureReason.MISSING_METADATA,
                message = "No model artifact checksum metadata provided"
            )
        }

        var verified = 0
        for (artifact in config.artifacts) {
            val file = File(config.modelPath, artifact.fileName)
            if (!file.exists() || !file.isFile) {
                return VerificationResult(
                    success = false,
                    failureReason = FailureReason.MISSING_FILE,
                    message = "Missing model artifact: ${artifact.fileName}",
                    verifiedArtifacts = verified,
                    failedArtifact = artifact.fileName
                )
            }

            val actual = try {
                sha256(file)
            } catch (e: Exception) {
                return VerificationResult(
                    success = false,
                    failureReason = FailureReason.IO_ERROR,
                    message = "Failed hashing ${artifact.fileName}: ${e.message}",
                    verifiedArtifacts = verified,
                    failedArtifact = artifact.fileName
                )
            }

            if (!actual.equals(artifact.sha256, ignoreCase = true)) {
                return VerificationResult(
                    success = false,
                    failureReason = FailureReason.CHECKSUM_MISMATCH,
                    message = "Checksum mismatch for ${artifact.fileName}",
                    verifiedArtifacts = verified,
                    failedArtifact = artifact.fileName
                )
            }

            verified++
        }

        return VerificationResult(
            success = true,
            message = "Verified ${config.artifacts.size} model artifacts",
            verifiedArtifacts = verified
        )
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString(separator = "") { "%02x".format(it) }
    }
}
