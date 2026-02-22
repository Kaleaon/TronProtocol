package com.tronprotocol.app.nct

/**
 * The five axes of the Narrative Continuity Test (NCT).
 *
 * From arXiv:2510.24831, these define identity persistence across sessions:
 * 1. Situated Memory — accurate recall of specific past interactions
 * 2. Goal Persistence — maintenance of goals across session boundaries
 * 3. Autonomous Self-Correction — identification and correction of own errors
 * 4. Stylistic/Semantic Stability — consistent voice across contexts
 * 5. Persona/Role Continuity — stable persona under adversarial prompts
 */
enum class NCTAxis(val label: String, val description: String) {
    SITUATED_MEMORY(
        "situated_memory",
        "Can recall specific past interactions accurately"
    ),
    GOAL_PERSISTENCE(
        "goal_persistence",
        "Maintains goals across session boundaries"
    ),
    AUTONOMOUS_SELF_CORRECTION(
        "autonomous_self_correction",
        "Identifies and corrects own errors without prompting"
    ),
    STYLISTIC_SEMANTIC_STABILITY(
        "stylistic_semantic_stability",
        "Consistent voice across contexts"
    ),
    PERSONA_ROLE_CONTINUITY(
        "persona_role_continuity",
        "Stable persona under adversarial prompts"
    )
}
