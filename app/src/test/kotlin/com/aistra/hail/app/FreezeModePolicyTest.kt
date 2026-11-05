package com.aistra.hail.app

import org.junit.Assert.*
import org.junit.Test

class FreezeModePolicyTest {
    private val modes = listOf("su_disable", "su_hide", "su_suspend", "owner_hide", "owner_suspend", "privapp_disable")

    @Test fun reversibleStateRemainsVisibleAfterChangingModes() {
        assertTrue(FreezeModePolicy.isFrozen("su_disable", false, true, false, false))
        assertTrue(FreezeModePolicy.isFrozen("owner_suspend", true, false, false, false))
        assertFalse(FreezeModePolicy.isFrozen("su_disable", false, false, false, true))
        assertTrue(FreezeModePolicy.isFrozen("su_stop", false, false, false, true))
    }

    @Test fun thawPlansAllObservedLayersUsingOnlySelectedAuthority() {
        assertEquals(listOf("su_disable", "su_hide", "su_suspend"),
            FreezeModePolicy.thawModes("su_disable", true, true, true, modes))
    }

    @Test fun unsupportedLayerDoesNotPretendToThawOrSwitchPrivileges() {
        assertThrows(IllegalStateException::class.java) {
            FreezeModePolicy.thawModes("privapp_disable", false, true, false, modes)
        }
    }
}
