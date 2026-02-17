package com.tronprotocol.app.selfmod

/**
 * Represents a code modification proposal
 */
class CodeModification(
    val id: String,
    val componentName: String,
    val description: String,
    val originalCode: String,
    val modifiedCode: String,
    val timestamp: Long,
    var status: ModificationStatus
) {
    var appliedTimestamp: Long = 0L
    var backupId: String? = null
    var rollbackCheckpointId: String? = null

    override fun toString(): String {
        return "CodeModification{" +
                "id='$id'" +
                ", component='$componentName'" +
                ", status=$status" +
                ", description='$description'" +
                "}"
    }
}
