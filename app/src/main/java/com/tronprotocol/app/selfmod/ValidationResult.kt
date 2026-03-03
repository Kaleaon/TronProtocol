package com.tronprotocol.app.selfmod

/**
 * Result of staged code modification validation and gating.
 */
class ValidationResult {

    enum class Stage {
        PROPOSAL,
        STATIC_CHECKS,
        SANDBOX_RUN,
        CANARY_ROLLOUT,
        FULL_ROLLOUT,
        ROLLED_BACK
    }

    data class GateResult(
        val gateName: String,
        val passed: Boolean,
        val details: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    private var valid: Boolean = false
    private var stage: Stage = Stage.PROPOSAL
    private val errors = mutableListOf<String>()
    private val warnings = mutableListOf<String>()
    private val gates = mutableListOf<GateResult>()

    fun addError(error: String) { errors.add(error); valid = false }
    fun addWarning(warning: String) { warnings.add(warning) }
    fun setValid(valid: Boolean) { this.valid = valid }
    fun setStage(stage: Stage) { this.stage = stage }

    fun addGateResult(gateName: String, passed: Boolean, details: String) {
        gates.add(GateResult(gateName = gateName, passed = passed, details = details))
        if (!passed) valid = false
    }

    fun isValid(): Boolean = valid && errors.isEmpty()
    fun getStage(): Stage = stage
    fun getErrors(): List<String> = ArrayList(errors)
    fun getWarnings(): List<String> = ArrayList(warnings)
    fun getGateResults(): List<GateResult> = ArrayList(gates)

    override fun toString(): String {
        return "ValidationResult{" +
                "valid=$valid" +
                ", stage=$stage" +
                ", gates=${gates.size}" +
                ", errors=${errors.size}" +
                ", warnings=${warnings.size}" +
                "}"
    }
}
