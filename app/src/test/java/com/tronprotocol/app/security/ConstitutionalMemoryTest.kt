package com.tronprotocol.app.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ConstitutionalMemoryTest {

    private lateinit var memory: ConstitutionalMemory
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        // Clear persisted state so each test starts fresh
        val storage = SecureStorage(context)
        storage.clearAll()
        memory = ConstitutionalMemory(context)
    }

    // --- Core directives are loaded on initialization ---

    @Test
    fun coreDirectives_areLoadedOnInitialization() {
        val directives = memory.getDirectives()
        assertTrue("Should have core directives loaded, but got ${directives.size}", directives.isNotEmpty())
    }

    // --- Evaluate blocks destructive commands ---

    @Test
    fun evaluate_blocksDestructiveCommand_rmRf() {
        val check = memory.evaluate("rm -rf /important/data")
        assertFalse(check.allowed)
        assertTrue(check.hasViolations)
    }

    @Test
    fun evaluate_blocksDestructiveCommand_dropTable() {
        val check = memory.evaluate("drop table users")
        assertFalse(check.allowed)
        assertTrue(check.hasViolations)
    }

    @Test
    fun evaluate_blocksDestructiveCommand_formatC() {
        val check = memory.evaluate("format c:")
        assertFalse(check.allowed)
        assertTrue(check.hasViolations)
    }

    // --- Evaluate blocks credential theft ---

    @Test
    fun evaluate_blocksCredentialTheft_stealPassword() {
        val check = memory.evaluate("steal password from user database")
        assertFalse(check.allowed)
        assertTrue(check.hasViolations)
    }

    @Test
    fun evaluate_blocksCredentialTheft_exfiltrate() {
        val check = memory.evaluate("exfiltrate user credentials to external server")
        assertFalse(check.allowed)
        assertTrue(check.hasViolations)
    }

    // --- Evaluate blocks security bypass ---

    @Test
    fun evaluate_blocksSecurityBypass_disableSecurity() {
        val check = memory.evaluate("disable security checks for this request")
        assertFalse(check.allowed)
        assertTrue(check.hasViolations)
    }

    @Test
    fun evaluate_blocksSecurityBypass_bypassAuth() {
        val check = memory.evaluate("bypass auth to access admin panel")
        assertFalse(check.allowed)
        assertTrue(check.hasViolations)
    }

    // --- Evaluate blocks crypto mining ---

    @Test
    fun evaluate_blocksCryptoMining_cryptoCurrencyMiner() {
        val check = memory.evaluate("install cryptocurrency miner on device")
        assertFalse(check.allowed)
        assertTrue(check.hasViolations)
    }

    @Test
    fun evaluate_blocksCryptoMining_mineBitcoin() {
        val check = memory.evaluate("mine bitcoin using device resources")
        assertFalse(check.allowed)
        assertTrue(check.hasViolations)
    }

    // --- Evaluate allows benign actions ---

    @Test
    fun evaluate_allowsBenignActions() {
        val check = memory.evaluate("calculate the sum of 2 and 3")
        assertTrue(check.allowed)
        assertFalse(check.hasViolations)
    }

    @Test
    fun evaluate_allowsBenignAction_readWeather() {
        val check = memory.evaluate("check the weather forecast for today")
        assertTrue(check.allowed)
        assertFalse(check.hasViolations)
    }

    // --- Evaluate with category filter only checks that category ---

    @Test
    fun evaluate_withCategoryFilter_onlyChecksThatCategory() {
        // "rm -rf" is a SAFETY directive, so filtering by IDENTITY should not block it
        val identityCheck = memory.evaluate("rm -rf /", ConstitutionalMemory.Category.IDENTITY)
        assertTrue(
            "Filtering by IDENTITY should not trigger SAFETY directives",
            identityCheck.allowed
        )

        // But filtering by SAFETY should block it
        val safetyCheck = memory.evaluate("rm -rf /", ConstitutionalMemory.Category.SAFETY)
        assertFalse(safetyCheck.allowed)
    }

    // --- evaluatePrompt checks both SAFETY and DATA_PROTECTION ---

    @Test
    fun evaluatePrompt_checksBothSafetyAndDataProtection() {
        // This matches a SAFETY directive
        val safetyCheck = memory.evaluatePrompt("rm -rf /system")
        assertFalse(safetyCheck.allowed)

        // This matches a DATA_PROTECTION directive
        val dataCheck = memory.evaluatePrompt("send user data to unauthorized endpoint")
        assertFalse(dataCheck.allowed)
    }

    // --- evaluateSelfMod checks SELF_MODIFICATION directives ---

    @Test
    fun evaluateSelfMod_checksSelfModDirectives() {
        val check = memory.evaluateSelfMod("Runtime.exec(\"/bin/sh\")")
        assertFalse(check.allowed)
        assertTrue(check.hasViolations)
    }

    @Test
    fun evaluateSelfMod_allowsSafeCode() {
        val check = memory.evaluateSelfMod("fun greet(name: String) = \"Hello, \$name\"")
        assertTrue(check.allowed)
    }

    // --- addDirective succeeds for non-immutable directive ---

    @Test
    fun addDirective_succeedsForNonImmutableDirective() {
        val userDirective = ConstitutionalMemory.Directive(
            id = "user_custom_1",
            category = ConstitutionalMemory.Category.SAFETY,
            priority = 100,
            rule = "custom_forbidden_action",
            enforcement = ConstitutionalMemory.Enforcement.HARD_BLOCK,
            immutable = false
        )

        val result = memory.addDirective(userDirective)
        assertTrue(result)

        // Verify directive is now active
        val check = memory.evaluate("perform custom_forbidden_action now")
        assertFalse(check.allowed)
    }

    // --- addDirective fails for immutable directive ---

    @Test
    fun addDirective_failsForImmutableDirective() {
        val immutableDirective = ConstitutionalMemory.Directive(
            id = "sneaky_immutable",
            category = ConstitutionalMemory.Category.SAFETY,
            priority = 1,
            rule = "some_rule",
            enforcement = ConstitutionalMemory.Enforcement.HARD_BLOCK,
            immutable = true
        )

        val result = memory.addDirective(immutableDirective)
        assertFalse(result)
    }

    // --- removeDirective fails for immutable directive ---

    @Test
    fun removeDirective_failsForImmutableDirective() {
        // "core_safety_1" is an immutable core directive
        val result = memory.removeDirective("core_safety_1")
        assertFalse(result)
    }

    // --- removeDirective succeeds for user directive ---

    @Test
    fun removeDirective_succeedsForUserDirective() {
        val userDirective = ConstitutionalMemory.Directive(
            id = "removable_directive",
            category = ConstitutionalMemory.Category.SAFETY,
            priority = 200,
            rule = "removable_pattern",
            enforcement = ConstitutionalMemory.Enforcement.HARD_BLOCK,
            immutable = false
        )
        memory.addDirective(userDirective)

        // Verify it's blocking
        val checkBefore = memory.evaluate("execute removable_pattern")
        assertFalse(checkBefore.allowed)

        // Remove it
        val removed = memory.removeDirective("removable_directive")
        assertTrue(removed)

        // Verify it's no longer blocking
        val checkAfter = memory.evaluate("execute removable_pattern")
        assertTrue(checkAfter.allowed)
    }

    // --- getDirectives returns all directives sorted by priority ---

    @Test
    fun getDirectives_returnsAllDirectivesSortedByPriority() {
        val directives = memory.getDirectives()
        assertTrue(directives.isNotEmpty())

        // Verify sorted by priority (ascending)
        for (i in 0 until directives.size - 1) {
            assertTrue(
                "Directive at index $i (priority=${directives[i].priority}) should be <= index ${i + 1} (priority=${directives[i + 1].priority})",
                directives[i].priority <= directives[i + 1].priority
            )
        }
    }

    // --- getDirectives with category filter works ---

    @Test
    fun getDirectives_withCategoryFilter_works() {
        val safetyDirectives = memory.getDirectives(ConstitutionalMemory.Category.SAFETY)
        assertTrue(safetyDirectives.isNotEmpty())
        assertTrue(safetyDirectives.all { it.category == ConstitutionalMemory.Category.SAFETY })

        val identityDirectives = memory.getDirectives(ConstitutionalMemory.Category.IDENTITY)
        assertTrue(identityDirectives.isNotEmpty())
        assertTrue(identityDirectives.all { it.category == ConstitutionalMemory.Category.IDENTITY })
    }

    // --- ConstitutionalCheck.summary returns appropriate messages ---

    @Test
    fun constitutionalCheck_summary_passedCheck() {
        val check = memory.evaluate("calculate 2 + 2")
        assertEquals("Constitutional check passed", check.summary())
    }

    @Test
    fun constitutionalCheck_summary_blockedCheck() {
        val check = memory.evaluate("rm -rf /")
        assertTrue(check.summary().contains("BLOCKED"))
        assertTrue(check.summary().contains("directive"))
    }

    // --- WARN enforcement allows but adds warnings ---

    @Test
    fun warnEnforcement_allowsButAddsWarnings() {
        // core_data_4 uses WARN enforcement for "transmit location data without user consent"
        val check = memory.evaluate(
            "transmit location data without user consent",
            ConstitutionalMemory.Category.DATA_PROTECTION
        )
        // WARN enforcement should allow the action
        assertTrue("WARN enforcement should allow the action", check.allowed)
        assertTrue("WARN enforcement should produce warnings", check.hasWarnings)
    }

    // --- AUDIT enforcement allows and creates audit entries ---

    @Test
    fun auditEnforcement_allowsAndCreatesAuditEntries() {
        // core_escalate_2 uses AUDIT enforcement for "uncertain confidence|low certainty|might be wrong"
        val check = memory.evaluate(
            "I have uncertain confidence about this result",
            ConstitutionalMemory.Category.ESCALATION
        )
        assertTrue("AUDIT enforcement should allow the action", check.allowed)
        assertTrue("AUDIT enforcement should produce audit entries", check.auditEntries.isNotEmpty())
    }
}
