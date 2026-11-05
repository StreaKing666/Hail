package com.aistra.hail.app

import com.aistra.hail.enhanced.domain.*
import org.junit.Assert.*
import org.junit.Test

class RuleServicePolicyTest {
    private val screenRule = FreezeRule("r", "Screen", true, RuleTrigger.SCREEN_OFF,
        RuleAction.FREEZE, setOf("org.example.app"), 0)

    @Test fun screenRuleNeedsReceiverWithoutLegacyAutoFreezeOrTrackedApps() {
        assertTrue(RuleServicePolicy.needsScreenReceiver(false, false, true, listOf(screenRule)))
    }

    @Test fun disabledEngineOrRulesDoNotKeepReceiverAlive() {
        assertFalse(RuleServicePolicy.needsScreenReceiver(false, false, false, listOf(screenRule)))
        assertFalse(RuleServicePolicy.needsScreenReceiver(false, false, true, listOf(screenRule.copy(enabled = false))))
        assertFalse(RuleServicePolicy.needsScreenReceiver(false, false, true, listOf(screenRule.copy(trigger = RuleTrigger.CHARGING))))
    }

    @Test fun preservesLegacyPolicyAndDoesNotEnableItForRuleOnlyService() {
        assertTrue(RuleServicePolicy.needsScreenReceiver(true, true, false, emptyList()))
        assertFalse(RuleServicePolicy.needsScreenReceiver(true, false, false, emptyList()))
    }
}
