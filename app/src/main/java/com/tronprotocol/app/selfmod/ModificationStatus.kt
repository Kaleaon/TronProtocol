package com.tronprotocol.app.selfmod

/**
 * Status of a code modification lifecycle.
 */
enum class ModificationStatus {
    PROPOSAL,
    STATIC_CHECKS,
    SANDBOX_RUN,
    CANARY_ROLLOUT,
    FULL_ROLLOUT,
    ROLLED_BACK,
    REJECTED
}
