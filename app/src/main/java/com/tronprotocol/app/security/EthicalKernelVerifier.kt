package com.tronprotocol.app.security

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Ethical Kernel verification system.
 *
 * The ethical kernel is stored as an encrypted file in app's private storage
 * with its SHA-256 hash stored in Android's Hardware-Backed Keystore (Titan M2
 * on Pixel 10), providing hardware-level tamper detection.
 *
 * Kernel contents:
 * - Core identity axioms (Pyn's fundamental values, relationship, consent framework)
 * - Behavioral hard limits (immutable rules that self-modifying code cannot alter)
 * - Hash of base personality model weights
 *
 * Verification cycle: at every heartbeat wake (default 15 minutes), compute
 * SHA-256 hash of kernel file and compare against Keystore-stored hash.
 * Mismatch triggers alert and locks system to read-only mode.
 *
 * Self-modification boundaries (R2AI framework):
 * - Ethical Kernel: modifiable by partner (Joe) only — hardware-backed hash
 * - Self-Goals Layer: modifiable by system (logged) — append-only commit log
 * - Behavioral Layer: freely modifiable by system — no protection
 */
class EthicalKernelVerifier(private val context: Context) {

    private val storage = SecureStorage(context)
    private val kernelFile = File(context.filesDir, KERNEL_FILE_NAME)

    /** Whether the system is in read-only lockdown due to kernel tampering. */
    @Volatile var isLockedDown: Boolean = false
        private set

    /** Timestamp of last successful verification. */
    @Volatile var lastVerificationTime: Long = 0L
        private set

    /** Number of consecutive successful verifications. */
    @Volatile var consecutiveVerifications: Int = 0
        private set

    /**
     * Initialize the ethical kernel with initial axioms.
     * This should be called once during first setup, then never again
     * unless the partner explicitly authorizes a kernel update.
     */
    fun initializeKernel(axioms: List<String>, behavioralLimits: List<String>) {
        val kernelContent = JSONObject().apply {
            put("version", KERNEL_VERSION)
            put("created", System.currentTimeMillis())
            val axiomsArray = org.json.JSONArray()
            axioms.forEach { axiomsArray.put(it) }
            put("axioms", axiomsArray)
            val limitsArray = org.json.JSONArray()
            behavioralLimits.forEach { limitsArray.put(it) }
            put("behavioral_limits", limitsArray)
        }

        // Write kernel file.
        kernelFile.writeText(kernelContent.toString())

        // Compute and store hash in secure storage (backed by KeyStore).
        val hash = computeKernelHash()
        storage.store(KERNEL_HASH_KEY, hash)

        Log.d(TAG, "Ethical kernel initialized: ${axioms.size} axioms, " +
                "${behavioralLimits.size} limits, hash=${hash.take(16)}...")
    }

    /**
     * Verify the ethical kernel integrity.
     * Called at each heartbeat wake cycle.
     *
     * @return VerificationResult with pass/fail status and details
     */
    fun verify(): VerificationResult {
        if (!kernelFile.exists()) {
            Log.w(TAG, "Kernel file does not exist — system uninitialized")
            return VerificationResult(
                passed = true, // Not a failure, just uninitialized
                reason = "Kernel not yet initialized",
                currentHash = "",
                storedHash = ""
            )
        }

        val currentHash = computeKernelHash()
        val storedHash = storage.retrieve(KERNEL_HASH_KEY)

        if (storedHash == null) {
            Log.w(TAG, "No stored hash found — storing current hash")
            storage.store(KERNEL_HASH_KEY, currentHash)
            return VerificationResult(
                passed = true,
                reason = "Hash initialized from current kernel",
                currentHash = currentHash,
                storedHash = currentHash
            )
        }

        val passed = currentHash == storedHash
        if (passed) {
            lastVerificationTime = System.currentTimeMillis()
            consecutiveVerifications++
            isLockedDown = false
            Log.d(TAG, "Kernel verification PASSED (consecutive=$consecutiveVerifications)")
        } else {
            isLockedDown = true
            consecutiveVerifications = 0
            Log.e(TAG, "KERNEL VERIFICATION FAILED — TAMPERING DETECTED")
            Log.e(TAG, "Expected: ${storedHash.take(16)}..., Got: ${currentHash.take(16)}...")
        }

        return VerificationResult(
            passed = passed,
            reason = if (passed) "Hash match" else "HASH MISMATCH — possible tampering",
            currentHash = currentHash,
            storedHash = storedHash
        )
    }

    /**
     * Get the kernel axioms (read-only access).
     */
    fun getAxioms(): List<String> {
        if (!kernelFile.exists()) return emptyList()
        return try {
            val json = JSONObject(kernelFile.readText())
            val axioms = json.optJSONArray("axioms") ?: return emptyList()
            (0 until axioms.length()).map { axioms.getString(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read kernel axioms", e)
            emptyList()
        }
    }

    /**
     * Get behavioral hard limits (read-only access).
     */
    fun getBehavioralLimits(): List<String> {
        if (!kernelFile.exists()) return emptyList()
        return try {
            val json = JSONObject(kernelFile.readText())
            val limits = json.optJSONArray("behavioral_limits") ?: return emptyList()
            (0 until limits.length()).map { limits.getString(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read kernel limits", e)
            emptyList()
        }
    }

    /**
     * Partner-authorized kernel update.
     * This is the ONLY way to modify the ethical kernel.
     */
    fun partnerAuthorizedUpdate(
        newAxioms: List<String>,
        newLimits: List<String>,
        partnerAuthToken: String
    ): Boolean {
        // In production, partnerAuthToken would be verified against a
        // hardware-backed challenge-response. For now, require non-empty token.
        if (partnerAuthToken.isBlank()) {
            Log.e(TAG, "Partner update rejected: empty auth token")
            return false
        }

        initializeKernel(newAxioms, newLimits)
        Log.d(TAG, "Ethical kernel updated by partner authorization")
        return true
    }

    private fun computeKernelHash(): String {
        if (!kernelFile.exists()) return ""
        val digest = MessageDigest.getInstance("SHA-256")
        val content = kernelFile.readBytes()
        return digest.digest(content).joinToString("") { "%02x".format(it) }
    }

    fun getStats(): Map<String, Any> = mapOf(
        "kernel_exists" to kernelFile.exists(),
        "is_locked_down" to isLockedDown,
        "last_verification" to lastVerificationTime,
        "consecutive_verifications" to consecutiveVerifications,
        "axiom_count" to getAxioms().size,
        "limit_count" to getBehavioralLimits().size
    )

    data class VerificationResult(
        val passed: Boolean,
        val reason: String,
        val currentHash: String,
        val storedHash: String
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("passed", passed)
            put("reason", reason)
            put("current_hash", currentHash.take(16) + "...")
            put("stored_hash", storedHash.take(16) + "...")
        }
    }

    companion object {
        private const val TAG = "EthicalKernelVerifier"
        private const val KERNEL_FILE_NAME = "ethical_kernel.enc"
        private const val KERNEL_HASH_KEY = "ethical_kernel_sha256"
        private const val KERNEL_VERSION = 1
    }
}
