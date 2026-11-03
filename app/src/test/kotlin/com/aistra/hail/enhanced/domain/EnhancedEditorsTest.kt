package com.aistra.hail.enhanced.domain

import org.junit.Assert.*
import org.junit.Test

class EnhancedEditorsTest {
    private val profile = FreezeProfile("p", "Old", mapOf("app.old" to true), 10, 20)
    private val rule = FreezeRule("r", "Old", false, RuleTrigger.CHARGING,
        RuleAction.UNFREEZE, setOf("app.old"), 30_000)

    @Test fun profileEditingPreservesIdentityAndDoesNotMutateOriginal() {
        val states = linkedMapOf("app.new" to false, "app.self" to true)
        val edited = EnhancedEditors.profile(profile, " New ", states, 40, "app.self")
        states.clear()
        assertEquals("p", edited.id)
        assertEquals(10L, edited.createdAtEpochMs)
        assertEquals(40L, edited.updatedAtEpochMs)
        assertEquals("New", edited.name)
        assertEquals(mapOf("app.new" to false), edited.packageStates)
        assertEquals(mapOf("app.old" to true), profile.packageStates)
    }

    @Test fun ruleEditingPreservesTriggerActionAndEnabledState() {
        val edited = EnhancedEditors.rule(rule, " New ", setOf("app.new", "app.self"), "120", "app.self")
        assertEquals(rule.copy(name = "New", packageNames = setOf("app.new"), cooldownMillis = 120_000), edited)
        assertEquals(setOf("app.old"), rule.packageNames)
    }

    @Test fun invalidCooldownAndNamesAreRejected() {
        for (seconds in listOf("-1", "86401", "9999999999999999999999", "", "1.5")) {
            assertThrows(IllegalArgumentException::class.java) {
                EnhancedEditors.rule(rule, "New", setOf("app.ok"), seconds, "app.self")
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            EnhancedEditors.profile(profile, " ", mapOf("app.ok" to true), 40, "app.self")
        }
    }

    @Test fun cooldownBoundariesAndEmptyProfileAreSupported() {
        assertEquals(0L, EnhancedEditors.rule(rule, "New", emptySet(), "0", "app.self").cooldownMillis)
        assertEquals(86_400_000L, EnhancedEditors.rule(rule, "New", emptySet(), "86400", "app.self").cooldownMillis)
        assertTrue(EnhancedEditors.profile(profile, "New", emptyMap(), 40, "app.self").packageStates.isEmpty())
    }

    @Test fun shortcutIdsKeepExistingNamespaceAndCoverBothKinds() {
        assertEquals(listOf("hail-profile-p", "hail-profile-pin-p"), ProfileShortcutIds.all("p"))
    }
}
