package com.aistra.hail.app

import com.aistra.hail.enhanced.domain.FreezeRule
import com.aistra.hail.enhanced.domain.RuleTrigger

internal object RuleServicePolicy {
    fun needsScreenReceiver(legacyEnabled: Boolean, hasEligibleApps: Boolean,
                            rulesEnabled: Boolean, rules: List<FreezeRule>): Boolean =
        (legacyEnabled && hasEligibleApps) ||
            (rulesEnabled && rules.any { it.enabled && it.trigger == RuleTrigger.SCREEN_OFF })
}
