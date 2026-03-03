package com.tronprotocol.app.security

/**
 * Data-classification and lifecycle controls for sensitive artifacts.
 */
object DataGovernancePolicy {

    enum class DataDomain {
        LOGS,
        MEMORY,
        MODEL_ARTIFACT
    }

    enum class Classification {
        PUBLIC,
        INTERNAL,
        CONFIDENTIAL,
        RESTRICTED
    }

    data class RetentionPolicy(
        val domain: DataDomain,
        val classification: Classification,
        val retentionDays: Long,
        val deleteOnUserRequest: Boolean,
        val secureDeleteRequired: Boolean
    )

    private val policies: Map<DataDomain, RetentionPolicy> = mapOf(
        DataDomain.LOGS to RetentionPolicy(
            domain = DataDomain.LOGS,
            classification = Classification.CONFIDENTIAL,
            retentionDays = 90,
            deleteOnUserRequest = true,
            secureDeleteRequired = true
        ),
        DataDomain.MEMORY to RetentionPolicy(
            domain = DataDomain.MEMORY,
            classification = Classification.RESTRICTED,
            retentionDays = 30,
            deleteOnUserRequest = true,
            secureDeleteRequired = true
        ),
        DataDomain.MODEL_ARTIFACT to RetentionPolicy(
            domain = DataDomain.MODEL_ARTIFACT,
            classification = Classification.INTERNAL,
            retentionDays = 365,
            deleteOnUserRequest = false,
            secureDeleteRequired = false
        )
    )

    fun policyFor(domain: DataDomain): RetentionPolicy =
        policies.getValue(domain)

    fun shouldDelete(domain: DataDomain, createdAtMillis: Long, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val retentionMillis = policyFor(domain).retentionDays * MILLIS_PER_DAY
        return nowMillis - createdAtMillis >= retentionMillis
    }

    const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L
}
