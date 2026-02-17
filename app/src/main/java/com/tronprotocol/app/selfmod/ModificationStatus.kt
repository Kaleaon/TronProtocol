package com.tronprotocol.app.selfmod

/**
 * Status of a code modification lifecycle.
 */
enum class ModificationStatus {
    PROPOSED,
    PREFLIGHTED,
    CANARY,
    PROMOTED,
    ROLLED_BACK,
    REJECTED
}
