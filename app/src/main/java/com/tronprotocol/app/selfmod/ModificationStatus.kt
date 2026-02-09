package com.tronprotocol.app.selfmod

/**
 * Status of a code modification
 */
enum class ModificationStatus {
    PROPOSED,      // Modification has been proposed but not applied
    APPLIED,       // Modification has been applied
    ROLLED_BACK,   // Modification was applied but then rolled back
    REJECTED       // Modification was rejected (failed validation or manual rejection)
}
