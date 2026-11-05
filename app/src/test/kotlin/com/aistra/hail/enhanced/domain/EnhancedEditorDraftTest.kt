package com.aistra.hail.enhanced.domain

import org.junit.Assert.*
import org.junit.Test

class EnhancedEditorDraftTest {
    @Test fun roundTripPreservesUnsavedInvalidInputSelectionAndOriginal() {
        val original = FreezeRule("r", "Original", true, RuleTrigger.CHARGING, RuleAction.FREEZE, setOf("app.one"), 30_000)
        val draft = EnhancedEditorDraft.forRule(original, false).copy(name = "Unsaved", cooldown = "-",
            states = mapOf("app.two" to false), search = "two", enabled = false,
            trigger = RuleTrigger.NOT_CHARGING, action = RuleAction.UNFREEZE)
        assertEquals(draft, EnhancedEditorDraft.decode(draft.encode()))
        assertEquals(original, draft.originalRule())
        assertThrows(IllegalArgumentException::class.java) { draft.buildRule("app.self") }
    }

    @Test fun completeRuleEditingChangesTriggerActionAndEnabledWithoutChangingIdentity() {
        val original = FreezeRule("r", "Original", true, RuleTrigger.CHARGING, RuleAction.FREEZE, setOf("app.one"), 30_000)
        val draft = EnhancedEditorDraft.forRule(original, false).copy(trigger = RuleTrigger.SCREEN_OFF,
            action = RuleAction.UNFREEZE, enabled = false, cooldown = "60")
        assertEquals(original.copy(trigger = RuleTrigger.SCREEN_OFF, action = RuleAction.UNFREEZE,
            enabled = false, cooldownMillis = 60_000), draft.buildRule("app.self"))
    }

    @Test fun profileRoundTripKeepsFrozenAndThawedTargetsAndCreationTime() {
        val original = FreezeProfile("p", "Original", mapOf("app.one" to true), 10, 20)
        val draft = EnhancedEditorDraft.forProfile(original, false).copy(name = " New ",
            states = mapOf("app.two" to false, "app.self" to true))
        assertEquals(draft, EnhancedEditorDraft.decode(draft.encode()))
        assertEquals(FreezeProfile("p", "New", mapOf("app.two" to false), 10, 40), draft.buildProfile(40, "app.self"))
    }
}
