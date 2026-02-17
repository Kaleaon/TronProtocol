package com.tronprotocol.app.selfmod

/**
 * Immutable audit record for each lifecycle gate transition.
 */
data class ModificationAuditRecord(
    val modificationId: String,
    val fromStatus: ModificationStatus,
    val toStatus: ModificationStatus,
    val gate: String,
    val outcome: String,
    val details: String,
    val timestamp: Long = System.currentTimeMillis()
)
