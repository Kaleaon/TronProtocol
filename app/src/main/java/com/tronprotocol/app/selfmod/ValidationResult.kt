package com.tronprotocol.app.selfmod

/**
 * Result of code modification validation
 */
class ValidationResult {
    private var valid: Boolean = false
    private val errors = mutableListOf<String>()
    private val warnings = mutableListOf<String>()

    fun addError(error: String) {
        errors.add(error)
        valid = false
    }

    fun addWarning(warning: String) {
        warnings.add(warning)
    }

    fun setValid(valid: Boolean) {
        this.valid = valid
    }

    fun isValid(): Boolean = valid && errors.isEmpty()

    fun getErrors(): List<String> = ArrayList(errors)

    fun getWarnings(): List<String> = ArrayList(warnings)

    override fun toString(): String {
        return "ValidationResult{" +
                "valid=$valid" +
                ", errors=${errors.size}" +
                ", warnings=${warnings.size}" +
                "}"
    }
}
